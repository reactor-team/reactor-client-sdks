# JNI symbols are derived from these class/method names.
-keep class inc.reactor.sdk.internal.NativeAbi { *; }
-keep class inc.reactor.sdk.internal.NativeClient { *; }
# Resolved on the receiver's actual class with GetMethodID, including under R8.
-keepclassmembers class inc.reactor.sdk.internal.ControlEvents {
    public void accept(int, byte[], byte[]);
}
-keepclassmembers class inc.reactor.sdk.CompletionReceiver {
    public void accept(int, byte[], byte[]);
}
