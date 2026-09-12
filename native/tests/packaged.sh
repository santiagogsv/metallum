#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
javac_bin="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
mkdir -p build/native/packaged-test-classes
"$javac_bin" --release 25 -cp "$METALLUM_TEST_JAR" -d build/native/packaged-test-classes native/tests/PackagedLibrarySmoke.java
"$java_bin" --enable-native-access=ALL-UNNAMED -cp "$METALLUM_TEST_JAR:build/native/packaged-test-classes" PackagedLibrarySmoke
