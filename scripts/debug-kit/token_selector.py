#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import re
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Dict, List, Tuple


TRUNCATION_MARKERS = ("…", "...")

FIELD_PATTERNS = [
    ("event_id", re.compile(r"eventId=([A-Za-z0-9_-]{16,})")),
    ("event_id_list", re.compile(r"event_ids=([A-Za-z0-9_,:-]+)(…|\.\.\.)?")),
    ("batch_id", re.compile(r"batch_id=([A-Za-z0-9_-]{6,})")),
    ("text", re.compile(r"(TEST\s+\d+:\s+[A-Z]\s+#[0-9]+)")),
    ("text", re.compile(r"(#[0-9]+)")),
]


STAGE_KEYWORDS = {
    "sent": ["Sent message at", "APP batch emit", "APP batch transport queued"],
    "send_progress": ["BLE batch send progress"],
    "recv_chunk": ["GATT recv chunk"],
    "reassembled": ["GATT reassembled"],
    "delivered": ["GATT deliver envelope"],
    "received": ["Received message at"],
    "extracted": ["APP batch extract"],
    "rendered": ["Rendered message at"],
    "failed": ["failed", "status=133", "connect failed", "send failed"],
    "drop": ["drop", "skipped", "no_job", "source_device_unknown"],
}


GOOD_STAGES = [
    "sent",
    "send_progress",
    "recv_chunk",
    "reassembled",
    "delivered",
    "received",
    "extracted",
    "rendered",
]

BAD_STAGES = [
    "failed",
    "drop",
]


@dataclass
class TokenInfo:
    token: str
    lines: List[Tuple[str, int, str]] = field(default_factory=list)
    stages: Dict[str, int] = field(default_factory=lambda: defaultdict(int))
    kinds: Dict[str, int] = field(default_factory=lambda: defaultdict(int))
    devices: set = field(default_factory=set)

    @property
    def total_hits(self) -> int:
        return len(self.lines)

    @property
    def good_score(self) -> int:
        score = 0
        for stage in GOOD_STAGES:
            if self.stages.get(stage, 0) > 0:
                score += 10
        score += min(len(self.devices), 3) * 8
        score += min(self.total_hits, 30)
        score -= self.stages.get("failed", 0) * 8
        return score

    @property
    def bad_score(self) -> int:
        score = 0
        score += self.stages.get("failed", 0) * 20
        score += self.stages.get("drop", 0) * 10

        has_sent = self.stages.get("sent", 0) > 0 or self.stages.get("send_progress", 0) > 0
        has_received = self.stages.get("received", 0) > 0 or self.stages.get("rendered", 0) > 0

        if has_sent and not has_received:
            score += 35

        if self.stages.get("send_progress", 0) > 0 and self.stages.get("reassembled", 0) == 0:
            score += 20

        if len(self.devices) == 1 and has_sent:
            score += 15

        return score


def detect_device(path: str) -> str:
    base = os.path.basename(path)
    m = re.search(r"device-([A-Z0-9]+)_", base)
    if m:
        return m.group(1)
    m = re.search(r"device-([A-Z0-9]+)", base)
    if m:
        return m.group(1)
    return base


def is_truncated_event_list(match: re.Match[str]) -> bool:
    if match.group(2):
        return True

    end = match.end(1)
    return any(match.string.startswith(marker, end) for marker in TRUNCATION_MARKERS)


def extract_tokens(line: str) -> List[Tuple[str, str]]:
    tokens: List[Tuple[str, str]] = []

    for kind, pattern in FIELD_PATTERNS:
        for match in pattern.finditer(line):
            value = match.group(1)

            if kind == "event_id_list":
                if is_truncated_event_list(match):
                    continue
                for token in value.split(","):
                    if token:
                        tokens.append((token, "event_id"))
                continue

            tokens.append((value, kind))

    return list(dict.fromkeys(tokens))


def classify_stages(line: str) -> List[str]:
    out: List[str] = []
    for stage, keys in STAGE_KEYWORDS.items():
        if any(k in line for k in keys):
            out.append(stage)
    return out


def analyze(files: List[str]) -> Dict[str, TokenInfo]:
    tokens: Dict[str, TokenInfo] = {}

    for path in files:
        device = detect_device(path)
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            for line_no, line in enumerate(f, start=1):
                found = extract_tokens(line)
                if not found:
                    continue

                stages = classify_stages(line)

                for token, kind in found:
                    info = tokens.setdefault(token, TokenInfo(token=token))
                    info.lines.append((path, line_no, line.rstrip()))
                    info.devices.add(device)
                    info.kinds[kind] += 1

                    for stage in stages:
                        info.stages[stage] += 1

    return tokens


def render_table(title: str, items: List[TokenInfo], mode: str, limit: int) -> None:
    print(f"\n## {title}")
    print()

    for idx, info in enumerate(items[:limit], start=1):
        score = info.good_score if mode == "good" else info.bad_score
        print(f"{idx}. token={info.token}")
        print(f"   score={score}")
        print(f"   hits={info.total_hits}")
        print(f"   devices={','.join(sorted(info.devices))}")
        print(f"   kinds={dict(info.kinds)}")
        print(f"   stages={dict(info.stages)}")

        if info.lines:
            path, line_no, text = info.lines[0]
            print(f"   sample={path}:{line_no}: {text[:220]}")
        print()


def main() -> int:
    parser = argparse.ArgumentParser(description="Select good/bad BLE mesh debug tokens from logs.")
    parser.add_argument("files", nargs="+", help="activity log files")
    parser.add_argument("--limit", type=int, default=10)
    args = parser.parse_args()

    tokens = analyze(args.files)

    good = sorted(tokens.values(), key=lambda x: x.good_score, reverse=True)
    bad = sorted(tokens.values(), key=lambda x: x.bad_score, reverse=True)

    print("# BLE Mesh Token Selector")
    print()
    print(f"files={len(args.files)}")
    print(f"tokens={len(tokens)}")

    render_table("Best GOOD tokens", good, "good", args.limit)
    render_table("Most SUSPICIOUS / BAD tokens", bad, "bad", args.limit)

    print("## Suggested next commands")
    print()
    best_good_event = next((x for x in good if x.kinds.get("event_id", 0) > 0), None)
    if best_good_event:
        print(f'./scripts/debug-kit/mesh_debug.sh bundle --label good "{best_good_event.token}"')
    elif good:
        print(f'./scripts/debug-kit/mesh_debug.sh bundle --label good "{good[0].token}"')
    if bad:
        print(f'./scripts/debug-kit/mesh_debug.sh bundle --label bad "{bad[0].token}"')

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
