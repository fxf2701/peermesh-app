#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Iterable

EVENT_RULES = [
    ("gatt_error", [r"status=133", r"gatt", r"disconnect", r"mtu"]),
    ("connect_cap", [r"BLE connect cap blocked"]),
    ("queue_timeout", [r"queued_no_progress_timeout", r"queue timeout", r"stuck queue"]),
    ("send_fail", [r"BLE batch send failed", r"write fail", r"send failed"]),
    ("send_ok", [r"write_ok", r"send ok", r"send success", r"batch send completed"]),
    ("enqueue", [r"enqueue", r"queued"]),
    ("recv", [r"recv", r"received", r"notification", r"notify"]),
    ("reassembly_complete", [r"reassembly.*complete", r"status=complete", r"complete reassembly"]),
    ("reassembly_progress", [r"reassembly", r"partial buffer", r"chunk", r"received=\d+/\d+"]),
    ("dedup_drop", [r"dedup", r"duplicate", r"drop reason=duplicate", r"result=drop"]),
    ("retry", [r"retry", r"attempt=", r"resend"]),
    ("dispatch_fail", [r"APP dispatch job failed", r"dispatch failed"]),
    ("dispatch_ok", [r"APP dispatch job completed", r"dispatch completed", r"rendered"]),
]

STAGE_ORDER = [
    "enqueue",
    "send_ok",
    "recv",
    "reassembly_progress",
    "reassembly_complete",
    "dispatch_ok",
]

STAGE_SCORE = {name: i + 1 for i, name in enumerate(STAGE_ORDER)}

LAYER_MAP = {
    "gatt_error": "BLE_LINK",
    "connect_cap": "BLE_LINK",
    "queue_timeout": "TRANSPORT_OR_STATE",
    "send_fail": "TRANSPORT_OR_STATE",
    "send_ok": "TRANSPORT",
    "enqueue": "TRANSPORT",
    "recv": "TRANSPORT_OR_PROTOCOL",
    "reassembly_progress": "PROTOCOL",
    "reassembly_complete": "PROTOCOL",
    "dedup_drop": "PROTOCOL_OR_STATE",
    "retry": "TRANSPORT_OR_STATE",
    "dispatch_fail": "APP_DISPATCH",
    "dispatch_ok": "APP_DISPATCH",
}

COMMON_TOKEN_PATTERNS = [
    r"msgId=([A-Za-z0-9._:-]+)",
    r"messageId=([A-Za-z0-9._:-]+)",
    r"hash=([A-Za-z0-9._:-]+)",
    r"#(\d+)",
]


@dataclass
class Event:
    file: str
    device: str
    line_no: int
    category: str
    layer: str
    score: int
    text: str


@dataclass
class Summary:
    token: str
    files: list[str]
    total_matches: int
    categories: dict[str, int]
    layers: dict[str, int]
    by_device: dict[str, dict]
    likely_divergence: str
    likely_layer: str
    reasoning: list[str]


def detect_device(file_path: str) -> str:
    name = os.path.basename(file_path)
    m = re.search(r"device-([A-Za-z0-9]+)_", name)
    if m:
        return m.group(1)
    return Path(file_path).stem


def classify_line(text: str) -> str | None:
    low = text.lower()
    for category, patterns in EVENT_RULES:
        for pat in patterns:
            if re.search(pat.lower(), low):
                return category
    return None


def extract_candidate_tokens(text: str) -> list[str]:
    found: list[str] = []
    for pat in COMMON_TOKEN_PATTERNS:
        for m in re.finditer(pat, text):
            token = m.group(1)
            if token:
                found.append(token)
    return found


def search_events(files: Iterable[str], needle: str) -> list[Event]:
    events: list[Event] = []
    needle_low = needle.lower()
    for file_path in files:
        device = detect_device(file_path)
        with open(file_path, "r", encoding="utf-8", errors="replace") as f:
            for i, line in enumerate(f, start=1):
                if needle_low not in line.lower():
                    continue
                category = classify_line(line) or "uncategorized"
                layer = LAYER_MAP.get(category, "UNKNOWN")
                score = STAGE_SCORE.get(category, 0)
                events.append(Event(
                    file=file_path,
                    device=device,
                    line_no=i,
                    category=category,
                    layer=layer,
                    score=score,
                    text=line.rstrip("\n"),
                ))
    return events


def summarize(events: list[Event], token: str) -> Summary:
    categories: dict[str, int] = defaultdict(int)
    layers: dict[str, int] = defaultdict(int)
    by_device: dict[str, dict] = {}
    reasoning: list[str] = []

    for ev in events:
        categories[ev.category] += 1
        layers[ev.layer] += 1

    grouped: dict[str, list[Event]] = defaultdict(list)
    for ev in events:
        grouped[ev.device].append(ev)

    for device, evs in grouped.items():
        evs = sorted(evs, key=lambda e: (e.score, e.line_no))
        max_score = max((e.score for e in evs), default=0)
        stage = next((name for name, score in STAGE_SCORE.items() if score == max_score), "uncategorized")
        by_device[device] = {
            "max_stage": stage,
            "max_score": max_score,
            "categories": [e.category for e in evs],
            "line_range": [evs[0].line_no, evs[-1].line_no] if evs else None,
            "last_event": evs[-1].text if evs else None,
        }

    if not grouped:
        likely_divergence = "No matching token found in provided logs."
        likely_layer = "UNKNOWN"
    else:
        highest = max(v["max_score"] for v in by_device.values())
        lowest = min(v["max_score"] for v in by_device.values())
        highest_devices = [k for k, v in by_device.items() if v["max_score"] == highest]
        lowest_devices = [k for k, v in by_device.items() if v["max_score"] == lowest]

        if highest == lowest:
            likely_divergence = "No strong stage divergence across devices; inspect line-level differences or missing devices."
        else:
            likely_divergence = (
                f"Token {token} progresses further on {', '.join(highest_devices)} "
                f"than on {', '.join(lowest_devices)}."
            )
            reasoning.append(
                f"Highest stage score={highest} on {', '.join(highest_devices)}, lowest score={lowest} on {', '.join(lowest_devices)}."
            )

        top_layer = max(layers.items(), key=lambda kv: kv[1])[0] if layers else "UNKNOWN"
        likely_layer = top_layer

        if categories.get("gatt_error"):
            reasoning.append("GATT/status related lines found; BLE link may be involved.")
        if categories.get("queue_timeout") or categories.get("send_fail"):
            reasoning.append("Queue/send failure lines found; transport or state handling may be the break point.")
        if categories.get("dedup_drop"):
            reasoning.append("Dedup/drop lines found; protocol or state filtering may be discarding a valid message.")
        if categories.get("dispatch_fail"):
            reasoning.append("App dispatch failure lines found; message may reach app layer but fail to render/process.")

    return Summary(
        token=token,
        files=sorted({e.file for e in events}),
        total_matches=len(events),
        categories=dict(sorted(categories.items())),
        layers=dict(sorted(layers.items())),
        by_device=by_device,
        likely_divergence=likely_divergence,
        likely_layer=likely_layer,
        reasoning=reasoning,
    )


def to_markdown(summary: Summary, events: list[Event], context_before: int = 0) -> str:
    lines: list[str] = []
    lines.append(f"# Deep Trace Report: `{summary.token}`")
    lines.append("")
    lines.append(f"Total matches: **{summary.total_matches}**")
    lines.append(f"Likely layer: **{summary.likely_layer}**")
    lines.append("")
    lines.append("## Divergence")
    lines.append(summary.likely_divergence)
    lines.append("")
    if summary.reasoning:
        lines.append("## Reasoning")
        for item in summary.reasoning:
            lines.append(f"- {item}")
        lines.append("")

    lines.append("## By device")
    for device, info in sorted(summary.by_device.items()):
        lines.append(f"### Device {device}")
        lines.append(f"- Max stage: `{info['max_stage']}` (score={info['max_score']})")
        if info.get("line_range"):
            lines.append(f"- Lines: {info['line_range'][0]}-{info['line_range'][1]}")
        if info.get("last_event"):
            lines.append(f"- Last event: `{info['last_event'][:240]}`")
        cats = info.get("categories") or []
        if cats:
            lines.append(f"- Categories: {', '.join(cats)}")
        lines.append("")

    lines.append("## Category counts")
    for k, v in summary.categories.items():
        lines.append(f"- `{k}`: {v}")
    lines.append("")

    lines.append("## Events")
    for ev in events:
        lines.append(f"### {Path(ev.file).name}:{ev.line_no} [{ev.device}] {ev.category} / {ev.layer}")
        lines.append("```text")
        lines.append(ev.text)
        lines.append("```")
        lines.append("")

    lines.append("## Codex prompt")
    lines.append("```text")
    lines.append("Use the deep trace report below.")
    lines.append("1. Summarize the runtime flow for this token.")
    lines.append("2. Identify the last confirmed step and the first missing step per device.")
    lines.append("3. Rank the top 3 root causes.")
    lines.append("4. Show supporting and contradicting evidence.")
    lines.append("5. Do not patch yet.")
    lines.append("```")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Deep trace BLE mesh tokens across logs.")
    parser.add_argument("needle", help="msgId / hash / token / #123 to trace")
    parser.add_argument("files", nargs="+", help="log files to inspect")
    parser.add_argument("--format", choices=["markdown", "json"], default="markdown")
    parser.add_argument("--output", help="optional output file path")
    args = parser.parse_args()

    events = search_events(args.files, args.needle)
    summary = summarize(events, args.needle)

    if args.format == "json":
        payload = {
            "summary": asdict(summary),
            "events": [asdict(e) for e in events],
        }
        text = json.dumps(payload, ensure_ascii=False, indent=2)
    else:
        text = to_markdown(summary, events)

    if args.output:
        out_path = Path(args.output)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)
        if not text.endswith("\n"):
            sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
