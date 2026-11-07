#!/usr/bin/env node
/* eslint-disable no-console */

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { Client } = require("pg");

function readNumber(value) {
  if (value === undefined || value === null || value === "") return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function ensureDir(filePath) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
}

function isLegacyStoredEnvelope(value) {
  return value &&
    typeof value === "object" &&
    !Array.isArray(value) &&
    typeof value.eid === "string" &&
    typeof value.gid === "string" &&
    Number.isInteger(value.ts) &&
    Number.isInteger(value.exp) &&
    Object.prototype.hasOwnProperty.call(value, "body");
}

function payloadFromStoredBody(body) {
  if (isLegacyStoredEnvelope(body)) {
    return body.body;
  }
  return body;
}

function rowToEventEnvelope(row) {
  return {
    eid: row.eid,
    gid: row.gid,
    ts: Number(row.ts),
    exp: Number(row.exp),
    body: payloadFromStoredBody(row.body),
    sig: row.sig,
  };
}

async function run() {
  const databaseUrl = process.env.DATABASE_URL;
  if (!databaseUrl) {
    console.error("DATABASE_URL is required");
    process.exit(1);
  }

  const gid = process.env.EXPORT_GID || null;
  const sinceTs = readNumber(process.env.EXPORT_SINCE_TS);
  const untilTs = readNumber(process.env.EXPORT_UNTIL_TS);
  const batchSize = readNumber(process.env.EXPORT_BATCH_SIZE) || 1000;
  const output =
    process.env.EXPORT_OUTPUT ||
    path.join("export", `events_${new Date().toISOString().replace(/[:.]/g, "")}.jsonl`);

  ensureDir(output);

  const client = new Client({ connectionString: databaseUrl });
  await client.connect();

  const stream = fs.createWriteStream(output, { encoding: "utf8" });
  const hash = crypto.createHash("sha256");

  let count = 0;
  let lastTs = sinceTs || 0;
  let lastEid = "";
  let hasMore = true;

  try {
    while (hasMore) {
      const conditions = [];
      const params = [];

      if (gid) {
        params.push(gid);
        conditions.push(`gid = $${params.length}`);
      }
      if (sinceTs !== null) {
        params.push(sinceTs);
        conditions.push(`ts >= $${params.length}`);
      }
      if (untilTs !== null) {
        params.push(untilTs);
        conditions.push(`ts <= $${params.length}`);
      }

      params.push(lastTs);
      params.push(lastEid);
      const cursorClause = `(ts > $${params.length - 1} OR (ts = $${params.length - 1} AND eid > $${params.length}))`;
      conditions.push(cursorClause);

      params.push(batchSize);

      const whereClause = conditions.length > 0 ? `WHERE ${conditions.join(" AND ")}` : "";
      const query = `
        SELECT eid, gid, ts, exp, body, sig
        FROM events
        ${whereClause}
        ORDER BY ts ASC, eid ASC
        LIMIT $${params.length}
      `;

      const res = await client.query(query, params);
      if (res.rows.length === 0) {
        hasMore = false;
        break;
      }

      for (const row of res.rows) {
        const line = JSON.stringify(rowToEventEnvelope(row));
        stream.write(line + "\n");
        hash.update(line);
        hash.update("\n");
        count += 1;
        lastTs = Number(row.ts);
        lastEid = row.eid;
      }
    }
  } finally {
    await client.end();
    stream.end();
  }

  const checksum = hash.digest("hex");
  fs.writeFileSync(`${output}.sha256`, `${checksum}  ${path.basename(output)}\n`);

  console.log(`Exported ${count} events to ${output}`);
  console.log(`Checksum written to ${output}.sha256`);
}

run().catch((err) => {
  console.error(err);
  process.exit(1);
});
