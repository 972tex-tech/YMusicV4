export enum WireTypes {
  VAR_INT = 0,
  FIXED_64 = 1,
  FIXED_LENGTH = 2,
  UINT_32 = 5,
}

export class ProtobufField<
  T extends WireTypes,
  V extends (T extends (WireTypes.FIXED_LENGTH | WireTypes.FIXED_64) ? Uint8Array : number),
> {
  constructor(readonly fieldNumber: number, readonly wireType: T, public value: V) {
  }
}
