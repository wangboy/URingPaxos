/**
 * Autogenerated by Thrift Compiler (0.9.1)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package ch.usi.da.smr.thrift.gen;


import java.util.Map;
import java.util.HashMap;
import org.apache.thrift.TEnum;

public enum CmdType implements org.apache.thrift.TEnum {
  GET(0),
  GETRANGE(1),
  PUT(2),
  DELETE(3),
  RESPONSE(4);

  private final int value;

  private CmdType(int value) {
    this.value = value;
  }

  /**
   * Get the integer value of this enum value, as defined in the Thrift IDL.
   */
  public int getValue() {
    return value;
  }

  /**
   * Find a the enum type by its integer value, as defined in the Thrift IDL.
   * @return null if the value is not found.
   */
  public static CmdType findByValue(int value) { 
    switch (value) {
      case 0:
        return GET;
      case 1:
        return GETRANGE;
      case 2:
        return PUT;
      case 3:
        return DELETE;
      case 4:
        return RESPONSE;
      default:
        return null;
    }
  }
}
