#!/usr/bin/env bash
# Retries "$@" up to 4 times total (1 initial attempt + 3 retries) with a
# fixed 2s/4s/8s backoff between attempts.
#
# Used to absorb transient failures shared across every SDK's
# integration-tests suite — reactor/echo session-creation rate limits, its
# shared capacity pool being briefly exhausted, a session or SDP answer not
# ready within its own poll budget — without masking a real regression: a
# genuine bug fails deterministically on every attempt and still fails in
# the end.
#
# Called from inside each `test:<lang>:integration-tests` mise task (or,
# for Swift, from within scripts/swift.sh), wrapping only the actual
# test-invocation line — never a build/cmake/cargo step ahead of it. That
# makes the task itself retry everywhere it's invoked (ci.yml, every
# release-<lang>.yml, and a contributor running it locally) with nothing
# for any caller to remember to wrap on its own.
#
# Deliberately NOT scoped to a specific error type or exception class. This
# repo's four SDKs each surface failures through a different test framework
# (pytest, Catch2, Swift Testing, Playwright) with no shared way to filter
# "only rerun on this kind of failure," and every previous attempt at a
# per-language scoped rule here has drifted (Python's pytest-rerunfailures
# config caught only RateLimitedError, missing the capacity/timeout cases;
# Swift's own connect-retry comment claimed to cover capacity but its code
# didn't). One flat rule, enforced once, here — see the sdk-from-ffi skill's
# "Integration-test retries" section before adding a framework-specific
# retry config back on top of this.
#
# Usage: scripts/retry.sh <command> [args...]
set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo "usage: retry.sh <command> [args...]" >&2
  exit 1
fi

delays=(2 4 8)
attempt=1
until "$@"; do
  if (( attempt > ${#delays[@]} )); then
    echo "::error::'$*' failed after $attempt attempts, giving up" >&2
    exit 1
  fi
  delay="${delays[$((attempt - 1))]}"
  echo "::warning::'$*' failed (attempt $attempt/$(( ${#delays[@]} + 1 ))), retrying in ${delay}s..." >&2
  sleep "$delay"
  ((attempt++))
done
