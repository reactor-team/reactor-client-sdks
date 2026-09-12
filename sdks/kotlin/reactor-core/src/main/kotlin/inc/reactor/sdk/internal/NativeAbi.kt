package inc.reactor.sdk.internal

/** Raw boundary only. The library loader must call [checkAbi] before using it. */
internal object NativeAbi {
    external fun checkAbi()

    external fun timeMicros(): Long
}
