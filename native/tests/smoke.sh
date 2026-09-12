#!/bin/sh
# Run from any directory after ./gradlew buildNative, using JDK 25+.
set -eu
cd "$(dirname "$0")/../.."
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
javac_bin="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
xcrun clang -fobjc-arc -I native/include native/tests/device_smoke.m \
    -L build/native -lmetallum_native -framework Metal -framework Foundation \
    -Wl,-rpath,"$PWD/build/native" -o build/native/device_smoke
build/native/device_smoke
"$javac_bin" --release 25 -d build/native/test-classes \
    src/main/java/com/metallum/nativebridge/NativeMetalDevice.java src/main/java/com/metallum/nativebridge/NativePipelineDescriptor.java native/tests/NativeDeviceSmoke.java
"$java_bin" --enable-native-access=ALL-UNNAMED -cp build/native/test-classes \
    NativeDeviceSmoke "$PWD/build/native/libmetallum_native.dylib"

# Gradle checkGpu supplies the full Minecraft/LWJGL runtime for production Java renderer tests.
if [ -n "${METALLUM_TEST_CLASSPATH:-}" ]; then
    "$javac_bin" --release 25 -cp "$METALLUM_TEST_CLASSPATH" -d build/native/test-classes native/tests/BuiltinPipelineGpuSmoke.java
    "$java_bin" --enable-native-access=ALL-UNNAMED -cp "build/native/test-classes:$METALLUM_TEST_CLASSPATH" \
        com.metallum.mtl.BuiltinPipelineGpuSmoke "$PWD/build/native/libmetallum_native.dylib"
fi
