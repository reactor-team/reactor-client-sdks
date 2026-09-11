import Testing

/// The shared suite both churn scenarios' `@Test` methods extend into (see
/// `LifecycleChurnTests.swift`/`SessionChurnTests.swift`) — declared here,
/// on its own, purely for the `.serialized` trait.
///
/// Swift Testing parallelizes test *and* suite execution by default,
/// including across suites in the same process (caught by Codex review on
/// PR #171): the two scenarios would otherwise run concurrently, each
/// sampling process-wide RSS/CPU/thread/fd counts that the *other* one's
/// workload is simultaneously perturbing — the resource counters
/// `ResourceSampler` reads have no notion of "this test's share," only the
/// whole process's. `.serialized` on a suite governs every `@Test` directly
/// in it, including ones added via `extension` in another file, which is
/// exactly what makes a single shared, empty, serialized suite the fix here
/// rather than something on each scenario individually.
@Suite(.serialized)
struct EnduranceTests {}
