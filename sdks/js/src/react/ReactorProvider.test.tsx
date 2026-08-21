/** @vitest-environment jsdom */
import { act, render, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { FakeReactorClient } from '../internal/fake-reactor-client';
import type { Reactor } from '../reactor';

vi.mock('../internal/wasm', () => ({
  loadReactorWasm: () => Promise.resolve({ ReactorClient: FakeReactorClient }),
}));

// Import after the mock so `Reactor` (transitively, via `./store`) picks up
// the faked wasm loader.
const { ReactorProvider, useReactorStore } = await import('./ReactorProvider');
const { useReactor } = await import('./hooks');

function currentClient(): FakeReactorClient {
  const client = FakeReactorClient.instances.at(-1);

  if (!client) {
    throw new Error('no FakeReactorClient constructed yet');
  }

  return client;
}

function Probe({ onReactor }: { onReactor: (reactor: Reactor) => void }) {
  const reactor = useReactor((s) => s.internal.reactor);

  onReactor(reactor);

  return null;
}

function StatusProbe() {
  const status = useReactorStore((s) => s.status);

  return <div data-testid="status">{status}</div>;
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

  it('rebuilds when connectOptions.autoConnect changes, same as any other prop', async () => {
    const seen: Reactor[] = [];
    const { rerender } = render(
      <ReactorProvider modelName="test-model" jwtToken="token">
        <Probe onReactor={(r) => seen.push(r)} />
      </ReactorProvider>,
    );

    await act(async () => {
      rerender(
        <ReactorProvider modelName="test-model" jwtToken="token" connectOptions={{ autoConnect: true }}>
          <Probe onReactor={(r) => seen.push(r)} />
        </ReactorProvider>,
      );
      await Promise.resolve();
    });

    expect(seen.at(-1)).not.toBe(seen[0]);
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

describe('ReactorProvider connectOptions.autoConnect', () => {
  it('does not connect on mount when omitted', () => {
    // The wasm client is built lazily, on the first connect() call — no
    // autoConnect means no connect() at all, so no client is constructed.
    const instancesBefore = FakeReactorClient.instances.length;

    render(
      <ReactorProvider modelName="test-model" jwtToken="token">
        <StatusProbe />
      </ReactorProvider>,
    );

    expect(FakeReactorClient.instances.length).toBe(instancesBefore);
  });

  it('connects on mount with jwtToken and the remaining connectOptions when true', async () => {
    render(
      <ReactorProvider
        modelName="test-model"
        jwtToken="token"
        connectOptions={{ autoConnect: true, maxAttempts: 3 }}
      >
        <StatusProbe />
      </ReactorProvider>,
    );

    await waitFor(() => expect(currentClient().connectCalls).toEqual([{ maxAttempts: 3 }]));
  });

  it('does not autoConnect if the store is already past "disconnected"', async () => {
    let renderCount = 0;

    function ConditionalAutoConnect() {
      renderCount += 1;
      return (
        <ReactorProvider modelName="test-model" jwtToken="token" connectOptions={{ autoConnect: true }}>
          <StatusProbe />
        </ReactorProvider>
      );
    }

    render(<ConditionalAutoConnect />);
    await waitFor(() => expect(currentClient().connectCalls.length).toBe(1));
    expect(renderCount).toBe(1);
  });

  it('does not let an autoConnect failure escape as an unhandled rejection', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    FakeReactorClient.nextConnectImpl = () => Promise.reject(new Error('boom'));

    render(
      <ReactorProvider modelName="test-model" jwtToken="token" connectOptions={{ autoConnect: true }}>
        <StatusProbe />
      </ReactorProvider>,
    );

    await waitFor(() => expect(consoleError).toHaveBeenCalled());
    expect(consoleError.mock.calls[0]?.[0]).toContain('autoConnect failed');

    consoleError.mockRestore();
  });
});

describe('ReactorProvider jwtToken', () => {
  it('passes jwtToken through as the Reactor jwt', async () => {
    render(
      <ReactorProvider modelName="test-model" jwtToken="a-token" connectOptions={{ autoConnect: true }}>
        <StatusProbe />
      </ReactorProvider>,
    );

    await waitFor(() => expect(currentClient().jwt).toBe('a-token'));
  });
});

describe('ReactorProvider unmount', () => {
  it('disconnects on unmount', async () => {
    const { unmount } = render(
      <ReactorProvider modelName="test-model" jwtToken="token" connectOptions={{ autoConnect: true }}>
        <StatusProbe />
      </ReactorProvider>,
    );

    await waitFor(() => expect(currentClient().connectCalls.length).toBe(1));
    const client = currentClient();

    unmount();

    expect(client.disconnectCalls).toBe(1);
  });
});
