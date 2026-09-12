#!/bin/sh
# No GPU required. This tests Java against a C fixture, not the Swift/Metal implementation.
set -eu
cd "$(dirname "$0")/../.."
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
javac_bin="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
mkdir -p build/native/test-classes
xcrun clang -dynamiclib -I native/include native/tests/ffi_fixture.c -o build/native/libffi_fixture.dylib
xcrun clang -dynamiclib -DTEST_ABI_VERSION=1 -I native/include native/tests/ffi_fixture.c -o build/native/libold_abi_fixture.dylib
"$javac_bin" --release 25 -d build/native/test-classes \
    src/main/java/com/metallum/nativebridge/NativeMetalDevice.java src/main/java/com/metallum/nativebridge/NativePipelineDescriptor.java native/tests/NativeDeviceSmoke.java
"$java_bin" --enable-native-access=ALL-UNNAMED -cp build/native/test-classes \
    NativeDeviceSmoke "$PWD/build/native/libffi_fixture.dylib" "$PWD/build/native/libold_abi_fixture.dylib"
"$javac_bin" --release 25 -d build/native/test-classes src/main/java/com/metallum/render/BoundedBufferPool.java native/tests/BoundedBufferPoolSmoke.java
"$java_bin" -cp build/native/test-classes com.metallum.render.BoundedBufferPoolSmoke
