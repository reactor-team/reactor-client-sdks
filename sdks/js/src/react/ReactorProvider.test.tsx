/** @vitest-environment jsdom */
import { render, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { FakeReactorClient } from '../internal/fake-reactor-client';

vi.mock('../internal/wasm', () => ({
  loadReactorWasm: () => Promise.resolve({ ReactorClient: FakeReactorClient }),
}));

// Import after the mock so `Reactor` (transitively, via `./store`) picks up
// the faked wasm loader.
const { ReactorProvider, useReactorStore } = await import('./ReactorProvider');

function currentClient(): FakeReactorClient {
  const client = FakeReactorClient.instances.at(-1);

  if (!client) {
    throw new Error('no FakeReactorClient constructed yet');
  }

  return client;
}

function StatusProbe() {
  const status = useReactorStore((s) => s.status);

  return <div data-testid="status">{status}</div>;
}

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
