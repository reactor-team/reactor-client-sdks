#include <jni.h>
#include "reactor_ffi.h"

// Android may resolve a dependency's JNI_OnLoad if the helper has none.
// The FFI owns WebRTC initialization; never initialize it a second time here.
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL Java_inc_reactor_sdk_probe_NativeProbeTest_lifecycle(
    JNIEnv *env, jobject self) {
    (void)env;
    (void)self;
    if (reactor_abi_version() != 2) return 10;
    ReactorHandle *handle = reactor_create_with_adm(
        "https://api.reactor.inc", "reactor/echo", NULL, 0, NULL, 0,
        "0.0.0-SNAPSHOT", "kotlin");
    if (!handle) return 11;
    return reactor_destroy(handle);
}
