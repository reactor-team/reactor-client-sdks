#!/usr/bin/env bash
# Run clang-tidy over the C++ SDK, with a resource directory it can actually use.
#
# clang-tidy resolves the compiler's builtin headers — stddef.h, stdarg.h,
# mmintrin.h and the rest — from its own resource directory, and the conda
# clang-tools package ships none. So it has to be told where to look.
#
# The subtlety, and the reason this is a script rather than a line in mise.toml:
# the directory has to come from a clang of the *same major version*. Builtin
# headers reference `__builtin_*` intrinsics that only that version's front end
# knows, so clang-tidy 21 reading clang 18's mmintrin.h fails with a page of
# "use of undeclared identifier '__builtin_ia32_...'" — which looks like broken
# code and is not. It only shows up once some translation unit includes the x86
# intrinsics, which is why it stayed hidden until the audio target arrived.
#
# Usage: scripts/clang-tidy-cpp.sh <build-dir> [file...]
#
# With REACTOR_REQUIRE_CLANG_TIDY set, being unable to run is a failure rather
# than a skip: a gate that quietly checks nothing is worse than no gate.
set -euo pipefail

build_dir="${1:?usage: clang-tidy-cpp.sh <build-dir> [file...]}"
shift

if ! command -v clang-tidy >/dev/null; then
  message="clang-tidy is not on PATH"
  if [ -n "${REACTOR_REQUIRE_CLANG_TIDY:-}" ]; then
    echo "lint:cpp: $message, and REACTOR_REQUIRE_CLANG_TIDY is set." >&2
    exit 1
  fi
  echo "lint:cpp: skipping clang-tidy - $message"
  exit 0
fi

# "LLVM version 21.1.8" -> "21"
tidy_major="$(clang-tidy --version | sed -n 's/.*version \([0-9]\{1,\}\).*/\1/p' | head -1)"

resource_dir=""
# In preference order: the clang mise pins (same conda toolchain as clang-tidy),
# a versioned system clang, then whatever is building. The last two are what a
# contributor's machine is likely to have.
for candidate in \
  "$(mise which clang++ 2>/dev/null || true)" \
  "$(command -v "clang++-${tidy_major}" || true)" \
  "${CXX:-}" \
  "$(command -v clang++ || true)" \
  "$(command -v c++ || true)"; do
  [ -n "$candidate" ] || continue
  candidate_dir="$("$candidate" -print-resource-dir 2>/dev/null || true)"
  [ -n "$candidate_dir" ] || continue
  # Its builtins have to be this clang-tidy's builtins.
  case "$(basename "$candidate_dir")" in
    "${tidy_major}" | "${tidy_major}."*)
      resource_dir="$candidate_dir"
      echo "lint:cpp: clang-tidy ${tidy_major} using builtins from $candidate"
      break
      ;;
  esac
done

if [ -z "$resource_dir" ]; then
  message="found no clang ${tidy_major} to take builtin headers from (tried mise, clang++-${tidy_major}, \$CXX, clang++, c++)"
  if [ -n "${REACTOR_REQUIRE_CLANG_TIDY:-}" ]; then
    echo "lint:cpp: $message, and REACTOR_REQUIRE_CLANG_TIDY is set." >&2
    exit 1
  fi
  echo "lint:cpp: skipping clang-tidy - $message"
  exit 0
fi

if [ "$#" -eq 0 ]; then
  echo "lint:cpp: no files to check"
  exit 0
fi

exec clang-tidy -p "$build_dir" --quiet --extra-arg="-resource-dir=$resource_dir" "$@"
