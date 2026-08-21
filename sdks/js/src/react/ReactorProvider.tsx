import { createContext, useContext, useEffect, useRef, useSyncExternalStore, type ReactNode } from 'react';
import { createReactorStore, type ReactorStore, type StoreApi } from './store';
import type { ConnectOptions, JwtSource, ReactorOptions } from '../types';

/**
 * `connectOptions` for the provider — adds `autoConnect` to the core
 * `ConnectOptions` for the React mount lifecycle. Read once, at first mount
 * (see `ReactorProvider`'s effect deps below) — changing these after mount
 * doesn't tear down or reconnect the session.
 */
export interface ReactorConnectOptions extends ConnectOptions {
  /** Connect automatically once the provider mounts. Default `false`. */
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

/**
 * Owns a `Reactor` instance (built once, lazily, on first render) and exposes
 * it to `useReactor` below. Unmounting disconnects it — see `Reactor.disconnect()`
 * for what that frees.
 */
export function ReactorProvider({
  apiUrl,
  modelName,
  local,
  jwtToken,
  connectOptions,
  children,
}: ReactorProviderProps) {
  const storeRef = useRef<StoreApi<ReactorStore> | undefined>(undefined);
  const { autoConnect = false, ...pollingOptions } = connectOptions ?? {};

  if (storeRef.current === undefined) {
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

    storeRef.current = createReactorStore(options, pollingOptions);
  }

  useEffect(() => {
    const store = storeRef.current;

    if (autoConnect && store?.getState().status === 'disconnected') {
      void store.getState().connect(jwtToken, pollingOptions);
    }

    return () => {
      void store?.getState().internal.reactor.disconnect();
    };
    // Mount-only: `autoConnect`/`jwtToken`/`pollingOptions` are read once at
    // first mount, not resynced on every render — see `ReactorConnectOptions`'s
    // doc comment.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return <ReactorContext.Provider value={storeRef.current}>{children}</ReactorContext.Provider>;
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
