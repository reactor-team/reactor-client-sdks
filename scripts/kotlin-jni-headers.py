#!/usr/bin/env python3
"""Generate JNI prototypes from compiled Kotlin descriptors, checked by C++."""

import re
import subprocess
import sys

PRIMITIVES = dict(
    zip(
        "VZBCSIJFD",
        [
            "void",
            "jboolean",
            "jbyte",
            "jchar",
            "jshort",
            "jint",
            "jlong",
            "jfloat",
            "jdouble",
        ],
    )
)


def types(descriptor):
    result = []
    while descriptor:
        match = re.match(r"\[*L[^;]+;|\[*[VZBCSIJFD]", descriptor)
        if not match:
            raise ValueError(f"Unsupported JVM descriptor: {descriptor}")
        item = match.group()
        descriptor = descriptor[len(item) :]
        if item.startswith("["):
            result.append(
                PRIMITIVES[item[1:]] + "Array"
                if item[1:] in PRIMITIVES
                else "jobjectArray"
            )
        elif item.startswith("L"):
            result.append("jstring" if item == "Ljava/lang/String;" else "jobject")
        else:
            result.append(PRIMITIVES[item])
    return result


def mangle(value):
    return value.replace("_", "_1").replace(".", "_")


output = [
    "// Generated from JVM bytecode. Do not edit.",
    "#include <jni.h>",
    'extern "C" {',
]
for classpath, name in zip(sys.argv[2::2], sys.argv[3::2]):
    text = subprocess.check_output(
        [sys.argv[1], "-p", "-s", "-classpath", classpath, name], text=True
    )
    methods = re.findall(
        r"[^\n]*\bnative\s+[^\n]*?\s+(\w+)\([^\n]*\);\s+descriptor: \(([^)]*)\)(\S+)",
        text,
    )
    if not methods:
        raise ValueError(f"No native methods found in {name}")
    seen = set()
    for method, args, returned in methods:
        if method in seen:
            raise ValueError("Overloaded JNI methods require descriptor mangling")
        seen.add(method)
        params = ["JNIEnv*", "jobject"] + types(args)
        output.append(
            f"JNIEXPORT {types(returned)[0]} JNICALL Java_{mangle(name)}_{mangle(method)}({', '.join(params)});"
        )
output.append("}")
print("\n".join(output))
