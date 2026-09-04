import CoreGraphics

/// How the capture pipeline has to turn a frame to put it upright.
///
/// A phone's camera sensor is mounted landscape, and `AVCaptureVideoDataOutput`
/// hands over what the sensor saw. So a portrait iPhone pushed the model a
/// picture lying on its side, and nothing downstream could tell: the frame is
/// well-formed, correctly sized, and wrong. Only the device's own orientation
/// says which way is up, and only at capture time.
///
/// Its own type rather than `UIDeviceOrientation` for one reason: that type does
/// not exist off iOS, and neither would a test of this table. The angles are
/// where the mistake lives — every one of them is easy to get backwards — so
/// they are stated somewhere a test can read them, and the iOS code maps its
/// platform enum onto this and nothing more.
enum CaptureOrientation: Equatable, CaseIterable {

    /// Held upright.
    case portrait

    /// Held upright, upside down.
    case portraitUpsideDown

    /// Turned anticlockwise — on a phone, the camera's own native orientation.
    case landscapeLeft

    /// Turned clockwise.
    case landscapeRight

    /// Degrees clockwise the capture connection must rotate to deliver upright.
    ///
    /// Zero is the camera's native orientation, which for a phone's rear sensor
    /// is the device held in `landscapeLeft`. Everything else is measured from
    /// there, which is why portrait is 90 rather than 0 — the case that looks
    /// like it should be the identity is the one that is not.
    var degrees: CGFloat {
        switch self {
        case .landscapeLeft: return 0
        case .portrait: return 90
        case .landscapeRight: return 180
        case .portraitUpsideDown: return 270
        }
    }
}

#if os(iOS)
    import AVFoundation
    import UIKit

    extension CaptureOrientation {

        /// The pre-iOS 17 spelling of the same rotation.
        ///
        /// The crossover is not a slip. `UIDeviceOrientation.landscapeLeft` and
        /// `AVCaptureVideoOrientation.landscapeRight` describe one physical way
        /// up: the first names which way the *device* was turned, the second
        /// which way the *image* must come out. Writing the obvious pairing here
        /// is the classic way to ship video rotated by half a turn.
        var videoOrientation: AVCaptureVideoOrientation {
            switch self {
            case .portrait: return .portrait
            case .portraitUpsideDown: return .portraitUpsideDown
            case .landscapeLeft: return .landscapeRight
            case .landscapeRight: return .landscapeLeft
            }
        }

        /// How the device is being held, or `nil` when it does not say.
        ///
        /// Face up, face down and unknown name no way up, so the last real
        /// answer is kept rather than snapping the picture to a guess.
        static func current(_ device: UIDeviceOrientation) -> CaptureOrientation? {
            switch device {
            case .portrait: return .portrait
            case .portraitUpsideDown: return .portraitUpsideDown
            case .landscapeLeft: return .landscapeLeft
            case .landscapeRight: return .landscapeRight
            default: return nil
            }
        }
    }
#endif
