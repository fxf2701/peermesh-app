#!/usr/bin/env node
/* eslint-disable no-console */

const http = require("http");
const crypto = require("crypto");
const { isDeepStrictEqual } = require("util");
const { Client } = require("pg");

const baseUrl = process.env.RELAY_BASE_URL || "http://localhost:8080";
const fetchMaxLimit = Number(process.env.FETCH_MAX_LIMIT || "200");
const databaseUrl = process.env.DATABASE_URL || process.env.RELAY_DATABASE_URL;

function uuidv4() {
  const bytes = crypto.randomBytes(16);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20),
  ].join("-");
}

function postJson(path, payload, headers = {}) {
  const data = JSON.stringify(payload);
  const url = new URL(path, baseUrl);

  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname,
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(data),
          ...headers,
        },
      },
      (res) => {
        let body = "";
        res.on("data", (chunk) => {
          body += chunk;
        });
        res.on("end", () => {
          resolve({ status: res.statusCode, body });
        });
      }
    );

    req.on("error", reject);
    req.write(data);
    req.end();
  });
}

function getJson(path, headers = {}) {
  const url = new URL(path, baseUrl);

  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname + url.search,
        method: "GET",
        headers,
      },
      (res) => {
        let body = "";
        res.on("data", (chunk) => {
          body += chunk;
        });
        res.on("end", () => {
          resolve({ status: res.statusCode, body });
        });
      }
    );

    req.on("error", reject);
    req.end();
  });
}

function parseJson(body) {
  try {
    return JSON.parse(body);
  } catch (err) {
    return null;
  }
}

function deepEqual(a, b) {
  return isDeepStrictEqual(a, b);
}

async function run() {
  let failed = 0;
  const now = Math.floor(Date.now() / 1000);
  const baseEvent = {
    gid: "",
    ts: now,
    exp: now + 600,
    body: { type: "CHAT.MSG" },
    sig: "abcd",
  };

  async function ingestEvents(gid, headers) {
    const ingest = await postJson(
      "/v1/events:ingest",
      {
        events: [
          { ...baseEvent, gid, eid: uuidv4(), ts: now + 1 },
          { ...baseEvent, gid, eid: uuidv4(), ts: now + 2 },
          { ...baseEvent, gid, eid: uuidv4(), ts: now + 3 },
        ],
      },
      headers
    );
    if (ingest.status !== 200) {
      throw new Error(`ingest failed: ${ingest.status} ${ingest.body}`);
    }
  }

  // Invalid cursor.
  try {
    const gid = `Z29vZGd${uuidv4().slice(0, 6)}`;
    const headers = { "x-forwarded-for": "fetch-invalid-cursor" };
    await ingestEvents(gid, headers);
    const res = await getJson(`/v1/events:fetch?gid=${gid}&cursor=not-base64`, headers);
    const parsed = parseJson(res.body);
    const ok =
      res.status === 400 &&
      parsed &&
      parsed.error &&
      parsed.error.code === "invalid_cursor";
    if (ok) {
      console.log("PASS invalid_cursor");
    } else {
      failed += 1;
      console.log("FAIL invalid_cursor");
      console.log(`  status: ${res.status}`);
      console.log(`  body: ${res.body}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL invalid_cursor");
    console.log(`  error: ${err.message || String(err)}`);
  }

  // Limit cap enforcement.
  try {
    const gid = `Z29vZGd${uuidv4().slice(0, 6)}`;
    const headers = { "x-forwarded-for": "fetch-limit-cap" };
    await ingestEvents(gid, headers);
    const requested = fetchMaxLimit + 5;
    const res = await getJson(
      `/v1/events:fetch?gid=${gid}&limit=${requested}`,
      headers
    );
    const parsed = parseJson(res.body);
    const ok =
      res.status === 200 &&
      parsed &&
      Array.isArray(parsed.events) &&
      parsed.events.length <= fetchMaxLimit;
    if (ok) {
      console.log("PASS limit_cap");
    } else {
      failed += 1;
      console.log("FAIL limit_cap");
      console.log(`  status: ${res.status}`);
      console.log(`  body: ${res.body}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL limit_cap");
    console.log(`  error: ${err.message || String(err)}`);
  }

  // Stable ordering & pagination.
  try {
    const gid = `Z29vZGd${uuidv4().slice(0, 6)}`;
    const headers = { "x-forwarded-for": "fetch-pagination" };
    await ingestEvents(gid, headers);
    const first = await getJson(`/v1/events:fetch?gid=${gid}&limit=2`, headers);
    const firstBody = parseJson(first.body);
    const firstOk =
      first.status === 200 &&
      firstBody &&
      Array.isArray(firstBody.events) &&
      firstBody.events.length === 2 &&
      firstBody.next_cursor;

    const second = await getJson(
      `/v1/events:fetch?gid=${gid}&limit=2&cursor=${encodeURIComponent(
        firstBody.next_cursor
      )}`,
      headers
    );
    const secondBody = parseJson(second.body);
    const secondOk =
      second.status === 200 &&
      secondBody &&
      Array.isArray(secondBody.events) &&
      secondBody.events.length === 1;

    const orderedOk =
      firstOk &&
      secondOk &&
      firstBody.events[0].ts <= firstBody.events[1].ts &&
      firstBody.events[1].ts <= secondBody.events[0].ts;

    if (orderedOk) {
      console.log("PASS fetch_pagination");
    } else {
      failed += 1;
      console.log("FAIL fetch_pagination");
      console.log(`  first: ${first.status} ${first.body}`);
      console.log(`  second: ${second.status} ${second.body}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL fetch_pagination");
    console.log(`  error: ${err.message || String(err)}`);
  }

  // Same-ts events should paginate by ingest order, not eid lexical order.
  try {
    const gid = `Z29vZGd${uuidv4().slice(0, 6)}`;
    const headers = { "x-forwarded-for": "fetch-same-ts-order" };
    const sharedTs = now + 20;
    const firstEvent = {
      ...baseEvent,
      gid,
      eid: "ffffffff-ffff-4fff-8fff-ffffffffffff",
      ts: sharedTs,
    };
    const secondEvent = {
      ...baseEvent,
      gid,
      eid: "00000000-0000-4000-8000-000000000000",
      ts: sharedTs,
    };
    const ingest = await postJson("/v1/events:ingest", { events: [firstEvent, secondEvent] }, headers);
    if (ingest.status !== 200) {
      throw new Error(`ingest failed: ${ingest.status} ${ingest.body}`);
    }

    const firstPage = await getJson(`/v1/events:fetch?gid=${gid}&limit=1`, headers);
    const firstBody = parseJson(firstPage.body);
    const secondPage = await getJson(
      `/v1/events:fetch?gid=${gid}&limit=10&cursor=${encodeURIComponent(firstBody.next_cursor)}`,
      headers
    );
    const secondBody = parseJson(secondPage.body);

    const ok =
      firstPage.status === 200 &&
      secondPage.status === 200 &&
      firstBody &&
      secondBody &&
      Array.isArray(firstBody.events) &&
      Array.isArray(secondBody.events) &&
      firstBody.events.length === 1 &&
      secondBody.events.length === 1 &&
      firstBody.events[0].eid === firstEvent.eid &&
      secondBody.events[0].eid === secondEvent.eid;

    if (ok) {
      console.log("PASS fetch_same_ts_ingest_order");
    } else {
      failed += 1;
      console.log("FAIL fetch_same_ts_ingest_order");
      console.log(`  first: ${firstPage.status} ${firstPage.body}`);
      console.log(`  second: ${secondPage.status} ${secondPage.body}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL fetch_same_ts_ingest_order");
    console.log(`  error: ${err.message || String(err)}`);
  }

  // Canonical envelope reconstruction from stored body payload.
  try {
    if (!databaseUrl) {
      console.log("SKIP fetch_canonical_envelope");
      console.log("  reason: DATABASE_URL not set");
    } else {
      const db = new Client({ connectionString: databaseUrl });
      await db.connect();
      try {
        const gid = `Z29vZGd${uuidv4().slice(0, 6)}`;
        const headers = { "x-forwarded-for": "fetch-envelope" };
        const event = {
          ...baseEvent,
          gid,
          eid: uuidv4(),
          ts: now + 10,
          body: {
            type: "CHAT.MSG",
            text: `envelope-${uuidv4().slice(0, 8)}`,
          },
        };

        const ingest = await postJson("/v1/events:ingest", { events: [event] }, headers);
        if (ingest.status !== 200) {
          throw new Error(`ingest failed: ${ingest.status} ${ingest.body}`);
        }

        const dbResult = await db.query(
          "SELECT eid, gid, ts, exp, body, sig FROM events WHERE eid = $1",
          [event.eid]
        );
        const stored = dbResult.rows[0];
        const fetchRes = await getJson(`/v1/events:fetch?gid=${gid}&limit=10`, headers);
        const fetchBody = parseJson(fetchRes.body);
        const fetched = Array.isArray(fetchBody && fetchBody.events)
          ? fetchBody.events.find((entry) => entry && entry.eid === event.eid)
          : null;
        const legacyEvent = {
          ...baseEvent,
          gid,
          eid: uuidv4(),
          ts: now + 11,
          body: {
            type: "CHAT.MSG",
            text: `legacy-${uuidv4().slice(0, 8)}`,
          },
        };
        await db.query(
          `INSERT INTO events (eid, gid, ts, exp, body, sig)
           VALUES ($1, $2, $3, $4, $5, $6)`,
          [
            legacyEvent.eid,
            legacyEvent.gid,
            legacyEvent.ts,
            legacyEvent.exp,
            legacyEvent,
            legacyEvent.sig,
          ]
        );
        const legacyFetchRes = await getJson(`/v1/events:fetch?gid=${gid}&limit=10`, headers);
        const legacyFetchBody = parseJson(legacyFetchRes.body);
        const legacyFetched = Array.isArray(legacyFetchBody && legacyFetchBody.events)
          ? legacyFetchBody.events.find((entry) => entry && entry.eid === legacyEvent.eid)
          : null;

        const ok =
          stored &&
          stored.body &&
          stored.body.type === event.body.type &&
          stored.body.text === event.body.text &&
          !Object.prototype.hasOwnProperty.call(stored.body, "eid") &&
          !Object.prototype.hasOwnProperty.call(stored.body, "gid") &&
          fetched &&
          fetched.eid === event.eid &&
          fetched.gid === event.gid &&
          fetched.ts === event.ts &&
          fetched.exp === event.exp &&
          fetched.sig === event.sig &&
          fetched.body &&
          fetched.body.type === event.body.type &&
          fetched.body.text === event.body.text &&
          legacyFetched &&
          legacyFetched.eid === legacyEvent.eid &&
          legacyFetched.body &&
          legacyFetched.body.type === legacyEvent.body.type &&
          legacyFetched.body.text === legacyEvent.body.text &&
          !Object.prototype.hasOwnProperty.call(legacyFetched.body, "eid");

        if (ok) {
          console.log("PASS fetch_canonical_envelope");
        } else {
          failed += 1;
          console.log("FAIL fetch_canonical_envelope");
          console.log(`  stored: ${JSON.stringify(stored)}`);
          console.log(`  fetched: ${JSON.stringify(fetched)}`);
          console.log(`  legacy_fetched: ${JSON.stringify(legacyFetched)}`);
        }
      } finally {
        await db.end();
      }
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL fetch_canonical_envelope");
    console.log(`  error: ${err.message || String(err)}`);
  }

  // Encrypted body must survive ingest, storage, and fetch without mutation.
  try {
    if (!databaseUrl) {
      console.log("SKIP fetch_encrypted_body_round_trip");
      console.log("  reason: DATABASE_URL not set");
    } else {
      const db = new Client({ connectionString: databaseUrl });
      await db.connect();
      try {
        const gid = `Z29vZGd${uuidv4().slice(0, 6)}`;
        const headers = { "x-forwarded-for": "fetch-encrypted-body" };
        const body = {
          v: 1,
          enc: "AES-256-GCM",
          kid: `gk-${uuidv4().slice(0, 8)}`,
          cty: "peermesh/app-event+json",
          nonce: Buffer.from(`nonce-${uuidv4()}`).toString("base64url"),
          ct: Buffer.from(`ciphertext-${uuidv4()}`).toString("base64url"),
          tag: Buffer.from(`tag-${uuidv4().slice(0, 12)}`).toString("base64url"),
        };
        const event = {
          gid,
          eid: uuidv4(),
          ts: now + 20,
          exp: now + 1200,
          body,
          sig: Buffer.from(`sig-${uuidv4()}`).toString("base64url"),
        };

        const ingest = await postJson("/v1/events:ingest", { events: [event] }, headers);
        if (ingest.status !== 200) {
          throw new Error(`ingest failed: ${ingest.status} ${ingest.body}`);
        }

        const dbResult = await db.query(
          "SELECT eid, gid, ts, exp, body, sig FROM events WHERE eid = $1",
          [event.eid]
        );
        const stored = dbResult.rows[0];
        const fetchRes = await getJson(`/v1/events:fetch?gid=${gid}&limit=10`, headers);
        const fetchBody = parseJson(fetchRes.body);
        const fetched = Array.isArray(fetchBody && fetchBody.events)
          ? fetchBody.events.find((entry) => entry && entry.eid === event.eid)
          : null;

        const ok =
          stored &&
          deepEqual(stored.body, body) &&
          stored.sig === event.sig &&
          fetched &&
          fetched.eid === event.eid &&
          fetched.gid === event.gid &&
          fetched.ts === event.ts &&
          fetched.exp === event.exp &&
          fetched.sig === event.sig &&
          deepEqual(fetched.body, body);

        if (ok) {
          console.log("PASS fetch_encrypted_body_round_trip");
        } else {
          failed += 1;
          console.log("FAIL fetch_encrypted_body_round_trip");
          console.log(`  stored: ${JSON.stringify(stored)}`);
          console.log(`  fetched: ${JSON.stringify(fetched)}`);
        }
      } finally {
        await db.end();
      }
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL fetch_encrypted_body_round_trip");
    console.log(`  error: ${err.message || String(err)}`);
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
