#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

say() {
  printf '\n==> %s\n' "$1"
}

run_gradle() {
  say "$1"
  shift
  ./gradlew --no-parallel "$@"
}

say "M3E12-T6 focused verification"
printf 'repo: %s\n' "$ROOT_DIR"

if command -v git >/dev/null 2>&1 && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  say "Checking patch whitespace"
  git diff --check
fi

run_gradle "Transport: proactive WANT, per-session dedupe, exact-ID DATA, diagnostics" \
  :transport:testDebugUnitTest \
  --tests app.peermesh.transport.ble.dtn.DtnSyncCoreTest \
  --tests app.peermesh.transport.ble.dtn.DtnControlRouterTest \
  --tests app.peermesh.transport.ble.dtn.DtnControlCodecTest

run_gradle "Data: durable exact-ID lookup, ingest/defer, repair scheduling, pending replay" \
  :data:testDebugUnitTest \
  --tests app.peermesh.data.dtn.RoomDtnInventoryStoreTest \
  --tests app.peermesh.data.ingest.EventIngestorM2C4AContractTest \
  --tests app.peermesh.data.relay.RelaySyncSchedulerTest \
  --tests app.peermesh.data.map.replay.RoomTargetPendingReplayCoordinatorTest \
  --tests app.peermesh.data.map.processing.PendingMapActionPersistenceContractTest

run_gradle "App: post-commit transport/refresh boundary and repair diagnostics grouping" \
  :app:testEmergencyDebugUnitTest \
  --tests app.peermesh.LocalMapPostCommitTransportTest \
  --tests app.peermesh.RemoteMapIngestRefreshM3E11Test \
  --tests app.peermesh.MapActivityLogLabelGroupTest

run_gradle "Compile: production transport" \
  :transport:compileDebugKotlin

run_gradle "Compile: emergency app graph" \
  :app:compileEmergencyDebugKotlin

if command -v git >/dev/null 2>&1 && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  say "Re-checking patch whitespace"
  git diff --check
fi

say "M3E12-T6 verification PASSED"
