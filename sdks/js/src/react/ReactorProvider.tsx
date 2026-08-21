import { createContext, useContext, useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import { createReactorStore, type ReactorStore, type StoreApi } from './store';
import type { ConnectOptions, JwtSource, ReactorOptions } from '../types';

export interface ReactorProviderProps {
  apiUrl?: string | undefined;
  modelName: string;
  local?: boolean | undefined;
  jwt?: JwtSource | undefined;
  connectOptions?: ConnectOptions | undefined;
  children?: ReactNode;
}

/** Exported so a component can optionally read the nearest provider's store
 *  without requiring one — e.g. `ClipPlayer`, which works both inside and
 *  outside a `ReactorProvider`. Most consumers want `useReactor`/`useReactorStore`
 *  instead, which throw outside a provider rather than returning `undefined`. */
export const ReactorContext = createContext<StoreApi<ReactorStore> | undefined>(undefined);

/** `apiUrl`/`modelName`/`local`/`jwt` build a `Reactor`; there's no way to
 *  change a live instance's model or endpoint, so a change to any of them
 *  means building a new one from scratch — built up field-by-field, not as
 *  one object literal, so an omitted prop stays omitted rather than becoming
 *  an explicit `undefined` (required under `exactOptionalPropertyTypes`). */
function buildReactorOptions({
  apiUrl,
  modelName,
  local,
  jwt,
}: Pick<ReactorProviderProps, 'apiUrl' | 'modelName' | 'local' | 'jwt'>): ReactorOptions {
  const options: ReactorOptions = { modelName };

  if (apiUrl !== undefined) {
    options.apiUrl = apiUrl;
  }
  if (local !== undefined) {
    options.local = local;
  }
  if (jwt !== undefined) {
    options.jwt = jwt;
  }

  return options;
}

/**
 * Owns a `Reactor` instance and exposes it to `useReactor` below.
 *
 * `apiUrl`/`modelName`/`local`/`jwt`/`connectOptions` are live: changing any
 * of them tears down the current `Reactor` (`disconnect()` then
 * `[Symbol.dispose]()`) and builds a fresh one, the same way
 * `useLiveKitRoom` rebuilds its `Room` when its construction `options`
 * change — `Reactor` has no equivalent of LiveKit's `token`/`serverUrl`
 * split (reconnecting the *same* instance with new credentials), since its
 * constructor bundles everything. Pass a stable `jwt` resolver (e.g. via
 * `useCallback`) and a stable `connectOptions` reference if you don't want
 * every parent render to rebuild the connection — object/function props are
 * compared by reference (`connectOptions` via a `JSON.stringify` snapshot,
 * same as `useLiveKitRoom` does for its own `options`).
 *
 * Unmounting tears down the same way — see `Reactor.disconnect()`/
 * `[Symbol.dispose]()` for what that frees.
 */
export function ReactorProvider({
  apiUrl,
  modelName,
  local,
  jwt,
  connectOptions,
  children,
}: ReactorProviderProps) {
  const [store, setStore] = useState(() =>
    createReactorStore(buildReactorOptions({ apiUrl, modelName, local, jwt }), connectOptions),
  );
  // Skips the rebuild effect's mandatory first run — the initial store was
  // already built synchronously above, so re-running the same construction
  // on mount would just replace it with an equivalent one for nothing.
  const isFirstRun = useRef(true);

  useEffect(() => {
    if (isFirstRun.current) {
      isFirstRun.current = false;

      return;
    }
    setStore(createReactorStore(buildReactorOptions({ apiUrl, modelName, local, jwt }), connectOptions));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [apiUrl, modelName, local, jwt, JSON.stringify(connectOptions)]);

  // Tears down whichever Reactor this provider currently owns — on rebuild
  // (store changes, so this cleanup fires for the one being replaced) and on
  // final unmount. disconnect() first (ends the session server-side; a bare
  // [Symbol.dispose]() skips that), then dispose to free local resources for
  // good and stop the store's event listeners from lingering on a Reactor
  // nothing references any more.
  useEffect(() => {
    return () => {
      const reactor = store.getState().internal.reactor;

      void reactor.disconnect().finally(() => reactor[Symbol.dispose]());
    };
  }, [store]);

  return <ReactorContext.Provider value={store}>{children}</ReactorContext.Provider>;
}

/** Reads the store directly, with whatever equality behavior `selector`
 *  itself implies — most callers want `useReactor` instead, which adds
 *  shallow comparison. Throws outside a `ReactorProvider`. */
export function useReactorStore<T>(selector: (state: ReactorStore) => T): T {
  const store = useContext(ReactorContext);

  if (store === undefined) {
    throw new Error('useReactor must be used within a ReactorProvider');
  }

  return useSyncExternalStore(store.subscribe, () => selector(store.getState()));
}
