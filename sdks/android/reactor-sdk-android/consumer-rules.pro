# Kept for every consumer that minifies.
#
# R8 has no way to see that these are reached from native code: the JNI bridge resolves them by
# their mangled, fully-qualified names at load time, so to R8 they are unused and its default is
# to remove or rename them. Either produces an UnsatisfiedLinkError in a release build and never
# in a debug one, which is the worst shape a bug of this kind can take.
-keepclasseswithmembernames,includedescriptorclasses class inc.reactor.sdk.android.internal.** {
    native <methods>;
}
-keep class inc.reactor.sdk.android.internal.NativeAbi { *; }

# The other direction, and the one that is easy to miss.
#
# The rule above covers Kotlin -> native: `native` declarations, which R8 already treats
# carefully. These are native -> Kotlin, and R8 has no reason to treat them carefully at all. The
# bridge resolves them with GetMethodID on a *name string*, so nothing in the bytecode references
# them and R8's default is to rename or remove them. A renamed callback is a NoSuchMethodError on
# the first event — in a release build, never in a debug one, and only once a session actually
# starts delivering.
#
# Members only: the class name is never looked up: the listener arrives as an object and
# Completions as a Class, so both may be obfuscated freely. It is the method *names* that have to
# survive.
-keepclassmembers interface inc.reactor.sdk.android.internal.NativeEvents { *; }
-keepclassmembers class * implements inc.reactor.sdk.android.internal.NativeEvents { *; }

# WebRTC's own Java half, looked up from native code by name for the same reason.
-keep class inc.reactor.org.webrtc.** { *; }
-keep class inc.reactor.org.jni_zero.** { *; }
