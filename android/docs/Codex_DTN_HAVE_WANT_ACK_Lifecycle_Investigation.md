# Codex Investigation Prompt --- DTN HAVE → WANT → DATA → ACK Lifecycle (Evidence Only)

## Objective

Continue the BLE/DTN investigation by identifying the exact lifecycle
break that prevents a NORMAL inventory window from being committed.

**Evidence gathering only. Do not implement production fixes.**

## Context

Previous investigation concluded: - Mixed Cause. - Stable peer identity
is correct. - 160 historical IDs are durable history. - NORMAL cursor
was not committed. - Generic peer suppression is in-memory. - Session
interruption may restart the inventory window.

Known session: `F6C01431201D-1785566879970-71adf6d9`

Known group: `H-MfE3z9pVj2M4FIOt5JLxLTguKOFp5q1kOQ-Mq0k4Q`

Use: - Activity Logs - Room DB - Current source - Existing diagnostic
artifacts

If evidence is unavailable, explicitly report **UNKNOWN**.

## Required investigation

Reconstruct the complete lifecycle:

HELLO → INV_HAVE → Receiver inventory comparison → INV_WANT → DATA → ACK
→ Cursor commit → Disconnect / cleanup

For every stage provide: - timestamp - production function - persisted
state - in-memory state - completion condition - evidence source

### A. HAVE lifecycle

Trace: buildHaveWindow() → queue → wire start → wire complete

Answer: - Entire envelope transmitted? - Any chunk loss? - Disconnect
before completion? - Cursor before/after build - Window size - Bytes -
Chunks

### B. Receiver WANT lifecycle

Determine whether receiver: received HAVE → parsed HAVE → compared
inventory → built WANT

If WANT exists: - requested IDs - count - timestamp

If not: - explain why.

### C. WANT transport

Trace: queue → wire → sender receive

Classify: - never built - built but never queued - queued but never
written - written but not received - received but ignored

### D. DATA lifecycle

If WANT arrived: lookup → DATA build → queue → wire → receiver ingest

Return: - requested IDs - served IDs - lookup failures

### E. ACK lifecycle

Trace: ACK build → queue → wire → sender receive

Determine: - complete - partial - missing

### F. Cursor commit

Locate the only production path(s) that commit NORMAL cursor.

Show: required preconditions actual state why commit happened or not.

### G. Disconnect cleanup

Trace: disconnect → cleanup → active window destroyed? → cursor
retained? → suppression retained?

Answer whether reconnect resumes an active window or always restarts.

### H. Unified timeline

Produce one chronological timeline with timestamps, component, state and
evidence.

## Mandatory questions

Q1. Is this true?

disconnect → destroy active window → cursor progress lost

If not, explain.

Q2. After immediate reconnect: resume active window or restart from
cursor?

Q3. If receiver never sends WANT: Is repeated historical inventory
EXPECTED or BUG according to current implementation?

Support with production code.

## Deliverables

Generate:

-   docs/diagnostics/dtn_have_want_ack_lifecycle.md
-   docs/diagnostics/dtn_cursor_commit_path.md
-   docs/diagnostics/dtn_disconnect_cleanup.md
-   artifacts/dtn_session_lifecycle.csv

## Final verdict

Choose one:

-   Lifecycle completed correctly
-   Receiver never generated WANT
-   WANT transport failed
-   DATA transport failed
-   ACK never completed
-   Cursor commit blocked
-   Disconnect interrupted completion
-   Multiple contributing causes
-   Inconclusive

Assign HIGH / MEDIUM / LOW confidence.

## Important

Do not modify production code, scheduler, cursor logic, suppression, or
DTN transport.
