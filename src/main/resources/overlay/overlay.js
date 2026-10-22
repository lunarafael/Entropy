(function (root) {
  "use strict";
  const MAX_MESSAGE_LENGTH = 65536;
  const count = value => Number.isSafeInteger(value) && value >= 0;
  function normalizeMessage(raw) {
    if (typeof raw === "string") {
      if (raw.length > MAX_MESSAGE_LENGTH) return null;
      try { raw = JSON.parse(raw); } catch (_) { return null; }
    }
    if (!raw || !["CREATE", "UPDATE", "END"].includes(raw.request)) return null;
    const modern = raw.protocolVersion !== undefined;
    if (modern && (raw.protocolVersion !== 2 || typeof raw.sessionId !== "string" ||
        !raw.sessionId.length || raw.sessionId.length > 128 || !count(raw.sequence) || !count(raw.pollId))) return null;
    if (!["MAJORITY", "PERCENTAGE"].includes(raw.votingMode)) return null;
    if (!count(raw.totalVotes)) return null;
    if (!Array.isArray(raw.voteOptions) || raw.voteOptions.length > 4 ||
        (raw.request !== "END" && raw.voteOptions.length < 1)) return null;
    const options = [];
    for (const option of raw.voteOptions) {
      if (!option || !count(option.value) || !Array.isArray(option.matches) ||
          option.matches.length < 1 || option.matches.length > 8 ||
          option.matches.some(match => typeof match !== "string" || !match.length || match.length > 32)) return null;
      // New servers resolve Components in Minecraft; old literal Components remain readable.
      const label = typeof option.labelText === "string" ? option.labelText :
        typeof option.label === "string" ? option.label : option.label && option.label.text;
      if (typeof label !== "string" || label.length > 2048) return null;
      options.push({label, matches: option.matches, value: option.value});
    }
    if (raw.request !== "END" && options.reduce((sum, option) => sum + option.value, 0) !== raw.totalVotes) return null;
    return {...raw, modern, options};
  }
  function percentage(value, total) { return total > 0 ? Math.min(100, Math.max(0, value / total * 100)) : 0; }
  class PollState {
    constructor() { this.reset(); }
    reset() { this.session = null; this.sequence = -1; this.poll = null; this.visible = false; }
    accept(message) {
      if (!message) return false;
      if (message.modern) {
        if (this.session !== null && this.session !== message.sessionId && message.request !== "CREATE") return false;
        if (this.session === message.sessionId && message.sequence <= this.sequence) return false;
        if (message.request === "UPDATE" && this.poll !== message.pollId) return false;
        if (message.request === "END" && this.poll !== null && this.poll !== message.pollId) return false;
        this.session = message.sessionId;
        this.sequence = message.sequence;
        this.poll = message.pollId;
      } else if (this.session !== null) return false;
      if (message.request === "UPDATE" && !this.visible) return false;
      this.visible = message.request !== "END";
      return true;
    }
  }
  function websocketUrl(value) {
    try {
      const url = new URL(value);
      if (!["ws:", "wss:"].includes(url.protocol) || url.username || url.password ||
          !["localhost", "127.0.0.1", "[::1]"].includes(url.hostname)) return null;
      return url.href;
    } catch (_) { return null; }
  }
  const api = {normalizeMessage, percentage, PollState, websocketUrl};
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (!root.document) return;

  const document = root.document;
  const params = new URLSearchParams(root.location.search);
  const poll = document.getElementById("poll");
  const options = document.getElementById("options");
  const status = document.getElementById("status");
  const timer = document.getElementById("timer");
  const state = new PollState();
  if (params.get("layout") === "horizontal") document.body.classList.add("horizontal");
  function debug(text) {
    if (params.get("debug") !== "1") return;
    status.hidden = false;
    status.textContent = text;
  }
  function element(tag, className, text) {
    const node = document.createElement(tag);
    node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }
  function receive(raw) {
    const message = normalizeMessage(raw);
    if (!state.accept(message)) { debug("Ignored invalid or outdated message."); return; }
    poll.hidden = !state.visible;
    if (!state.visible) return;
    options.replaceChildren();
    for (const option of message.options) {
      const row = element("li", "option");
      row.append(element("span", "key", option.matches[0]), element("span", "label", option.label));
      const result = element("span", "result");
      result.append(element("span", "percent", `${Math.round(percentage(option.value, message.totalVotes))}%`),
        element("span", "count", `${option.value} ${option.value === 1 ? "vote" : "votes"}`));
      const track = element("span", "track");
      const fill = element("span", "fill");
      fill.style.width = `${percentage(option.value, message.totalVotes)}%`;
      track.append(fill);
      row.append(result, track);
      options.append(row);
    }
    document.getElementById("total").textContent = `${message.totalVotes} ${message.totalVotes === 1 ? "vote" : "votes"}`;
    document.getElementById("hint").textContent = message.preview ? "PREVIEW · sample votes · no effect on gameplay" : message.votingMode === "PERCENTAGE" ?
      "Vote in chat · Each vote increases the effect's chance" : "Vote in chat · The effect with the most votes wins";
    // Only display the server's remaining ticks. A browser clock would drift while Minecraft is paused.
    timer.hidden = !count(message.remainingTicks);
    timer.textContent = timer.hidden ? "" : `${Math.ceil(message.remainingTicks / 20)}s`;
    debug(message.preview ? "PREVIEW · sample votes · no game connection" : "Connected · poll received");
  }
  if (params.get("preview") === "1") {
    let sequence = 0;
    const demo = () => receive({request: "CREATE", protocolVersion: 2, sessionId: "preview", pollId: 1,
      sequence: ++sequence, votingMode: "PERCENTAGE", totalVotes: 28, preview: true,
      voteOptions: ["Chicken rain", "Reverse gravity", "The floor is lava", "A very suspicious surprise…"]
        .map((labelText, i) => ({labelText, matches: [String(i + (params.get("numbers") === "5" ? 5 : 1))], value: [12, 8, 6, 2][i]}))});
    demo();
    document.getElementById("hint").textContent = "PREVIEW · sample votes · no game connection";
    return;
  }

  let connection = 0;
  let failures = 0;
  function connect(url) {
    const generation = ++connection;
    let socket;
    function reconnect() {
      if (generation !== connection) return;
      const retryGeneration = ++connection;
      poll.hidden = true;
      state.reset();
      debug("Disconnected · waiting for Minecraft / OBS integration");
      const delay = Math.min(30000, 1000 * 2 ** Math.min(failures++, 5)) + Math.random() * 500;
      // Refetch config.json too: a saved WebSocket port may change while the OBS URL stays the same.
      root.setTimeout(() => { if (retryGeneration === connection) start(); }, delay);
    }
    try { socket = new root.WebSocket(url); } catch (_) { reconnect(); return; }
    socket.onopen = () => {
      if (generation !== connection) return;
      failures = 0;
      debug("Connected · waiting for a poll");
    };
    socket.onmessage = event => { if (generation === connection) receive(event.data); };
    socket.onclose = reconnect;
    socket.onerror = () => { if (generation === connection) socket.close(); };
  }
  async function start() {
    const override = params.get("ws");
    let url = override ? websocketUrl(override) : null;
    if (override && !url) { debug("Invalid WebSocket URL: use a local address such as ws://127.0.0.1:PORT"); return; }
    if (!url && root.location.protocol !== "file:") {
      try {
        const response = await root.fetch("config.json", {cache: "no-store"});
        if (response.ok) url = websocketUrl((await response.json()).websocketUrl);
      } catch (_) { /* A file copy can still connect through the default local port. */ }
    }
    connect(url || "ws://127.0.0.1:9091");
  }
  start();
})(typeof globalThis !== "undefined" ? globalThis : this);
