# Relay Data Export Guide

This guide describes optional data exports for community transparency.

## 1) Export Script

Use `server/relay/scripts/export-events.js` to export events as JSONL.

Required:
- `DATABASE_URL`

Optional filters:
- `EXPORT_GID` (export only one group)
- `EXPORT_SINCE_TS` (unix seconds)
- `EXPORT_UNTIL_TS` (unix seconds)
- `EXPORT_BATCH_SIZE` (default 1000)
- `EXPORT_OUTPUT` (default `export/events_<timestamp>.jsonl`)

Example:

```bash
DATABASE_URL=postgres://... \
EXPORT_GID=Z29vZGd \
EXPORT_SINCE_TS=1730000000 \
EXPORT_UNTIL_TS=1730086400 \
EXPORT_OUTPUT=export/events_day1.jsonl \
server/relay/scripts/export-events.js
```

Test export:

```bash
ENV_FILE=server/.env.dev \
server/relay/scripts/test-export-events.sh
```

## 2) Output Format

Each line is a JSON object:

```json
{"eid":"...","gid":"...","ts":1730000000,"exp":1730003600,"body":{...},"sig":"..."}
```

## 3) Checksums

The script writes a checksum file next to the export:

```
export/events_day1.jsonl.sha256
```

Verify:

```bash
cd export
sha256sum -c events_day1.jsonl.sha256
```

## 4) Sharing Guidance

- Remove sensitive fields if needed before sharing.
- Provide the checksum with the export.
- Consider compressing large exports.
