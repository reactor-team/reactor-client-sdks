/** @vitest-environment jsdom */
import { act, cleanup, render, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FakeReactorClient } from '../internal/fake-reactor-client';
import { STATS_INTERVAL_MS } from '../internal/stats';
import type { Reactor } from '../reactor';

vi.mock('../internal/wasm', () => ({
  loadReactorWasm: () => Promise.resolve({ ReactorClient: FakeReactorClient }),
}));

// Import after the mock so `Reactor` (transitively, via `./store`) picks up
// the faked wasm loader.
const { ReactorProvider } = await import('./ReactorProvider');
const { useReactor, useReactorMessage, useReactorInternalMessage, useStats } = await import('./hooks');

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

afterEach(() => {
  cleanup();
});

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

describe('useReactorMessage', () => {
  it('fires the latest handler on each app-scope message, not on runtimeMessage', async () => {
    const handler = vi.fn();
    let reactor: Reactor | undefined;

    function Probe({ onMessage }: { onMessage: (message: unknown) => void }) {
      reactor = useReactor((s) => s.internal.reactor);
      useReactorMessage(onMessage);
      return null;
    }

    const { rerender } = render(
      <Provider>
        <Probe onMessage={handler} />
      </Provider>,
    );

    await act(() => reactor?.connect() ?? Promise.resolve());
    act(() => currentClient().emitRuntimeMessage({ type: 'runtime', data: null }));
    expect(handler).not.toHaveBeenCalled();

    act(() => currentClient().emitMessage({ type: 'app', data: { value: 1 } }));
    expect(handler).toHaveBeenCalledWith({ type: 'app', data: { value: 1 } });

    const secondHandler = vi.fn();

    rerender(
      <Provider>
        <Probe onMessage={secondHandler} />
      </Provider>,
    );
    act(() => currentClient().emitMessage({ type: 'app', data: { value: 2 } }));

    expect(handler).toHaveBeenCalledTimes(1);
    expect(secondHandler).toHaveBeenCalledWith({ type: 'app', data: { value: 2 } });
  });
});

describe('useReactorInternalMessage', () => {
  it('fires on runtimeMessage but not on message', async () => {
    const handler = vi.fn();
    let reactor: Reactor | undefined;

    function Probe() {
      reactor = useReactor((s) => s.internal.reactor);
      useReactorInternalMessage(handler);
      return null;
    }

    render(
      <Provider>
        <Probe />
      </Provider>,
    );

    await act(() => reactor?.connect() ?? Promise.resolve());
    act(() => currentClient().emitMessage({ type: 'app', data: null }));
    expect(handler).not.toHaveBeenCalled();

    act(() => currentClient().emitRuntimeMessage({ type: 'runtime', data: { value: 1 } }));
    expect(handler).toHaveBeenCalledWith({ type: 'runtime', data: { value: 1 } });
  });
});

describe('useStats', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('is undefined until the first statsUpdate, then tracks each new sample', async () => {
    vi.useFakeTimers();

    let reactor: Reactor | undefined;
    let stats: ReturnType<typeof useStats>;

    function Probe() {
      reactor = useReactor((s) => s.internal.reactor);
      stats = useStats();
      return null;
    }

    render(
      <Provider>
        <Probe />
      </Provider>,
    );

    await act(() => reactor?.connect() ?? Promise.resolve());
    expect(stats).toBeUndefined();

    const report = { forEach: () => {}, get: () => undefined } as unknown as RTCStatsReport;

    currentClient().peerConnectionResult = { getStats: vi.fn().mockResolvedValue(report) } as unknown as RTCPeerConnection;
    act(() => currentClient().emitReady());

    await act(() => vi.advanceTimersByTimeAsync(STATS_INTERVAL_MS));
    expect(stats).toEqual(reactor?.getStats());

    const firstSample = stats;

    await act(() => vi.advanceTimersByTimeAsync(STATS_INTERVAL_MS));
    expect(stats).toEqual(reactor?.getStats());
    expect(stats).not.toBe(firstSample);
  });

  it('unsubscribes from statsUpdate on unmount', async () => {
    let reactor: Reactor | undefined;

    function Probe() {
      reactor = useReactor((s) => s.internal.reactor);
      useStats();
      return null;
    }

    const { unmount } = render(
      <Provider>
        <Probe />
      </Provider>,
    );

    await act(() => reactor?.connect() ?? Promise.resolve());

    const offSpy = vi.spyOn(reactor as Reactor, 'off');

    unmount();

    expect(offSpy).toHaveBeenCalledWith('statsUpdate', expect.any(Function));
  });
});
