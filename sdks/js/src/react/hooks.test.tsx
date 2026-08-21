/** @vitest-environment jsdom */
import { act, render, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { FakeReactorClient } from '../internal/fake-reactor-client';
import type { Reactor } from '../reactor';

vi.mock('../internal/wasm', () => ({
  loadReactorWasm: () => Promise.resolve({ ReactorClient: FakeReactorClient }),
}));

// Import after the mock so `Reactor` (transitively, via `./store`) picks up
// the faked wasm loader.
const { ReactorProvider, useReactor } = await import('./index');

function currentClient(): FakeReactorClient {
  const client = FakeReactorClient.instances.at(-1);

  if (!client) {
    throw new Error('no FakeReactorClient constructed yet');
  }

  return client;
}

function Provider({ children }: { children: ReactNode }) {
  return <ReactorProvider modelName="test-model">{children}</ReactorProvider>;
}

describe('useReactor', () => {
  it('throws outside a ReactorProvider', () => {
    expect(() => renderHook(() => useReactor((s) => s.status))).toThrow(
      'useReactor must be used within a ReactorProvider',
    );
  });

  it('reads state through the provider', () => {
    const { result } = renderHook(() => useReactor((s) => s.status), { wrapper: Provider });

    expect(result.current).toBe('disconnected');
  });

  it('does not re-render on a store update the selector does not depend on', async () => {
    let renders = 0;
    let reactor: Reactor | undefined;

    function Probe() {
      renders += 1;

      const { status } = useReactor((s) => ({ status: s.status }));

      reactor = useReactor((s) => s.internal.reactor);

      return <span>{status}</span>;
    }

    render(
      <Provider>
        <Probe />
      </Provider>,
    );
    expect(renders).toBe(1);

    // Constructs the underlying FakeReactorClient; doesn't itself fire a
    // statusChanged event (that's the binding's job, not connect()'s).
    await act(() => reactor?.connect() ?? Promise.resolve());

    act(() => {
      currentClient().emitSessionIdChanged('session-123');
    });

    expect(renders).toBe(1);
  });

  it('binds sendCommand to the underlying reactor', async () => {
    const { result } = renderHook(() => useReactor((s) => s.sendCommand), { wrapper: Provider });

    await act(() => result.current('set_image', { url: 'https://example.com/a.png' }));

    expect(currentClient().sendCommandCalls).toEqual([
      { command: 'set_image', data: { url: 'https://example.com/a.png' }, uploads: undefined },
    ]);
  });

  it('exposes the underlying Reactor through internal.reactor', () => {
    const { result } = renderHook(() => useReactor((s) => s.internal.reactor), { wrapper: Provider });

    expect(result.current.getStatus()).toBe('disconnected');
  });
});
