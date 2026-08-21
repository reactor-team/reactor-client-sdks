import { createContext, useContext, useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import { createReactorStore, type ReactorStore, type StoreApi } from './store';
import type { ConnectOptions, JwtSource, ReactorOptions } from '../types';

/** `connectOptions` for the provider — adds `autoConnect` to the core
 *  `ConnectOptions`. Live, same as every other `ReactorProviderProps` field —
 *  see `ReactorProvider`'s doc comment. */
export interface ReactorConnectOptions extends ConnectOptions {
  /** Connect automatically once a Reactor is built — initial mount, or any
   *  later rebuild. Default `false`. */
  autoConnect?: boolean;
}

export interface ReactorProviderProps {
  apiUrl?: string | undefined;
  modelName: string;
  local?: boolean | undefined;
  /** Static token, or a resolver called before every authenticated request
   *  — see `JwtSource`. Required for `autoConnect` against a non-local
   *  runtime; can also be supplied later via an explicit `connect()` call
   *  instead. */
  jwtToken?: JwtSource | undefined;
  connectOptions?: ReactorConnectOptions | undefined;
  children?: ReactNode;
}

/** Exported so a component can optionally read the nearest provider's store
 *  without requiring one — e.g. `ClipPlayer`, which works both inside and
 *  outside a `ReactorProvider`. Most consumers want `useReactor`/`useReactorStore`
 *  instead, which throw outside a provider rather than returning `undefined`. */
export const ReactorContext = createContext<StoreApi<ReactorStore> | undefined>(undefined);

type BuildStoreProps = Pick<
  ReactorProviderProps,
  'apiUrl' | 'modelName' | 'local' | 'jwtToken' | 'connectOptions'
>;

/**
 * Builds a fresh store (and the `Reactor` underneath) from the provider's
 * props, firing `connect()` immediately when `autoConnect` is set. Used for
 * both the initial mount and every later rebuild, so both go through the
 * same auto-connect path rather than only the first one.
 */
function buildStore({ apiUrl, modelName, local, jwtToken, connectOptions }: BuildStoreProps): StoreApi<ReactorStore> {
  // Built up field-by-field, not as one object literal, so an omitted prop
  // stays omitted rather than becoming an explicit `undefined` — required
  // under `exactOptionalPropertyTypes`.
  const options: ReactorOptions = { modelName };

  if (apiUrl !== undefined) {
    options.apiUrl = apiUrl;
  }
  if (local !== undefined) {
    options.local = local;
  }
  if (jwtToken !== undefined) {
    options.jwt = jwtToken;
  }

  const { autoConnect = false, ...pollingOptions } = connectOptions ?? {};
  const store = createReactorStore(options, pollingOptions);

  if (autoConnect && store.getState().status === 'disconnected') {
    // A rejection here (auth/network failure, ...) would otherwise escape as
    // an unhandled promise rejection — connect() throws rather than
    // reporting failures through the `error` event, and nothing here awaits
    // this fire-and-forget call.
    store.getState().connect(jwtToken, pollingOptions).catch((error: unknown) => {
      console.error('[Reactor.ReactorProvider] autoConnect failed:', error);
    });
  }

  return store;
}

/**
 * Owns a `Reactor` instance and exposes it to `useReactor` below.
 *
 * `apiUrl`/`modelName`/`local`/`jwtToken`/`connectOptions` (including
 * `autoConnect`) are live: changing any of them tears down the current
 * `Reactor` (`disconnect()` then `[Symbol.dispose]()`) and builds a fresh
 * one — the same way `useLiveKitRoom` rebuilds its `Room` when its
 * construction `options` change. `Reactor` has no equivalent of LiveKit's
 * `token`/`serverUrl` split (reconnecting the *same* instance with new
 * credentials) or its live `connect` boolean (toggling the same `Room`'s
 * connection without rebuilding it) — its constructor bundles everything, so
 * any change, `autoConnect` included, means a fresh instance rather than
 * reconnecting/disconnecting the existing one. Pass a stable `jwtToken`
 * (e.g. via `useCallback` for a resolver) and a stable `connectOptions`
 * reference if you don't want an unrelated parent render to rebuild the
 * connection — object/function props are compared by reference
 * (`connectOptions` via a `JSON.stringify` snapshot, same as
 * `useLiveKitRoom` does for its own `options`).
 *
 * Unmounting tears down the same way — see `Reactor.disconnect()`/
 * `[Symbol.dispose]()` for what that frees.
 */
export function ReactorProvider({
  apiUrl,
  modelName,
  local,
  jwtToken,
  connectOptions,
  children,
}: ReactorProviderProps) {
  const [store, setStore] = useState(() => buildStore({ apiUrl, modelName, local, jwtToken, connectOptions }));
  // Skips the rebuild effect's mandatory first run — the initial store was
  // already built synchronously above, so re-running the same construction
  // on mount would just replace it with an equivalent one for nothing.
  const isFirstRun = useRef(true);

  useEffect(() => {
    if (isFirstRun.current) {
      isFirstRun.current = false;

      return;
    }
    setStore(buildStore({ apiUrl, modelName, local, jwtToken, connectOptions }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [apiUrl, modelName, local, jwtToken, JSON.stringify(connectOptions)]);

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
