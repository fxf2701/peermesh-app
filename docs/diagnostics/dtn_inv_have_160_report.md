# DTN `INV_HAVE` 160-ID investigation — Device A

## A. Executive conclusion

**EXACT LIST RECONSTRUCTED** from the Device A snapshot, subject to the normal
forensic limitation that the DB snapshot was copied at 14:25 rather than at
13:48. The result is nevertheless strongly determined by the code and counts:
at the packet timestamp the DB contains exactly **160 valid NORMAL-tier
events**, and the received packet reports exactly `ids=160`.

The ordered list is in:

* [candidate_inv_have_160.csv](../../artifacts/candidate_inv_have_160.csv)
* [candidate_inv_have_160.json](../../artifacts/candidate_inv_have_160.json)

The list is ordered exactly as `DtnForwardPolicy.sortForTransfer()` uses for
NORMAL tier: `priority ASC`, `created_t ASC`, `event_id ASC`.

## B. Evidence and reproducibility

Target packet recorded by Device B:

```text
2026-08-01 13:48:39 +07
DTN inv_have recv
peer_id=F6C01431201D82C6
session_id=F6C01431201D-1785566879970-71adf6d9
ids=160 data=0 bytes=7584 chunks=45
```

Device A database acquisition:

| Field | Value |
| --- | --- |
| ADB serial | `251fd3e9` |
| Package | `app.peermesh` |
| Device | Samsung SM-J415F, Android 9 / SDK 28 |
| Copy time | 2026-08-01 14:25 +07 |
| DB | 1,277,952 B, SHA-256 `e6b4ac74456b81e55d92566297863a74d1be79123cb02d6246279d7251a7c168` |
| WAL | 524,288 B, SHA-256 `fa78505750d4e340716923aaaff6f425e9978ff7882da7f1cb05322dffc6936e` |
| SHM | 32,768 B, SHA-256 `5257ef2f9352d750d005c7ffa1f9315b87ace59062d54526f984b22653c58128` |

All DB access used `sqlite3 -readonly`; the app was force-stopped before
copying only to obtain a consistent DB/WAL/SHM snapshot. No row/schema/app data
was modified.

At `nowSeconds=1785566919` (13:48:39 +07), filtering valid rows with
`inserted_at <= nowSeconds` produces:

| Tier | Rule | Count |
| --- | --- | ---: |
| HIGH | `CHAT.MSG`/`CHAT.REPLY`/`CHAT.EDIT`/`CHAT.DEL` less than one hour old | 2 |
| NORMAL | every non-`SYS.ACK` event not classified HIGH | **160** |
| LOW | `SYS.ACK` | 138 |

There were 316 valid rows at the timestamp, 16 newer rows inserted after the
packet, and zero invalid rows in the group. The exact equality between the
NORMAL candidate count and packet `ids=160` proves that none of the NORMAL
candidates were removed by peer suppression and that the NORMAL window reached
the tier's end. A prior cursor could only have yielded 160 if it preceded every
NORMAL candidate; it therefore does not change the ordered ID list.

Run [query_dtn_inv_have_160.sql](query_dtn_inv_have_160.sql) against a
read-only copy to reproduce raw rows, candidate rows, aggregate counts, batch
joins, and persisted DTN state. The companion
[`reconstruct_dtn_inv_have.kt`](../../tools/diagnostics/reconstruct_dtn_inv_have.kt)
implements the same candidate filter/sort in a JDBC read-only utility.

## C. Inventory selection algorithm

For this packet the current source executes:

```text
RoomDtnInventoryStore.listInventoryAfter(gid, cursor, scanLimit)
  → EventsDao.loadDtnCursorWindow(... ORDER BY inserted_at, event_id)
  → DtnInventoryBuilder.buildHaveWindow(...)
  → DtnInventoryTier.classify(item, nowSeconds)
  → DtnForwardPolicy.sortForTransfer(...)
  → repairSuppressionStore.filterPeerSatisfied(...)
  → matches.keys.take(limit)
```

Details:

* `DtnSyncEngine.buildNextHaveForPeer()` advances tier order `HIGH → NORMAL → LOW`.
* `DEFAULT_INVENTORY_LIMIT=250`; this is **not** a 160-ID cap.
* `scanLimit=max(limit*4,limit)=1000`; `MAX_SCAN_BATCHES=8`.
* The reported 160-ID envelope is necessarily the NORMAL window, after the
  preceding two HIGH chat IDs were dealt with/advanced in the same session.
* The first source scan from a null/effectively-pre-NORMAL cursor reads all 316
  rows. It keeps exactly the 160 NORMAL entries, sorts them, and a second scan
  is empty; `hasMoreInTier=false`.
* Remote peer used by the inventory builder is Device B stable peer ID
  `3D3FA1C865F3B265`. Device A's sender peer ID is `F6C01431201D82C6`.
* The persisted cursor currently present for B is only `#high`, updated after
  the target session (`updated_at=1785569092`). It does not record historical
  NORMAL window state. The packet count makes the resulting NORMAL list
  unambiguous anyway.

## D. Type, source, category, and age breakdown

### Event type

| Type | Count |
| --- | ---: |
| `SYS.USER` | 37 |
| `SYS.GROUP` | 36 |
| `MAP.MARKER.ADD` | 27 |
| `MAP.ITEM.CONFIRM` | 19 |
| `MAP.AREA.ADD` | 9 |
| `MAP.ITEM.SITUATION.STATUS` | 8 |
| `MAP.ITEM.SITUATION.UPDATE` | 8 |
| `CHAT.MSG` | 7 |
| `CHAT.REPLY` | 3 |
| `MAP.ITEM.ACKNOWLEDGE` | 2 |
| `MAP.MARKER.DEL` | 2 |
| `MAP.MARKER.UPD` | 2 |

### Source and category

| Dimension | Count |
| --- | ---: |
| `mesh` source | 115 |
| `local` source | 29 |
| `dtn` source | 16 |
| Map canonical | 40 |
| Map action | 37 |
| Chat | 10 |
| System | 73 |
| Priority 2 (Map) | 77 |
| Priority 3 (Chat) | 10 |
| Priority 4 (System/other) | 73 |

### Age at build

| Age bucket | Count |
| --- | ---: |
| 30 minutes–6 hours | 14 |
| 6–24 hours | 35 |
| 1–7 days | 111 |

Youngest candidate: 5,595 seconds (about 1h33m). Oldest: 321,891 seconds
(about 3d17h). There are no candidates younger than 30 minutes because fresh
chat belongs to the earlier HIGH tier.

## E. Outbound-batch relationship

Of the 160 advertised IDs:

| Relationship at snapshot | Count |
| --- | ---: |
| No `outbound_batch_event` row: durable-history only | 159 |
| Associated with a terminal `FAILED_FINAL` batch | 1 |
| Active/retryable outbound batch | 0 |

The single batch-linked ID is `9jKSUSmgt5Vs_7fbTZmYeRzsWI3ktrxBIMP-hPKK4q8`
(`SYS.USER`), batch `aPNfSCBhB9MK`, status `FAILED_FINAL`, attempt count 1.

Thus inventory membership is demonstrably not equivalent to the APP outbound
queue: nearly the entire packet is durable historical event inventory.

## F. Suppression/cursor limits

`repairSuppressionStore.filterPeerSatisfied(...)` is called by the builder,
but its runtime implementation is `InMemoryDtnRepairSuppressionStore`. It has
no Room table, no timestamped persistence, and is lost on process restart.
Therefore the events table alone cannot generally reconstruct historical
suppression for an arbitrary packet.

For this exact packet, however, it could not have suppressed any candidate:
there are exactly 160 eligible NORMAL entries and the packet contains 160 IDs.
The persisted `dtn_advertised` table has zero rows for B/group and is not used
by the present selection path. `dtn_sync_cursor` has a later HIGH cursor only;
it cannot prove the prior session's transient in-memory state.

## G. BLE runtime relevance

The control envelope carries 7,584 bytes in 45 chunks for no application data.
It is a mixed durable-history inventory, not a recent-map-only inventory:

* 77 map events (48.1%),
* 73 system metadata events (45.6%),
* 10 historical chat events (6.3%).

Repeated advertisement across reconnects/session rotations is **confirmed by
code and supported by persisted state**. A cursor is committed only after the
active window completes (empty WANT or matching DATA ACK). If the connection
ends before that, the window cursor remains only in in-memory session state;
`onPeerDisconnected()` drops it. A later session can therefore restart the
same tier and advertise the same historical NORMAL IDs. The lack of a persisted
NORMAL cursor for B after this test is consistent with that outcome.

This supports the hypothesis that generic inventory traffic can occupy the BLE
control lane even when there is no new map interaction. This investigation does
not propose or implement a production transport change.
