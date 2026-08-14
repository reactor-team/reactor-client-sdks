# Contributing to reactor-client-sdks

Thanks for taking the time to contribute. This document covers everything
you need to get a change from your machine into `main`.

## Table of contents

- [Getting set up](#getting-set-up)
- [Building and testing](#building-and-testing)
- [Code style](#code-style)
- [Commit messages](#commit-messages)
- [Developer Certificate of Origin (DCO)](#developer-certificate-of-origin-dco)
- [Opening a pull request](#opening-a-pull-request)
- [Reporting a bug or requesting a feature](#reporting-a-bug-or-requesting-a-feature)

## Getting set up

The whole toolchain — Rust, `uv`, `ruff`, `cargo-nextest`, `shellcheck`,
[hk](https://github.com/jdx/hk) — is pinned by [mise](https://mise.jdx.dev)
and locked in `mise.lock`. Nothing else needs to be installed globally.

```bash
mise trust
mise install             # installs the pinned toolchain
mise run install-hooks   # wires up pre-commit / pre-push hooks (via hk)
```

On Intel Macs (`darwin/amd64`), `mise install` fails on `hk` — it only ships
binaries for `linux`, `darwin/arm64` and `windows`. Skip it and install
everything else:

```bash
MISE_DISABLE_TOOLS=hk mise install
```

You'll just miss `mise run install-hooks` (pre-commit/pre-push git hooks);
everything else — building, linting, testing — works the same.

A thin `make` shim forwards to the same tasks (`make ci`, `make test`,
`make help`) at the repo root. Run `mise tasks` any time to see the full
list.

## Building and testing

```bash
cargo check --workspace                     # build.rs auto-downloads the matching
                                             # prebuilt libwebrtc for reactor-ffi's target
mise run lint                               # fmt-check + clippy + ruff + repo lints
mise run test                               # test:rust (nextest + doctests) + test:python (pytest)
mise run ci                                 # lint + test — the exact tasks CI runs
mise run build:wheel                        # cargo build --release, then a wheel with it bundled
```

If `mise run ci` passes locally, CI should too. If you're working on the
Python SDK, see [`sdks/python/README.md`](sdks/python/README.md#the-native-library)
for how the native library is resolved (an editable install picks up a
local `cargo build` automatically).

## Code style

- Rust: `rustfmt` (`mise run fmt`) and `clippy` with warnings denied
  (`mise run clippy`, covers every crate including `reactor-ffi`). Both run
  in `mise run ci` and again in CI.
- Python: `ruff` check + format (`mise run lint:python`;
  `sdks/python/pyproject.toml` sets `line-length = 100`, target `py310`).
- Shell: `shellcheck` over every tracked script and bash `mise-tasks`
  (`mise run lint:shell`).
- ABI: `mise run lint:abi` checks that `lib.rs`, `reactor_ffi.h` and
  `_ffi.py` all declare the same C ABI surface — a real safeguard now that
  the header, the Rust `#[no_mangle]` functions, and the Python `ctypes`
  bindings are three separate places the same signature has to match.
- Git hooks (installed via `mise run install-hooks`) run the fast subset of
  these on `pre-commit` and the heavier, compiling checks on `pre-push` —
  so most issues surface before you even open a PR.

## Commit messages

Commits follow `type(scope): summary`, optionally referencing a tracking
ticket, e.g.:

```
feat(reactor-core): platform-agnostic business logic crate
fix(reactor-core): atomic status guard, heartbeat, tokio-platform default
refactor(ffi): drop the hand-rolled metadata transform, require reactor-webrtc 0.7 (REA-4908)
chore: lock reactor-webrtc 0.7.0
```

Common types: `feat`, `fix`, `refactor`, `chore`, `ci`, `docs`. The scope is
usually the crate or area touched (`reactor-core`, `ffi`, `python-sdk`).
Keep the summary imperative and under ~72 characters; use the body to
explain the *why* when it isn't obvious from the diff.

## Developer Certificate of Origin (DCO)

Every commit must carry a `Signed-off-by` trailer certifying you wrote it
(or otherwise have the right to submit it under this project's license).
CI enforces this on every pull request.

```bash
git commit -s -m "fix(python-sdk): ..."
```

If you forgot on a commit that's already pushed:

```bash
git rebase --signoff main
git push --force-with-lease
```

## Opening a pull request

1. Branch off `main`.
2. Make your change, with tests (see [Building and testing](#building-and-testing)).
   If it touches anything documented — the root README, an SDK's own
   README, or a guide under `docs/` — update that documentation and its
   code examples in the same PR, not as a follow-up.
3. Push and open a PR. `main` requires, before merging:
   - At least one approving review, including a review from a code owner
     (see [`.github/CODEOWNERS`](.github/CODEOWNERS)).
   - The `CI Complete` status check passing.
   - No unresolved review threads.
4. PRs merge via a regular merge commit or squash — either is allowed.

## Reporting a bug or requesting a feature

Open a [GitHub issue](https://github.com/reactor-team/reactor-client-sdks/issues)
with as much detail as you can: platform, SDK language and version, and —
for a bug — a minimal repro.
