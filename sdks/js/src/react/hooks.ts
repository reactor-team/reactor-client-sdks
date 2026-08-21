import { useEffect, useRef, useState } from 'react';
import { useReactorStore } from './ReactorProvider';
import type { ReactorStore } from './store';
import type { ConnectionStats, ReactorMessage } from '../types';

function shallowEqual(a: unknown, b: unknown): boolean {
  if (Object.is(a, b)) {
    return true;
  }
  if (typeof a !== 'object' || a === null || typeof b !== 'object' || b === null) {
    return false;
  }

  const keysA = Object.keys(a);
  const keysB = Object.keys(b);

  return (
    keysA.length === keysB.length &&
    keysA.every((key) => Object.is((a as Record<string, unknown>)[key], (b as Record<string, unknown>)[key]))
  );
}

/** Wraps `selector` so repeated calls return the *previous* result whenever
 *  the new one is shallowly equal to it — lets a selector return a fresh
 *  object each call without forcing a re-render on every unrelated store
 *  update (the object reference `useReactor` sees only changes when its
 *  shallow contents do). */
function useShallowSelector<T>(selector: (state: ReactorStore) => T): (state: ReactorStore) => T {
  const cache = useRef<{ value: T } | undefined>(undefined);

  return (state) => {
    const next = selector(state);

    if (cache.current && shallowEqual(cache.current.value, next)) {
      return cache.current.value;
    }

    cache.current = { value: next };
    return next;
  };
}

/** Reads from the nearest `ReactorProvider`'s store, with a shallow-equality
 *  check on the selector's result — so a selector returning a fresh object
 *  each call doesn't cause a re-render on every unrelated store update. */
export function useReactor<T>(selector: (state: ReactorStore) => T): T {
  return useReactorStore(useShallowSelector(selector));
}

/** Subscribes to app-scope `message` payloads sent by the model via
 *  `get_ctx().send()`. `handler` is kept in a ref so identity churn on
 *  every render doesn't retrigger the subscription effect. */
export function useReactorMessage(handler: (message: ReactorMessage) => void): void {
  const reactor = useReactor((state) => state.internal.reactor);
  const handlerRef = useRef(handler);

  handlerRef.current = handler;

  useEffect(() => {
    const listener = (message: ReactorMessage) => handlerRef.current(message);

    reactor.on('message', listener);
    return () => reactor.off('message', listener);
  }, [reactor]);
}

/** Subscribes to platform-scope `runtimeMessage` payloads (moderation,
 *  clip/recording lifecycle, ...) — see `useReactorMessage` for app-scope
 *  payloads instead. */
export function useReactorInternalMessage(handler: (message: ReactorMessage) => void): void {
  const reactor = useReactor((state) => state.internal.reactor);
  const handlerRef = useRef(handler);

  handlerRef.current = handler;

  useEffect(() => {
    const listener = (message: ReactorMessage) => handlerRef.current(message);

    reactor.on('runtimeMessage', listener);
    return () => reactor.off('runtimeMessage', listener);
  }, [reactor]);
}

/** The most recent `statsUpdate` sample; `undefined` until the first one
 *  arrives, and reset back to `undefined` on unmount or reactor change. */
export function useStats(): ConnectionStats | undefined {
  const reactor = useReactor((state) => state.internal.reactor);
  const [stats, setStats] = useState<ConnectionStats | undefined>(undefined);

  useEffect(() => {
    setStats(undefined);
    reactor.on('statsUpdate', setStats);
    return () => {
      reactor.off('statsUpdate', setStats);
      setStats(undefined);
    };
  }, [reactor]);

  return stats;
}
