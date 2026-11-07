# M3E12-T8 Final Evidence Summary

## Verdict

```text
PASS WITH ACCEPTED LIMITATION
```

## Proven end-to-end properties

- durable offline queueing;
- retry after peer unavailability;
- delivery after reconnect;
- two-way chat convergence;
- two-way canonical marker convergence;
- dependent action arrival before root;
- missing-root defer and repair scheduling;
- canonical ADD materialization;
- large inventory comparison;
- mandatory WANT generation;
- no silent `onHave()` stop after diagnostics;
- no crash or ANR in the final runs.

## Large inventory evidence

The final run included a valid generic inventory larger than the previously problematic 160-ID case:

```text
INV_HAVE ids=218
bytes≈10.2 KB
chunks=61
```

The receiver:

```text
reassembled the envelope
→ validated CRC
→ parsed the packet
→ completed `onHave` comparison
→ generated non-empty INV_WANT (64 IDs)
```

This materially reduces confidence that inventory size alone caused the earlier silent `onHave()` stop.

## Shared runtime limitation

A reconnect can enter a degraded period where:

```text
peer advertisements are visible
Nearby reports one peer
pending work exists
but connect/service/MTU/write progress does not begin
```

Observed recovery mechanisms included timeout cleanup, hard reset and transport restart. Pending durable events later converged.

This limitation is transferred to BR1 and does not invalidate M3E12 dependency-repair correctness.

## Closure boundary

M3E12 is closed. Future work must not modify Map dependency-repair semantics unless BR1 or later evidence proves a direct defect in that path.
