import { ProtobufField, WireTypes } from './common';

// eslint-disable-next-line @typescript-eslint/no-extraneous-class
export class ProtobufEncoder {
  private static calculateFieldSize(field: ProtobufField<any, any>): number {
    const tagSize = this.calculateVarIntSize((field.fieldNumber << 3) | field.wireType);

    switch (field.wireType) {
      case WireTypes.VAR_INT:
        return tagSize + this.calculateVarIntSize(field.value);
      case WireTypes.FIXED_64:
        return tagSize + 8;
      case WireTypes.UINT_32:
        return tagSize + 4;
      case WireTypes.FIXED_LENGTH:
        return tagSize + this.calculateVarIntSize(field.value.length) + field.value.length;
      default:
        throw new Error(`Unsupported wire type ${field.wireType}`);
    }
  }

  private static calculateVarIntSize(value: number): number {
    let size = 0;
    do {
      size++;
      value = ProtobufEncoder.shiftRight(value, 7);
    } while (value !== 0);
    return size;
  }

  private static writeVarInt(buffer: Uint8Array, offset: number, value: number): number {
    let currentOffset = offset;
    while (true) {
      const byte = value & 0x7F;
      value = ProtobufEncoder.shiftRight(value, 7);
      if (value === 0) {
        buffer[currentOffset++] = byte;
        break;
      }
      buffer[currentOffset++] = byte | 0x80;
    }
    return currentOffset;
  }

  private static shiftRight(num: number, shiftAmount: number) {
    // Validate inputs
    if (!Number.isSafeInteger(num) || !Number.isInteger(shiftAmount)) {
      throw new Error(`Number and shift amount must be (safe) integers ${num} >> ${shiftAmount}`);
    }
    if (shiftAmount < 0) {
      throw new Error('Shift amount must be non-negative');
    }

    // Check if number fits within 32-bit signed integer range (-2^31 to 2^31 - 1)
    const is32Bit = num >= -2147483648 && num <= 2147483647;

    if (is32Bit) {
      // Perform normal 32-bit signed right shift
      return num >>> shiftAmount;
    } else {
      // Handle numbers larger than 32-bit (up to 2^53 - 1)
      // Split into high and low 32-bit parts
      const low = num & 0xFFFFFFFF; // Lower 32 bits (unsigned)
      const high = Math.floor(num / 0x100000000); // Higher bits (num / 2^32)

      const shift = shiftAmount;

      // Perform right shift
      // Low bits shift right, high bits shift into low's upper bits
      const shiftedLow = (low >>> shift) | (high << (32 - shift));
      const shiftedHigh = high >>> shift;

      // Combine high and low parts
      // Return result, ensuring it’s within safe integer range
      return (shiftedHigh * 0x100000000) + (shiftedLow >>> 0);
    }
  }

  private static writeFixed64(buffer: Uint8Array, offset: number, value: Uint8Array): number {
    buffer.set(value, offset);
    return offset + 8;
  }

  private static writeFixed32(buffer: Uint8Array, offset: number, value: number): number {
    const view = new DataView(buffer.buffer, buffer.byteOffset + offset, 4);
    view.setUint32(0, value, true);
    return offset + 4;
  }

  private static writeFixedLength(buffer: Uint8Array, offset: number, value: Uint8Array): number {
    offset = this.writeVarInt(buffer, offset, value.length);
    buffer.set(value, offset);
    return offset + value.length;
  }

  static encode(fields: ProtobufField<any, any>[]): Uint8Array {
    // Pre-calculate total size needed
    let totalSize = 0;
    for (const field of fields) {
      totalSize += this.calculateFieldSize(field);
    }

    const buffer = new Uint8Array(totalSize);
    let offset = 0;

    for (const field of fields) {
      // Write tag (field number and wire type)
      offset = this.writeVarInt(buffer, offset, (field.fieldNumber << 3) | field.wireType);

      // Write value based on wire type
      switch (field.wireType) {
        case WireTypes.VAR_INT:
          offset = this.writeVarInt(buffer, offset, field.value);
          break;
        case WireTypes.FIXED_64:
          offset = this.writeFixed64(buffer, offset, field.value);
          break;
        case WireTypes.UINT_32:
          offset = this.writeFixed32(buffer, offset, field.value);
          break;
        case WireTypes.FIXED_LENGTH:
          offset = this.writeFixedLength(buffer, offset, field.value);
          break;
        default:
          throw new Error(`Unsupported wire type ${field.wireType}`);
      }
    }

    return buffer;
  }
}
