// Copied into every consumer app the runner builds (run.mjs), unchanged: the
// one surface its checks drive, the same way a real app would use the SDK.
import { Reactor } from '@reactor-team/js-sdk';

// Nothing listens here, so `connect()` fails fast — but only after the wasm
// binding is up and has made its first HTTP request, which is all a check
// needs to know the binding loaded.
const API_URL = 'http://127.0.0.1:9';

export function installProbe() {
  if (window.__consumer) {return;}

  const probe = { apiRequests: 0, connect };
  const fetch = window.fetch.bind(window);

  window.fetch = (input, init) => {
    if (String(input?.url ?? input).startsWith(API_URL)) {probe.apiRequests++;}

    return fetch(input, init);
  };

  async function connect() {
    const before = probe.apiRequests;
    let error = null;

    try {
      await new Reactor({ modelName: 'consumer-test', apiUrl: API_URL, jwt: 'not-a-real-token' }).connect();
    } catch (e) {
      error = String(e?.message ?? e);
    }

    return { reachedApi: probe.apiRequests > before, error };
  }

  window.__consumer = probe;
}
