#!/bin/sh
# CPU-only verification of real Swift descriptor policy and Java/C ABI ownership.
set -eu
cd "$(dirname "$0")/../.."
sh native/tests/descriptors.sh
sh native/tests/ffi_contract.sh
