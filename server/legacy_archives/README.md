# Legacy Server Archives

This directory holds local artifacts moved out of the old flat `server/`
layout after the repository was split into:

- `server/relay`
- `server/registry`

Current archive buckets:

- `flat-server-backups/`: old local backup dumps that previously lived under
  `server/server/backups/`

These artifacts are intentionally kept out of the active runtime tree so new
development and operator workflows do not accidentally reference obsolete paths.
