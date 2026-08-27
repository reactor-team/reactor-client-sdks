// Reads the JWT minted by each example's own `vite.config.ts` middleware —
// see that file for why the minting itself lives server-side, keyed off
// REACTOR_API_KEY, rather than here.
//
// Call once per connect() and hand the result to it as a plain string, not
// as this function itself: passed as a resolver, the SDK would call it again
// on later hops and get back a *different* token from the same-scoped mint,
// which 403s reading back a session it didn't create.
export async function fetchToken(): Promise<string> {
  const r = await fetch('/api/token');

  if (!r.ok) {
    throw new Error(`token fetch failed: ${r.status}`);
  }
  const { jwt } = (await r.json()) as { jwt: string };

  return jwt;
}
