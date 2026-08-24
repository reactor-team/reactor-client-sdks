/** @vitest-environment jsdom */
import { act, render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { FakeReactorClient } from '../internal/fake-reactor-client';
import type { Reactor } from '../reactor';

vi.mock('../internal/wasm', () => ({
  loadReactorWasm: () => Promise.resolve({ ReactorClient: FakeReactorClient }),
}));

// Import after the mock so `Reactor` (transitively, via `./store`) picks up
// the faked wasm loader.
const { ReactorProvider } = await import('./ReactorProvider');
const { useReactor } = await import('./hooks');

function Probe({ onReactor }: { onReactor: (reactor: Reactor) => void }) {
  const reactor = useReactor((s) => s.internal.reactor);

  onReactor(reactor);

  return null;
}

describe('ReactorProvider', () => {
  it('keeps the same Reactor across a re-render with unchanged props', () => {
    const seen: Reactor[] = [];
    const { rerender } = render(
      <ReactorProvider modelName="test-model">
        <Probe onReactor={(r) => seen.push(r)} />
      </ReactorProvider>,
    );

    rerender(
      <ReactorProvider modelName="test-model">
        <Probe onReactor={(r) => seen.push(r)} />
      </ReactorProvider>,
    );

    expect(seen.length).toBeGreaterThanOrEqual(2);
    expect(seen.every((reactor) => reactor === seen[0])).toBe(true);
  });

  it('rebuilds the Reactor when modelName changes, and disposes the old one', async () => {
    const seen: Reactor[] = [];
    const { rerender } = render(
      <ReactorProvider modelName="model-a">
        <Probe onReactor={(r) => seen.push(r)} />
      </ReactorProvider>,
    );

    await act(async () => {
      rerender(
        <ReactorProvider modelName="model-b">
          <Probe onReactor={(r) => seen.push(r)} />
        </ReactorProvider>,
      );
      // The teardown of the old Reactor runs async (disconnect().finally(...)).
      await Promise.resolve();
    });

    // Rebuilding swaps the store via a state update inside an effect, so the
    // render right after rerender() still reflects the old store — only the
    // render that follows the effect carries the new one.
    const oldReactor = seen[0]!;
    const newReactor = seen.at(-1)!;

    expect(newReactor).not.toBe(oldReactor);
    // Disposed — any further use rejects, rather than silently reusing a
    // torn-down instance. connect() is async, so a synchronous throw inside
    // it surfaces as a rejection, not a thrown error.
    await expect(oldReactor.connect()).rejects.toThrow('disposed');
  });

  it('disconnects and disposes the Reactor on unmount', async () => {
    let reactor: Reactor | undefined;
    const { unmount } = render(
      <ReactorProvider modelName="test-model">
        <Probe onReactor={(r) => (reactor = r)} />
      </ReactorProvider>,
    );

    await act(async () => {
      unmount();
      await Promise.resolve();
    });

    await expect(reactor!.connect()).rejects.toThrow('disposed');
  });
});
