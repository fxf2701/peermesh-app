#!/usr/bin/env node
/* eslint-disable no-console */

const crypto = require("crypto");
const fs = require("fs");
const net = require("net");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");

const registryBaseUrl = process.env.REGISTRY_BASE_URL || "http://127.0.0.1:8090";
const registryAdminUsername = process.env.REGISTRY_ADMIN_USERNAME || "admin";
const registryAdminPassword = process.env.REGISTRY_ADMIN_PASSWORD || "admin1234";
const chromeBinary = process.env.CHROME_BIN || "google-chrome";
const pageTimeoutMs = Number(process.env.REGISTRY_ADMIN_BROWSER_TIMEOUT_MS || "30000");
const pollIntervalMs = Number(process.env.REGISTRY_ADMIN_BROWSER_POLL_MS || "250");

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function requestJson(pathName, { method = "GET", body, cookie } = {}) {
  const res = await fetch(`${registryBaseUrl}${pathName}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(cookie ? { Cookie: cookie } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const payload = await res.json().catch(() => ({}));
  return { res, payload };
}

async function waitFor(fn, label, timeoutMs = pageTimeoutMs) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    const result = await fn();
    if (result) return result;
    await sleep(pollIntervalMs);
  }
  throw new Error(`timeout waiting for ${label}`);
}

async function loginRegistryAdmin() {
  const { res } = await requestJson("/admin/api/auth/login", {
    method: "POST",
    body: {
      username: registryAdminUsername,
      password: registryAdminPassword,
    },
  });
  if (res.status !== 200) {
    throw new Error(`registry admin login failed with status ${res.status}`);
  }
  const cookieHeader = res.headers.get("set-cookie");
  if (!cookieHeader) {
    throw new Error("registry admin login did not return session cookie");
  }
  return cookieHeader.split(";")[0];
}

async function httpJson(url) {
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`http ${res.status} for ${url}`);
  }
  return res.json();
}

function encodeFrame(payload) {
  const payloadBuffer = Buffer.from(payload, "utf8");
  const payloadLength = payloadBuffer.length;
  let header = null;
  if (payloadLength < 126) {
    header = Buffer.alloc(2);
    header[1] = 0x80 | payloadLength;
  } else if (payloadLength < 65536) {
    header = Buffer.alloc(4);
    header[1] = 0x80 | 126;
    header.writeUInt16BE(payloadLength, 2);
  } else {
    header = Buffer.alloc(10);
    header[1] = 0x80 | 127;
    header.writeBigUInt64BE(BigInt(payloadLength), 2);
  }
  header[0] = 0x81;
  const mask = crypto.randomBytes(4);
  const masked = Buffer.alloc(payloadLength);
  for (let i = 0; i < payloadLength; i += 1) {
    masked[i] = payloadBuffer[i] ^ mask[i % 4];
  }
  return Buffer.concat([header, mask, masked]);
}

function decodeFrames(buffer) {
  const messages = [];
  let offset = 0;
  while (offset + 2 <= buffer.length) {
    const first = buffer[offset];
    const second = buffer[offset + 1];
    const opcode = first & 0x0f;
    let payloadLength = second & 0x7f;
    let cursor = offset + 2;
    if (payloadLength === 126) {
      if (cursor + 2 > buffer.length) break;
      payloadLength = buffer.readUInt16BE(cursor);
      cursor += 2;
    } else if (payloadLength === 127) {
      if (cursor + 8 > buffer.length) break;
      payloadLength = Number(buffer.readBigUInt64BE(cursor));
      cursor += 8;
    }
    const masked = (second & 0x80) !== 0;
    const maskLength = masked ? 4 : 0;
    if (cursor + maskLength + payloadLength > buffer.length) break;
    let payload = buffer.subarray(cursor + maskLength, cursor + maskLength + payloadLength);
    if (masked) {
      const mask = buffer.subarray(cursor, cursor + 4);
      const unmasked = Buffer.alloc(payloadLength);
      for (let i = 0; i < payloadLength; i += 1) {
        unmasked[i] = payload[i] ^ mask[i % 4];
      }
      payload = unmasked;
    }
    messages.push({
      opcode,
      payload,
      bytesConsumed: cursor + maskLength + payloadLength - offset,
    });
    offset = cursor + maskLength + payloadLength;
  }
  return { messages, remaining: buffer.subarray(offset) };
}

class CdpClient {
  constructor(wsUrl) {
    this.wsUrl = new URL(wsUrl);
    this.socket = null;
    this.nextId = 1;
    this.pending = new Map();
    this.buffer = Buffer.alloc(0);
    this.events = [];
  }

  async connect() {
    const key = crypto.randomBytes(16).toString("base64");
    this.socket = net.createConnection({
      host: this.wsUrl.hostname,
      port: Number(this.wsUrl.port || 80),
    });

    await new Promise((resolve, reject) => {
      let handshake = "";
      const onData = (chunk) => {
        handshake += chunk.toString("utf8");
        if (!handshake.includes("\r\n\r\n")) return;
        this.socket.off("data", onData);
        if (!handshake.startsWith("HTTP/1.1 101")) {
          reject(new Error(`websocket handshake failed: ${handshake.split("\r\n")[0] || "unknown"}`));
          return;
        }
        resolve();
      };
      this.socket.on("data", onData);
      this.socket.on("error", reject);
      const request = [
        `GET ${this.wsUrl.pathname}${this.wsUrl.search} HTTP/1.1`,
        `Host: ${this.wsUrl.host}`,
        "Upgrade: websocket",
        "Connection: Upgrade",
        `Sec-WebSocket-Key: ${key}`,
        "Sec-WebSocket-Version: 13",
        "\r\n",
      ].join("\r\n");
      this.socket.write(request);
    });

    this.socket.on("data", (chunk) => {
      this.buffer = Buffer.concat([this.buffer, chunk]);
      const decoded = decodeFrames(this.buffer);
      this.buffer = decoded.remaining;
      for (const message of decoded.messages) {
        if (message.opcode === 0x8) {
          this.close();
          continue;
        }
        if (message.opcode === 0x9) {
          this.socket.write(Buffer.concat([Buffer.from([0x8a, message.payload.length]), message.payload]));
          continue;
        }
        if (message.opcode !== 0x1) continue;
        let parsed = null;
        try {
          parsed = JSON.parse(message.payload.toString("utf8"));
        } catch (_) {
          parsed = null;
        }
        if (!parsed) continue;
        if (parsed.id && this.pending.has(parsed.id)) {
          const { resolve, reject } = this.pending.get(parsed.id);
          this.pending.delete(parsed.id);
          if (parsed.error) reject(new Error(parsed.error.message || "cdp error"));
          else resolve(parsed.result);
          continue;
        }
        this.events.push(parsed);
      }
    });
  }

  async send(method, params = {}) {
    const id = this.nextId;
    this.nextId += 1;
    const payload = JSON.stringify({ id, method, params });
    const promise = new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
    });
    this.socket.write(encodeFrame(payload));
    return promise;
  }

  takeEvents(method) {
    const matched = this.events.filter((event) => event.method === method);
    this.events = this.events.filter((event) => event.method !== method);
    return matched;
  }

  async evaluate(expression) {
    const result = await this.send("Runtime.evaluate", {
      expression,
      awaitPromise: true,
      returnByValue: true,
    });
    if (result.exceptionDetails) {
      throw new Error(`runtime evaluate failed: ${expression}`);
    }
    return result.result ? result.result.value : undefined;
  }

  async waitForLoad() {
    await waitFor(async () => {
      const events = this.takeEvents("Page.loadEventFired");
      return events.length > 0;
    }, "page load");
  }

  close() {
    if (this.socket) {
      this.socket.destroy();
      this.socket = null;
    }
  }
}

async function launchChrome() {
  const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), "registry-admin-browser-"));
  const chrome = spawn(chromeBinary, [
    "--headless=new",
    "--disable-gpu",
    "--no-sandbox",
    "--remote-debugging-port=0",
    `--user-data-dir=${userDataDir}`,
    "about:blank",
  ], {
    stdio: ["ignore", "pipe", "pipe"],
  });

  let devtoolsUrl = "";
  const parseOutput = (chunk) => {
    const text = chunk.toString("utf8");
    const match = text.match(/DevTools listening on (ws:\/\/[^\s]+)/);
    if (match) devtoolsUrl = match[1];
  };
  chrome.stdout.on("data", parseOutput);
  chrome.stderr.on("data", parseOutput);

  await waitFor(() => devtoolsUrl || null, "chrome devtools url");
  const browserWsUrl = devtoolsUrl;
  const browserHttpUrl = browserWsUrl.replace(/^ws:/, "http:").replace(/\/devtools\/browser\/.*$/, "");
  const targets = await httpJson(`${browserHttpUrl}/json/list`);
  const pageTarget = Array.isArray(targets)
    ? targets.find((target) => target.type === "page" && target.webSocketDebuggerUrl)
    : null;
  if (!pageTarget) {
    chrome.kill("SIGTERM");
    throw new Error("unable to find chrome page target");
  }
  return {
    chrome,
    userDataDir,
    wsUrl: pageTarget.webSocketDebuggerUrl,
  };
}

async function main() {
  console.log("admin_federation_browser_smoke_start");

  const cookie = await loginRegistryAdmin();
  await requestJson("/admin/api/federation/sessions", { cookie });

  const { chrome, userDataDir, wsUrl } = await launchChrome();
  const client = new CdpClient(wsUrl);
  try {
    await client.connect();
    await client.send("Page.enable");
    await client.send("Runtime.enable");

    await client.send("Page.navigate", { url: `${registryBaseUrl}/admin/federation` });
    await client.waitForLoad();

    const loginVisible = await waitFor(
      () => client.evaluate("Boolean(document.querySelector('#login-form'))"),
      "login form visible"
    );
    assert(loginVisible, "login form should render");

    await client.evaluate(`
      (() => {
        document.querySelector('input[name="username"]').value = ${JSON.stringify(registryAdminUsername)};
        document.querySelector('input[name="password"]').value = ${JSON.stringify(registryAdminPassword)};
        document.querySelector('#login-form button[type="submit"]').click();
        return true;
      })()
    `);

    const sessionsLoaded = await waitFor(
      () => client.evaluate(`
        (() => {
          const panel = document.getElementById('app-panel');
          const body = document.getElementById('federation-sessions-body');
          return Boolean(panel && !panel.classList.contains('hidden') && body && body.querySelectorAll('tr').length > 0);
        })()
      `),
      "federation sessions table"
    );
    assert(sessionsLoaded, "federation session list should load after login");
    console.log("PASS admin_federation_browser_login_and_list");

    const detailHref = await client.evaluate(`
      (() => {
        const link = document.querySelector('#federation-sessions-body a.button-link');
        return link ? link.href : '';
      })()
    `);
    assert(detailHref, "session detail link should exist");
    await client.send("Page.navigate", { url: detailHref });
    await client.waitForLoad();

    const detailLoaded = await waitFor(
      () => client.evaluate(`
        (() => {
          const title = document.getElementById('federation-session-title');
          const peers = document.getElementById('federation-session-peers-body');
          return Boolean(title && title.textContent && peers && peers.querySelectorAll('tr').length > 0);
        })()
      `),
      "federation session detail view"
    );
    assert(detailLoaded, "session detail page should render");
    console.log("PASS admin_federation_browser_session_detail");

    await client.send("Page.navigate", { url: `${registryBaseUrl}/admin/federation` });
    await client.waitForLoad();
    await waitFor(
      () => client.evaluate("Boolean(document.getElementById('federation-sessions-body') && document.getElementById('federation-sessions-body').querySelector('button[data-action=\"session-revoke\"]'))"),
      "session revoke button"
    );

    await client.evaluate(`
      (() => {
        window.prompt = () => 'browser smoke revoke';
        window.confirm = () => true;
        const button = document.querySelector('#federation-sessions-body button[data-action="session-revoke"]:not([disabled])');
        if (!button) return false;
        button.click();
        return true;
      })()
    `);

    const revoked = await waitFor(
      () => client.evaluate(`
        (() => {
          return Array.from(document.querySelectorAll('#federation-sessions-body tr')).some((row) => {
            const status = row.querySelector('td:nth-child(8) .badge');
            return status && status.textContent.trim().toLowerCase() === 'revoked';
          });
        })()
      `),
      "revoked session status after UI action"
    );
    assert(revoked, "session revoke button should update session status");
    console.log("PASS admin_federation_browser_revoke_action");

    await client.evaluate(`
      (() => {
        const button = document.getElementById('logout-button');
        if (button) button.click();
        return true;
      })()
    `);
    const loggedOut = await waitFor(
      () => client.evaluate(`
        (() => {
          const panel = document.getElementById('login-panel');
          return Boolean(panel && !panel.classList.contains('hidden'));
        })()
      `),
      "logout to show login panel"
    );
    assert(loggedOut, "logout should show login panel");
    console.log("PASS admin_federation_browser_logout");
  } finally {
    client.close();
    const chromeExited = new Promise((resolve) => {
      chrome.once("exit", resolve);
      chrome.once("error", resolve);
    });
    chrome.kill("SIGTERM");
    await Promise.race([chromeExited, sleep(3000)]);
    fs.rmSync(userDataDir, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.log("FAIL admin_federation_browser_smoke");
  console.log(`  error: ${err && err.message ? err.message : String(err)}`);
  process.exit(1);
});
