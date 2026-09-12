#!/bin/sh
# Test real Swift descriptor policy and installed Metal SDK; no GPU required.
set -eu
cd "$(dirname "$0")/../.."
mkdir -p build/native
xcrun swiftc -parse-as-library -swift-version 6 -target arm64-apple-macosx27.0 \
    -module-cache-path build/native/module-cache \
    native/MetallumNative.swift native/MetalResources.swift native/MetalShaders.swift native/tests/ResourceDescriptorSmoke.swift \
    -o build/native/descriptor_smoke
build/native/descriptor_smoke
