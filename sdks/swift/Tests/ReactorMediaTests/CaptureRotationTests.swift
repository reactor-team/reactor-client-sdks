import CoreGraphics
import Testing

@testable import ReactorMedia

@Suite("Capture rotation")
struct CaptureRotationTests {

    @Test("the camera's native orientation is the one that needs no rotation")
    func nativeOrientationIsLandscapeLeft() {
        // Not portrait, which is the assumption that produces sideways video:
        // a phone's rear sensor is mounted landscape, and zero degrees is what
        // it already gives you.
        #expect(CaptureOrientation.landscapeLeft.degrees == 0)
    }

    @Test("every orientation maps to a distinct quarter turn")
    func anglesAreDistinctQuarterTurns() {
        let angles = CaptureOrientation.allCases.map(\.degrees)
        #expect(Set(angles).count == CaptureOrientation.allCases.count)
        for angle in angles {
            #expect(angle >= 0 && angle < 360)
            #expect(angle.truncatingRemainder(dividingBy: 90) == 0)
        }
    }

    @Test("opposite ways up are half a turn apart")
    func oppositesAreHalfATurnApart() {
        // The check that catches a table transposed by one step, which still
        // looks orderly and puts every frame a quarter turn out.
        func apart(_ a: CaptureOrientation, _ b: CaptureOrientation) -> CGFloat {
            let d = abs(a.degrees - b.degrees)
            return min(d, 360 - d)
        }
        #expect(apart(.portrait, .portraitUpsideDown) == 180)
        #expect(apart(.landscapeLeft, .landscapeRight) == 180)
        #expect(apart(.portrait, .landscapeLeft) == 90)
    }
}
