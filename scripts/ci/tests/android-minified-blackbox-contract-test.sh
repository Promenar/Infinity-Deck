#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
BLACKBOX_SCRIPT="${REPO_ROOT}/scripts/ci/android-minified-blackbox-smoke.sh"
PNG_NORMALIZER="${REPO_ROOT}/scripts/ci/normalize-android-screencap-png.py"
PNG_NORMALIZER_TEST="${REPO_ROOT}/scripts/ci/tests/test-normalize-android-screencap-png.py"
APP_BUILD="${REPO_ROOT}/native-ui/app/build.gradle.kts"
SETTINGS="${REPO_ROOT}/native-ui/settings.gradle.kts"
FIXTURE_ROOT="${REPO_ROOT}/native-ui/blackbox-fixture"
FIXTURE_BUILD="${FIXTURE_ROOT}/build.gradle.kts"
FIXTURE_MANIFEST="${FIXTURE_ROOT}/src/main/AndroidManifest.xml"
FIXTURE_PROVIDER="${FIXTURE_ROOT}/src/main/java/com/promenar/infinity/blackboxfixture/BlackBoxFixtureProvider.java"
FIXTURE_SENDER="${FIXTURE_ROOT}/src/main/java/com/promenar/infinity/blackboxfixture/BlackBoxFixtureShareActivity.java"
UI_TAGS="${REPO_ROOT}/native-ui/app/src/main/java/com/promenar/infinity/ui/testing/UiTags.kt"
SHARE_SHEET="${REPO_ROOT}/native-ui/app/src/main/java/com/promenar/infinity/share/ui/ShareImportSheet.kt"
MAIN_ACTIVITY="${REPO_ROOT}/native-ui/app/src/main/java/com/promenar/infinity/MainActivity.kt"
RELEASE_WORKFLOW="${REPO_ROOT}/.github/workflows/release.yml"

fail() {
    echo "minified black-box 契约断言失败：$*" >&2
    exit 1
}

assert_file() {
    [[ -f "$1" ]] || fail "缺少文件 $1"
}

assert_contains() {
    grep -Fq -- "$2" "$1" || fail "$1 缺少 $2"
}

assert_not_contains() {
    if grep -Fq -- "$2" "$1"; then
        fail "$1 不应包含 $2"
    fi
}

assert_count() {
    local actual
    actual="$(grep -Fc -- "$2" "$1" || true)"
    [[ "${actual}" == "$3" ]] || fail "$1 中 $2 应出现 $3 次，实际 ${actual} 次"
}

assert_absent() {
    [[ ! -e "$1" ]] || fail "应删除 $1"
}

assert_file "${BLACKBOX_SCRIPT}"
assert_file "${PNG_NORMALIZER}"
assert_file "${PNG_NORMALIZER_TEST}"
assert_file "${FIXTURE_BUILD}"
assert_file "${FIXTURE_MANIFEST}"
assert_file "${FIXTURE_PROVIDER}"
assert_file "${FIXTURE_SENDER}"

assert_contains "${SETTINGS}" 'include(":blackbox-fixture")'
assert_contains "${FIXTURE_BUILD}" 'id("com.android.application")'
assert_not_contains "${FIXTURE_BUILD}" 'project(":app")'
assert_not_contains "${FIXTURE_BUILD}" 'androidTest'
assert_not_contains "${FIXTURE_BUILD}" 'org.jetbrains.kotlin'
assert_contains "${FIXTURE_MANIFEST}" 'com.promenar.infinity.blackboxfixture.documents'
assert_contains "${FIXTURE_MANIFEST}" 'android:exported="true"'
assert_contains "${FIXTURE_MANIFEST}" 'android:grantUriPermissions="true"'
assert_not_contains "${FIXTURE_MANIFEST}" 'android.intent.action.MAIN'
assert_contains "${FIXTURE_MANIFEST}" '.BlackBoxFixtureShareActivity'
assert_contains "${FIXTURE_MANIFEST}" '@android:style/Theme.NoDisplay'
assert_contains "${FIXTURE_SENDER}" 'Intent.ACTION_SEND'
assert_contains "${FIXTURE_SENDER}" 'Intent.FLAG_GRANT_READ_URI_PERMISSION'
assert_contains "${FIXTURE_SENDER}" 'ClipData.newRawUri'
assert_contains "${FIXTURE_SENDER}" 'setComponent(target)'

for fixture_contract in \
    'release-parser-canary.pdf' \
    'application/pdf' \
    'release-parser-canary-empty.docx' \
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document' \
    'release-index-canary.txt' \
    'text/plain' \
    'OpenableColumns.DISPLAY_NAME' \
    'OpenableColumns.SIZE' \
    'MediaStore.MediaColumns.MIME_TYPE' \
    '[Content_Types].xml' \
    '_rels/.rels' \
    'word/document.xml' \
    'ParcelFileDescriptor.MODE_READ_ONLY'; do
    assert_contains "${FIXTURE_PROVIDER}" "${fixture_contract}"
done
for rejected_operation in 'insert(' 'delete(' 'update('; do
    assert_contains "${FIXTURE_PROVIDER}" "${rejected_operation}"
done

minified_block="$(sed -n '/create("minifiedTest") {/,/^        release {/p' "${APP_BUILD}")"
[[ -n "${minified_block}" ]] || fail "app 缺少 minifiedTest"
for invariant in \
    'applicationIdSuffix = ".minifiedTest"' \
    'isDebuggable = false' \
    'isMinifyEnabled = true' \
    'isShrinkResources = true' \
    'signingConfig = signingConfigs.getByName("debug")' \
    'buildConfigField("boolean", "LOCAL_INFERENCE_AVAILABLE", "false")' \
    'getDefaultProguardFile("proguard-android-optimize.txt")' \
    '"proguard-rules.pro"'; do
    grep -Fq -- "${invariant}" <<<"${minified_block}" || fail "minifiedTest 缺少 ${invariant}"
done
assert_not_contains "${APP_BUILD}" 'getByName("minifiedTest") {'
assert_not_contains "${APP_BUILD}" '"minifiedTestImplementation"'
assert_not_contains "${APP_BUILD}" 'proguard-minified-test-runner-rules.pro'
assert_not_contains "${APP_BUILD}" 'proguard-android-test-rules.pro'
assert_absent "${REPO_ROOT}/native-ui/app/proguard-minified-test-runner-rules.pro"
assert_absent "${REPO_ROOT}/native-ui/app/proguard-android-test-rules.pro"
assert_absent "${REPO_ROOT}/scripts/ci/tests/app-minified-runner-tracing-contract-test.sh"
assert_absent "${REPO_ROOT}/scripts/ci/tests/app-minified-androidtest-r8-contract-test.sh"

for tag in \
    SHARE_IMPORT_SHEET SHARE_IMPORT_ITEM SHARE_IMPORT_STATUS \
    SHARE_IMPORT_KNOWLEDGE_BASE_TARGET SHARE_IMPORT_LATER SHARE_IMPORT_CANCEL SHARE_IMPORT_ACTION; do
    assert_contains "${UI_TAGS}" "const val ${tag}"
done
assert_contains "${SHARE_SHEET}" '.testTag(UiTags.SHARE_IMPORT_SHEET)'
# 其余标签可经参数传给行组件；是否真的出现在无障碍树里由黑盒冒烟的 UI dump 断言。
for tag in \
    SHARE_IMPORT_ITEM SHARE_IMPORT_STATUS SHARE_IMPORT_KNOWLEDGE_BASE_TARGET \
    SHARE_IMPORT_LATER SHARE_IMPORT_CANCEL SHARE_IMPORT_ACTION; do
    assert_contains "${SHARE_SHEET}" "UiTags.${tag}"
done
assert_contains "${MAIN_ACTIVITY}" 'testTagsAsResourceId = true'
assert_contains "${SHARE_SHEET}" 'import androidx.compose.ui.semantics.semantics'
assert_contains "${SHARE_SHEET}" 'import androidx.compose.ui.semantics.testTagsAsResourceId'
# 分享面板经 InfinityModalSheet 包装 ModalBottomSheet；两种写法都按同一语义根约束检查。
modal_sheet_block="$(sed -En '/^[[:space:]]*(ModalBottomSheet|InfinityModalSheet)\(/,/^[[:space:]]*\) \{/p' "${SHARE_SHEET}")"
grep -Eq '^[[:space:]]*modifier = Modifier$' <<<"${modal_sheet_block}" ||
    fail "ModalBottomSheet modifier 必须建立独立语义根"
grep -Eq '^[[:space:]]*\.testTag\(UiTags\.SHARE_IMPORT_SHEET\)$' <<<"${modal_sheet_block}" ||
    fail "ModalBottomSheet 独立语义根缺少 SHARE_IMPORT_SHEET tag"
grep -Eq '^[[:space:]]*\.semantics \{ testTagsAsResourceId = true \},$' <<<"${modal_sheet_block}" ||
    fail "ModalBottomSheet 独立语义根未启用 testTagsAsResourceId"
sheet_tag_line="$(grep -nF '.testTag(UiTags.SHARE_IMPORT_SHEET)' <<<"${modal_sheet_block}" | cut -d: -f1)"
sheet_resource_id_line="$(grep -nF '.semantics { testTagsAsResourceId = true }' <<<"${modal_sheet_block}" | cut -d: -f1)"
[[ -n "${sheet_tag_line}" && -n "${sheet_resource_id_line}" && "${sheet_tag_line}" -lt "${sheet_resource_id_line}" ]] ||
    fail "ModalBottomSheet 必须在同一 modifier 链上先标记 sheet 再启用 resource-id"

for smoke_contract in \
    ':app:assembleSideloadMinifiedTest' \
    ':blackbox-fixture:assembleDebug' \
    'INFINITY_MINIFIED_TARGET_APK' \
    'INFINITY_MINIFIED_TARGET_PACKAGE' \
    'INFINITY_MINIFIED_SKIP_BUILD' \
    'INFINITY_MINIFIED_E2E_ABI' \
    'ANDROID_MINIFIED_BLACKBOX_ARTIFACT_DIR' \
    'run-with-timeout.py' \
    'com.google.android.marvin.talkback' \
    'android.permission.POST_NOTIFICATIONS' \
    'pm list instrumentation' \
    'run-as' \
    'usesCleartextTraffic' \
    'StartupGateTestActivity' \
    'RestoreRelayDebugReceiver' \
    'SecretFieldTestActivity' \
    'BackupTestActivity' \
    'mapping.txt' \
    'FIXTURE_SHARE_COMPONENT' \
    'release-parser-canary.pdf' \
    'release-parser-canary-empty.docx' \
    'release-index-canary.txt' \
    'uiautomator dump' \
    'screencap -p' \
    'normalize-android-screencap-png.py' \
    'Imported · Indexed' \
    '已导入 · 已完成索引' \
    'Imported · Indexing failed' \
    '已导入 · 索引失败' \
    'Retry failed' \
    '重试失败项' \
    'run_canary_text' \
    'wait_for_failed_retry()' \
    'wait_for_refailed_retry()' \
    'xml_has_any_text' \
    'click_any_text "${trigger_xml}" "${english_retry}" "${chinese_retry}"' \
    'click_any_text' \
    'am force-stop' \
    'exit-code.txt'; do
    assert_contains "${BLACKBOX_SCRIPT}" "${smoke_contract}"
done
assert_contains "${BLACKBOX_SCRIPT}" 'capture_normalized_screenshot() {'
assert_contains "${BLACKBOX_SCRIPT}" 'python3 "${PNG_ALPHA_NORMALIZER}" "${raw}" "${output}"'
assert_contains "${BLACKBOX_SCRIPT}" 'capture_normalized_screenshot "${ARTIFACT_DIR}/screen-final.png"'
assert_contains "${BLACKBOX_SCRIPT}" 'capture_stable_screenshot "${ARTIFACT_DIR}/screen-launcher.png"'
assert_contains "${BLACKBOX_SCRIPT}" 'rm -f "${previous}" "${current}" "${raw}"'
assert_not_contains "${BLACKBOX_SCRIPT}" 'screencap -p > "${ARTIFACT_DIR}/screen-final.png"'
assert_not_contains "${BLACKBOX_SCRIPT}" 'screencap -p > "${ARTIFACT_DIR}/screen-launcher.png"'
assert_contains "${BLACKBOX_SCRIPT}" 'node.attrib.get("enabled", "false") == "true"'
assert_contains "${BLACKBOX_SCRIPT}" 'adb shell pm grant "${TALKBACK_PACKAGE}" "${NOTIFICATION_PERMISSION}"'
assert_contains "${BLACKBOX_SCRIPT}" 'adb shell pm revoke "${TALKBACK_PACKAGE}" "${NOTIFICATION_PERMISSION}"'

indexed_wait_block="$(sed -n '/^wait_for_indexed_status() {/,/^}/p' "${BLACKBOX_SCRIPT}")"
grep -Fq 'xml_has_tag_text "${xml}" share_import_status "${english}"' <<<"${indexed_wait_block}" ||
    fail "wait_for_indexed_status 必须限定 SHARE_IMPORT_STATUS 英文状态节点"
grep -Fq 'xml_has_tag_text "${xml}" share_import_status "${chinese}"' <<<"${indexed_wait_block}" ||
    fail "wait_for_indexed_status 必须限定 SHARE_IMPORT_STATUS 中文状态节点"

for tagged_status_function in wait_for_failed_retry wait_for_refailed_retry; do
    tagged_status_block="$(sed -n "/^${tagged_status_function}() {/,/^}/p" "${BLACKBOX_SCRIPT}")"
    grep -Fq 'xml_has_tag_text "${xml}" share_import_status' <<<"${tagged_status_block}" ||
        fail "${tagged_status_function} 必须限定 SHARE_IMPORT_STATUS 状态节点"
done
assert_not_contains "${BLACKBOX_SCRIPT}" 'assembleMinifiedTestAndroidTest'
assert_not_contains "${BLACKBOX_SCRIPT}" 'mainactivity-e2e'
assert_not_contains "${BLACKBOX_SCRIPT}" 'am instrument'
assert_not_contains "${BLACKBOX_SCRIPT}" 'ui-test-manifest'
assert_contains "${BLACKBOX_SCRIPT}" '--es document_name "${name}"'
assert_contains "${BLACKBOX_SCRIPT}" '--es mime_type "${mime}"'
assert_contains "${BLACKBOX_SCRIPT}" '--es target_component "${TARGET_COMPONENT}"'
assert_not_contains "${BLACKBOX_SCRIPT}" '--eu android.intent.extra.STREAM'
assert_not_contains "${BLACKBOX_SCRIPT}" '-f 0x10000001'
assert_count "${BLACKBOX_SCRIPT}" '-a android.intent.action.MAIN' 4
assert_count "${BLACKBOX_SCRIPT}" '-c android.intent.category.LAUNCHER' 4
assert_contains "${BLACKBOX_SCRIPT}" 'com.promenar.infinity.test'
assert_not_contains "${BLACKBOX_SCRIPT}" '--projection _display_name:size'
assert_contains "${BLACKBOX_SCRIPT}" '--projection _display_name:_size:mime_type'
assert_contains "${BLACKBOX_SCRIPT}" 'mime_type=${mime}'
assert_not_contains "${BLACKBOX_SCRIPT}" 'content gettype'
assert_not_contains "${BLACKBOX_SCRIPT}" 'cp "${LAST_WINDOW_XML}" "${durable_xml}"'
assert_contains "${BLACKBOX_SCRIPT}" 'require_file "${durable_xml}"'
assert_not_contains "${BLACKBOX_SCRIPT}" 'am-start-${key}-persistent.txt'
assert_not_contains "${BLACKBOX_SCRIPT}" 'screen-${key}-persistent.png'
assert_contains "${BLACKBOX_SCRIPT}" 'WAIT_SECONDS="${ANDROID_MINIFIED_BLACKBOX_TIMEOUT_SECONDS:-120}"'
assert_contains "${BLACKBOX_SCRIPT}" 'WAIT_SECONDS < 10 || WAIT_SECONDS > 900'
assert_contains "${BLACKBOX_SCRIPT}" 'date +%s'
assert_contains "${BLACKBOX_SCRIPT}" 'UI_DUMP_MAX_SECONDS=10'
assert_contains "${BLACKBOX_SCRIPT}" 'remaining_seconds() {'
assert_not_contains "${BLACKBOX_SCRIPT}" 'seq 1 '

for wait_function in \
    wait_for_launcher_stable \
    wait_for_share_surface \
    wait_for_indexed_status \
    wait_for_initial_launcher_ui \
    wait_for_failed_retry \
    wait_for_refailed_retry; do
    wait_block="$(sed -n "/^${wait_function}() {/,/^}/p" "${BLACKBOX_SCRIPT}")"
    [[ -n "${wait_block}" ]] || fail "缺少 ${wait_function} deadline 等待函数"
    grep -Fq 'local deadline=$(( $(date +%s) + WAIT_SECONDS ))' <<<"${wait_block}" ||
        fail "${wait_function} 未按 WAIT_SECONDS 建立阶段 deadline"
    grep -Fq 'while (( $(date +%s) < deadline )); do' <<<"${wait_block}" ||
        fail "${wait_function} 未消费阶段 deadline"
done

dump_ui_block="$(sed -n '/^dump_ui() {/,/^}/p' "${BLACKBOX_SCRIPT}")"
for dump_timeout_contract in \
    'local deadline="$2"' \
    'remaining="$(remaining_seconds "${deadline}")"' \
    'dump_timeout="${UI_DUMP_MAX_SECONDS}"' \
    'if (( dump_timeout > remaining )); then'; do
    grep -Fq -- "${dump_timeout_contract}" <<<"${dump_ui_block}" ||
        fail "dump_ui 未按剩余 deadline 约束单次 timeout：${dump_timeout_contract}"
done

health_block="$(sed -n '/^assert_target_healthy() {/,/^}/p' "${BLACKBOX_SCRIPT}")"
for health_contract in \
    'adb logcat -d -v threadtime' \
    'logcat-health-${phase}.txt' \
    'adb shell dumpsys activity exit-info "${TARGET_PACKAGE}"' \
    'exit-info-${phase}.txt' \
    'ANR in ${TARGET_PACKAGE//./\\.}' \
    'reason=(4 \(CRASH\)|5 \(CRASH_NATIVE\)|6 \(ANR\))'; do
    grep -Fq -- "${health_contract}" <<<"${health_block}" ||
        fail "assert_target_healthy 缺少全 logcat/exit-info 契约：${health_contract}"
done

for host_anr_contract in \
    'HOST_SYSTEM_ANR_RECOVERED=false' \
    'host_system_anr_wait_coordinates()' \
    'recover_host_system_anr_once()' \
    "Quickstep isn't responding" \
    "Pixel Launcher isn't responding" \
    "System UI isn't responding" \
    'android:id/aerr_wait' \
    'screen-host-system-anr-before-wait.png' \
    'window-host-system-anr-before-wait.xml' \
    'window-host-system-anr-before-wait-click.xml' \
    'if ! capture_stable_screenshot' \
    'if ! adb shell input tap ${coordinates}' \
    '宿主 System UI ANR 再次出现，拒绝继续恢复' \
    'recover_host_system_anr_once "${xml}" "${deadline}"'; do
    assert_contains "${BLACKBOX_SCRIPT}" "${host_anr_contract}"
done

host_anr_recovery_block="$(
    sed -n '/^recover_host_system_anr_once() {/,/^}/p' "${BLACKBOX_SCRIPT}"
)"
screenshot_line="$(
    grep -n 'screen-host-system-anr-before-wait.png' <<<"${host_anr_recovery_block}" |
        cut -d: -f1
)"
wait_click_line="$(
    grep -n 'host_system_anr_wait_coordinates "${click_xml}"' \
        <<<"${host_anr_recovery_block}" |
        cut -d: -f1
)"
[[ -n "${screenshot_line}" && -n "${wait_click_line}" ]] ||
    fail "宿主 ANR 恢复必须同时保留截图与 Wait 点击"
(( screenshot_line < wait_click_line )) ||
    fail "宿主 ANR 证据截图必须发生在 Wait 点击之前"

host_anr_coordinates_block="$(
    sed -n \
        '/^host_system_anr_wait_coordinates() {/,/^recover_host_system_anr_once() {/p' \
        "${BLACKBOX_SCRIPT}" |
        sed '$d'
)"
(
    eval "${host_anr_coordinates_block}"
    eval "${host_anr_recovery_block}"

    temp_dir="$(mktemp -d)"
    ARTIFACT_DIR="${temp_dir}/artifacts"
    mkdir -p "${ARTIFACT_DIR}"
    HOST_SYSTEM_ANR_RECOVERED=false
    MOCK_SCREENSHOT_STATUS=0
    MOCK_ADB_STATUS=0
    MOCK_FRESH_XML="${temp_dir}/host-anr.xml"
    MOCK_ADB_LOG="${temp_dir}/adb.log"

    cat > "${temp_dir}/host-anr.xml" <<'XML'
<hierarchy>
  <node text="Quickstep isn't responding" resource-id="android:id/alertTitle" package="android"
        enabled="true" clickable="false" bounds="[0,0][100,20]" />
  <node text="Wait" resource-id="android:id/aerr_wait" package="android"
        enabled="true" clickable="true" bounds="[10,20][50,60]" />
</hierarchy>
XML
    cat > "${temp_dir}/target-anr.xml" <<'XML'
<hierarchy>
  <node text="Infinity Deck isn't responding" resource-id="android:id/alertTitle" package="android"
        enabled="true" clickable="false" bounds="[0,0][100,20]" />
  <node text="Wait" resource-id="android:id/aerr_wait" package="android"
        enabled="true" clickable="true" bounds="[10,20][50,60]" />
</hierarchy>
XML
    cat > "${temp_dir}/wait-missing.xml" <<'XML'
<hierarchy>
  <node text="Quickstep isn't responding" resource-id="android:id/alertTitle" package="android"
        enabled="true" clickable="false" bounds="[0,0][100,20]" />
</hierarchy>
XML

    capture_stable_screenshot() {
        if (( MOCK_SCREENSHOT_STATUS != 0 )); then
            return "${MOCK_SCREENSHOT_STATUS}"
        fi
        : > "$1"
    }
    dump_ui() {
        cp "${MOCK_FRESH_XML}" "$1"
    }
    adb() {
        printf '%s\n' "$*" >> "${MOCK_ADB_LOG}"
        return "${MOCK_ADB_STATUS}"
    }

    MOCK_SCREENSHOT_STATUS=1
    if recover_host_system_anr_once "${temp_dir}/host-anr.xml" 9999999999; then
        fail "截图失败时不得继续恢复宿主 ANR"
    fi
    [[ ! -e "${MOCK_ADB_LOG}" ]] ||
        fail "截图失败时不得点击 Wait"

    MOCK_SCREENSHOT_STATUS=0
    recover_host_system_anr_once "${temp_dir}/host-anr.xml" 9999999999
    [[ "${HOST_SYSTEM_ANR_RECOVERED}" == "true" ]] ||
        fail "首次宿主 ANR 恢复后必须记录已恢复状态"
    [[ "$(wc -l < "${MOCK_ADB_LOG}" | tr -d ' ')" == "1" ]] ||
        fail "首次宿主 ANR 恢复必须且只能点击一次"
    [[ -s "${ARTIFACT_DIR}/window-host-system-anr-before-wait.xml" ]] ||
        fail "首次宿主 ANR 必须保留点击前 XML"
    [[ -s "${ARTIFACT_DIR}/window-host-system-anr-before-wait-click.xml" ]] ||
        fail "首次宿主 ANR 必须保留点击时 XML"

    if recover_host_system_anr_once "${temp_dir}/host-anr.xml" 9999999999; then
        fail "第二次宿主 ANR 必须失败关闭"
    fi
    [[ "$(wc -l < "${MOCK_ADB_LOG}" | tr -d ' ')" == "1" ]] ||
        fail "第二次宿主 ANR 不得再次点击"

    HOST_SYSTEM_ANR_RECOVERED=false
    if recover_host_system_anr_once "${temp_dir}/target-anr.xml" 9999999999; then
        fail "目标 Infinity Deck ANR 不得进入宿主恢复路径"
    fi

    MOCK_FRESH_XML="${temp_dir}/wait-missing.xml"
    if recover_host_system_anr_once "${temp_dir}/host-anr.xml" 9999999999; then
        fail "截图后 Wait 消失时必须失败关闭"
    fi
    [[ "$(wc -l < "${MOCK_ADB_LOG}" | tr -d ' ')" == "1" ]] ||
        fail "Wait 消失时不得使用旧 XML 坐标点击"

    MOCK_FRESH_XML="${temp_dir}/host-anr.xml"
    MOCK_ADB_STATUS=1
    if recover_host_system_anr_once "${temp_dir}/host-anr.xml" 9999999999; then
        fail "Wait 点击失败时必须失败关闭"
    fi
    [[ "${HOST_SYSTEM_ANR_RECOVERED}" == "false" ]] ||
        fail "Wait 点击失败时不得记录为已恢复"
)

artifact_resolution_block="$(sed -n '/^ARTIFACT_DIR_INPUT=/,/^WAIT_SECONDS=/p' "${BLACKBOX_SCRIPT}")"
[[ -n "${artifact_resolution_block}" ]] || fail "smoke 必须先保存 ARTIFACT_DIR_INPUT 再规范路径"
for artifact_path_contract in \
    'ARTIFACT_DIR_INPUT="${ANDROID_MINIFIED_BLACKBOX_ARTIFACT_DIR:-artifacts/android-minified-blackbox-api-${API_LEVEL:-unknown}-${TARGET_ABI}}"' \
    'if [[ "${ARTIFACT_DIR_INPUT}" == /* ]]; then' \
    'ARTIFACT_DIR="${ARTIFACT_DIR_INPUT}"' \
    'ARTIFACT_DIR="${REPO_ROOT}/${ARTIFACT_DIR_INPUT}"'; do
    grep -Fq -- "${artifact_path_contract}" <<<"${artifact_resolution_block}" ||
        fail "artifact 路径规范块缺少 ${artifact_path_contract}"
done
artifact_input_line="$(grep -n '^ARTIFACT_DIR_INPUT=' "${BLACKBOX_SCRIPT}" | cut -d: -f1)"
native_cd_line="$(grep -n '^[[:space:]]*cd "${NATIVE_ROOT}"' "${BLACKBOX_SCRIPT}" | head -n 1 | cut -d: -f1)"
[[ -n "${artifact_input_line}" && -n "${native_cd_line}" && "${artifact_input_line}" -lt "${native_cd_line}" ]] ||
    fail "artifact 目录必须在进入 native-ui 前完成绝对路径规范化"

assert_contains "${RELEASE_WORKFLOW}" 'minified-blackbox:'
assert_contains "${RELEASE_WORKFLOW}" 'Release 等价 minifiedTest 黑盒回归'
assert_contains "${RELEASE_WORKFLOW}" 'scripts/ci/android-minified-blackbox-smoke.sh'
assert_contains "${RELEASE_WORKFLOW}" 'release-minified-blackbox-api-${{ matrix.api-level }}-${{ github.run_attempt }}'
assert_contains "${RELEASE_WORKFLOW}" 'artifacts/android-minified-blackbox-api-${{ matrix.api-level }}-x86_64/'
assert_contains "${RELEASE_WORKFLOW}" 'needs: [validate-release-inputs, static-checks]'
assert_contains "${RELEASE_WORKFLOW}" 'needs: [validate-release-inputs, signed-apk-smoke, device-e2e, minified-blackbox]'
assert_not_contains "${RELEASE_WORKFLOW}" 'INFINITY_DEVICE_E2E_BUILD_TYPE: minifiedTest'

echo "minified release-equivalent black-box 架构契约通过。"
