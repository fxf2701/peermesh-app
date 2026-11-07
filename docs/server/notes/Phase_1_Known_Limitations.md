# Phase 1 — Known Limitations / Operational Considerations

This note captures known limitations and operational considerations for the
Phase 1 opaque relay server. It is intended as a backlog for future hardening.

## Known Limitations

- **Opaque relay only**: server does not verify MAC/signature; integrity is
  verified on clients. (Private relay mode with server-side MAC verification is
  deferred to Phase 3.)
- **In-memory rate limits**: rate limiting is per-process and resets on restart;
  not consistent across multiple instances.
- **In-memory nonce cache**: replay protection is per-process; not suitable for
  multi-instance deployments without shared storage.
- **Trusted proxy assumption**: `x-forwarded-for` is used for rate limiting;
  requires a trusted reverse proxy configuration to prevent spoofing.
- **Cleanup runs on every instance**: periodic TTL cleanup can run concurrently
  across instances (higher DB load).
- **Ingest insert is serial**: events are inserted one-by-one in a transaction;
  large batches may be slow without bulk insert optimization.
- **Observability is minimal**: basic request logs and health check only; no
  metrics, traces, or structured logging.

## Operational Considerations

- **Single-instance is simplest**: current defaults are optimized for single
  relay deployments.
- **Federation requires trust**: peer relay synchronization should be restricted
  to allowlisted operators.
- **Limits need tuning**: rate limits, request size, and TTL settings should be
  tuned per deployment to balance abuse risk and usability.
