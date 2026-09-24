const assert = require('assert');
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const nacl = require('tweetnacl');
const WebSocket = require('ws');

const port = 32000 + Math.floor(Math.random() * 1000);
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'torx-relay-test-'));
const dataFile = path.join(tempDir, 'queues.json');
const relayUrl = `ws://127.0.0.1:${port}`;
const inspectUrl = `http://127.0.0.1:${port}/inspect`;
const queueId = 'queue-test-' + 'a'.repeat(48);
const queueToken = 'capability-' + 'b'.repeat(48);
const envelopeId = 'envelope-durable-1';

let server;

function startServer() {
  return new Promise((resolve, reject) => {
    server = spawn(process.execPath, [path.join(__dirname, 'server.js')], {
      env: {
        ...process.env,
        PORT: String(port),
        RELAY_DATA_FILE: dataFile,
        RELAY_INSPECT_ENABLED: 'true',
        RELAY_INSPECT_TOKEN: 'test-token',
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    const timeout = setTimeout(() => reject(new Error('relay start timeout')), 5000);
    server.stdout.on('data', data => {
      if (data.toString().includes('WebSocket')) {
        clearTimeout(timeout);
        resolve();
      }
    });
    server.stderr.on('data', data => process.stderr.write(data));
    server.once('exit', code => {
      if (code && code !== 0) reject(new Error(`relay exited with ${code}`));
    });
  });
}

function stopServer() {
  return new Promise(resolve => {
    if (!server || server.exitCode !== null) return resolve();
    server.once('exit', resolve);
    server.kill();
  });
}

function connectClient() {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(relayUrl);
    const keyPair = nacl.sign.keyPair();
    const waiters = new Map();
    const backlog = [];
    const timeout = setTimeout(() => reject(new Error('client auth timeout')), 5000);

    function deliver(message) {
      const waiter = waiters.get(message.type);
      if (waiter) {
        waiters.delete(message.type);
        waiter(message);
      } else {
        backlog.push(message);
      }
    }

    ws.on('message', raw => {
      const message = JSON.parse(raw.toString());
      if (message.type === 'challenge') {
        const nonce = Buffer.from(message.nonce, 'hex');
        ws.send(JSON.stringify({
          type: 'auth',
          publicKey: Buffer.from(keyPair.publicKey).toString('hex'),
          signature: Buffer.from(nacl.sign.detached(nonce, keyPair.secretKey)).toString('hex'),
        }));
      } else if (message.type === 'auth_ok') {
        clearTimeout(timeout);
        resolve({
          ws,
          next(type) {
            const index = backlog.findIndex(item => item.type === type);
            if (index >= 0) return Promise.resolve(backlog.splice(index, 1)[0]);
            return new Promise(nextResolve => waiters.set(type, nextResolve));
          },
        });
      } else {
        deliver(message);
      }
    });
    ws.once('error', reject);
  });
}

async function run() {
  try {
    await startServer();
    const receiver = await connectClient();
    receiver.ws.send(JSON.stringify({ type: 'subscribe', queueId, queueToken }));
    await receiver.next('subscribed');

    const sender = await connectClient();
    sender.ws.send(JSON.stringify({
      type: 'message',
      id: envelopeId,
      queueId,
      queueToken,
      payload: '{"version":2}',
    }));
    await sender.next('sent');
    const firstDelivery = await receiver.next('message');
    assert.strictEqual(firstDelivery.id, envelopeId);

    receiver.ws.close();
    sender.ws.close();
    await stopServer();

    await startServer();
    const restartedReceiver = await connectClient();
    restartedReceiver.ws.send(JSON.stringify({ type: 'subscribe', queueId, queueToken }));
    const redelivery = await restartedReceiver.next('message');
    assert.strictEqual(redelivery.id, envelopeId, 'unacked envelope must survive relay restart');
    restartedReceiver.ws.send(JSON.stringify({
      type: 'ack',
      id: redelivery.id,
      queueId,
      queueToken,
    }));

    await new Promise(resolve => setTimeout(resolve, 100));
    const response = await fetch(inspectUrl, { headers: { Authorization: 'Bearer test-token' } });
    const inspect = await response.json();
    assert.strictEqual(inspect.totalBufferedMessages, 0, 'durably ACKed envelope must be deleted');
    restartedReceiver.ws.close();
    console.log('relay durability test passed');
  } finally {
    await stopServer();
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

run().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
