# Relay Partitioning Tests

This doc describes how to test the optional partitioned schema.

## Prerequisites
- Apply `server/relay/config/schema_partitioned.sql` to your database.
- Create at least one partition for the `events` table.

Example:

```sql
CREATE TABLE IF NOT EXISTS events_exp_2025_01
  PARTITION OF events FOR VALUES FROM (1735689600) TO (1738368000);
```

## Run the partitioning test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-partitioning-tests.sh
USE_ENV_DEV=1 ./server/relay/scripts/run-partitioning-tests.sh
```

Expected output includes:
- `PASS partitioning_enabled`
- `PASS partitions_exist`

## Run the partition self-check test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-partition-self-check-tests.sh
USE_ENV_DEV=1 ./server/relay/scripts/run-partition-self-check-tests.sh
```

Expected output includes:
- `PASS partition_self_check` (partitioned)
