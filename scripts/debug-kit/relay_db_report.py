#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import shutil
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
DEFAULT_OUTPUT_DIR = REPO_ROOT / "logs" / "analysis"
APP_ID = "app.peermesh"
APP_DATA_DIR = f"/data/user/0/{APP_ID}"
DB_BASENAME = "peer_mesh.db"


def run(cmd: list[str], *, capture_output: bool = True, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        cmd,
        text=True,
        capture_output=capture_output,
        check=check,
    )


def require_host_command(name: str) -> None:
    if shutil.which(name):
        return
    print(f"Missing required host command: {name}", file=sys.stderr)
    sys.exit(1)


def utc_iso_from_epoch(value: Any) -> str | None:
    if value is None:
        return None
    try:
        epoch = int(value)
    except (TypeError, ValueError):
        return None
    if epoch <= 0:
        return None
    # App tables mix Unix seconds, Unix milliseconds, and logical clocks.
    # Convert only plausible Unix timestamps; otherwise return the raw scalar.
    if epoch > 10_000_000_000_000:
        return f"raw:{epoch}"
    if epoch > 10_000_000_000:
        epoch = epoch / 1000.0
    try:
        return datetime.fromtimestamp(epoch, tz=timezone.utc).isoformat()
    except (OverflowError, OSError, ValueError):
        return f"raw:{value}"


def sanitize_name(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "-", value).strip("-") or "device"


def adb_tar_export(serial: str, destination: Path) -> None:
    tar_cmd = [
        "adb",
        "-s",
        serial,
        "exec-out",
        "run-as",
        APP_ID,
        "sh",
        "-c",
        (
            f"cd {APP_DATA_DIR} && "
            "tar -cf - "
            f"databases/{DB_BASENAME} "
            f"databases/{DB_BASENAME}-wal "
            f"databases/{DB_BASENAME}-shm "
            "shared_prefs/peer_mesh_prefs.xml"
        ),
    ]
    with destination.open("wb") as fh:
        proc = subprocess.run(tar_cmd, stdout=fh, stderr=subprocess.PIPE, check=False)
    if proc.returncode != 0:
        err = proc.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"ADB export failed for {serial}: {err or f'exit {proc.returncode}'}")


def extract_tar(tar_path: Path, output_dir: Path) -> None:
    with tarfile.open(tar_path) as tar:
        tar.extractall(output_dir)


def parse_prefs_xml(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    text = path.read_text(encoding="utf-8", errors="replace")
    entries: dict[str, str] = {}
    for match in re.finditer(r'<string name="([^"]+)">(.*?)</string>', text, re.DOTALL):
        entries[match.group(1)] = match.group(2)
    for match in re.finditer(r'<long name="([^"]+)" value="([^"]+)" ?/?>', text):
        entries[match.group(1)] = match.group(2)
    for match in re.finditer(r'<boolean name="([^"]+)" value="([^"]+)" ?/?>', text):
        entries[match.group(1)] = match.group(2)
    return entries


def normalize_pref_value(key: str, value: str) -> Any:
    if not key.endswith("_json"):
        return value
    try:
        return json.loads(value)
    except json.JSONDecodeError:
        return value


def query_rows(conn: sqlite3.Connection, sql: str, params: tuple[Any, ...] = ()) -> list[dict[str, Any]]:
    cur = conn.execute(sql, params)
    cols = [item[0] for item in cur.description]
    return [dict(zip(cols, row)) for row in cur.fetchall()]


def table_exists(conn: sqlite3.Connection, table_name: str) -> bool:
    rows = query_rows(
        conn,
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
        (table_name,),
    )
    return bool(rows)


def scalar_int(conn: sqlite3.Connection, sql: str, params: tuple[Any, ...] = ()) -> int:
    rows = query_rows(conn, sql, params)
    if not rows:
        return 0
    first = next(iter(rows[0].values()), 0)
    try:
        return int(first)
    except (TypeError, ValueError):
        return 0


def collect_report_data(db_path: Path, prefs_path: Path, message_limit: int) -> dict[str, Any]:
    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        user_version = scalar_int(conn, "PRAGMA user_version")
        has_deleted_events = table_exists(conn, "deleted_events")
        messages = query_rows(
            conn,
            """
            SELECT gid, message_id, text, status, delivery_status, created_t, updated_t, actor_did
            FROM chat_messages
            ORDER BY created_t DESC
            LIMIT ?
            """,
            (message_limit,),
        )
        events = query_rows(
            conn,
            """
            SELECT event_id, gid, type, source, created_t, actor_did, inserted_at,
                   substr(payload_json, 1, 220) AS payload_preview
            FROM events
            ORDER BY created_t DESC
            LIMIT ?
            """,
            (message_limit,),
        )
        outbound_batches = query_rows(
            conn,
            """
            SELECT batch_id, gid, status, lane, priority, attempt_count, last_error,
                   created_at, updated_at, sent_at, event_count,
                   first_event_created_t, last_event_created_t, source
            FROM outbound_batch
            ORDER BY created_at DESC
            LIMIT 50
            """,
        )
        relay_push_state = query_rows(
            conn,
            """
            SELECT relay_url, last_push_at, last_push_event_id, updated_at
            FROM relay_push_state
            ORDER BY updated_at DESC
            """,
        )
        relay_cursors = query_rows(
            conn,
            """
            SELECT relay_url, gid, cursor, updated_at
            FROM relay_cursors
            ORDER BY updated_at DESC
            LIMIT 50
            """,
        )
        counts = {
            "chat_messages": query_rows(conn, "SELECT COUNT(*) AS value FROM chat_messages")[0]["value"],
            "events": query_rows(conn, "SELECT COUNT(*) AS value FROM events")[0]["value"],
            "outbound_batch": query_rows(conn, "SELECT COUNT(*) AS value FROM outbound_batch")[0]["value"],
            "relay_push_state": query_rows(conn, "SELECT COUNT(*) AS value FROM relay_push_state")[0]["value"],
            "relay_cursors": query_rows(conn, "SELECT COUNT(*) AS value FROM relay_cursors")[0]["value"],
        }
        delete_analysis = collect_delete_analysis(conn, message_limit, has_deleted_events)
    finally:
        conn.close()

    for row in messages:
        row["created_t_iso"] = utc_iso_from_epoch(row.get("created_t"))
        row["updated_t_iso"] = utc_iso_from_epoch(row.get("updated_t"))
    for row in events:
        row["created_t_iso"] = utc_iso_from_epoch(row.get("created_t"))
        row["inserted_at_iso"] = utc_iso_from_epoch(row.get("inserted_at"))
    for row in outbound_batches:
        for key in ("created_at", "updated_at", "sent_at", "first_event_created_t", "last_event_created_t"):
            row[f"{key}_iso"] = utc_iso_from_epoch(row.get(key))
    for row in relay_push_state:
        row["last_push_at_iso"] = utc_iso_from_epoch(row.get("last_push_at"))
        row["updated_at_iso"] = utc_iso_from_epoch(row.get("updated_at"))
    for row in relay_cursors:
        row["updated_at_iso"] = utc_iso_from_epoch(row.get("updated_at"))

    prefs = parse_prefs_xml(prefs_path)
    interesting_prefs = {
        key: normalize_pref_value(key, prefs.get(key))
        for key in [
            "managed_relay_access_binding_json",
            "managed_relay_last_bootstrap_error_json",
            "managed_relay_last_session_open_error_json",
            "known_relays_json",
            "relay_blocked_relays_json",
            "relay_last_attempt_ms",
            "relay_last_success_ms",
            "relay_last_error",
            "cloud_sync_user_enabled",
            "ble_mesh_user_enabled",
        ]
        if key in prefs
    }
    if "relay_last_attempt_ms" in interesting_prefs:
        interesting_prefs["relay_last_attempt_ms_iso"] = utc_iso_from_epoch(interesting_prefs["relay_last_attempt_ms"])
    if "relay_last_success_ms" in interesting_prefs:
        interesting_prefs["relay_last_success_ms_iso"] = utc_iso_from_epoch(interesting_prefs["relay_last_success_ms"])

    return {
        "schema": {
            "user_version": user_version,
            "has_deleted_events_table": has_deleted_events,
        },
        "counts": counts,
        "messages": messages,
        "events": events,
        "outbound_batches": outbound_batches,
        "relay_push_state": relay_push_state,
        "relay_cursors": relay_cursors,
        "prefs": interesting_prefs,
        "delete_analysis": delete_analysis,
    }


def collect_delete_analysis(
    conn: sqlite3.Connection,
    message_limit: int,
    has_deleted_events: bool,
) -> dict[str, Any]:
    deleted_messages = query_rows(
        conn,
        """
        SELECT gid, message_id, text, status, delivery_status, actor_did,
               created_t, updated_t, last_event_id, last_event_t, last_event_actor
        FROM chat_messages
        WHERE status = 'deleted'
        ORDER BY updated_t DESC, created_t DESC
        LIMIT ?
        """,
        (message_limit,),
    )
    chat_del_events = query_rows(
        conn,
        """
        SELECT event_id, gid, type, source, created_t, actor_did, ref_target,
               substr(payload_json, 1, 220) AS payload_preview
        FROM events
        WHERE type = 'CHAT.DEL'
        ORDER BY created_t DESC
        LIMIT ?
        """,
        (message_limit,),
    )
    tombstones: list[dict[str, Any]]
    if has_deleted_events:
        tombstones = query_rows(
            conn,
            """
            SELECT gid, target_event_id, delete_event_id, actor_did,
                   deleted_at, expires_at, target_created_t, delete_created_t,
                   last_event_t, last_event_lt, last_event_actor
            FROM deleted_events
            ORDER BY deleted_at DESC
            LIMIT ?
            """,
            (message_limit,),
        )
    else:
        tombstones = []

    sql_checks: list[dict[str, Any]] = []
    anomalies: list[str] = []
    tombstones_by_target = {
        (row["gid"], row["target_event_id"]): row
        for row in tombstones
    }
    for row in deleted_messages:
        gid = row["gid"]
        message_id = row["message_id"]
        original_event_rows = query_rows(
            conn,
            """
            SELECT event_id, type, source, created_t, actor_did
            FROM events
            WHERE gid = ? AND event_id = ?
            LIMIT 1
            """,
            (gid, message_id),
        )
        original_event = original_event_rows[0] if original_event_rows else None
        tombstone = tombstones_by_target.get((gid, message_id))
        check = {
            "gid": gid,
            "message_id": message_id,
            "text": row.get("text"),
            "status": row.get("status"),
            "last_event_id": row.get("last_event_id"),
            "original_event_present": original_event is not None,
            "original_event_type": original_event.get("type") if original_event else None,
            "tombstone_present": tombstone is not None,
            "delete_event_id": tombstone.get("delete_event_id") if tombstone else None,
            "tombstone_expires_at": tombstone.get("expires_at") if tombstone else None,
        }
        sql_checks.append(check)
        if row.get("text") != "Deleted":
            anomalies.append(
                f"deleted message `{message_id}` in gid `{gid}` has text `{row.get('text')}` instead of `Deleted`"
            )
        if row.get("status") != "deleted":
            anomalies.append(
                f"deleted message `{message_id}` in gid `{gid}` has status `{row.get('status')}`"
            )
        if original_event is not None:
            anomalies.append(
                f"deleted message `{message_id}` in gid `{gid}` still has original plaintext event in `events`"
            )
        if has_deleted_events and tombstone is None:
            anomalies.append(
                f"deleted message `{message_id}` in gid `{gid}` has no matching tombstone in `deleted_events`"
            )

    if not has_deleted_events:
        anomalies.append("`deleted_events` table is missing in this DB snapshot")

    for row in deleted_messages:
        row["created_t_iso"] = utc_iso_from_epoch(row.get("created_t"))
        row["updated_t_iso"] = utc_iso_from_epoch(row.get("updated_t"))
        row["last_event_t_iso"] = utc_iso_from_epoch(row.get("last_event_t"))
    for row in chat_del_events:
        row["created_t_iso"] = utc_iso_from_epoch(row.get("created_t"))
    for row in tombstones:
        row["deleted_at_iso"] = utc_iso_from_epoch(row.get("deleted_at"))
        row["expires_at_iso"] = utc_iso_from_epoch(row.get("expires_at"))
        row["target_created_t_iso"] = utc_iso_from_epoch(row.get("target_created_t"))
        row["delete_created_t_iso"] = utc_iso_from_epoch(row.get("delete_created_t"))
        row["last_event_t_iso"] = utc_iso_from_epoch(row.get("last_event_t"))
    for row in sql_checks:
        row["tombstone_expires_at_iso"] = utc_iso_from_epoch(row.get("tombstone_expires_at"))

    return {
        "deleted_message_count": scalar_int(
            conn,
            "SELECT COUNT(*) AS value FROM chat_messages WHERE status = 'deleted'",
        ),
        "chat_del_event_count": scalar_int(
            conn,
            "SELECT COUNT(*) AS value FROM events WHERE type = 'CHAT.DEL'",
        ),
        "tombstone_count": scalar_int(
            conn,
            "SELECT COUNT(*) AS value FROM deleted_events",
        ) if has_deleted_events else 0,
        "deleted_messages": deleted_messages,
        "chat_del_events": chat_del_events,
        "tombstones": tombstones,
        "sql_checks": sql_checks,
        "anomalies": anomalies,
    }


def section_table(title: str, rows: list[dict[str, Any]], keys: list[str]) -> list[str]:
    lines = [f"## {title}", ""]
    if not rows:
        lines.append("_No rows_")
        lines.append("")
        return lines
    lines.append("| " + " | ".join(keys) + " |")
    lines.append("| " + " | ".join(["---"] * len(keys)) + " |")
    for row in rows:
        values = []
        for key in keys:
            value = row.get(key)
            if value is None:
                values.append("")
            else:
                text = str(value).replace("\n", "\\n").replace("|", "\\|")
                values.append(text)
        lines.append("| " + " | ".join(values) + " |")
    lines.append("")
    return lines


def build_markdown(serial: str, report_data: dict[str, Any], artifact_dir: Path) -> str:
    lines: list[str] = []
    lines.append(f"# Relay DB Report: `{serial}`")
    lines.append("")
    lines.append(f"- Generated at: `{datetime.now(timezone.utc).isoformat()}`")
    lines.append(f"- Artifact dir: `{artifact_dir}`")
    lines.append("")
    lines.append("## Schema")
    lines.append("")
    lines.append(f"- `user_version`: `{report_data['schema']['user_version']}`")
    lines.append(f"- `deleted_events` table present: `{report_data['schema']['has_deleted_events_table']}`")
    lines.append("")
    lines.append("## Counts")
    lines.append("")
    for key, value in report_data["counts"].items():
        lines.append(f"- `{key}`: `{value}`")
    lines.append("")
    lines.append("## Preferences")
    lines.append("")
    if report_data["prefs"]:
        for key, value in report_data["prefs"].items():
            if isinstance(value, (dict, list)):
                pretty = json.dumps(value, ensure_ascii=True, separators=(",", ":"), sort_keys=True)
                lines.append(f"- `{key}`: `{pretty}`")
            else:
                lines.append(f"- `{key}`: `{value}`")
    else:
        lines.append("_No interesting prefs found_")
    lines.append("")
    lines.extend(
        section_table(
            "Latest Chat Messages",
            report_data["messages"],
            ["gid", "message_id", "text", "status", "delivery_status", "created_t_iso", "actor_did"],
        )
    )
    lines.extend(
        section_table(
            "Latest Events",
            report_data["events"],
            ["event_id", "gid", "type", "source", "created_t_iso", "actor_did", "payload_preview"],
        )
    )
    lines.extend(
        section_table(
            "Outbound Batch",
            report_data["outbound_batches"],
            [
                "batch_id",
                "gid",
                "status",
                "source",
                "lane",
                "attempt_count",
                "event_count",
                "created_at_iso",
                "updated_at_iso",
                "sent_at_iso",
                "last_error",
            ],
        )
    )
    lines.extend(
        section_table(
            "Relay Push State",
            report_data["relay_push_state"],
            ["relay_url", "last_push_at_iso", "last_push_event_id", "updated_at_iso"],
        )
    )
    lines.extend(
        section_table(
            "Relay Cursors",
            report_data["relay_cursors"],
            ["relay_url", "gid", "cursor", "updated_at_iso"],
        )
    )
    delete_analysis = report_data["delete_analysis"]
    lines.append("## Delete Message Analysis")
    lines.append("")
    lines.append(f"- `deleted_message_count`: `{delete_analysis['deleted_message_count']}`")
    lines.append(f"- `chat_del_event_count`: `{delete_analysis['chat_del_event_count']}`")
    lines.append(f"- `tombstone_count`: `{delete_analysis['tombstone_count']}`")
    lines.append("")
    if delete_analysis["anomalies"]:
        lines.append("### Delete Anomalies")
        lines.append("")
        for item in delete_analysis["anomalies"]:
            lines.append(f"- {item}")
        lines.append("")
    else:
        lines.append("### Delete Anomalies")
        lines.append("")
        lines.append("_No delete anomalies detected from SQL checks_")
        lines.append("")
    lines.extend(
        section_table(
            "Deleted Chat Messages",
            delete_analysis["deleted_messages"],
            [
                "gid",
                "message_id",
                "text",
                "status",
                "delivery_status",
                "updated_t_iso",
                "last_event_id",
                "last_event_actor",
            ],
        )
    )
    lines.extend(
        section_table(
            "Latest CHAT.DEL Events",
            delete_analysis["chat_del_events"],
            ["event_id", "gid", "source", "created_t_iso", "actor_did", "ref_target", "payload_preview"],
        )
    )
    lines.extend(
        section_table(
            "Deleted Event Tombstones",
            delete_analysis["tombstones"],
            [
                "gid",
                "target_event_id",
                "delete_event_id",
                "actor_did",
                "deleted_at_iso",
                "expires_at_iso",
                "last_event_actor",
            ],
        )
    )
    lines.extend(
        section_table(
            "Delete SQL Checks",
            delete_analysis["sql_checks"],
            [
                "gid",
                "message_id",
                "text",
                "status",
                "last_event_id",
                "original_event_present",
                "original_event_type",
                "tombstone_present",
                "delete_event_id",
                "tombstone_expires_at_iso",
            ],
        )
    )
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Export Android app relay DB artifacts for one device and build a debug report."
    )
    parser.add_argument("serial", help="ADB serial of the target device")
    parser.add_argument(
        "--output-dir",
        default=str(DEFAULT_OUTPUT_DIR),
        help="Directory where report artifacts will be written (default: logs/analysis)",
    )
    parser.add_argument(
        "--message-limit",
        type=int,
        default=20,
        help="Number of latest chat_messages/events rows to include",
    )
    args = parser.parse_args()

    require_host_command("adb")
    output_root = Path(args.output_dir).resolve()
    output_root.mkdir(parents=True, exist_ok=True)

    stamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
    artifact_dir = output_root / f"relay-db-report_{sanitize_name(args.serial)}_{stamp}"
    artifact_dir.mkdir(parents=True, exist_ok=True)

    tar_path = artifact_dir / "app_data.tar"
    try:
        adb_tar_export(args.serial, tar_path)
        extract_tar(tar_path, artifact_dir)
        db_path = artifact_dir / "databases" / DB_BASENAME
        prefs_path = artifact_dir / "shared_prefs" / "peer_mesh_prefs.xml"
        report_data = collect_report_data(db_path, prefs_path, args.message_limit)
    except Exception as exc:  # noqa: BLE001
        print(f"Failed to generate report: {exc}", file=sys.stderr)
        return 1

    report_json_path = artifact_dir / "report.json"
    report_json_path.write_text(
        json.dumps(
            {
                "serial": args.serial,
                "generated_at": datetime.now(timezone.utc).isoformat(),
                "artifact_dir": str(artifact_dir),
                "report": report_data,
            },
            ensure_ascii=True,
            indent=2,
        ),
        encoding="utf-8",
    )

    report_md_path = artifact_dir / "report.md"
    report_md_path.write_text(build_markdown(args.serial, report_data, artifact_dir), encoding="utf-8")

    print(f"Report written to: {report_md_path}")
    print(f"JSON written to:   {report_json_path}")
    print(f"Artifacts kept in: {artifact_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
