
namespace java ch.usi.da.dmap.thrift.gen

exception MapError {
	1: string errorMsg,
}

exception WrongPartition {
	1: string errorMsg,
}

enum CommandType {
    GET = 0,
    PUT = 1,
    REMOVE = 2,
    SIZE = 3,
    CLEAR = 4,
    CONTAINSVALUE = 5,
    FIRSTKEY = 6,
    LASTKEY = 7;
}

struct Command {
  1: i64 id,
  2: CommandType type,
  3: optional binary key,
  4: optional binary value,
  5: optional i64 snapshot,
  6: i64 partition_version,
}

struct Response {
  1: i64 id,
  2: i64 count,
  3: optional binary key,
  4: optional binary value,
}

enum RangeType {
    CREATERANGE = 1,
    PERSISTRANGE = 2,
    GETRANGE = 3,
    DELETERANGE = 4
}

struct RangeCommand {
  1: i64 id,
  2: RangeType type,
  3: optional binary fromkey,
  4: optional binary tokey,
  5: optional i32 fromid,
  6: optional i32 toid,
  7: optional i64 snapshot,
  8: i64 partition_version,
}

struct RangeResponse {
  1: i64 id,
  2: i64 count,
  3: i64 snapshot,
  4: optional binary values
}

struct Partition {
  1: i64 version,
  2: map<i32,set<string>> partitions
}

service Dmap {
	Response execute(1: Command cmd) throws (1: MapError e, 2: WrongPartition p),
	RangeResponse range(1: RangeCommand cmd) throws (1: MapError e, 2: WrongPartition p),
	Partition partition(1: i64 id)
}
