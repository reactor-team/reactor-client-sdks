# Changelog

All notable changes to `reactor-sdk` are documented here. This file starts
after 1.2.0 — 1.2.0 and every release before it (1.0.0 through 1.2.0) predate
this file and aren't backfilled; see their GitHub release notes instead.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/);
versioning follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed

- `Reactor.close()` now settles any operation still in flight (e.g. a
  `send_command()` whose reply hadn't arrived yet) with an `AbortedError`,
  instead of leaving the caller's `await` hung for the life of the process.
  `disconnect()` was never affected — only the synchronous `close()` path.
