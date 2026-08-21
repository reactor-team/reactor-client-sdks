import { createContext, useContext, useEffect, useRef, useSyncExternalStore, type ReactNode } from 'react';
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

const ReactorContext = createContext<StoreApi<ReactorStore> | undefined>(undefined);

/**
 * Owns a `Reactor` instance (built once, lazily, on first render) and exposes
 * it to `useReactor` below. Unmounting disconnects it — see `Reactor.disconnect()`
 * for what that frees.
 */
export function ReactorProvider({
  apiUrl,
  modelName,
  local,
  jwt,
  connectOptions,
  children,
}: ReactorProviderProps) {
  const storeRef = useRef<StoreApi<ReactorStore> | undefined>(undefined);

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
    if (jwt !== undefined) {
      options.jwt = jwt;
    }

    storeRef.current = createReactorStore(options, connectOptions);
  }

  useEffect(() => {
    const store = storeRef.current;

    return () => {
      void store?.getState().internal.reactor.disconnect();
    };
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
