#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NATIVE_ROOT="${REPO_ROOT}/native-ui"
TIMEOUT_HELPER="${REPO_ROOT}/scripts/ci/run-with-timeout.py"
PNG_ALPHA_NORMALIZER="${REPO_ROOT}/scripts/ci/normalize-android-screencap-png.py"
TARGET_APK_OVERRIDE="${INFINITY_MINIFIED_TARGET_APK:-}"
TARGET_PACKAGE="${INFINITY_MINIFIED_TARGET_PACKAGE:-com.promenar.infinity.minifiedTest}"
SKIP_BUILD="${INFINITY_MINIFIED_SKIP_BUILD:-false}"
TARGET_ABI="${INFINITY_MINIFIED_E2E_ABI:-x86_64}"
FIXTURE_PACKAGE="com.promenar.infinity.blackboxfixture"
FIXTURE_AUTHORITY="com.promenar.infinity.blackboxfixture.documents"
FIXTURE_SHARE_COMPONENT="${FIXTURE_PACKAGE}/.BlackBoxFixtureShareActivity"
TALKBACK_PACKAGE="com.google.android.marvin.talkback"
NOTIFICATION_PERMISSION="android.permission.POST_NOTIFICATIONS"
FIXTURE_APK="${NATIVE_ROOT}/blackbox-fixture/build/outputs/apk/debug/blackbox-fixture-debug.apk"
DEFAULT_TARGET_APK="${NATIVE_ROOT}/app/build/outputs/apk/sideload/minifiedTest/app-sideload-minifiedTest.apk"
DEFAULT_MAPPING="${NATIVE_ROOT}/app/build/outputs/mapping/sideloadMinifiedTest/mapping.txt"
TEXT_CANARY_NAME="release-index-canary.txt"
TEXT_CANARY_MIME="text/plain"
API_LEVEL="$(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
ARTIFACT_DIR_INPUT="${ANDROID_MINIFIED_BLACKBOX_ARTIFACT_DIR:-artifacts/android-minified-blackbox-api-${API_LEVEL:-unknown}-${TARGET_ABI}}"
if [[ "${ARTIFACT_DIR_INPUT}" == /* ]]; then
    ARTIFACT_DIR="${ARTIFACT_DIR_INPUT}"
else
    ARTIFACT_DIR="${REPO_ROOT}/${ARTIFACT_DIR_INPUT}"
fi
WAIT_SECONDS="${ANDROID_MINIFIED_BLACKBOX_TIMEOUT_SECONDS:-120}"
UI_DUMP_MAX_SECONDS=10
DEFAULT_MINIFIED_MODE=true
TARGET_COMPONENT=""
TARGET_PID=""
LAST_WINDOW_XML="${ARTIFACT_DIR}/window-final.xml"
DISPLAY_STATE_CAPTURED=false
ORIGINAL_ACCELEROMETER_ROTATION=""
ORIGINAL_USER_ROTATION=""
TALKBACK_PRESENT=false
TALKBACK_NOTIFICATION_WAS_GRANTED=false
HOST_SYSTEM_ANR_RECOVERED=false

case "${TARGET_ABI}" in
    x86_64|arm64-v8a) ;;
    *)
        echo "INFINITY_MINIFIED_E2E_ABI 仅支持 x86_64 或 arm64-v8a：${TARGET_ABI}" >&2
        exit 1
        ;;
esac
case "${SKIP_BUILD}" in
    true|false) ;;
    *)
        echo "INFINITY_MINIFIED_SKIP_BUILD 仅支持 true 或 false：${SKIP_BUILD}" >&2
        exit 1
        ;;
esac
if ! [[ "${WAIT_SECONDS}" =~ ^[0-9]+$ ]] || (( WAIT_SECONDS < 10 || WAIT_SECONDS > 900 )); then
    echo "ANDROID_MINIFIED_BLACKBOX_TIMEOUT_SECONDS 必须是 10..900 的正整数：${WAIT_SECONDS}" >&2
    exit 1
fi

if [[ -n "${TARGET_APK_OVERRIDE}" ]]; then
    if [[ "${TARGET_APK_OVERRIDE}" == /* ]]; then
        TARGET_APK="${TARGET_APK_OVERRIDE}"
    else
        TARGET_APK="${REPO_ROOT}/${TARGET_APK_OVERRIDE}"
    fi
    DEFAULT_MINIFIED_MODE=false
else
    TARGET_APK="${DEFAULT_TARGET_APK}"
fi

mkdir -p "${ARTIFACT_DIR}"

capture_device_display_state() {
    ORIGINAL_ACCELEROMETER_ROTATION="$(adb shell settings get system accelerometer_rotation 2>/dev/null | tr -d '\r')"
    ORIGINAL_USER_ROTATION="$(adb shell settings get system user_rotation 2>/dev/null | tr -d '\r')"
    DISPLAY_STATE_CAPTURED=true
}

restore_setting() {
    local namespace="$1"
    local key="$2"
    local value="$3"
    # 读取失败（空值）时不改动；删除会让 ColorOS 等系统回到默认值，例如重新打开自动旋转。
    if [[ -z "${value}" ]]; then
        return 0
    fi
    if [[ "${value}" == "null" ]]; then
        adb shell settings delete "${namespace}" "${key}" >/dev/null 2>&1 || true
    else
        adb shell settings put "${namespace}" "${key}" "${value}" >/dev/null 2>&1 || true
    fi
}

restore_device_display_state() {
    [[ "${DISPLAY_STATE_CAPTURED}" == "true" ]] || return 0
    restore_setting system accelerometer_rotation "${ORIGINAL_ACCELEROMETER_ROTATION}"
    restore_setting system user_rotation "${ORIGINAL_USER_ROTATION}"
    DISPLAY_STATE_CAPTURED=false
}

prepare_uiautomator_environment() {
    if ! adb shell pm path "${TALKBACK_PACKAGE}" 2>/dev/null | grep -Fq 'package:'; then
        return 0
    fi
    TALKBACK_PRESENT=true
    if adb shell dumpsys package "${TALKBACK_PACKAGE}" 2>/dev/null \
        | grep -Fq "${NOTIFICATION_PERMISSION}: granted=true"; then
        TALKBACK_NOTIFICATION_WAS_GRANTED=true
    fi
    adb shell pm grant "${TALKBACK_PACKAGE}" "${NOTIFICATION_PERMISSION}"
    adb shell am force-stop "${TALKBACK_PACKAGE}" >/dev/null 2>&1 || true
}

restore_uiautomator_environment() {
    if [[ "${TALKBACK_PRESENT}" == "true" ]] && \
        [[ "${TALKBACK_NOTIFICATION_WAS_GRANTED}" != "true" ]]; then
        adb shell pm revoke "${TALKBACK_PACKAGE}" "${NOTIFICATION_PERMISSION}" \
            >/dev/null 2>&1 || true
    fi
    TALKBACK_PRESENT=false
}

remaining_seconds() {
    local deadline="$1"
    local now
    now="$(date +%s)"
    if (( now >= deadline )); then
        printf '0\n'
    else
        printf '%s\n' "$((deadline - now))"
    fi
}

dump_ui() {
    local destination="$1"
    local deadline="$2"
    local remaining dump_timeout pull_timeout
    remaining="$(remaining_seconds "${deadline}")"
    (( remaining > 0 )) || return 1
    dump_timeout="${UI_DUMP_MAX_SECONDS}"
    if (( dump_timeout > remaining )); then
        dump_timeout="${remaining}"
    fi
    python3 "${TIMEOUT_HELPER}" "${dump_timeout}" adb shell uiautomator dump /sdcard/infinity-blackbox-window.xml \
        >/dev/null 2>&1
    remaining="$(remaining_seconds "${deadline}")"
    (( remaining > 0 )) || return 1
    pull_timeout=5
    if (( pull_timeout > remaining )); then
        pull_timeout="${remaining}"
    fi
    python3 "${TIMEOUT_HELPER}" "${pull_timeout}" adb pull \
        /sdcard/infinity-blackbox-window.xml "${destination}" >/dev/null 2>&1
}

capture_stable_screenshot() {
    local output="$1"
    local previous="${output}.previous"
    local current="${output}.current"
    local raw="${output}.raw"
    local attempt=0

    rm -f "${previous}" "${current}" "${raw}"
    sleep 1
    adb exec-out screencap -p > "${previous}"
    while (( attempt < 8 )); do
        sleep 0.25
        adb exec-out screencap -p > "${current}"
        if cmp -s "${previous}" "${current}"; then
            mv "${current}" "${raw}"
            rm -f "${previous}"
            break
        fi
        mv "${current}" "${previous}"
        attempt=$(( attempt + 1 ))
    done
    if [[ ! -f "${raw}" ]]; then
        mv "${previous}" "${raw}"
    fi
    if ! python3 "${PNG_ALPHA_NORMALIZER}" "${raw}" "${output}"; then
        rm -f "${previous}" "${current}" "${raw}"
        return 1
    fi
    rm -f "${previous}" "${current}" "${raw}"
}

capture_normalized_screenshot() {
    local output="$1"
    local raw="${output}.raw"

    rm -f "${raw}"
    if ! adb exec-out screencap -p > "${raw}"; then
        rm -f "${raw}"
        return 1
    fi
    if ! python3 "${PNG_ALPHA_NORMALIZER}" "${raw}" "${output}"; then
        rm -f "${raw}"
        return 1
    fi
    rm -f "${raw}"
}

capture_artifacts() {
    local exit_code="$1"
    set +e
    adb logcat -d -v threadtime > "${ARTIFACT_DIR}/logcat.txt" 2>&1
    adb logcat -b crash -d -v threadtime > "${ARTIFACT_DIR}/crash-log.txt" 2>&1
    adb shell dumpsys activity activities > "${ARTIFACT_DIR}/activities.txt" 2>&1
    adb shell dumpsys activity processes > "${ARTIFACT_DIR}/activity-processes.txt" 2>&1
    adb shell dumpsys package "${TARGET_PACKAGE}" > "${ARTIFACT_DIR}/target-package.txt" 2>&1
    adb shell dumpsys package "${FIXTURE_PACKAGE}" > "${ARTIFACT_DIR}/fixture-package.txt" 2>&1
    adb shell pm list instrumentation > "${ARTIFACT_DIR}/instrumentation.txt" 2>&1
    dump_ui "${LAST_WINDOW_XML}" "$(( $(date +%s) + UI_DUMP_MAX_SECONDS ))"
    capture_normalized_screenshot "${ARTIFACT_DIR}/screen-final.png" 2>/dev/null || true
    printf '%s\n' "${exit_code}" > "${ARTIFACT_DIR}/exit-code.txt"
    restore_uiautomator_environment
    restore_device_display_state
    set -e
}

trap 'capture_artifacts "$?"' EXIT

require_file() {
    [[ -f "$1" ]] || {
        echo "缺少构建产物：$1" >&2
        exit 1
    }
}

require_nonempty_file() {
    [[ -s "$1" ]] || {
        echo "文件缺失或为空：$1" >&2
        exit 1
    }
}

if [[ "${SKIP_BUILD}" == "false" ]]; then
    cd "${NATIVE_ROOT}"
    chmod +x gradlew
    if [[ "${DEFAULT_MINIFIED_MODE}" == "true" ]]; then
        ./gradlew --stacktrace -Pinfinity.deviceE2eAbi="${TARGET_ABI}" \
            :app:assembleSideloadMinifiedTest :blackbox-fixture:assembleDebug --console=plain
    else
        ./gradlew --stacktrace :blackbox-fixture:assembleDebug --console=plain
    fi
fi

require_file "${TARGET_APK}"
require_file "${FIXTURE_APK}"
require_file "${PNG_ALPHA_NORMALIZER}"
if [[ "${DEFAULT_MINIFIED_MODE}" == "true" ]]; then
    require_nonempty_file "${DEFAULT_MAPPING}"
    cp "${DEFAULT_MAPPING}" "${ARTIFACT_DIR}/mapping.txt"
fi

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
AAPT2="$(find "${SDK_ROOT}/build-tools" -type f -name aapt2 2>/dev/null | sort | tail -n 1)"
if [[ -z "${AAPT2}" || ! -x "${AAPT2}" ]]; then
    echo "未找到 aapt2" >&2
    exit 1
fi
"${AAPT2}" dump xmltree "${TARGET_APK}" --file AndroidManifest.xml \
    > "${ARTIFACT_DIR}/target-manifest.txt"
MANIFEST_DUMP="${ARTIFACT_DIR}/target-manifest.txt"
grep -Fq 'usesCleartextTraffic(0x010104ec)=true' "${MANIFEST_DUMP}" || {
    echo "目标 APK 未声明允许明文流量" >&2
    exit 1
}
if grep -Fq 'debuggable(0x0101000f)=true' "${MANIFEST_DUMP}"; then
    echo "目标 APK manifest 仍可调试" >&2
    exit 1
fi
for forbidden_component in \
    StartupGateTestActivity RestoreRelayDebugReceiver SecretFieldTestActivity BackupTestActivity; do
    if grep -Fq "${forbidden_component}" "${MANIFEST_DUMP}"; then
        echo "目标 APK 泄露测试组件：${forbidden_component}" >&2
        exit 1
    fi
done

adb wait-for-device
capture_device_display_state
prepare_uiautomator_environment
for stale_package in \
    com.promenar.infinity.test \
    com.promenar.infinity.minifiedTest.test \
    com.promenar.infinity.deviceTest.test \
    com.promenar.infinity.mainactivitye2e; do
    adb shell pm uninstall "${stale_package}" >/dev/null 2>&1 || true
done
adb shell pm uninstall "${TARGET_PACKAGE}" >/dev/null 2>&1 || true
adb shell pm uninstall "${FIXTURE_PACKAGE}" >/dev/null 2>&1 || true
adb install --no-streaming "${FIXTURE_APK}"
adb install --no-streaming "${TARGET_APK}"

adb shell pm list instrumentation | tr -d '\r' > "${ARTIFACT_DIR}/instrumentation-after-install.txt"
if grep -Fq "(target=${TARGET_PACKAGE})" "${ARTIFACT_DIR}/instrumentation-after-install.txt"; then
    echo "仍有 instrumentation 指向目标包" >&2
    exit 1
fi

adb shell dumpsys package "${TARGET_PACKAGE}" | tr -d '\r' \
    > "${ARTIFACT_DIR}/target-package-after-install.txt"
if grep -Eq 'flags=\[[^]]*DEBUGGABLE' "${ARTIFACT_DIR}/target-package-after-install.txt"; then
    echo "设备端目标包仍带 DEBUGGABLE flag" >&2
    exit 1
fi
if adb shell run-as "${TARGET_PACKAGE}" pwd > "${ARTIFACT_DIR}/run-as.txt" 2>&1; then
    echo "run-as 意外成功，目标包不是发行等价非调试包" >&2
    exit 1
fi

TARGET_COMPONENT="$(adb shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "${TARGET_PACKAGE}" \
    | tr -d '\r' | tail -n 1)"
if [[ "${TARGET_COMPONENT}" != "${TARGET_PACKAGE}/"* ]]; then
    echo "无法解析目标启动组件：${TARGET_COMPONENT}" >&2
    exit 1
fi

for name_and_mime in \
    'release-parser-canary-empty.pdf|application/pdf' \
    'release-parser-canary-empty.docx|application/vnd.openxmlformats-officedocument.wordprocessingml.document' \
    'release-index-canary.txt|text/plain'; do
    name="${name_and_mime%%|*}"
    mime="${name_and_mime#*|}"
    uri="content://${FIXTURE_AUTHORITY}/${name}"
    adb shell content query --uri "${uri}" --projection _display_name:_size:mime_type \
        > "${ARTIFACT_DIR}/fixture-query-${name}.txt"
    grep -Fq "_display_name=${name}" "${ARTIFACT_DIR}/fixture-query-${name}.txt" || {
        echo "fixture query 未暴露正确文件名：${name}" >&2
        exit 1
    }
    grep -Fq "mime_type=${mime}" "${ARTIFACT_DIR}/fixture-query-${name}.txt" || {
        echo "fixture MIME 不匹配：${name}" >&2
        exit 1
    }
done

assert_target_healthy() {
    local phase="$1"
    local pid full_log exit_info
    pid="$(adb shell pidof "${TARGET_PACKAGE}" 2>/dev/null | tr -d '\r')"
    if [[ -z "${pid}" ]]; then
        echo "${phase}：目标进程已消失" >&2
        return 1
    fi
    full_log="${ARTIFACT_DIR}/logcat-health-${phase}.txt"
    exit_info="${ARTIFACT_DIR}/exit-info-${phase}.txt"
    adb logcat -d -v threadtime | tr -d '\r' > "${full_log}"
    adb shell dumpsys activity exit-info "${TARGET_PACKAGE}" | tr -d '\r' > "${exit_info}"
    if grep -Eq "Process: ${TARGET_PACKAGE//./\\.}([,[:space:]]|$)|ANR in ${TARGET_PACKAGE//./\\.}" \
        "${full_log}"; then
        echo "${phase}：全量 logcat 检测到目标崩溃或 ANR" >&2
        return 1
    fi
    if grep -Eq 'reason=(4 \(CRASH\)|5 \(CRASH_NATIVE\)|6 \(ANR\))' "${exit_info}"; then
        echo "${phase}：exit-info 检测到目标 crash 或 ANR" >&2
        return 1
    fi
}

wait_for_launcher_stable() {
    local phase="$1"
    local consecutive=0
    local previous_pid=""
    local pid
    local deadline=$(( $(date +%s) + WAIT_SECONDS ))
    while (( $(date +%s) < deadline )); do
        pid="$(adb shell pidof "${TARGET_PACKAGE}" 2>/dev/null | tr -d '\r')"
        adb shell dumpsys activity activities | tr -d '\r' \
            > "${ARTIFACT_DIR}/activities-${phase}.txt"
        if [[ -n "${pid}" ]] && [[ "${pid}" == "${previous_pid}" ]] && \
            grep -Eq "(topResumedActivity|mResumedActivity)=.*${TARGET_PACKAGE//./\\.}" \
                "${ARTIFACT_DIR}/activities-${phase}.txt"; then
            consecutive=$((consecutive + 1))
            if (( consecutive >= 3 )); then
                TARGET_PID="${pid}"
                return 0
            fi
        else
            consecutive=0
        fi
        previous_pid="${pid}"
        sleep 0.25
    done
    echo "${phase}：目标未达到稳定 PID/top-resumed 状态" >&2
    return 1
}

xml_has_tag() {
    local xml="$1"
    local tag="$2"
    python3 - "${xml}" "${tag}" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
tag = sys.argv[2]
for node in root.iter("node"):
    resource_id = node.attrib.get("resource-id", "")
    if resource_id == tag or resource_id.endswith("/" + tag) or resource_id.endswith(":" + tag):
        raise SystemExit(0)
raise SystemExit(1)
PY
}

xml_has_tag_text() {
    local xml="$1"
    local tag="$2"
    local expected="$3"
    python3 - "${xml}" "${tag}" "${expected}" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
tag, expected = sys.argv[2], sys.argv[3]
for node in root.iter("node"):
    resource_id = node.attrib.get("resource-id", "")
    if not (resource_id == tag or resource_id.endswith("/" + tag) or resource_id.endswith(":" + tag)):
        continue
    # 标签节点可能是容器，文字落在子节点上（如分享导入状态行拆成格式、大小与状态三段）。
    if any(expected in child.attrib.get("text", "") for child in node.iter("node")):
        raise SystemExit(0)
raise SystemExit(1)
PY
}

xml_has_any_text() {
    local xml="$1"
    local expected="$2"
    python3 - "${xml}" "${expected}" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
expected = sys.argv[2]
for node in root.iter("node"):
    for key in ("text", "content-desc"):
        value = node.attrib.get(key, "")
        if expected in value:
            raise SystemExit(0)
raise SystemExit(1)
PY
}

xml_has_any_text_in_list() {
    local xml="$1"
    local expected_list="$2"
    local expected
    IFS="|" read -r -a expected_items <<< "${expected_list}"
    for expected in "${expected_items[@]}"; do
        if xml_has_any_text "${xml}" "${expected}"; then
            return 0
        fi
    done
    return 1
}

click_tag() {
    local xml="$1"
    local tag="$2"
    local coordinates
    coordinates="$(python3 - "${xml}" "${tag}" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
tag = sys.argv[2]
for node in root.iter("node"):
    resource_id = node.attrib.get("resource-id", "")
    if resource_id == tag or resource_id.endswith("/" + tag) or resource_id.endswith(":" + tag):
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
        if match:
            left, top, right, bottom = map(int, match.groups())
            print(f"{(left + right) // 2} {(top + bottom) // 2}")
            raise SystemExit(0)
raise SystemExit(1)
PY
)"
    adb shell input tap ${coordinates}
}

host_system_anr_wait_coordinates() {
    local xml="$1"
    python3 - "${xml}" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

allowed_titles = {
    "Quickstep isn't responding",
    "Pixel Launcher isn't responding",
    "System UI isn't responding",
}
root = ET.parse(sys.argv[1]).getroot()
title_found = False
wait_bounds = None
for node in root.iter("node"):
    resource_id = node.attrib.get("resource-id", "")
    package_name = node.attrib.get("package", "")
    if (
        package_name == "android"
        and resource_id == "android:id/alertTitle"
        and node.attrib.get("text", "").strip() in allowed_titles
    ):
        title_found = True
    if (
        package_name == "android"
        and resource_id == "android:id/aerr_wait"
        and node.attrib.get("enabled") == "true"
        and node.attrib.get("clickable") == "true"
    ):
        wait_bounds = node.attrib.get("bounds", "")

match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", wait_bounds or "")
if not title_found or match is None:
    raise SystemExit(1)
left, top, right, bottom = map(int, match.groups())
print(f"{(left + right) // 2} {(top + bottom) // 2}")
PY
}

recover_host_system_anr_once() {
    local xml="$1"
    local deadline="$2"
    local click_xml="${ARTIFACT_DIR}/window-host-system-anr-before-wait-click.xml"
    local coordinates
    if [[ "${HOST_SYSTEM_ANR_RECOVERED}" == "true" ]]; then
        echo "宿主 System UI ANR 再次出现，拒绝继续恢复" >&2
        return 1
    fi

    if ! host_system_anr_wait_coordinates "${xml}" >/dev/null; then
        echo "当前窗口不是允许恢复的宿主 System UI ANR" >&2
        return 1
    fi
    if ! cp "${xml}" "${ARTIFACT_DIR}/window-host-system-anr-before-wait.xml"; then
        echo "无法保存宿主 System UI ANR 点击前 XML" >&2
        return 1
    fi
    if ! capture_stable_screenshot \
        "${ARTIFACT_DIR}/screen-host-system-anr-before-wait.png"; then
        echo "无法保存宿主 System UI ANR 点击前截图" >&2
        return 1
    fi
    if ! dump_ui "${click_xml}" "${deadline}"; then
        echo "无法在宿主 System UI ANR 点击前刷新窗口结构" >&2
        return 1
    fi
    if ! coordinates="$(host_system_anr_wait_coordinates "${click_xml}")"; then
        echo "宿主 System UI ANR 的 Wait 按钮已消失或不可点击" >&2
        return 1
    fi

    if ! adb shell input tap ${coordinates}; then
        echo "宿主 System UI ANR 的 Wait 按钮点击失败" >&2
        return 1
    fi
    HOST_SYSTEM_ANR_RECOVERED=true
    sleep 1
}

click_any_text() {
    local xml="$1"
    shift
    local candidates=("$@")
    local candidate_count="${#candidates[@]}"
    if (( candidate_count == 0 )); then
        return 1
    fi
    local coordinates
    coordinates="$(python3 - "${xml}" "${candidates[@]}" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
expected = sys.argv[2:]
for node in root.iter("node"):
    text = node.attrib.get("text", "")
    content_desc = node.attrib.get("content-desc", "")
    enabled = node.attrib.get("enabled", "false") == "true"
    for candidate in expected:
        if enabled and candidate and (candidate in text or candidate in content_desc):
            match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
            if match:
                left, top, right, bottom = map(int, match.groups())
                print(f"{(left + right) // 2} {(top + bottom) // 2}")
                raise SystemExit(0)
raise SystemExit(1)
PY
)"
    [[ -n "${coordinates}" ]] || return 1
    adb shell input tap ${coordinates}
}

wait_for_share_surface() {
    local phase="$1"
    local filename="$2"
    local xml="${ARTIFACT_DIR}/window-${phase}.xml"
    local deadline=$(( $(date +%s) + WAIT_SECONDS ))
    while (( $(date +%s) < deadline )); do
        if dump_ui "${xml}" "${deadline}" && \
            xml_has_tag "${xml}" share_import_sheet && \
            xml_has_tag "${xml}" share_import_item && \
            xml_has_tag "${xml}" share_import_status && \
            xml_has_tag "${xml}" share_import_knowledge_base_target && \
            grep -Fq "${filename}" "${xml}"; then
            LAST_WINDOW_XML="${xml}"
            return 0
        fi
        sleep 0.5
    done
    echo "${phase}：分享导入表面未出现或缺少 ${filename}" >&2
    return 1
}

reject_failure_surface() {
    local xml="$1"
    if grep -Eq 'Rejected|已拒绝|not supported|不支持此文件类型|do not match|文件内容与声明类型不一致|cannot read|无法读取|could not be read|读取文件失败|could not be saved|保存文件失败|permission|权限' "${xml}"; then
        echo "分享导入出现拒绝、读取、MIME、权限或写入失败表面" >&2
        return 1
    fi
}

wait_for_indexed_status() {
    local phase="$1"
    local xml="${ARTIFACT_DIR}/window-${phase}-indexed.xml"
    local english="Imported · Indexed"
    local chinese="已导入 · 已完成索引"
    local deadline=$(( $(date +%s) + WAIT_SECONDS ))
    while (( $(date +%s) < deadline )); do
        if dump_ui "${xml}" "${deadline}"; then
            reject_failure_surface "${xml}"
            assert_target_healthy "${phase}"
            if xml_has_tag_text "${xml}" share_import_status "${english}" || \
                xml_has_tag_text "${xml}" share_import_status "${chinese}"; then
                LAST_WINDOW_XML="${xml}"
                capture_stable_screenshot "${ARTIFACT_DIR}/screen-${phase}-indexed.png"
                return 0
            fi
        fi
        sleep 0.5
    done
    echo "${phase}：未观察到导入并完成索引状态" >&2
    return 1
}

wait_for_failed_retry() {
    local phase="$1"
    local filename="$2"
    local xml="${ARTIFACT_DIR}/window-${phase}-failed-retry.xml"
    local english_failure="Imported · Indexing failed, tap Retry"
    local chinese_failure="已导入 · 索引失败，可重试"
    local english_retry="Retry failed"
    local chinese_retry="重试失败项"
    local deadline=$(( $(date +%s) + WAIT_SECONDS ))
    while (( $(date +%s) < deadline )); do
        if dump_ui "${xml}" "${deadline}"; then
            assert_target_healthy "${phase}"
            if xml_has_any_text "${xml}" "${filename}" && \
                { xml_has_tag_text "${xml}" share_import_status "${english_failure}" || \
                    xml_has_tag_text "${xml}" share_import_status "${chinese_failure}"; } && \
                xml_has_any_text_in_list "${xml}" "${english_retry}|${chinese_retry}"; then
                LAST_WINDOW_XML="${xml}"
                return 0
            fi
        fi
        sleep 0.5
    done
    echo "${phase}：未观察到 ${filename} 的失败 + Retry 状态" >&2
    return 1
}

wait_for_refailed_retry() {
    local phase="$1"
    local filename="$2"
    local xml="${ARTIFACT_DIR}/window-${phase}-refailed-retry.xml"
    local english_failure="Imported · Indexing failed, tap Retry"
    local chinese_failure="已导入 · 索引失败，可重试"
    local english_retry="Retry failed"
    local chinese_retry="重试失败项"
    local deadline=$(( $(date +%s) + WAIT_SECONDS ))
    while (( $(date +%s) < deadline )); do
        if dump_ui "${xml}" "${deadline}"; then
            assert_target_healthy "${phase}"
            if xml_has_any_text "${xml}" "${filename}" && \
                { xml_has_tag_text "${xml}" share_import_status "${english_failure}" || \
                    xml_has_tag_text "${xml}" share_import_status "${chinese_failure}"; } && \
                xml_has_any_text_in_list "${xml}" "${english_retry}|${chinese_retry}"; then
                LAST_WINDOW_XML="${xml}"
                return 0
            fi
        fi
        sleep 0.5
    done
    echo "${phase}：未观察到 ${filename} 的再次失败 + Retry 状态" >&2
    return 1
}

wait_for_initial_launcher_ui() {
    local xml="${ARTIFACT_DIR}/window-launcher.xml"
    local deadline=$(( $(date +%s) + WAIT_SECONDS ))
    while (( $(date +%s) < deadline )); do
        if ! dump_ui "${xml}" "${deadline}"; then
            sleep 0.5
            continue
        fi
        if host_system_anr_wait_coordinates "${xml}" >/dev/null; then
            recover_host_system_anr_once "${xml}" "${deadline}" || return 1
            continue
        fi
        # 字标是不进入无障碍树的灯珠动画，以语言选择区判定欢迎页已稳定。
        if grep -Fq 'Choose language' "${xml}" && \
            grep -Fq 'English' "${xml}" && \
            grep -Fq '中文 (简体)' "${xml}"; then
            LAST_WINDOW_XML="${xml}"
            return 0
        fi
        sleep 0.5
    done
    echo "首次冷启动未出现稳定的语言选择欢迎页（Choose language/English/中文 (简体)）" >&2
    return 1
}

adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
adb logcat -c
adb shell am force-stop "${TARGET_PACKAGE}"
adb shell am start -W -n "${TARGET_COMPONENT}" \
    -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
    > "${ARTIFACT_DIR}/am-start-launcher.txt"
wait_for_launcher_stable launcher
wait_for_initial_launcher_ui
assert_target_healthy launcher
capture_stable_screenshot "${ARTIFACT_DIR}/screen-launcher.png"

run_canary() {
    local name="$1"
    local mime="$2"
    local key="$3"
    local uri="content://${FIXTURE_AUTHORITY}/${name}"
    local durable_xml="${ARTIFACT_DIR}/window-${key}-durable.xml"

    adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
    adb logcat -c
    adb shell am force-stop "${TARGET_PACKAGE}"
    adb shell am start -S -W -n "${FIXTURE_SHARE_COMPONENT}" \
        --es document_name "${name}" --es mime_type "${mime}" \
        --es target_component "${TARGET_COMPONENT}" \
        > "${ARTIFACT_DIR}/am-start-${key}-share.txt"
    wait_for_launcher_stable "${key}-share"
    wait_for_share_surface "${key}-share" "${name}"
    reject_failure_surface "${LAST_WINDOW_XML}"
    assert_target_healthy "${key}-share"
    capture_stable_screenshot "${ARTIFACT_DIR}/screen-${key}-share.png"

    adb shell am force-stop "${TARGET_PACKAGE}"
    adb shell am start -W -n "${TARGET_COMPONENT}" \
        -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
        > "${ARTIFACT_DIR}/am-start-${key}-durable.txt"
    wait_for_launcher_stable "${key}-durable"
    wait_for_share_surface "${key}-durable" "${name}"
    require_file "${durable_xml}"
    click_tag "${durable_xml}" share_import_knowledge_base_target
    sleep 0.25
    dump_ui "${durable_xml}" "$(( $(date +%s) + WAIT_SECONDS ))"
    click_tag "${durable_xml}" share_import_action
    wait_for_indexed_status "${key}"
}

run_canary_text() {
    local name="$1"
    local mime="$2"
    local key="$3"
    local uri="content://${FIXTURE_AUTHORITY}/${name}"
    local durable_xml="${ARTIFACT_DIR}/window-${key}-durable.xml"
    local trigger_xml="${ARTIFACT_DIR}/window-${key}-retry-trigger.xml"
    local english_retry="Retry failed"
    local chinese_retry="重试失败项"

    adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
    adb logcat -c
    adb shell am force-stop "${TARGET_PACKAGE}"
    adb shell am start -S -W -n "${FIXTURE_SHARE_COMPONENT}" \
        --es document_name "${name}" --es mime_type "${mime}" \
        --es target_component "${TARGET_COMPONENT}" \
        > "${ARTIFACT_DIR}/am-start-${key}-share.txt"
    wait_for_launcher_stable "${key}-share"
    wait_for_share_surface "${key}-share" "${name}"
    assert_target_healthy "${key}-share"
    capture_stable_screenshot "${ARTIFACT_DIR}/screen-${key}-share.png"

    adb shell am force-stop "${TARGET_PACKAGE}"
    adb shell am start -W -n "${TARGET_COMPONENT}" \
        -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
        > "${ARTIFACT_DIR}/am-start-${key}-durable.txt"
    wait_for_launcher_stable "${key}-durable"
    wait_for_share_surface "${key}-durable" "${name}"
    require_file "${durable_xml}"
    click_tag "${durable_xml}" share_import_knowledge_base_target
    sleep 0.25
    dump_ui "${durable_xml}" "$(( $(date +%s) + WAIT_SECONDS ))"
    click_tag "${durable_xml}" share_import_action
    if ! wait_for_failed_retry "${key}-failed" "${name}"; then
        echo "${key}：未观察到文本索引失败与可重试序列" >&2
        return 1
    fi
    if ! dump_ui "${trigger_xml}" "$(( $(date +%s) + WAIT_SECONDS ))"; then
        echo "${key}：读取文本重试触发页失败" >&2
        return 1
    fi
    click_any_text "${trigger_xml}" "${english_retry}" "${chinese_retry}"
    sleep 0.25
    if ! wait_for_refailed_retry "${key}-refailed" "${name}"; then
        echo "${key}：未再次观察到文本失败 + Retry 状态" >&2
        return 1
    fi

    capture_stable_screenshot "${ARTIFACT_DIR}/screen-${key}-refailed.png"
    assert_target_healthy "${key}-refailed"
}

run_canary release-parser-canary-empty.pdf application/pdf pdf
run_canary release-parser-canary-empty.docx \
    application/vnd.openxmlformats-officedocument.wordprocessingml.document docx
run_canary_text "${TEXT_CANARY_NAME}" "${TEXT_CANARY_MIME}" text

printf 'api_level=%s\nabi=%s\ntarget_package=%s\ndefault_minified_mode=%s\n' \
    "${API_LEVEL}" "${TARGET_ABI}" "${TARGET_PACKAGE}" "${DEFAULT_MINIFIED_MODE}" \
    > "${ARTIFACT_DIR}/matrix.txt"

echo "API ${API_LEVEL} release-equivalent minified 黑盒冷启动、PDF/DOCX 分享导入及文本索引失败重试通过。"
