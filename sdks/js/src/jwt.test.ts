import { describe, expect, it } from 'vitest';
import { normalizeJwtSource } from './jwt';

describe('normalizeJwtSource', () => {
  it('wraps a static string in a resolver that always returns it', async () => {
    const resolver = normalizeJwtSource('a-token');

    expect(await resolver()).toBe('a-token');
    expect(await resolver()).toBe('a-token');
  });

  it('passes a resolver function through unchanged', () => {
    const resolver = () => 'dynamic-token';

    expect(normalizeJwtSource(resolver)).toBe(resolver);
  });
});
