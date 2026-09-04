/// The Reactor Swift SDK.
///
/// This is the scaffold: the package, the module map over the C ABI, and the
/// tasks that lint and test them. The object model — `Reactor`, `Track`, the
/// error type — arrives over the pull requests grouped by the `Swift SDK`
/// milestone, each one green on its own.
public enum ReactorSDK {

    /// The SDK's version.
    ///
    /// Changing this and merging to `main` is what publishes a release, so it
    /// stays at a development value until the release workflow exists to act
    /// on it.
    public static let version = "0.0.0-dev"
}
