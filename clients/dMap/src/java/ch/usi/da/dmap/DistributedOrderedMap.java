package ch.usi.da.dmap;
/* 
 * Copyright (c) 2017 Università della Svizzera italiana (USI)
 * 
 * This file is part of URingPaxos.
 *
 * URingPaxos is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * URingPaxos is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with URingPaxos.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.io.IOException;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Logger;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;

import ch.usi.da.dmap.thrift.gen.Command;
import ch.usi.da.dmap.thrift.gen.CommandType;
import ch.usi.da.dmap.thrift.gen.Dmap;
import ch.usi.da.dmap.thrift.gen.MapError;
import ch.usi.da.dmap.thrift.gen.Partition;
import ch.usi.da.dmap.thrift.gen.RangeCommand;
import ch.usi.da.dmap.thrift.gen.RangeResponse;
import ch.usi.da.dmap.thrift.gen.RangeType;
import ch.usi.da.dmap.thrift.gen.Response;
import ch.usi.da.dmap.thrift.gen.WrongPartition;
import ch.usi.da.dmap.utils.Pair;
import ch.usi.da.dmap.utils.Utils;
import ch.usi.da.paxos.lab.DummyWatcher;


/**
 * Name: DistributedOrderedMap<br>
 * Description: <br>
 * 
 * Creation date: Jan 28, 2017<br>
 * $Id$
 * 
 * Notes:
 * - Not using AbstractMap because it implements some methods inefficient for distributed operations.
 *
 * 
 * @author Samuel Benz benz@geoid.ch
 */
public class DistributedOrderedMap<K extends Comparable<K>,V> implements SortedMap<K,V>, Cloneable, java.io.Serializable {
	
	private final static long serialVersionUID = -8575201903369745596L;

	private final static Logger logger = Logger.getLogger(DistributedOrderedMap.class);
	
	private final Comparator<? super K> comparator;
	
	private final Random rand = new Random();
		
	private ZooKeeper zoo;
		
	private final int get_range_size = 100;

	private long partition_version = 0;
	
	private SortedMap<Integer,Set<String>> partitions = new TreeMap<Integer,Set<String>>();
	
	private Map<Integer,List<Dmap.Client>> clients = new HashMap<Integer,List<Dmap.Client>>();

	public final String mapID;

	public DistributedOrderedMap(String mapID, String zookeeper_host) {
		this(mapID,zookeeper_host,null);
	}
	
	public DistributedOrderedMap(String mapID, String zookeeper_host, Comparator<? super K> comparator) {
		this.comparator = comparator; // important at replica
		this.mapID = mapID;
		final String path = "/dmap/" + mapID;
		try {
			zoo = new ZooKeeper(zookeeper_host,3000,new DummyWatcher());
			// lookup one replica to initialize the partitions map
			List<String> replicas = zoo.getChildren(path,false);
			if(replicas.isEmpty()){
				logger.error(this + " can not locate any replica!");
			}else{
				int pos = rand.nextInt(replicas.size());
				byte[] a = zoo.getData(path + "/" + replicas.get(pos),false,null);
				String[] as = new String(a).split(";");
				String ip = as[0];
				int port = Integer.parseInt(as[1]);
				TTransport transport = new TSocket(ip,port);
				TProtocol protocol = new TBinaryProtocol(transport);
				Dmap.Client client = new Dmap.Client(protocol);
			    transport.open();
				readPartitions(client);
				if(!partitions.isEmpty()){
					logger.info(this + " initialized.");
				}else{
					logger.error(this + " could not initalze the partition map!");
				}
			}
		} catch (IOException | KeeperException | InterruptedException e) {
			logger.error(this + " ZooKeeper init error!",e);
		} catch (TTransportException e) {
			logger.error(this + " Thrift init error!",e);
		}

	}
	
	private Dmap.Client getClient(){
		return getClient(null);
	}

	private Dmap.Client getClient(Object key){
		if(key == null){ // random partition
			return getClient(rand.nextInt());
		}else{
			return getClient(key.hashCode());
		}
	}
	
	private Dmap.Client getClient(int hash){
		Dmap.Client client = null;
		int partition = 0;
		SortedMap<Integer,Set<String>> tailMap = partitions.tailMap(hash);
		partition = tailMap.isEmpty() ? partitions.firstKey() : tailMap.firstKey();

		if(!clients.containsKey(partition)){
			clients.put(partition,new ArrayList<Dmap.Client>());
		}
		List<Dmap.Client> c = clients.get(partition);
		if(c.isEmpty()){
			Set<String> caddrs = partitions.get(partition);
			for(String addr : caddrs){
				String[] as = new String(addr).split(";");
				String ip = as[0];
				int port = Integer.parseInt(as[1]);
				TTransport transport = new TSocket(ip,port);
				TProtocol protocol = new TBinaryProtocol(transport);
				client = new Dmap.Client(protocol);
				try {
					//TODO: how to handle broken connections
					transport.open();
				} catch (TTransportException e) {
					logger.error(this,e);
				}
				c.add(client);				
			}
		}else{
			int pos = rand.nextInt(c.size());			
			client = c.get(pos);
		}
		return client;
	}
	
	private long getCmdID(){
		return rand.nextLong();
	}
	
	private void readPartitions(Dmap.Client client){
		try {
			logger.info(this + " request partition map.");
			Partition p = client.partition(getCmdID());
			if(p.getVersion() != partition_version){
				partitions.clear();
				partitions.putAll(p.getPartitions());
				partition_version = p.getVersion();
				logger.info(this + " installed new partition map version " + partition_version + " (" + partitions + ")");
			}else{
				logger.info(this + " reveived same version partition map.");
			}
		} catch (TException e) {
			logger.error(this,e);
		}
	}
	
	
	// single partition commands

	
	@Override
	public V get(Object key) {
		return get(key,null);
	}

	@SuppressWarnings("unchecked")
	private V get(Object key,Long snapshotID) {
		if(key == null){ throw new NullPointerException(); }
		Response ret = null;
		try {
			Command cmd = new Command();
			cmd.setId(getCmdID());
			cmd.setType(CommandType.GET);
			cmd.setKey(Utils.getBuffer(key));
			cmd.setPartition_version(partition_version);
			if(snapshotID != null){
				cmd.setSnapshot(snapshotID);
			}
			ret = getClient(key).execute(cmd);
		} catch (MapError e){
			logger.error(this + " " + e.errorMsg);
		} catch (WrongPartition p){
			readPartitions(getClient());
			return get(key,snapshotID);
		} catch (TException | IOException e) {
			logger.error(this,e);
		}
		if(ret != null && ret.isSetValue()){
			try {
				return (V) Utils.getObject(ret.getValue());
			} catch (ClassNotFoundException | IOException e) {
				logger.error(this,e);
			}
		}
		return null;
	}

	@Override
	public V put(K key,V value) {
		return put(key,value,null);
	}
	
	@SuppressWarnings("unchecked")
	private V put(K key,V value,Long snapshotID) {
		if(key == null){ throw new NullPointerException(); }
		Response ret = null;
		try {
			Command cmd = new Command();
			cmd.setId(getCmdID());
			cmd.setType(CommandType.PUT);
			cmd.setKey(Utils.getBuffer(key));
			cmd.setValue(Utils.getBuffer(value));
			cmd.setPartition_version(partition_version);
			if(snapshotID != null){
				cmd.setSnapshot(snapshotID);
			}
			ret = getClient(key).execute(cmd);
		} catch (MapError e){
			logger.error(this + " " + e.errorMsg);
		} catch (WrongPartition p){
			readPartitions(getClient());
			return put(key,value,snapshotID);
		} catch (TException | IOException e) {
			logger.error(this,e);
		}
		if(ret != null && ret.isSetValue()){
			try {
				return (V) Utils.getObject(ret.getValue());
			} catch (ClassNotFoundException | IOException e) {
				logger.error(this,e);
			}
		}
		return null;
	}

	@Override
	public V remove(Object key) {
		return remove(key,null);
	}

	@SuppressWarnings("unchecked")
	private V remove(Object key,Long snapshotID) {
		if(key == null){ throw new NullPointerException(); }
		Response ret = null;
		try {
			Command cmd = new Command();
			cmd.setId(getCmdID());
			cmd.setType(CommandType.REMOVE);
			cmd.setKey(Utils.getBuffer(key));
			cmd.setPartition_version(partition_version);
			if(snapshotID != null){
				cmd.setSnapshot(snapshotID);
			}
			ret = getClient(key).execute(cmd);
		} catch (MapError e){
			logger.error(this + " " + e.errorMsg);
		} catch (WrongPartition p){
			readPartitions(getClient());
			return remove(key,snapshotID);
		} catch (TException | IOException e) {
			logger.error(this,e);
		}
		if(ret != null && ret.isSetValue()){
			try {
				return (V) Utils.getObject(ret.getValue());
			} catch (ClassNotFoundException | IOException e) {
				logger.error(this,e);
			}
		}
		return null;
	}

	@Override
	public boolean containsKey(Object key) {
		return containsKey(key,null);
	}

	private boolean containsKey(Object key,Long snapshotID) {
		if(key == null){ throw new NullPointerException(); }
		V v = get(key,snapshotID);
		if(v != null){
			return true;
		}else{
			return false;
		}
	}

	@Override
	public void putAll(Map<? extends K, ? extends V> m) {
		putAll(m,null);
	}
	
	private void putAll(Map<? extends K, ? extends V> m,Long snapshotID) {
		for(Map.Entry<? extends K, ? extends V> e : m.entrySet()){
			put(e.getKey(),e.getValue(),snapshotID);
		}
	}

	
	// multi-partition commands
	

	private long sizeLong(Long snapshotID) {
		Response ret = null;
		try {
			Command cmd = new Command();
			cmd.setId(getCmdID());
			cmd.setType(CommandType.SIZE);
			cmd.setPartition_version(partition_version);
			if(snapshotID != null){
				cmd.setSnapshot(snapshotID);
			}
			ret = getClient().execute(cmd);
		} catch (MapError e){
			logger.error(this + " " + e.errorMsg);
		} catch (WrongPartition p){
			readPartitions(getClient());
			return sizeLong(snapshotID);
		} catch (TException e) {
			logger.error(this,e);
		}
		if(ret != null && ret.isSetCount()){
				return ret.getCount();
		}
		return 0;
	}

	@Override
	public int size() {
		return (int) sizeLong(null);
	}
	
	@Override
	public boolean isEmpty() {
		return size() == 0;
	}

	@Override
	public boolean containsValue(Object value) {
		return containsValue(value,null);
	}
	
	private boolean containsValue(Object value,Long snapshotID) {
		if(value == null){ throw new NullPointerException(); }
		Response ret = null;
		try {
			Command cmd = new Command();
			cmd.setId(getCmdID());
			cmd.setType(CommandType.CONTAINSVALUE);
			cmd.setValue(Utils.getBuffer(value));
			cmd.setPartition_version(partition_version);
			if(snapshotID != null){
				cmd.setSnapshot(snapshotID);
			}
			ret = getClient().execute(cmd);
		} catch (MapError e){
			logger.error(this + " " + e.errorMsg);
		} catch (WrongPartition p){
			readPartitions(getClient());
			return containsValue(value,snapshotID);
		} catch (TException | IOException e) {
			logger.error(this,e);
		}
		if(ret != null && ret.getCount() > 0){
			return true;
		}
		return false;
	}

	@Override
	public void clear() {
		clear(null);
	}
	
	private void clear(Long snapshotID){
		try {
			Command cmd = new Command();
			cmd.setId(getCmdID());
			cmd.setType(CommandType.CLEAR);
			cmd.setPartition_version(partition_version);
			if(snapshotID != null){
				cmd.setSnapshot(snapshotID);
			}
			getClient().execute(cmd);
		} catch (MapError e){
			logger.error(this + " " + e.errorMsg);
		} catch (WrongPartition p){
			readPartitions(getClient());
			clear(snapshotID);
		} catch (TException e) {
			logger.error(this,e);
		}
	}

	@Override
	public K firstKey() {
		return firstKey(null);
	}

	@SuppressWarnings("unchecked")
	private K firstKey(Long snapshotID){
		Response ret = null;
		try {
			Command cmd = new Command();
			cmd.setId(getCmdID());
			cmd.setType(CommandType.FIRSTKEY);
			cmd.setPartition_version(partition_version);
			if(snapshotID != null){
				cmd.setSnapshot(snapshotID);
			}
			ret = getClient().execute(cmd);
		} catch (MapError e){
			logger.error(this + " " + e.errorMsg);
		} catch (WrongPartition p){
			readPartitions(getClient());
			return firstKey(snapshotID);
		} catch (TException e) {
			logger.error(this,e);
		}
		if(ret != null && ret.isSetKey()){
			try {
				return (K) Utils.getObject(ret.getKey());
			} catch (ClassNotFoundException | IOException e) {
				logger.error(this,e);
			}
		}
		throw new NoSuchElementException();
	}

	@Override
	public K lastKey() {
		return lastKey(null);
	}

	@SuppressWarnings("unchecked")
	private K lastKey(Long snapshotID){
		Response ret = null;
		try {
			Command cmd = new Command();
			cmd.setId(getCmdID());
			cmd.setType(CommandType.LASTKEY);
			cmd.setPartition_version(partition_version);
			if(snapshotID != null){
				cmd.setSnapshot(snapshotID);
			}
			ret = getClient().execute(cmd);
		} catch (MapError e){
			logger.error(this + " " + e.errorMsg);
		} catch (WrongPartition p){
			readPartitions(getClient());
			return lastKey(snapshotID);
		} catch (TException e) {
			logger.error(this,e);
		}
		if(ret != null && ret.isSetKey()){
			try {
				return (K) Utils.getObject(ret.getKey());
			} catch (ClassNotFoundException | IOException e) {
				logger.error(this,e);
			}
		}
		throw new NoSuchElementException();
	}

	
	// global snapshot/iterator commands


	private SortedMap<K,V> subMap(K fromKey,K toKey,Long snapshotID) {
		RangeResponse ret = null;
		SortedMap<K,V> submap = null;		
		try {
			RangeCommand cmd = new RangeCommand();
			cmd.setId(getCmdID());
			cmd.setType(RangeType.CREATERANGE);
			cmd.setPartition_version(partition_version);
			if(fromKey != null){
				cmd.setFromkey(Utils.getBuffer(fromKey));
			}
			if(toKey != null){
				cmd.setTokey(Utils.getBuffer(toKey));
			}
			if(snapshotID != null){ // snapshot of a snapshot?
				cmd.setSnapshot(snapshotID);
			}
			ret = getClient().range(cmd);
			if(ret.isSetSnapshot()){
				snapshotID = ret.getSnapshot();
				Map<Integer,Long> partitions_size = new HashMap<Integer,Long>();
				for(Entry<Integer,Set<String>> e : partitions.entrySet()){
					// get size of every partition slice
					RangeResponse r = null;
					while(r == null){
						RangeCommand s = new RangeCommand();
						s.setId(getCmdID());
						s.setType(RangeType.PARTITIONSIZE);
						s.setPartition_version(partition_version);
						s.setSnapshot(snapshotID);
						try{
							r = getClient(e.getKey()).range(s);
						}catch(MapError me){
							// retry snapshot must exist eventually
							Thread.sleep(50);
						}
					}
					partitions_size.put(e.getKey(),r.getCount());
				}
				submap = new SnapshotView(snapshotID,partitions_size);
				logger.debug(this + " created snapshot view " + snapshotID);
			}
		} catch (MapError e){
			logger.error(this + " " + e.errorMsg);
		} catch (WrongPartition p){
			readPartitions(getClient());
			return subMap(fromKey,toKey,snapshotID);
		} catch (TException | IOException | InterruptedException e) {
			logger.error(this,e);
		}
		return submap;
	}

	public void removeSnapshot(Long snapshotID){
		RangeCommand cmd = new RangeCommand();
		cmd.setId(getCmdID());
		cmd.setType(RangeType.DELETERANGE);
		cmd.setSnapshot(snapshotID);
		cmd.setPartition_version(partition_version);
		try {
			getClient().range(cmd);
			logger.debug(this + " released snapshot " + snapshotID);	
		} catch (MapError e) {
			logger.error(this + " error!",e);
		} catch (WrongPartition p){
			readPartitions(getClient());
			removeSnapshot(snapshotID);
		} catch (TException e) {
			logger.error(this + " error!",e);
		}				
	}

	@Override
	public SortedMap<K,V> subMap(K fromKey, K toKey) {
		return subMap(fromKey,toKey,null);
	}
	
	@Override
	public SortedMap<K,V> headMap(K toKey) {
		return subMap(null,toKey,null);
	}

	@Override
	public SortedMap<K,V> tailMap(K fromKey) {
		return subMap(fromKey,null,null);
	}

	@Override
	public Set<K> keySet() {
		return subMap(null,null,null).keySet();
	}

	@Override
	public Collection<V> values() {
		return subMap(null,null,null).values();
	}

	@Override
	public Set<java.util.Map.Entry<K,V>> entrySet() {
		return subMap(null,null,null).entrySet();
	}

	
	// others
	
	
	@Override
	public Comparator<? super K> comparator() {
		return comparator;
	}
	
	@Override
	public String toString(){
		return "Distributed Ordered Map: " + mapID;
	}
	
	@Override
	public boolean equals(Object obj) {
		if(obj instanceof DistributedOrderedMap<?,?>){
            if(this.mapID.equals(((DistributedOrderedMap<?,?>) obj).mapID)){
                    return true;
            }
		}
		return false;
	}
	
	@Override
	public int hashCode() {
		return mapID.hashCode();
	}

	
	// view and iterators on a snapshot
	
	
	class SnapshotView implements SortedMap<K,V> {

		public final Map<Integer,Long> partitions_size;
		
		public final long snapshotID;
		
		public final long size;
		
		public SnapshotView(long snapshotID, Map<Integer,Long> partitions_size){
			this.partitions_size = partitions_size;
			this.snapshotID = snapshotID;
			long size = 0;
			for(Long l : partitions_size.values()){
				size += l;
			}
			this.size = size;
		}
		
		@Override
		public int size() {
			return (int) size;
		}

		@Override
		public boolean isEmpty() {
			return size > 0 ? false : true;
		}

		@Override
		public boolean containsKey(Object key) {
			return DistributedOrderedMap.this.containsKey(key,snapshotID);
		}

		@Override
		public boolean containsValue(Object value) {
			return DistributedOrderedMap.this.containsValue(value,snapshotID);
		}

		@Override
		public V get(Object key) {
			return DistributedOrderedMap.this.get(key,snapshotID);
		}

		@Override
		public V put(K key,V value) {
			throw new IllegalArgumentException();
			//return DistributedOrderedMap.this.put(key,value,snapshotID);
		}

		@Override
		public V remove(Object key) {
			throw new IllegalArgumentException();
			//return DistributedOrderedMap.this.remove(key,snapshotID);
		}

		@Override
		public void putAll(Map<? extends K, ? extends V> m) {
			throw new IllegalArgumentException();
			//DistributedOrderedMap.this.putAll(m,snapshotID);
		}

		@Override
		public void clear() {
			removeSnapshot();
			//DistributedOrderedMap.this.clear(snapshotID);
		}
		
		public void removeSnapshot(){
			DistributedOrderedMap.this.removeSnapshot(snapshotID);
		}

		@Override
		public Comparator<? super K> comparator() {
			return DistributedOrderedMap.this.comparator();
		}

		@Override
		public SortedMap<K, V> subMap(K fromKey,K toKey) {
			return DistributedOrderedMap.this.subMap(fromKey,toKey,snapshotID);
		}

		@Override
		public SortedMap<K, V> headMap(K toKey) {
			return DistributedOrderedMap.this.subMap(null,toKey,snapshotID);
		}

		@Override
		public SortedMap<K, V> tailMap(K fromKey) {
			return DistributedOrderedMap.this.subMap(fromKey,null,snapshotID);
		}

		@Override
		public K firstKey() {
			return DistributedOrderedMap.this.firstKey(snapshotID);
		}

		@Override
		public K lastKey() {
			return DistributedOrderedMap.this.lastKey(snapshotID);
		}

		@Override
		public Set<K> keySet() {
			//TODO:
			return null;
		}

		@Override
		public Collection<V> values() {
			//TODO:
			return null;
		}

		@Override
		public Set<java.util.Map.Entry<K,V>> entrySet() {
			return new EntrySet(this);
		}

		@Override
		public String toString(){
			return DistributedOrderedMap.this + " snapshot: " + snapshotID + " size: " + size;
		}
	}

	
    class EntrySet extends AbstractSet<Map.Entry<K,V>> {

    	private final SnapshotView view;
    	
    	private final BlockingQueue<Map.Entry<K,V>> queue[];
    	
    	private final long queue_size[];
    	    			    		
    	@SuppressWarnings("unchecked")
		public EntrySet(SnapshotView view){
    		this.view = view;
    		queue = (BlockingQueue<Map.Entry<K,V>>[]) new BlockingQueue[view.partitions_size.size()];
    		queue_size = new long[view.partitions_size.size()];
    		int i = 0;
    		for(Entry<Integer,Long> e : view.partitions_size.entrySet()){
    			queue[i] = new LinkedBlockingQueue<Map.Entry<K,V>>(1000);
    			queue_size[i] = e.getValue();
    			Thread t = new Thread(new QueueFiller(view,queue[i],e));
    			t.start();
    			i++;
    		}
    	}
    	
        public Iterator<Map.Entry<K,V>> iterator() {
            return new EntryIterator<Map.Entry<K,V>>(this,queue,queue_size);
        }

        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry<?,?> entry = (Map.Entry<?,?>) o;
            Object value = entry.getValue();
            V p = view.get(entry.getKey());
            return p != null && p.equals(value);
        }

        public boolean remove(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry<?,?> entry = (Map.Entry<?,?>) o;
            Object value = entry.getValue();
            V p = view.get(entry.getKey());
            if (p != null && p.equals(value)) {
                view.remove(entry.getKey());
                return true;
            }
            return false;
        }

        public int size() {
            return view.size();
        }

        public void clear() {
        	view.clear();
        }
        
    }

    class EntryIterator<T> implements Iterator<T> {
        
    	private final EntrySet set;
    	
    	private long delivered = 0;
    	
        private final BlockingQueue<T> queue[];
        
        private final long queue_delivered[];
        
        private final long queue_size[];
        
        T last = null;
        
        EntryIterator(EntrySet set, BlockingQueue<T> queue[], long[] queue_size) {
        	this.set = set;
        	this.queue = queue;
        	this.queue_size = queue_size;
        	queue_delivered = new long[queue.length];
        }

        public final boolean hasNext() {
        	return delivered < set.view.size ? true : false;
        }

        public void remove() {
        	if(last == null){
        		throw new IllegalStateException();
        	}
        	set.remove(last);
        }
        
		@SuppressWarnings("unchecked")
		@Override
		public T next() {
			T o = null;
			int qi = 0;
			if(delivered < set.view.size){
				for(int i=0;i<queue.length;i++){
					if(queue_delivered[i] < queue_size[i]){
						T s = null;
						while(s == null){
							s = queue[i].peek();
							if(s == null){
								try {
									Thread.sleep(100);
								} catch (InterruptedException e) {
								}
							}
						}
						if(o == null || ((Map.Entry<K,V>) s).getKey().compareTo(((Map.Entry<K,V>)o).getKey()) < 0){
							o = s;
							qi = i;
						}
					}
				}
				delivered++;
				queue_delivered[qi]++;
				return queue[qi].poll();
			}else{
				throw new NoSuchElementException();
			}
		}
    }
    
    class QueueFiller implements Runnable {
    	
    	private final SnapshotView view;

    	private final BlockingQueue<Map.Entry<K,V>> queue;
    	
    	private final Map.Entry<Integer,Long> partitions_size;
    	
    	public QueueFiller(SnapshotView view,BlockingQueue<Map.Entry<K,V>> queue,Map.Entry<Integer,Long> partitions_size){
    		this.view = view;
    		this.queue = queue;
    		this.partitions_size = partitions_size;
    	}
    	
    	@Override
    	public void run() {
    		long snapshotID = view.snapshotID;
    		long size = partitions_size.getValue();
    		long retreived = 0;
    		int from = 0;
    		do{
				try {
	    			RangeCommand cmd = new RangeCommand();
	    			cmd.setId(getCmdID());
	    			cmd.setType(RangeType.GETRANGE);
	    			cmd.setSnapshot(snapshotID);
	    			cmd.setFromid(from);
	    			cmd.setToid(from+get_range_size);
	    			cmd.setPartition_version(partition_version);
	    			from = from+get_range_size;
	    			RangeResponse ret = getClient(partitions_size.getKey()).range(cmd); //TODO: ask multiple replicas with different offset
	    			if(ret != null){
	    				if(ret.isSetValues()){
		    				@SuppressWarnings("unchecked")
							List<Pair<K,V>> sublist = (List<Pair<K,V>>) Utils.getObject(ret.getValues());
		    				for(Pair<K,V> e : sublist){
		    					queue.put(e);
		    					retreived++;
		    				}	    					
	    				}
	    			}
				} catch (MapError e){
					logger.error(view + " error!",e);
				} catch (WrongPartition p){
				} catch (TException | ClassNotFoundException | IOException | InterruptedException e) {
					logger.error(view + " error!",e);
				}
    		}while(retreived < size);
    		queue.add(new Pair<K,V>(null,null)); // poison object
    	}
    }

}
