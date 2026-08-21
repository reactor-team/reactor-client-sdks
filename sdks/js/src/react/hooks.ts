import { useShallow } from 'zustand/shallow';
import { useReactorStore } from './ReactorProvider';
import type { ReactorStore } from './store';

/** Reads from the nearest `ReactorProvider`'s store, with a shallow-equality
 *  check on the selector's result — so a selector returning a fresh object
 *  each call doesn't cause a re-render on every unrelated store update. */
export function useReactor<T>(selector: (state: ReactorStore) => T): T {
  return useReactorStore(useShallow(selector));
}
