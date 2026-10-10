import type { JwtRequestContext, JwtSource } from '../types';

/** Resolves a `JwtSource` (a plain string, or a resolver called at request
 *  time with `context`) to a plain string — shared by the clip surfaces,
 *  which fall back to a `ReactorProvider`'s own `JwtSource` when no explicit
 *  resolver is passed. */
export async function resolveJwtSource(source: JwtSource, context?: JwtRequestContext): Promise<string> {
  return typeof source === 'function' ? source(context) : source;
}
