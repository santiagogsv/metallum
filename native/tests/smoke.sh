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
    src/main/java/com/metallum/nativebridge/NativeMetalDevice.java native/tests/NativeDeviceSmoke.java
"$java_bin" --enable-native-access=ALL-UNNAMED -cp build/native/test-classes \
    NativeDeviceSmoke "$PWD/build/native/libmetallum_native.dylib"
