import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { createHandler, mediaUrl, track } from './server.mjs';

const TOKEN = 'test-pairing-token-that-is-never-a-real-secret';
const ID = 'A'.repeat(32);
const record = { id: ID, originalId: 1234, name: '测试歌曲', artists: [{ name: '测试艺人' }], album: { name: '测试专辑' },
  duration: 180000, coverImgUrl: 'http://p1.music.126.net/test.jpg', visible: true, playFlag: true, freeTrailFlag: false };
async function fixture(t, run) {
  const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'ami-cloud-test-'));
  const handoffFile = path.join(folder, 'handoff.json');
  const calls = [];
  const server = http.createServer(createHandler({ token: TOKEN, handoffFile, run: async (args, allowEmpty) => {
    calls.push(args); return run(args, { handoffFile, allowEmpty });
  } }));
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); fs.rmSync(folder, { recursive: true, force: true }); });
  const request = async (route, body = {}, token = TOKEN) => {
    const response = await fetch('http://127.0.0.1:' + server.address().port + '/v1/' + route, {
      method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' }, body: JSON.stringify(body)
    });
    return { status: response.status, body: await response.json() };
  };
  return { request, calls };
}
test('pairing is required before any CLI operation; unknown operations never execute commands', async t => {
  const { request, calls } = await fixture(t, () => assert.fail('CLI should not run'));
  assert.equal((await request('status', {}, 'wrong')).status, 401);
  assert.equal((await request('arbitrary-command')).status, 400);
  assert.equal(calls.length, 0);
});
test('search remains an argument, pages preserve their cursor, and repeated reads use a bounded cache', async t => {
  const { request, calls } = await fixture(t, () => ({ code: 200, data: { records: [record], recordCount: 31 } }));
  const query = 'a & echo example | $(whoami)';
  const first = await request('search', { query });
  assert.equal(first.status, 200); assert.equal(first.body.tracks[0].title, record.name);
  assert.equal(first.body.nextOffset, 1); assert.equal(first.body.more, true);
  assert.equal(calls[0][3], query);
  await request('search', { query }); assert.equal(calls.length, 1);
});
test('public errors omit raw CLI output and signed media URLs', async t => {
  const { request } = await fixture(t, () => { throw new Error('PRIVATE_KEY=secret https://example.test/token'); });
  const response = await request('status');
  assert.equal(response.status, 400); assert.doesNotMatch(JSON.stringify(response), /PRIVATE_KEY|example\.test|secret/);
});
test('official playlist tracks use an array response and retain pagination across full pages', async t => {
  const { request } = await fixture(t, args => ({ code: 200, data: args[args.indexOf('--offset') + 1] === '0'
    ? Array.from({ length: 30 }, (_, index) => ({ ...record, id: index.toString(16).padStart(32, 'A'), originalId: index + 100 })) : [record] }));
  const first = await request('tracks', { id: ID });
  assert.equal(first.body.tracks.length, 30); assert.equal(first.body.more, true); assert.equal(first.body.nextOffset, 30);
  const second = await request('tracks', { id: ID, offset: 30 });
  assert.equal(second.body.tracks.length, 1); assert.equal(second.body.more, false); assert.equal(second.body.nextOffset, 31);
});
test('trial-only entries do not initiate playback, while full entries hand off through the CLI and cache the result', async t => {
  const { request, calls } = await fixture(t, (args, { handoffFile }) => {
    if (args[0] === 'search') return { code: 200, data: { records: [record, { ...record, id: 'B'.repeat(32), freeTrailFlag: true }], recordCount: 2 } };
    if (args[0] === 'play') fs.writeFileSync(handoffFile, JSON.stringify({ url: 'http://iot202.music.126.net/test.mp3', capturedAt: Date.now() }));
    return {};
  });
  await request('search', { query: 'fixture' });
  assert.equal((await request('stream', { id: 'B'.repeat(32) })).status, 400);
  assert.equal(calls.filter(x => x[0] === 'play').length, 0);
  const first = await request('stream', { id: ID });
  assert.equal(first.body.url, 'https://iot202.music.126.net/test.mp3');
  await request('stream', { id: ID }); assert.equal(calls.filter(x => x[0] === 'play').length, 1);
  assert.deepEqual(calls.find(x => x[0] === 'play'), ['play', '--song', '--encrypted-id', ID, '--original-id', '1234', '--output', 'json']);
});
test('CDN origin checks reject credentials, unrelated hosts, non-HTTPS ports, and unsupported identifiers', () => {
  for (const raw of ['https://music.126.net.evil.test/song', 'https://user@p1.music.126.net/a', 'https://p1.music.126.net:8080/a', 'file:///a']) assert.throws(() => mediaUrl(raw));
  assert.equal(mediaUrl('http://p1.music.126.net/a', true), 'https://p1.music.126.net/a?param=600y600');
  assert.equal(track({ ...record, originalId: Number.MAX_SAFE_INTEGER + 2 }), null);
  assert.equal(track({ ...record, visible: false }).available, false);
});
