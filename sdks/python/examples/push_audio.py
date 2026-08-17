#!/usr/bin/env python3
"""
Push audio example — stream PCM into a sendonly audio track.

Two modes:
  --wav FILE   Read a WAV file (any sample rate / channels) and re-sample to 48 kHz mono
  --sine HZ    Generate a sine tone at the given frequency (default: 440 Hz)

Audio is pushed in 10 ms chunks (480 samples at 48 kHz, interleaved i16 PCM).
The loop sleeps `chunk_duration - processing_time` between pushes to deliver
frames at real-time pace.

Usage:
    # Sine wave (A-440)
    python -m examples.push_audio --track audio_input --sine 440 --duration 10

    # Push a WAV file
    python -m examples.push_audio --track audio_input --wav hello.wav

    # WAV with a non-default track name
    python -m examples.push_audio --track my_track --wav speech.wav --duration 30

Environment variables (overridden by flags):
    REACTOR_API_URL, REACTOR_MODEL, REACTOR_JWT, REACTOR_LOCAL
"""

from __future__ import annotations

import argparse
import asyncio
import math
import struct
import sys
import time
import wave
from collections.abc import Generator
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from .reactor_client import make_reactor

SAMPLE_RATE = 48_000
CHUNK_SAMPLES = 480  # 10 ms at 48 kHz
CHUNK_SECS = CHUNK_SAMPLES / SAMPLE_RATE


def _parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Push audio frames into a Reactor sendonly track")
    p.add_argument(
        "--track", metavar="NAME", required=True, help="Name of the sendonly audio track"
    )

    src = p.add_mutually_exclusive_group(required=True)
    src.add_argument(
        "--sine", metavar="HZ", type=float, help="Generate a sine tone at HZ (default: 440)"
    )
    src.add_argument(
        "--wav", metavar="FILE", help="Push samples from a WAV file (resampled to 48 kHz mono i16)"
    )

    p.add_argument(
        "--duration",
        metavar="SECS",
        type=float,
        default=None,
        help="Stop after N seconds (default: file duration or 30 s for sine)",
    )
    p.add_argument("--model", metavar="NAME")
    p.add_argument("--api-url", metavar="URL")
    p.add_argument("--jwt", metavar="TOKEN")
    p.add_argument("--local", action="store_true", default=None)
    return p.parse_args()


def _sine_generator(
    freq_hz: float,
    sample_rate: int,
    chunk: int,
) -> Generator[bytes, None, None]:
    """Yield 10 ms chunks of i16 sine PCM indefinitely."""
    phase = 0.0
    step = 2.0 * math.pi * freq_hz / sample_rate
    while True:
        samples = []
        for _ in range(chunk):
            val = int(math.sin(phase) * 32767)
            samples.append(max(-32768, min(32767, val)))
            phase = (phase + step) % (2.0 * math.pi)
        yield struct.pack(f"<{chunk}h", *samples)


def _wav_generator(path: str, chunk: int) -> Generator[bytes, None, None]:
    """
    Read a WAV file and yield i16 mono chunks at 48 kHz.

    Resampling is nearest-neighbour (good enough for a demo).
    Mixed down to mono if stereo.
    """
    with wave.open(path, "rb") as w:
        n_channels = w.getnchannels()
        sampwidth = w.getsampwidth()
        orig_rate = w.getframerate()
        n_frames = w.getnframes()
        raw = w.readframes(n_frames)

    if sampwidth == 2:
        fmt = f"<{len(raw) // 2}h"
        samples_all: list[int] = list(struct.unpack(fmt, raw))
    elif sampwidth == 1:
        samples_all = [(b - 128) * 256 for b in raw]
    else:
        raise ValueError(f"Unsupported WAV sample width: {sampwidth} bytes")

    if n_channels > 1:
        mixed: list[int] = []
        for i in range(0, len(samples_all), n_channels):
            mixed.append(int(sum(samples_all[i : i + n_channels]) / n_channels))
        samples_all = mixed

    # Nearest-neighbour resample to SAMPLE_RATE
    if orig_rate != SAMPLE_RATE:
        ratio = orig_rate / SAMPLE_RATE
        new_len = int(len(samples_all) / ratio)
        samples_all = [
            samples_all[min(int(i * ratio), len(samples_all) - 1)] for i in range(new_len)
        ]

    # Yield in chunks, padding the last one with silence
    pos = 0
    while pos < len(samples_all):
        slice_ = samples_all[pos : pos + chunk]
        if len(slice_) < chunk:
            slice_ = slice_ + [0] * (chunk - len(slice_))
        yield struct.pack(f"<{chunk}h", *slice_)
        pos += chunk


async def main() -> None:
    args = _parse_args()
    duration = args.duration

    if args.sine is not None:
        freq = args.sine if args.sine > 0 else 440.0
        if duration is None:
            duration = 30.0
        gen = _sine_generator(freq, SAMPLE_RATE, CHUNK_SAMPLES)
        print(f"Generating {freq:.0f} Hz sine wave, {duration:.1f}s", file=sys.stderr)
    else:
        if not Path(args.wav).exists():
            print(f"File not found: {args.wav}", file=sys.stderr)
            sys.exit(1)
        gen = _wav_generator(args.wav, CHUNK_SAMPLES)
        print(f"Reading WAV: {args.wav}", file=sys.stderr)

    reactor = make_reactor(
        api_url=args.api_url,
        model_name=args.model,
        jwt=args.jwt,
        local=args.local if args.local else None,
    )
    reactor.on("error", lambda e: print(f"[error] {e}", file=sys.stderr))

    ready = asyncio.Event()
    reactor.on("status_changed", lambda s: ready.set() if s == "ready" else None)

    print("Connecting…", file=sys.stderr)
    await reactor.connect()
    await asyncio.wait_for(ready.wait(), timeout=60)
    print("Ready. Publishing track and pushing audio…", file=sys.stderr)

    # The track knows it is a sendonly audio track, so push_frame below needs no
    # kind in its name and no track name in its arguments.
    track = await reactor.publish_track(args.track)

    chunks_sent = 0
    t_start = time.monotonic()
    deadline = t_start + duration if duration else None

    for chunk_pcm in gen:
        loop_start = time.monotonic()

        if deadline and loop_start >= deadline:
            break

        track.push_frame(
            chunk_pcm,
            samples_per_channel=CHUNK_SAMPLES,
            sample_rate=SAMPLE_RATE,
            num_channels=1,
        )
        chunks_sent += 1

        elapsed = time.monotonic() - loop_start
        sleep_for = max(0.0, CHUNK_SECS - elapsed)
        await asyncio.sleep(sleep_for)

    total = time.monotonic() - t_start
    print(
        f"Done — pushed {chunks_sent} chunks ({chunks_sent * CHUNK_SECS:.2f}s audio) "
        f"in {total:.2f}s real time",
        file=sys.stderr,
    )

    track.unpublish()
    await reactor.disconnect()
    reactor.close()


if __name__ == "__main__":
    asyncio.run(main())
