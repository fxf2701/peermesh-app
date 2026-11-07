# Relay Dedupe Note (Content/Nonce)

Current dedupe uses `eid` uniqueness in the database. This avoids duplicate
events with the same ID but does not prevent replays with different IDs.

Consider adding optional dedupe signals:

- **Nonce-based**: reject if `body.nonce` already seen within a TTL window.
- **Content hash**: compute a short hash from `gid + body` for replay suppression.

Trade-offs:
- Stronger dedupe reduces spam/replay but risks false positives.
- Hash-based dedupe needs careful TTL to avoid hiding legitimate repeats.

Suggested approach:
- Keep `eid` as primary dedupe.
- Add nonce-based dedupe for clients that include nonces.
- Make content-hash dedupe opt-in per deployment.
