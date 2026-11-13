#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

python3 scripts/model-catalog/update-model-catalog.py \
  --check \
  --input native-ui/app/src/main/assets/model-catalog/models-dev.normalized.json \
  --manifest native-ui/app/src/main/assets/model-catalog/manifest.json
python3 scripts/ci/tests/check-text-wrapping-test.py
python3 scripts/ci/check-text-wrapping.py
python3 scripts/ci/tests/check-distribution-manifests-test.py
python3 scripts/ci/tests/check-native-page-alignment-test.py
python3 -m unittest scripts.tests.test_verify_release_apk
python3 scripts/ci/tests/screenshot-baseline-test.py
python3 scripts/ci/tests/screenshot-results-test.py
python3 scripts/ci/tests/release-workflow-reliability-test.py
bash scripts/ci/tests/android-device-core-e2e-contract-test.sh
bash scripts/ci/tests/android-minified-blackbox-contract-test.sh
