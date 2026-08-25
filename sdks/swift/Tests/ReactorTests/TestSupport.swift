import Foundation

/// Wait for `condition` without blocking a thread.
///
/// Every test here that waits on something the SDK does asynchronously goes
/// through this, and the reason is a CI failure rather than taste: a *synchronous*
/// test that busy-polls with `usleep` blocks a thread of the cooperative pool, and
/// on a runner with few cores the `Task` it is waiting for may never be scheduled
/// inside the timeout. It passed on a laptop with ten cores and failed on CI.
///
/// `Task.sleep` yields instead, so the work being waited for can actually run.
///
/// Returns `false` on timeout rather than trapping, so a caller can turn "this
/// never happened" into a named expectation — which matters most for the tests
/// whose regression is a *hang*: awaiting the task directly would take the whole
/// suite down with it, because a continuation that is never resumed ignores
/// cancellation.
func waitUntil(
    timeout: Duration = .seconds(5),
    _ condition: @Sendable () -> Bool
) async -> Bool {
    let deadline = ContinuousClock.now.advanced(by: timeout)
    while !condition() {
        if ContinuousClock.now > deadline { return false }
        try? await Task.sleep(for: .milliseconds(5))
    }
    return true
}
