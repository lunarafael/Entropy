const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const script = path.resolve(__dirname, '../../src/main/resources/overlay/overlay.js');
const {normalizeMessage, percentage, PollState, websocketUrl} = require(script);

function packet(changes = {}) {
  return {request: 'CREATE', protocolVersion: 2, sessionId: 'minecraft-a', pollId: 1, sequence: 1,
    totalVotes: 4, votingMode: 'PERCENTAGE', voteOptions: [{labelText: 'Gravidade invertida', matches: ['5'], value: 4}], ...changes};
}
const normalized = changes => normalizeMessage(packet(changes));

test('normalizes resolved labels, legacy literals and the actual voting number', () => {
  assert.equal(normalized().options[0].matches[0], '5');
  assert.equal(normalized().options[0].label, 'Gravidade invertida');
  const legacy = packet();
  delete legacy.protocolVersion;
  legacy.voteOptions = [{label: {text: 'Ação <b>literal</b>'}, matches: ['8'], value: 4}];
  assert.equal(normalizeMessage(JSON.stringify(legacy)).options[0].label, 'Ação <b>literal</b>');
});
test('rejects malformed, oversized, unsupported and inconsistent input', () => {
  for (const input of ['{', 'x'.repeat(65537), null, packet({protocolVersion: 99}),
    packet({sequence: -1}), packet({totalVotes: 0}), packet({voteOptions: []}),
    packet({voteOptions: [{labelText: 'bad', matches: [5], value: 4}]}),
    packet({voteOptions: [{labelText: 'bad', matches: ['5'], value: -1}]})]) {
    assert.equal(normalizeMessage(input), null);
  }
});
test('zero votes produce an empty finite bar', () => {
  assert.equal(percentage(0, 0), 0);
  assert.ok(Math.abs(percentage(1, 3) - 100 / 3) < 1e-10);
  assert.ok(Number.isFinite(percentage(0, 0)));
});
test('sequence, poll and session guards reject stale traffic', () => {
  const state = new PollState();
  assert.equal(state.accept(normalized()), true);
  assert.equal(state.accept(normalized({sequence: 1, request: 'UPDATE'})), false);
  assert.equal(state.accept(normalized({sequence: 2, request: 'UPDATE', pollId: 9})), false);
  assert.equal(state.accept(normalized({sequence: 2, request: 'END', voteOptions: [], totalVotes: 0})), true);
  assert.equal(state.visible, false);
  assert.equal(state.accept(normalized({sequence: 3, pollId: 2})), true);
  assert.equal(state.accept(normalized({sequence: 4, request: 'END', pollId: 1, voteOptions: [], totalVotes: 0})), false);
  assert.equal(state.visible, true);
  assert.equal(state.accept(normalized({sequence: 1, sessionId: 'minecraft-b'})), true);
  assert.equal(state.accept(normalized({sequence: 5, request: 'UPDATE'})), false);
});
test('late connection can receive idle END and reset accepts a restarted server', () => {
  const state = new PollState();
  assert.equal(state.accept(normalized({request: 'END', totalVotes: 0, voteOptions: []})), true);
  state.reset();
  assert.equal(state.accept(normalized()), true);
});
test('WebSocket URLs only accept local hosts and websocket protocols', () => {
  assert.equal(websocketUrl('ws://127.0.0.1:1234'), 'ws://127.0.0.1:1234/');
  assert.equal(websocketUrl('ws://localhost:9091'), 'ws://localhost:9091/');
  assert.equal(websocketUrl('ws://[::1]:9091'), 'ws://[::1]:9091/');
  assert.equal(websocketUrl('wss://example.com'), null);
  assert.equal(websocketUrl('https://localhost:9091'), null);
  assert.equal(websocketUrl('ws://user:pass@localhost:9091'), null);
});

function browser(search = '', environment = {}) {
  class Element {
    constructor() { this.hidden = true; this.children = []; this.style = {}; this.textContent = ''; this.classList = {add() {}}; }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = children; }
  }
  const nodes = Object.fromEntries(['poll', 'options', 'status', 'timer', 'total', 'hint'].map(id => [id, new Element()]));
  const sockets = [], timers = [];
  class WebSocket {
    constructor(url) { this.url = url; sockets.push(this); }
    close() { this.onclose(); }
  }
  const context = {URL, URLSearchParams, console, Math, document: {getElementById: id => nodes[id],
    createElement: () => new Element(), body: new Element()}, location: {protocol: environment.protocol || 'file:', search}, WebSocket,
    fetch: environment.fetch,
    setTimeout: (fn, delay) => timers.push({fn, delay})};
  vm.runInNewContext(fs.readFileSync(script, 'utf8'), context);
  return {nodes, sockets, timers};
}
test('preview displays four fictional choices without opening a socket', () => {
  const {nodes, sockets} = browser('?preview=1&numbers=5');
  assert.equal(sockets.length, 0);
  assert.equal(nodes.poll.hidden, false);
  assert.equal(nodes.options.children.length, 4);
  assert.equal(nodes.options.children[0].children[0].textContent, '5');
  assert.match(nodes.hint.textContent, /PREVIEW/);
});
test('DOM uses literal text, server ticks, hides END and reconnects without stale callbacks', () => {
  const {nodes, sockets, timers} = browser('?debug=1');
  const socket = sockets[0];
  socket.onmessage({data: JSON.stringify(packet({remainingTicks: 65,
    voteOptions: [{labelText: '<img src=x onerror=alert(1)> ação', matches: ['8'], value: 4}]}))});
  assert.equal(nodes.options.children[0].children[1].textContent, '<img src=x onerror=alert(1)> ação');
  assert.equal(nodes.timer.textContent, '4s');
  assert.equal(nodes.poll.hidden, false);
  socket.onmessage({data: JSON.stringify(packet({request: 'END', sequence: 2, totalVotes: 0, voteOptions: []}))});
  assert.equal(nodes.poll.hidden, true);
  socket.onclose();
  assert.ok(timers[0].delay >= 1000 && timers[0].delay < 1500);
  socket.onmessage({data: JSON.stringify(packet({sequence: 3}))});
  assert.equal(nodes.poll.hidden, true);
  socket.onclose();
  assert.equal(timers.length, 1);
  timers[0].fn();
  sockets[1].onmessage({data: JSON.stringify(packet({sessionId: 'minecraft-b'}))});
  assert.equal(nodes.poll.hidden, false);
  assert.equal(nodes.timer.hidden, true);
  socket.onclose();
  assert.equal(nodes.poll.hidden, false);
});

test('hosted reconnect discovers a changed websocket port without refreshing OBS', async () => {
  let port = 9091;
  const {sockets, timers} = browser('', {protocol: 'http:', fetch: async () => ({
    ok: true, json: async () => ({websocketUrl: `ws://127.0.0.1:${port}`})
  })});
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(sockets[0].url, 'ws://127.0.0.1:9091/');
  sockets[0].onclose();
  port = 9191;
  timers[0].fn();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(sockets[1].url, 'ws://127.0.0.1:9191/');
});
