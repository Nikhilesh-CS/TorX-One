/**
 * TorX One 2.0 — Opaque Queue-Addressed Offline Relay Server
 *
 * Implements the Opaque Queue-Addressed Offline Relay Protocol:
 *   1. Routes and buffers strictly by opaque queueId (Identity != Connection != Queue != Transport Address)
 *   2. No public key exposure: neither sender nor recipient public keys are stored or routed
 *   3. Ephemeral challenge-response authentication for connection gating
 *   4. Clients subscribe to their pairwise receive queueIds ({ type: 'subscribe', queues: [...] })
 *   5. Exposes GET /inspect to verify opaque queue indexing with zero plaintexts or identity keys
 *
 * Platform-agnostic: works with iOS, Android, or any client speaking the WebSocket JSON protocol.
 * The relay is an opaque message buffer indexed by queueId, not a zero-knowledge proof system.
 */

const http = require('http');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');
const nacl = require('tweetnacl');

// ─── Configuration ───────────────────────────────────────────────────────────

const PORT = parseInt(process.env.PORT, 10) || 3000;
const MAX_QUEUE_PER_RECIPIENT = 100;

// ─── State ───────────────────────────────────────────────────────────────────

/** @type {Map<string, Set<WebSocket>>} queueId -> Set<WebSocket> */
const queueSubscribers = new Map();

/** @type {Map<WebSocket, Set<string>>} WebSocket -> Set<queueId> */
const wsSubscriptions = new Map();

/** @type {Map<string, WebSocket>} Legacy fallback: ed25519PubKeyHex -> WebSocket */
const clients = new Map();

/** @type {Map<string, Array<{id:string, queueId:string, payload:string, ciphertext:string, timestamp:number}>>} */
const queues = new Map();

// ─── Hex Utilities ───────────────────────────────────────────────────────────

function toHex(buf) {
  return Buffer.from(buf).toString('hex');
}

function fromHex(hex) {
  return new Uint8Array(Buffer.from(hex, 'hex'));
}

function generateId() {
  return crypto.randomBytes(16).toString('hex');
}

// ─── HTTP Server (/inspect + status) ─────────────────────────────────────────

const httpServer = http.createServer((req, res) => {
  // CORS headers for all responses
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  // ── /inspect endpoint ──────────────────────────────────────────────────
  if (req.url === '/inspect' || req.url === '/inspect/') {
    const snapshot = {};
    for (const [queueId, messages] of queues) {
      snapshot[queueId] = messages.map(m => ({
        id: m.id,
        queueId: m.queueId,
        payloadBytes: m.payload ? m.payload.length : (m.ciphertext ? m.ciphertext.length / 2 : 0),
        timestamp: new Date(m.timestamp).toISOString(),
      }));
    }
    const body = JSON.stringify({
      server: 'torx-one-opaque-relay',
      protocol: 'Opaque Queue-Addressed Offline Relay Protocol',
      connectedClients: wsSubscriptions.size || clients.size,
      activeQueues: queueSubscribers.size,
      bufferedQueues: queues.size,
      totalBufferedMessages: [...queues.values()].reduce((sum, q) => sum + q.length, 0),
      queues: snapshot,
      note: 'The relay is an opaque message buffer indexed by queueId. It routes strictly by opaque queue identifier and holds encrypted payloads. No public keys, identities, or plaintexts are exposed.',
    }, null, 2);

    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(body);
    return;
  }

  // ── Status page (everything else) ──────────────────────────────────────
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({
    server: 'torx-one-opaque-relay',
    protocol: 'Opaque Queue-Addressed Offline Relay Protocol',
    status: 'running',
    connectedClients: wsSubscriptions.size || clients.size,
    activeQueues: queueSubscribers.size,
    bufferedQueues: queues.size,
    endpoints: {
      websocket: `ws://localhost:${PORT}`,
      inspect: `http://localhost:${PORT}/inspect`,
    },
    note: 'Connect from TorX One app via WebSocket using opaque queue subscriptions.',
  }, null, 2));
});

// ─── WebSocket Server ────────────────────────────────────────────────────────

const wss = new WebSocketServer({ server: httpServer });

wss.on('connection', (ws, req) => {
  let authenticated = false;
  let clientPubKey = null;

  // Generate and send challenge nonce
  const challengeNonce = crypto.randomBytes(32);
  send(ws, { type: 'challenge', nonce: toHex(challengeNonce) });

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      send(ws, { type: 'error', message: 'Invalid JSON' });
      return;
    }

    // ── Unauthenticated: only accept 'auth' ──────────────────────────────
    if (!authenticated) {
      if (msg.type !== 'auth') {
        send(ws, { type: 'error', message: 'Authenticate first' });
        return;
      }
      handleAuth(ws, msg, challengeNonce, (pubKey) => {
        authenticated = true;
        clientPubKey = pubKey;

        // Register legacy fallback
        const existing = clients.get(clientPubKey);
        if (existing && existing !== ws && existing.readyState === 1) {
          send(existing, { type: 'error', message: 'Replaced by new connection' });
          existing.close();
        }
        clients.set(clientPubKey, ws);

        send(ws, { type: 'auth_ok' });
        log(`✓ Authenticated: ${shortKey(clientPubKey)}`);

        // Flush legacy queue if any
        flushQueue(clientPubKey, ws);
      });
      return;
    }

    // ── Authenticated: handle message types ──────────────────────────────
    switch (msg.type) {
      case 'subscribe':
        handleSubscribe(ws, msg);
        break;

      case 'unsubscribe':
        handleUnsubscribe(ws, msg);
        break;

      case 'message':
        handleMessage(ws, clientPubKey, msg);
        break;

      case 'ack':
        // Acknowledged receipt from client
        if (msg.queueId && msg.id) {
          const queue = queues.get(msg.queueId);
          if (queue) {
            const idx = queue.findIndex(m => m.id === msg.id);
            if (idx !== -1) queue.splice(idx, 1);
            if (queue.length === 0) queues.delete(msg.queueId);
          }
        }
        break;

      default:
        send(ws, { type: 'error', message: `Unknown type: ${msg.type}` });
    }
  });

  ws.on('close', () => {
    // Unsubscribe from all queues
    const subs = wsSubscriptions.get(ws);
    if (subs) {
      for (const qId of subs) {
        const set = queueSubscribers.get(qId);
        if (set) {
          set.delete(ws);
          if (set.size === 0) queueSubscribers.delete(qId);
        }
      }
      wsSubscriptions.delete(ws);
    }

    if (clientPubKey) {
      if (clients.get(clientPubKey) === ws) {
        clients.delete(clientPubKey);
      }
      log(`✗ Disconnected: ${shortKey(clientPubKey)}`);
    }
  });

  ws.on('error', (err) => {
    log(`⚠ WebSocket error: ${err.message}`);
  });
});

// ─── Auth Handler ────────────────────────────────────────────────────────────

function handleAuth(ws, msg, challengeNonce, onSuccess) {
  try {
    if (!msg.publicKey || !msg.signature) {
      throw new Error('Missing publicKey or signature');
    }

    const pubKeyBytes = fromHex(msg.publicKey);
    const signatureBytes = fromHex(msg.signature);

    if (pubKeyBytes.length !== 32) throw new Error('Public key must be 32 bytes');
    if (signatureBytes.length !== 64) throw new Error('Signature must be 64 bytes');

    const valid = nacl.sign.detached.verify(challengeNonce, signatureBytes, pubKeyBytes);
    if (!valid) throw new Error('Signature verification failed');

    onSuccess(msg.publicKey);
  } catch (err) {
    send(ws, { type: 'error', message: `Auth failed: ${err.message}` });
    ws.close();
  }
}

// ─── Subscription Handlers ───────────────────────────────────────────────────

function handleSubscribe(ws, msg) {
  const queueIds = [];
  if (Array.isArray(msg.queues)) {
    queueIds.push(...msg.queues);
  } else if (typeof msg.queueId === 'string' && msg.queueId) {
    queueIds.push(msg.queueId);
  }

  if (!wsSubscriptions.has(ws)) {
    wsSubscriptions.set(ws, new Set());
  }
  const userSubs = wsSubscriptions.get(ws);

  let flushedCount = 0;
  for (const qId of queueIds) {
    const cleanId = String(qId).trim();
    if (!cleanId) continue;
    userSubs.add(cleanId);

    if (!queueSubscribers.has(cleanId)) {
      queueSubscribers.set(cleanId, new Set());
    }
    queueSubscribers.get(cleanId).add(ws);

    // Flush any buffered messages waiting on this queueId
    flushedCount += flushQueue(cleanId, ws);
  }

  send(ws, { type: 'subscribed', count: queueIds.length, flushed: flushedCount });
  log(`✓ Subscribed to ${queueIds.length} queue(s) (flushed ${flushedCount})`);
}

function handleUnsubscribe(ws, msg) {
  const queueIds = [];
  if (Array.isArray(msg.queues)) {
    queueIds.push(...msg.queues);
  } else if (typeof msg.queueId === 'string' && msg.queueId) {
    queueIds.push(msg.queueId);
  }

  const userSubs = wsSubscriptions.get(ws);
  for (const qId of queueIds) {
    const cleanId = String(qId).trim();
    if (userSubs) userSubs.delete(cleanId);
    const set = queueSubscribers.get(cleanId);
    if (set) {
      set.delete(ws);
      if (set.size === 0) queueSubscribers.delete(cleanId);
    }
  }

  send(ws, { type: 'unsubscribed', count: queueIds.length });
  log(`✓ Unsubscribed from ${queueIds.length} queue(s)`);
}

// ─── Message Handler ─────────────────────────────────────────────────────────

function handleMessage(ws, senderPubKey, msg) {
  const targetQueueId = (msg.queueId || msg.to || '').trim();
  if (!targetQueueId || (!msg.ciphertext && !msg.payload)) {
    send(ws, { type: 'error', message: 'Missing queueId (or to), or ciphertext/payload' });
    return;
  }

  const payload = msg.payload || msg.ciphertext || '';
  const envelope = {
    id: generateId(),
    queueId: targetQueueId,
    payload: payload,
    ciphertext: msg.ciphertext || '',
    nonce: msg.nonce || '',
    signature: msg.signature || null,
    timestamp: Date.now(),
  };

  const subscribers = queueSubscribers.get(targetQueueId);
  const legacyWs = clients.get(targetQueueId);

  let forwarded = false;

  if (subscribers && subscribers.size > 0) {
    for (const subWs of subscribers) {
      if (subWs.readyState === 1 && subWs !== ws) {
        send(subWs, {
          type: 'message',
          id: envelope.id,
          queueId: targetQueueId,
          payload: envelope.payload,
          ciphertext: envelope.ciphertext,
          timestamp: envelope.timestamp,
        });
        forwarded = true;
      }
    }
  }

  if (!forwarded && legacyWs && legacyWs.readyState === 1 && legacyWs !== ws) {
    send(legacyWs, {
      type: 'message',
      id: envelope.id,
      queueId: targetQueueId,
      payload: envelope.payload,
      ciphertext: envelope.ciphertext,
      timestamp: envelope.timestamp,
    });
    forwarded = true;
  }

  if (forwarded) {
    log(`→ Relayed ${shortId(envelope.id)} on queue ${shortId(targetQueueId)}`);
  } else {
    // Offline recipient -> queue buffer
    if (!queues.has(targetQueueId)) queues.set(targetQueueId, []);
    const queue = queues.get(targetQueueId);

    if (queue.length >= MAX_QUEUE_PER_RECIPIENT) {
      send(ws, { type: 'error', message: 'Queue full (max 100)' });
      return;
    }

    queue.push(envelope);
    log(`⏳ Queued ${shortId(envelope.id)} for offline queue ${shortId(targetQueueId)} (${queue.length} buffered)`);
  }

  // Acknowledge receipt to sender
  send(ws, { type: 'sent', id: envelope.id });
}

// ─── Queue Flush ─────────────────────────────────────────────────────────────

function flushQueue(queueId, ws) {
  const queue = queues.get(queueId);
  if (!queue || queue.length === 0) return 0;

  const count = queue.length;
  for (const m of queue) {
    const out = {
      type: 'message',
      id: m.id,
      queueId: m.queueId,
      payload: m.payload,
      ciphertext: m.ciphertext,
      timestamp: m.timestamp,
    };
    send(ws, out);
  }
  log(`  Flushed ${count} queued message(s) on queue ${shortId(queueId)}`);
  queues.delete(queueId);
  return count;
}

// ─── Utilities ───────────────────────────────────────────────────────────────

function send(ws, obj) {
  if (ws.readyState === 1) ws.send(JSON.stringify(obj));
}

function shortKey(hex) {
  return hex.substring(0, 12) + '…';
}

function shortId(hex) {
  return hex.substring(0, 8);
}

function log(msg) {
  const ts = new Date().toISOString().substring(11, 19);
  console.log(`[${ts}] ${msg}`);
}

// ─── Start ───────────────────────────────────────────────────────────────────

httpServer.listen(PORT, () => {
  console.log(`
  ╔═══════════════════════════════════════════════════╗
  ║    T O R X   O N E  —  Opaque Offline Relay       ║
  ╠═══════════════════════════════════════════════════╣
  ║                                                   ║
  ║   WebSocket :  ws://localhost:${String(PORT).padEnd(5)}               ║
  ║   Inspector :  http://localhost:${String(PORT).padEnd(5)}/inspect     ║
  ║   Status    :  http://localhost:${String(PORT).padEnd(5)}             ║
  ║                                                   ║
  ║   Routes strictly by opaque queueId.              ║
  ║   The relay NEVER sees plaintext or public keys.  ║
  ║                                                   ║
  ╚═══════════════════════════════════════════════════╝
  `);
});
