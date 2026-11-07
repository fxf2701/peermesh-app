# Relay Disaster Recovery Plan (RPO/RTO)

This document defines recovery objectives and steps for major outages.

## 1) Objectives

- **RPO (Recovery Point Objective):** 15 minutes
- **RTO (Recovery Time Objective):** 2 hours

These targets can be adjusted based on operator capacity and risk tolerance.

## 2) Scenarios Covered

- Total database loss
- Host loss (VM or node failure)
- Region/network partition

## 3) Recovery Strategy

- Maintain frequent backups with validation.
- Store at least one copy offsite.
- Keep a tested restore procedure ready.
- Document configuration and secrets in a secure store.

## 4) Recovery Steps (DB Loss)

1) Provision a new Postgres instance.
2) Restore the latest backup.
3) Point relay to the restored DB (`DATABASE_URL`).
4) Run a basic ingest/fetch smoke test.
5) Resume federation sync.

## 5) Recovery Steps (Host Loss)

1) Rebuild the host or provision a new VM.
2) Deploy relay binaries/containers.
3) Restore configs and secrets.
4) Restore DB or connect to existing DB.
5) Run smoke tests and re-enable traffic.

## 6) Verification

- Confirm `/healthz` and `/readyz`.
- Confirm Prometheus scrape targets.
- Monitor error budget burn for 1–2 hours.

## 7) Review Cadence

- Review DR plan quarterly.
- Run a restore drill at least monthly.
