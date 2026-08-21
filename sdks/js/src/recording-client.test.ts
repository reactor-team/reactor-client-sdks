import { describe, expect, it, vi } from 'vitest';
import type * as RecordingModule from './recording';
import type { Reactor } from './reactor';
import type { Clip } from './types';

vi.mock('./recording', async (importOriginal) => ({
  ...(await importOriginal<typeof RecordingModule>()),
  fetchPlaylist: vi.fn(),
  createPlayableManifestUrl: vi.fn(),
}));

const { fetchPlaylist, createPlayableManifestUrl } = await import('./recording');
const { RecordingClient } = await import('./recording-client');

const CLIP: Clip = {
  sessionId: 'sess_1',
  kind: 'snap',
  startMarker: 0,
  endMarker: 10,
  nowMarker: 10,
  predictedReadyAtMs: 1_000,
  playlistUrl: 'https://api.reactor.test/clips?session_id=sess_1',
};

function fakeReactor() {
  const requestClip = vi.fn().mockResolvedValue(CLIP);
  const requestRecording = vi.fn().mockResolvedValue(CLIP);
  const downloadClipAsFile = vi.fn().mockResolvedValue(new Blob(['mp4-bytes']));
  const reactor = { requestClip, requestRecording, downloadClipAsFile } as unknown as Reactor;

  return { reactor, requestClip, requestRecording, downloadClipAsFile };
}

describe('RecordingClient', () => {
  it('requestClip delegates to the underlying Reactor', async () => {
    const { reactor, requestClip } = fakeReactor();
    const client = new RecordingClient(reactor);

    const clip = await client.requestClip(10);

    expect(requestClip).toHaveBeenCalledWith(10);
    expect(clip).toBe(CLIP);
  });

  it('requestRecording delegates to the underlying Reactor', async () => {
    const { reactor, requestRecording } = fakeReactor();
    const client = new RecordingClient(reactor);

    const clip = await client.requestRecording();

    expect(requestRecording).toHaveBeenCalled();
    expect(clip).toBe(CLIP);
  });

  it('downloadClipAsFile delegates to the underlying Reactor with the same arguments', async () => {
    const { reactor, downloadClipAsFile } = fakeReactor();
    const client = new RecordingClient(reactor);

    const blob = await client.downloadClipAsFile(CLIP, 'out.mp4', { jwt: 'jwt-token' });

    expect(downloadClipAsFile).toHaveBeenCalledWith(CLIP, 'out.mp4', { jwt: 'jwt-token' });
    expect(blob).toBeInstanceOf(Blob);
  });

  it('fetchPlaylist polls clip.playlistUrl, seeding predictedReadyAtMs from the clip', async () => {
    vi.mocked(fetchPlaylist).mockResolvedValueOnce('#EXTM3U\n');
    const client = new RecordingClient(fakeReactor().reactor);

    const body = await client.fetchPlaylist(CLIP, { jwt: 'jwt-token' });

    expect(fetchPlaylist).toHaveBeenCalledWith(CLIP.playlistUrl, {
      predictedReadyAtMs: CLIP.predictedReadyAtMs,
      jwt: 'jwt-token',
    });
    expect(body).toBe('#EXTM3U\n');
  });

  it('getPlayableManifestUrl fetches the playlist and wraps it in a blob URL', async () => {
    vi.mocked(fetchPlaylist).mockResolvedValueOnce('#EXTM3U\n');
    vi.mocked(createPlayableManifestUrl).mockReturnValueOnce('blob:test-manifest');
    const client = new RecordingClient(fakeReactor().reactor);

    const url = await client.getPlayableManifestUrl(CLIP);

    expect(createPlayableManifestUrl).toHaveBeenCalledWith('#EXTM3U\n', CLIP.playlistUrl);
    expect(url).toBe('blob:test-manifest');
  });

  it('destroy is a no-op', () => {
    const client = new RecordingClient(fakeReactor().reactor);

    expect(() => client.destroy()).not.toThrow();
  });
});
