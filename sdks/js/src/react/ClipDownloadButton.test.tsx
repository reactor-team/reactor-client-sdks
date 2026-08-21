/** @vitest-environment jsdom */
import { act, fireEvent, render } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeReactorClient } from '../internal/fake-reactor-client';
import { downloadClipAsFile } from '../recording';
import type * as RecordingModule from '../recording';
import type { Clip } from '../types';

vi.mock('../internal/wasm', () => ({
  loadReactorWasm: () => Promise.resolve({ ReactorClient: FakeReactorClient }),
}));

vi.mock('../recording', async (importOriginal) => ({
  ...(await importOriginal<typeof RecordingModule>()),
  downloadClipAsFile: vi.fn(),
}));

const { ClipDownloadButton } = await import('./ClipDownloadButton');

const CLIP: Clip = {
  sessionId: 'sess_1',
  kind: 'snap',
  startMarker: 0,
  endMarker: 10,
  nowMarker: 10,
  predictedReadyAtMs: 0,
  playlistUrl: 'https://api.reactor.test/clips?session_id=sess_1',
};

function button(container: HTMLElement): HTMLButtonElement {
  const el = container.querySelector('button');

  if (!el) {
    throw new Error('no <button> rendered');
  }
  return el;
}

beforeEach(() => {
  vi.mocked(downloadClipAsFile).mockReset();
});

describe('ClipDownloadButton', () => {
  it('renders the default "Download" label and triggers a download on click', async () => {
    vi.mocked(downloadClipAsFile).mockResolvedValue(new Blob());

    const { container } = render(<ClipDownloadButton clip={CLIP} />);

    expect(button(container).textContent).toBe('Download');

    await act(async () => {
      fireEvent.click(button(container));
      await Promise.resolve();
    });

    expect(downloadClipAsFile).toHaveBeenCalledWith(CLIP, 'reactor-clip.mp4', expect.any(Object));
  });

  it('shows download progress in the default label', async () => {
    let capturedOnProgress!: (info: { fetched: number; total: number; bytes: number }) => void;

    vi.mocked(downloadClipAsFile).mockImplementation((_clip, _filename, options) => {
      capturedOnProgress = options?.onProgress ?? (() => {});
      return new Promise(() => {});
    });

    const { container } = render(<ClipDownloadButton clip={CLIP} />);

    await act(async () => {
      fireEvent.click(button(container));
      await Promise.resolve();
    });

    act(() => capturedOnProgress({ fetched: 2, total: 5, bytes: 100 }));

    expect(button(container).textContent).toBe('Downloading 2/5…');
    expect(button(container).disabled).toBe(true);
  });

  it('renders a static label when children is a plain node', () => {
    const { container } = render(<ClipDownloadButton clip={CLIP}>Save clip</ClipDownloadButton>);

    expect(button(container).textContent).toBe('Save clip');
  });

  it('renders a state-aware label when children is a render function', () => {
    const { container } = render(
      <ClipDownloadButton clip={CLIP}>{(state) => `state: ${state.kind}`}</ClipDownloadButton>,
    );

    expect(button(container).textContent).toBe('state: idle');
  });

  it('fires onSuccess with the resolved Blob', async () => {
    const blob = new Blob(['data']);

    vi.mocked(downloadClipAsFile).mockResolvedValue(blob);
    const onSuccess = vi.fn();

    const { container } = render(<ClipDownloadButton clip={CLIP} onSuccess={onSuccess} />);

    await act(async () => {
      fireEvent.click(button(container));
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(onSuccess).toHaveBeenCalledWith(blob);
  });

  it('fires onError and shows the message on failure', async () => {
    vi.mocked(downloadClipAsFile).mockRejectedValue(new Error('boom'));
    const onError = vi.fn();

    const { container } = render(<ClipDownloadButton clip={CLIP} onError={onError} />);

    await act(async () => {
      fireEvent.click(button(container));
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(onError).toHaveBeenCalledWith(new Error('boom'));
    expect(button(container).getAttribute('title')).toBe('boom');
  });

  it('respects the disabled prop even when idle', () => {
    const { container } = render(<ClipDownloadButton clip={CLIP} disabled />);

    expect(button(container).disabled).toBe(true);
  });
});
