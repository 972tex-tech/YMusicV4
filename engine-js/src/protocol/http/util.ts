/** Byte length of str as UTF-8, WITHOUT encoding it — the encoder's own C measurer, so
 *  Content-Length can never disagree with the bytes __utf8Into/__utf8Encode later produce. */
export function utf8ByteLength(str: string): number {
  return __utf8Length(str, 0, str.length);
}

/** Detach a fire-and-forget promise: its failure is deliberate to ignore (documented per call). */
export function swallow(p: MaybePromise): void {
  if (p) p.catch(function () {});
}
