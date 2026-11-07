#!/usr/bin/env node
/* eslint-disable no-console */

const http = require("http");
const crypto = require("crypto");

const baseUrl = process.env.RELAY_BASE_URL || "http://localhost:8080";
const maxEventsPerRequest = Number(process.env.MAX_EVENTS_PER_REQUEST || "100");
const maxEventBytes = Number(process.env.MAX_EVENT_BYTES || "65536");

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

function postJson(path, payload) {
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

function getJson(path) {
  const url = new URL(path, baseUrl);

  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname + url.search,
        method: "GET",
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

async function run() {
  const now = Math.floor(Date.now() / 1000);
  const exp = now + 300;
  const gid = "Z29vZGd";
  const baseEvent = {
    eid: uuidv4(),
    gid,
    ts: now,
    exp,
    body: { type: "CHAT.MSG" },
    sig: "abcd",
  };

  function makeEvent(overrides) {
    return { ...baseEvent, eid: uuidv4(), ...overrides };
  }

  function buildLargeEvent() {
    const event = makeEvent({ body: { type: "CHAT.MSG", text: "" } });
    let paddingSize = maxEventBytes + 1024;
    event.body.text = "a".repeat(paddingSize);
    let serializedSize = Buffer.byteLength(JSON.stringify(event));
    if (serializedSize <= maxEventBytes) {
      const extra = maxEventBytes - serializedSize + 256;
      event.body.text += "b".repeat(extra);
    }
    return event;
  }

  const cases = [
    {
      name: "invalid_body",
      payload: { foo: 1 },
      expectStatus: 400,
      expectBody: (parsed) =>
        parsed &&
        parsed.error &&
        parsed.error.code === "invalid_body",
    },
    {
      name: "missing_eid",
      payload: { events: [makeEvent({ eid: undefined })] },
      expectStatus: 200,
      expectBody: (parsed) =>
        parsed &&
        parsed.rejected &&
        parsed.rejected[0] &&
        parsed.rejected[0].reason === "invalid_eid",
    },
    {
      name: "missing_gid",
      payload: { events: [makeEvent({ gid: undefined })] },
      expectStatus: 200,
      expectBody: (parsed) =>
        parsed &&
        parsed.rejected &&
        parsed.rejected[0] &&
        parsed.rejected[0].reason === "invalid_gid",
    },
    {
      name: "missing_ts",
      payload: { events: [makeEvent({ ts: undefined })] },
      expectStatus: 200,
      expectBody: (parsed) =>
        parsed &&
        parsed.rejected &&
        parsed.rejected[0] &&
        parsed.rejected[0].reason === "invalid_ts",
    },
    {
      name: "missing_exp",
      payload: { events: [makeEvent({ exp: undefined })] },
      expectStatus: 200,
      expectBody: (parsed) =>
        parsed &&
        parsed.rejected &&
        parsed.rejected[0] &&
        parsed.rejected[0].reason === "invalid_exp",
    },
    {
      name: "missing_body",
      payload: { events: [makeEvent({ body: undefined })] },
      expectStatus: 200,
      expectBody: (parsed) =>
        parsed &&
        parsed.rejected &&
        parsed.rejected[0] &&
        parsed.rejected[0].reason === "invalid_body",
    },
    {
      name: "missing_sig",
      payload: { events: [makeEvent({ sig: undefined })] },
      expectStatus: 200,
      expectBody: (parsed) =>
        parsed &&
        parsed.rejected &&
        parsed.rejected[0] &&
        parsed.rejected[0].reason === "invalid_sig",
    },
    {
      name: "invalid_eid",
      payload: { events: [makeEvent({ eid: "not-a-uuid" })] },
      expectStatus: 200,
      expectBody: (parsed) =>
        parsed &&
        parsed.rejected &&
        parsed.rejected[0] &&
        parsed.rejected[0].reason === "invalid_eid",
    },
    {
      name: "invalid_sig_type",
      payload: { events: [makeEvent({ sig: 1234 })] },
      expectStatus: 200,
      expectBody: (parsed) =>
        parsed &&
        parsed.rejected &&
        parsed.rejected[0] &&
        parsed.rejected[0].reason === "invalid_sig",
    },
    {
      name: "too_many_events",
      payload: {
        events: Array.from({ length: maxEventsPerRequest + 1 }, () =>
          makeEvent({})
        ),
      },
      expectStatus: 400,
      expectBody: (parsed) =>
        parsed &&
        parsed.error &&
        parsed.error.code === "too_many_events",
    },
    {
      name: "event_too_large",
      payload: { events: [buildLargeEvent()] },
      expectStatus: 200,
      expectBody: (parsed) =>
        parsed &&
        parsed.rejected &&
        parsed.rejected[0] &&
        parsed.rejected[0].reason === "event_too_large",
    },
    {
      name: "invalid_gid",
      payload: {
        events: [
          {
            eid: uuidv4(),
            gid: "@@@",
            ts: now,
            exp,
            body: { type: "CHAT.MSG" },
            sig: "abcd",
          },
        ],
      },
      expectStatus: 200,
      expectBody: (parsed) =>
        parsed &&
        parsed.rejected &&
        parsed.rejected[0] &&
        parsed.rejected[0].reason === "invalid_gid",
    },
    {
      name: "expired",
      payload: {
        events: [
          {
            ...makeEvent({ exp: now - 500 }),
          },
        ],
      },
      expectStatus: 200,
      expectBody: (parsed) =>
        parsed &&
        parsed.rejected &&
        parsed.rejected[0] &&
        parsed.rejected[0].reason === "expired",
    },
    {
      name: "future_ts",
      payload: {
        events: [
          {
            ...makeEvent({ ts: now + 9999, exp: now + 20000 }),
          },
        ],
      },
      expectStatus: 200,
      expectBody: (parsed) =>
        parsed &&
        parsed.rejected &&
        parsed.rejected[0] &&
        parsed.rejected[0].reason === "future_ts",
    },
    {
      name: "valid",
      payload: {
        events: [
          {
            ...makeEvent({}),
          },
        ],
      },
      expectStatus: 200,
      expectBody: (parsed) =>
        parsed &&
        Array.isArray(parsed.accepted) &&
        parsed.accepted.length === 1,
    },
  ];

  let failed = 0;
  for (const testCase of cases) {
    try {
      const response = await postJson("/v1/events:ingest", testCase.payload);
      let parsed = null;
      try {
        parsed = JSON.parse(response.body);
      } catch (err) {
        parsed = null;
      }

      const statusOk = response.status === testCase.expectStatus;
      const bodyOk = testCase.expectBody(parsed);
      const ok = statusOk && bodyOk;

      if (ok) {
        console.log(`PASS ${testCase.name}`);
      } else {
        failed += 1;
        console.log(`FAIL ${testCase.name}`);
        console.log(`  status: ${response.status}`);
        console.log(`  body: ${response.body}`);
      }
    } catch (err) {
      failed += 1;
      console.log(`FAIL ${testCase.name}`);
      console.log(`  error: ${err.message || String(err)}`);
    }
  }

  if (failed > 0) {
    process.exit(1);
  }

  // Duplicate/idempotent ingest check.
  try {
    const duplicateEid = uuidv4();
    const duplicateEvent = makeEvent({ eid: duplicateEid });
    const first = await postJson("/v1/events:ingest", { events: [duplicateEvent] });
    const second = await postJson("/v1/events:ingest", { events: [duplicateEvent] });
    const firstBody = JSON.parse(first.body);
    const secondBody = JSON.parse(second.body);
    const firstOk =
      first.status === 200 &&
      Array.isArray(firstBody.accepted) &&
      firstBody.accepted[0] === duplicateEid;
    const secondOk =
      second.status === 200 &&
      Array.isArray(secondBody.rejected) &&
      secondBody.rejected[0] &&
      secondBody.rejected[0].reason === "duplicate";

    if (firstOk && secondOk) {
      console.log("PASS duplicate");
    } else {
      failed += 1;
      console.log("FAIL duplicate");
      console.log(`  first: ${first.status} ${first.body}`);
      console.log(`  second: ${second.status} ${second.body}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL duplicate");
    console.log(`  error: ${err.message || String(err)}`);
  }

  // Fetch/pagination check.
  try {
    const fetchGid = "Z29vZGd" + uuidv4().slice(0, 6);
    const fetchBase = {
      gid: fetchGid,
      ts: now,
      exp: now + 600,
      body: { type: "CHAT.MSG" },
      sig: "abcd",
    };
    const events = [
      { ...fetchBase, eid: uuidv4(), ts: now + 1 },
      { ...fetchBase, eid: uuidv4(), ts: now + 2 },
      { ...fetchBase, eid: uuidv4(), ts: now + 3 },
    ];

    const ingest = await postJson("/v1/events:ingest", { events });
    if (ingest.status !== 200) {
      throw new Error(`ingest failed: ${ingest.status} ${ingest.body}`);
    }

    const firstFetch = await getJson(
      `/v1/events:fetch?gid=${encodeURIComponent(fetchGid)}&limit=2`
    );
    const firstBody = JSON.parse(firstFetch.body);
    const firstOk =
      firstFetch.status === 200 &&
      Array.isArray(firstBody.events) &&
      firstBody.events.length === 2 &&
      firstBody.next_cursor;

    const secondFetch = await getJson(
      `/v1/events:fetch?gid=${encodeURIComponent(fetchGid)}&limit=2&cursor=${encodeURIComponent(
        firstBody.next_cursor
      )}`
    );
    const secondBody = JSON.parse(secondFetch.body);
    const secondOk =
      secondFetch.status === 200 &&
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
      console.log(`  first: ${firstFetch.status} ${firstFetch.body}`);
      console.log(`  second: ${secondFetch.status} ${secondFetch.body}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL fetch_pagination");
    console.log(`  error: ${err.message || String(err)}`);
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
