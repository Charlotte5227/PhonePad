// PhonePad ブラウザ版 — 接続・キーボード・マウス・その他
"use strict";

const $ = (id) => document.getElementById(id);
// iPhone・iPad (画面の端からなぞるとOSの操作が優先されるので、端にボタンを置かない)
const IS_IOS = /iPhone|iPad|iPod/.test(navigator.userAgent) || (/Macintosh/.test(navigator.userAgent) && navigator.maxTouchPoints > 1);
if (IS_IOS) document.documentElement.classList.add("ios");
// iPhone の Safari (ホーム画面から開いていないとき)。iOS 26 の Safari は下のツールバーがページに重なり、
// ページからはその高さが分からないので、余白を空ける (設定で調整できる)
const IS_IOS_SAFARI = IS_IOS && navigator.standalone === false;
const SAFARI_VER = parseInt((navigator.userAgent.match(/Version\/(\d+)/) || [])[1] || "0", 10);
if (IS_IOS_SAFARI) document.documentElement.classList.add("ios-safari");
const enc = new TextEncoder();
const dec = new TextDecoder();

// ---------------------------------------------------------------- 小道具

function b64(s) {
  const b = enc.encode(s);
  let bin = "";
  for (let i = 0; i < b.length; i += 0x8000) bin += String.fromCharCode.apply(null, b.subarray(i, i + 0x8000));
  return btoa(bin);
}
function unb64(s) {
  try {
    const bin = atob(s.trim());
    const b = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) b[i] = bin.charCodeAt(i);
    return dec.decode(b);
  } catch (e) { return ""; }
}
function b64urlBytes(s) {
  s = s.replace(/-/g, "+").replace(/_/g, "/");
  while (s.length % 4) s += "=";
  const bin = atob(s);
  const b = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) b[i] = bin.charCodeAt(i);
  return b;
}
function rawStore(k, s) { try { if (s === "" || s == null) localStorage.removeItem("pp." + k); else localStorage.setItem("pp." + k, s); } catch (e) {} }
function rawLoad(k) { try { return localStorage.getItem("pp." + k) || ""; } catch (e) { return ""; } }
// 配置・ショートカットは PC とやりとりする (Sync)。アプリごとの配置を使っている間は、そちらを読み書きする
const SYNC_RE = /^(shortcuts|kbCustom|padLayout|padAreas|kbLayout\.[A-Za-z]{2,8}(\.land)?)$/;
function store(k, v) {
  const s = JSON.stringify(v);
  if (SYNC_RE.test(k)) { Sync.write(k, s); return; }
  rawStore(k, s);
}
function unstore(k) {
  if (SYNC_RE.test(k)) { Sync.write(k, ""); return; }
  rawStore(k, "");
}
function load(k, def) {
  const s = SYNC_RE.test(k) && Sync.overlay && k in Sync.overlay.items ? Sync.overlay.items[k] : rawLoad(k);
  if (!s) return def;
  try { return JSON.parse(s); } catch (e) { return def; }
}
let toastTimer = 0;
function toast(msg) {
  let t = document.querySelector(".toast");
  if (!t) { t = document.createElement("div"); t.className = "toast"; document.body.appendChild(t); }
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 3500);
}

// 設定 (このブラウザに保存)
const prefs = Object.assign({
  mouseSpeed: 1.5, mouseAccel: 0.6, scrollSpeed: 1.0, invertTwoFinger: false,
  turboRate: 15, positional: false, floatingSticks: true,
  scrControl: true, scrQuality: 1, scrDragCursor: false, mutePc: false,
  keyRepeatRate: 16, keyRepeatDelay: 400, scrAutoZoom: true, oneHand: false, oneHandLeft: false,
  bottomGap: SAFARI_VER >= 26 && /iPhone/.test(navigator.userAgent) ? 64 : 0,
}, load("prefs", {}));
function savePrefs() { store("prefs", prefs); applyBottomGap(); }
function applyBottomGap() { document.documentElement.style.setProperty("--xb", (IS_IOS_SAFARI ? prefs.bottomGap : 0) + "px"); }
applyBottomGap();

// ---------------------------------------------------------------- 接続 (暗号化)

// 暗号化した WebSocket 1本分。first は最初に送る1行 (鍵が正しいかをPCが確かめる)
function SecureSock(path, key, first, onPayload, onClose) {
  const ws = new WebSocket("ws://" + location.host + path);
  ws.binaryType = "arraybuffer";
  let k = null, tx = 0, rx = 0;
  const self = {
    ws,
    get open() { return k !== null && ws.readyState === 1; },
    send(kind, bytes) {
      if (!this.open) return;
      tx++;
      const plain = new Uint8Array(bytes.length + 1);
      plain[0] = kind.charCodeAt(0);
      plain.set(bytes, 1);
      ws.send(nacl.secretbox(plain, nonce(1, tx), k));
    },
    close() { try { ws.close(); } catch (e) {} },
  };
  ws.onmessage = (ev) => {
    const data = new Uint8Array(ev.data);
    if (!k) {
      // "PPW1" + ランダム24バイト → この接続だけの鍵を作る
      if (data.length !== 28 || dec.decode(data.subarray(0, 4)) !== "PPW1") { ws.close(); return; }
      const m = new Uint8Array(32 + 24);
      m.set(key, 0);
      m.set(data.subarray(4), 32);
      k = nacl.hash(m).subarray(0, 32);
      self.send(first[0], enc.encode(first[1]));
      if (self.onReady) setTimeout(() => self.onReady(), 0);
      return;
    }
    rx++;
    const plain = nacl.secretbox.open(data, nonce(2, rx), k);
    if (!plain || plain.length === 0) { ws.close(); return; }
    onPayload(String.fromCharCode(plain[0]), plain.subarray(1));
  };
  ws.onclose = () => onClose && onClose();
  ws.onerror = () => {};
  return self;
}

const Link = {
  main: null, media: null, key: null, code: "",
  state: "idle", fatal: false, handlers: [], onState: [], pingTimer: 0, retryTimer: 0, mediaTimer: 0,
  pcName: "", pcVersion: "", wantMedia: false, onMediaReady: [], mediaUsers: new Set(),

  init() {
    // QRコードのURL: http://PC:47806/#鍵.接続コード (ホーム画面に追加しても残るよう、そのままにする)
    const h = location.hash.replace(/^#/, "");
    let creds = null;
    if (h.includes(".")) {
      const [k, c] = h.split(".");
      creds = { k, c };
      store("creds", creds);
    } else {
      creds = load("creds", null);
    }
    try {
      if (creds) { this.key = b64urlBytes(creds.k); this.code = creds.c; }
    } catch (e) { this.key = null; }
    if (!this.key || this.key.length !== 32 || !/^\d{6}$/.test(this.code)) {
      $("gate").hidden = false;
      return false;
    }
    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState === "visible") {
        if (this.state !== "open" && this.state !== "connecting" && !this.fatal) this.connect();
      } else {
        this.send("RA"); // 押しっぱなしを全部離す
      }
    });
    window.addEventListener("pageshow", () => { if (this.state === "closed" && !this.fatal) this.connect(); });
    this.connect();
    return true;
  },

  setState(s, text, cls) {
    this.state = s;
    const st = $("status");
    st.textContent = text;
    st.className = "status " + (cls || "");
    this.onState.forEach((f) => f(s));
  },

  connect() {
    clearTimeout(this.retryTimer);
    if (this.main) this.main.close();
    this.closeMedia();
    this.setState("connecting", "PCに接続しています…");
    let sock;
    try {
      sock = SecureSock("/ws", this.key, ["L", "HELLO " + this.code + " " + b64(deviceName()) + " 2"],
        (kind, body) => {
          if (sock !== this.main) return;
          if (kind === "L") this.onLine(dec.decode(body));
          else this.handlers.forEach((h) => h.kind === kind && h.fn(body));
        },
        () => {
          if (sock !== this.main) return;
          clearInterval(this.pingTimer);
          this.closeMedia();
          if (this.fatal) return;
          this.setState("closed", "切断されました。つなぎ直しています…", "warn");
          this.retry();
        });
    } catch (e) { this.retry(); return; }
    this.main = sock;
  },

  // 画面と音は別の通信路で送る (大きなデータの後ろでクリックなどが待たされないように)
  openMedia() {
    clearTimeout(this.mediaTimer);
    if (this.media || this.state !== "open") return;
    const sock = SecureSock("/ws?media=1", this.key, ["M", this.code],
      (kind, body) => { if (sock === this.media) this.handlers.forEach((h) => h.kind === kind && h.fn(body)); },
      () => {
        if (sock !== this.media) return;
        this.media = null;
        if (this.wantMedia && this.state === "open") this.mediaTimer = setTimeout(() => this.openMedia(), 1000);
      });
    sock.onReady = () => { if (sock === this.media) this.onMediaReady.forEach((f) => f()); };
    this.media = sock;
  },
  closeMedia() { clearTimeout(this.mediaTimer); if (this.media) { const m = this.media; this.media = null; m.close(); } },
  // 画面・音のどちらかを使っている間だけ開いておく
  needMedia(who, on) {
    if (on) this.mediaUsers.add(who); else this.mediaUsers.delete(who);
    this.wantMedia = this.mediaUsers.size > 0;
    if (this.wantMedia) this.openMedia(); else this.closeMedia();
  },
  get mediaReady() { return !!(this.media && this.media.open); },
  sendMedia(kind, bytes) { if (this.media) this.media.send(kind, bytes); },

  retry() {
    clearTimeout(this.retryTimer);
    this.retryTimer = setTimeout(() => {
      if (document.visibilityState === "visible" && !this.fatal) this.connect();
    }, 1500);
  },

  onLine(line) {
    if (line === "PONG") return;
    const f = line.split(" ");
    if (f[0] === "OK") {
      this.pcName = unb64(f[1] || "") || "PC";
      this.pcVersion = f[3] || "";
      this.setState("open", "● " + this.pcName, "ok");
      clearInterval(this.pingTimer);
      this.pingTimer = setInterval(() => this.send("PING"), 1000);
      if (this.wantMedia) this.openMedia();
      return;
    }
    if (line.startsWith("NG code")) {
      this.fatal = true;
      this.setState("closed", "接続コードが変わりました", "warn");
      $("gateMsg").textContent = "PCの接続コードが変更されたため、このページは使えなくなりました。PCの PhonePad の画面にある「iPhone・ブラウザで使う」のQRコードを、もう一度読み取ってください。";
      $("gate").hidden = false;
      try { localStorage.removeItem("pp.creds"); } catch (e) {}
      return;
    }
    if (line.startsWith("NG blocked")) {
      this.setState("closed", "失敗が続いたため、少し待ってからつなぎ直します", "warn");
      return;
    }
    if (line === "BYE") {
      this.fatal = true;
      this.closeMedia();
      this.setState("closed", "PCの画面から切断されました（ページを開き直すとつながります）", "warn");
      if (this.main) this.main.close();
      return;
    }
    if (line.startsWith("ER ")) { toast(unb64(line.slice(3))); return; }
    this.handlers.forEach((h) => h.kind === "L" && line.startsWith(h.prefix) && h.fn(line.slice(h.prefix.length)));
  },

  on(prefix, fn) { this.handlers.push({ kind: "L", prefix, fn }); },
  onData(kind, fn) { this.handlers.push({ kind, fn }); },

  get ready() { return this.state === "open" && this.main && this.main.open; },

  send(line) { if (this.ready) this.main.send("L", enc.encode(line)); },
};

// ---------------------------------------------------------------- 設定のやりとり (PC の画面から変えられるように)
// スマホで変えた分は「まだ伝えていない」印をつけておき、つないだときにまとめて送る。
const Sync = {
  overlay: null, // PC で前に出ているアプリ用の配置 {id, name, items}
  ready: false,
  pending: {}, timer: 0,
  isKey(k) { return SYNC_RE.test(k); },
  deviceId() {
    let id = rawLoad("deviceId");
    if (!/^[A-Za-z0-9_-]{8,64}$/.test(id)) {
      const b = new Uint8Array(12);
      crypto.getRandomValues(b);
      id = "w-" + Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
      rawStore("deviceId", id);
    }
    return id;
  },
  dirty() { try { return JSON.parse(rawLoad("syncDirty") || "[]"); } catch (e) { return []; } },
  setDirty(list) { rawStore("syncDirty", list.length ? JSON.stringify(list) : ""); },
  write(k, s) {
    if (this.overlay && k in this.overlay.items) {
      this.overlay.items[k] = s;
      Link.send("CPK " + b64(JSON.stringify({ id: this.overlay.id, items: { [k]: s } })));
      return;
    }
    rawStore(k, s);
    const d = this.dirty();
    if (!d.includes(k)) { d.push(k); this.setDirty(d); }
    // つながっていればすぐ伝える (続けて変えたときはまとめる)
    if (this.ready && Link.ready) {
      this.pending[k] = s;
      clearTimeout(this.timer);
      this.timer = setTimeout(() => {
        const items = this.pending;
        this.pending = {};
        if (Object.keys(items).length) Link.send("CFK " + b64(JSON.stringify({ items })));
      }, 250);
    }
  },
  items() {
    const out = { shortcuts: "", kbCustom: "", padLayout: "", padAreas: "" };
    try {
      for (let i = 0; i < localStorage.length; i++) {
        const k = localStorage.key(i);
        if (k && k.startsWith("pp.") && this.isKey(k.slice(3))) out[k.slice(3)] = localStorage.getItem(k) || "";
      }
    } catch (e) {}
    return out;
  },
  onOpen() {
    this.ready = true;
    Link.send("CFG " + b64(JSON.stringify({ id: this.deviceId(), kind: "web", name: deviceName(), items: this.items(), dirty: this.dirty() })));
  },
  // 画面を作り直す
  reload() {
    if (typeof KbStage !== "undefined") KbStage.reload();
    if (window.Pad && Pad.reload) Pad.reload();
    shortcuts = load("shortcuts", null) || DEFAULT_SC.map((s) => Object.assign({}, s));
  },
};
Link.onState.push((s) => {
  if (s === "open") Sync.onOpen();
  else {
    Sync.ready = false;
    if (Sync.overlay) { Sync.overlay = null; Sync.reload(); }
  }
});
Link.on("CA ", (b) => {
  let keys; try { keys = JSON.parse(unb64(b)); } catch (e) { return; }
  Sync.setDirty(Sync.dirty().filter((k) => !keys.includes(k)));
});
Link.on("CU ", (b) => {
  let o; try { o = JSON.parse(unb64(b)); } catch (e) { return; }
  for (const [k, v] of Object.entries(o.items || {})) if (Sync.isKey(k)) rawStore(k, v);
  Sync.reload();
  toast("PCの画面で変えた設定を受け取りました");
});
Link.on("CP", (rest) => {
  const b = rest.trim();
  const before = Sync.overlay;
  if (!b) {
    Sync.overlay = null;
    if (before) toast("いつもの配置に戻しました");
  } else {
    let o; try { o = JSON.parse(unb64(b)); } catch (e) { return; }
    const items = {};
    for (const [k, v] of Object.entries(o.items || {})) if (Sync.isKey(k)) items[k] = v;
    Sync.overlay = { id: o.id, name: o.name || "", items };
    if (!before || before.id !== o.id) toast("PCで使っているアプリに合わせて、「" + Sync.overlay.name + "」の配置にしました");
  }
  Sync.reload();
});

// nonce = 向き(1バイト) + 通し番号(8バイト, 大きい桁から) + 0
function nonce(dir, n) {
  const b = new Uint8Array(24);
  b[0] = dir;
  for (let i = 8; i >= 1; i--) { b[i] = n % 256; n = Math.floor(n / 256); }
  return b;
}

function deviceName() {
  const ua = navigator.userAgent;
  if (/iPhone/.test(ua)) return "iPhone（ブラウザ）";
  if (/iPad|Macintosh/.test(ua) && navigator.maxTouchPoints > 1) return "iPad（ブラウザ）";
  if (/Android/.test(ua)) return "Android（ブラウザ）";
  return "ブラウザ";
}

// ---------------------------------------------------------------- タブ

const Views = {
  current: "kbView",
  listeners: {},
  show(id) {
    if (id === this.current) return;
    const prev = this.current;
    this.current = id;
    document.querySelectorAll(".view").forEach((v) => { v.hidden = v.id !== id; });
    // コントローラーと画面表示は、画面いっぱいに使う
    document.body.classList.toggle("full", id === "padView" || id === "scrView");
    document.querySelectorAll("#tabs button").forEach((b) => b.classList.toggle("on", b.dataset.v === id));
    (this.listeners[prev] || []).forEach((f) => f(false));
    (this.listeners[id] || []).forEach((f) => f(true));
  },
  on(id, fn) { (this.listeners[id] = this.listeners[id] || []).push(fn); },
};
document.querySelectorAll("#tabs button").forEach((b) => { b.onclick = () => Views.show(b.dataset.v); });

// ---------------------------------------------------------------- キーボードが出たとき
// iPhone では、キーボードが出ても画面の大きさが変わらず下が隠れるので、見えている範囲に合わせる
if (window.visualViewport) {
  const vv = window.visualViewport;
  const fit = () => {
    // 見えている範囲 (キーボードやツールバーを除いた部分) にぴったり合わせる
    const full = document.documentElement.clientHeight;
    const shrunk = vv.height < full - 1;
    document.body.style.height = shrunk ? vv.height + "px" : "";
    document.body.style.transform = shrunk && vv.offsetTop ? "translateY(" + vv.offsetTop + "px)" : "";
    window.dispatchEvent(new Event("pp-resize"));
  };
  vv.addEventListener("resize", fit);
  vv.addEventListener("scroll", fit);
  window.addEventListener("load", fit);
  setTimeout(fit, 300);
}

// iPhone で2本指の操作をしたときに、ページ全体が拡大されてずれないようにする
["gesturestart", "gesturechange", "gestureend"].forEach((t) => document.addEventListener(t, (e) => e.preventDefault(), { passive: false }));
document.addEventListener("touchmove", (e) => { if (e.scale !== undefined && e.scale !== 1) e.preventDefault(); }, { passive: false });
// ずれてしまったときは元に戻す
window.addEventListener("scroll", () => { if (window.scrollX || window.scrollY) window.scrollTo(0, 0); });

// 画面を切り替えた直後の「おまけのクリック」を捨てる
// (指を離したときのクリックが、切り替え後に同じ場所に出てきたボタンに当たってしまうため)
let blockClicksUntil = 0;
function blockClicks(ms) { blockClicksUntil = performance.now() + (ms || 450); }
document.addEventListener("click", (e) => {
  if (performance.now() < blockClicksUntil) { e.stopPropagation(); e.preventDefault(); }
}, true);

// ---------------------------------------------------------------- 修飾キー (Ctrl など)

const CTRL = 1, SHIFT = 2, ALT = 4, WIN = 8;
const Mods = {
  oneShot: 0, locked: 0, buttons: {},
  get mask() { return this.oneShot | this.locked; },
  tap(bit) {
    // 1回目: 次の1キーだけ、2回目: 押しっぱなし、3回目: 解除
    if (this.locked & bit) this.locked &= ~bit;
    else if (this.oneShot & bit) { this.oneShot &= ~bit; this.locked |= bit; }
    else this.oneShot |= bit;
    this.paint();
  },
  consume() { const m = this.mask; if (this.oneShot) { this.oneShot = 0; this.paint(); } return m; },
  hasCommand() { return (this.mask & (CTRL | ALT | WIN)) !== 0; },
  paint() {
    for (const [bit, list] of Object.entries(this.buttons)) {
      for (const b of list) {
        b.classList.toggle("on", (this.mask & bit) !== 0);
        b.classList.toggle("lock", (this.locked & bit) !== 0);
      }
    }
  },
};

function sendKey(k) { Link.send("KY " + k + " " + Mods.consume()); }

// 文字を送る (Enter は Enter キー、Ctrl などが付いていればショートカット)
let echoHook = null;
function sendText(s) {
  let buf = "";
  const flush = () => { if (buf) { Link.send("TX " + b64(buf)); buf = ""; } };
  for (const ch of s) {
    if (ch === "\n") { flush(); sendKey("ENTER"); continue; }
    if (ch === "\r") continue;
    if (Mods.hasCommand()) { flush(); Link.send("KY C" + ch.codePointAt(0).toString(16) + " " + Mods.consume()); continue; }
    buf += ch;
    if (Mods.oneShot) Mods.consume();
  }
  flush();
  if (echoHook) echoHook();
}

// ---------------------------------------------------------------- 文字入力欄
// 欄にはいつも目印の文字を置いておき、増えた分を送る・減った分だけ ⌫ を送る。
// 変換中 (日本語入力の下線が出ている間) は何もしない。
const SENTINEL = "​​";

function attachTyper(ta, onFocusChange, onSent) {
  let composing = false;
  const reset = () => { ta.value = SENTINEL; try { ta.setSelectionRange(SENTINEL.length, SENTINEL.length); } catch (e) {} };
  const sync = () => {
    if (composing) return;
    const v = ta.value;
    if (v === SENTINEL) return;
    if (v.startsWith(SENTINEL)) {
      sendText(v.slice(SENTINEL.length));
      onSent && onSent(v.slice(SENTINEL.length));
    } else if (v.length < SENTINEL.length && SENTINEL.startsWith(v)) {
      for (let i = 0; i < SENTINEL.length - v.length; i++) { Link.send("KY BACK " + Mods.consume()); onSent && onSent(null); }
      if (echoHook) echoHook();
    } else {
      // 目印ごと置き換えられた (全選択して入力など)
      const t = v.replace(/​/g, "");
      if (t) { sendText(t); onSent && onSent(t); }
    }
    reset();
  };
  ta.addEventListener("compositionstart", () => { composing = true; });
  ta.addEventListener("compositionend", () => { composing = false; setTimeout(sync, 0); });
  ta.addEventListener("input", (e) => { if (!e.isComposing) sync(); });
  ta.addEventListener("keydown", (e) => {
    // 外付けキーボードなど: 欄が空でも ⌫ や矢印を送れるように
    if (composing || e.isComposing) return;
    const map = { ArrowLeft: "LEFT", ArrowRight: "RIGHT", ArrowUp: "UP", ArrowDown: "DOWN", Escape: "ESC", Tab: "TAB" };
    if (map[e.key]) { e.preventDefault(); sendKey(map[e.key]); }
  });
  ta.addEventListener("focus", () => { reset(); onFocusChange && onFocusChange(true); });
  ta.addEventListener("blur", () => { onFocusChange && onFocusChange(false); });
  reset();
}

// ---------------------------------------------------------------- キーボード画面

const kbView = $("kbView");
let typing = false;

function keyButton(label, onTap, repeat) {
  const b = document.createElement("button");
  b.textContent = label;
  let t1 = 0, t2 = 0;
  const stop = () => { clearTimeout(t1); clearInterval(t2); };
  // 入力欄からフォーカスを奪わない (スマホのキーボードを閉じない)
  b.addEventListener("touchstart", (e) => e.preventDefault(), { passive: false });
  b.addEventListener("mousedown", (e) => e.preventDefault());
  b.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    onTap();
    if (repeat) {
      // 押しっぱなしの速さは設定で変えられる
      const every = Math.round(1000 / Math.max(2, prefs.keyRepeatRate));
      t1 = setTimeout(() => { t2 = setInterval(onTap, every); }, prefs.keyRepeatDelay);
    }
  });
  ["pointerup", "pointercancel", "pointerleave"].forEach((ev) => b.addEventListener(ev, stop));
  return b;
}
function modButton(label, bit) {
  const b = keyButton(label, () => Mods.tap(bit));
  (Mods.buttons[bit] = Mods.buttons[bit] || []).push(b);
  return b;
}
const k1 = $("keys1"), k2 = $("keys2"), fnBox = $("fnKeys");
k1.append(keyButton("Esc", () => sendKey("ESC")), keyButton("Tab", () => sendKey("TAB")),
  modButton("Ctrl", CTRL), modButton("Shift", SHIFT), modButton("Alt", ALT), modButton("Win", WIN));
const fnBtn = keyButton("Fn", () => { fnBox.hidden = !fnBox.hidden; fnBtn.classList.toggle("on", !fnBox.hidden); });
k1.append(fnBtn);
k2.append(keyButton("←", () => sendKey("LEFT"), true), keyButton("↑", () => sendKey("UP"), true),
  keyButton("↓", () => sendKey("DOWN"), true), keyButton("→", () => sendKey("RIGHT"), true),
  keyButton("⌫", () => sendKey("BACK"), true), keyButton("Del", () => sendKey("DEL"), true),
  keyButton("⏎", () => sendKey("ENTER")));
const fnKeys = [];
for (let i = 1; i <= 12; i++) fnKeys.push(["F" + i, "F" + i]);
fnKeys.push(["Home", "HOME"], ["End", "END"], ["PgUp", "PGUP"], ["PgDn", "PGDN"],
  ["Ins", "INS"], ["PrtSc", "PRTSC"], ["半/全", "KANJI"], ["Winのみ", "WIN"]);
for (const [l, k] of fnKeys) {
  fnBox.append(keyButton(l, () => { sendKey(k); fnBox.hidden = true; fnBtn.classList.remove("on"); }));
}

attachTyper($("typer"), (f) => {
  typing = f;
  kbView.classList.toggle("typing", f);
  updateMirrorVisibility();
});

// ---- PCで入力中の欄 ----
const Mirror = {
  text: "", d2p: [0], p2d: [0], ok: false, last: "",
  // 表示する場所 (キーボードを出したときの欄と、配置した「PCの入力欄」)
  boxes: [{ box: $("mirror"), title: $("mirrorTitle") }],
  update(json) {
    if (json === this.last) return;
    this.last = json;
    let o;
    try { o = JSON.parse(json); } catch (e) { return; }
    if (!o.ok) {
      this.ok = false;
      for (const { box, title } of this.boxes) {
        title.textContent = "PCの入力欄" + (o.reason ? "（" + o.reason + "）" : "（読み取れません）");
        box.textContent = "";
      }
      return;
    }
    this.ok = true;
    const pc = o.text || "";
    // CRLF → 表示用の改行1文字。位置の対応表を作る
    let disp = "";
    const d2p = [], p2d = new Array(pc.length + 1);
    for (let i = 0; i < pc.length;) {
      p2d[i] = disp.length;
      if (pc[i] === "\r" && pc[i + 1] === "\n") { d2p.push(i); p2d[i + 1] = disp.length; disp += "\n"; i += 2; continue; }
      d2p.push(i);
      disp += pc[i] === "\r" ? "\n" : pc[i];
      i++;
    }
    p2d[pc.length] = disp.length;
    d2p.push(pc.length);
    this.text = disp; this.d2p = d2p; this.p2d = p2d;
    const caret = o.caret >= 0 && o.caret <= pc.length ? p2d[o.caret] : -1;
    const sel = o.selEnd >= 0 && o.selEnd <= pc.length ? p2d[o.selEnd] : -1;
    for (const { box, title } of this.boxes) {
      title.textContent = caret >= 0 ? "PCの入力欄（触れた位置にカーソルを移動）" : "PCの入力欄";
      box.textContent = "";
      const add = (t, cls) => {
        if (!t) return;
        const sp = document.createElement("span");
        if (cls) sp.className = cls;
        sp.textContent = t;
        box.appendChild(sp);
      };
      if (caret >= 0 && sel > caret) {
        add(disp.slice(0, caret)); add(disp.slice(caret, sel), "sel"); add(disp.slice(sel));
      } else if (caret >= 0) {
        add(disp.slice(0, caret));
        const c = document.createElement("span"); c.className = "caret"; box.appendChild(c);
        add(disp.slice(caret));
        // カーソルが見えるようにスクロール
        requestAnimationFrame(() => { const r = c.offsetTop - box.clientHeight / 3; if (r > box.scrollTop + box.clientHeight - 20 || c.offsetTop < box.scrollTop) box.scrollTop = Math.max(0, r); });
      } else add(disp);
      if (!disp) add(" ");
    }
  },
};
Link.on("TF ", (b) => Mirror.update(unb64(b)));
function mirrorTap(box) { box.addEventListener("pointerdown", (e) => {
  e.preventDefault();
  if (!Mirror.ok) return;
  // 触れた位置の文字の番号を求める
  let node = null, off = 0;
  if (document.caretRangeFromPoint) {
    const r = document.caretRangeFromPoint(e.clientX, e.clientY);
    if (r) { node = r.startContainer; off = r.startOffset; }
  } else if (document.caretPositionFromPoint) {
    const p = document.caretPositionFromPoint(e.clientX, e.clientY);
    if (p) { node = p.offsetNode; off = p.offset; }
  }
  if (!node) return;
  let idx = 0;
  for (const s of box.childNodes) {
    if (s === node || s.contains(node)) { idx += s.classList && s.classList.contains("caret") ? 0 : off; break; }
    idx += s.classList && s.classList.contains("caret") ? 0 : s.textContent.length;
  }
  idx = Math.max(0, Math.min(idx, Mirror.d2p.length - 1));
  Link.send("CS " + Mirror.d2p[idx]);
}); }
mirrorTap($("mirror"));
let mirrorTimer = 0;
// 「PCの入力欄」は、文字を打っている間と、配置で表示にしているときに出す
let mirrorPartShown = () => false;
function updateMirrorVisibility() {
  const kb = Views.current === "kbView";
  $("mirrorBox").hidden = !(kb && typing);
  const show = kb && (typing || mirrorPartShown());
  clearInterval(mirrorTimer);
  if (show) { Link.send("TQ"); mirrorTimer = setInterval(() => Link.send("TQ"), 700); }
}
echoHook = () => { setTimeout(() => Link.send("TQ"), 120); };
Views.on("kbView", updateMirrorVisibility);

// ---- トラックパッド ----
(function trackpad() {
  const pad = $("pad");
  const lab = $("padLab");
  const pts = new Map();
  let downX = 0, downY = 0, downT = 0, lastX = 0, lastY = 0, lastT = 0;
  let moved = false, maxPts = 0, drag = null, lpTimer = 0;
  let twoLast = null, twoStart = null;
  let accX = 0, accY = 0, wv = 0, wh = 0;
  let middleHeld = false, middleUsed = false;
  const slop = 8;

  const label = () => {
    lab.textContent = drag === "L" ? "左ボタンでドラッグ中" : drag === "R" ? "右ボタンでドラッグ中" : middleHeld ? "スクロール" : "マウス";
  };
  const wheel = (dv, dh) => {
    wv += dv; wh += dh;
    const sv = Math.abs(wv) >= 15 ? Math.trunc(wv) : 0;
    const sh = Math.abs(wh) >= 15 ? Math.trunc(wh) : 0;
    if (sv || sh) { Link.send("MW " + sv + " " + sh); wv -= sv; wh -= sh; }
  };
  const move = (dx, dy, dt) => {
    const v = Math.hypot(dx, dy) / Math.max(dt, 4);
    const gain = prefs.mouseSpeed * (1 + prefs.mouseAccel * Math.min(v, 3)) * 1.6;
    accX += dx * gain; accY += dy * gain;
    const ix = Math.trunc(accX), iy = Math.trunc(accY);
    if (ix || iy) { Link.send("MM " + ix + " " + iy); accX -= ix; accY -= iy; }
  };
  const center = () => {
    let x = 0, y = 0;
    for (const p of pts.values()) { x += p.x; y += p.y; }
    return { x: x / pts.size, y: y / pts.size };
  };
  const drawDots = () => {
    pad.querySelectorAll(".dot").forEach((d) => d.remove());
    const r = pad.getBoundingClientRect();
    for (const p of pts.values()) {
      const d = document.createElement("div");
      d.className = "dot"; d.style.left = (p.x - r.left) + "px"; d.style.top = (p.y - r.top) + "px";
      pad.appendChild(d);
    }
  };

  pad.addEventListener("pointerdown", (e) => {
    if (e.target.closest("button")) return; // 「片手操作」ボタン
    pad.setPointerCapture(e.pointerId);
    pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pts.size === 1) {
      downX = lastX = e.clientX; downY = lastY = e.clientY; downT = lastT = e.timeStamp;
      moved = false; maxPts = 1; drag = null; accX = accY = 0; wv = wh = 0;
      const r = pad.getBoundingClientRect();
      const side = e.clientX - r.left < r.width / 2 ? "L" : "R";
      clearTimeout(lpTimer);
      lpTimer = setTimeout(() => {
        if (!moved && maxPts === 1 && !drag && !middleHeld) { drag = side; Link.send("MB " + side + " 1"); label(); }
      }, 450);
    } else {
      maxPts = Math.max(maxPts, pts.size);
      clearTimeout(lpTimer);
      if (pts.size === 2) { twoStart = twoLast = center(); }
    }
    drawDots();
  });
  pad.addEventListener("pointermove", (e) => {
    if (!pts.has(e.pointerId)) return;
    pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pts.size >= 2 && !drag) {
      const c = center();
      if (!moved && Math.hypot(c.x - twoStart.x, c.y - twoStart.y) > slop) moved = true;
      if (moved) {
        const k = 6 * prefs.scrollSpeed * (prefs.invertTwoFinger ? -1 : 1);
        wheel((c.y - twoLast.y) * k, -(c.x - twoLast.x) * k);
      }
      twoLast = c;
    } else if (pts.size === 1 || drag) {
      const dx = e.clientX - lastX, dy = e.clientY - lastY;
      if (!moved && Math.hypot(e.clientX - downX, e.clientY - downY) > slop) { moved = true; clearTimeout(lpTimer); }
      if (moved || drag) {
        if (middleHeld && !drag) {
          middleUsed = true;
          const k = 6 * prefs.scrollSpeed;
          wheel(-dy * k, dx * k);
        } else move(dx, dy, e.timeStamp - lastT);
      }
      lastX = e.clientX; lastY = e.clientY; lastT = e.timeStamp;
    }
    drawDots();
  });
  const up = (e) => {
    if (!pts.has(e.pointerId)) return;
    pts.delete(e.pointerId);
    if (pts.size > 0) {
      // 残った指の位置から続ける
      const p = pts.values().next().value;
      lastX = p.x; lastY = p.y; lastT = e.timeStamp;
      if (pts.size === 2) twoLast = center();
      drawDots();
      return;
    }
    clearTimeout(lpTimer);
    if (drag) { Link.send("MB " + drag + " 0"); drag = null; }
    else if (e.type === "pointerup" && !moved && e.timeStamp - downT < 350) {
      const r = pad.getBoundingClientRect();
      let b = downX - r.left < r.width / 2 ? "L" : "R";
      if (maxPts >= 2) b = "R";
      else if (middleHeld) { middleUsed = true; b = "M"; }
      Link.send("MC " + b);
    }
    label();
    drawDots();
  };
  pad.addEventListener("pointerup", up);
  pad.addEventListener("pointercancel", up);

  // 下のボタン: 押している間ボタンを押しっぱなし
  const hold = (id, btn) => {
    const el = $(id);
    el.addEventListener("pointerdown", (e) => { e.preventDefault(); el.classList.add("on"); Link.send("MB " + btn + " 1"); });
    const rel = () => { if (el.classList.contains("on")) { el.classList.remove("on"); Link.send("MB " + btn + " 0"); } };
    el.addEventListener("pointerup", rel); el.addEventListener("pointercancel", rel); el.addEventListener("pointerleave", rel);
  };
  hold("mbL", "L"); hold("mbR", "R");
  // 長押しボタン: 押しながらトラックパッドをなぞる=スクロール、何もしないで離す=中クリック
  const mid = $("mbM");
  mid.addEventListener("pointerdown", (e) => { e.preventDefault(); middleHeld = true; middleUsed = false; mid.classList.add("on"); label(); });
  const midUp = () => {
    if (!middleHeld) return;
    if (!middleUsed) Link.send("MC M");
    middleHeld = false; mid.classList.remove("on"); label();
  };
  mid.addEventListener("pointerup", midUp); mid.addEventListener("pointercancel", midUp);
})();

// ---- 片手操作 ----
// 1本指で全部できるようにする。右手用はボタンの列を右 (親指の側) に、左手用は左に置く。
// 右手用では「＜ 左手用」を左寄せ、左手用では「＞ 右手用」を右寄せにする
(function oneHand() {
  const pad = $("ohPad");
  let grabbing = false;
  // 部品の位置は kblayout.js (右手用・左手用で別々の配置)
  function apply() {
    $("ohHand").textContent = prefs.oneHandLeft ? "＞ 右手用" : "＜ 左手用";
    if (!prefs.oneHand && grabbing) setGrab(false);
    if (typeof KbStage !== "undefined") KbStage.refresh();
  }
  window.applyOneHand = apply;
  function setGrab(g) {
    grabbing = g;
    Link.send("MB L " + (g ? 1 : 0));
    $("ohGrab").textContent = g ? "はなす" : "つかむ";
    $("ohGrab").classList.toggle("on", g);
    $("ohLab").textContent = g ? "つかんでいます" : "マウス（片手）";
    $("ohSub").textContent = g ? "なぞって動かし、「はなす」で離します" : "なぞる=移動　タップ=左クリック";
  }
  $("ohEnter").onclick = () => { prefs.oneHand = true; savePrefs(); apply(); };
  $("ohExit").onclick = () => { prefs.oneHand = false; savePrefs(); apply(); };
  $("ohHand").onclick = () => { prefs.oneHandLeft = !prefs.oneHandLeft; savePrefs(); apply(); };
  const repeatBtn = (el, fn) => {
    let t1 = 0, t2 = 0;
    const stop = () => { clearTimeout(t1); clearInterval(t2); el.classList.remove("on"); };
    el.addEventListener("pointerdown", (e) => {
      e.preventDefault(); el.classList.add("on"); fn();
      t1 = setTimeout(() => { t2 = setInterval(fn, Math.round(1000 / Math.max(2, prefs.keyRepeatRate))); }, prefs.keyRepeatDelay);
    });
    ["pointerup", "pointercancel", "pointerleave"].forEach((t) => el.addEventListener(t, stop));
  };
  repeatBtn($("ohUp"), () => Link.send("MW 120 0"));
  repeatBtn($("ohDown"), () => Link.send("MW -120 0"));
  $("ohRight").onclick = () => Link.send("MC R");
  $("ohLeft").onclick = () => Link.send("MC L");
  $("ohGrab").onclick = () => setGrab(!grabbing);

  // 1本指のトラックパッド (タップ=左クリック)
  let id = null, downX = 0, downY = 0, lastX = 0, lastY = 0, lastT = 0, downT = 0, moved = false, accX = 0, accY = 0;
  const dot = document.createElement("div");
  dot.className = "dot"; dot.hidden = true; pad.appendChild(dot);
  pad.addEventListener("pointerdown", (e) => {
    if (e.target !== pad && !e.target.classList.contains("lab") && !e.target.classList.contains("ohSub")) return;
    if (id !== null) return;
    id = e.pointerId; pad.setPointerCapture(id);
    downX = lastX = e.clientX; downY = lastY = e.clientY; downT = lastT = e.timeStamp; moved = false; accX = accY = 0;
  });
  pad.addEventListener("pointermove", (e) => {
    if (e.pointerId !== id) return;
    const r = pad.getBoundingClientRect();
    dot.hidden = false; dot.style.left = (e.clientX - r.left) + "px"; dot.style.top = (e.clientY - r.top) + "px";
    if (!moved && Math.hypot(e.clientX - downX, e.clientY - downY) > 8) moved = true;
    if (moved) {
      const dx = e.clientX - lastX, dy = e.clientY - lastY;
      const v = Math.hypot(dx, dy) / Math.max(e.timeStamp - lastT, 4);
      const gain = prefs.mouseSpeed * (1 + prefs.mouseAccel * Math.min(v, 3)) * 1.6;
      accX += dx * gain; accY += dy * gain;
      const ix = Math.trunc(accX), iy = Math.trunc(accY);
      if (ix || iy) { Link.send("MM " + ix + " " + iy); accX -= ix; accY -= iy; }
    }
    lastX = e.clientX; lastY = e.clientY; lastT = e.timeStamp;
  });
  const up = (e) => {
    if (e.pointerId !== id) return;
    id = null; dot.hidden = true;
    if (e.type === "pointerup" && !moved && e.timeStamp - downT < 350) Link.send("MC L");
  };
  pad.addEventListener("pointerup", up);
  pad.addEventListener("pointercancel", up);
  apply();
})();

// ---------------------------------------------------------------- ★ ショートカット

const DEFAULT_SC = [
  ["コピー", "C63", CTRL], ["貼り付け", "C76", CTRL], ["切り取り", "C78", CTRL], ["元に戻す", "C7a", CTRL],
  ["やり直し", "C79", CTRL], ["全て選択", "C61", CTRL], ["保存", "C73", CTRL], ["検索", "C66", CTRL],
  ["ウィンドウ切替", "TAB", ALT], ["閉じる", "F4", ALT], ["エクスプローラー", "C65", WIN], ["デスクトップ", "C64", WIN],
  ["スクリーンショット", "C73", WIN | SHIFT], ["タスクマネージャー", "ESC", CTRL | SHIFT], ["新しいタブ", "C74", CTRL], ["タブを閉じる", "C77", CTRL],
].map(([l, k, m]) => ({ l, k, m, r: "" }));
let shortcuts = load("shortcuts", null) || DEFAULT_SC.map((s) => Object.assign({}, s));

function renderShortcuts() {
  const box = $("starList");
  box.textContent = "";
  shortcuts.forEach((s, i) => {
    const b = document.createElement("button");
    b.textContent = s.l;
    let lp = 0, long = false;
    b.addEventListener("pointerdown", () => {
      long = false;
      lp = setTimeout(() => {
        long = true;
        if (confirm("「" + s.l + "」を削除しますか？")) { shortcuts.splice(i, 1); store("shortcuts", shortcuts); renderShortcuts(); }
      }, 600);
    });
    b.addEventListener("pointerup", () => {
      clearTimeout(lp);
      if (long) return;
      if (s.r) Link.send("RUN " + b64(s.r));
      else Link.send("KY " + s.k + " " + (s.m | Mods.consume()));
      $("starSheet").hidden = true;
    });
    b.addEventListener("pointercancel", () => clearTimeout(lp));
    box.appendChild(b);
  });
}
// "Win+E" → {k: "C65", m: WIN}
function parseCombo(t) {
  const parts = t.split("+").map((x) => x.trim()).filter(Boolean);
  if (!parts.length) return null;
  let m = 0, key = null;
  const names = { ESC: "ESC", ESCAPE: "ESC", TAB: "TAB", ENTER: "ENTER", BACKSPACE: "BACK", BS: "BACK", DEL: "DEL", DELETE: "DEL",
    HOME: "HOME", END: "END", PGUP: "PGUP", PAGEUP: "PGUP", PGDN: "PGDN", PAGEDOWN: "PGDN", INS: "INS", INSERT: "INS",
    PRTSC: "PRTSC", PRINTSCREEN: "PRTSC", UP: "UP", DOWN: "DOWN", LEFT: "LEFT", RIGHT: "RIGHT", SPACE: "SPACE",
    "↑": "UP", "↓": "DOWN", "←": "LEFT", "→": "RIGHT" };
  for (const p of parts) {
    const u = p.toUpperCase();
    if (u === "CTRL" || u === "CONTROL") m |= CTRL;
    else if (u === "SHIFT") m |= SHIFT;
    else if (u === "ALT") m |= ALT;
    else if (u === "WIN" || u === "WINDOWS") m |= WIN;
    else if (/^F([1-9]|1[0-2])$/.test(u)) key = u;
    else if (names[u]) key = names[u];
    else if ([...p].length === 1) key = "C" + p.toLowerCase().codePointAt(0).toString(16);
    else return null;
  }
  if (!key) { if (m === WIN) return { k: "WIN", m: 0 }; return null; }
  return { k: key, m };
}
$("starBtn").addEventListener("mousedown", (e) => e.preventDefault());
$("starBtn").addEventListener("pointerdown", (e) => e.preventDefault());
$("starBtn").onclick = () => { renderShortcuts(); $("starForm").hidden = true; $("starSheet").hidden = false; };
$("starClose").onclick = () => { $("starSheet").hidden = true; };
$("starEdit").onclick = () => { $("starForm").hidden = !$("starForm").hidden; };
$("starSheet").addEventListener("click", (e) => { if (e.target === $("starSheet")) $("starSheet").hidden = true; });
$("sfAdd").onclick = () => {
  const l = $("sfLabel").value.trim();
  const keys = $("sfKeys").value.trim();
  const run = $("sfRun").value.trim();
  if (!l) { toast("名前を入れてください"); return; }
  if (run) shortcuts.push({ l, k: "", m: 0, r: run });
  else {
    const c = parseCombo(keys);
    if (!c) { toast("キーの書き方が分かりません（例: Win+E、Ctrl+Shift+Esc）"); return; }
    shortcuts.push({ l, k: c.k, m: c.m, r: "" });
  }
  store("shortcuts", shortcuts);
  $("sfLabel").value = $("sfKeys").value = $("sfRun").value = "";
  renderShortcuts();
  toast("「" + l + "」を追加しました");
};
$("sfDefaults").onclick = () => {
  if (!confirm("ショートカットを最初の一覧に戻しますか？")) return;
  shortcuts = DEFAULT_SC.map((s) => Object.assign({}, s));
  store("shortcuts", shortcuts);
  renderShortcuts();
};

// ---------------------------------------------------------------- その他の画面

// 音量
let volInfo = null, volDragging = false;
Link.on("VI ", (b) => {
  let o; try { o = JSON.parse(unb64(b)); } catch (e) { return; }
  volInfo = o;
  if (!volDragging) $("vol").value = o.vol || 0;
  $("volVal").textContent = (o.vol || 0) + (o.mute ? "（ミュート）" : "");
  $("mute").classList.toggle("on", !!o.mute);
  $("volErr").textContent = o.error || "";
  const box = $("devs");
  box.textContent = "";
  (o.devices || []).forEach((d) => {
    const btn = document.createElement("button");
    btn.textContent = (d.default ? "● " : "") + (d.name || "?");
    btn.classList.toggle("on", !!d.default);
    btn.onclick = () => Link.send("VD " + b64(d.id));
    box.appendChild(btn);
  });
});
$("vol").addEventListener("input", () => { volDragging = true; $("volVal").textContent = $("vol").value; Link.send("VS " + $("vol").value); });
$("vol").addEventListener("change", () => { volDragging = false; });
$("mute").onclick = () => Link.send("VM " + (volInfo && volInfo.mute ? 0 : 1));

// クリップボード
$("clipSend").onclick = () => Link.send("CB " + b64($("clipText").value));
$("clipGet").onclick = () => Link.send("CQ");
Link.on("CK", () => toast("PCのクリップボードに入れました"));
Link.on("CT", (b) => { $("clipText").value = unb64(b); toast("PCのクリップボードの文字を受け取りました"); });

// 電源
const PW_NAMES = { LOCK: "ロック", MONOFF: "画面を消す", SLEEP: "スリープ", RESTART: "再起動", SHUTDOWN: "シャットダウン" };
document.querySelectorAll("[data-pw]").forEach((b) => {
  b.onclick = () => {
    const a = b.dataset.pw;
    if ((a === "SLEEP" || a === "RESTART" || a === "SHUTDOWN") && !confirm("PCを「" + PW_NAMES[a] + "」しますか？")) return;
    Link.send("PW " + a);
  };
});

// PCの状態
function fmtBytes(n) { return n >= 1e12 ? (n / 1e12).toFixed(1) + " TB" : (n / 1e9).toFixed(1) + " GB"; }
Link.on("SI ", (b) => {
  let o; try { o = JSON.parse(unb64(b)); } catch (e) { return; }
  const rows = [];
  rows.push(["CPU", (o.cpu >= 0 ? o.cpu.toFixed(0) + "%" : "-") + (o.cpuName ? "（" + o.cpuName + "）" : "")]);
  if (o.memTotal) rows.push(["メモリ", fmtBytes(o.memUsed) + " / " + fmtBytes(o.memTotal)]);
  if (o.diskTotal) rows.push(["Cドライブ", "空き " + fmtBytes(o.diskFree) + " / " + fmtBytes(o.diskTotal)]);
  rows.push(["電池", o.battery >= 0 ? o.battery + "%" + (o.charging ? "（充電中）" : "") : "なし"]);
  if (o.uptime) rows.push(["起動から", Math.floor(o.uptime / 3600) + "時間" + Math.floor((o.uptime % 3600) / 60) + "分"]);
  const dl = $("sys");
  dl.textContent = "";
  rows.forEach(([k, v]) => { const dt = document.createElement("dt"); dt.textContent = k; const dd = document.createElement("dd"); dd.textContent = v; dl.append(dt, dd); });
});
let sysTimer = 0;
Views.on("moreView", (on) => {
  clearInterval(sysTimer);
  if (on) {
    Link.send("VQ"); Link.send("SQ");
    sysTimer = setInterval(() => Link.send("SQ"), 3000);
  }
});

// 設定
(function settings() {
  const box = $("settings");
  const slider = (key, title, lo, hi, step) => {
    const d = document.createElement("div");
    d.className = "set";
    const lab = document.createElement("label");
    const t = document.createElement("span"); t.textContent = title;
    const v = document.createElement("span"); v.textContent = prefs[key];
    lab.append(t, v);
    const r = document.createElement("input");
    r.type = "range"; r.min = lo; r.max = hi; r.step = step; r.value = prefs[key];
    r.oninput = () => { prefs[key] = parseFloat(r.value); v.textContent = r.value; savePrefs(); };
    d.append(lab, r);
    box.appendChild(d);
  };
  const check = (key, title, after) => {
    const l = document.createElement("label");
    l.className = "chk";
    const c = document.createElement("input");
    c.type = "checkbox"; c.checked = !!prefs[key]; c.dataset.k = key;
    c.onchange = () => { prefs[key] = c.checked; savePrefs(); after && after(); };
    const s = document.createElement("span"); s.textContent = title;
    l.append(c, s);
    box.appendChild(l);
  };
  slider("mouseSpeed", "マウスの速さ", 0.3, 4, 0.1);
  slider("mouseAccel", "マウスの加速", 0, 1.5, 0.1);
  slider("scrollSpeed", "スクロールの速さ", 0.3, 3, 0.1);
  slider("keyRepeatRate", "矢印キーなどを長押ししたときの速さ（回/秒）", 4, 40, 1);
  slider("keyRepeatDelay", "長押ししてから連続入力が始まるまで（ミリ秒）", 150, 800, 50);
  check("invertTwoFinger", "2本指スクロールの向きを逆にする");
  check("oneHand", "マウスを片手操作にする（キーボード画面のボタンを隠してしまったときも、ここで切り替えられます）", () => window.applyOneHand && applyOneHand());
  check("oneHandLeft", "片手操作を左手用にする", () => window.applyOneHand && applyOneHand());
  // 画面のボタンで切り替えたときにも合わせる
  Views.on("moreView", (on) => { if (on) box.querySelectorAll("input[data-k]").forEach((c) => { c.checked = !!prefs[c.dataset.k]; }); });
  if (IS_IOS_SAFARI) slider("bottomGap", "画面の下の余白（下のボタンが Safari のバーに隠れるときは大きく）", 0, 120, 2);
  check("scrAutoZoom", "PCの画面で文字を入力するとき、最後にタップした場所を拡大する");
  slider("turboRate", "連打の速さ（回/秒）", 5, 30, 1);
  check("positional", "ABXYをSwitchの位置どおりに割り当てる（オフ: 文字どおり A→A）", () => window.Pad && Pad.prefsChanged());
  check("floatingSticks", "コントローラーのスティックを触れた位置に出す", () => window.Pad && Pad.prefsChanged());
})();
$("showPcPage").onclick = () => { Link.send("SP"); toast("PCに接続画面を表示しました"); };
$("padReset").onclick = () => { if (confirm("コントローラーの配置を初期に戻しますか？")) { window.Pad && Pad.resetLayout(); toast("初期配置に戻しました"); } };

// PCの音をスマホで聞く (このページを開いている間だけ)
// 届く間隔のばらつきを吸収するため、少し先 (約0.12秒) に予約して鳴らす。遅れがたまったら捨てて追いつく
const PcSound = {
  ctx: null, on: false, rate: 0, next: 0,
  toggle() {
    this.on = !this.on;
    if (this.on) {
      // iPhone では、ボタンを押したときに音の準備をする必要がある
      const AC = window.AudioContext || window.webkitAudioContext;
      if (!this.ctx) this.ctx = new AC();
      this.ctx.resume();
      // 無音を一度鳴らして、音を出せる状態にする
      const b = this.ctx.createBuffer(1, 1, 22050), src = this.ctx.createBufferSource();
      src.buffer = b; src.connect(this.ctx.destination); src.start(0);
      this.next = 0;
      Link.needMedia("sound", true);
      this.start();
    } else {
      Link.sendMedia("A", enc.encode("0"));
      Link.needMedia("sound", false);
    }
    this.paint();
  },
  start() { if (this.on && Link.mediaReady) { this.next = 0; Link.sendMedia("A", enc.encode(prefs.mutePc ? "1m" : "1")); } },
  paint() { $("listen").textContent = "🎧 スマホで聞く: " + (this.on ? "ON" : "OFF"); $("listen").classList.toggle("on", this.on); },
  play(bytes) {
    if (!this.on || !this.ctx || !this.rate) return;
    const n = Math.floor(bytes.length / 4);
    if (!n) return;
    const now = this.ctx.currentTime;
    if (this.next < now + 0.03) this.next = now + 0.12; // 途切れたら、少し貯めてから鳴らす
    if (this.next > now + 0.4) return; // 遅れすぎ: 捨てて追いつく
    const dv = new DataView(bytes.buffer, bytes.byteOffset, n * 4);
    const buf = this.ctx.createBuffer(2, n, this.rate);
    const L = buf.getChannelData(0), R = buf.getChannelData(1);
    for (let i = 0; i < n; i++) { L[i] = dv.getInt16(i * 4, true) / 32768; R[i] = dv.getInt16(i * 4 + 2, true) / 32768; }
    const src = this.ctx.createBufferSource();
    src.buffer = buf;
    src.connect(this.ctx.destination);
    src.start(this.next);
    this.next += n / this.rate;
  },
};
$("listen").onclick = () => PcSound.toggle();
$("mutePc").checked = !!prefs.mutePc;
$("mutePc").onchange = () => { prefs.mutePc = $("mutePc").checked; savePrefs(); if (PcSound.on) PcSound.start(); };
Link.onData("a", (b) => { PcSound.rate = parseInt(dec.decode(b), 10) || 48000; PcSound.next = 0; });
Link.onData("A", (b) => PcSound.play(b));
Link.onMediaReady.push(() => PcSound.start());
document.addEventListener("visibilitychange", () => {
  // 戻ってきたら鳴らし直す (iPhone は裏に回ると音を止めるため)
  if (document.visibilityState === "visible" && PcSound.ctx && PcSound.on) { PcSound.ctx.resume(); PcSound.next = 0; }
});

// ---------------------------------------------------------------- PCから届くもの
// ページ・ファイルは画面の上に出す (押すと開く・保存する)
const Banner = {
  url: "",
  show(title, body, go, href, download) {
    $("bannerT").textContent = title;
    $("bannerB").textContent = body;
    const a = $("bannerGo");
    a.textContent = go;
    a.href = href;
    if (download) a.setAttribute("download", download); else a.removeAttribute("download");
    if (this.url && this.url !== href) { try { URL.revokeObjectURL(this.url); } catch (e) {} }
    this.url = href.startsWith("blob:") ? href : "";
    $("banner").hidden = false;
    if (navigator.vibrate) navigator.vibrate(60);
  },
  hide() { $("banner").hidden = true; },
};
$("bannerClose").onclick = () => Banner.hide();
$("bannerGo").addEventListener("click", () => {
  const h = $("bannerGo").href;
  setTimeout(() => { if ($("bannerGo").href === h) Banner.hide(); }, 300);
});
Link.on("NT ", (b) => {
  let o; try { o = JSON.parse(unb64(b)); } catch (e) { return; }
  toast((o.title ? o.title + "：" : "") + (o.text || ""));
  if (navigator.vibrate) navigator.vibrate([80, 60, 80]);
});
Link.on("OU ", (b) => {
  const u = unb64(b);
  if (!/^https?:\/\//i.test(u)) return;
  Banner.show("PCから届いたページ", u, "開く", u);
});

// スマホを鳴らす (このページを開いている間だけ)
const Ring = {
  ctx: null, timer: 0, stopAt: 0,
  start() {
    $("ring").hidden = false;
    this.stopAt = Date.now() + 60000;
    try {
      const AC = window.AudioContext || window.webkitAudioContext;
      this.ctx = this.ctx || (PcSound.ctx) || new AC();
      this.ctx.resume();
    } catch (e) {}
    clearInterval(this.timer);
    const beep = () => {
      if (Date.now() > this.stopAt) { this.stop(); return; }
      if (navigator.vibrate) navigator.vibrate([300, 200, 300]);
      if (!this.ctx) return;
      const t = this.ctx.currentTime;
      for (let i = 0; i < 3; i++) {
        const o = this.ctx.createOscillator(), g = this.ctx.createGain();
        o.frequency.value = i % 2 ? 1320 : 990;
        g.gain.setValueAtTime(0.0001, t + i * 0.22);
        g.gain.exponentialRampToValueAtTime(0.6, t + i * 0.22 + 0.02);
        g.gain.exponentialRampToValueAtTime(0.0001, t + i * 0.22 + 0.2);
        o.connect(g); g.connect(this.ctx.destination);
        o.start(t + i * 0.22); o.stop(t + i * 0.22 + 0.21);
      }
    };
    beep();
    this.timer = setInterval(beep, 1000);
  },
  stop() {
    clearInterval(this.timer);
    $("ring").hidden = true;
    if (navigator.vibrate) navigator.vibrate(0);
  },
};
$("ringStop").onclick = () => Ring.stop();
Link.on("RING ", (b) => { if (b.trim() === "1") Ring.start(); else Ring.stop(); });

// ---- ファイル ----
function bytesToB64(u8) {
  let bin = "";
  for (let i = 0; i < u8.length; i += 0x8000) bin += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
  return btoa(bin);
}
function b64ToBytes(s) {
  const bin = atob(s);
  const b = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) b[i] = bin.charCodeAt(i);
  return b;
}
// PC → このページ (届いたら「保存」を出す)
const Incoming = {};
const MAX_IN = 300 * 1024 * 1024;
Link.on("FS ", (rest) => {
  const [id, meta] = rest.split(" ");
  let m; try { m = JSON.parse(unb64(meta)); } catch (e) { return; }
  if (m.s > MAX_IN) { Link.send("FK " + id + " " + b64("ブラウザ版で受け取れるのは300MBまでです")); return; }
  Incoming[id] = { name: m.n || "file", size: m.s || 0, parts: [], got: 0 };
  toast("PCからファイルを受け取っています: " + (m.n || ""));
});
Link.on("FD ", (rest) => {
  const [id, data] = rest.split(" ");
  const f = Incoming[id];
  if (!f) return;
  const b = b64ToBytes(data);
  f.parts.push(b);
  f.got += b.length;
  if (f.got > MAX_IN) { delete Incoming[id]; Link.send("FK " + id + " " + b64("大きすぎます")); }
});
Link.on("FE ", (rest) => {
  const id = rest.trim();
  const f = Incoming[id];
  if (!f) return;
  delete Incoming[id];
  const url = URL.createObjectURL(new Blob(f.parts));
  Link.send("FK " + id + " ok");
  Banner.show("PCからファイルが届きました", f.name, "保存", url, f.name);
});
Link.on("FX ", (rest) => { delete Incoming[rest.trim()]; });

// このページ → PC
const Outgoing = {};
Link.on("FK ", (rest) => {
  const [id, res] = rest.trim().split(" ");
  const o = Outgoing[id];
  if (!o) return;
  delete Outgoing[id];
  o(res === "ok" ? null : unb64(res || ""));
});
async function sendFileToPC(file, onProg) {
  const id = "w" + Date.now().toString(36) + Math.floor(Math.random() * 1e6).toString(36);
  const done = new Promise((res) => { Outgoing[id] = res; });
  Link.send("FS " + id + " " + b64(JSON.stringify({ n: file.name, s: file.size })));
  const CH = 32 * 1024;
  for (let off = 0; off < file.size; off += CH) {
    if (!Link.ready) { delete Outgoing[id]; return "切断されました"; }
    // 送りきれていない分がたまったら待つ (マウスなどの操作が遅れないように)
    while (Link.main && Link.main.ws.bufferedAmount > 256 * 1024) await new Promise((r) => setTimeout(r, 30));
    const buf = new Uint8Array(await file.slice(off, off + CH).arrayBuffer());
    Link.send("FD " + id + " " + bytesToB64(buf));
    onProg(Math.min(1, (off + CH) / file.size));
  }
  Link.send("FE " + id);
  return done;
}
$("sendFileBtn").onclick = () => $("sendFileIn").click();
$("sendFileIn").onchange = async () => {
  const files = [...$("sendFileIn").files];
  $("sendFileIn").value = "";
  if (!files.length) return;
  if (!Link.ready) { toast("PCとつながっていません"); return; }
  let ok = 0;
  for (const f of files) {
    const err = await sendFileToPC(f, (p) => { $("sendProg").textContent = "送っています: " + f.name + "（" + Math.round(p * 100) + "%）"; });
    if (err) { toast("送れませんでした: " + err); } else ok++;
  }
  $("sendProg").textContent = ok + " 個のファイルをPCへ送りました（PCの「ダウンロード」の中の「PhonePad」フォルダ）";
};

// ヘルプ
const openHelp = () => { $("helpSheet").hidden = false; };
$("helpBtn2").onclick = openHelp;
$("helpClose").onclick = () => { $("helpSheet").hidden = true; };
$("helpSheet").addEventListener("click", (e) => { if (e.target === $("helpSheet")) $("helpSheet").hidden = true; });
$("guideLink").onclick = () => { window.open("https://charlotte5227.github.io/PhonePad/guide.html", "_blank"); };

// 画面ごとの使い方 (初めて開いたときに一度だけ自動で出す)
const COACH = {
  kbView: ["キーボードとマウス", [
    "上の入力欄をタップして文字を打つと、すぐPCに入ります。日本語の変換も使えます。文字を打っている間は、入力用のキーが上に並びます。",
    "下の大きな面がマウスです。なぞる=カーソル移動、左半分をタップ=左クリック、右半分をタップ=右クリック。",
    "2本指でなぞる=スクロール。長押ししてから動かす=ドラッグ。",
    "「長押し」ボタンを押しながらなぞる=スクロール。何もせず離す=中クリック。",
    "Ctrl・Shift・Alt・Win は、1回押すと次の1キーだけ、2回押すと押しっぱなし（もう1回で解除）。",
    "★ でコピー・貼り付けなどのショートカットや、アプリ・サイトを開けます。",
    "マウスの右上の「片手操作」で、片手（1本指）だけで使える画面になります。「＜ 左手用」「＞ 右手用」で利き手を切り替えます。",
    "上の「✎ 配置」で、ボタンの位置・大きさを変えたり、隠したり、好きなキー（F5、Ctrl+C、Alt+Tab など）を追加したりできます。",
  ]],
  padView: ["コントローラー", [
    "スマホを横向きにして使います。PCからは Xbox 360 コントローラーに見えます。",
    "L・R のスティックは、触れた位置に出ます（設定で固定にもできます）。",
    "T を押す=「押している間は連打」のON/OFF。ボタンを押しながら T=そのボタンだけ自動連打。",
    "✎=ボタンの位置・大きさ・形を変える。⌨=キーボードに戻る。⚙=設定。",
    "✎ →「エリアを描く」で、指でなぞって囲んだ場所を好きなボタンにできます（例: 右半分を全部 A）。使わない部品は「隠す」で消せます。",
  ]],
  scrView: ["PCの画面", [
    "「操作: ON」のとき: タップ=クリック、長押し=右クリック、長押ししてから動かす=ドラッグ。",
    "1本指でなぞる=スクロール（「☐ なぞってカーソル移動」にチェックでカーソル移動）。2本指でなぞる=スクロール。",
    "2本指で広げる・つまむ=拡大・縮小。2本指タップ=右クリック、3本指タップ=中クリック。",
    "「⌨ 文字入力」で下に入力欄とキーが出ます。先にPCの画面で入力したい欄をタップしておくと、その場所が拡大されて見やすくなります。",
    "「← 戻る」でキーボード画面へ。反応が遅いときは「画質」を下げてください。",
    "横向きのときは、上のボタンを左上の「☰」にしまっています。押すと出てきます。",
  ]],
};
function showCoach(id) {
  const c = COACH[id];
  if (!c) { openHelp(); return; }
  $("coachTitle").textContent = c[0] + "の使い方";
  const ul = $("coachList");
  ul.textContent = "";
  c[1].forEach((t) => { const li = document.createElement("li"); li.textContent = t; ul.appendChild(li); });
  $("coachSheet").hidden = false;
}
$("coachOk").onclick = () => { $("coachSheet").hidden = true; };
$("coachMore").onclick = () => { $("coachSheet").hidden = true; openHelp(); };
$("coachSheet").addEventListener("click", (e) => { if (e.target === $("coachSheet")) $("coachSheet").hidden = true; });
$("helpBtn").onclick = () => showCoach(Views.current);
$("howKb").onclick = () => showCoach("kbView");
$("howPad").onclick = () => showCoach("padView");
$("howScr").onclick = () => showCoach("scrView");
for (const id of Object.keys(COACH)) {
  Views.on(id, (on) => {
    if (on && Link.state !== "idle" && !load("coach." + id, false)) { store("coach." + id, true); setTimeout(() => showCoach(id), 300); }
  });
}
Link.onState.push((s) => {
  if (s === "open") $("aboutVer").textContent = "PhonePad ブラウザ版（PC: " + Link.pcName + " / v" + Link.pcVersion + "）";
});

// iPhone の Safari で開いているときは、ホーム画面への追加をすすめる (一度だけ)
if (IS_IOS && navigator.standalone === false && !load("a2hsTip", false)) {
  store("a2hsTip", true);
  setTimeout(() => toast("Safari の共有ボタン（□↑）→「ホーム画面に追加」で開くと、画面を広く使えて操作しやすくなります"), 4000);
}

// 初めて開いたときは、できること・できないことを見せる (閉じたらキーボード画面の使い方)
if (Link.init()) {
  if (!load("helpSeen", false)) {
    store("helpSeen", true);
    openHelp();
    const close = $("helpClose").onclick;
    $("helpClose").onclick = () => {
      close();
      $("helpClose").onclick = close;
      if (!load("coach.kbView", false)) { store("coach.kbView", true); showCoach("kbView"); }
    };
  } else if (!load("coach.kbView", false)) { store("coach.kbView", true); showCoach("kbView"); }
}
