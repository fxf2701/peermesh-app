#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple


STAGE_ORDER = [
    "matched",
    "enqueue",
    "send_start",
    "send_ok",
    "recv",
    "reassembly_progress",
    "reassembly_complete",
    "dedup_accept",
    "dispatch_ok",
]

STAGE_RANK = {name: i for i, name in enumerate(STAGE_ORDER)}


EVENT_PATTERNS: List[Tuple[str, List[str]]] = [
    ("enqueue", [
        "enqueue",
        "queued",
        "queued_no_progress_timeout",
    ]),
    ("send_start", [
        "write_start",
        "send start",
        "sending chunk",
        "BLE batch send",
    ]),
    ("send_ok", [
        "write_ok",
        "send ok",
        "batch send success",
        "sent successfully",
    ]),
    ("recv", [
        "recv",
        "received",
        "onCharacteristicChanged",
    ]),
    ("reassembly_progress", [
        "reassembly",
        "received=",
        "partial buffer",
    ]),
    ("reassembly_complete", [
        "reassembly complete",
        "status=complete",
        "message complete",
        "reassembled successfully",
    ]),
    ("dedup_accept", [
        "dedup accept",
        "result=accept",
    ]),
    ("dispatch_ok", [
        "APP dispatch job completed",
        "dispatch complete",
        "rendered",
        "ui updated",
    ]),
]

FAILURE_PATTERNS: List[Tuple[str, List[str]]] = [
    ("gatt_error", [
        "status=133",
        "gatt error",
    ]),
    ("queue_timeout", [
        "queued_no_progress_timeout",
    ]),
    ("send_failed", [
        "BLE batch send failed",
        "write failed",
        "send failed",
    ]),
    ("dedup_drop", [
        "dedup drop",
        "result=drop",
    ]),
    ("dispatch_fail", [
        "APP dispatch job failed",
        "dispatch failed",
    ]),
]


@dataclass
class DeviceTrace:
    device: str
    path: str
    matched_lines: List[Tuple[int, str]] = field(default_factory=list)
    stages: List[str] = field(default_factory=list)
    failures: List[str] = field(default_factory=list)

    @property
    def max_stage(self) -> str:
        if not self.stages:
            return "matched" if self.matched_lines else "no_match"
        best = max(self.stages, key=lambda s: STAGE_RANK.get(s, -1))
        return best

    @property
    def max_stage_rank(self) -> int:
        if self.max_stage == "no_match":
            return -1
        return STAGE_RANK.get(self.max_stage, 0)


def detect_device_name(path: str) -> str:
    base = os.path.basename(path)
    m = re.search(r"device-([A-Z0-9]+)_", base)
    if m:
        return m.group(1)
    return os.path.splitext(base)[0]


def classify_line(line: str) -> Tuple[List[str], List[str]]:
    low = line.lower()
    stages: List[str] = []
    fails: List[str] = []

    for stage, patterns in EVENT_PATTERNS:
        for p in patterns:
            if p.lower() in low:
                stages.append(stage)
                break

    for fail, patterns in FAILURE_PATTERNS:
        for p in patterns:
            if p.lower() in low:
                fails.append(fail)
                break

    return stages, fails


def analyze_file(path: str, needle: str) -> DeviceTrace:
    trace = DeviceTrace(device=detect_device_name(path), path=path)

    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            for idx, line in enumerate(f, start=1):
                if needle in line:
                    clean = line.rstrip("\n")
                    trace.matched_lines.append((idx, clean))
                    stages, fails = classify_line(clean)

                    if not stages:
                        trace.stages.append("matched")
                    else:
                        trace.stages.extend(stages)

                    trace.failures.extend(fails)
    except FileNotFoundError:
        pass

    trace.stages = dedupe_keep_order(trace.stages)
    trace.failures = dedupe_keep_order(trace.failures)
    return trace


def dedupe_keep_order(items: List[str]) -> List[str]:
    seen = set()
    out = []
    for item in items:
        if item not in seen:
            seen.add(item)
            out.append(item)
    return out


def likely_layer(trace: DeviceTrace) -> str:
    fails = set(trace.failures)
    stage = trace.max_stage

    if "gatt_error" in fails:
        return "BLE_LINK"
    if "queue_timeout" in fails or "send_failed" in fails:
        return "TRANSPORT_OR_STATE"
    if "dedup_drop" in fails:
        return "PROTOCOL"
    if "dispatch_fail" in fails:
        return "APPLICATION"
    if stage in {"recv", "reassembly_progress", "reassembly_complete"}:
        return "PROTOCOL_OR_STATE"
    if stage in {"enqueue", "send_start", "send_ok"}:
        return "TRANSPORT"
    if stage == "dispatch_ok":
        return "APPLICATION"
    return "UNKNOWN"


def summarize_divergence(traces: List[DeviceTrace]) -> Dict[str, object]:
    matched = [t for t in traces if t.matched_lines]
    if not matched:
        return {
            "status": "no_match",
            "message": "No device matched the needle.",
        }

    best = max(matched, key=lambda t: t.max_stage_rank)
    worst = min(matched, key=lambda t: t.max_stage_rank)

    divergence = {
        "status": "ok",
        "best_device": best.device,
        "best_stage": best.max_stage,
        "worst_device": worst.device,
        "worst_stage": worst.max_stage,
        "gap": best.max_stage_rank - worst.max_stage_rank,
        "details": [],
    }

    for t in traces:
        divergence["details"].append({
            "device": t.device,
            "path": t.path,
            "matched": len(t.matched_lines),
            "max_stage": t.max_stage,
            "failures": t.failures,
            "likely_layer": likely_layer(t),
        })

    return divergence


def render_text(needle: str, traces: List[DeviceTrace], summary: Dict[str, object]) -> str:
    parts: List[str] = []
    parts.append(f"=== Divergence Detector ===")
    parts.append(f"needle={needle}")
    parts.append("")

    if summary["status"] == "no_match":
        parts.append(summary["message"])
        return "\n".join(parts)

    parts.append(
        f"best_device={summary['best_device']} best_stage={summary['best_stage']}"
    )
    parts.append(
        f"worst_device={summary['worst_device']} worst_stage={summary['worst_stage']}"
    )
    parts.append(f"stage_gap={summary['gap']}")
    parts.append("")

    for t in traces:
        parts.append(f"--- device={t.device} file={t.path}")
        parts.append(f"matched_lines={len(t.matched_lines)}")
        parts.append(f"max_stage={t.max_stage}")
        parts.append(f"likely_layer={likely_layer(t)}")
        parts.append(f"failures={','.join(t.failures) if t.failures else '-'}")
        if t.matched_lines:
            parts.append("sample_matches:")
            for line_no, line in t.matched_lines[:5]:
                parts.append(f"  {line_no}: {line}")
        else:
            parts.append("sample_matches: (none)")
        parts.append("")

    parts.append("divergence_hint:")
    best_stage = summary["best_stage"]
    worst_stage = summary["worst_stage"]
    parts.append(
        f"  Device {summary['best_device']} reached {best_stage}, while "
        f"{summary['worst_device']} stopped at {worst_stage}."
    )
    parts.append(
        "  Investigate the first pipeline step present on the best device but missing on the worst device."
    )

    return "\n".join(parts)


def render_markdown(needle: str, traces: List[DeviceTrace], summary: Dict[str, object]) -> str:
    lines: List[str] = []
    lines.append("# Divergence Detector Report")
    lines.append("")
    lines.append(f"- needle: `{needle}`")
    lines.append("")

    if summary["status"] == "no_match":
        lines.append(summary["message"])
        return "\n".join(lines)

    lines.append("## Summary")
    lines.append("")
    lines.append(f"- best device: **{summary['best_device']}**")
    lines.append(f"- best stage: **{summary['best_stage']}**")
    lines.append(f"- worst device: **{summary['worst_device']}**")
    lines.append(f"- worst stage: **{summary['worst_stage']}**")
    lines.append(f"- stage gap: **{summary['gap']}**")
    lines.append("")

    lines.append("## Per-device details")
    lines.append("")
    for t in traces:
        lines.append(f"### Device {t.device}")
        lines.append(f"- file: `{t.path}`")
        lines.append(f"- matched lines: **{len(t.matched_lines)}**")
        lines.append(f"- max stage: **{t.max_stage}**")
        lines.append(f"- likely layer: **{likely_layer(t)}**")
        lines.append(f"- failures: `{', '.join(t.failures) if t.failures else '-'}`")
        lines.append("")
        if t.matched_lines:
            lines.append("Sample matches:")
            lines.append("")
            lines.append("```text")
            for line_no, line in t.matched_lines[:5]:
                lines.append(f"{line_no}: {line}")
            lines.append("```")
            lines.append("")

    lines.append("## Divergence hint")
    lines.append("")
    lines.append(
        f"Device **{summary['best_device']}** reached **{summary['best_stage']}**, "
        f"while device **{summary['worst_device']}** stopped at **{summary['worst_stage']}**."
    )
    lines.append("")
    lines.append(
        "Investigate the first pipeline step that appears on the best device but is absent on the worst device."
    )
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Detect stage divergence across BLE mesh logs.")
    parser.add_argument("needle", help="msgId / hash / token / #123 to trace")
    parser.add_argument("files", nargs="+", help="log files")
    parser.add_argument("--format", choices=["text", "markdown"], default="text")
    parser.add_argument("--output", help="write result to file")
    args = parser.parse_args()

    traces = [analyze_file(path, args.needle) for path in args.files]
    summary = summarize_divergence(traces)

    if args.format == "markdown":
        rendered = render_markdown(args.needle, traces, summary)
    else:
        rendered = render_text(args.needle, traces, summary)

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(rendered)
    else:
        print(rendered)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())