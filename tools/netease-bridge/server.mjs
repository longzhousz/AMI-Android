import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const ID = /^[A-Fa-f0-9]{32}$/;
const limit = (value, max = 500) => Math.min(max, Math.max(0, Number(value) || 0));
const text = (value, max = 200) => String(value ?? '').slice(0, max);
export function mediaUrl(raw, artwork = false) {
  const uri = new URL(raw);
  if (!['http:', 'https:'].includes(uri.protocol) || uri.username || uri.password || (uri.port && uri.port !== '443') ||
    !(artwork ? /^p\d+\.music\.126\.net$/.test(uri.hostname) : uri.hostname.endsWith('.music.126.net'))) throw new Error('Unsupported media origin');
  uri.protocol = 'https:';
  if (artwork) uri.searchParams.set('param', '600y600');
  return uri.href;
}
const cover = raw => { try { return mediaUrl(raw, true); } catch { return null; } };
export function track(record) {
  if (!ID.test(record.id) || !Number.isSafeInteger(record.originalId) || record.originalId <= 0) return null;
  return { id: record.id.toUpperCase(), originalId: String(record.originalId), title: text(record.name),
    artist: text((record.artists || []).map(x => x.name).filter(Boolean).join('、')), album: text(record.album?.name),
    duration: limit(record.duration, 24 * 60 * 60 * 1000), cover: cover(record.coverImgUrl),
    available: record.visible === true && record.playFlag !== false && record.freeTrailFlag !== true };
}
function playlist(record) {
  if (!ID.test(record.id)) return null;
  return { id: record.id.toUpperCase(), title: text(record.name), cover: cover(record.coverImgUrl), count: limit(record.trackCount, 100000) };
}
function data(response) {
  if (response?.code !== 200) throw new Error('网易云暂未返回数据，请检查登录状态或当日额度');
  return response.data;
}

/** Only these read operations and the CLI's documented playback command are exposed. */
export function createHandler({ token, run, handoffFile }) {
  const expected = Buffer.from(token);
  const songs = new Map(), streams = new Map(), cached = new Map();
  let pending = Promise.resolve();
  const serial = task => { const next = pending.then(task, task); pending = next.catch(() => {}); return next; };
  const memo = async (key, task) => {
    const before = cached.get(key); if (before && before.until > Date.now()) return before.value;
    const value = await task(); cached.set(key, { value, until: Date.now() + 60000 });
    if (cached.size > 64) cached.delete(cached.keys().next().value); return value;
  };
  const remember = rows => rows.map(track).filter(Boolean).map(song => {
    songs.delete(song.id); songs.set(song.id, song); if (songs.size > 3000) songs.delete(songs.keys().next().value); return song;
  });
  const lookup = raw => { if (!ID.test(raw || '')) throw new Error('歌曲标识无效'); return raw.toUpperCase(); };
  const dispatch = async (route, body) => {
    if (route === '/v1/status') return memo('status', async () => ({ nickname: text(data(await run(['user', 'info', '--output', 'json'])).nickname || '网易云音乐') }));
    if (route === '/v1/playlists') return memo('playlists', async () => {
      const created = data(await run(['playlist', 'created', '--limit', '500', '--output', 'json']));
      const collected = data(await run(['playlist', 'collected', '--limit', '500', '--output', 'json']));
      const unique = new Map();
      for (const row of [...(created.records || []), ...(collected.records || [])]) { const item = playlist(row); if (item) unique.set(item.id, item); }
      return { playlists: [...unique.values()] };
    });
    if (route === '/v1/search' || route === '/v1/tracks') {
      const offset = limit(body.offset, 100000), args = route === '/v1/search' ? ['search', 'song', '--keyword', text(body.query, 100).trim()] : ['playlist', 'tracks', '--playlistId', lookup(body.id)];
      if (route === '/v1/search' && !args[3]) throw new Error('请输入搜索内容');
      return memo(JSON.stringify(args) + ':' + offset, async () => {
        const result = data(await run([...args, '--limit', '30', '--offset', String(offset), '--output', 'json']));
        // playlist.tracks returns an array; search.song returns a records envelope.
        const rows = Array.isArray(result) ? result : result.records || [], tracks = remember(rows);
        return { tracks, offset, nextOffset: offset + rows.length,
          more: Array.isArray(result) ? rows.length === 30 : offset + rows.length < result.recordCount };
      });
    }
    if (route === '/v1/lyrics') {
      const id = lookup(body.id); if (!songs.has(id)) throw new Error('请先打开歌曲所在歌单');
      return memo('lyrics:' + id, async () => {
        const result = data(await run(['song', 'lyric', '--songId', id, '--output', 'json']));
        return { original: text(result.lyric || result.txtLyric, 250000), translated: text(result.transLyric, 250000) };
      });
    }
    if (route === '/v1/stream') return serial(async () => {
      const id = lookup(body.id), item = songs.get(id);
      if (!item) throw new Error('请重新打开这首歌所在的歌单或搜索结果');
      // First preview deliberately excludes trial-only entries; never treat a trial URL as full access.
      if (!item.available) throw new Error('这首歌暂不支持在本试用版内播放，可在网易云音乐中收听');
      const before = streams.get(id); if (before && before.expiresAt > Date.now()) return before;
      const started = Date.now();
      await run(['queue', 'clear', '--output', 'json']);
      await run(['play', '--song', '--encrypted-id', id, '--original-id', item.originalId, '--output', 'json'], true);
      for (let attempt = 0; attempt < 150; attempt++) {
        let capture; try { capture = JSON.parse(fs.readFileSync(handoffFile, 'utf8')); } catch {}
        if (capture?.capturedAt >= started) {
          const result = { url: mediaUrl(capture.url), expiresAt: Date.now() + 20 * 60 * 1000 };
          streams.set(id, result); if (streams.size > 32) streams.delete(streams.keys().next().value);
          return result;
        }
        await delay(100);
      }
      throw new Error('官方播放器交接超时，请在电脑连接端重新登录后重试');
    });
    throw new Error('未知操作');
  };
  return async (req, res) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8'); res.setHeader('Cache-Control', 'no-store');
    const supplied = Buffer.from(String(req.headers.authorization || '').replace(/^Bearer /, ''));
    if (supplied.length !== expected.length || !crypto.timingSafeEqual(supplied, expected)) { res.writeHead(401); res.end('{"error":"连接码无效，请重新连接"}'); return; }
    if (req.method !== 'POST') { res.writeHead(405); res.end('{"error":"Unsupported method"}'); return; }
    try {
      let bytes = 0; const chunks = [];
      for await (const chunk of req) { bytes += chunk.length; if (bytes > 16384) throw new Error('请求过大'); chunks.push(chunk); }
      const body = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
      const result = await dispatch(req.url, body); res.end(JSON.stringify(result));
    } catch (error) {
      // Neither CLI output nor signed media URLs belong in logs or public error responses.
      res.writeHead(400); res.end(JSON.stringify({ error: /^(网易云|歌曲|请输入|请先|请重新|这首歌|官方播放器|未知操作|请求过大)/.test(error.message) ? error.message : '网易云连接暂不可用，请检查电脑端登录和网络' }));
    }
  };
}

async function adapter(folder) {
  const bin = path.join(folder, 'bin'); fs.mkdirSync(bin, { recursive: true, mode: 0o700 });
  const executable = path.join(bin, process.platform === 'win32' ? 'mpv.exe' : 'mpv');
  if (!fs.existsSync(executable)) {
    if (process.platform === 'win32') {
      await new Promise((resolve, reject) => {
        // Scope policy to this build process, so an unmodified script from the downloaded ZIP can run.
        const child = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', path.join(HERE, 'build-adapter.ps1'), '-Output', executable], { stdio: 'inherit' });
        child.once('error', reject); child.once('exit', code => code === 0 ? resolve() : reject(new Error('无法准备 Windows 播放适配器')));
      });
    } else {
      fs.writeFileSync(executable, '#!/usr/bin/env sh\nexec "$AMI_BRIDGE_NODE" "$AMI_BRIDGE_ADAPTER" "$@"\n', { mode: 0o700 });
    }
  }
  return bin;
}
async function main() {
  const folder = process.env.AMI_BRIDGE_STATE_DIR || path.join(process.env.LOCALAPPDATA || path.join(os.homedir(), '.local', 'share'), 'AMI', 'netease');
  fs.mkdirSync(folder, { recursive: true, mode: 0o700 });
  const bin = await adapter(folder), entry = process.env.AMI_NCM_CLI || require.resolve('@music163/ncm-cli');
  const env = { ...process.env, PATH: bin + path.delimiter + process.env.PATH, AMI_BRIDGE_NODE: process.execPath, AMI_BRIDGE_ADAPTER: path.join(HERE, 'player-adapter.cjs'), AMI_BRIDGE_STATE_DIR: folder };
  const run = (args, allowEmpty = false, interactive = false) => new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [entry, ...args], { env, shell: false, windowsHide: !interactive, stdio: interactive ? 'inherit' : ['ignore', 'pipe', 'pipe'] });
    // Credential entry and scanning the official QR need a human, not a 25-second deadline.
    let output = ''; const timeout = interactive ? undefined : setTimeout(() => { child.kill(); reject(new Error('官方播放器请求超时')); }, 25000);
    child.once('error', () => { clearTimeout(timeout); reject(new Error('无法启动官方 CLI')); });
    child.stdout?.on('data', bytes => { output += bytes.toString('utf8'); if (output.length > 3000000) child.kill(); });
    child.stderr?.on('data', () => {});
    child.once('exit', code => { clearTimeout(timeout); if (code !== 0) return reject(new Error('网易云连接暂不可用，请在电脑端重新登录'));
      if (interactive || (allowEmpty && !output.trim())) return resolve({});
      try { resolve(JSON.parse(output)); } catch { reject(new Error('网易云返回格式异常，请检查官方 CLI 版本')); }
    });
  });
  if (process.argv.includes('--setup')) {
    let configured = true; try { await run(['login', '--check', '--output', 'json']); } catch { configured = false; }
    if (!configured) await run(['configure'], true, true);
    const status = await run(['login', '--check', '--output', 'json']);
    if (!status.success) { await run(['login'], true, true); }
    if (!(await run(['login', '--check', '--output', 'json'])).success) throw new Error('请先完成官方 CLI 登录');
  }
  await run(['config', 'set', 'player', 'mpv'], true, true);
  const pairingFile = path.join(folder, 'pairing.json');
  let token; try { token = JSON.parse(fs.readFileSync(pairingFile, 'utf8')).token; } catch {}
  if (!/^[A-Za-z0-9_-]{43}$/.test(token || '')) { token = crypto.randomBytes(32).toString('base64url'); fs.writeFileSync(pairingFile, JSON.stringify({ token }), { mode: 0o600 }); }
  const port = Number(process.env.AMI_BRIDGE_PORT || 17863); if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid port');
  const server = http.createServer(createHandler({ token, run, handoffFile: process.env.AMI_BRIDGE_CAPTURE_FILE || path.join(folder, 'handoff.json') }));
  server.listen(port, process.env.AMI_BRIDGE_BIND || '0.0.0.0', () => {
    console.log('AMI 网易云连接端已启动。请保持此窗口打开。');
    if (process.env.AMI_BRIDGE_QUIET !== '1') {
      const addresses = Object.values(os.networkInterfaces()).flat().filter(x => x.family === 'IPv4' && !x.internal && /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(x.address));
      for (const address of addresses) console.log('在 AMI 中粘贴连接地址：http://' + address.address + ':' + port + '/#' + token);
      if (!addresses.length) console.log('请连接可信的局域网后重新启动连接端。');
    }
  });
  server.on('error', () => { console.error('连接端端口不可用，请关闭重复启动的窗口后重试。'); process.exitCode = 1; });
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) main().catch(() => { console.error('连接端启动失败，请检查 Node.js、官方 CLI 配置和登录状态。'); process.exitCode = 1; });
