#!/usr/bin/env bash

set -euo pipefail
# 即使调用者启用了 bash -x，也不跟踪设备验收中的认证参数。
set +x

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${REPO_ROOT}/scripts/ci/lib/android-instrumentation.sh"
NATIVE_ROOT="${REPO_ROOT}/native-ui"
DEVICE_E2E_ABI="${INFINITY_DEVICE_E2E_ABI:-x86_64}"
DEVICE_E2E_SCOPE="${INFINITY_DEVICE_E2E_SCOPE:-full}"
BUILD_TYPE_TASK="DeviceTest"
DISTRIBUTION_TASK="Sideload"
API_LEVEL="$(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
case "${DEVICE_E2E_ABI}" in
    x86_64|arm64-v8a) ;;
    *)
        echo "INFINITY_DEVICE_E2E_ABI 仅支持 x86_64 或 arm64-v8a：${DEVICE_E2E_ABI}" >&2
        exit 1
        ;;
esac
TARGET_PACKAGE="com.promenar.infinity.deviceTest"
TARGET_PACKAGE_REGEX="${TARGET_PACKAGE//./\\.}"
APP_APK="${NATIVE_ROOT}/app/build/outputs/apk/sideload/deviceTest/app-sideload-deviceTest.apk"
APP_TEST_APK="${NATIVE_ROOT}/app/build/outputs/apk/androidTest/sideload/deviceTest/app-sideload-deviceTest-androidTest.apk"
MAIN_ACTIVITY_TEST_APK="${NATIVE_ROOT}/mainactivity-e2e/build/outputs/apk/deviceTest/mainactivity-e2e-deviceTest.apk"
case "${DEVICE_E2E_SCOPE}" in
    minimum|full) ;;
    *)
        echo "INFINITY_DEVICE_E2E_SCOPE 仅支持 minimum 或 full：${DEVICE_E2E_SCOPE}" >&2
        exit 1
        ;;
esac
ARTIFACT_DIR_INPUT="${ANDROID_E2E_ARTIFACT_DIR:-artifacts/android-device-api-${API_LEVEL:-unknown}-${DEVICE_E2E_ABI}}"
if [[ "${ARTIFACT_DIR_INPUT}" == /* ]]; then
    ARTIFACT_DIR="${ARTIFACT_DIR_INPUT}"
else
    ARTIFACT_DIR="${REPO_ROOT}/${ARTIFACT_DIR_INPUT}"
fi
INSTRUMENT_TIMEOUT_SECONDS="${ANDROID_E2E_TIMEOUT_SECONDS:-120}"
APP_RUNNER=""
MAIN_ACTIVITY_RUNNER=""
TIMEOUT_HELPER="${REPO_ROOT}/scripts/ci/run-with-timeout.py"
DISPLAY_STATE_CAPTURED=false
ORIGINAL_WM_SIZE_OVERRIDE=""
ORIGINAL_WM_DENSITY_OVERRIDE=""
ORIGINAL_FONT_SCALE=""
ORIGINAL_ACCELEROMETER_ROTATION=""
ORIGINAL_USER_ROTATION=""
PENDING_NOTIFICATION_SCREENSHOT_STAGE=""
PENDING_NOTIFICATION_SCREENSHOT_EXPECTED=""
SSH_HELPER="${REPO_ROOT}/scripts/ci/ssh-device-session.py"
SSH_REVERSE_PORT=""
SSH_REVERSE_ACTIVE=false
SHARED_STORAGE_ACCESS_GRANTED=false
if [[ -n "${INFINITY_SSH_TEST_SESSION:-}" ]]; then
    if [[ "${INFINITY_SSH_TEST_SESSION}" != /* ]]; then
        export INFINITY_SSH_TEST_SESSION="${PWD}/${INFINITY_SSH_TEST_SESSION}"
    fi
    python3 "${SSH_HELPER}" validate
fi

redact_output() {
    python3 "${SSH_HELPER}" redact
}

mkdir -p "${ARTIFACT_DIR}"
printf 'api_level=%s\nabi=%s\nscope=%s\ndistribution=sideload\nbuild_type=deviceTest\n' \
    "${API_LEVEL}" "${DEVICE_E2E_ABI}" "${DEVICE_E2E_SCOPE}" \
    > "${ARTIFACT_DIR}/matrix.txt"

capture_artifacts() {
    local exit_code="$1"
    set +e
    adb logcat -d -v threadtime 2>&1 | redact_output > "${ARTIFACT_DIR}/logcat.txt"
    adb logcat -b crash -d -v threadtime 2>&1 | redact_output > "${ARTIFACT_DIR}/crash-log.txt"
    adb shell dumpsys activity processes 2>&1 | redact_output > "${ARTIFACT_DIR}/activity-processes.txt"
    adb shell dumpsys package "${TARGET_PACKAGE}" 2>&1 | redact_output > "${ARTIFACT_DIR}/target-package.txt"
    adb shell uiautomator dump /sdcard/infinity-window.xml >/dev/null 2>&1
    adb shell cat /sdcard/infinity-window.xml 2>/dev/null | redact_output > "${ARTIFACT_DIR}/window.xml"
    adb exec-out screencap -p > "${ARTIFACT_DIR}/screen.png" 2>/dev/null
    printf '%s\n' "${exit_code}" > "${ARTIFACT_DIR}/exit-code.txt"
    set -e
}

capture_device_display_state() {
    local wm_size_output
    local wm_density_output
    wm_size_output="$(adb shell wm size 2>/dev/null | tr -d '\r')"
    wm_density_output="$(adb shell wm density 2>/dev/null | tr -d '\r')"
    ORIGINAL_WM_SIZE_OVERRIDE="$(printf '%s\n' "${wm_size_output}" | sed -n 's/^Override size: //p')"
    ORIGINAL_WM_DENSITY_OVERRIDE="$(printf '%s\n' "${wm_density_output}" | sed -n 's/^Override density: //p')"
    ORIGINAL_FONT_SCALE="$(adb shell settings get system font_scale 2>/dev/null | tr -d '\r')"
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
    if [[ "${DISPLAY_STATE_CAPTURED}" != "true" ]]; then
        return 0
    fi

    if [[ -n "${ORIGINAL_WM_SIZE_OVERRIDE}" ]]; then
        adb shell wm size "${ORIGINAL_WM_SIZE_OVERRIDE}" >/dev/null 2>&1 || true
    else
        adb shell wm size reset >/dev/null 2>&1 || true
    fi
    if [[ -n "${ORIGINAL_WM_DENSITY_OVERRIDE}" ]]; then
        adb shell wm density "${ORIGINAL_WM_DENSITY_OVERRIDE}" >/dev/null 2>&1 || true
    else
        adb shell wm density reset >/dev/null 2>&1 || true
    fi
    restore_setting system font_scale "${ORIGINAL_FONT_SCALE}"
    restore_setting system accelerometer_rotation "${ORIGINAL_ACCELEROMETER_ROTATION}"
    restore_setting system user_rotation "${ORIGINAL_USER_ROTATION}"
    DISPLAY_STATE_CAPTURED=false
}

# instrumentation 结束时 UiAutomation 可能解冻屏幕旋转；整轮开始时记录、退出时恢复用户的旋转设置。
ROTATION_STATE_CAPTURED=false
capture_rotation_state() {
    SESSION_ACCELEROMETER_ROTATION="$(adb shell settings get system accelerometer_rotation 2>/dev/null | tr -d '\r')"
    SESSION_USER_ROTATION="$(adb shell settings get system user_rotation 2>/dev/null | tr -d '\r')"
    ROTATION_STATE_CAPTURED=true
}

restore_rotation_state() {
    if [[ "${ROTATION_STATE_CAPTURED}" != "true" ]]; then
        return 0
    fi
    restore_setting system accelerometer_rotation "${SESSION_ACCELEROMETER_ROTATION}"
    restore_setting system user_rotation "${SESSION_USER_ROTATION}"
}

finish_run() {
    local exit_code="$1"
    if [[ "${SHARED_STORAGE_ACCESS_GRANTED}" == "true" ]]; then
        adb shell appops set "${TARGET_PACKAGE}" MANAGE_EXTERNAL_STORAGE default >/dev/null 2>&1 || true
    fi
    if [[ "${SSH_REVERSE_ACTIVE}" == "true" ]]; then
        adb reverse --remove "tcp:${SSH_REVERSE_PORT}" >/dev/null 2>&1 || true
        SSH_REVERSE_ACTIVE=false
    fi
    if [[ -n "${PENDING_NOTIFICATION_SCREENSHOT_STAGE}" ]]; then
        pull_notification_permission_screenshots \
            "${PENDING_NOTIFICATION_SCREENSHOT_STAGE}" \
            "${PENDING_NOTIFICATION_SCREENSHOT_EXPECTED}" || true
    fi
    capture_artifacts "${exit_code}"
    restore_device_display_state
    restore_rotation_state
}

trap 'finish_run "$?"' EXIT

require_file() {
    if [[ ! -f "$1" ]]; then
        echo "缺少构建产物：$1" >&2
        exit 1
    fi
}

discover_runner() {
    local runner_class="$1"
    local matches
    matches="$(adb shell pm list instrumentation | tr -d '\r' | sed -n \
        "s#^instrumentation:\([^ ]*/${runner_class}\) (target=${TARGET_PACKAGE})#\1#p")"
    if [[ -z "${matches}" ]] || [[ "$(printf '%s\n' "${matches}" | wc -l | tr -d ' ')" != "1" ]]; then
        echo "无法唯一定位 ${runner_class}，当前 instrumentation：" >&2
        adb shell pm list instrumentation >&2
        exit 1
    fi
    printf '%s\n' "${matches}"
}

instrument_timeout_for_test() {
    if [[ -n "${ANDROID_E2E_TIMEOUT_SECONDS:-}" ]]; then
        printf '%s\n' "${ANDROID_E2E_TIMEOUT_SECONDS}"
    elif [[ "$1" == "workspace-filesystem-contracts" ]]; then
        # 此套件包含单项 180 秒的千文件测试，外层还需覆盖其余用例和启动开销。
        printf '%s\n' 240
    else
        printf '%s\n' "${INSTRUMENT_TIMEOUT_SECONDS}"
    fi
}

run_test() {
    local name="$1"
    local runner="$2"
    shift 2
    local output_file="${ARTIFACT_DIR}/${name}.txt"
    local status
    local -a pipeline_status
    echo "运行设备测试：${name}"
    set +e
    python3 "${TIMEOUT_HELPER}" "$(instrument_timeout_for_test "${name}")" adb shell am instrument -w -r "$@" "${runner}" \
        2>&1 | redact_output | tee "${output_file}"
    pipeline_status=("${PIPESTATUS[@]}")
    status="${pipeline_status[0]}"
    set -e
    if (( pipeline_status[1] != 0 || pipeline_status[2] != 0 )); then
        echo "设备测试输出脱敏或产物写入失败：${name}" >&2
        return 1
    fi
    infinity_assert_normal_instrumentation_output "${output_file}" "${status}"
}

run_ssh_device_contracts() {
    if [[ -z "${INFINITY_SSH_TEST_SESSION:-}" ]]; then
        echo "跳过 SSH 设备用例：未设置 INFINITY_SSH_TEST_SESSION。"
        return 0
    fi
    SSH_REVERSE_PORT="$(python3 "${SSH_HELPER}" port)"
    # 仅经 ADB 到宿主回环；不使用 10.0.2.2，也不覆盖调用者已有的 reverse 规则。
    adb reverse --no-rebind "tcp:${SSH_REVERSE_PORT}" "tcp:${SSH_REVERSE_PORT}"
    SSH_REVERSE_ACTIVE=true
    prepare_target_for_foreground_notification
    local output_file="${ARTIFACT_DIR}/ssh-runtime.txt"
    local -a pipeline_status
    echo "运行设备测试：ssh-runtime"
    set +e
    python3 "${SSH_HELPER}" run --runner "${APP_RUNNER}" --timeout "${INSTRUMENT_TIMEOUT_SECONDS}" \
        2>&1 | tee "${output_file}"
    pipeline_status=("${PIPESTATUS[@]}")
    set -e
    if (( pipeline_status[1] != 0 )); then
        echo "SSH 设备测试产物写入失败" >&2
        return 1
    fi
    infinity_assert_normal_instrumentation_output "${output_file}" "${pipeline_status[0]}"
    adb reverse --remove "tcp:${SSH_REVERSE_PORT}"
    SSH_REVERSE_ACTIVE=false
}

run_layout_and_accessibility_contracts() {
    local status=0
    capture_device_display_state

    # 这些测试在 Compose 测试宿主内自行覆盖 2.0x 字体、横屏、紧凑/展开宽度，
    # 不用 shell 强改显示参数；无论测试成功与否，阶段结束都恢复设备显示状态。
    run_test accessibility-smoke "${APP_RUNNER}" \
        -e class com.promenar.infinity.ui.AccessibilitySmokeTest || status=$?
    run_test adaptive-navigation "${APP_RUNNER}" \
        -e class com.promenar.infinity.ui.AdaptiveNavigationTest || status=$?
    run_test onboarding-layout "${APP_RUNNER}" \
        -e class com.promenar.infinity.onboarding.OnboardingScreenLayoutTest || status=$?
    run_test text-wrapping "${APP_RUNNER}" \
        -e class com.promenar.infinity.ui.TextWrappingDeviceTest || status=$?

    restore_device_display_state
    return "${status}"
}

force_stop_target() {
    adb shell am force-stop "${TARGET_PACKAGE}"
    local attempt
    for attempt in $(seq 1 50); do
        if [[ -z "$(adb shell pidof "${TARGET_PACKAGE}" 2>/dev/null | tr -d '\r')" ]]; then
            return 0
        fi
        sleep 0.1
    done
    echo "目标进程未在 force-stop 后退出" >&2
    return 1
}

wait_for_package_manager_idle() {
    if (( API_LEVEL >= 33 )); then
        adb shell cmd package wait-for-handler --timeout 5000 >/dev/null
        adb shell cmd package wait-for-background-handler --timeout 5000 >/dev/null
    else
        # Android 12 尚未提供 wait-for-handler 子命令，给权限状态落盘留出稳定窗口。
        sleep 1
    fi
}

reset_target_with_notification_permission_revoked() {
    adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
    adb shell pm revoke "${TARGET_PACKAGE}" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
    adb shell pm clear-permission-flags \
        "${TARGET_PACKAGE}" android.permission.POST_NOTIFICATIONS user-set user-fixed \
        >/dev/null 2>&1 || true
    wait_for_package_manager_idle
}

reset_target_with_notification_permission_granted() {
    adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
    adb shell pm grant "${TARGET_PACKAGE}" android.permission.POST_NOTIFICATIONS
    wait_for_package_manager_idle
}

pull_notification_permission_screenshots() {
    local stage="$1"
    local expected_name="$2"
    local remote_dir="/sdcard/Android/data/${TARGET_PACKAGE}/files"
    local remote_files
    local remote_file
    local pulled=0
    local pull_failed=false

    if ! remote_files="$(adb shell find "${remote_dir}" -maxdepth 1 -type f -name '*.png' -print 2>&1)"; then
        echo "无法枚举 ${stage} 权限截图：${remote_files}" >&2
        return 1
    fi
    remote_files="${remote_files//$'\r'/}"

    while IFS= read -r remote_file; do
        [[ -n "${remote_file}" ]] || continue
        if ! adb pull "${remote_file}" "${ARTIFACT_DIR}/${stage}-$(basename "${remote_file}")" >/dev/null; then
            echo "${stage} 无法回拉权限截图：${remote_file}" >&2
            pull_failed=true
            continue
        fi
        pulled=$((pulled + 1))
    done <<<"${remote_files}"
    if (( pulled == 0 )); then
        echo "${stage} 未回拉任何权限截图" >&2
        return 1
    fi
    if [[ "${pull_failed}" == "true" ]]; then
        return 1
    fi
    if ! grep -Fxq -- "${remote_dir}/${expected_name}" <<<"${remote_files}"; then
        echo "${stage} 缺少预期权限截图：${expected_name}" >&2
        return 1
    fi
}

run_notification_permission_test() {
    local stage="$1"
    local expected_name="$2"
    shift 2
    local test_status=0
    local pull_status=0

    PENDING_NOTIFICATION_SCREENSHOT_STAGE="${stage}"
    PENDING_NOTIFICATION_SCREENSHOT_EXPECTED="${expected_name}"
    run_test "${stage}" "${MAIN_ACTIVITY_RUNNER}" "$@" || test_status=$?
    pull_notification_permission_screenshots "${stage}" "${expected_name}" || pull_status=$?
    if (( pull_status == 0 )); then
        PENDING_NOTIFICATION_SCREENSHOT_STAGE=""
        PENDING_NOTIFICATION_SCREENSHOT_EXPECTED=""
    fi

    if (( test_status != 0 )); then
        return "${test_status}"
    fi
    return "${pull_status}"
}

run_notification_permission_contracts() {
    reset_target_with_notification_permission_revoked
    run_notification_permission_test \
        notification-permission-deny notification-permission-deny-dialog.png \
        -e class com.promenar.infinity.MainActivityNotificationE2eTest#denyingSystemNotificationPermissionContinuesForegroundOnlyWithoutFgs

    reset_target_with_notification_permission_revoked
    run_notification_permission_test \
        notification-permission-grant notification-permission-grant-dialog.png \
        -e class com.promenar.infinity.MainActivityNotificationE2eTest#grantingSystemNotificationPermissionContinuesBackgroundAllowed
}

run_notification_open_session_contract() {
    if (( API_LEVEL >= 33 )); then
        reset_target_with_notification_permission_granted
    else
        adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
        wait_for_package_manager_idle
    fi
    run_test notification-open-session "${MAIN_ACTIVITY_RUNNER}" \
        -e class com.promenar.infinity.MainActivityNotificationE2eTest#postedNotificationOpenPendingIntentReturnsToExactSession
}

run_notification_background_lifecycle_contract() {
    prepare_target_for_foreground_notification
    run_test notification-background-lifecycle "${MAIN_ACTIVITY_RUNNER}" \
        -e class com.promenar.infinity.MainActivityNotificationE2eTest#backgroundGenerationSurvivesRealLockWakeRotationAndRepeatedStopDoesNotRevive
}

prepare_target_for_foreground_notification() {
    if (( API_LEVEL >= 33 )); then
        reset_target_with_notification_permission_granted
    else
        adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
        wait_for_package_manager_idle
    fi
}

run_expected_relay_death() {
    local phase="${1:-stage}"
    local output_file="${ARTIFACT_DIR}/restore-relay-${phase}-expected-death.txt"
    local relay_log_file="${ARTIFACT_DIR}/restore-relay-${phase}-relay-logcat.txt"
    local after_pids_file="${ARTIFACT_DIR}/restore-relay-${phase}-after-pids.txt"
    local status
    echo "运行预期杀进程阶段：restore-relay-${phase}"
    # 只清理 relay 证据所在的 main buffer，保留此前测试的 crash buffer 供最终门禁审计。
    adb logcat -b main -c
    set +e
    python3 "${TIMEOUT_HELPER}" "${INSTRUMENT_TIMEOUT_SECONDS}" adb shell am instrument -w -r \
        -e class com.promenar.infinity.data.backup.AndroidRestoreRelayEndToEndTest \
        -e restoreRelayPhase "${phase}" \
        "${APP_RUNNER}" 2>&1 | redact_output | tee "${output_file}"
    status="${PIPESTATUS[0]}"
    set -e

    infinity_assert_expected_process_death_output "${output_file}" "${status}"
    adb logcat -b main -d -v threadtime -s InfinityLogger:D '*:S' 2>&1 | redact_output > "${relay_log_file}"
    if ! grep -Eq '\[InfinityRestoreRelay\] relay_pid=[0-9]+ main_pid=[0-9]+ payload=none' "${relay_log_file}"; then
        echo "预期进程死亡阶段缺少 RestoreRelayActivity 专属执行证据：${relay_log_file}" >&2
        return 1
    fi
    if [[ "$(adb get-state 2>/dev/null)" != "device" ]]; then
        echo "relay 阶段后 ADB 设备不可用，不能判定为预期进程死亡" >&2
        return 1
    fi
    # relay 可能已拉起新进程；下一 verify 阶段会用持久化的 KEY_STAGE_PID
    # 断言新 PID 与被杀死的 stage PID 不同，这里只保留阶段结束时的进程证据。
    adb shell pidof "${TARGET_PACKAGE}" 2>/dev/null | tr -d '\r' > "${after_pids_file}" || true
}

cd "${NATIVE_ROOT}"
chmod +x gradlew
./gradlew --stacktrace -Pinfinity.deviceE2e=true \
    -Pinfinity.deviceE2eAbi="${DEVICE_E2E_ABI}" \
    ":app:assemble${DISTRIBUTION_TASK}${BUILD_TYPE_TASK}" \
    ":app:assemble${DISTRIBUTION_TASK}${BUILD_TYPE_TASK}AndroidTest" \
    ":mainactivity-e2e:assemble${BUILD_TYPE_TASK}"

require_file "${APP_APK}"
require_file "${APP_TEST_APK}"
require_file "${MAIN_ACTIVITY_TEST_APK}"

adb wait-for-device
capture_rotation_state
adb shell pm uninstall "${TARGET_PACKAGE}" >/dev/null 2>&1 || true
adb install --no-streaming "${APP_APK}"
adb install -r --no-streaming "${APP_TEST_APK}"
adb install -r --no-streaming "${MAIN_ACTIVITY_TEST_APK}"

APP_RUNNER="$(discover_runner androidx.test.runner.AndroidJUnitRunner)"
MAIN_ACTIVITY_RUNNER="$(discover_runner com.promenar.infinity.MainActivityE2eRunner)"
printf '%s\n' "${APP_RUNNER}" > "${ARTIFACT_DIR}/app-runner.txt"
printf '%s\n' "${MAIN_ACTIVITY_RUNNER}" > "${ARTIFACT_DIR}/mainactivity-runner.txt"

adb logcat -c

# 专用设备测试包已在上方重新安装，共享卷用例需要真实的所有文件访问权限。
adb shell appops set "${TARGET_PACKAGE}" MANAGE_EXTERNAL_STORAGE allow
SHARED_STORAGE_ACCESS_GRANTED=true
run_test shared-storage-file-tools "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.storage.SharedStorageFileToolsDeviceTest
adb shell appops set "${TARGET_PACKAGE}" MANAGE_EXTERNAL_STORAGE default
SHARED_STORAGE_ACCESS_GRANTED=false

run_test workspace-filesystem-contracts "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.repository.AndroidSecureWorkspaceFileOpsTest
run_test workspace-ported-filesystem-contracts "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.repository.AndroidPortedSecureWorkspaceFileOpsTest
run_test workspace-creation-races "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.repository.AndroidSecureWorkspaceCreationRaceTest
run_test recovery-snapshot-contracts "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.local.db.recovery.AndroidRecoverySnapshotStoreTest
run_test dual-database-recovery "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.local.db.recovery.AndroidDualDatabaseRecoveryDeviceTest
run_test dual-database-interruption "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.local.db.recovery.AndroidDualDatabaseRecoveryInterruptionTest
run_test dual-database-source-probe "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.local.db.recovery.AndroidDualDatabaseSourceProbeSafetyTest
run_test dual-database-workspace-recovery "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.local.db.recovery.AndroidDualDatabaseWorkspaceRecoveryTest
run_test legacy-attachment-access "${APP_RUNNER}" \
    -e class com.promenar.infinity.ui.chat.LegacyAttachmentDeviceTest
run_test chat-image-encoder "${APP_RUNNER}" \
    -e class com.promenar.infinity.ui.chat.ChatImageEncoderDeviceTest
run_test thinking-preview-motion "${APP_RUNNER}" \
    -e class com.promenar.infinity.ui.chat.ThinkingPreviewMotionTest
run_test inspector-strip-drag "${APP_RUNNER}" \
    -e class com.promenar.infinity.ui.chat.InspectorStripDragTest
run_test agent-hub-content "${APP_RUNNER}" \
    -e class com.promenar.infinity.ui.hub.AgentHubScreenContentTest
run_test packaged-python "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.exec.PackagedPythonDeviceTest
run_test builtin-exec "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.exec.BuiltinExecDeviceTest
run_test directory-workspace "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.workspace.DirectoryWorkspaceDeviceTest
run_test workspace-picker "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.workspace.WorkspacePickerDeviceTest
run_test git-inspector "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.git.GitInspectorDeviceTest
run_test git-remote-auth "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.git.remote.GitRemoteAuthDeviceTest
tailnet_args=(-e class com.promenar.infinity.data.tailnet.TailnetDeviceTest)
if [[ "${INFINITY_E2E_TAILNET_CONTROL_PLANE:-}" == "skip" ]]; then
    echo "跳过 Tailscale 控制面交互登录用例：INFINITY_E2E_TAILNET_CONTROL_PLANE=skip。"
    tailnet_args+=(-e tailnetControlPlane skip)
fi
run_test tailnet-node "${APP_RUNNER}" "${tailnet_args[@]}"

if [[ "${DEVICE_E2E_SCOPE}" == "full" ]]; then
    if (( API_LEVEL >= 33 )); then
        run_notification_permission_contracts
    else
        echo "API ${API_LEVEL} 无运行时通知权限，跳过 Android 13+ 权限弹窗 E2E。"
    fi
    run_notification_open_session_contract
    run_notification_background_lifecycle_contract
fi

adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
run_test mainactivity-chat-flow "${MAIN_ACTIVITY_RUNNER}" \
    -e class com.promenar.infinity.MainActivityChatFlowE2eTest

adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
run_test mainactivity-new-session "${MAIN_ACTIVITY_RUNNER}" \
    -e class com.promenar.infinity.MainActivityNewSessionE2eTest

adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
run_test onboarding-full "${APP_RUNNER}" \
    -e class com.promenar.infinity.onboarding.OnboardingAndroidEndToEndTest
run_test prompt-editor-contracts "${APP_RUNNER}" \
    -e class com.promenar.infinity.ui.common.UnifiedPromptEditorE2ETest
run_layout_and_accessibility_contracts

# API 31 minimum 与 API 35 full 共用这些真实设备能力合同。
run_test external-folder-saf "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.storage.SafExternalFolderDeviceTest
run_test script-runtime "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.script.ScriptRuntimeDeviceTest
run_test rich-content-webview "${APP_RUNNER}" \
    -e class com.promenar.infinity.ui.renderer.RichContentWebViewDeviceTest
run_test rich-content-theme "${APP_RUNNER}" \
    -e class com.promenar.infinity.ui.renderer.RichContentThemeTest

if [[ "${DEVICE_E2E_SCOPE}" == "minimum" ]]; then
    echo "跳过 SSH 设备用例：minimum 范围不运行 SSH。"
    adb logcat -b crash -d -v threadtime 2>&1 | redact_output > "${ARTIFACT_DIR}/crash-after-tests.txt"
    if grep -Eq "Process: ${TARGET_PACKAGE_REGEX}([,[:space:]]|$)|ANR in ${TARGET_PACKAGE_REGEX}" "${ARTIFACT_DIR}/crash-after-tests.txt"; then
        echo "最低 SDK 核心回归后 crash buffer 包含 Infinity Deck 崩溃" >&2
        exit 1
    fi
    echo "API ${API_LEVEL} 最低 SDK 核心可达性、无障碍与自适应布局测试全部通过。"
    exit 0
fi

run_test external-folder-real-path "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.storage.RealPathExternalFolderDeviceTest
run_test database-migration-1-2 "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.local.db.InfinityDatabaseMigration1To2Test
run_test database-migration-2-3 "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.local.db.InfinityDatabaseMigration2To3Test
run_test database-migration-1-current "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.local.db.InfinityDatabaseMigration1To5Test
run_test database-migration-18-22 "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.local.db.InfinityDatabaseMigration18To23Test
run_test legacy-database-promotion "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.local.db.LegacyDatabasePromoterInstrumentedTest
run_ssh_device_contracts

run_test onboarding-seed-first-chat "${APP_RUNNER}" \
    -e class com.promenar.infinity.onboarding.OnboardingAndroidEndToEndTest#forceStopPhaseCheckpoint \
    -e onboardingPhase seed_first_chat
force_stop_target
run_test onboarding-verify-first-chat "${APP_RUNNER}" \
    -e class com.promenar.infinity.onboarding.OnboardingAndroidEndToEndTest#forceStopPhaseCheckpoint \
    -e onboardingPhase verify_first_chat

run_test backup-compose-saf-roundtrip "${APP_RUNNER}" \
    -e class com.promenar.infinity.ui.settings.BackupComposeSafRoundTripTest

run_test backup-process-prepare "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.backup.AndroidBackupProcessRecreationTest \
    -e backupPhase prepare
force_stop_target
run_test backup-process-commit "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.backup.AndroidBackupProcessRecreationTest \
    -e backupPhase commit

adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
run_test restore-relay-export-no-key "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.backup.AndroidRestoreRelayEndToEndTest \
    -e restoreRelayPhase exportNoKey
force_stop_target
run_expected_relay_death stageNoKey
run_test restore-relay-verify-no-key "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.backup.AndroidRestoreRelayEndToEndTest \
    -e restoreRelayPhase verifyNoKey
force_stop_target
run_test restore-relay-no-replay-no-key "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.backup.AndroidRestoreRelayEndToEndTest \
    -e restoreRelayPhase noReplayNoKey

adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
run_test restore-relay-export "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.backup.AndroidRestoreRelayEndToEndTest \
    -e restoreRelayPhase export
force_stop_target
run_expected_relay_death stage
run_test restore-relay-verify "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.backup.AndroidRestoreRelayEndToEndTest \
    -e restoreRelayPhase verify
force_stop_target
run_test restore-relay-no-replay "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.backup.AndroidRestoreRelayEndToEndTest \
    -e restoreRelayPhase noReplay

run_test generation-notification-factory "${APP_RUNNER}" \
    -e class com.promenar.infinity.background.generation.GenerationNotificationFactoryTest
prepare_target_for_foreground_notification
run_test generation-foreground-service "${APP_RUNNER}" \
    -e class com.promenar.infinity.background.generation.GenerationForegroundServiceDeviceTest

run_test document-parser-fixtures "${APP_RUNNER}" \
    -e class com.promenar.infinity.data.rag.DocumentParserDeviceE2eTest
run_test share-import-contracts "${APP_RUNNER}" \
    -e class com.promenar.infinity.share.core.ShareImportAndroidEndToEndTest

force_stop_target
run_test generation-cold-track "${APP_RUNNER}" \
    -e class com.promenar.infinity.background.generation.GenerationForegroundServiceColdStartDeviceTest#coldTrackDuringStartupRecoveryStopsGracefullyWithoutProcessCrash
force_stop_target
run_test generation-cold-stop "${APP_RUNNER}" \
    -e class com.promenar.infinity.background.generation.GenerationForegroundServiceColdStartDeviceTest#coldStopDuringStartupRecoveryStopsGracefullyWithoutCoordinator

adb logcat -b crash -d -v threadtime 2>&1 | redact_output > "${ARTIFACT_DIR}/crash-after-tests.txt"
if grep -Eq "Process: ${TARGET_PACKAGE_REGEX}([,[:space:]]|$)|ANR in ${TARGET_PACKAGE_REGEX}" "${ARTIFACT_DIR}/crash-after-tests.txt"; then
    echo "设备回归后 crash buffer 包含 Infinity Deck 崩溃" >&2
    exit 1
fi

echo "API ${API_LEVEL} 完整设备 E2E 全部通过。"
