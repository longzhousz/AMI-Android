// A transport adapter for mpv's public JSON IPC protocol. Audio plays in AMI.
const fs = require('node:fs');
const net = require('node:net');
const path = require('node:path');
const argv = process.argv.slice(2);
if (argv.includes('--version')) { console.log('mpv 0.34.0 (AMI JSON IPC handoff adapter)'); process.exit(0); }
const flag = '--input-ipc-server';
const position = argv.indexOf(flag);
const socket = argv.find(x => x.startsWith(flag + '='))?.slice(flag.length + 1) || (position >= 0 ? argv[position + 1] : null);
if (!socket) process.exit(2);
const folder = process.env.AMI_BRIDGE_STATE_DIR;
if (!folder) process.exit(2);
const target = path.join(folder, 'handoff.json');
const state = { pause: true, 'idle-active': true, 'time-pos': 0, duration: null, path: '', 'media-title': '', volume: 100 };
const peers = new Set();
const observers = new Map();
const event = message => { for (const peer of peers) peer.write(JSON.stringify(message) + '\n'); };
const changed = name => { for (const [id, property] of observers) if (property === name) event({ event: 'property-change', id, name, data: state[name] }); };
const server = net.createServer(peer => {
  peers.add(peer); let buffer = '';
  peer.on('data', bytes => {
    buffer += bytes.toString('utf8');
    if (buffer.length > 65536) return peer.destroy();
    while (buffer.includes('\n')) {
      const end = buffer.indexOf('\n'), line = buffer.slice(0, end); buffer = buffer.slice(end + 1);
      let request;
      try {
        request = JSON.parse(line);
        const [command, ...args] = request.command || [];
        let data = null, error = 'success';
        if (command === 'get_property') {
          if (!(args[0] in state) || state[args[0]] === null) error = 'property unavailable'; else data = state[args[0]];
        } else if (command === 'set_property') {
          if (args[0] in state) { state[args[0]] = args[1]; changed(args[0]); } else error = 'property unavailable';
        } else if (command === 'observe_property') { observers.set(args[0], args[1]); setTimeout(() => changed(args[1]), 1); }
        else if (command === 'loadfile') {
          const uri = new URL(args[0]);
          if (!['http:', 'https:'].includes(uri.protocol) || !uri.hostname.endsWith('.music.126.net') || uri.username || uri.password) throw new Error('Unexpected media origin');
          state.path = args[0]; state['idle-active'] = false; state.pause = true;
          const temporary = target + '.' + process.pid;
          fs.writeFileSync(temporary, JSON.stringify({ url: args[0], capturedAt: Date.now() }), { mode: 0o600 }); fs.renameSync(temporary, target);
          setTimeout(() => { event({ event: 'file-loaded' }); changed('path'); changed('idle-active'); }, 5);
        } else if (command === 'stop') { state.pause = true; state['idle-active'] = true; changed('pause'); }
        else if (command === 'seek') { state['time-pos'] = Number(args[0]); changed('time-pos'); }
        else if (command === 'quit') { peer.end(JSON.stringify({ error, request_id: request.request_id }) + '\n'); setTimeout(() => process.exit(0), 10); continue; }
        else if (!['request_log_messages', 'enable_event'].includes(command)) error = 'command not found';
        peer.write(JSON.stringify({ data, error, request_id: request.request_id }) + '\n');
      } catch { peer.write(JSON.stringify({ error: 'invalid command', request_id: request?.request_id }) + '\n'); }
    }
  });
  const cleanup = () => peers.delete(peer); peer.on('close', cleanup); peer.on('error', cleanup);
});
server.on('error', () => process.exit(2)); server.listen(socket);
process.on('SIGTERM', () => { for (const peer of peers) peer.destroy(); server.close(() => process.exit(0)); });
