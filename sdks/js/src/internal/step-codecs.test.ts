import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DecodeError } from '../errors';
import {
  FakeVideoDecoder,
  FakeVideoEncoder,
  FakeVideoFrame,
  fakeSupport,
  fakeWebCodecs,
} from './fake-web-codecs';
import type { StepFrame, StepResult } from './reactor-wasm.types';
import { InputEncoders, OutputDecoders, probeStepCodecs, type WebCodecs } from './step-codecs';
import type { Pixels } from './push-frames';

let api: WebCodecs;

beforeEach(() => {
  api = fakeWebCodecs();
});

const pixels = (width = 2, height = 2): Pixels => ({
  data: new Uint8Array(width * height * 4),
  width,
  height,
  format: 'rgba',
});

describe('probeStepCodecs', () => {
  it('lists what the browser encodes and decodes, H.264 first', async () => {
    await expect(probeStepCodecs(api)).resolves.toEqual({
      encode: ['h264', 'vp9', 'av1', 'vp8'],
      decode: ['h264', 'vp9', 'av1', 'vp8'],
    });
  });

  it('leaves out what the browser does not support, in each direction', async () => {
    fakeSupport.all = false;
    fakeSupport.encode.add('vp8');
    fakeSupport.decode.add('avc1.42e033').add('vp8');

    await expect(probeStepCodecs(api)).resolves.toEqual({ encode: ['vp8'], decode: ['h264', 'vp8'] });
  });

  it('offers nothing without WebCodecs', async () => {
    await expect(probeStepCodecs({})).resolves.toEqual({ encode: [], decode: [] });
  });
});

describe('InputEncoders', () => {
  it('starts a track at a keyframe and follows with inter frames', async () => {
    const encoders = new InputEncoders(api);

    const first = await encoders.encode('cam', 'h264', pixels(), false);
    const second = await encoders.encode('cam', 'h264', pixels(), false);

    expect(first).toEqual([{ data: new Uint8Array([1, 1]), keyframe: true }]);
    expect(second).toEqual([{ data: new Uint8Array([0, 2]), keyframe: false }]);
    expect(FakeVideoEncoder.instances).toHaveLength(1);
    expect(FakeVideoEncoder.instances[0]?.config).toMatchObject({
      codec: 'avc1.42e033',
      width: 2,
      height: 2,
      latencyMode: 'realtime',
      avc: { format: 'annexb' },
    });
    // Every frame it made was closed once handed to the encoder.
    expect(FakeVideoFrame.open).toBe(0);
  });

  it('makes a keyframe when asked', async () => {
    const encoders = new InputEncoders(api);

    await encoders.encode('cam', 'vp9', pixels(), false);
    const [frame] = await encoders.encode('cam', 'vp9', pixels(), true);

    expect(frame?.keyframe).toBe(true);
    expect(FakeVideoEncoder.instances[0]?.config?.codec).toBe('vp09.00.51.08');
    expect(FakeVideoEncoder.instances[0]?.config).not.toHaveProperty('avc');
  });

  it('starts every track again at a keyframe after a reset, as on a new connection', async () => {
    const encoders = new InputEncoders(api);

    await encoders.encode('cam', 'h264', pixels(), false);
    encoders.reset();
    const [frame] = await encoders.encode('cam', 'h264', pixels(), false);

    expect(frame?.keyframe).toBe(true);
    expect(FakeVideoEncoder.instances.map((encoder) => encoder.state)).toEqual(['closed', 'configured']);
  });

  it('starts a new stream when the size or the codec changes', async () => {
    const encoders = new InputEncoders(api);

    await encoders.encode('cam', 'h264', pixels(2, 2), false);
    const [resized] = await encoders.encode('cam', 'h264', pixels(4, 2), false);
    const [recoded] = await encoders.encode('cam', 'vp8', pixels(4, 2), false);

    expect(resized?.keyframe).toBe(true);
    expect(recoded?.keyframe).toBe(true);
    expect(FakeVideoEncoder.instances).toHaveLength(3);
  });

  it('keeps each track its own stream', async () => {
    const encoders = new InputEncoders(api);

    await encoders.encode('cam', 'h264', pixels(), false);
    const [other] = await encoders.encode('depth', 'h264', pixels(), false);

    expect(other?.keyframe).toBe(true);
  });

  it('drops a failed encoder, so the track starts again at a keyframe', async () => {
    const encoders = new InputEncoders(api);

    await encoders.encode('cam', 'h264', pixels(), false);
    FakeVideoEncoder.failNext = true;
    await expect(encoders.encode('cam', 'h264', pixels(), false)).rejects.toThrow('encoder failed');
    const [frame] = await encoders.encode('cam', 'h264', pixels(), false);

    expect(frame?.keyframe).toBe(true);
  });

  it('closes an encoder whose configure() throws', async () => {
    const encoders = new InputEncoders(api);
    const configure = vi.spyOn(FakeVideoEncoder.prototype, 'configure').mockImplementationOnce(() => {
      throw new Error('unsupported size');
    });

    await expect(encoders.encode('cam', 'h264', pixels(), false)).rejects.toThrow('unsupported size');
    expect(FakeVideoEncoder.instances.map((encoder) => encoder.state)).toEqual(['closed']);
    configure.mockRestore();
  });

  it('gives RGB pixels an opaque alpha', async () => {
    const encoders = new InputEncoders(api);
    const rgb: Pixels = { data: new Uint8Array([1, 2, 3]), width: 1, height: 1, format: 'rgb' };

    await expect(encoders.encode('cam', 'h264', rgb, false)).resolves.toHaveLength(1);
  });
});

function encodedFrame(byte: number, keyframe: boolean, codec: StepFrame['encoding'] = 'h264'): StepFrame {
  return { encoding: codec, dtype: 'uint8', shape: [1, 1, 3], data: new Uint8Array([byte]), keyframe };
}

function result(...frames: StepFrame[]): StepResult {
  return { step: 1, tracks: [{ track: 'out', kind: 'video', rate: 30, frames }], elapsed: 0, files: [] };
}

describe('OutputDecoders', () => {
  it('decodes an encoded track to RGB, keeping what the frame carried', async () => {
    const decoders = new OutputDecoders(api);
    const metadata = new Uint8Array([9]);

    const decoded = await decoders.decodeResult(
      result({ ...encodedFrame(10, true), metadata, captureTimeUs: 5 }, encodedFrame(11, false)),
    );

    expect(decoded.tracks[0]?.frames).toEqual([
      {
        encoding: 'h264',
        dtype: 'uint8',
        shape: [1, 1, 3],
        data: new Uint8Array([10, 2, 3]),
        keyframe: true,
        metadata,
        captureTimeUs: 5,
      },
      { encoding: 'h264', dtype: 'uint8', shape: [1, 1, 3], data: new Uint8Array([11, 2, 3]), keyframe: false },
    ]);
    expect(decoders.needsKeyframe()).toBe(false);
    expect(FakeVideoFrame.open).toBe(0);
  });

  it('hands a result with no encoded frame back as it is', async () => {
    const decoders = new OutputDecoders(api);
    const raw = result({ encoding: 'raw', dtype: 'uint8', shape: [1, 1, 3], data: new Uint8Array(3) });

    await expect(decoders.decodeResult(raw)).resolves.toBe(raw);
    expect(FakeVideoDecoder.instances).toHaveLength(0);
  });

  it('keeps one stream per track across results, fed in the order they arrive', async () => {
    const decoders = new OutputDecoders(api);
    // Both handed over before either is awaited, as two results that arrive
    // one after the other are: the second continues the first's stream.
    const first = decoders.decodeResult(result(encodedFrame(1, true)));
    const second = decoders.decodeResult(result(encodedFrame(2, false)));

    expect((await first).tracks[0]?.frames[0]?.data).toEqual(new Uint8Array([1, 2, 3]));
    expect((await second).tracks[0]?.frames[0]?.data).toEqual(new Uint8Array([2, 2, 3]));
    expect(FakeVideoDecoder.instances).toHaveLength(1);
  });

  it('refuses a stream that does not start at a keyframe, and waits for one', async () => {
    const decoders = new OutputDecoders(api);

    await expect(decoders.decodeResult(result(encodedFrame(1, false)))).rejects.toBeInstanceOf(DecodeError);
    expect(decoders.needsKeyframe()).toBe(true);

    await decoders.decodeResult(result(encodedFrame(2, true)));
    expect(decoders.needsKeyframe()).toBe(false);
  });

  it('drops a decoder that fails, and asks for a keyframe until one restarts the track', async () => {
    const decoders = new OutputDecoders(api);

    await decoders.decodeResult(result(encodedFrame(1, true)));
    await expect(decoders.decodeResult(result(encodedFrame(0xff, false)))).rejects.toThrow(DecodeError);
    expect(decoders.needsKeyframe()).toBe(true);
    // An inter frame cannot restart it.
    await expect(decoders.decodeResult(result(encodedFrame(3, false)))).rejects.toThrow('waiting for a keyframe');

    const restarted = await decoders.decodeResult(result(encodedFrame(4, true)));

    expect(restarted.tracks[0]?.frames[0]?.data).toEqual(new Uint8Array([4, 2, 3]));
    expect(decoders.needsKeyframe()).toBe(false);
    expect(FakeVideoDecoder.instances.map((decoder) => decoder.state)).toEqual(['closed', 'configured']);
  });

  it('starts every track again at a keyframe after a reset, as on a new connection', async () => {
    const decoders = new OutputDecoders(api);

    await decoders.decodeResult(result(encodedFrame(1, true)));
    decoders.reset();

    await expect(decoders.decodeResult(result(encodedFrame(2, false)))).rejects.toThrow('waiting for a keyframe');
    await expect(decoders.decodeResult(result(encodedFrame(3, true)))).resolves.toBeDefined();
  });

  it('closes a decoder whose configure() throws', async () => {
    const decoders = new OutputDecoders(api);
    const configure = vi.spyOn(FakeVideoDecoder.prototype, 'configure').mockImplementationOnce(() => {
      throw new Error('unsupported codec');
    });

    await expect(decoders.decodeResult(result(encodedFrame(1, true)))).rejects.toBeInstanceOf(DecodeError);
    expect(FakeVideoDecoder.instances.map((decoder) => decoder.state)).toEqual(['closed']);
    configure.mockRestore();
  });

  it('asks for keyframes once after abandon(), and starts every track again at one', async () => {
    const decoders = new OutputDecoders(api);

    await decoders.decodeResult(result(encodedFrame(1, true)));
    decoders.abandon();

    expect(decoders.takeKeyframeRequest()).toBe(true);
    expect(decoders.takeKeyframeRequest()).toBe(false);
    await expect(decoders.decodeResult(result(encodedFrame(2, false)))).rejects.toThrow('waiting for a keyframe');
  });

  it('keeps feeding the other tracks when one fails', async () => {
    const decoders = new OutputDecoders(api);
    const both: StepResult = {
      step: 1,
      tracks: [
        { track: 'a', kind: 'video', rate: 30, frames: [encodedFrame(1, false)] },
        { track: 'b', kind: 'video', rate: 30, frames: [encodedFrame(2, true)] },
      ],
      elapsed: 0,
      files: [],
    };

    await expect(decoders.decodeResult(both)).rejects.toBeInstanceOf(DecodeError);
    // 'b' started its stream all the same.
    const next = await decoders.decodeResult({
      ...both,
      tracks: [{ track: 'b', kind: 'video', rate: 30, frames: [encodedFrame(5, false)] }],
    });

    expect(next.tracks[0]?.frames[0]?.data).toEqual(new Uint8Array([5, 2, 3]));
  });

  it('fails an encoded frame without WebCodecs', async () => {
    const decoders = new OutputDecoders({});

    await expect(decoders.decodeResult(result(encodedFrame(1, true)))).rejects.toThrow('no WebCodecs VideoDecoder');
  });
});
