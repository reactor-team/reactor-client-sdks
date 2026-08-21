import type { JwtSource } from '../types';

/** Resolves a `JwtSource` (a plain string, or a resolver called at request
 *  time) to a plain string — shared by `ClipPlayer`/`useClipDownload`, which
 *  both fall back to a `ReactorProvider`'s own `JwtSource` when no explicit
 *  resolver is passed. */
export async function resolveJwtSource(source: JwtSource): Promise<string> {
  return typeof source === 'function' ? source() : source;
}
