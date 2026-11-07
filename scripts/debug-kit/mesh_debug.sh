#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ANDROID_DIR="$REPO_ROOT/android"
LOGS_DIR="$REPO_ROOT/logs"
ANALYSIS_DIR="$LOGS_DIR/analysis"
DEEP_TRACE_PY="$SCRIPT_DIR/deep_trace.py"
DIVERGENCE_PY="$SCRIPT_DIR/divergence_detector.py"
TOKEN_SELECTOR_PY="$SCRIPT_DIR/token_selector.py"
RELAY_DB_REPORT_PY="$SCRIPT_DIR/relay_db_report.py"

usage() {
  cat <<'USAGE'
Usage:
  mesh_debug.sh devices
  mesh_debug.sh build [emergency|official|official-debug|both|official-release]
  mesh_debug.sh install [emergency|official|official-debug|official-release] [serial ...]
  mesh_debug.sh reset-db [official|emergency|auto] <serial ...>
  mesh_debug.sh check-ids [official|emergency|auto] <serial ...>
  mesh_debug.sh release-smoke
  mesh_debug.sh pull-activity [auto|emergency|official] [serial ...]
  mesh_debug.sh latest [count]
  mesh_debug.sh metrics [log_file ...]
  mesh_debug.sh dtn [log_file ...]
  mesh_debug.sh dtn-md [log_file ...]
  mesh_debug.sh classify [log_file ...]
  mesh_debug.sh trace <needle> [log_file ...]
  mesh_debug.sh deep-trace <needle> [log_file ...]
  mesh_debug.sh deep-trace-md <needle> [log_file ...]
  mesh_debug.sh divergence <needle> [log_file ...]
  mesh_debug.sh divergence-md <needle> [log_file ...]
  mesh_debug.sh select-tokens [log_file ...]
  mesh_debug.sh missing [--sender A] [--receivers B,C] [log_file ...]
  mesh_debug.sh missing-md [--sender A] [--receivers B,C] [log_file ...]
  mesh_debug.sh failed-sending [log_file ...]
  mesh_debug.sh failed-sending-md [log_file ...]
  mesh_debug.sh bundle [--label label] [--full|--lite] [needle] [log_file ...]
  mesh_debug.sh codex-bundle [--type generic|dtn|mtu|queue|retry|relay|dedup|app|verify|reset] [--label label] [--full|--lite] [needle] [log_file ...]
  mesh_debug.sh relay-db-report <serial> [--output-dir dir] [--message-limit N]
  mesh_debug.sh round [count]

Notes:
  - install: if no serial provided, installs to all connected devices.
  - official maps to the clean Official release build/install path.
  - official-debug maps to the Official debug build/install path.
  - reset-db: clears app data for the selected package(s), launches once, and prints/logs fresh mesh IDs. Serial is required.
  - check-ids: launches/checks selected package(s) without clearing data, then prints runtime/prefs mesh IDs.
  - pull-activity: pulls latest device-*_activity_log_live_*.txt per device into repo logs/.
  - metrics: prints BLE mesh quick counters.
  - dtn: prints DTN control-plane, opaque carrier, and retry/failure counters.
  - dtn-md: writes DTN round report into logs/analysis/.
  - classify: suggests likely debug layer from symptom patterns.
  - trace: greps a msgId/hash/token across logs with context.
  - deep-trace: runs structured cross-device trace analysis.
  - deep-trace-md: writes deep trace markdown report into logs/analysis/.
  - divergence: compares stage progress across devices.
  - divergence-md: writes divergence markdown report into logs/analysis/.
  - bundle: writes minimal debug material into logs/analysis/.
    --lite includes only core token analysis: deep trace + divergence (default).
    --full includes metrics/classify/raw trace/deep trace/divergence.
  - codex-bundle: writes Vietnamese Codex prompt + debug material into one ready-to-copy file.
  - relay-db-report: exports peer_mesh.db (+ WAL/SHM) and prefs from one device, then writes markdown/json reports into logs/analysis/.
  - missing: compares sender batch/event list against receiver Received/Rendered/APP extract logs.
  - missing-md: writes missing batch/event report into logs/analysis/.
  - failed-sending: lists event/batch send failures across all batch types.
  - failed-sending-md: writes failed sending report into logs/analysis/.
  - round: prints a strict BLE debug round checklist.
  - release-smoke: builds Official release APK/AAB and checks key release settings.
USAGE
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "Missing command: $1" >&2; exit 1; }
}

require_analysis_cmds() {
  require_cmd rg
  require_cmd sort
  require_cmd awk
  require_cmd head
  require_cmd sed
}

require_python() {
  if [ -x /usr/bin/python3 ]; then
    echo /usr/bin/python3
    return 0
  fi
  if command -v python3 >/dev/null 2>&1; then
    echo python3
    return 0
  fi
  if command -v python >/dev/null 2>&1; then
    echo python
    return 0
  fi
  echo "Missing python3" >&2
  exit 1
}

ensure_deep_trace_script() {
  if [ ! -f "$DEEP_TRACE_PY" ]; then
    echo "Deep trace engine not found: $DEEP_TRACE_PY" >&2
    echo "Expected path: scripts/debug-kit/deep_trace.py" >&2
    exit 1
  fi
}

ensure_divergence_script() {
  if [ ! -f "$DIVERGENCE_PY" ]; then
    echo "Divergence detector not found: $DIVERGENCE_PY" >&2
    echo "Expected path: scripts/debug-kit/divergence_detector.py" >&2
    exit 1
  fi
}

ensure_relay_db_report_script() {
  if [ ! -f "$RELAY_DB_REPORT_PY" ]; then
    echo "Relay DB report script not found: $RELAY_DB_REPORT_PY" >&2
    echo "Expected path: scripts/debug-kit/relay_db_report.py" >&2
    exit 1
  fi
}

flavor_cap() {
  case "$1" in
    emergency) echo "Emergency" ;;
    official-debug|official|official-release) echo "Official" ;;
    *)
      echo "Unsupported flavor: $1" >&2
      exit 1
      ;;
  esac
}

apk_path() {
  case "$1" in
    emergency) echo "$ANDROID_DIR/app/build/outputs/apk/emergency/debug/app-emergency-debug.apk" ;;
    official-debug) echo "$ANDROID_DIR/app/build/outputs/apk/official/debug/app-official-debug.apk" ;;
    official|official-release) echo "$ANDROID_DIR/app/build/outputs/apk/official/release/app-official-release.apk" ;;
    *)
      echo "Unsupported flavor: $1" >&2
      exit 1
      ;;
  esac
}

app_ids_for_mode() {
  case "$1" in
    emergency) echo "app.peermesh.emergency" ;;
    official) echo "app.peermesh" ;;
    auto) echo "app.peermesh.emergency app.peermesh" ;;
    *)
      echo "Unsupported mode: $1" >&2
      exit 1
      ;;
  esac
}

connected_devices() {
  adb devices | awk 'NR>1 && $2=="device" {print $1}'
}

build_one() {
  local flavor="$1"
  local gradle_task=""
  case "$flavor" in
    emergency)
      gradle_task=":app:assembleEmergencyDebug"
      ;;
    official-debug)
      gradle_task=":app:assembleOfficialDebug"
      ;;
    official|official-release)
      gradle_task=":app:assembleOfficialRelease"
      ;;
    *)
      echo "Unsupported flavor: $flavor" >&2
      exit 1
      ;;
  esac
  (
    cd "$ANDROID_DIR"
    ./gradlew "$gradle_task"
  )
  echo "Built: $(apk_path "$flavor")"
}

build_official_release() {
  (
    cd "$ANDROID_DIR"
    ./gradlew ":app:assembleOfficialRelease"
  )
  echo "Built: $ANDROID_DIR/app/build/outputs/apk/official/release/app-official-release.apk"
}

release_smoke() {
  require_cmd rg

  echo "== Build Official release APK and AAB =="
  (
    cd "$ANDROID_DIR"
    ./gradlew ":app:assembleOfficialRelease" ":app:bundleOfficialRelease"
  )

  local apk="$ANDROID_DIR/app/build/outputs/apk/official/release/app-official-release.apk"
  local aab="$ANDROID_DIR/app/build/outputs/bundle/officialRelease/app-official-release.aab"
  local gradle_file="$ANDROID_DIR/app/build.gradle.kts"
  local release_manifest="$ANDROID_DIR/app/src/release/AndroidManifest.xml"

  echo
  echo "== Artifact check =="
  for artifact in "$apk" "$aab"; do
    if [ ! -f "$artifact" ]; then
      echo "Missing artifact: $artifact" >&2
      exit 1
    fi
    echo "OK: $artifact"
  done

  echo
  echo "== Release config check =="
  rg -q 'applicationId = "app\.peermesh"' "$gradle_file" || { echo "Missing production applicationId app.peermesh" >&2; exit 1; }
  rg -q 'minSdk = 26' "$gradle_file" || { echo "Missing minSdk 26" >&2; exit 1; }
  rg -q 'targetSdk = 35' "$gradle_file" || { echo "Missing targetSdk 35" >&2; exit 1; }
  rg -q 'PEER_MESH_PRO_SUBSCRIPTION_ID", "\\"peer_mesh_pro_monthly\\""' "$gradle_file" || { echo "Missing Play subscription id peer_mesh_pro_monthly" >&2; exit 1; }
  rg -q 'ALLOW_PRO_DEV_OVERRIDE", "false"' "$gradle_file" || { echo "Release dev override is not disabled" >&2; exit 1; }
  rg -q 'ENABLE_ACTIVITY_LOG", "false"' "$gradle_file" || { echo "Release ActivityLog is not disabled" >&2; exit 1; }
  rg -q 'ENABLE_DEBUG_TOOLS", "false"' "$gradle_file" || { echo "Release debug tools are not disabled" >&2; exit 1; }
  rg -q 'manifestPlaceholders\["appAllowBackup"\] = false' "$gradle_file" || { echo "Release backup placeholder is not disabled" >&2; exit 1; }
  rg -q 'manifestPlaceholders\["appUsesCleartextTraffic"\] = false' "$gradle_file" || { echo "Release cleartext placeholder is not disabled" >&2; exit 1; }
  rg -q 'android:name="\.TestActivity"' "$release_manifest" || { echo "Release manifest does not remove TestActivity" >&2; exit 1; }
  rg -q 'android:name="\.debug\.ActivityLogCaptureService"' "$release_manifest" || { echo "Release manifest does not remove ActivityLogCaptureService" >&2; exit 1; }
  rg -q 'android:name="\.debug\.ActivityLogBootReceiver"' "$release_manifest" || { echo "Release manifest does not remove ActivityLogBootReceiver" >&2; exit 1; }

  echo "OK: package app.peermesh"
  echo "OK: minSdk 26, targetSdk 35"
  echo "OK: subscription peer_mesh_pro_monthly"
  echo "OK: release debug overrides, activity logs, debug tools disabled"
  echo "OK: release manifest removes TestActivity and ActivityLog components"
}

install_one_device() {
  local flavor="$1"
  local serial="$2"
  local apk
  apk="$(apk_path "$flavor")"
  if [ ! -f "$apk" ]; then
    echo "APK not found: $apk" >&2
    echo "Run: $0 build $flavor" >&2
    exit 1
  fi
  echo "Installing $flavor on $serial"
  adb -s "$serial" install -r "$apk"
}

extract_pref_string() {
  local xml="$1"
  local key="$2"
  printf '%s\n' "$xml" |
    sed -n "s/.*<string name=\"$key\">\([^<]*\)<\/string>.*/\1/p" |
    head -n 1
}

sha256_first_8_bytes_hex() {
  local value="$1"
  printf '%s' "$value" | sha256sum | awk '{print toupper(substr($1, 1, 16))}'
}

read_run_as_file() {
   local serial="$1"
   local pkg="$2"
  local file="$3"
  adb -s "$serial" shell run-as "$pkg" cat "$file" 2>/dev/null | tr -d '\r' || true
}

run_as_available() {
  local serial="$1"
  local pkg="$2"
  adb -s "$serial" shell run-as "$pkg" pwd >/dev/null 2>&1
}

poll_runtime_log() {
  local serial="$1"
  local attempts="${2:-8}"
  local sleep_s="${3:-1}"
  local runtime_log=""
  local i
  for i in $(seq 1 "$attempts"); do
    runtime_log="$(adb -s "$serial" logcat -d -v time 2>/dev/null | tr -d '\r' || true)"
    if printf '%s\n' "$runtime_log" | grep -Eiq 'stableDeviceId=|advertisedDeviceId=|helloPeerId=|DTN hello sent|DTN sync summary|BLE advertised device_id initialized|action=start_transport'; then
      printf '%s\n' "$runtime_log"
      return 0
    fi
    sleep "$sleep_s"
  done
  printf '%s\n' "$runtime_log"
}

first_log_value() {
  local runtime_log="$1"
  local pattern="$2"
  printf '%s\n' "$runtime_log" |
    sed -n -E "$pattern" |
    tail -n 1 |
    tr '[:lower:]' '[:upper:]'
}

print_ids_one_package() {
  local serial="$1"
  local pkg="$2"
  local clear_first="${3:-false}"
  local serial="$1"
  local pkg="$2"

  echo "== $serial / $pkg =="
  adb -s "$serial" shell am force-stop "$pkg" >/dev/null 2>&1 || true
  if [ "$clear_first" = "true" ]; then
    adb -s "$serial" shell pm clear "$pkg"
  fi
  adb -s "$serial" logcat -c || true
  adb -s "$serial" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null

  # Give app startup/identity initialization/BLE service hooks a real chance.
  sleep 2

  local files=""
  local prefs_unreadable="false"
  if run_as_available "$serial" "$pkg"; then
    files="$(adb -s "$serial" shell run-as "$pkg" find . -maxdepth 3 -type f 2>/dev/null | tr -d '\r' | sort || true)"
  else
    prefs_unreadable="true"
  fi

  echo "[files]"
  if [ -n "$files" ]; then
    printf '%s\n' "$files" | sed 's/^/  /'
  elif [ "$prefs_unreadable" = "true" ]; then
    echo "  (run-as unavailable; prefs/db files cannot be inspected)"
  else
    echo "  (none)"
  fi

  local ble_prefs app_prefs local_device_id actor_id dtn_peer_id
  local runtime_log expected_device_id advertised_runtime_id hello_runtime_peer_id sync_route_peer_id ble_runtime_short_id
  local stable_runtime_id advertised_from_start hello_from_start

  if [ "$prefs_unreadable" = "false" ]; then
    ble_prefs="$(read_run_as_file "$serial" "$pkg" "shared_prefs/ble_mesh_identity.xml")"
    app_prefs="$(read_run_as_file "$serial" "$pkg" "shared_prefs/peer_mesh_prefs.xml")"
  else
    ble_prefs=""
    app_prefs=""
  fi

  local_device_id="$(extract_pref_string "$ble_prefs" "local_device_id")"
  actor_id="$(extract_pref_string "$app_prefs" "active_actor_id")"
  if [ -z "$actor_id" ]; then
    actor_id="$(extract_pref_string "$app_prefs" "main_actor_id")"
  fi
  if [ -z "$actor_id" ]; then
    actor_id="$(extract_pref_string "$app_prefs" "device_id")"
  fi
  if [ -n "$actor_id" ]; then
    dtn_peer_id="$(sha256_first_8_bytes_hex "$actor_id")"
  else
    dtn_peer_id=""
  fi
  expected_device_id="$dtn_peer_id"

  runtime_log="$(poll_runtime_log "$serial" 10 1)"

  # Runtime evidence. Parse the explicit identity fields added to service_evaluate first,
  # then fall back to older log labels.
  stable_runtime_id="$(first_log_value "$runtime_log" 's/.*stableDeviceId=([A-Fa-f0-9]{16}).*/\1/Ip')"
  advertised_from_start="$(first_log_value "$runtime_log" 's/.*advertisedDeviceId=([A-Fa-f0-9]{16}).*/\1/Ip')"
  hello_from_start="$(first_log_value "$runtime_log" 's/.*helloPeerId=([A-Fa-f0-9]{16}).*/\1/Ip')"

  advertised_runtime_id="$advertised_from_start"
  if [ -z "$advertised_runtime_id" ]; then
    advertised_runtime_id="$(first_log_value "$runtime_log" 's/.*(advertised|advertise).*device[_ ]?id[=: ]+([A-Fa-f0-9]{16}).*/\2/Ip')"
  fi
  if [ -z "$advertised_runtime_id" ]; then
    advertised_runtime_id="$stable_runtime_id"
  fi

  ble_runtime_short_id="$(first_log_value "$runtime_log" 's/.*BLE advertised device_id initialized id=([A-Fa-f0-9]+).*/\1/Ip')"
  if [ -z "$ble_runtime_short_id" ] && [ -n "$advertised_runtime_id" ]; then
    ble_runtime_short_id="${advertised_runtime_id:0:8}"
  fi

  hello_runtime_peer_id="$hello_from_start"
  if [ -z "$hello_runtime_peer_id" ]; then
    hello_runtime_peer_id="$(first_log_value "$runtime_log" 's/.*DTN hello sent.*peer_id=([A-Za-z0-9_.:-]+).*/\1/p')"
  fi
  sync_route_peer_id="$(first_log_value "$runtime_log" 's/.*DTN sync summary.*direction=route.*peer_id=([A-Za-z0-9_.:-]+).*/\1/p')"

  adb -s "$serial" shell log -t PeerMeshClearCheck \
    "pkg=$pkg prefsUnreadable=$prefs_unreadable localDeviceId=${local_device_id:-unknown} expectedDeviceId=${expected_device_id:-unknown} stableDeviceId=${stable_runtime_id:-unknown} observedAdvertisedDeviceId=${advertised_runtime_id:-unknown} observedHelloPeerId=${hello_runtime_peer_id:-unknown} observedRoutePeerId=${sync_route_peer_id:-unknown} actorId=${actor_id:-unknown}" \
    >/dev/null 2>&1 || true

  echo "[ids]"
  echo "  prefs_unreadable=$prefs_unreadable"
  echo "  localDeviceId_pref=${local_device_id:-unknown}"
  echo "  actorId=${actor_id:-unknown}"
  echo "  expected DTN peerId=${dtn_peer_id:-unknown}"
  echo "  observed stableDeviceId=${stable_runtime_id:-unknown}"
  echo "  observed advertised deviceId=${advertised_runtime_id:-unknown}"
  echo "  observed hello peerId=${hello_runtime_peer_id:-unknown}"
  echo "  observed route peerId=${sync_route_peer_id:-unknown}"
  echo "  BleMeshRuntime short advertised id=${ble_runtime_short_id:-unknown}"
}

reset_db_one_package() {
  print_ids_one_package "$1" "$2" true
}

check_ids_one_package() {
  print_ids_one_package "$1" "$2" false
}

reset_db_devices() {
  local mode="$1"
  shift
  local serials=("$@")
  if [ "${#serials[@]}" -eq 0 ]; then
    echo "Usage: mesh_debug.sh reset-db [official|emergency|auto] <serial ...>" >&2
    echo "Serial is required to avoid accidentally clearing every connected device." >&2
    exit 1
  fi

  local serial pkg
  for serial in "${serials[@]}"; do
    for pkg in $(app_ids_for_mode "$mode"); do
      if adb -s "$serial" shell pm path "$pkg" >/dev/null 2>&1; then
        reset_db_one_package "$serial" "$pkg"
      else
        echo "== $serial / $pkg =="
        echo "  package not installed, skipped"
      fi
    done
  done
}


check_ids_devices() {
  local mode="$1"
  shift
  local serials=("$@")
  if [ "${#serials[@]}" -eq 0 ]; then
    echo "Usage: mesh_debug.sh check-ids [official|emergency|auto] <serial ...>" >&2
    echo "Serial is required to avoid accidentally checking every connected device." >&2
    exit 1
  fi

  local serial pkg
  for serial in "${serials[@]}"; do
    for pkg in $(app_ids_for_mode "$mode"); do
      if adb -s "$serial" shell pm path "$pkg" >/dev/null 2>&1; then
        check_ids_one_package "$serial" "$pkg"
      else
        echo "== $serial / $pkg =="
        echo "  package not installed, skipped"
      fi
    done
  done
}

pull_latest_activity_for_device() {
  local mode="$1"
  local serial="$2"

  mkdir -p "$LOGS_DIR"

  local best_remote=""
  local best_pkg=""
  local best_source=""
  local pkg
  for pkg in $(app_ids_for_mode "$mode"); do
    local remote_candidate
    remote_candidate="$(
      adb -s "$serial" shell "{
        ls -1t /sdcard/Android/data/$pkg/files/activity_logs/device-*_activity_log_live_*.txt 2>/dev/null
        ls -1t /storage/emulated/0/Android/data/$pkg/files/activity_logs/device-*_activity_log_live_*.txt 2>/dev/null
      } | head -n 1" | tr -d '\r'
    )"
    if [ -n "$remote_candidate" ]; then
      best_remote="$remote_candidate"
      best_pkg="$pkg"
      best_source="adb"
      break
    fi

    # Fallback: some devices expose app files via host MTP mount (gvfs).
    local gvfs_dir pattern
    pattern="/run/user/$(id -u)/gvfs/mtp:host=*/Phone/Android/data/$pkg/files/activity_logs"
    for gvfs_dir in $pattern; do
      [ -d "$gvfs_dir" ] || continue
      # Prefer the MTP path that contains the serial (if present in mount name).
      if [[ "$gvfs_dir" == *"$serial"* ]]; then
        remote_candidate="$(ls -1t \
          "$gvfs_dir"/device-*_activity_log_live_*.txt \
          2>/dev/null | head -n 1 || true)"
        if [ -n "$remote_candidate" ]; then
          best_remote="$remote_candidate"
          best_pkg="$pkg"
          best_source="gvfs"
          break
        fi
      fi
    done
    if [ -z "$best_remote" ]; then
      for gvfs_dir in $pattern; do
        [ -d "$gvfs_dir" ] || continue
        remote_candidate="$(ls -1t \
          "$gvfs_dir"/device-*_activity_log_live_*.txt \
          2>/dev/null | head -n 1 || true)"
        if [ -n "$remote_candidate" ]; then
          best_remote="$remote_candidate"
          best_pkg="$pkg"
          best_source="gvfs"
          break
        fi
      done
    fi
    if [ -n "$best_remote" ]; then
      break
    fi
  done

  if [ -z "$best_remote" ]; then
    echo "No device-*_activity_log_live_*.txt found on $serial (mode=$mode)" >&2
    return 1
  fi

  local base_name out_name out_path
  base_name="$(basename "$best_remote")"
  out_name="$base_name"
  out_path="$LOGS_DIR/$out_name"

  if [ "$best_source" = "adb" ]; then
    adb -s "$serial" pull "$best_remote" "$out_path" >/dev/null
    echo "Pulled [$serial][$best_pkg][adb] -> $out_path"
    return 0
  fi

  cp -f "$best_remote" "$out_path"
  echo "Pulled [$serial][$best_pkg][gvfs] -> $out_path"
}

latest_logs() {
  local count="${1:-3}"
  {
    ls -1t "$LOGS_DIR"/device-*_activity_log_live_*.txt 2>/dev/null
  } | head -n "$count"
}

log_epoch_from_path() {
  local path="$1"
  local base ts date_part time_part
  base="$(basename "$path")"
  if [[ "$base" =~ _([0-9]{8})_([0-9]{6})- ]]; then
    date_part="${BASH_REMATCH[1]}"
    time_part="${BASH_REMATCH[2]}"
    ts="${date_part:0:4}-${date_part:4:2}-${date_part:6:2} ${time_part:0:2}:${time_part:2:2}:${time_part:4:2}"
    date -d "$ts" +%s 2>/dev/null
    return
  fi
  echo ""
}

latest_round_logs() {
  local count="${1:-3}"
  local window_secs="${2:-60}"
  local all=()
  mapfile -t all < <(ls -1t "$LOGS_DIR"/device-*_activity_log_live_*.txt 2>/dev/null)
  [ "${#all[@]}" -eq 0 ] && return 0

  local anchor="${all[0]}"
  local anchor_epoch
  anchor_epoch="$(log_epoch_from_path "$anchor")"
  if [ -z "$anchor_epoch" ]; then
    printf '%s\n' "${all[@]}" | head -n "$count"
    return 0
  fi

  local picked=()
  local seen_devices="|"
  local path epoch delta device
  for path in "${all[@]}"; do
    epoch="$(log_epoch_from_path "$path")"
    [ -z "$epoch" ] && continue
    delta=$(( anchor_epoch - epoch ))
    [ "$delta" -lt 0 ] && delta=$(( -delta ))
    [ "$delta" -gt "$window_secs" ] && continue
    device="$(basename "$path" | sed -E 's/^(device-[^_]+)_activity_log_live_.*/\1/')"
    case "$seen_devices" in
      *"|$device|"*) continue ;;
    esac
    picked+=("$path")
    seen_devices="${seen_devices}${device}|"
    [ "${#picked[@]}" -ge "$count" ] && break
  done

  if [ "${#picked[@]}" -eq 0 ]; then
    printf '%s\n' "${all[@]}" | head -n "$count"
    return 0
  fi

  printf '%s\n' "${picked[@]}"
}

ensure_files_or_latest() {
  local -n _out=$1
  shift || true
  _out=("$@")
  if [ "${#_out[@]}" -eq 0 ]; then
    mapfile -t _out < <(latest_round_logs 3 60)
  fi
  if [ "${#_out[@]}" -eq 0 ]; then
    echo "No log files found. Pass files explicitly or put logs in $LOGS_DIR." >&2
    exit 1
  fi
}

count_pattern() {
  rg -c --fixed-strings "$1" "$2" 2>/dev/null || echo 0
}

count_regex() {
  rg -c "$1" "$2" 2>/dev/null || echo 0
}

run_metrics() {
  require_analysis_cmds
  local files=()
  ensure_files_or_latest files "$@"
  local f
  for f in "${files[@]}"; do
    echo "==== $f"
    echo "mtu_negotiation=$(count_pattern "MTU negotiation" "$f")"
    echo "queued_no_write_attempt_timeout=$(count_pattern "queued_no_write_attempt_timeout" "$f")"
    echo "queued_no_progress_timeout=$(count_pattern "queued_no_progress_timeout" "$f")"
    echo "ble_batch_send_failed=$(count_pattern "BLE batch send failed" "$f")"
    echo "ble_batch_send_complete=$(count_pattern "BLE batch send complete" "$f")"
    echo "app_dispatch_failed=$(count_pattern "APP dispatch job failed" "$f")"
    echo "app_dispatch_completed=$(count_pattern "APP dispatch job completed" "$f")"
    echo "app_batch_transport_skipped=$(count_pattern "APP batch transport skipped" "$f")"
    echo "ble_connect_cap_blocked=$(count_pattern "BLE connect cap blocked" "$f")"
    echo "gatt_status_133=$(count_pattern "status=133" "$f")"
    echo "dtn_control=$(count_regex "DTN (hello|inv_have|inv_want|data|ack|sync summary)" "$f")"
    echo "dtn_opaque_retry=$(count_pattern "DTN opaque retry" "$f")"
    echo "identity_canonicalized=$(count_pattern "identity_canonicalized" "$f")"
    echo "queued_target_rewritten=$(count_pattern "queued_target_rewritten" "$f")"
    echo "env_blocked_debounce=$(count_pattern "BLE env blocked debounce" "$f")"
    echo
  done
}

run_dtn_report_text() {
  require_analysis_cmds
  local files=()
  ensure_files_or_latest files "$@"
  local f
  for f in "${files[@]}"; do
    echo "==== $f"
    echo "[control]"
    echo "hello_sent=$(count_pattern "DTN hello sent" "$f")"
    echo "hello_drop=$(count_pattern "DTN hello drop" "$f")"
    echo "inv_have_recv=$(count_pattern "DTN inv_have recv" "$f")"
    echo "inv_have_sent=$(count_pattern "DTN inv_have sent" "$f")"
    echo "inv_want_recv=$(count_pattern "DTN inv_want recv" "$f")"
    echo "inv_want_sent=$(count_pattern "DTN inv_want sent" "$f")"
    echo "data_recv=$(count_pattern "DTN data recv" "$f")"
    echo "data_sent=$(count_pattern "DTN data sent" "$f")"
    echo "ack_recv=$(count_pattern "DTN ack recv" "$f")"
    echo "ack_sent=$(count_pattern "DTN ack sent" "$f")"
    echo "sync_summary=$(count_pattern "DTN sync summary" "$f")"
    echo "manifest_key_recv=$(count_pattern "DTN manifest_key recv" "$f")"
    echo "manifest_key_sent=$(count_pattern "DTN manifest_key sent" "$f")"
    echo "manifest_offer=$(count_pattern "DTN manifest_offer" "$f")"
    echo "manifest_accept=$(count_pattern "DTN manifest_accept" "$f")"
    echo "carrier_data_recv=$(count_pattern "DTN carrier_data recv" "$f")"
    echo "carrier_data_sent=$(count_pattern "DTN carrier_data sent" "$f")"
    echo
    echo "[opaque_carrier]"
    echo "opaque_retry_queued=$(count_pattern "DTN opaque retry queued" "$f")"
    echo "opaque_retry_skipped=$(count_pattern "DTN opaque retry skipped" "$f")"
    echo "opaque_retry_store=$(count_pattern "DTN opaque retry store" "$f")"
    echo "opaque_store_saved=$(count_pattern "reason=store_saved" "$f")"
    echo "opaque_store_missing=$(count_pattern "reason=store_missing" "$f")"
    echo "opaque_capsule_build_failed=$(count_pattern "reason=capsule_build_failed" "$f")"
    echo "opaque_retry_mark_forwarded=$(count_pattern "DTN opaque retry mark_forwarded" "$f")"
    echo "opaque_retry_send_complete=$(count_pattern "DTN opaque retry send_complete" "$f")"
    echo "opaque_retry_delete=$(count_pattern "DTN opaque retry delete" "$f")"
    echo "opaque_retry_failed=$(count_pattern "DTN opaque retry retry_failed" "$f")"
    echo "opaque_no_non_origin_target=$(count_pattern "reason=no_non_origin_target" "$f")"
    echo "opaque_enqueue_failed=$(count_pattern "reason=enqueue_failed" "$f")"
    echo
    echo "[transport]"
    echo "ble_send_complete=$(count_pattern "BLE batch send complete" "$f")"
    echo "ble_send_failed=$(count_pattern "BLE batch send failed" "$f")"
    echo "queued_no_write_attempt_timeout=$(count_pattern "queued_no_write_attempt_timeout" "$f")"
    echo "zero_chunk_failures=$(count_regex "chunks=0/[0-9]+" "$f")"
    echo "write_failed_status_133=$(count_pattern "write_failed_status_133" "$f")"
    echo "broadcast_rejected=$(count_pattern "BROADCAST_REJECT_DECISION" "$f")"
    echo "transport_skipped=$(count_pattern "APP batch transport skipped" "$f")"
    echo "identity_alias_added=$(count_pattern "identity_alias_added" "$f")"
    echo "identity_canonicalized=$(count_pattern "identity_canonicalized" "$f")"
    echo "queued_target_rewritten=$(count_pattern "queued_target_rewritten" "$f")"
    echo "stale_identity_suppressed=$(count_pattern "stale_identity_suppressed" "$f")"
    echo "env_blocked_debounce_start=$(count_pattern "BLE env blocked debounce start" "$f")"
    echo "env_blocked_debounce_cancelled=$(count_pattern "BLE env blocked debounce cancelled" "$f")"
    echo "env_blocked_debounce_stop=$(count_pattern "BLE env blocked debounce stop" "$f")"
    echo
    echo "[recent_dtn_timeline]"
    rg -n "DTN hello|DTN inv_have|DTN inv_want|DTN data|DTN ack|DTN manifest_key|DTN manifest_offer|DTN manifest_accept|DTN carrier_data|DTN sync summary|DTN opaque retry|BLE batch send failed|BLE batch send complete|BROADCAST_REJECT_DECISION|APP batch transport skipped|identity_alias_added|identity_canonicalized|queued_target_rewritten|stale_identity_suppressed|BLE env blocked debounce" "$f" | tail -n 50 || echo "(no DTN/BLE timeline)"
    echo
  done
}

run_dtn_batch_transport_md() {
  local py
  py="$(require_python)"
  "$py" - "$@" <<'PY_DTN_BATCH_TRANSPORT'
import os
import re
import sys
from collections import OrderedDict, defaultdict

BATCH_RE = re.compile(r"batch_id=([A-Za-z0-9_-]+)")
DEVICE_PATH_RE = re.compile(r"(?:^|[/\\])device-([A-Za-z0-9_-]+)_activity_log")
DEVICE_RE = re.compile(r"(?:device|device_id)=([A-Za-z0-9_.:-]+)")
EVENT_IDS_RE = re.compile(r"event_ids=([^ \"\t]+)")
EVENT_ID_RE = re.compile(r"eventId=([A-Za-z0-9_-]+)")
REASON_RE = re.compile(r"\breason=([^ \"\t]+)")
SKIP_RE = re.compile(r"\bskip_reason=([^ \"\t]+)")
TARGETS_RE = re.compile(r"\btargets=([^ \"\t]+)")
CHUNKS_RE = re.compile(r"\bchunks=([^ \"\t]+)")
BYTES_RE = re.compile(r"\bbytes=([^ \"\t]+)")
PAYLOAD_BYTES_RE = re.compile(r"\bpayload_bytes=([0-9]+)")
TEXT_RE = re.compile(r"text=([^\"]*)")
TEST_TEXT_RE = re.compile(r"(?:\bTest\s+\d+:\s+|\b[A-Za-z0-9 _-]+:\s+)([A-Za-z])\s+#(\d+)\b", re.IGNORECASE)
TIME_RE = re.compile(r"^(\d{2}):(\d{2}):(\d{2})\.(\d{3})")
ADDR_RE = re.compile(r"\baddr=([A-Fa-f0-9:]+)")
TARGET_WITH_ID_RE = re.compile(r"([A-Fa-f0-9:]{17})(?:\(id=([A-Za-z0-9_.:-]+)\))?")
SESSION_PEERS_RE = re.compile(r"\bsession_peers=([^ \"\t]+)")
NEARBY_TOTAL_RE = re.compile(r"\bnearby_total=([0-9]+)")
SESSION_NEARBY_RE = re.compile(r"\bsession_nearby=([0-9]+)")
NON_SESSION_NEARBY_RE = re.compile(r"\bnon_session_nearby=([0-9]+)")
SCAN_CALLBACKS_RE = re.compile(r"\bcallbacks=([0-9]+)")
ACTION_RE = re.compile(r"\baction=([^ \"\t]+)")
TRIGGER_RE = re.compile(r"\btrigger=([^ \"\t]+)")
EVENT_RE = re.compile(r"\bevent=([^ \"\t]+)")
STATE_RE = re.compile(r"\bstate=([0-9]+)")
BT_ENABLED_RE = re.compile(r"\bbt_enabled=(true|false)")
LOCATION_ENABLED_RE = re.compile(r"\blocation_enabled=(true|false)")
CAN_START_RE = re.compile(r"\bcan_start=(true|false)")
SERVICE_RUNNING_RE = re.compile(r"\bservice_running=(true|false)")
MESH_RUNNING_RE = re.compile(r"\bmesh_running=(true|false)")
ENABLED_RE = re.compile(r"\benabled=(true|false)")
GRACE_MS_RE = re.compile(r"\bgraceMs=([0-9]+)")
AGE_MS_RE = re.compile(r"\bage_ms=([0-9]+)")
STARTUP_AGE_MS_RE = re.compile(r"\bstartup_age_ms=([0-9]+)")
LAST_PROGRESS_AGE_MS_RE = re.compile(r"\blast_progress_age_ms=([0-9]+)")
NEARBY_INLINE_RE = re.compile(r"\bnearby=([0-9]+)")
ACTIVE_WORK_RE = re.compile(r"\bactiveWork=(true|false)")

STAGE_LABELS = OrderedDict([
    ("created", "APP batch bypass"),
    ("emit", "APP batch emit"),
    ("transport_queued", "APP batch transport queued"),
    ("transport_skipped", "APP batch transport skipped"),
    ("send_progress", "BLE batch send progress"),
    ("send_complete", "BLE batch send complete"),
    ("send_failed", "BLE batch send failed"),
    ("dispatch_completed", "APP dispatch job completed"),
    ("dispatch_failed", "APP dispatch job failed"),
    ("extract", "APP batch extract"),
    ("broadcast_rejected", "BROADCAST_REJECT_DECISION"),
    ("opaque_store", "DTN opaque retry store"),
    ("opaque_queued", "DTN opaque retry queued"),
    ("opaque_skipped", "DTN opaque retry skipped"),
    ("opaque_mark_forwarded", "DTN opaque retry mark_forwarded"),
    ("opaque_send_complete", "DTN opaque retry send_complete"),
    ("opaque_delete", "DTN opaque retry delete"),
    ("opaque_retry_failed", "DTN opaque retry retry_failed"),
])

TERMINAL_STAGES = {
    "send_complete",
    "send_failed",
    "dispatch_completed",
    "dispatch_failed",
    "transport_skipped",
    "broadcast_rejected",
    "opaque_send_complete",
    "opaque_retry_failed",
    "opaque_delete",
}

def device_name(path, index):
    m = DEVICE_PATH_RE.search(path)
    if m:
        return m.group(1)
    return chr(ord("A") + index)

def ts_value(line):
    m = TIME_RE.search(line)
    if not m:
        return None
    hh, mm, ss, ms = [int(x) for x in m.groups()]
    return ((hh * 60 + mm) * 60 + ss) * 1000 + ms

def ts_text(line):
    return line.split(" ", 1)[0] if line else ""

def stage_for(line):
    if "APP batch bypass" in line and "mode=new" in line:
        return "created"
    for key, marker in STAGE_LABELS.items():
        if marker in line:
            return key
    return None

def addr_value(line):
    m = ADDR_RE.search(line)
    return m.group(1) if m else ""

def event_ids(line):
    ids = []
    m = EVENT_ID_RE.search(line)
    if m:
        ids.append(m.group(1))
    m = EVENT_IDS_RE.search(line)
    if m:
        token = m.group(1)
        if "…" not in token:
            ids.extend(x for x in token.split(",") if x)
    return ids

def source_value(line):
    source_match = re.search(r"\bsource=([^ \"\t]+)", line)
    return source_match.group(1) if source_match else ""

def kind_value(line):
    kind_match = re.search(r"\bkind=([^ \"\t]+)", line)
    return kind_match.group(1) if kind_match else ""

def accepted_value(line):
    accepted_match = re.search(r"\baccepted=([^ \"\t]+)", line)
    return accepted_match.group(1) if accepted_match else ""

def relay_eligible_value(line):
    relay_match = re.search(r"\brelay_eligible=([^ \"\t]+)", line)
    return relay_match.group(1) if relay_match else ""

def total_bytes_value(line):
    payload_match = PAYLOAD_BYTES_RE.search(line)
    if payload_match:
        return int(payload_match.group(1))
    bytes_match = BYTES_RE.search(line)
    if not bytes_match:
        return None
    token = bytes_match.group(1)
    if "/" in token:
        token = token.split("/")[-1]
    return int(token) if token.isdigit() else None

def md(value):
    value = str(value or "-")
    return value.replace("|", "\\|").replace("\n", " ")

def short(value, limit=96):
    value = str(value or "")
    if len(value) <= limit:
        return value
    return value[: limit - 1] + "..."

def parse(paths):
    batches = OrderedDict()
    opaque_windows = []
    batch_byte_hints = []

    def rec_for(batch_id):
        return batches.setdefault(batch_id, {
            "batch_id": batch_id,
            "origin": "",
            "origin_source": "",
            "first_ts": None,
            "first_time": "",
            "last_ts": None,
            "last_time": "",
            "first_line": "",
            "events": OrderedDict(),
            "texts": OrderedDict(),
            "stage_counts": defaultdict(int),
            "devices_seen": OrderedDict(),
            "targets_queued": OrderedDict(),
            "targets_complete": OrderedDict(),
            "targets_failed": OrderedDict(),
            "reasons": OrderedDict(),
            "timeline": [],
        })

    for index, path in enumerate(paths):
        dev_name = device_name(path, index)
        with open(path, encoding="utf-8", errors="replace") as fh:
            current_opaque = None
            for line_no, raw in enumerate(fh, 1):
                line = raw.rstrip("\n")
                ts = ts_value(line)
                ttxt = ts_text(line)
                if "GATT deliver envelope" in line:
                    delivered_bytes = total_bytes_value(line)
                    current_opaque = {
                        "ts": ts,
                        "time": ttxt,
                        "device": dev_name,
                        "line": line_no,
                        "bytes": delivered_bytes,
                        "source_addr": re.search(r"\baddr=([^ \"\t]+)", line).group(1) if re.search(r"\baddr=([^ \"\t]+)", line) else "",
                        "decisions": [],
                    }
                    opaque_windows.append(current_opaque)
                    continue
                if "BLE relay decision" in line:
                    decision = {
                        "ts": ts,
                        "time": ttxt,
                        "line": line_no,
                        "kind": kind_value(line),
                        "accepted": accepted_value(line),
                        "relay_eligible": relay_eligible_value(line),
                        "reason": REASON_RE.search(line).group(1) if REASON_RE.search(line) else "",
                    }
                    if current_opaque is None or current_opaque["device"] != dev_name or (
                        ts is not None and current_opaque.get("ts") is not None and ts - current_opaque["ts"] > 5_000
                    ):
                        current_opaque = {
                            "ts": ts,
                            "time": ttxt,
                            "device": dev_name,
                            "line": line_no,
                            "bytes": None,
                            "source_addr": "",
                            "decisions": [],
                        }
                        opaque_windows.append(current_opaque)
                    current_opaque["decisions"].append(decision)
                    continue

                mb = BATCH_RE.search(line)
                if not mb:
                    continue
                stage = stage_for(line)
                if not stage:
                    continue

                batch_id = mb.group(1)
                rec = rec_for(batch_id)
                total_bytes = total_bytes_value(line)
                if total_bytes is not None and stage in ("emit", "send_progress", "send_complete", "send_failed", "opaque_send_complete", "opaque_retry_failed"):
                    batch_byte_hints.append({
                        "batch_id": batch_id,
                        "device": dev_name,
                        "stage": stage,
                        "ts": ts,
                        "time": ttxt,
                        "line": line_no,
                        "bytes": total_bytes,
                    })

                if rec["first_ts"] is None or (ts is not None and ts < rec["first_ts"]):
                    rec["first_ts"] = ts
                    rec["first_time"] = ttxt
                    rec["first_line"] = f"{dev_name}:{line_no}"
                if ts is not None and (rec["last_ts"] is None or ts >= rec["last_ts"]):
                    rec["last_ts"] = ts
                    rec["last_time"] = ttxt

                rec["stage_counts"][stage] += 1
                rec["devices_seen"][dev_name] = 1

                if stage == "created":
                    rec["origin"] = rec["origin"] or dev_name
                    source = source_value(line)
                    if source:
                        rec["origin_source"] = rec["origin_source"] or source
                elif not rec["origin"] and stage == "emit":
                    rec["origin"] = dev_name

                for event_id in event_ids(line):
                    rec["events"].setdefault(event_id, 1)

                text_match = TEXT_RE.search(line)
                if text_match:
                    rec["texts"].setdefault(short(text_match.group(1), 120), 1)

                target_match = TARGETS_RE.search(line)
                if target_match and target_match.group(1) not in ("auto", "none"):
                    rec["targets_queued"].setdefault(target_match.group(1), 1)

                device_match = DEVICE_RE.search(line)
                target = device_match.group(1) if device_match else ""
                if target:
                    if stage in ("send_complete", "opaque_send_complete", "dispatch_completed"):
                        rec["targets_complete"].setdefault(target, 1)
                    elif stage in ("send_failed", "opaque_retry_failed", "dispatch_failed"):
                        rec["targets_failed"].setdefault(target, 1)

                reason = REASON_RE.search(line)
                skip = SKIP_RE.search(line)
                if reason:
                    rec["reasons"].setdefault(reason.group(1), 1)
                if skip:
                    rec["reasons"].setdefault("skip:" + skip.group(1), 1)

                chunks = CHUNKS_RE.search(line)
                bytes_ = BYTES_RE.search(line)
                detail = []
                if target:
                    detail.append(f"target={target}")
                if reason:
                    detail.append(f"reason={reason.group(1)}")
                if skip:
                    detail.append(f"skip={skip.group(1)}")
                if chunks:
                    detail.append(f"chunks={chunks.group(1)}")
                if bytes_:
                    detail.append(f"bytes={bytes_.group(1)}")
                rec["timeline"].append({
                    "ts": ts if ts is not None else 10**12,
                    "time": ttxt,
                    "device": dev_name,
                    "line": line_no,
                    "stage": stage,
                    "detail": " ".join(detail),
                })
    return batches, opaque_windows, batch_byte_hints

def parse_phase1(paths):
    delivery = OrderedDict()
    counters = OrderedDict()
    ack_stats = OrderedDict()

    def counter_for(device):
        return counters.setdefault(device, {
            "opaque_store": 0,
            "opaque_queued": 0,
            "send_complete": 0,
            "send_failed": 0,
            "queued_timeout": 0,
            "zero_chunk_failures": 0,
            "dispatch_completed": 0,
            "dispatch_failed": 0,
            "transport_skipped": 0,
            "broadcast_rejected": 0,
            "drop_retry_exhausted": 0,
            "first_peer_delivered": 0,
            "write_failed_status_1": 0,
            "write_failed_status_133": 0,
            "identity_alias_added": 0,
            "identity_canonicalized": 0,
            "queued_target_rewritten": 0,
            "stale_identity_suppressed": 0,
            "last_hop_unknown_drop": 0,
            "opaque_no_non_origin_target": 0,
        })

    def delivery_for(event_id):
        return delivery.setdefault(event_id, {
            "event_id": event_id,
            "sender_label": "",
            "sender_device": "",
            "message_no": None,
            "text": "",
            "batch_ids": OrderedDict(),
            "sent": None,
            "received": OrderedDict(),
            "rendered": OrderedDict(),
            "is_ack": False,
        })

    def ack_for(device):
        return ack_stats.setdefault(device, {
            "ack_sent": 0,
            "ack_window_armed": 0,
            "ack_flush_start": 0,
            "ack_applied_delivered": 0,
            "ack_applied_seen": 0,
            "ack_send_locked": 0,
        })

    for index, path in enumerate(paths):
        dev_name = device_name(path, index)
        ctr = counter_for(dev_name)
        ack = ack_for(dev_name)
        with open(path, encoding="utf-8", errors="replace") as fh:
            for line_no, raw in enumerate(fh, 1):
                line = raw.rstrip("\n")
                ttxt = ts_text(line)
                tsv = ts_value(line)

                if "DTN opaque retry store" in line:
                    ctr["opaque_store"] += 1
                if "DTN opaque retry queued" in line:
                    ctr["opaque_queued"] += 1
                if "BLE batch send complete" in line:
                    ctr["send_complete"] += 1
                if "BLE batch send failed" in line:
                    ctr["send_failed"] += 1
                if "queued_no_write_attempt_timeout" in line:
                    ctr["queued_timeout"] += 1
                if re.search(r"\bchunks=0/[0-9]+", line):
                    ctr["zero_chunk_failures"] += 1
                if "APP dispatch job completed" in line:
                    ctr["dispatch_completed"] += 1
                if "APP dispatch job failed" in line:
                    ctr["dispatch_failed"] += 1
                if "APP batch transport skipped" in line:
                    ctr["transport_skipped"] += 1
                if "BROADCAST_REJECT_DECISION" in line:
                    ctr["broadcast_rejected"] += 1
                if "decision=drop_retry_exhausted" in line:
                    ctr["drop_retry_exhausted"] += 1
                if "reason=first_peer_delivered" in line:
                    ctr["first_peer_delivered"] += 1
                if "write_failed_status_1" in line:
                    ctr["write_failed_status_1"] += 1
                if "write_failed_status_133" in line:
                    ctr["write_failed_status_133"] += 1
                if "identity_alias_added" in line:
                    ctr["identity_alias_added"] += 1
                if "identity_canonicalized" in line:
                    ctr["identity_canonicalized"] += 1
                if "queued_target_rewritten" in line:
                    ctr["queued_target_rewritten"] += 1
                if "stale_identity_suppressed" in line:
                    ctr["stale_identity_suppressed"] += 1
                if "reason=last_hop_unknown_drop" in line:
                    ctr["last_hop_unknown_drop"] += 1
                if "reason=no_non_origin_target" in line:
                    ctr["opaque_no_non_origin_target"] += 1

                if "SYS ACK send locked" in line:
                    ack["ack_send_locked"] += 1
                if "Sent message at" in line and "type=SYS.ACK" in line:
                    ack["ack_sent"] += 1
                if "APP batch window armed" in line and "type=ACK" in line:
                    ack["ack_window_armed"] += 1
                if "APP batch flush start" in line and "type=ACK" in line:
                    ack["ack_flush_start"] += 1
                if "ACK applied delivered" in line:
                    ack["ack_applied_delivered"] += 1
                if "ACK applied seen" in line:
                    ack["ack_applied_seen"] += 1

                ids = event_ids(line)
                if not ids:
                    continue
                text_match = TEXT_RE.search(line)
                text = text_match.group(1).strip() if text_match else ""
                test_match = TEST_TEXT_RE.search(text)
                batch_match = BATCH_RE.search(line)
                batch_id = batch_match.group(1) if batch_match else ""
                is_ack_line = "type=SYS.ACK" in line

                for eid in ids:
                    rec = delivery_for(eid)
                    if is_ack_line:
                        rec["is_ack"] = True
                    if batch_id:
                        rec["batch_ids"][batch_id] = 1
                    if text and not rec["text"]:
                        rec["text"] = text
                    if test_match:
                        rec["sender_label"] = rec["sender_label"] or test_match.group(1).upper()
                        rec["message_no"] = rec["message_no"] or int(test_match.group(2))

                    if "Sent message at" in line:
                        rec["sender_device"] = dev_name
                        if rec["sent"] is None:
                            rec["sent"] = {"time": ttxt, "ts": tsv, "line": line_no, "device": dev_name}
                    elif "Received message at" in line:
                        rec["received"][dev_name] = {"time": ttxt, "ts": tsv, "line": line_no}
                    elif "Rendered message at" in line:
                        rec["rendered"][dev_name] = {"time": ttxt, "ts": tsv, "line": line_no}

    return delivery, counters, ack_stats

def parse_dtn_control(paths):
    summaries = OrderedDict()
    timeline = []

    def summary_for(device):
        return summaries.setdefault(device, {
            "hello_sent": 0,
            "hello_recv": 0,
            "inv_have_sent": 0,
            "inv_have_recv": 0,
            "inv_want_sent": 0,
            "inv_want_recv": 0,
            "data_sent": 0,
            "data_recv": 0,
            "ack_sent": 0,
            "ack_recv": 0,
            "manifest_key_sent": 0,
            "manifest_key_recv": 0,
            "manifest_offer_sent": 0,
            "manifest_offer_recv": 0,
            "manifest_accept_sent": 0,
            "manifest_accept_recv": 0,
            "carrier_data_sent": 0,
            "carrier_data_recv": 0,
            "sync_summary": 0,
            "route_responses": 0,
            "route_sent": 0,
            "route_with_payload": 0,
        })

    def add_timeline(ts, time, device, line_no, label, detail):
        timeline.append({
            "ts": ts if ts is not None else 10**12,
            "time": time,
            "device": device,
            "line": line_no,
            "label": label,
            "detail": detail,
        })

    def marker_detail(line):
        parts = []
        for pattern, name in [
            (r"\btype=([^ \"\t]+)", "type"),
            (r"\bdirection=([^ \"\t]+)", "direction"),
            (r"\bpeer_id=([^ \"\t]+)", "peer"),
            (r"\bsession_id=([^ \"\t]+)", "session"),
            (r"\bids=([0-9]+)", "ids"),
            (r"\bdata=([0-9]+)", "data"),
            (r"\bresponses=([0-9]+)", "responses"),
            (r"\bsent=([0-9]+)", "sent"),
            (r"\breason=([^ \"\t]+)", "reason"),
        ]:
            m = re.search(pattern, line)
            if m:
                parts.append(f"{name}={m.group(1)}")
        return " ".join(parts)

    for index, path in enumerate(paths):
        dev_name = device_name(path, index)
        summary = summary_for(dev_name)
        with open(path, encoding="utf-8", errors="replace") as fh:
            for line_no, raw in enumerate(fh, 1):
                line = raw.rstrip("\n")
                if "DTN " not in line:
                    continue
                ts = ts_value(line)
                ttxt = ts_text(line)
                detail = marker_detail(line)
                for packet_type in (
                    "hello",
                    "inv_have",
                    "inv_want",
                    "data",
                    "ack",
                    "manifest_key",
                    "manifest_offer",
                    "manifest_accept",
                    "carrier_data",
                ):
                    if f"DTN {packet_type} sent" in line:
                        summary[f"{packet_type}_sent"] += 1
                        add_timeline(ts, ttxt, dev_name, line_no, f"{packet_type}_sent", detail)
                    elif f"DTN {packet_type} recv" in line:
                        summary[f"{packet_type}_recv"] += 1
                        add_timeline(ts, ttxt, dev_name, line_no, f"{packet_type}_recv", detail)
                if "DTN control handle result" in line:
                    responses = re.search(r"\bresponses=([0-9]+)", line)
                    sent = re.search(r"\bsent=([0-9]+)", line)
                    handled = re.search(r"\bhandled=([^ \"\t]+)", line)
                    resp = int(responses.group(1)) if responses else 0
                    snd = int(sent.group(1)) if sent else 0
                    summary["route_responses"] += resp
                    summary["route_sent"] += snd
                    if (handled and handled.group(1) == "true") or resp > 0 or snd > 0:
                        summary["route_with_payload"] += 1
                    add_timeline(ts, ttxt, dev_name, line_no, "control_handle_result", detail)
                if "DTN sync summary" in line:
                    summary["sync_summary"] += 1
                    responses = re.search(r"\bresponses=([0-9]+)", line)
                    sent = re.search(r"\bsent=([0-9]+)", line)
                    data_count = re.search(r"\bdata=([0-9]+)", line)
                    id_count = re.search(r"\bids=([0-9]+)", line)
                    resp = int(responses.group(1)) if responses else 0
                    snd = int(sent.group(1)) if sent else 0
                    dct = int(data_count.group(1)) if data_count else 0
                    ict = int(id_count.group(1)) if id_count else 0
                    if resp > 0 or snd > 0 or dct > 0 or ict > 0:
                        summary["route_with_payload"] += 1
                    add_timeline(ts, ttxt, dev_name, line_no, "sync_summary", detail)
    return summaries, sorted(timeline, key=lambda x: (x["ts"], x["device"], x["line"]))

def parse_dtn_payload_ingest(paths):
    ingest = OrderedDict()

    def rows_for(device):
        return ingest.setdefault(device, [])

    for index, path in enumerate(paths):
        dev_name = device_name(path, index)
        rows = rows_for(dev_name)
        with open(path, encoding="utf-8", errors="replace") as fh:
            for line_no, raw in enumerate(fh, 1):
                line = raw.rstrip("\n")
                if "Received message at" not in line or "source=dtn" not in line:
                    continue
                ttxt = ts_text(line)
                text_match = re.search(r"\btext=(.+?)\"?$", line)
                event_match = re.search(r"\bevent_id=([^ \"\t]+)", line)
                rows.append({
                    "time": ttxt,
                    "line": line_no,
                    "event_id": event_match.group(1) if event_match else "",
                    "text": text_match.group(1) if text_match else "",
                })
    return ingest

def dtn_control_verdict(summary):
    carrier_flow = (
        summary["manifest_offer_sent"] + summary["manifest_offer_recv"] +
        summary["manifest_accept_sent"] + summary["manifest_accept_recv"] +
        summary["carrier_data_sent"] + summary["carrier_data_recv"]
    )
    classic_flow = (
        summary["inv_have_sent"] + summary["inv_have_recv"] +
        summary["inv_want_sent"] + summary["inv_want_recv"] +
        summary["data_sent"] + summary["data_recv"] +
        summary["ack_sent"] + summary["ack_recv"]
    )
    if classic_flow > 0 or carrier_flow > 0:
        return "BEYOND_HELLO"
    if summary["route_with_payload"] > 0:
        return "HELLO_PLUS_ROUTE_ONLY"
    if summary["hello_sent"] > 0 or summary["hello_recv"] > 0:
        return "HELLO_ONLY"
    return "NO_DTN_SIGNAL"

def render_dtn_control_plane(summaries, timeline):
    print("## DTN Control-Plane Evidence")
    print()
    print("Goal: prove physical-device DTN activity beyond `hello`. `BEYOND_HELLO` requires at least one inventory/data/ack exchange (`inv_have`, `inv_want`, `data`, `ack`) or carrier-manifest exchange (`manifest_offer`, `manifest_accept`, `carrier_data`).")
    print()
    if not summaries:
        print("(no DTN control-plane markers found)")
        print()
        return
    print("### DTN Control Summary")
    print()
    print("| Device | hello s/r | inv_have s/r | inv_want s/r | data s/r | ack s/r | manifest_key s/r | manifest_offer s/r | manifest_accept s/r | carrier_data s/r | sync summaries | route resp/sent | Verdict |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|")
    for device, s in summaries.items():
        print(
            f"| {md(device)} | {s['hello_sent']}/{s['hello_recv']} | "
            f"{s['inv_have_sent']}/{s['inv_have_recv']} | "
            f"{s['inv_want_sent']}/{s['inv_want_recv']} | "
            f"{s['data_sent']}/{s['data_recv']} | "
            f"{s['ack_sent']}/{s['ack_recv']} | "
            f"{s['manifest_key_sent']}/{s['manifest_key_recv']} | "
            f"{s['manifest_offer_sent']}/{s['manifest_offer_recv']} | "
            f"{s['manifest_accept_sent']}/{s['manifest_accept_recv']} | "
            f"{s['carrier_data_sent']}/{s['carrier_data_recv']} | "
            f"{s['sync_summary']} | "
            f"{s['route_responses']}/{s['route_sent']} | "
            f"{md(dtn_control_verdict(s))} |"
        )
    print()
    round_verdict = "NO_DTN_SIGNAL"
    if any(dtn_control_verdict(s) == "BEYOND_HELLO" for s in summaries.values()):
        round_verdict = "BEYOND_HELLO"
    elif any(dtn_control_verdict(s) == "HELLO_PLUS_ROUTE_ONLY" for s in summaries.values()):
        round_verdict = "HELLO_PLUS_ROUTE_ONLY"
    elif any(dtn_control_verdict(s) == "HELLO_ONLY" for s in summaries.values()):
        round_verdict = "HELLO_ONLY"
    print(f"Round verdict: `{round_verdict}`")
    print()
    print("### DTN Control Timeline")
    print()
    if not timeline:
        print("(no DTN control timeline)")
        print()
        return
    print("| Time | Device | Line | Marker | Detail |")
    print("|---|---|---:|---|---|")
    for item in timeline[:220]:
        print(
            f"| {md(item['time'])} | {md(item['device'])} | {item['line']} | {md(item['label'])} | {md(short(item['detail'], 180))} |"
        )
    if len(timeline) > 220:
        print(f"| ... | ... | ... | ... | truncated {len(timeline) - 220} additional DTN control rows |")
    print()

def connectivity_marker(line):
    markers = [
        ("service_lifecycle", "BLE service lifecycle"),
        ("service_start_command", "BLE service start command"),
        ("service_start_request", "BLE service start request"),
        ("service_stop_mesh", "BLE service stop mesh"),
        ("service_evaluate", "BLE service evaluate"),
        ("advertise_started", "BLE advertise started"),
        ("advertise_failed", "BLE advertise failed"),
        ("advertise_retry", "BLE advertise retry"),
        ("scan_callback", "BLE scan callback"),
        ("nearby", "NEARBY_COUNT_BREAKDOWN"),
        ("candidate_pool", "CANDIDATE_POOL_SNAPSHOT"),
        ("queued_targets", "Queued BLE targets"),
        ("gatt_connected", "GATT connected"),
        ("gatt_services", "GATT services discovered"),
        ("gatt_disconnect", "GATT disconnect reason"),
        ("gatt_failed", "GATT connect failed"),
        ("gatt_backoff", "GATT connect backoff"),
        ("gatt_timeout", "GATT connect timeout"),
        ("gatt_skipped", "GATT connect skipped"),
        ("connect_cap_blocked", "BLE connect cap blocked"),
        ("connect_cap_snapshot", "BLE connect cap snapshot"),
        ("dispatch_pull", "DISPATCH pull attempt"),
        ("mtu", "MTU negotiation"),
    ]
    for key, marker in markers:
        if marker in line:
            return key
    return None

def parse_connectivity(paths):
    summaries = OrderedDict()
    timeline = []

    def summary_for(device):
        return summaries.setdefault(device, {
            "first_scan": "",
            "last_scan": "",
            "scan_count": 0,
            "max_nearby": 0,
            "max_session_nearby": 0,
            "max_non_session_nearby": 0,
            "advertise_started": 0,
            "advertise_failed": 0,
            "service_start": 0,
            "service_stop": 0,
            "candidate_peers": OrderedDict(),
            "queued_peers": OrderedDict(),
            "connected": OrderedDict(),
            "services": OrderedDict(),
            "disconnects": defaultdict(int),
            "failures": defaultdict(int),
            "backoffs": defaultdict(int),
            "connect_cap_blocked": 0,
        })

    def add_timeline(ts, time, device, line_no, event, peer="", addr="", detail=""):
        timeline.append({
            "ts": ts if ts is not None else 10**12,
            "time": time,
            "device": device,
            "line": line_no,
            "event": event,
            "peer": peer,
            "addr": addr,
            "detail": detail,
        })

    for index, path in enumerate(paths):
        dev_name = device_name(path, index)
        summary = summary_for(dev_name)
        last_nearby_tuple = None
        with open(path, encoding="utf-8", errors="replace") as fh:
            for line_no, raw in enumerate(fh, 1):
                line = raw.rstrip("\n")
                marker = connectivity_marker(line)
                if marker is None:
                    continue
                ts = ts_value(line)
                ttxt = ts_text(line)
                addr = addr_value(line)
                device_match = DEVICE_RE.search(line)
                peer = device_match.group(1) if device_match else ""
                reason = REASON_RE.search(line)
                detail_parts = []
                if reason:
                    detail_parts.append(f"reason={reason.group(1)}")

                if marker == "scan_callback":
                    summary["scan_count"] += 1
                    summary["first_scan"] = summary["first_scan"] or ttxt
                    summary["last_scan"] = ttxt
                    callbacks = SCAN_CALLBACKS_RE.search(line)
                    if callbacks:
                        detail_parts.append(f"callbacks={callbacks.group(1)}")
                    continue

                if marker == "nearby":
                    total = int(NEARBY_TOTAL_RE.search(line).group(1)) if NEARBY_TOTAL_RE.search(line) else 0
                    session = int(SESSION_NEARBY_RE.search(line).group(1)) if SESSION_NEARBY_RE.search(line) else 0
                    non_session = int(NON_SESSION_NEARBY_RE.search(line).group(1)) if NON_SESSION_NEARBY_RE.search(line) else 0
                    summary["max_nearby"] = max(summary["max_nearby"], total)
                    summary["max_session_nearby"] = max(summary["max_session_nearby"], session)
                    summary["max_non_session_nearby"] = max(summary["max_non_session_nearby"], non_session)
                    current = (total, session, non_session)
                    if current != last_nearby_tuple:
                        add_timeline(
                            ts, ttxt, dev_name, line_no, "nearby_count", "",
                            "",
                            f"nearby_total={total} session={session} non_session={non_session}",
                        )
                        last_nearby_tuple = current
                    continue

                if marker in ("service_start_command", "service_start_request"):
                    summary["service_start"] += 1
                elif marker == "service_stop_mesh":
                    summary["service_stop"] += 1
                elif marker == "advertise_started":
                    summary["advertise_started"] += 1
                elif marker == "advertise_failed":
                    summary["advertise_failed"] += 1
                elif marker == "connect_cap_blocked":
                    summary["connect_cap_blocked"] += 1

                if marker == "candidate_pool":
                    peers = SESSION_PEERS_RE.search(line)
                    peer_token = peers.group(1) if peers else ""
                    if peer_token and peer_token != "none":
                        for item in peer_token.split(","):
                            if item:
                                summary["candidate_peers"].setdefault(item, 1)
                    add_timeline(ts, ttxt, dev_name, line_no, "candidate_pool", peer_token, "", short(line.split('msg="', 1)[-1].rstrip('"'), 160))
                    continue

                if marker == "queued_targets":
                    raw_targets = line.split('msg="', 1)[-1].rstrip('"') if 'msg="' in line else line
                    pairs = TARGET_WITH_ID_RE.findall(raw_targets)
                    if pairs:
                        for target_addr, target_id in pairs:
                            if target_id:
                                summary["queued_peers"].setdefault(target_id, 1)
                            add_timeline(ts, ttxt, dev_name, line_no, "queued_target", target_id, target_addr, "")
                    else:
                        add_timeline(ts, ttxt, dev_name, line_no, "queued_target", "", "", short(raw_targets, 160))
                    continue

                if marker == "gatt_connected":
                    summary["connected"].setdefault(peer or addr, 1)
                    add_timeline(ts, ttxt, dev_name, line_no, "gatt_connected", peer, addr, "")
                    continue
                if marker == "gatt_services":
                    summary["services"].setdefault(peer or addr, 1)
                    add_timeline(ts, ttxt, dev_name, line_no, "gatt_services", peer, addr, "")
                    continue
                if marker == "gatt_disconnect":
                    summary["disconnects"][peer or addr or "unknown"] += 1
                    add_timeline(ts, ttxt, dev_name, line_no, "gatt_disconnect", peer, addr, " ".join(detail_parts) or short(line, 160))
                    continue
                if marker in ("gatt_failed", "gatt_timeout"):
                    summary["failures"][peer or addr or "unknown"] += 1
                    add_timeline(ts, ttxt, dev_name, line_no, marker, peer, addr, " ".join(detail_parts) or short(line, 160))
                    continue
                if marker == "gatt_backoff":
                    summary["backoffs"][peer or addr or "unknown"] += 1
                    delay = re.search(r"\bdelay_ms=([0-9]+)", line)
                    if delay:
                        detail_parts.append(f"delay_ms={delay.group(1)}")
                    add_timeline(ts, ttxt, dev_name, line_no, "gatt_backoff", peer, addr, " ".join(detail_parts))
                    continue
                if marker == "dispatch_pull":
                    add_timeline(ts, ttxt, dev_name, line_no, "dispatch_pull", peer, addr, " ".join(detail_parts))
                    continue
                if marker == "mtu":
                    add_timeline(ts, ttxt, dev_name, line_no, "mtu", peer, addr, short(line.split('msg="', 1)[-1].rstrip('"') if 'msg="' in line else line, 160))
                    continue

                add_timeline(ts, ttxt, dev_name, line_no, marker, peer, addr, " ".join(detail_parts) or short(line.split('msg="', 1)[-1].rstrip('"') if 'msg="' in line else line, 160))

    return summaries, sorted(timeline, key=lambda x: (x["ts"], x["device"], x["line"]))

def render_connectivity(summaries, timeline):
    print("## BLE Peer Availability and Connections")
    print()
    print("Scope: inferred from ActivityLog markers: service start/stop, advertise, scan callbacks, nearby counts, candidate pools, queued targets, GATT connect/service/disconnect/fail/backoff, and dispatch pulls.")
    print()
    if not summaries:
        print("(no BLE connectivity markers found)")
        print()
        return
    print("| Device | Scan window | Scan callbacks | Max nearby | Candidate peers | Queued peer ids | Connected addr/ids | Services discovered | Disconnect/fail/backoff | Advertise/service |")
    print("|---|---|---:|---|---|---|---|---|---|---|")
    for device, s in summaries.items():
        disconnect_total = sum(s["disconnects"].values())
        failure_total = sum(s["failures"].values())
        backoff_total = sum(s["backoffs"].values())
        scan_window = "-"
        if s["first_scan"] or s["last_scan"]:
            scan_window = f"{s['first_scan'] or '?'} -> {s['last_scan'] or '?'}"
        print(
            "| "
            + " | ".join([
                md(device),
                md(scan_window),
                md(s["scan_count"]),
                md(f"{s['max_nearby']} (session={s['max_session_nearby']}, non_session={s['max_non_session_nearby']})"),
                md(short(",".join(s["candidate_peers"].keys()), 160)),
                md(short(",".join(s["queued_peers"].keys()), 160)),
                md(short(",".join(s["connected"].keys()), 160)),
                md(short(",".join(s["services"].keys()), 160)),
                md(f"disconnect={disconnect_total} fail={failure_total} backoff={backoff_total} cap_blocked={s['connect_cap_blocked']}"),
                md(f"adv_ok={s['advertise_started']} adv_fail={s['advertise_failed']} service_start={s['service_start']} service_stop={s['service_stop']}"),
            ])
            + " |"
        )
    print()
    print("### BLE Connectivity Timeline")
    print()
    if not timeline:
        print("(no timeline)")
        print()
        return
    print("| Time | Device | Line | Event | Peer/device_id | Addr | Detail |")
    print("|---|---|---:|---|---|---|---|")
    for item in timeline[:300]:
        print(
            f"| {md(item['time'])} | {md(item['device'])} | {item['line']} | "
            f"{md(item['event'])} | {md(item['peer'])} | {md(item['addr'])} | {md(short(item['detail'], 180))} |"
        )
    if len(timeline) > 300:
        print(f"| ... | ... | ... | ... | ... | ... | truncated {len(timeline) - 300} additional connectivity rows |")
    print()

def mesh_state_label(kind, value):
    if value is None:
        return "?"
    if kind == "bt":
        return {
            "true": "on",
            "false": "off",
            "10": "off",
            "11": "turning_on",
            "12": "on",
            "13": "turning_off",
        }.get(value, value)
    if kind == "location":
        return "on" if value == "true" else ("off" if value == "false" else value)
    if kind in ("mesh", "service", "can_start"):
        return "running" if value == "true" and kind in ("mesh", "service") else (
            "stopped" if value == "false" and kind in ("mesh", "service") else (
                "yes" if value == "true" and kind == "can_start" else (
                    "no" if value == "false" and kind == "can_start" else value
                )
            )
        )
    return value

def parse_bool_token(regex, line):
    m = regex.search(line)
    return m.group(1) if m else None

def parse_mesh_state(paths):
    summaries = OrderedDict()
    timeline = []

    def summary_for(device):
        return summaries.setdefault(device, {
            "bt_on": 0,
            "bt_off": 0,
            "location_on": 0,
            "location_off": 0,
            "service_start": 0,
            "service_stop": 0,
            "mesh_started": 0,
            "mesh_stopped": 0,
            "hard_restart": 0,
            "delay_restart": 0,
            "delay_stop": 0,
            "startup_stalled": 0,
            "transport_stalled": 0,
            "last_bt": "?",
            "last_location": "?",
            "last_service": "?",
            "last_mesh": "?",
            "last_can_start": "?",
        })

    current = defaultdict(lambda: {
        "bt": "?",
        "location": "?",
        "service": "?",
        "mesh": "?",
        "can_start": "?",
    })

    def apply_state(device, **kwargs):
        state = current[device]
        for key, value in kwargs.items():
            if value is not None:
                state[key] = value
        return state

    def add_timeline(ts, time, device, line_no, event, detail="", state_overrides=None):
        summary = summary_for(device)
        state = current[device].copy()
        if state_overrides:
            for key, value in state_overrides.items():
                if value is not None:
                    state[key] = value
        apply_state(device, **state)
        summary["last_bt"] = mesh_state_label("bt", state["bt"])
        summary["last_location"] = mesh_state_label("location", state["location"])
        summary["last_service"] = mesh_state_label("service", state["service"])
        summary["last_mesh"] = mesh_state_label("mesh", state["mesh"])
        summary["last_can_start"] = mesh_state_label("can_start", state["can_start"])
        timeline.append({
            "ts": ts if ts is not None else 10**12,
            "time": time,
            "device": device,
            "line": line_no,
            "event": event,
            "bt": summary["last_bt"],
            "location": summary["last_location"],
            "service": summary["last_service"],
            "mesh": summary["last_mesh"],
            "can_start": summary["last_can_start"],
            "detail": detail,
        })

    for index, path in enumerate(paths):
        dev_name = device_name(path, index)
        summary = summary_for(dev_name)
        last_eval_signature = None
        with open(path, encoding="utf-8", errors="replace") as fh:
            for line_no, raw in enumerate(fh, 1):
                line = raw.rstrip("\n")
                ts = ts_value(line)
                ttxt = ts_text(line)
                msg = line.split('msg="', 1)[-1].rstrip('"') if 'msg="' in line else line

                if "BLE bluetooth state" in line:
                    enabled = parse_bool_token(ENABLED_RE, line)
                    if enabled == "true":
                        summary["bt_on"] += 1
                    elif enabled == "false":
                        summary["bt_off"] += 1
                    add_timeline(ts, ttxt, dev_name, line_no, "bluetooth_state", f"enabled={enabled or '?'}", {"bt": enabled})
                    continue

                if "BLE location state" in line:
                    enabled = parse_bool_token(ENABLED_RE, line)
                    if enabled == "true":
                        summary["location_on"] += 1
                    elif enabled == "false":
                        summary["location_off"] += 1
                    add_timeline(ts, ttxt, dev_name, line_no, "location_state", f"enabled={enabled or '?'}", {"location": enabled})
                    continue

                if "BLE service evaluate state" in line:
                    trigger = TRIGGER_RE.search(line)
                    bt_enabled = parse_bool_token(BT_ENABLED_RE, line)
                    location_enabled = parse_bool_token(LOCATION_ENABLED_RE, line)
                    can_start = parse_bool_token(CAN_START_RE, line)
                    service_running = parse_bool_token(SERVICE_RUNNING_RE, line)
                    mesh_running = parse_bool_token(MESH_RUNNING_RE, line)
                    signature = (trigger.group(1) if trigger else "", bt_enabled, location_enabled, can_start, service_running, mesh_running)
                    if signature != last_eval_signature:
                        detail_parts = []
                        if trigger:
                            detail_parts.append(f"trigger={trigger.group(1)}")
                            if trigger.group(1).startswith("bluetooth_state_"):
                                bt_state = trigger.group(1).rsplit("_", 1)[-1]
                                if bt_state in {"10", "11", "12", "13"}:
                                    apply_state(dev_name, bt=bt_state)
                                    if bt_state == "12":
                                        summary["bt_on"] += 1
                                    elif bt_state == "10":
                                        summary["bt_off"] += 1
                            elif trigger.group(1).startswith("location_"):
                                detail_parts.append("location_trigger=true")
                        if bt_enabled is not None:
                            detail_parts.append(f"bt={mesh_state_label('bt', bt_enabled)}")
                        if location_enabled is not None:
                            detail_parts.append(f"loc={mesh_state_label('location', location_enabled)}")
                        if can_start is not None:
                            detail_parts.append(f"can_start={mesh_state_label('can_start', can_start)}")
                        if service_running is not None:
                            detail_parts.append(f"service={mesh_state_label('service', service_running)}")
                        if mesh_running is not None:
                            detail_parts.append(f"mesh={mesh_state_label('mesh', mesh_running)}")
                        add_timeline(
                            ts, ttxt, dev_name, line_no, "service_evaluate_state", " ".join(detail_parts),
                            {
                                "bt": bt_enabled,
                                "location": location_enabled,
                                "can_start": can_start,
                                "service": service_running,
                                "mesh": mesh_running,
                            },
                        )
                        last_eval_signature = signature
                    continue

                if "BLE service evaluate decision" in line:
                    trigger = TRIGGER_RE.search(line)
                    action = ACTION_RE.search(line)
                    reason = REASON_RE.search(line)
                    age_ms = AGE_MS_RE.search(line)
                    detail_parts = []
                    if trigger:
                        detail_parts.append(f"trigger={trigger.group(1)}")
                        if trigger.group(1).startswith("bluetooth_state_"):
                            bt_state = trigger.group(1).rsplit("_", 1)[-1]
                            if bt_state in {"10", "11", "12", "13"}:
                                apply_state(dev_name, bt=bt_state)
                    if action:
                        detail_parts.append(f"action={action.group(1)}")
                        if action.group(1) == "start_transport":
                            summary["service_start"] += 1
                        elif action.group(1) == "stop_service":
                            summary["service_stop"] += 1
                        elif action.group(1) == "hard_restart":
                            summary["hard_restart"] += 1
                        elif action.group(1) == "delay_restart":
                            summary["delay_restart"] += 1
                        elif action.group(1) == "delay_stop":
                            summary["delay_stop"] += 1
                    if reason:
                        detail_parts.append(f"reason={reason.group(1)}")
                    if age_ms:
                        detail_parts.append(f"age_ms={age_ms.group(1)}")
                    add_timeline(ts, ttxt, dev_name, line_no, "service_evaluate_decision", " ".join(detail_parts))
                    continue

                if "BLE service lifecycle" in line:
                    event = EVENT_RE.search(line)
                    detail = f"event={event.group(1)}" if event else short(msg, 160)
                    overrides = {}
                    if event and event.group(1) == "onCreate":
                        overrides["service"] = "true"
                        summary["service_start"] += 1
                    elif event and event.group(1) == "onDestroy":
                        overrides["service"] = "false"
                        overrides["mesh"] = "false"
                        summary["service_stop"] += 1
                    add_timeline(ts, ttxt, dev_name, line_no, "service_lifecycle", detail, overrides)
                    continue

                if "BLE service stop mesh" in line:
                    summary["mesh_stopped"] += 1
                    summary["service_stop"] += 1
                    event = EVENT_RE.search(line)
                    add_timeline(ts, ttxt, dev_name, line_no, "service_stop_mesh", f"event={event.group(1) if event else 'stop'}", {"mesh": "false", "service": "false"})
                    continue

                if "Mesh transport start" in line:
                    stage = re.search(r"\bstage=([^ \"\t]+)", line)
                    reason = REASON_RE.search(line)
                    detail_parts = []
                    overrides = {}
                    if stage:
                        detail_parts.append(f"stage={stage.group(1)}")
                        if stage.group(1) == "started":
                            overrides["mesh"] = "true"
                            overrides["service"] = "true"
                            summary["mesh_started"] += 1
                        elif stage.group(1) == "skipped":
                            overrides["mesh"] = "false"
                    if reason:
                        detail_parts.append(f"reason={reason.group(1)}")
                    add_timeline(ts, ttxt, dev_name, line_no, "mesh_transport_start", " ".join(detail_parts), overrides)
                    continue

                if "BLE advertise started" in line:
                    add_timeline(ts, ttxt, dev_name, line_no, "advertise_started", short(msg, 160))
                    continue
                if "BLE advertise failed" in line:
                    add_timeline(ts, ttxt, dev_name, line_no, "advertise_failed", short(msg, 160))
                    continue
                if "BLE advertise retry" in line:
                    add_timeline(ts, ttxt, dev_name, line_no, "advertise_retry", short(msg, 160))
                    continue

                if "BLE mesh startup stalled" in line:
                    summary["startup_stalled"] += 1
                    age = STARTUP_AGE_MS_RE.search(line)
                    detail = f"startup_age_ms={age.group(1)}" if age else short(msg, 160)
                    add_timeline(ts, ttxt, dev_name, line_no, "mesh_startup_stalled", detail)
                    continue

                if "BLE mesh transport stalled" in line:
                    summary["transport_stalled"] += 1
                    age = LAST_PROGRESS_AGE_MS_RE.search(line)
                    nearby = NEARBY_INLINE_RE.search(line)
                    action = ACTION_RE.search(line)
                    detail_parts = []
                    if nearby:
                        detail_parts.append(f"nearby={nearby.group(1)}")
                    if age:
                        detail_parts.append(f"last_progress_age_ms={age.group(1)}")
                    if action:
                        detail_parts.append(f"action={action.group(1)}")
                    add_timeline(ts, ttxt, dev_name, line_no, "mesh_transport_stalled", " ".join(detail_parts) or short(msg, 160))
                    continue

                if "BLE env blocked debounce start" in line or "BLE env blocked debounce cancelled" in line or "BLE env blocked debounce stop" in line:
                    if " start" in line:
                        event = "env_blocked_debounce_start"
                    elif " cancelled" in line:
                        event = "env_blocked_debounce_cancelled"
                    else:
                        event = "env_blocked_debounce_stop"
                    bt_enabled = re.search(r"\bbluetoothEnabled=(true|false)", line)
                    location_enabled = re.search(r"\blocationEnabled=(true|false)", line)
                    grace = GRACE_MS_RE.search(line)
                    active_work = ACTIVE_WORK_RE.search(line)
                    trigger = TRIGGER_RE.search(line)
                    reason = REASON_RE.search(line)
                    detail_parts = []
                    if trigger:
                        detail_parts.append(f"trigger={trigger.group(1)}")
                    if grace:
                        detail_parts.append(f"graceMs={grace.group(1)}")
                    if reason:
                        detail_parts.append(f"reason={reason.group(1)}")
                    if active_work:
                        detail_parts.append(f"activeWork={active_work.group(1)}")
                    add_timeline(
                        ts, ttxt, dev_name, line_no, event, " ".join(detail_parts),
                        {
                            "bt": bt_enabled.group(1) if bt_enabled else None,
                            "location": location_enabled.group(1) if location_enabled else None,
                        },
                    )
                    continue

    return summaries, sorted(timeline, key=lambda x: (x["ts"], x["device"], x["line"]))

def render_mesh_state_timeline(summaries, timeline):
    print("## BLE Mesh State Timeline")
    print()
    print("Scope: BT/Location receiver events, service evaluate state/decision, mesh start/stop, advertise state, and stall/debounce markers. `BT`, `Location`, `Mesh`, and `Service` are best-effort snapshots from the latest known state per device.")
    print()
    if not summaries:
        print("(no BLE mesh state markers found)")
        print()
        return
    print("### BLE Mesh State Summary")
    print()
    print("| Device | BT churn | Service cycles | Mesh restarts | Timeline verdict |")
    print("|---|---:|---:|---:|---|")
    for device, s in summaries.items():
        bt_churn = s["bt_on"] + s["bt_off"]
        service_cycles = s["service_start"] + s["service_stop"]
        mesh_restarts = s["hard_restart"] + s["delay_restart"] + s["transport_stalled"]
        if s["bt_off"] > 0 or s["location_off"] > 0 or s["delay_stop"] > 0:
            verdict = "BT_OR_ENV_CHURN"
        elif s["service_stop"] > 0:
            verdict = "SERVICE_RESTARTED"
        elif s["transport_stalled"] > 0 or s["hard_restart"] > 0 or s["delay_restart"] > 0:
            verdict = "TRANSPORT_RESTARTED"
        else:
            verdict = "STEADY_STATE"
        print(f"| {md(device)} | {bt_churn} | {service_cycles} | {mesh_restarts} | {md(verdict)} |")
    print()
    print("| Device | BT last | Location last | Can start | Mesh last | Service last | BT on/off | Location on/off | Mesh start/stop | Service start/stop | Stalls | Restart actions |")
    print("|---|---|---|---|---|---|---:|---:|---:|---:|---|---|")
    for device, s in summaries.items():
        stalls = f"startup={s['startup_stalled']} transport={s['transport_stalled']}"
        restarts = f"hard={s['hard_restart']} delay_restart={s['delay_restart']} delay_stop={s['delay_stop']}"
        print(
            f"| {md(device)} | {md(s['last_bt'])} | {md(s['last_location'])} | {md(s['last_can_start'])} | "
            f"{md(s['last_mesh'])} | {md(s['last_service'])} | {s['bt_on']}/{s['bt_off']} | "
            f"{s['location_on']}/{s['location_off']} | {s['mesh_started']}/{s['mesh_stopped']} | "
            f"{s['service_start']}/{s['service_stop']} | "
            f"{md(stalls)} | "
            f"{md(restarts)} |"
        )
    print()
    print("### BLE Mesh State Events")
    print()
    if not timeline:
        print("(no state timeline)")
        print()
        return
    print("| Time | Device | Line | Event | BT | Location | Can start | Mesh | Service | Detail |")
    print("|---|---|---:|---|---|---|---|---|---|---|")
    for item in timeline[:400]:
        print(
            f"| {md(item['time'])} | {md(item['device'])} | {item['line']} | {md(item['event'])} | "
            f"{md(item['bt'])} | {md(item['location'])} | {md(item['can_start'])} | "
            f"{md(item['mesh'])} | {md(item['service'])} | {md(short(item['detail'], 180))} |"
        )
    if len(timeline) > 400:
        print(f"| ... | ... | ... | ... | ... | ... | ... | ... | ... | truncated {len(timeline) - 400} additional state rows |")
    print()

def status_for(rec):
    complete = bool(rec["targets_complete"]) or rec["stage_counts"].get("send_complete", 0) or rec["stage_counts"].get("opaque_send_complete", 0)
    failed = bool(rec["targets_failed"]) or rec["stage_counts"].get("send_failed", 0) or rec["stage_counts"].get("opaque_retry_failed", 0)
    if complete and failed:
        return "PARTIAL"
    if complete:
        return "SUCCESS"
    if failed:
        return "FAIL"
    if rec["stage_counts"].get("transport_queued", 0) or rec["stage_counts"].get("opaque_queued", 0):
        return "QUEUED"
    return "SEEN"

def duration_s(rec):
    if rec["first_ts"] is None or rec["last_ts"] is None:
        return "-"
    delta = rec["last_ts"] - rec["first_ts"]
    if delta < 0:
        return "-"
    return f"{delta / 1000:.3f}"

def nearby_batch_candidates(window, batch_byte_hints, limit=6):
    wbytes = window.get("bytes")
    if wbytes is None:
        return []
    candidates = []
    for hint in batch_byte_hints:
        if hint.get("bytes") != wbytes:
            continue
        delta = None
        if window.get("ts") is not None and hint.get("ts") is not None:
            delta = abs(window["ts"] - hint["ts"]) / 1000.0
        same_device_penalty = 1 if hint.get("device") == window.get("device") else 0
        stage_weight = {
            "emit": 0,
            "send_complete": 1,
            "send_failed": 2,
            "opaque_send_complete": 3,
            "opaque_retry_failed": 4,
            "send_progress": 5,
        }.get(hint.get("stage"), 9)
        candidates.append((same_device_penalty, 999999.0 if delta is None else delta, stage_weight, hint))
    candidates.sort(key=lambda x: (x[0], x[1], x[2], x[3]["time"], x[3]["device"], x[3]["batch_id"]))
    return candidates[:limit]

def opaque_status(window):
    reasons = [d.get("reason", "") for d in window.get("decisions", [])]
    kinds = [d.get("kind", "") for d in window.get("decisions", [])]
    if "opaque_forward_scheduled" in reasons:
        return "FORWARD_SCHEDULED"
    if "opaque_forward_skipped" in reasons:
        return "FORWARD_SKIPPED"
    if "opaque_candidate" in reasons or "opaque" in kinds:
        return "OPAQUE_CANDIDATE"
    if window.get("decisions"):
        return "DECIDED"
    return "DELIVER_ONLY"

def latency_ms(sent_entry, target_entry):
    if not sent_entry or not target_entry:
        return None
    sent_ts = sent_entry.get("ts")
    target_ts = target_entry.get("ts")
    if sent_ts is None or target_ts is None:
        return None
    delta = target_ts - sent_ts
    return delta if delta >= 0 else None

def latency_text(ms):
    if ms is None:
        return "-"
    return f"{ms} ms"

def latency_summary_text(values):
    if not values:
        return "-"
    return f"{min(values)}/{percentile_median(values)}/{max(values)} ms"

def timeline_target(item):
    detail = item.get("detail", "")
    m = re.search(r"\btarget=([A-Za-z0-9_.:-]+)", detail)
    return m.group(1) if m else ""

def first_received_entry(rec, receiver_device=None):
    entries = []
    for device, entry in rec.get("received", {}).items():
        if device == rec.get("sender_device"):
            continue
        if receiver_device and device != receiver_device:
            continue
        ts = entry.get("ts")
        if ts is None:
            continue
        entries.append((ts, device, entry))
    if not entries:
        return None, None
    entries.sort(key=lambda x: (x[0], x[1]))
    _, device, entry = entries[0]
    return device, entry

def build_batch_index(ordered):
    return {rec["batch_id"]: rec for rec in ordered}

def relay_timing_for_event(rec, batch_index):
    sent = rec.get("sent")
    if not sent:
        return {
            "batch_id": "",
            "relay_device": "",
            "source_to_relay_ms": None,
            "relay_hold_ms": None,
            "total_ms": None,
        }

    batch = None
    batch_id = ""
    for candidate in rec.get("batch_ids", {}).keys():
        if candidate in batch_index:
            batch = batch_index[candidate]
            batch_id = candidate
            break

    receiver_device, first_recv = first_received_entry(rec)
    total_ms = latency_ms(sent, first_recv)

    result = {
        "batch_id": batch_id,
        "relay_device": "",
        "source_to_relay_ms": None,
        "relay_hold_ms": None,
        "total_ms": total_ms,
    }
    if not batch or verdict_for(batch) != "RELAYED" or not receiver_device:
        return result

    sender_device = rec.get("sender_device")
    relay_device = ""
    relay_devices = [
        device for device in batch.get("devices_seen", {}).keys()
        if device not in (sender_device, receiver_device)
    ]
    if relay_devices:
        relay_device = relay_devices[0]
    else:
        return result

    source_to_relay_complete_ts = None
    relay_send_complete_ts = None
    sender_fallback_ts = None
    relay_fallback_ts = None

    for item in sorted(batch.get("timeline", []), key=lambda x: (x["ts"], x["line"])):
        item_ts = item.get("ts")
        if item_ts is None or item_ts >= 10**12:
            continue
        item_device = item.get("device")
        item_stage = item.get("stage")
        item_target = timeline_target(item)

        if item_device == sender_device and item_stage == "send_complete":
            if sender_fallback_ts is None:
                sender_fallback_ts = item_ts
            if relay_device and item_target == relay_device and source_to_relay_complete_ts is None:
                source_to_relay_complete_ts = item_ts

        if item_device == relay_device and item_stage in ("send_complete", "opaque_send_complete"):
            if relay_fallback_ts is None:
                relay_fallback_ts = item_ts
            if item_target == receiver_device and relay_send_complete_ts is None:
                relay_send_complete_ts = item_ts

    source_to_relay_complete_ts = source_to_relay_complete_ts or sender_fallback_ts
    relay_send_complete_ts = relay_send_complete_ts or relay_fallback_ts

    result["relay_device"] = relay_device
    if source_to_relay_complete_ts is not None:
        delta = source_to_relay_complete_ts - sent.get("ts")
        result["source_to_relay_ms"] = delta if delta >= 0 else None
    if source_to_relay_complete_ts is not None and relay_send_complete_ts is not None:
        delta = relay_send_complete_ts - source_to_relay_complete_ts
        result["relay_hold_ms"] = delta if delta >= 0 else None
    return result

def percentile_median(values):
    if not values:
        return None
    values = sorted(values)
    n = len(values)
    mid = n // 2
    if n % 2 == 1:
        return values[mid]
    return int(round((values[mid - 1] + values[mid]) / 2.0))

def render_delivery(delivery, batch_index):
    print("## Delivery")
    print()
    print("Delivery is evaluated by `Received message at`. `Rendered message at` is shown as a UI-foreground signal only and may undercount when the chat screen is not active. `SYS.ACK` control traffic is excluded from delivery scoring.")
    print()
    user_events = [
        rec for rec in delivery.values()
        if rec.get("sent") and rec.get("text") and not rec.get("is_ack")
    ]
    if not user_events:
        print("(no user-visible message events found)")
        print()
        return

    by_sender = OrderedDict()
    by_direction = OrderedDict()
    def delivery_sort_key(rec):
        sent = rec.get("sent") or {}
        return (
            rec.get("sender_label") or rec.get("sender_device") or "?",
            rec.get("message_no") or 10**9,
            sent.get("ts") if sent.get("ts") is not None else 10**12,
            rec["event_id"],
        )

    for rec in sorted(user_events, key=delivery_sort_key):
        by_sender.setdefault(rec.get("sender_label") or rec.get("sender_device") or "?", []).append(rec)
        rendered_receivers = [d for d in rec["rendered"].keys() if d != rec["sender_device"]]
        received_only = [d for d in rec["received"].keys() if d != rec["sender_device"] and d not in rendered_receivers]
        all_receivers = rendered_receivers + received_only
        if all_receivers:
            for receiver in all_receivers:
                by_direction.setdefault((rec["sender_device"], receiver), []).append(rec)
        else:
            by_direction.setdefault((rec["sender_device"], "?"), []).append(rec)

    print("| Direction | Received / Sent | Missing | Rendered | Receive without render | First receive latency (min/median/max) | First render latency (min/median/max) | Source -> relay complete (min/median/max) | Relay hold until target (min/median/max) | End-to-end source -> target (min/median/max) |")
    print("|---|---:|---:|---:|---:|---|---|---|---|---|")
    for (sender_device, receiver_device), rows in by_direction.items():
        received_count = 0
        missing = 0
        rendered_count = 0
        received_without_render = 0
        receive_latencies = []
        render_latencies = []
        source_to_relay_latencies = []
        relay_hold_latencies = []
        total_latencies = []
        for rec in rows:
            receiver_rendered = receiver_device in rec["rendered"]
            receiver_received = receiver_device in rec["received"]
            recv_latency = latency_ms(rec.get("sent"), rec["received"].get(receiver_device))
            render_latency = latency_ms(rec.get("sent"), rec["rendered"].get(receiver_device))
            relay_timing = relay_timing_for_event(rec, batch_index)
            if recv_latency is not None:
                receive_latencies.append(recv_latency)
            if render_latency is not None:
                render_latencies.append(render_latency)
            if relay_timing["source_to_relay_ms"] is not None:
                source_to_relay_latencies.append(relay_timing["source_to_relay_ms"])
            if relay_timing["relay_hold_ms"] is not None:
                relay_hold_latencies.append(relay_timing["relay_hold_ms"])
            if relay_timing["total_ms"] is not None:
                total_latencies.append(relay_timing["total_ms"])
            if receiver_received:
                received_count += 1
                if receiver_rendered:
                    rendered_count += 1
                else:
                    received_without_render += 1
            else:
                missing += 1
        direction = f"{sender_device} -> {receiver_device}"
        recv_summary = latency_summary_text(receive_latencies)
        render_summary = latency_summary_text(render_latencies)
        source_to_relay_summary = latency_summary_text(source_to_relay_latencies)
        relay_hold_summary = latency_summary_text(relay_hold_latencies)
        total_summary = latency_summary_text(total_latencies)
        print(
            f"| {md(direction)} | {received_count}/{len(rows)} | {missing} | {rendered_count} | {received_without_render} | "
            f"{md(recv_summary)} | {md(render_summary)} | {md(source_to_relay_summary)} | {md(relay_hold_summary)} | {md(total_summary)} |"
        )
    print()

    print("### Latency")
    print()
    print("`Source -> relay complete` = from sender `Sent message at` to sender `BLE batch send complete` toward relay. `Relay hold until target` = from that sender->relay complete until relay `BLE batch send complete` toward target. `End-to-end` = sender `Sent message at` to target first `Received message at`.")
    print()
    print("| # | Event | Text | Sender device | Sent | Received by | Rendered by | First received Δ | First rendered Δ | Relay device | Source -> relay complete | Relay hold until target | End-to-end source -> target | Delivery status | UI status | Batch |")
    print("|---:|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for idx, rec in enumerate(sorted(user_events, key=delivery_sort_key), 1):
        received = ",".join(rec["received"].keys())
        rendered = ",".join(rec["rendered"].keys())
        delivery_status = "RECEIVED" if received else "MISSING"
        ui_status = "RENDERED" if rendered else ("NOT_RENDERED" if received else "MISSING")
        relay_timing = relay_timing_for_event(rec, batch_index)
        first_received = min(
            (
                value
                for value in (latency_ms(rec.get("sent"), entry) for entry in rec["received"].values())
                if value is not None
            ),
            default=None,
        )
        first_rendered = min(
            (
                value
                for value in (latency_ms(rec.get("sent"), entry) for entry in rec["rendered"].values())
                if value is not None
            ),
            default=None,
        )
        print(
            "| " + " | ".join([
                str(idx),
                f"`{md(rec['event_id'])}`",
                md(short(rec["text"], 120)),
                md(rec["sender_device"]),
                md(rec["sent"]["time"]),
                md(received),
                md(rendered),
                md(latency_text(first_received)),
                md(latency_text(first_rendered)),
                md(relay_timing["relay_device"]),
                md(latency_text(relay_timing["source_to_relay_ms"])),
                md(latency_text(relay_timing["relay_hold_ms"])),
                md(latency_text(relay_timing["total_ms"])),
                md(delivery_status),
                md(ui_status),
                md(short(",".join(rec["batch_ids"].keys()), 120)),
            ]) + " |"
        )
    print()

def render_ack_activity(ack_stats):
    print("## ACK Activity")
    print()
    print("Scope: `SYS.ACK` control traffic only. This section tracks ACK generation, batching, and local apply markers separately from user-visible delivery.")
    print()
    if not ack_stats:
        print("(no ACK markers found)")
        print()
        return
    print("| Device | ACK sent | Window armed | Flush start | Applied delivered | Applied seen | Send locked | Verdict |")
    print("|---|---:|---:|---:|---:|---:|---:|---|")
    for device, stats in ack_stats.items():
        if stats["ack_send_locked"] > 0:
            verdict = "LOCKED"
        elif stats["ack_sent"] == 0 and stats["ack_applied_delivered"] == 0 and stats["ack_applied_seen"] == 0:
            verdict = "NO_ACK_ACTIVITY"
        elif stats["ack_applied_delivered"] > 0 or stats["ack_applied_seen"] > 0:
            verdict = "APPLIED"
        else:
            verdict = "SENT_ONLY"
        print(
            f"| {md(device)} | {stats['ack_sent']} | {stats['ack_window_armed']} | {stats['ack_flush_start']} | "
            f"{stats['ack_applied_delivered']} | {stats['ack_applied_seen']} | {stats['ack_send_locked']} | {md(verdict)} |"
        )
    print()

def render_dtn_payload_ingest(ingest):
    print("## DTN Payload Ingest")
    print()
    print("Scope: `Received message at source=dtn`. This captures user-visible payloads delivered via DTN even when the generic delivery table cannot correlate a full sent/received pair.")
    print()
    if not any(rows for rows in ingest.values()):
        print("(no DTN payload ingest rows found)")
        print()
        return
    print("| Device | DTN received rows | Sample texts |")
    print("|---|---:|---|")
    for device, rows in ingest.items():
        samples = [row["text"] for row in rows if row["text"]]
        sample_text = ", ".join(samples[:3])
        print(f"| {md(device)} | {len(rows)} | {md(short(sample_text, 180))} |")
    print()
    print("| Time | Device | Line | Event | Text |")
    print("|---|---|---:|---|---|")
    flat = []
    for device, rows in ingest.items():
        for row in rows:
            flat.append((row["time"], device, row))
    for _, device, row in flat[:60]:
        print(
            f"| {md(row['time'])} | {md(device)} | {row['line']} | "
            f"{md(row['event_id'])} | {md(short(row['text'], 180))} |"
        )
    if len(flat) > 60:
        print(f"| ... | ... | ... | ... | truncated {len(flat) - 60} additional DTN payload rows |")
    print()

def render_carrier_and_noise(ordered, counters):
    print("## Carrier")
    print()
    print("| Device | Opaque store | Opaque queued | Send complete | Send failed | Queue timeout |")
    print("|---|---:|---:|---:|---:|---:|")
    for device, ctr in counters.items():
        print(
            f"| {md(device)} | {ctr['opaque_store']} | {ctr['opaque_queued']} | "
            f"{ctr['send_complete']} | {ctr['send_failed']} | {ctr['queued_timeout']} |"
        )
    print()

    print("## Source-side Noise")
    print()
    print("Definition: source-origin batch completed to one peer, but the source later still recorded additional failed send attempts for other peers.")
    print()
    rows = []
    for rec in ordered:
        if rec.get("origin_source") != "mesh":
            continue
        origin = rec.get("origin")
        if not origin:
            continue
        source_completes = OrderedDict()
        source_fails = OrderedDict()
        for item in sorted(rec["timeline"], key=lambda x: (x["ts"], x["line"])):
            if item["device"] != origin:
                continue
            if item["stage"] == "send_complete":
                target = ""
                m = re.search(r"target=([A-Za-z0-9_.:-]+)", item["detail"])
                if m:
                    target = m.group(1)
                if target:
                    source_completes[target] = 1
            elif item["stage"] == "send_failed":
                target = ""
                reason = ""
                mt = re.search(r"target=([A-Za-z0-9_.:-]+)", item["detail"])
                mr = re.search(r"reason=([A-Za-z0-9_.:-]+)", item["detail"])
                if mt:
                    target = mt.group(1)
                if mr:
                    reason = mr.group(1)
                source_fails[target or f"line{item['line']}"] = reason or "-"
        if source_completes and source_fails:
            rows.append({
                "batch_id": rec["batch_id"],
                "origin": origin,
                "text": next(iter(rec["texts"]), "") or next(iter(rec["events"]), ""),
                "complete_targets": ",".join(source_completes.keys()),
                "failed_targets": ",".join(f"{k}:{v}" for k, v in source_fails.items()),
            })
    if not rows:
        print("(no source-side secondary-attempt noise found)")
        print()
        return
    print("| Batch | Origin | Text/Event | Complete targets | Failed targets after success |")
    print("|---|---|---|---|---|")
    for row in rows:
        print(
            f"| `{md(row['batch_id'])}` | {md(row['origin'])} | {md(short(row['text'], 120))} | "
            f"{md(row['complete_targets'])} | {md(short(row['failed_targets'], 180))} |"
        )
    print()

def verdict_for(rec):
    complete = bool(rec["targets_complete"]) or rec["stage_counts"].get("send_complete", 0) or rec["stage_counts"].get("opaque_send_complete", 0)
    failed = bool(rec["targets_failed"]) or rec["stage_counts"].get("send_failed", 0) or rec["stage_counts"].get("opaque_retry_failed", 0)
    carrier_store = rec["stage_counts"].get("opaque_store", 0) > 0
    carrier_queue = rec["stage_counts"].get("opaque_queued", 0) > 0
    if complete and carrier_store:
        return "RELAYED"
    if complete and failed:
        return "PARTIAL"
    if complete:
        return "SUCCESS"
    if carrier_store and not complete:
        return "COLLECTED_ONLY"
    if failed:
        return "FAIL"
    if carrier_queue:
        return "QUEUED"
    return "SEEN"

def render_per_batch_verdict(ordered):
    print("## Per-batch Verdict")
    print()
    print("| # | Batch | Origin | Verdict | Devices seen | Complete targets | Failed targets | Reasons |")
    print("|---:|---|---|---|---|---|---|---|")
    for idx, rec in enumerate(ordered, 1):
        print(
            "| " + " | ".join([
                str(idx),
                f"`{md(rec['batch_id'])}`",
                md((rec["origin"] or "-") + (f"/{rec['origin_source']}" if rec["origin_source"] else "")),
                md(verdict_for(rec)),
                md(",".join(rec["devices_seen"].keys())),
                md(",".join(rec["targets_complete"].keys())),
                md(",".join(rec["targets_failed"].keys())),
                md(short(",".join(rec["reasons"].keys()), 160)),
            ]) + " |"
        )
    print()

def render_queue_health(counters):
    print("## Queue Health")
    print()
    print("| Device | Queue timeout | Zero-chunk failures | Send failed | Dispatch failed | Transport skipped | Broadcast rejected | drop_retry_exhausted | Verdict |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---|")
    for device, ctr in counters.items():
        if ctr["queued_timeout"] == 0 and ctr["zero_chunk_failures"] == 0 and ctr["send_failed"] == 0:
            verdict = "QUEUE_HEALTHY"
        elif ctr["drop_retry_exhausted"] > 0:
            verdict = "RETRY_EXHAUSTED"
        elif ctr["queued_timeout"] > 0 and ctr["zero_chunk_failures"] > 0:
            verdict = "ZERO_CHUNK_FAIL"
        elif ctr["queued_timeout"] > 0:
            verdict = "QUEUED_NO_PROGRESS"
        else:
            verdict = "NOISY"
        print(
            f"| {md(device)} | {ctr['queued_timeout']} | {ctr['zero_chunk_failures']} | "
            f"{ctr['send_failed']} | {ctr['dispatch_failed']} | {ctr['transport_skipped']} | "
            f"{ctr['broadcast_rejected']} | {ctr['drop_retry_exhausted']} | {md(verdict)} |"
        )
    print()

def render_transport_stability(connectivity_summaries, counters):
    print("## Transport Stability")
    print()
    print("| Device | Service start/stop | Advertise ok/fail | Disconnect/fail/backoff | write_status_1 | write_status_133 | Verdict |")
    print("|---|---|---|---|---:|---:|---|")
    for device, summary in connectivity_summaries.items():
        ctr = counters.get(device, {})
        disconnect_total = sum(summary["disconnects"].values())
        failure_total = sum(summary["failures"].values())
        backoff_total = sum(summary["backoffs"].values())
        if failure_total == 0 and disconnect_total <= 2 and ctr.get("write_failed_status_133", 0) == 0 and ctr.get("write_failed_status_1", 0) == 0:
            verdict = "SERVICE_STABLE"
        elif ctr.get("write_failed_status_133", 0) > 0 or ctr.get("write_failed_status_1", 0) > 0:
            verdict = "WRITE_CALLBACK_FAILURE"
        else:
            verdict = "CONNECT_CHURN"
        print(
            f"| {md(device)} | {summary['service_start']}/{summary['service_stop']} | "
            f"{summary['advertise_started']}/{summary['advertise_failed']} | "
            f"{disconnect_total}/{failure_total}/{backoff_total} | "
            f"{ctr.get('write_failed_status_1', 0)} | {ctr.get('write_failed_status_133', 0)} | {md(verdict)} |"
        )
    print()

def render_routing_identity(connectivity_summaries, counters):
    print("## Routing / Identity")
    print()
    print("| Device | Candidate peers | Queued peers | alias_added | canonicalized | target_rewritten | stale_suppressed | anti_loop_drop | no_non_origin_target | Verdict |")
    print("|---|---|---|---:|---:|---:|---:|---:|---:|---|")
    for device, summary in connectivity_summaries.items():
        ctr = counters.get(device, {})
        verdict = "IDENTITY_CLEAN"
        if ctr.get("identity_alias_added", 0) or ctr.get("identity_canonicalized", 0) or ctr.get("queued_target_rewritten", 0):
            verdict = "IDENTITY_REWRITE_NOISE"
        elif ctr.get("last_hop_unknown_drop", 0) or ctr.get("opaque_no_non_origin_target", 0):
            verdict = "ANTI_LOOP_DROP"
        print(
            f"| {md(device)} | {md(short(','.join(summary['candidate_peers'].keys()), 120))} | "
            f"{md(short(','.join(summary['queued_peers'].keys()), 120))} | "
            f"{ctr.get('identity_alias_added', 0)} | {ctr.get('identity_canonicalized', 0)} | "
            f"{ctr.get('queued_target_rewritten', 0)} | {ctr.get('stale_identity_suppressed', 0)} | "
            f"{ctr.get('last_hop_unknown_drop', 0)} | {ctr.get('opaque_no_non_origin_target', 0)} | {md(verdict)} |"
        )
    print()

def main(paths):
    batches, opaque_windows, batch_byte_hints = parse(paths)
    delivery, counters, ack_stats = parse_phase1(paths)
    dtn_control_summaries, dtn_control_timeline = parse_dtn_control(paths)
    dtn_payload_ingest = parse_dtn_payload_ingest(paths)
    connectivity_summaries, connectivity_timeline = parse_connectivity(paths)
    mesh_state_summaries, mesh_state_timeline = parse_mesh_state(paths)
    ordered = sorted(
        batches.values(),
        key=lambda r: (r["first_ts"] if r["first_ts"] is not None else 10**12, r["batch_id"]),
    )

    batch_index = build_batch_index(ordered)
    render_delivery(delivery, batch_index)
    render_ack_activity(ack_stats)
    render_dtn_payload_ingest(dtn_payload_ingest)
    render_dtn_control_plane(dtn_control_summaries, dtn_control_timeline)
    render_carrier_and_noise(ordered, counters)
    render_queue_health(counters)
    render_transport_stability(connectivity_summaries, counters)
    render_mesh_state_timeline(mesh_state_summaries, mesh_state_timeline)
    render_routing_identity(connectivity_summaries, counters)
    render_per_batch_verdict(ordered)
    render_connectivity(connectivity_summaries, connectivity_timeline)

    print("## Batch Transport Matrix")
    print()
    print("Scope: all `batch_id` entries found in the provided ActivityLogs. `queued` means the batch entered a local queue; only `send_complete` / `opaque_send_complete` means BLE write finished for a target.")
    print()
    print("| # | Batch | Origin | First seen | Duration s | Status | Devices seen | Complete targets | Failed targets | Reasons | Stage counts | Text/Event sample |")
    print("|---:|---|---|---|---:|---|---|---|---|---|---|---|")
    for idx, rec in enumerate(ordered, 1):
        counts = ",".join(f"{k}={rec['stage_counts'].get(k, 0)}" for k in (
            "created",
            "emit",
            "transport_queued",
            "send_progress",
            "send_complete",
            "send_failed",
            "opaque_store",
            "opaque_queued",
            "opaque_send_complete",
            "opaque_retry_failed",
            "transport_skipped",
            "broadcast_rejected",
        ) if rec["stage_counts"].get(k, 0))
        sample = next(iter(rec["texts"]), "") or next(iter(rec["events"]), "")
        print(
            "| "
            + " | ".join([
                str(idx),
                f"`{md(rec['batch_id'])}`",
                md((rec["origin"] or "-") + (f"/{rec['origin_source']}" if rec["origin_source"] else "")),
                md(f"{rec['first_time']} {rec['first_line']}"),
                md(duration_s(rec)),
                md(verdict_for(rec)),
                md(",".join(rec["devices_seen"].keys())),
                md(",".join(rec["targets_complete"].keys())),
                md(",".join(rec["targets_failed"].keys())),
                md(short(",".join(rec["reasons"].keys()), 140)),
                md(short(counts, 160)),
                md(short(sample, 140)),
            ])
            + " |"
        )

    interesting = [
        window for window in opaque_windows
        if any(
            d.get("reason") in ("opaque_candidate", "opaque_forward_skipped", "opaque_forward_scheduled", "store_rejected", "dedupe_hit")
            for d in window.get("decisions", [])
        )
    ]
    print("## Opaque Forwarding Gaps")
    print()
    print("Scope: `GATT deliver envelope` windows whose relay decision lacks a `batch_id`. Candidate batches are matched only by payload byte size and nearby timestamps, so use them as evidence hints, not hard attribution.")
    print()
    if not interesting:
        print("(no opaque forwarding windows found)")
        print()
    else:
        print("| # | Device | Deliver | Bytes | Status | Decisions | Candidate batches by bytes/time |")
        print("|---:|---|---|---:|---|---|---|")
        for idx, window in enumerate(interesting, 1):
            decisions = "; ".join(
                " ".join(
                    x for x in (
                        f"line={d.get('line')}",
                        f"kind={d.get('kind')}" if d.get("kind") else "",
                        f"accepted={d.get('accepted')}" if d.get("accepted") else "",
                        f"relay_eligible={d.get('relay_eligible')}" if d.get("relay_eligible") else "",
                        f"reason={d.get('reason')}" if d.get("reason") else "",
                    ) if x
                )
                for d in window.get("decisions", [])
            )
            candidates = []
            for _, delta, _, hint in nearby_batch_candidates(window, batch_byte_hints):
                delta_text = "?" if delta == 999999.0 else f"{delta:.3f}s"
                candidates.append(
                    f"{hint['batch_id']}@{hint['device']}:{hint['line']} {hint['stage']} {hint['time']} delta={delta_text}"
                )
            print(
                "| "
                + " | ".join([
                    str(idx),
                    md(window.get("device")),
                    md(f"{window.get('time')}:{window.get('line')}"),
                    md(window.get("bytes") if window.get("bytes") is not None else "-"),
                    md(opaque_status(window)),
                    md(short(decisions, 220)),
                    md(short("; ".join(candidates), 260)),
                ])
                + " |"
            )
        print()

    print("## Batch Transport Details")
    print()
    for rec in ordered:
        print(f"### `{md(rec['batch_id'])}`")
        print()
        print(f"- status: `{verdict_for(rec)}`")
        print(f"- origin: `{md(rec['origin'] or '-')}`")
        print(f"- first_seen: `{md(rec['first_time'])}` at `{md(rec['first_line'])}`")
        print(f"- duration_s: `{duration_s(rec)}`")
        if rec["events"]:
            print(f"- event_ids: `{md(short(','.join(rec['events'].keys()), 300))}`")
        if rec["texts"]:
            print(f"- text: {md(short(next(iter(rec['texts'])), 220))}")
        print()
        print("| Time | Device | Line | Stage | Detail |")
        print("|---|---|---:|---|---|")
        timeline = sorted(rec["timeline"], key=lambda x: (x["ts"], x["device"], x["line"]))
        for item in timeline:
            print(
                f"| {md(item['time'])} | {md(item['device'])} | {item['line']} | "
                f"{md(item['stage'])} | {md(item['detail'])} |"
            )
        print()

if __name__ == "__main__":
    if len(sys.argv) <= 1:
        print("No log files provided.", file=sys.stderr)
        sys.exit(1)
    main(sys.argv[1:])
PY_DTN_BATCH_TRANSPORT
}

run_dtn_report_md() {
  require_analysis_cmds
  local files=()
  ensure_files_or_latest files "$@"
  mkdir -p "$ANALYSIS_DIR"
  local ts out
  ts="$(date +%Y%m%d_%H%M%S_%N)"
  out="$ANALYSIS_DIR/dtn_round_${ts}_$$.md"
  {
    echo "# DTN Mesh Round Report"
    echo
    echo "Generated: $(date '+%Y-%m-%d %H:%M:%S')"
    echo
    echo "## Source logs"
    local f
    for f in "${files[@]}"; do
      echo "- $f"
    done
    echo
    echo "## DTN Summary"
    echo '```text'
    run_dtn_report_text "${files[@]}"
    echo '```'
    echo
    run_dtn_batch_transport_md "${files[@]}"
  } > "$out"
  echo "$out"
}

run_classify() {
  require_analysis_cmds
  local files=()
  ensure_files_or_latest files "$@"
  local f
  for f in "${files[@]}"; do
    echo "==== $f"
    [[ $(count_pattern "status=133" "$f") -gt 0 ]] && echo "likely_layer=BLE_LINK"
    [[ $(count_pattern "BLE connect cap blocked" "$f") -gt 0 ]] && echo "likely_layer=BLE_LINK"
    [[ $(count_pattern "queued_no_write_attempt_timeout" "$f") -gt 0 ]] && echo "likely_layer=TRANSPORT_QUEUE"
    [[ $(count_pattern "queued_no_progress_timeout" "$f") -gt 0 ]] && echo "likely_layer=TRANSPORT_OR_STATE"
    [[ $(count_pattern "BLE batch send failed" "$f") -gt 0 ]] && echo "likely_layer=TRANSPORT_OR_STATE"
    [[ $(count_pattern "DTN opaque retry retry_failed" "$f") -gt 0 ]] && echo "likely_layer=DTN_OPAQUE_CARRIER"
    [[ $(count_pattern "DTN opaque retry skipped" "$f") -gt 0 ]] && echo "likely_layer=DTN_OPAQUE_CARRIER"
    [[ $(count_pattern "DTN sync summary" "$f") -gt 0 ]] && echo "likely_layer=DTN_CONTROL"
    [[ $(count_pattern "APP dispatch job failed" "$f") -gt 0 ]] && echo "likely_layer=APP"
    [[ $(count_pattern "APP dispatch job completed" "$f") -gt 0 ]] && echo "likely_layer=APP"
    echo
  done
}

run_trace() {
  require_analysis_cmds
  local needle="${1:-}"
  if [ -z "$needle" ]; then
    echo "Usage: mesh_debug.sh trace <needle> [log_file ...]" >&2
    exit 1
  fi
  shift || true
  local files=()
  ensure_files_or_latest files "$@"
  local f
  for f in "${files[@]}"; do
    echo "==== $f"
    rg -n --fixed-strings "$needle" "$f" || echo "(no match)"
    echo
  done
}

run_deep_trace() {
  ensure_deep_trace_script
  local py
  py="$(require_python)"
  local needle="${1:-}"
  if [ -z "$needle" ]; then
    echo "Usage: mesh_debug.sh deep-trace <needle> [log_file ...]" >&2
    exit 1
  fi
  shift || true
  local files=()
  ensure_files_or_latest files "$@"
  "$py" "$DEEP_TRACE_PY" "$needle" "${files[@]}"
}

run_deep_trace_md() {
  ensure_deep_trace_script
  local py
  py="$(require_python)"
  local needle="${1:-}"
  if [ -z "$needle" ]; then
    echo "Usage: mesh_debug.sh deep-trace-md <needle> [log_file ...]" >&2
    exit 1
  fi
  shift || true
  local files=()
  ensure_files_or_latest files "$@"
  mkdir -p "$ANALYSIS_DIR"
  local ts safe out
  ts="$(date +%Y%m%d_%H%M%S)"
  safe="$(printf '%s' "$needle" | tr ' /:' '___' | tr -cd '[:alnum:]_.-')"
  [ -z "$safe" ] && safe="trace"
  out="$ANALYSIS_DIR/deep_trace_${safe}_${ts}.md"
  "$py" "$DEEP_TRACE_PY" "$needle" "${files[@]}" --format markdown --output "$out"
  echo "$out"
}

run_divergence() {
  require_analysis_cmds
  ensure_divergence_script
  local py
  py="$(require_python)"
  local needle="${1:-}"
  if [ -z "$needle" ]; then
    echo "Usage: mesh_debug.sh divergence <needle> [log_file ...]" >&2
    exit 1
  fi
  shift || true
  local files=()
  ensure_files_or_latest files "$@"
  "$py" "$DIVERGENCE_PY" "$needle" "${files[@]}"
}

run_divergence_md() {
  require_analysis_cmds
  ensure_divergence_script
  local py
  py="$(require_python)"
  local needle="${1:-}"
  if [ -z "$needle" ]; then
    echo "Usage: mesh_debug.sh divergence-md <needle> [log_file ...]" >&2
    exit 1
  fi
  shift || true
  local files=()
  ensure_files_or_latest files "$@"
  mkdir -p "$ANALYSIS_DIR"
  local ts safe out
  ts="$(date +%Y%m%d_%H%M%S)"
  safe="$(printf '%s' "$needle" | tr ' /:' '___' | tr -cd '[:alnum:]_.-')"
  [ -z "$safe" ] && safe="divergence"
  out="$ANALYSIS_DIR/divergence_${safe}_${ts}.md"
  "$py" "$DIVERGENCE_PY" "$needle" "${files[@]}" --format markdown --output "$out"
  echo "$out"
}

run_round() {
  local count="${1:-1}"
  cat <<EOF
BLE Mesh Debug Round x$count
1. Pull latest logs from all devices
2. Run quick metrics and DTN summary
3. Pick one message token and one batch_id
4. Trace sender -> carrier -> receiver
5. Check DTN control: hello, inv_have, inv_want, data, ack, sync summary
6. Check opaque carrier: queued, skipped, retry_failed, send_complete, delete
7. Identify the first missing stage with log evidence
8. Rank root causes from evidence only
9. Apply minimal patch only after root cause is verified
10. Re-test and stop after 3 failed patch rounds
EOF
}

ensure_token_selector_script() {
  if [ ! -f "$TOKEN_SELECTOR_PY" ]; then
    echo "Token selector not found: $TOKEN_SELECTOR_PY" >&2
    echo "Expected path: scripts/debug-kit/token_selector.py" >&2
    exit 1
  fi
}

run_select_tokens() {
  ensure_token_selector_script
  local py
  py="$(require_python)"

  local files=()
  ensure_files_or_latest files "$@"

  "$py" "$TOKEN_SELECTOR_PY" "${files[@]}"
}



run_missing_events_python() {
  local format="$1" output="$2"
  shift 2 || true
  local py
  py="$(require_python)"
  "$py" - "$format" "$output" "$@" <<'PY_MISSING_EVENTS'
import argparse, re, sys
from collections import OrderedDict, defaultdict
EVENT_RE=re.compile(r"eventId=([A-Za-z0-9_-]+)|event_ids=([A-Za-z0-9_,\-]+)")
BATCH_RE=re.compile(r"batch_id=([A-Za-z0-9_-]+)")
TEXT_RE=re.compile(r"text=([^\"]*)")
TYPE_RE=re.compile(r"\btype=([A-Z0-9_.-]+)")
DEVICE_RE=re.compile(r"(?:^|[/\\])device-([A-Za-z0-9_-]+)_activity_log")
STAGES=OrderedDict([('sent','Sent message at'),('sender_emit','APP batch emit'),('sender_transport_queued','APP batch transport queued'),('mtu_negotiation','MTU negotiation'),('received','Received message at'),('db_insert','DB event insert'),('rendered','Rendered message at'),('extract','APP batch extract'),('reassembled','GATT reassembled'),('deliver_envelope','GATT deliver envelope'),('relay_decision','BLE relay decision'),('forward_queued','BLE batch forward queued'),('forward_delivered','BLE batch forward delivered'),('forward_skipped','BLE batch forward skipped'),('delayed_relay_dropped','BLE delayed relay dropped'),('send_progress','BLE batch send progress'),('send_complete','BLE batch send complete'),('send_failed','BLE batch send failed'),('dispatch_completed','APP dispatch job completed'),('dispatch_failed','APP dispatch job failed'),('pull_attempt','DISPATCH pull attempt'),('pull_skipped','DISPATCH job pull skipped')])
OK=('received','db_insert','rendered','extract')
OBSERVED=('received','db_insert','rendered','extract','forward_queued','forward_delivered','delayed_relay_dropped','send_progress','send_complete','dispatch_completed')
def dev(path,i):
 m=DEVICE_RE.search(path); return m.group(1) if m else chr(ord('A')+i)
def parse(path,name):
 events=OrderedDict(); batches_seen=defaultdict(lambda: defaultdict(list)); counters=defaultdict(int)
 with open(path,encoding='utf-8',errors='replace') as fh:
  for no,line in enumerate(fh,1):
   stage=next((k for k,v in STAGES.items() if v in line),None)
   if not stage: continue
   counters[stage]+=1
   mb=BATCH_RE.search(line); batch=mb.group(1) if mb else ''
   mt=TEXT_RE.search(line); text=mt.group(1).strip() if mt else ''
   mtype=TYPE_RE.search(line); typ=mtype.group(1) if mtype else ''
   if batch: batches_seen[batch][stage].append(no)
   ids=[]
   for me in EVENT_RE.finditer(line): ids += [x for x in (me.group(1) or me.group(2)).split(',') if len(x) >= 40]
   for eid in ids:
    rec=events.setdefault(eid, {'text':text,'type':typ,'batches':OrderedDict(),'stages':defaultdict(list),'first_line':no})
    if text and not rec['text']: rec['text']=text
    if typ and not rec['type']: rec['type']=typ
    if batch: rec['batches'][batch]=1
    rec['stages'][stage].append((no,batch))
 return {'name':name,'path':path,'events':events,'batches_seen':batches_seen,'counters':counters}
def mark(r,s): return 'Y' if r and r['stages'].get(s) else '-'
def batches(r): return ','.join(r['batches'].keys()) if r else ''
def sender_side_event(r):
 return bool(r and any(r['stages'].get(s) for s in ('sent','sender_emit')))
def text_sender_name(text,name):
 return bool(text and re.search(r"(?:^|\b)%s\s+#\d+\b" % re.escape(name), text))
def actor_from_text(text):
 m=re.search(r"\bactor=([A-Za-z0-9_-]+)", text or '')
 return m.group(1) if m else ''
def message_number(text,name):
 m=re.search(r"(?:^|\b)%s\s+#(\d+)\b" % re.escape(name), text or '')
 return int(m.group(1)) if m else None
def best_type(eid,sender,receivers):
 srec=sender['events'].get(eid)
 if srec and srec.get('type'): return srec['type']
 for r in receivers:
  rr=r['events'].get(eid)
  if rr and rr.get('type'): return rr['type']
 return ''
def best_text(eid,sender,receivers):
 srec=sender['events'].get(eid)
 if srec and srec.get('text'): return srec['text']
 for r in receivers:
  rr=r['events'].get(eid)
  if rr and rr.get('text'): return rr['text']
 return ''
def best_batch(eid,sender,receivers):
 srec=sender['events'].get(eid)
 if srec and batches(srec): return batches(srec)
 for r in receivers:
  rr=r['events'].get(eid)
  if rr and batches(rr): return batches(rr)
 return ''
def receiver_observed(r,eid,batch):
 rec=r['events'].get(eid)
 if rec and any(rec['stages'].get(s) for s in OK): return True
 if batch:
  seen=r['batches_seen'].get(batch,{})
  return any(seen.get(s) for s in OBSERVED)
 return False
def classify_event(eid,sender,receivers,local_actors):
 typ=best_type(eid,sender,receivers)
 txt=best_text(eid,sender,receivers)
 actor=actor_from_text(txt)
 if typ == 'SYS.ACK': return 'ack'
 if typ in ('CHAT.MSG','CHAT.REPLY'): return 'message'
 if text_sender_name(txt,sender['name']) or (actor and actor in local_actors): return 'message'
 return 'unknown'
def event_belongs_to_sender_message(eid,sender,receivers,local_actors):
 txt=best_text(eid,sender,receivers)
 actor=actor_from_text(txt)
 return text_sender_name(txt,sender['name']) or (actor and actor in local_actors)
def build_rows(ids,sender,receivers,start_idx=1):
 rows=[]
 def sort_key(eid):
  txt=best_text(eid,sender,receivers)
  msg_no=message_number(txt,sender['name'])
  srec=sender['events'].get(eid)
  line=srec['first_line'] if srec else min((r['events'][eid]['first_line'] for r in receivers if eid in r['events']), default=10**9)
  return (msg_no if msg_no is not None else 10**9, line, eid)
 for offset,eid in enumerate(sorted(ids.keys(), key=sort_key),start_idx):
  row={'idx':offset,'eid':eid,'type':best_type(eid,sender,receivers),'text':best_text(eid,sender,receivers),'batch':best_batch(eid,sender,receivers),'rr':[]}
  for r in receivers:
   rr=r['events'].get(eid); ok=receiver_observed(r,eid,row['batch']); row['rr'].append((r['name'],ok,mark(rr,'received'),mark(rr,'db_insert'),mark(rr,'rendered'),mark(rr,'extract'),batches(rr) or (row['batch'] if ok else '')))
  rows.append(row)
 return rows
def missing_by_receiver(rows,receivers):
 missing=OrderedDict((r['name'],[]) for r in receivers)
 for row in rows:
  for name,ok,*_ in row['rr']:
   if not ok: missing[name].append(row)
 return missing
def append_scope(out,md,title,rows,missing,receivers,include_details=True):
 if md:
  out += [f"## {title}",'',f"Events: {len(rows)}",'','| Receiver | Observed in log | No evidence in log |','|---|---:|---:|']
  for name,miss in missing.items(): out.append(f"| {name} | {len(rows)-len(miss)} | {len(miss)} |")
  out += ['','### Event matrix','']
  header=['#','sender_batch','event_type','event_id','text']
  for r in receivers: header += [f"{r['name']}_ok",f"{r['name']}_recv",f"{r['name']}_db",f"{r['name']}_render",f"{r['name']}_extract",f"{r['name']}_batch"]
  out.append('| '+' | '.join(header)+' |'); out.append('| '+' | '.join(['---']*len(header))+' |')
  for row in rows:
   vals=[str(row['idx']),row['batch'],row['type'],row['eid'],row['text'].replace('|','\\|')]
   for name,ok,recv,dbins,rend,extr,batch in row['rr']: vals += ['OBSERVED' if ok else 'NO_EVIDENCE',recv,dbins,rend,extr,batch]
   out.append('| '+' | '.join(vals)+' |')
  if include_details:
   out += ['','### No-evidence details']
   for name,miss in missing.items():
    out.append(f"#### {name}"); out += ['- none'] if not miss else [f"- #{r['idx']} type={r['type'] or '-'} batch={r['batch']} event={r['eid']} text={r['text']}" for r in miss]
 else:
  out += [f"{title}: {len(rows)} events"]
  for name,miss in missing.items(): out.append(f"- {name}: observed={len(rows)-len(miss)} no_evidence={len(miss)}")
  for row in rows:
   bits=[f"{name}={'OBSERVED' if ok else 'NO_EVIDENCE'}(recv={recv} db={dbins} render={rend} extract={extr} batch={batch or '-'})" for name,ok,recv,dbins,rend,extr,batch in row['rr']]
   out.append(f"{row['idx']:02d}. type={row['type'] or '-'} batch={row['batch'] or '-'} event={row['eid']} text=\"{row['text']}\" :: "+' ; '.join(bits))
  if include_details:
   out += ['No-evidence details:']
   for name,miss in missing.items(): out.append(f"- {name}: "+(', '.join(f"#{r['idx']}({r['batch']})" for r in miss) if miss else 'none'))
 out.append('')
def main():
 ap=argparse.ArgumentParser(); ap.add_argument('format',choices=['text','markdown']); ap.add_argument('output'); ap.add_argument('--sender',default='A'); ap.add_argument('--receivers',default=''); ap.add_argument('files',nargs='+')
 a=ap.parse_args(); parsed=[parse(f,dev(f,i)) for i,f in enumerate(a.files)]; by={p['name']:p for p in parsed}; sender=by.get(a.sender,parsed[0])
 receivers=[by[x.strip()] for x in a.receivers.split(',') if x.strip() in by] if a.receivers else [p for p in parsed if p is not sender]
 local_actors=set(actor_from_text(rec.get('text','')) for rec in sender['events'].values() if rec['stages'].get('sent') and rec.get('type') != 'SYS.ACK')
 local_actors.discard('')
 message_ids=OrderedDict(); ack_ids=OrderedDict(); unknown_ids=OrderedDict()
 for eid,rec in sender['events'].items():
  if not sender_side_event(rec): continue
  scope=classify_event(eid,sender,receivers,local_actors)
  if scope == 'ack': ack_ids[eid]=1
  elif scope == 'message' and event_belongs_to_sender_message(eid,sender,receivers,local_actors): message_ids[eid]=1
  else: unknown_ids[eid]=1
 for r in receivers:
  for eid,rec in r['events'].items():
   txt=rec.get('text','')
   actor=actor_from_text(txt)
   if classify_event(eid,sender,receivers,local_actors) == 'message' and (text_sender_name(txt,sender['name']) or (actor and actor in local_actors)):
    message_ids.setdefault(eid,1)
 message_rows=build_rows(message_ids,sender,receivers)
 ack_rows=build_rows(ack_ids,sender,receivers)
 unknown_rows=build_rows(unknown_ids,sender,receivers)
 message_missing=missing_by_receiver(message_rows,receivers)
 ack_missing=missing_by_receiver(ack_rows,receivers)
 unknown_missing=missing_by_receiver(unknown_rows,receivers)
 md=a.format=='markdown'; out=[]; out += [('# ' if md else '==== ')+'BLE Mesh Activity Log Evidence Report'+('' if md else ' ===='),'',f"Sender: {sender['name']} ({sender['path']})",'Receivers: '+', '.join(f"{r['name']} ({r['path']})" for r in receivers),f"Message events: {len(message_rows)}",f"ACK events: {len(ack_rows)}",f"Unknown sender-side events: {len(unknown_rows)}"]
 out.append('Scope: activity-log evidence only; UI/database state may contain messages not emitted as Received/Rendered log lines.')
 for name,miss in message_missing.items(): out.append(f"Message no evidence on {name}: {len(miss)}/{len(message_rows)}")
 for name,miss in ack_missing.items(): out.append(f"ACK no evidence on {name}: {len(miss)}/{len(ack_rows)}")
 out.append('')
 if md:
  out += ['## Summary','','| Scope | Events | ' + ' | '.join(f"{r['name']} observed" for r in receivers) + ' | ' + ' | '.join(f"{r['name']} no evidence" for r in receivers) + ' |','|---|---:|' + '|'.join(['---:']*(len(receivers)*2)) + '|']
  for label,rows,missing in (('Message',message_rows,message_missing),('ACK',ack_rows,ack_missing),('Unknown',unknown_rows,unknown_missing)):
   out.append(f"| {label} | {len(rows)} | " + ' | '.join(str(len(rows)-len(missing[r['name']])) for r in receivers) + ' | ' + ' | '.join(str(len(missing[r['name']])) for r in receivers) + ' |')
  out.append('')
  append_scope(out,md,'Message Events',message_rows,message_missing,receivers)
  append_scope(out,md,'ACK Events',ack_rows,ack_missing,receivers)
  append_scope(out,md,'Unknown Sender-side Events',unknown_rows,unknown_missing,receivers,include_details=False)
 else:
  append_scope(out,md,'Message Events',message_rows,message_missing,receivers)
  append_scope(out,md,'ACK Events',ack_rows,ack_missing,receivers)
  append_scope(out,md,'Unknown Sender-side Events',unknown_rows,unknown_missing,receivers,include_details=False)
 out += ['','Stage counters:']; keys=['sent','mtu_negotiation','received','db_insert','rendered','extract','reassembled','deliver_envelope','send_complete','send_failed','dispatch_completed','dispatch_failed','pull_attempt','pull_skipped']
 for p in parsed: out.append(f"- {p['name']}: "+', '.join(f"{k}={p['counters'].get(k,0)}" for k in keys))
 report='\n'.join(out)+'\n'
 if a.output=='-': sys.stdout.write(report)
 else: open(a.output,'w',encoding='utf-8').write(report); print(a.output)
if __name__=='__main__': main()
PY_MISSING_EVENTS
}

run_missing_events() {
  local sender="A" receivers=""
  while [ "${1:-}" != "" ]; do
    case "${1:-}" in
      --sender) sender="${2:-}"; shift 2 || true ;;
      --receivers) receivers="${2:-}"; shift 2 || true ;;
      --) shift || true; break ;;
      *) break ;;
    esac
  done
  local files=(); ensure_files_or_latest files "$@"
  if [ -n "$receivers" ]; then
    run_missing_events_python text - --sender "$sender" --receivers "$receivers" "${files[@]}"
  else
    run_missing_events_python text - --sender "$sender" "${files[@]}"
  fi
}

run_missing_events_md() {
  local sender="A" receivers=""
  while [ "${1:-}" != "" ]; do
    case "${1:-}" in
      --sender) sender="${2:-}"; shift 2 || true ;;
      --receivers) receivers="${2:-}"; shift 2 || true ;;
      --) shift || true; break ;;
      *) break ;;
    esac
  done
  local files=(); ensure_files_or_latest files "$@"
  mkdir -p "$ANALYSIS_DIR"
  local ts out; ts="$(date +%Y%m%d_%H%M%S_%N)"; out="$ANALYSIS_DIR/missing_events_${ts}_$$.md"
  if [ -n "$receivers" ]; then
    run_missing_events_python markdown "$out" --sender "$sender" --receivers "$receivers" "${files[@]}"
  else
    run_missing_events_python markdown "$out" --sender "$sender" "${files[@]}"
  fi
}

run_failed_sending_python() {
  local format="$1" output="$2"
  shift 2 || true
  local py
  py="$(require_python)"
  "$py" - "$format" "$output" "$@" <<'PY_FAILED_SENDING'
import argparse, re, sys
from collections import OrderedDict

BATCH_RE = re.compile(r"batch_id=([A-Za-z0-9_-]+)")
EVENT_IDS_RE = re.compile(r"event_ids=([^ \"\t]+)")
EVENT_ID_RE = re.compile(r"eventId=([A-Za-z0-9_-]+)")
TYPE_RE = re.compile(r"\btype=([A-Z0-9_.-]+)")
DEVICE_RE = re.compile(r"(?:device|device_id)=([A-Za-z0-9_.:-]+)")
REASON_RE = re.compile(r"\breason=([^ \"\t]+)")
SKIP_RE = re.compile(r"\bskip_reason=([^ \"\t]+)")
PAYLOAD_RE = re.compile(r"\bpayload_bytes=(\d+)")
COUNT_RE = re.compile(r"\bcount=(\d+)")
CHUNKS_RE = re.compile(r"\bchunks=([^ \"\t]+)")
BYTES_RE = re.compile(r"\bbytes=([^ \"\t]+)")
FANOUT_TARGET_RE = re.compile(r"\bfanout_target=([^ \"\t]+)")
FANOUT_SENT_RE = re.compile(r"\bfanout_sent=([^ \"\t]+)")

def time_of(line):
    return line.split(" ", 1)[0] if line else ""

def find_event_ids(line):
    ids = []
    m = EVENT_ID_RE.search(line)
    if m:
        ids.append(m.group(1))
    m = EVENT_IDS_RE.search(line)
    if m:
        token = m.group(1)
        if "…" not in token:
            ids.extend(x for x in token.split(",") if x and len(x) >= 40)
    return ids

def short(v, limit=72):
    if not v:
        return "-"
    return v if len(v) <= limit else v[: limit - 1] + "..."

def collect_event_types(paths):
    event_types = {}
    for path in paths:
        with open(path, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                typ = TYPE_RE.search(line)
                if not typ:
                    continue
                for event_id in find_event_ids(line):
                    event_types.setdefault(event_id, typ.group(1))
    return event_types

def parse(path, known_event_types):
    batches = OrderedDict()
    event_types = dict(known_event_types)

    def batch_for(bid):
        return batches.setdefault(
            bid,
            {
                "first_line": None,
                "first_time": "",
                "gid": "",
                "payload": "",
                "count": "",
                "events": OrderedDict(),
                "reasons": OrderedDict(),
                "targets": OrderedDict(),
                "failures": [],
                "failed": False,
            },
        )

    with open(path, encoding="utf-8", errors="replace") as fh:
        for line_no, line in enumerate(fh, 1):
            line = line.rstrip("\n")
            ids = find_event_ids(line)
            typ = TYPE_RE.search(line)
            if typ:
                for event_id in ids:
                    event_types.setdefault(event_id, typ.group(1))

            mb = BATCH_RE.search(line)
            if not mb:
                continue
            bid = mb.group(1)
            batch = batch_for(bid)
            if batch["first_line"] is None:
                batch["first_line"] = line_no
                batch["first_time"] = time_of(line)

            gid = re.search(r"\bgid=([^ \"\t]+)", line)
            if gid and not batch["gid"]:
                batch["gid"] = gid.group(1)

            payload = PAYLOAD_RE.search(line)
            if payload:
                batch["payload"] = payload.group(1)

            count = COUNT_RE.search(line)
            if count:
                batch["count"] = count.group(1)

            for event_id in ids:
                batch["events"].setdefault(event_id, 1)

            reason = REASON_RE.search(line)
            if reason:
                batch["reasons"].setdefault(reason.group(1), 1)

            skip = SKIP_RE.search(line)
            if skip:
                batch["reasons"].setdefault("skip:" + skip.group(1), 1)

            dev = DEVICE_RE.search(line)
            if dev:
                batch["targets"].setdefault(dev.group(1), 1)

            failure_kind = None
            if "BLE batch send failed" in line:
                failure_kind = "ble_send_failed"
            elif "APP dispatch job failed" in line:
                failure_kind = "app_dispatch_failed"
            elif "APP batch transport skipped" in line and skip:
                failure_kind = "transport_skipped"
            elif "BROADCAST_REJECT_DECISION" in line:
                failure_kind = "broadcast_rejected"
            elif "transition=in_progress_to_failed" in line:
                failure_kind = "state_failed"

            if failure_kind:
                batch["failed"] = True
                detail = {
                    "line": line_no,
                    "time": time_of(line),
                    "kind": failure_kind,
                    "device": dev.group(1) if dev else "",
                    "reason": reason.group(1) if reason else "",
                    "skip_reason": skip.group(1) if skip else "",
                    "chunks": "",
                    "bytes": "",
                    "fanout": "",
                }
                chunks = CHUNKS_RE.search(line)
                if chunks:
                    detail["chunks"] = chunks.group(1)
                bytes_ = BYTES_RE.search(line)
                if bytes_:
                    detail["bytes"] = bytes_.group(1)
                ft = FANOUT_TARGET_RE.search(line)
                fs = FANOUT_SENT_RE.search(line)
                if ft or fs:
                    detail["fanout"] = f"{fs.group(1) if fs else '?'}/{ft.group(1) if ft else '?'}"
                batch["failures"].append(detail)

    for batch in batches.values():
        batch["types"] = OrderedDict()
        for event_id in batch["events"]:
            batch["types"].setdefault(event_types.get(event_id, "UNKNOWN"), 1)
    return batches

def render_text(path, batches):
    out = [f"==== {path}"]
    failed = [item for item in batches.items() if item[1]["failed"]]
    out.append(f"failed_batches={len(failed)}")
    if not failed:
        out.append("(none)")
        out.append("")
        return out

    for idx, (bid, batch) in enumerate(failed, 1):
        events = list(batch["events"].keys())
        failures = batch["failures"]
        kinds = OrderedDict((f["kind"], 1) for f in failures)
        targets = list(batch["targets"].keys())
        reasons = list(batch["reasons"].keys())
        types = list(batch["types"].keys())
        first = failures[0]
        out.append(
            f"{idx:02d}. batch={bid} first_fail={first['time']} line={first['line']} "
            f"types={','.join(types) or '-'} count={batch['count'] or '-'} "
            f"payload_bytes={batch['payload'] or '-'} failures={len(failures)} "
            f"kinds={','.join(kinds.keys())}"
        )
        out.append(f"    events={short(','.join(events), 160)}")
        out.append(f"    targets={short(','.join(targets), 120)} reasons={short(','.join(reasons), 120)}")
        for failure in failures[:6]:
            detail = failure["reason"] or failure["skip_reason"] or "-"
            extra = []
            if failure["device"]:
                extra.append(f"device={failure['device']}")
            if failure["chunks"]:
                extra.append(f"chunks={failure['chunks']}")
            if failure["bytes"]:
                extra.append(f"bytes={failure['bytes']}")
            if failure["fanout"]:
                extra.append(f"fanout={failure['fanout']}")
            out.append(
                f"    - {failure['time']} line={failure['line']} {failure['kind']} "
                f"reason={detail}" + (f" {' '.join(extra)}" if extra else "")
            )
        if len(failures) > 6:
            out.append(f"    - ... {len(failures) - 6} more")
    out.append("")
    return out

def render_markdown(paths, parsed):
    out = ["# Failed Sending Report", ""]
    out.append("Scope: activity-log evidence only. A batch is listed when the log contains send failure, dispatch failure, transport skipped, broadcast rejected, or failed state transition.")
    out.append("")
    out.append("## Summary")
    out.append("")
    out.append("| Log | Failed batches |")
    out.append("|---|---:|")
    for path, batches in zip(paths, parsed):
        out.append(f"| `{path}` | {sum(1 for b in batches.values() if b['failed'])} |")
    out.append("")

    for path, batches in zip(paths, parsed):
        failed = [item for item in batches.items() if item[1]["failed"]]
        out.append(f"## {path}")
        out.append("")
        if not failed:
            out.append("- none")
            out.append("")
            continue
        out.append("| # | Batch | Types | Count | Payload | First fail | Failure kinds | Events |")
        out.append("|---:|---|---|---:|---:|---|---|---|")
        for idx, (bid, batch) in enumerate(failed, 1):
            first = batch["failures"][0]
            kinds = ",".join(OrderedDict((f["kind"], 1) for f in batch["failures"]).keys())
            events = short(",".join(batch["events"].keys()), 120).replace("|", "\\|")
            types = ",".join(batch["types"].keys()) or "-"
            out.append(
                f"| {idx} | `{bid}` | {types} | {batch['count'] or '-'} | {batch['payload'] or '-'} | "
                f"{first['time']} L{first['line']} | {kinds} | `{events}` |"
            )
        out.append("")
        out.append("### Failure Details")
        out.append("")
        for bid, batch in failed:
            out.append(f"#### `{bid}`")
            for failure in batch["failures"]:
                detail = failure["reason"] or failure["skip_reason"] or "-"
                extra = []
                if failure["device"]:
                    extra.append(f"device={failure['device']}")
                if failure["chunks"]:
                    extra.append(f"chunks={failure['chunks']}")
                if failure["bytes"]:
                    extra.append(f"bytes={failure['bytes']}")
                if failure["fanout"]:
                    extra.append(f"fanout={failure['fanout']}")
                out.append(f"- {failure['time']} L{failure['line']} `{failure['kind']}` reason={detail}" + (f" {' '.join(extra)}" if extra else ""))
            out.append("")
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("format", choices=["text", "markdown"])
    ap.add_argument("output")
    ap.add_argument("files", nargs="+")
    args = ap.parse_args()

    event_types = collect_event_types(args.files)
    parsed = [parse(path, event_types) for path in args.files]
    if args.format == "markdown":
        report = "\n".join(render_markdown(args.files, parsed)) + "\n"
    else:
        lines = []
        for path, batches in zip(args.files, parsed):
            lines.extend(render_text(path, batches))
        report = "\n".join(lines)

    if args.output == "-":
        sys.stdout.write(report)
    else:
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(report)
        print(args.output)

if __name__ == "__main__":
    main()
PY_FAILED_SENDING
}

run_failed_sending() {
  local files=()
  ensure_files_or_latest files "$@"
  run_failed_sending_python text - "${files[@]}"
}

run_failed_sending_md() {
  local files=()
  ensure_files_or_latest files "$@"
  mkdir -p "$ANALYSIS_DIR"
  local ts out
  ts="$(date +%Y%m%d_%H%M%S_%N)"
  out="$ANALYSIS_DIR/failed_sending_${ts}_$$.md"
  run_failed_sending_python markdown "$out" "${files[@]}"
}

run_bundle() {
  require_analysis_cmds

  local label=""
  local mode="lite"

  while [ "${1:-}" != "" ]; do
    case "${1:-}" in
      --label)
        label="${2:-}"
        if [ -z "$label" ]; then
          echo "Usage: mesh_debug.sh bundle [--label label] [--full|--lite] [needle] [log_file ...]" >&2
          exit 1
        fi
        shift 2 || true
        ;;
      --full)
        mode="full"
        shift || true
        ;;
      --lite)
        mode="lite"
        shift || true
        ;;
      --)
        shift || true
        break
        ;;
      *)
        break
        ;;
    esac
  done

  local maybe_needle="${1:-}"
  local needle=""
  if [ -n "$maybe_needle" ] && [ ! -f "$maybe_needle" ]; then
    needle="$maybe_needle"
    shift || true
  fi

  local files=()
  ensure_files_or_latest files "$@"
  mkdir -p "$ANALYSIS_DIR"

  local ts safe out
  ts="$(date +%Y%m%d_%H%M%S_%N)"
  safe="$(printf '%s' "${needle:-all}" | tr ' /:' '___' | tr -cd '[:alnum:]_.-')"
  [ -z "$safe" ] && safe="bundle"

  if [ -n "$label" ]; then
    local safe_label
    safe_label="$(printf '%s' "$label" | tr ' /:' '___' | tr -cd '[:alnum:]_.-')"
    [ -n "$safe_label" ] && safe="${safe_label}_${safe}"
  fi

  safe="${mode}_${safe}"
  out="$ANALYSIS_DIR/mesh_bundle_${safe}_${ts}_$$.md"

  {
    echo "# BLE Mesh Debug Material"
    echo
    echo "Generated: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "Mode: $mode"
    [ -n "$label" ] && echo "Label: $label"
    [ -n "$needle" ] && echo "Needle: $needle"
    echo
    echo "## Source logs"
    local f
    for f in "${files[@]}"; do
      echo "- $f"
    done

    if [ "$mode" = "full" ]; then
      echo
      echo "## Quick metrics"
      echo '```text'
      run_metrics "${files[@]}"
      echo '```'

      echo
      echo "## DTN summary"
      echo '```text'
      run_dtn_report_text "${files[@]}"
      echo '```'

      echo
      echo "## Layer classification"
      echo '```text'
      run_classify "${files[@]}"
      echo '```'
    fi

    if [ -n "$needle" ]; then
      if [ "$mode" = "full" ]; then
        echo
        echo "## Raw trace"
        echo '```text'
        run_trace "$needle" "${files[@]}" || true
        echo '```'
      fi

      if [ -f "$DEEP_TRACE_PY" ]; then
        echo
        echo "## Deep trace"
        echo '```text'
        run_deep_trace "$needle" "${files[@]}" || true
        echo '```'
      fi

      if [ -f "$DIVERGENCE_PY" ]; then
        echo
        echo "## Divergence detector"
        echo '```text'
        run_divergence "$needle" "${files[@]}" || true
        echo '```'
      fi
    else
      echo
      echo "## Token status"
      echo "No token provided. This material only contains broad scan data."
    fi
  } > "$out"

  echo "$out"
}

codex_prompt_for_type() {
  local prompt_type="${1:-generic}"
  case "$prompt_type" in
    generic)
      cat <<'EOF'
# Prompt cho Codex

Chỉ sử dụng tài liệu điều tra bên dưới làm nguồn dữ liệu DUY NHẤT.

Tập trung vào MỘT token xuyên suốt tất cả device.

Yêu cầu:
1. Dựng lại runtime flow từng bước.
2. Xác định divergence point:
   - bước cuối cùng còn đúng
   - bước đầu tiên bị thiếu hoặc sai
3. So sánh tiến trình giữa các device:
   - device nào đi xa hơn?
   - device nào dừng sớm?
4. Đưa ra top 3 root cause:
   - bằng chứng hỗ trợ từ log
   - bằng chứng phản bác
   - mức độ tin cậy
5. KHÔNG đề xuất patch.

Nếu dữ liệu không đủ, hãy nói rõ thiếu log hoặc artifact nào.

---
EOF
      ;;
    mtu)
      cat <<'EOF'
# Prompt cho Codex - MTU / Chunking

Chỉ sử dụng tài liệu điều tra bên dưới làm nguồn dữ liệu DUY NHẤT.

Đây là case nghi vấn MTU / chunking.

Yêu cầu:
1. Xác định kích thước chunk thực tế:
   - số chunks
   - bytes mỗi chunk
2. So sánh với expected:
   - default 20 bytes
   - MTU negotiated
   - effective payload sau khi trừ header
3. Xác định message/batch có bị gửi trước khi MTU sẵn sàng không.
4. Tìm code path quyết định:
   - chunk size
   - MTU
   - max payload
   - packet rebuild
5. Giải thích vì sao có thể có 2 mode:
   - nhiều chunks nhỏ
   - ít chunks lớn
6. KHÔNG patch.

---
EOF
      ;;
    queue)
      cat <<'EOF'
# Prompt cho Codex - Queue / Stuck / Orphan

Chỉ sử dụng tài liệu điều tra bên dưới làm nguồn dữ liệu DUY NHẤT.

Đây là case nghi vấn queue hoặc state machine bị kẹt.

Yêu cầu:
1. Dựng flow của batch:
   - enqueue
   - staged
   - active
   - complete/fail
2. Xác định batch dừng ở state nào.
3. Kiểm tra dấu hiệu:
   - stuck watchdog
   - orphan timeout
   - active_packet
   - waiting_devices
   - held/staged/queued
4. Xác định có mất ownership hoặc race condition không.
5. KHÔNG patch.

---
EOF
      ;;
    retry)
      cat <<'EOF'
# Prompt cho Codex - Retry / Backoff

Chỉ sử dụng tài liệu điều tra bên dưới làm nguồn dữ liệu DUY NHẤT.

Đây là case nghi vấn retry / backoff.

Yêu cầu:
1. Liệt kê các retry theo timeline:
   - thời điểm
   - device
   - target
   - reason
2. Xác định retry có tiến triển không hay loop lại cùng lỗi.
3. Kiểm tra retry có rebuild packet không hay reuse packet cũ.
4. So sánh retry thành công và retry thất bại.
5. KHÔNG patch.

---
EOF
      ;;
    relay)
      cat <<'EOF'
# Prompt cho Codex - Relay / Routing

Chỉ sử dụng tài liệu điều tra bên dưới làm nguồn dữ liệu DUY NHẤT.

Đây là case nghi vấn relay / routing.

Yêu cầu:
1. Dựng flow sender → relay → receiver.
2. Xác định device nào không forward hoặc forward sai.
3. Kiểm tra:
   - relay decision
   - broadcast_rejected
   - peer_retry_due
   - source_device_unknown
   - target_no_longer_missing
4. Xác định route thất bại ở bước nào.
5. KHÔNG patch.

---
EOF
      ;;
    dtn)
      cat <<'EOF'
# Prompt cho Codex - Full DTN / Opaque Carrier

Chỉ sử dụng tài liệu điều tra bên dưới làm nguồn dữ liệu DUY NHẤT.

Đây là case nghi vấn Full DTN hoặc opaque carrier.

Yêu cầu:
1. Dựng flow sender -> carrier -> receiver theo batch_id/event_id.
2. Xác định batch có đi qua carrier không:
   - DTN hello
   - inv_have / inv_want
   - data / ack
   - opaque retry queued/skipped/retry_failed/send_complete/delete
3. Phân biệt rõ:
   - queued nghĩa là chỉ vào queue
   - send_complete nghĩa là đã ghi BLE xong
   - chunks=0 nghĩa là chưa bắt đầu write
4. Kiểm tra backlog/cooldown/retry loop:
   - enqueue_failed
   - no_non_origin_target
   - queued_no_write_attempt_timeout
   - broadcast_rejected
5. Kết luận root cause dựa trên log và code path, không suy đoán.
6. KHÔNG patch.

---
EOF
      ;;
    dedup)
      cat <<'EOF'
# Prompt cho Codex - Dedup / Reassembly

Chỉ sử dụng tài liệu điều tra bên dưới làm nguồn dữ liệu DUY NHẤT.

Đây là case nghi vấn protocol: dedup / reassembly.

Yêu cầu:
1. Xác định message/batch có nhận đủ chunk không.
2. Kiểm tra reassembly có complete không.
3. Kiểm tra message có bị dedup/drop sai không.
4. So sánh message render được và message bị drop.
5. Xác định lỗi nằm ở protocol hay state.
6. KHÔNG patch.

---
EOF
      ;;
    app)
      cat <<'EOF'
# Prompt cho Codex - App / Dispatch / UI

Chỉ sử dụng tài liệu điều tra bên dưới làm nguồn dữ liệu DUY NHẤT.

Đây là case nghi vấn application layer.

Yêu cầu:
1. Xác định message có vào APP layer không.
2. Kiểm tra:
   - APP batch extract
   - dispatch job
   - persistence
   - rendered message
3. So sánh message render thành công và message fail.
4. Xác định lỗi nằm ở dispatch, storage hay UI observer.
5. KHÔNG patch.

---
EOF
      ;;
    verify)
      cat <<'EOF'
# Prompt cho Codex - Verify sau patch

Chỉ sử dụng tài liệu điều tra bên dưới làm nguồn dữ liệu DUY NHẤT.

Dựa trên bundle mới sau khi patch:

1. Flow lỗi cũ còn xuất hiện không?
2. Có còn dấu hiệu cũ như:
   - chunk nhỏ bất thường
   - orphan timeout
   - stuck watchdog
   - retry loop
   - send failed
3. Flow mới có giống GOOD baseline không?
4. Có side effect mới không?
5. KHÔNG patch thêm nếu chưa có evidence rõ.

---
EOF
      ;;
    reset)
      cat <<'EOF'
# Prompt cho Codex - Reset điều tra

Dừng patch.

Reset toàn bộ giả định.

Chỉ sử dụng tài liệu điều tra bên dưới làm nguồn dữ liệu DUY NHẤT.

Yêu cầu:
1. Những giả định trước đó có thể sai ở đâu?
2. Layer nào cần điều tra lại?
3. Còn thiếu log/instrumentation nào?
4. Đề xuất hướng điều tra mới.
5. KHÔNG patch.

---
EOF
      ;;
    *)
      echo "Unsupported prompt type: $prompt_type" >&2
      echo "Supported: generic|dtn|mtu|queue|retry|relay|dedup|app|verify|reset" >&2
      exit 1
      ;;
  esac
}

run_codex_bundle() {
  require_analysis_cmds

  local prompt_type="generic"
  local label=""
  local mode="lite"

  while [ "${1:-}" != "" ]; do
    case "${1:-}" in
      --type)
        prompt_type="${2:-}"
        if [ -z "$prompt_type" ]; then
          echo "Usage: mesh_debug.sh codex-bundle [--type type] [--label label] [--full|--lite] [needle] [log_file ...]" >&2
          exit 1
        fi
        shift 2 || true
        ;;
      --label)
        label="${2:-}"
        if [ -z "$label" ]; then
          echo "Usage: mesh_debug.sh codex-bundle [--type type] [--label label] [--full|--lite] [needle] [log_file ...]" >&2
          exit 1
        fi
        shift 2 || true
        ;;
      --full)
        mode="full"
        shift || true
        ;;
      --lite)
        mode="lite"
        shift || true
        ;;
      --)
        shift || true
        break
        ;;
      *)
        break
        ;;
    esac
  done

  local maybe_needle="${1:-}"
  local needle=""
  if [ -n "$maybe_needle" ] && [ ! -f "$maybe_needle" ]; then
    needle="$maybe_needle"
    shift || true
  fi

  local files=()
  ensure_files_or_latest files "$@"
  mkdir -p "$ANALYSIS_DIR"

  local ts safe out
  ts="$(date +%Y%m%d_%H%M%S_%N)"
  safe="$(printf '%s' "${needle:-all}" | tr ' /:' '___' | tr -cd '[:alnum:]_.-')"
  [ -z "$safe" ] && safe="bundle"

  if [ -n "$label" ]; then
    local safe_label
    safe_label="$(printf '%s' "$label" | tr ' /:' '___' | tr -cd '[:alnum:]_.-')"
    [ -n "$safe_label" ] && safe="${safe_label}_${safe}"
  fi

  safe="codex_${prompt_type}_${mode}_${safe}"
  out="$ANALYSIS_DIR/${safe}_${ts}_$$.md"

  {
    codex_prompt_for_type "$prompt_type"

    echo "# Tài liệu điều tra BLE Mesh"
    echo
    echo "Thời điểm tạo: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "Chế độ: $mode"
    echo "Prompt type: $prompt_type"
    [ -n "$label" ] && echo "Nhãn: $label"
    [ -n "$needle" ] && echo "Token: $needle"
    echo
    echo "## Log nguồn"
    local f
    for f in "${files[@]}"; do
      echo "- $f"
    done

    if [ "$mode" = "full" ]; then
      echo
      echo "## Chỉ số nhanh"
      echo '```text'
      run_metrics "${files[@]}"
      echo '```'

      echo
      echo "## Tổng quan DTN"
      echo '```text'
      run_dtn_report_text "${files[@]}"
      echo '```'

      echo
      echo "## Phân loại layer nghi ngờ"
      echo '```text'
      run_classify "${files[@]}"
      echo '```'
    fi

    if [ -n "$needle" ]; then
      if [ "$mode" = "full" ]; then
        echo
        echo "## Trace thô"
        echo '```text'
        run_trace "$needle" "${files[@]}" || true
        echo '```'
      fi

      if [ -f "$DEEP_TRACE_PY" ]; then
        echo
        echo "## Deep trace"
        echo '```text'
        run_deep_trace "$needle" "${files[@]}" || true
        echo '```'
      fi

      if [ -f "$DIVERGENCE_PY" ]; then
        echo
        echo "## Divergence detector"
        echo '```text'
        run_divergence "$needle" "${files[@]}" || true
        echo '```'
      fi
    else
      echo
      echo "## Trạng thái token"
      echo "Chưa cung cấp token. Tài liệu này chỉ chứa dữ liệu quét tổng quan."
    fi
  } > "$out"

  echo "$out"
}

run_relay_db_report() {
  ensure_relay_db_report_script

  if [ $# -lt 1 ]; then
    echo "Usage: mesh_debug.sh relay-db-report <serial> [--output-dir dir] [--message-limit N]" >&2
    exit 1
  fi

  local py
  py="$(require_python)"
  "$py" "$RELAY_DB_REPORT_PY" "$@"
}


main() {
  local cmd="${1:-}"
  case "$cmd" in
    devices|build|install|reset-db|check-ids|pull-activity) require_cmd adb ;;
  esac
  case "$cmd" in
    devices)
      adb devices
      ;;
    build)
      local flavor="${2:-emergency}"
      case "$flavor" in
        emergency|official|official-debug)
          build_one "$flavor"
          ;;
        both)
          build_one emergency
          build_one official
          ;;
        official-release)
          build_one "$flavor"
          ;;
        *)
          usage
          exit 1
          ;;
      esac
      ;;
    install)
      local flavor="${2:-emergency}"
      if [ "$flavor" != "emergency" ] && [ "$flavor" != "official" ] && [ "$flavor" != "official-debug" ] && [ "$flavor" != "official-release" ]; then
        echo "install supports: emergency|official|official-debug|official-release" >&2
        exit 1
      fi
      shift 2 || true
      local serials=("$@")
      if [ "${#serials[@]}" -eq 0 ]; then
        mapfile -t serials < <(connected_devices)
      fi
      if [ "${#serials[@]}" -eq 0 ]; then
        echo "No connected devices." >&2
        exit 1
      fi
      local s
      for s in "${serials[@]}"; do
        install_one_device "$flavor" "$s"
      done
      ;;
    reset-db)
      shift || true
      local mode="official"
      if [ "${1:-}" = "auto" ] || [ "${1:-}" = "emergency" ] || [ "${1:-}" = "official" ]; then
        mode="$1"
        shift || true
      fi
      reset_db_devices "$mode" "$@"
      ;;
    check-ids)
      shift || true
      local mode="official"
      if [ "${1:-}" = "auto" ] || [ "${1:-}" = "emergency" ] || [ "${1:-}" = "official" ]; then
        mode="$1"
        shift || true
      fi
      check_ids_devices "$mode" "$@"
      ;;
    release-smoke)
      release_smoke
      ;;
    pull-activity)
      local mode="${2:-auto}"
      if [ "$mode" != "auto" ] && [ "$mode" != "emergency" ] && [ "$mode" != "official" ]; then
        echo "pull-activity supports: auto|emergency|official" >&2
        exit 1
      fi
      shift 2 || true
      local serials=("$@")
      if [ "${#serials[@]}" -eq 0 ]; then
        mapfile -t serials < <(connected_devices)
      fi
      if [ "${#serials[@]}" -eq 0 ]; then
        echo "No connected devices." >&2
        exit 1
      fi
      local pulled=0
      local s
      for s in "${serials[@]}"; do
        if pull_latest_activity_for_device "$mode" "$s"; then
          pulled=$((pulled + 1))
        fi
      done
      if [ "$pulled" -eq 0 ]; then
        echo "pull-activity failed: no logs pulled from any device." >&2
        exit 1
      fi
      ;;
    latest)
      shift || true
      latest_logs "${1:-3}"
      ;;
    metrics)
      shift || true
      run_metrics "$@"
      ;;
    dtn)
      shift || true
      run_dtn_report_text "$@"
      ;;
    dtn-md)
      shift || true
      run_dtn_report_md "$@"
      ;;
    classify)
      shift || true
      run_classify "$@"
      ;;
    trace)
      shift || true
      run_trace "$@"
      ;;
    deep-trace)
      shift || true
      run_deep_trace "$@"
      ;;
    deep-trace-md)
      shift || true
      run_deep_trace_md "$@"
      ;;
    divergence)
      shift || true
      run_divergence "$@"
      ;;
    divergence-md)
      shift || true
      run_divergence_md "$@"
      ;;
    missing)
      shift || true
      run_missing_events "$@"
      ;;
    missing-md)
      shift || true
      run_missing_events_md "$@"
      ;;
    failed-sending)
      shift || true
      run_failed_sending "$@"
      ;;
    failed-sending-md)
      shift || true
      run_failed_sending_md "$@"
      ;;
    bundle)
      shift || true
      run_bundle "$@"
      ;;
    codex-bundle)
      shift || true
      run_codex_bundle "$@"
      ;;
    relay-db-report)
      shift || true
      run_relay_db_report "$@"
      ;;
    round)
      shift || true
      run_round "${1:-1}"
      ;;
    select-tokens) shift; run_select_tokens "$@" ;;
    *)
      usage
      exit 1
      ;;
  esac
}

main "$@"
