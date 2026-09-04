import Foundation
import Testing

@testable import ReactorMedia

@Suite("CaptureGate")
struct CaptureGateTests {

    @Test("a closed gate delivers nothing")
    func closedGateDropsEverything() {
        let gate = CaptureGate()
        var delivered = 0

        gate.withDelivery { delivered += 1 }

        #expect(delivered == 0)
        #expect(gate.droppedCaptures == 1)
        #expect(gate.delivered == 0)
    }

    @Test("an open gate delivers, and counts")
    func openGateDelivers() {
        let gate = CaptureGate()
        gate.open()
        var delivered = 0

        gate.withDelivery { delivered += 1 }
        gate.withDelivery { delivered += 1 }

        #expect(delivered == 2)
        #expect(gate.delivered == 2)
        #expect(gate.droppedCaptures == 0)
    }

    @Test("closing while a delivery is running does not block on it")
    func closingDoesNotWaitForADelivery() {
        // This is the whole reason the type exists. Closing a capture device
        // waits for the callback currently running, so if `close()` held a lock
        // that the callback needed, it would be waiting for the thread waiting
        // for it. Here: a delivery parks inside its closure, and close() must
        // still return immediately.
        let gate = CaptureGate()
        gate.open()

        let insideDelivery = DispatchSemaphore(value: 0)
        let releaseDelivery = DispatchSemaphore(value: 0)
        let closed = DispatchSemaphore(value: 0)

        DispatchQueue.global().async {
            gate.withDelivery {
                insideDelivery.signal()
                // Stands in for a push that is taking its time.
                _ = releaseDelivery.wait(timeout: .now() + 5)
            }
        }

        #expect(insideDelivery.wait(timeout: .now() + 2) == .success)

        DispatchQueue.global().async {
            gate.close()
            closed.signal()
        }

        // The assertion that matters: this returns while the delivery is still
        // in its closure.
        #expect(closed.wait(timeout: .now() + 2) == .success)
        #expect(!gate.isOpen)

        releaseDelivery.signal()
    }

    @Test("a capture that arrives while stopping is dropped rather than pushed")
    func captureWhileStoppingIsDropped() {
        let gate = CaptureGate()
        gate.open()
        gate.close()

        var delivered = 0
        gate.withDelivery { delivered += 1 }

        // Pushing into a track that is going away is the failure this prevents.
        #expect(delivered == 0)
        #expect(gate.droppedCaptures == 1)
    }

    @Test("counts survive concurrent deliveries")
    func concurrentDeliveriesAreCounted() {
        let gate = CaptureGate()
        gate.open()
        let counted = Counter()

        DispatchQueue.concurrentPerform(iterations: 200) { _ in
            gate.withDelivery { counted.increment() }
        }

        #expect(gate.delivered == 200)
        #expect(counted.current == 200)
    }
}
