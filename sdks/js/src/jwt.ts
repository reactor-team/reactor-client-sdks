import type { JwtSource } from './types';

/** Lazy resolver for a Coordinator/runtime bearer token, called immediately
 *  before each authenticated request — see `JwtSource`. */
export type JwtResolver = () => string | Promise<string>;

/**
 * Wraps a `JwtSource` into a `JwtResolver`: a static string becomes a
 * resolver that always returns it, a resolver passes through unchanged.
 * Useful for feeding a source that may be either form (e.g. a
 * `ReactorProvider`'s `jwtToken`) into a `getJwt` prop that expects a
 * resolver.
 */
export function normalizeJwtSource(source: JwtSource): JwtResolver {
  if (typeof source === 'function') {
    return source;
  }
  return () => source;
}
