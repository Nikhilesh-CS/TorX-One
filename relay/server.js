/**
 * TorX One — Relay Server
 *
 * A zero-knowledge relay that:
 *   1. Authenticates clients via Ed25519 challenge-response
 *   2. Routes encrypted blobs by recipient public key
 *   3. Queues messages for offline recipients (in-memory, capped)
 *   4. Exposes GET /inspect to prove it only ever sees ciphertext
 *
 * Platform-agnostic: works with iOS (swift-sodium), Android (lazysodium),
 * or any client that speaks the same WebSocket JSON protocol.
 *
 * It NEVER has decryption keys. Even if fully compromised, messages stay unreadable.
 */

const http = require('http');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');
const nacl = require('tweetnacl');

// ─── Configuration ───────────────────────────────────────────────────────────

const PORT = parseInt(process.env.PORT, 10) || 3000;
const MAX_QUEUE_PER_RECIPIENT = 100;

// ─── State ───────────────────────────────────────────────────────────────────

/** @type {Map<string, WebSocket>} ed25519PubKeyHex → WebSocket */
const clients = new Map();

/** @type {Map<string, Array<{id:string, from:string, ciphertext:string, nonce:string, signature:string|null, timestamp:number}>>} */
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
    for (const [pubkey, messages] of queues) {
      snapshot[pubkey] = messages.map(m => ({
        id: m.id,
        from: m.from,
        ciphertext: m.ciphertext,         // Full hex — verify it's gibberish
        ciphertextBytes: m.ciphertext.length / 2,
        nonce: m.nonce,
        signature: m.signature || null,
        timestamp: new Date(m.timestamp).toISOString(),
      }));
    }
    const body = JSON.stringify({
      server: 'astra-mesh-relay',
      connectedClients: clients.size,
      connectedKeys: [...clients.keys()].map(k => k.substring(0, 16) + '…'),
      queuedRecipients: queues.size,
      totalQueuedMessages: [...queues.values()].reduce((sum, q) => sum + q.length, 0),
      queues: snapshot,
      note: 'Every ciphertext value below is raw XSalsa20-Poly1305 (crypto_box) output. The relay has no decryption key. If you can read any message in plaintext here, something is broken.',
    }, null, 2);

    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(body);
    return;
  }

  // ── Status page (everything else) ──────────────────────────────────────
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({
    server: 'astra-mesh-relay',
    status: 'running',
    connectedClients: clients.size,
    endpoints: {
      websocket: `ws://localhost:${PORT}`,
      inspect: `http://localhost:${PORT}/inspect`,
    },
    note: 'Connect from the iOS or Android app via WebSocket.',
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

        // Boot any existing connection for this key (single-session)
        const existing = clients.get(clientPubKey);
        if (existing && existing !== ws && existing.readyState === 1) {
          send(existing, { type: 'error', message: 'Replaced by new connection' });
          existing.close();
        }

        clients.set(clientPubKey, ws);
        send(ws, { type: 'auth_ok' });
        log(`✓ Authenticated: ${shortKey(clientPubKey)}`);

        // Flush queued messages
        flushQueue(clientPubKey, ws);
      });
      return;
    }

    // ── Authenticated: handle message types ──────────────────────────────
    switch (msg.type) {
      case 'message':
        handleMessage(ws, clientPubKey, msg);
        break;

      case 'ack':
        // Acknowledged — nothing to do in this simple implementation
        break;

      default:
        send(ws, { type: 'error', message: `Unknown type: ${msg.type}` });
    }
  });

  ws.on('close', () => {
    if (clientPubKey) {
      // Only remove if this ws is still the registered one (might have been replaced)
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

// ─── Message Handler ─────────────────────────────────────────────────────────

function handleMessage(ws, senderPubKey, msg) {
  if (!msg.to || !msg.ciphertext || !msg.nonce) {
    send(ws, { type: 'error', message: 'Missing to, ciphertext, or nonce' });
    return;
  }

  const envelope = {
    id: generateId(),
    from: senderPubKey,
    ciphertext: msg.ciphertext,
    nonce: msg.nonce,
    signature: msg.signature || null,
    timestamp: Date.now(),
  };

  const recipientWs = clients.get(msg.to);
  if (recipientWs && recipientWs.readyState === 1) {
    // Recipient online → forward immediately
    send(recipientWs, {
      type: 'message',
      id: envelope.id,
      from: envelope.from,
      ciphertext: envelope.ciphertext,
      nonce: envelope.nonce,
      signature: envelope.signature,
    });
    log(`→ Relayed ${shortId(envelope.id)} : ${shortKey(senderPubKey)} → ${shortKey(msg.to)}`);
  } else {
    // Recipient offline → queue
    if (!queues.has(msg.to)) queues.set(msg.to, []);
    const queue = queues.get(msg.to);

    if (queue.length >= MAX_QUEUE_PER_RECIPIENT) {
      send(ws, { type: 'error', message: 'Recipient queue full (max 100)' });
      return;
    }

    queue.push(envelope);
    log(`⏳ Queued ${shortId(envelope.id)} for offline ${shortKey(msg.to)} (${queue.length} queued)`);
  }

  // Acknowledge to sender
  send(ws, { type: 'sent', id: envelope.id });
}

// ─── Queue Flush ─────────────────────────────────────────────────────────────

function flushQueue(pubKey, ws) {
  const queue = queues.get(pubKey);
  if (!queue || queue.length === 0) return;

  for (const m of queue) {
    send(ws, {
      type: 'message',
      id: m.id,
      from: m.from,
      ciphertext: m.ciphertext,
      nonce: m.nonce,
      signature: m.signature,
    });
  }
  log(`  Flushed ${queue.length} queued message(s) to ${shortKey(pubKey)}`);
  queues.delete(pubKey);
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
  ║          A S T R A   M E S H  —  Relay            ║
  ╠═══════════════════════════════════════════════════╣
  ║                                                   ║
  ║   WebSocket :  ws://localhost:${String(PORT).padEnd(5)}               ║
  ║   Inspector :  http://localhost:${String(PORT).padEnd(5)}/inspect     ║
  ║   Status    :  http://localhost:${String(PORT).padEnd(5)}             ║
  ║                                                   ║
  ║   Connect from iOS or Android app.                ║
  ║   The relay NEVER sees plaintext — verify at      ║
  ║   /inspect while messages are in-flight.          ║
  ║                                                   ║
  ╚═══════════════════════════════════════════════════╝
  `);
});
