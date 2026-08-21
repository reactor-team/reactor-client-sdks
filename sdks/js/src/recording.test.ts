import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  RecordingError,
  __remuxInternals,
  createPlayableManifestUrl,
  downloadClipAsFile,
  fetchPlaylist,
  parsePlaylist,
} from './recording';
import type { Clip } from './types';

/** Predicted ready time well in the future so polling tests don't trip the deadline. */
const FUTURE_READY_MS = 9_999_999_999_999;

const SAMPLE_CLIP: Clip = {
  sessionId: 'rec-123',
  kind: 'snap',
  startMarker: 120,
  endMarker: 150,
  nowMarker: 150,
  predictedReadyAtMs: FUTURE_READY_MS,
  playlistUrl: 'http://localhost:8080/clips?session_id=rec-123&start=120&end=150',
};

const SAMPLE_MANIFEST = `#EXTM3U
#EXT-X-VERSION:7
#EXT-X-TARGETDURATION:4
#EXT-X-PLAYLIST-TYPE:VOD
#EXT-X-MAP:URI="/clips/chunks/rec-123/init.mp4"
#EXTINF:4.000,
/clips/chunks/rec-123/chunk_00000.m4s
#EXTINF:4.000,
/clips/chunks/rec-123/chunk_00001.m4s
#EXT-X-ENDLIST
`;

describe('parsePlaylist', () => {
  it('extracts the init segment and ordered media segments', () => {
    const { initUrl, segmentUrls } = parsePlaylist(
      SAMPLE_MANIFEST,
      'http://localhost:8080/clips?session_id=rec-123&start=0&end=8',
    );

    expect(initUrl).toBe('http://localhost:8080/clips/chunks/rec-123/init.mp4');
    expect(segmentUrls).toEqual([
      'http://localhost:8080/clips/chunks/rec-123/chunk_00000.m4s',
      'http://localhost:8080/clips/chunks/rec-123/chunk_00001.m4s',
    ]);
  });

  it('preserves absolute chunk URLs verbatim', () => {
    const manifest = `#EXTM3U
#EXT-X-VERSION:7
#EXT-X-MAP:URI="https://cdn.reactor.inc/init.mp4"
#EXTINF:4.000,
https://cdn.reactor.inc/chunk_00000.m4s
#EXT-X-ENDLIST
`;
    const { initUrl, segmentUrls } = parsePlaylist(manifest, 'http://localhost/clips?x=1');

    expect(initUrl).toBe('https://cdn.reactor.inc/init.mp4');
    expect(segmentUrls).toEqual(['https://cdn.reactor.inc/chunk_00000.m4s']);
  });

  it('throws INVALID_PLAYLIST when #EXT-X-MAP is missing', () => {
    const broken = '#EXTM3U\n#EXTINF:4.000,\nchunk_00000.m4s\n';

    expect(() => parsePlaylist(broken, 'http://localhost/clips')).toThrow(RecordingError);
  });

  it('throws INVALID_PLAYLIST when there are no segments', () => {
    const broken = '#EXTM3U\n#EXT-X-MAP:URI="init.mp4"\n#EXT-X-ENDLIST\n';

    expect(() => parsePlaylist(broken, 'http://localhost/clips')).toThrow(RecordingError);
  });
});

describe('createPlayableManifestUrl', () => {
  // The function returns a blob: URL we can't fetch in Node, so URL.createObjectURL
  // is stubbed to capture the Blob it was called with and read its text back.
  let capturedBlob: Blob | null;
  let originalCreateObjectURL: typeof URL.createObjectURL | undefined;

  beforeEach(() => {
    capturedBlob = null;
    originalCreateObjectURL = URL.createObjectURL?.bind(URL);
    URL.createObjectURL = vi.fn((b: Blob) => {
      capturedBlob = b;
      return 'blob:reactor-test/abc';
    });
  });

  afterEach(() => {
    if (originalCreateObjectURL) {
      URL.createObjectURL = originalCreateObjectURL;
    } else {
      delete (URL as Partial<typeof URL>).createObjectURL;
    }
  });

  it('absolutizes path-only chunk URLs against the playlist URL', async () => {
    const url = createPlayableManifestUrl(
      SAMPLE_MANIFEST,
      'http://localhost:8080/clips?session_id=rec-123&start=0&end=8',
    );

    expect(url).toBe('blob:reactor-test/abc');
    expect(capturedBlob).not.toBeNull();
    const text = await capturedBlob!.text();

    expect(text).toContain('#EXT-X-MAP:URI="http://localhost:8080/clips/chunks/rec-123/init.mp4"');
    expect(text).toContain('http://localhost:8080/clips/chunks/rec-123/chunk_00000.m4s');
    expect(text).toContain('http://localhost:8080/clips/chunks/rec-123/chunk_00001.m4s');
  });

  it('leaves absolute chunk URLs unchanged (presigned URLs must not be touched)', async () => {
    const manifest =
      '#EXTM3U\n' +
      '#EXT-X-VERSION:7\n' +
      '#EXT-X-MAP:URI="https://s3.amazonaws.com/bucket/sess/init.mp4?sig=x"\n' +
      '#EXTINF:10.000,\n' +
      'https://s3.amazonaws.com/bucket/sess/chunk_00000.m4s?sig=y\n' +
      '#EXT-X-ENDLIST\n';

    createPlayableManifestUrl(manifest, 'https://api.reactor.inc/clips?session_id=sess&start=0&end=10');
    const text = await capturedBlob!.text();

    expect(text).toContain('URI="https://s3.amazonaws.com/bucket/sess/init.mp4?sig=x"');
    expect(text).toContain('https://s3.amazonaws.com/bucket/sess/chunk_00000.m4s?sig=y');
    expect(text).not.toContain('api.reactor.inc/bucket/');
  });

  it('preserves the HLS Content-Type marker and comment/directive lines', async () => {
    createPlayableManifestUrl(SAMPLE_MANIFEST, 'http://localhost:8080/clips?session_id=rec-123');

    expect(capturedBlob!.type).toBe('application/vnd.apple.mpegurl');
    const text = await capturedBlob!.text();

    expect(text).toContain('#EXTM3U');
    expect(text).toContain('#EXT-X-TARGETDURATION:4');
    expect(text).toContain('#EXT-X-ENDLIST');
  });

  it('throws INVALID_PLAYLIST outside a browser environment', () => {
    delete (URL as Partial<typeof URL>).createObjectURL;
    expect(() =>
      createPlayableManifestUrl(SAMPLE_MANIFEST, 'http://localhost:8080/clips?session_id=rec-123'),
    ).toThrow(RecordingError);
  });
});

describe('fetchPlaylist', () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('returns the body on 200', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(new Response(SAMPLE_MANIFEST, { status: 200 })) as never;

    const body = await fetchPlaylist('http://localhost/clips?x=1');

    expect(body).toBe(SAMPLE_MANIFEST);
  });

  it('retries on 202 then returns 200', async () => {
    let calls = 0;

    globalThis.fetch = vi.fn().mockImplementation(() => {
      calls++;
      if (calls === 1) {
        return Promise.resolve(new Response(null, { status: 202, headers: { 'Retry-After': '0' } }));
      }
      return Promise.resolve(new Response(SAMPLE_MANIFEST, { status: 200 }));
    }) as never;

    const body = await fetchPlaylist('http://localhost/clips?x=1', {
      predictedReadyAtMs: Date.now() + 10_000,
      minRetryDelayMs: 0,
      maxRetryDelayMs: 0,
    });

    expect(body).toBe(SAMPLE_MANIFEST);
    expect(calls).toBe(2);
  });

  it('throws CLIP_GONE on 410', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(new Response(null, { status: 410 })) as never;

    await expect(fetchPlaylist('http://localhost/clips?x=1')).rejects.toMatchObject({ code: 'CLIP_GONE' });
  });

  it('throws CLIP_GONE on 404', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(new Response(null, { status: 404 })) as never;

    await expect(fetchPlaylist('http://localhost/clips?x=1')).rejects.toMatchObject({ code: 'CLIP_GONE' });
  });

  it('throws PLAYLIST_FETCH_FAILED on other 4xx/5xx', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(new Response(null, { status: 500 })) as never;

    await expect(fetchPlaylist('http://localhost/clips?x=1')).rejects.toMatchObject({
      code: 'PLAYLIST_FETCH_FAILED',
    });
  });

  it('throws PLAYLIST_FETCH_FAILED on a network error', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new Error('offline')) as never;

    await expect(fetchPlaylist('http://localhost/clips?x=1')).rejects.toMatchObject({
      code: 'PLAYLIST_FETCH_FAILED',
    });
  });

  it('gives the full slack window when polling starts after predictedReadyAtMs (late click)', async () => {
    let calls = 0;

    globalThis.fetch = vi.fn().mockImplementation(() => {
      calls++;
      if (calls < 3) {
        return Promise.resolve(new Response(null, { status: 202, headers: { 'Retry-After': '0' } }));
      }
      return Promise.resolve(new Response(SAMPLE_MANIFEST, { status: 200 }));
    }) as never;

    const body = await fetchPlaylist('http://localhost/clips?x=1', {
      predictedReadyAtMs: Date.now() - 5_000,
      slackMs: 1_000,
      minRetryDelayMs: 0,
      maxRetryDelayMs: 0,
    });

    expect(body).toBe(SAMPLE_MANIFEST);
    expect(calls).toBe(3);
  });

  it('throws CLIP_NOT_READY when an opt-in slackMs deadline passes with a stuck 202', async () => {
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 202, headers: { 'Retry-After': '0' } })) as never;

    await expect(
      fetchPlaylist('http://localhost/clips?x=1', {
        predictedReadyAtMs: Date.now() - 10_000,
        slackMs: 0,
        minRetryDelayMs: 0,
        maxRetryDelayMs: 0,
      }),
    ).rejects.toMatchObject({ code: 'CLIP_NOT_READY' });
  });

  it('throws CLIP_NOT_READY when an opt-in maxRetries cap is exhausted', async () => {
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 202, headers: { 'Retry-After': '0' } })) as never;

    await expect(
      fetchPlaylist('http://localhost/clips?x=1', { maxRetries: 1, minRetryDelayMs: 0, maxRetryDelayMs: 0 }),
    ).rejects.toMatchObject({ code: 'CLIP_NOT_READY' });
  });

  it('polls past the predicted-ready epoch by default (no bound → no CLIP_NOT_READY)', async () => {
    let calls = 0;

    globalThis.fetch = vi.fn().mockImplementation(() => {
      calls++;
      if (calls <= 10) {
        return Promise.resolve(new Response(null, { status: 202, headers: { 'Retry-After': '0' } }));
      }
      return Promise.resolve(new Response(SAMPLE_MANIFEST, { status: 200 }));
    }) as never;

    const body = await fetchPlaylist('http://localhost/clips?x=1', {
      predictedReadyAtMs: Date.now() - 60_000,
      minRetryDelayMs: 0,
      maxRetryDelayMs: 0,
    });

    expect(body).toBe(SAMPLE_MANIFEST);
    expect(calls).toBe(11);
  });

  it('polls indefinitely by default and stops only when the signal aborts', async () => {
    let calls = 0;

    globalThis.fetch = vi.fn().mockImplementation((_url: string, init?: RequestInit) => {
      if (init?.signal?.aborted) {
        return Promise.reject(new DOMException('Aborted', 'AbortError'));
      }
      calls++;
      return Promise.resolve(new Response(null, { status: 202, headers: { 'Retry-After': '0' } }));
    }) as never;

    const controller = new AbortController();
    const promise = fetchPlaylist('http://localhost/clips?x=1', {
      predictedReadyAtMs: Date.now() - 60_000,
      minRetryDelayMs: 0,
      maxRetryDelayMs: 0,
      signal: controller.signal,
    });

    await new Promise((r) => setTimeout(r, 20));
    controller.abort();

    await expect(promise).rejects.toBeInstanceOf(DOMException);
    expect(calls).toBeGreaterThan(5);
  });
});

describe('downloadClipAsFile', () => {
  const initBytes = new Uint8Array([1, 2, 3, 4]);
  const chunk0 = new Uint8Array([5, 6, 7, 8, 9, 10]);
  const chunk1 = new Uint8Array([11, 12, 13]);
  const concatSize = initBytes.byteLength + chunk0.byteLength + chunk1.byteLength;

  function installChunkFetchMock(): ReturnType<typeof vi.fn> {
    const mockFetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/clips?')) {
        return Promise.resolve(new Response(SAMPLE_MANIFEST, { status: 200 }));
      }
      if (url.endsWith('/init.mp4')) {
        return Promise.resolve(new Response(initBytes, { status: 200 }));
      }
      if (url.endsWith('/chunk_00000.m4s')) {
        return Promise.resolve(new Response(chunk0, { status: 200 }));
      }
      if (url.endsWith('/chunk_00001.m4s')) {
        return Promise.resolve(new Response(chunk1, { status: 200 }));
      }
      return Promise.resolve(new Response(null, { status: 404 }));
    });

    globalThis.fetch = mockFetch as never;
    return mockFetch;
  }

  let realLoadMp4Box: typeof __remuxInternals.loadMp4Box;

  beforeEach(() => {
    realLoadMp4Box = __remuxInternals.loadMp4Box;
    // Default: remux throws, exercising the silent-fallback path — keeps the
    // byte-count assertions honest without a real fMP4 fixture. Tests that
    // care about the success path override this with a passthrough stub.
    __remuxInternals.loadMp4Box = () => Promise.reject(new Error('stubbed'));
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  });
  afterEach(() => {
    __remuxInternals.loadMp4Box = realLoadMp4Box;
    vi.restoreAllMocks();
  });

  it('fetches the playlist and every chunk, in order, and assembles a Blob', async () => {
    installChunkFetchMock();
    const onProgress = vi.fn();

    const blob = await downloadClipAsFile(SAMPLE_CLIP, null, { onProgress });

    expect(blob).toBeInstanceOf(Blob);
    expect(blob.size).toBe(concatSize);
    expect(onProgress).toHaveBeenCalledTimes(3);
    expect(onProgress).toHaveBeenLastCalledWith(expect.objectContaining({ fetched: 3, total: 3 }));
  });

  it('rejects with CHUNK_FETCH_FAILED when a chunk 5xxs', async () => {
    globalThis.fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/clips?')) {
        return Promise.resolve(new Response(SAMPLE_MANIFEST, { status: 200 }));
      }
      if (url.endsWith('/init.mp4')) {
        return Promise.resolve(new Response(new Uint8Array([1, 2]), { status: 200 }));
      }
      return Promise.resolve(new Response(null, { status: 503 }));
    }) as never;

    await expect(downloadClipAsFile(SAMPLE_CLIP, null)).rejects.toMatchObject({
      code: 'CHUNK_FETCH_FAILED',
    });
  });

  it('falls back to the byte-concatenated fMP4 on remux failure (logged via console.warn)', async () => {
    installChunkFetchMock();

    const blob = await downloadClipAsFile(SAMPLE_CLIP, null);
    const out = new Uint8Array(await blob.arrayBuffer());

    expect(out.byteLength).toBe(concatSize);
    expect(out[0]).toBe(initBytes[0]);
    expect(console.warn).toHaveBeenCalledTimes(1);
  });

  it('throws DOWNLOAD_UNSUPPORTED outside a DOM environment when a filename is given', async () => {
    installChunkFetchMock();
    const originalDocument = globalThis.document;

    // @ts-expect-error - simulating a non-DOM environment
    delete globalThis.document;
    try {
      await expect(downloadClipAsFile(SAMPLE_CLIP, 'clip.mp4')).rejects.toMatchObject({
        code: 'DOWNLOAD_UNSUPPORTED',
      });
    } finally {
      globalThis.document = originalDocument;
    }
  });
});
