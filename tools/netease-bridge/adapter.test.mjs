import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

test('Windows launcher and mpv IPC handoff preserve arguments without playing or downloading audio', { timeout: 25000 }, async t => {
  const root = path.dirname(fileURLToPath(import.meta.url));
  const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'AMI bridge 测试 '));
  const endpoint = process.platform === 'win32' ? '\\\\.\\pipe\\ami transport ' + process.pid : path.join(folder, 'player.sock');
  const env = { ...process.env, AMI_BRIDGE_STATE_DIR: folder, AMI_BRIDGE_NODE: process.execPath, AMI_BRIDGE_ADAPTER: path.join(root, 'player-adapter.cjs') };
  let binary = process.execPath, prefix = [env.AMI_BRIDGE_ADAPTER];
  if (process.platform === 'win32') {
    binary = path.join(folder, 'mpv.exe'); prefix = [];
    const compiled = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', path.join(root, 'build-adapter.ps1'), '-Output', binary], { encoding: 'utf8' });
    assert.equal(compiled.status, 0, compiled.stderr);
  }
  const version = spawnSync(binary, [...prefix, '--version'], { env, encoding: 'utf8' });
  assert.equal(version.status, 0, version.stderr); assert.match(version.stdout, /^mpv .*AMI JSON IPC/);
  const child = spawn(binary, [...prefix, '--input-ipc-server=' + endpoint], { env, stdio: 'ignore' });
  let peer;
  t.after(() => { peer?.destroy(); child.kill(); fs.rmSync(folder, { recursive: true, force: true }); });
  for (let i = 0; i < 100 && !peer; i++) {
    peer = await new Promise(resolve => { const socket = net.connect(endpoint); socket.once('error', () => { socket.destroy(); resolve(null); }); socket.once('connect', () => resolve(socket)); });
    if (!peer) await delay(50);
  }
  assert.ok(peer, 'IPC must become available, including when the socket name contains spaces');
  let buffer = '', next = 0; const waiting = new Map(), events = [];
  peer.on('data', bytes => {
    buffer += bytes.toString();
    while (buffer.includes('\n')) {
      const end = buffer.indexOf('\n'), message = JSON.parse(buffer.slice(0, end)); buffer = buffer.slice(end + 1);
      if (message.event) events.push(message); else { waiting.get(message.request_id)?.(message); waiting.delete(message.request_id); }
    }
  });
  const send = command => new Promise(resolve => { const id = ++next; waiting.set(id, resolve); peer.write(JSON.stringify({ command, request_id: id }) + '\n'); });
  assert.equal((await send(['get_property', 'pause'])).data, true);
  assert.equal((await send(['loadfile', 'https://unrelated.example/song.mp3'])).error, 'invalid command');
  assert.equal(fs.existsSync(path.join(folder, 'handoff.json')), false);
  const url = 'https://iot202.music.126.net/fixture.mp3?sign=one%2Btwo&other=three';
  assert.equal((await send(['loadfile', url])).error, 'success');
  const handoff = JSON.parse(fs.readFileSync(path.join(folder, 'handoff.json')));
  assert.equal(handoff.url, url); assert.ok(handoff.capturedAt > Date.now() - 5000);
  for (let i = 0; i < 20 && !events.some(x => x.event === 'file-loaded'); i++) await delay(10);
  assert.ok(events.some(x => x.event === 'file-loaded'));
  assert.equal((await send(['get_property', 'idle-active'])).data, false);
  assert.equal((await send(['get_property', 'pause'])).data, true);
  const finished = once(child, 'exit'); await send(['quit']);
  assert.equal((await finished)[0], 0);
});
