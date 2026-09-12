#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
javac_bin="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
"$javac_bin" --release 25 -cp "$METALLUM_TEST_CLASSPATH" -d build/native/test-classes native/tests/RenderAdapterSmoke.java
"$java_bin" --enable-native-access=ALL-UNNAMED -cp "build/native/test-classes:$METALLUM_TEST_CLASSPATH" com.metallum.mtl.RenderAdapterSmoke "$PWD/build/native/libffi_fixture.dylib"
