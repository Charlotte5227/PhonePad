// PhonePad ブラウザ版 — 接続・キーボード・マウス・その他
"use strict";

const $ = (id) => document.getElementById(id);
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
function store(k, v) { try { localStorage.setItem("pp." + k, JSON.stringify(v)); } catch (e) {} }
function load(k, def) {
  try { const v = localStorage.getItem("pp." + k); return v == null ? def : JSON.parse(v); } catch (e) { return def; }
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
  turboRate: 15, positional: false, floatingSticks: true, fieldAlways: false,
  scrControl: true, scrQuality: 1, scrDragCursor: false,
}, load("prefs", {}));
function savePrefs() { store("prefs", prefs); }

// ---------------------------------------------------------------- 接続 (暗号化)

const Link = {
  ws: null, key: null, code: "", k: null, tx: 0, rx: 0,
  state: "idle", fatal: false, handlers: [], onState: [], pingTimer: 0, retryTimer: 0,
  pcName: "", pcVersion: "",

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
    if (this.ws) { try { this.ws.close(); } catch (e) {} }
    this.setState("connecting", "PCに接続しています…");
    let ws;
    try { ws = new WebSocket("ws://" + location.host + "/ws"); } catch (e) { this.retry(); return; }
    ws.binaryType = "arraybuffer";
    this.ws = ws;
    let hello = false;
    ws.onmessage = (ev) => {
      if (ws !== this.ws) return;
      const data = new Uint8Array(ev.data);
      if (!hello) {
        // "PPW1" + ランダム24バイト → この接続だけの鍵を作る
        if (data.length !== 28 || dec.decode(data.subarray(0, 4)) !== "PPW1") { ws.close(); return; }
        const m = new Uint8Array(32 + 24);
        m.set(this.key, 0);
        m.set(data.subarray(4), 32);
        this.k = nacl.hash(m).subarray(0, 32);
        this.tx = 0; this.rx = 0;
        hello = true;
        this.sendRaw("L", enc.encode("HELLO " + this.code + " " + b64(deviceName()) + " 2"));
        return;
      }
      this.rx++;
      const plain = nacl.secretbox.open(data, nonce(2, this.rx), this.k);
      if (!plain || plain.length === 0) { ws.close(); return; }
      const kind = String.fromCharCode(plain[0]);
      const body = plain.subarray(1);
      if (kind === "L") this.onLine(dec.decode(body));
      else this.handlers.forEach((h) => h.kind === kind && h.fn(body));
    };
    ws.onclose = () => {
      if (ws !== this.ws) return;
      clearInterval(this.pingTimer);
      if (this.fatal) return;
      this.setState("closed", "切断されました。つなぎ直しています…", "warn");
      this.retry();
    };
    ws.onerror = () => {};
  },

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
      this.setState("closed", "PCの画面から切断されました（ページを開き直すとつながります）", "warn");
      try { this.ws.close(); } catch (e) {}
      return;
    }
    if (line.startsWith("ER ")) { toast(unb64(line.slice(3))); return; }
    this.handlers.forEach((h) => h.kind === "L" && line.startsWith(h.prefix) && h.fn(line.slice(h.prefix.length)));
  },

  on(prefix, fn) { this.handlers.push({ kind: "L", prefix, fn }); },
  onData(kind, fn) { this.handlers.push({ kind, fn }); },

  get ready() { return this.state === "open" && this.ws && this.ws.readyState === 1; },

  send(line) { if (this.ready) this.sendRaw("L", enc.encode(line)); },

  sendRaw(kind, bytes) {
    if (!this.ws || this.ws.readyState !== 1) return;
    this.tx++;
    const plain = new Uint8Array(bytes.length + 1);
    plain[0] = kind.charCodeAt(0);
    plain.set(bytes, 1);
    this.ws.send(nacl.secretbox(plain, nonce(1, this.tx), this.k));
  },
};

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
    for (const [bit, b] of Object.entries(this.buttons)) {
      b.classList.toggle("on", (this.mask & bit) !== 0);
      b.classList.toggle("lock", (this.locked & bit) !== 0);
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

function attachTyper(ta, onFocusChange) {
  let composing = false;
  const reset = () => { ta.value = SENTINEL; try { ta.setSelectionRange(SENTINEL.length, SENTINEL.length); } catch (e) {} };
  const sync = () => {
    if (composing) return;
    const v = ta.value;
    if (v === SENTINEL) return;
    if (v.startsWith(SENTINEL)) {
      sendText(v.slice(SENTINEL.length));
    } else if (v.length < SENTINEL.length && SENTINEL.startsWith(v)) {
      for (let i = 0; i < SENTINEL.length - v.length; i++) Link.send("KY BACK " + Mods.consume());
      if (echoHook) echoHook();
    } else {
      // 目印ごと置き換えられた (全選択して入力など)
      const t = v.replace(/​/g, "");
      if (t) sendText(t);
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
      t1 = setTimeout(() => { t2 = setInterval(onTap, 60); }, 400);
    }
  });
  ["pointerup", "pointercancel", "pointerleave"].forEach((ev) => b.addEventListener(ev, stop));
  return b;
}
function modButton(label, bit) {
  const b = keyButton(label, () => Mods.tap(bit));
  Mods.buttons[bit] = b;
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
  update(json) {
    if (json === this.last) return;
    this.last = json;
    let o;
    try { o = JSON.parse(json); } catch (e) { return; }
    const box = $("mirror");
    if (!o.ok) {
      this.ok = false;
      $("mirrorTitle").textContent = "PCの入力欄" + (o.reason ? "（" + o.reason + "）" : "（読み取れません）");
      box.textContent = "";
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
    $("mirrorTitle").textContent = caret >= 0 ? "PCの入力欄（触れた位置にカーソルを移動）" : "PCの入力欄";
    box.textContent = "";
    const add = (t, cls) => {
      if (!t) return;
      const s = document.createElement("span");
      if (cls) s.className = cls;
      s.textContent = t;
      box.appendChild(s);
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
  },
};
Link.on("TF ", (b) => Mirror.update(unb64(b)));
$("mirror").addEventListener("pointerdown", (e) => {
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
  for (const s of $("mirror").childNodes) {
    if (s === node || s.contains(node)) { idx += s.classList && s.classList.contains("caret") ? 0 : off; break; }
    idx += s.classList && s.classList.contains("caret") ? 0 : s.textContent.length;
  }
  idx = Math.max(0, Math.min(idx, Mirror.d2p.length - 1));
  Link.send("CS " + Mirror.d2p[idx]);
});
let mirrorTimer = 0;
function updateMirrorVisibility() {
  const show = Views.current === "kbView" && (prefs.fieldAlways || typing);
  $("mirrorBox").hidden = !show;
  clearInterval(mirrorTimer);
  if (show) { Link.send("TQ"); mirrorTimer = setInterval(() => Link.send("TQ"), 700); }
}
echoHook = () => { if (!$("mirrorBox").hidden) setTimeout(() => Link.send("TQ"), 120); };
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
    c.type = "checkbox"; c.checked = !!prefs[key];
    c.onchange = () => { prefs[key] = c.checked; savePrefs(); after && after(); };
    const s = document.createElement("span"); s.textContent = title;
    l.append(c, s);
    box.appendChild(l);
  };
  slider("mouseSpeed", "マウスの速さ", 0.3, 4, 0.1);
  slider("mouseAccel", "マウスの加速", 0, 1.5, 0.1);
  slider("scrollSpeed", "スクロールの速さ", 0.3, 3, 0.1);
  check("invertTwoFinger", "2本指スクロールの向きを逆にする");
  check("fieldAlways", "PCの入力欄の内容を常に表示する（オフ: 文字を入力しているときだけ）");
  slider("turboRate", "連打の速さ（回/秒）", 5, 30, 1);
  check("positional", "ABXYをSwitchの位置どおりに割り当てる（オフ: 文字どおり A→A）", () => window.Pad && Pad.prefsChanged());
  check("floatingSticks", "コントローラーのスティックを触れた位置に出す", () => window.Pad && Pad.prefsChanged());
})();
$("padReset").onclick = () => { if (confirm("コントローラーの配置を初期に戻しますか？")) { window.Pad && Pad.resetLayout(); toast("初期配置に戻しました"); } };

// PCの音をスマホで聞く (このページを開いている間だけ)
const PcSound = {
  ctx: null, on: false, rate: 0, next: 0,
  toggle() {
    this.on = !this.on;
    if (this.on) {
      // iPhone では、ボタンを押したときに音の準備をする必要がある
      const AC = window.AudioContext || window.webkitAudioContext;
      if (!this.ctx) this.ctx = new AC();
      this.ctx.resume();
      this.next = 0;
      Link.sendRaw("A", enc.encode("1"));
    } else {
      Link.sendRaw("A", enc.encode("0"));
    }
    this.paint();
  },
  paint() { $("listen").textContent = "🎧 スマホで聞く: " + (this.on ? "ON" : "OFF"); $("listen").classList.toggle("on", this.on); },
  play(bytes) {
    if (!this.on || !this.ctx || !this.rate) return;
    const n = Math.floor(bytes.length / 4);
    if (!n) return;
    const dv = new DataView(bytes.buffer, bytes.byteOffset, n * 4);
    const buf = this.ctx.createBuffer(2, n, this.rate);
    const L = buf.getChannelData(0), R = buf.getChannelData(1);
    for (let i = 0; i < n; i++) { L[i] = dv.getInt16(i * 4, true) / 32768; R[i] = dv.getInt16(i * 4 + 2, true) / 32768; }
    const now = this.ctx.currentTime;
    if (this.next < now + 0.03) this.next = now + 0.08; // 途切れたら少し先から
    if (this.next > now + 0.5) return; // 遅れすぎは捨てる
    const src = this.ctx.createBufferSource();
    src.buffer = buf;
    src.connect(this.ctx.destination);
    src.start(this.next);
    this.next += n / this.rate;
  },
};
$("listen").onclick = () => PcSound.toggle();
Link.onData("a", (b) => { PcSound.rate = parseInt(dec.decode(b), 10) || 48000; });
Link.onData("A", (b) => PcSound.play(b));
Link.onState.push((s) => { if (s === "open" && PcSound.on) { PcSound.next = 0; Link.sendRaw("A", enc.encode("1")); } });

// ヘルプ
const openHelp = () => { $("helpSheet").hidden = false; };
$("helpBtn").onclick = openHelp;
$("helpBtn2").onclick = openHelp;
$("helpClose").onclick = () => { $("helpSheet").hidden = true; };
$("helpSheet").addEventListener("click", (e) => { if (e.target === $("helpSheet")) $("helpSheet").hidden = true; });
Link.onState.push((s) => {
  if (s === "open") $("aboutVer").textContent = "PhonePad ブラウザ版（PC: " + Link.pcName + " / v" + Link.pcVersion + "）";
});

// 初めて開いたときは、できること・できないことを見せる
if (Link.init()) {
  if (!load("helpSeen", false)) { store("helpSeen", true); openHelp(); }
}
