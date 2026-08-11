import { ProtobufField, WireTypes } from './common';

export class ProtobufDecoder {
  constructor(readonly buffer: Uint8Array, private offset: number) {
  }

  readonly hasMore = (): boolean => {
    return this.offset < this.buffer.length;
  };

  readonly nextField = (): ProtobufField<any, any> => {
    const tag = this.readVarIntUnsigned();
    const fieldNumber = tag >>> 3;
    const wireType = tag & 0x7;
    if (wireType == WireTypes.VAR_INT) {
      return new ProtobufField(fieldNumber, WireTypes.VAR_INT, this.readVarIntUnsigned());
    } else if (wireType == WireTypes.FIXED_64) {
      return new ProtobufField(fieldNumber, WireTypes.FIXED_64, this.readFixed64AsU8());
    } else if (wireType == WireTypes.UINT_32) {
      return new ProtobufField(fieldNumber, WireTypes.UINT_32, this.readFixed32Uint32());
    } else if (wireType == WireTypes.FIXED_LENGTH) {
      const length = this.readVarIntUnsigned();
      if (this.offset + length > this.buffer.length) {
        throw new Error(`Not enough buffer, require ${length} bytes for field ${fieldNumber} type ${wireType}`);
      }
      const data = new Uint8Array(this.buffer.buffer, this.buffer.byteOffset + this.offset, length);
      this.offset += length;
      return new ProtobufField(fieldNumber, WireTypes.FIXED_LENGTH, data);
    } else {
      throw new Error(`Unsupported wire type ${wireType}`);
    }
  };

  private readonly readVarIntUnsigned = (): number => {
    let value = 0;
    let bytesRead = 0;
    let shift = 0;

    while (true) {
      if (this.offset + bytesRead >= this.buffer.length) {
        throw new Error('Buffer too short to parse varint');
      }

      const byte = this.buffer[this.offset + bytesRead];
      bytesRead++;

      // Add the 7 bits of data to our value
      const chunk = shift < 28 ? (byte & 0x7F) << shift : (byte & 0x7F) * (2 ** (shift));
      value += chunk;
      shift += 7;

      // If the MSB is 0, this is the last byte
      if ((byte & 0x80) === 0) {
        break;
      }

      // Check for varint overflow (protobuf varints are max 64 bits)
      if (shift >= 64) {
        throw new Error('Varint too large');
      }
    }

    // Return tuple of [parsed value, number of bytes consumed]
    this.offset += bytesRead;
    return value;
  };

  private readonly readFixed64AsU8 = (): Uint8Array => {
    const requiredBytes = 8;

    if (this.offset + requiredBytes > this.buffer.length) {
      throw new Error(`Buffer too short: need ${requiredBytes} bytes, got ${this.buffer.length - this.offset}`);
    }

    const data = new Uint8Array(this.buffer.buffer, this.buffer.byteOffset + this.offset, requiredBytes);
    this.offset += requiredBytes;
    return data;
  };

  private readonly readFixed32Uint32 = (): number => {
    const requiredBytes = 4;

    if (this.offset + requiredBytes > this.buffer.length) {
      throw new Error(`Buffer too short to parse double: need ${requiredBytes} bytes, got ${this.buffer.length - this.offset}`);
    }

    // Create a view into the buffer and read as float64
    const view = new DataView(this.buffer.buffer, this.buffer.byteOffset + this.offset, requiredBytes);
    const value = view.getUint32(0, true); // true for little-endian

    this.offset += requiredBytes;
    return value;
  };

  readonly readAllField = (): ProtobufField<any, any>[] => {
    const res = [];
    while (this.hasMore()) {
      res.push(this.nextField());
    }
    return res;
  };

  static readonly toJson = (fields: ProtobufField<any, any>[]): any => {
    try {
      const res: any = {};
      for (let i = 0; i < fields.length; i++) {
        const field = fields[i];
        const value = {
          type: field.wireType,
          value: field.value,
        };
        if (field.wireType == WireTypes.FIXED_LENGTH) {
          try {
            value.value = ProtobufDecoder.toJson(new ProtobufDecoder(field.value as Uint8Array, 0).readAllField());
          } catch (ignore) {
            value.value = String.fromCharCode(...field.value);
          }
        }
        if (Array.isArray(res[`f${field.fieldNumber}`])) {
          res[`f${field.fieldNumber}`].push(value);
        } else if (res[`f${field.fieldNumber}`]) {
          res[`f${field.fieldNumber}`] = [res[`f${field.fieldNumber}`], value];
        } else {
          res[`f${field.fieldNumber}`] = value;
        }
      }
      return res;
    } catch (ignore) {
      return null;
    }
  };
}
