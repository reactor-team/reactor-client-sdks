package inc.reactor.sdk.android.internal

/**
 * The two native entry points A02 needs. The rest of the boundary arrives with A03.
 *
 * Declared `external` against `sdks/android/native/src/abi.cpp`, whose function names are the
 * mangled form of this object's fully-qualified name. Renaming or moving this object without
 * renaming the C++ symbols produces an `UnsatisfiedLinkError` on first call.
 */
internal object NativeAbi {
    /**
     * Throws [UnsatisfiedLinkError] when the loaded library's ABI differs from the one this AAR
     * was compiled against, naming both numbers.
     */
    external fun checkAbi()

    /** The ABI version the loaded library reports. */
    external fun abiVersion(): Int
}
