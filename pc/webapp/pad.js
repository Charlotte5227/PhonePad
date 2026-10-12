// PhonePad ブラウザ版 — コントローラー (Xbox 360 コントローラーとしてPCに認識される)
"use strict";

const Pad = (function () {
  const UP = 0x0001, DOWN = 0x0002, LEFT = 0x0004, RIGHT = 0x0008, START = 0x0010, BACK = 0x0020,
    LTHUMB = 0x0040, RTHUMB = 0x0080, LB = 0x0100, RB = 0x0200, GUIDE = 0x0400,
    XA = 0x1000, XB = 0x2000, XX = 0x4000, XY = 0x8000, LT = 1 << 16, RT = 1 << 17;
  const TURBO_ELIGIBLE = 0x3FFFF & ~(START | BACK | GUIDE);
  const C = { bg: "#1B1815", surface: "#26221E", surface2: "#332D27", line: "#4E453C", ink: "#F1EBE4",
    sub: "#A99D91", accent: "#E0A35A", accentInk: "#2A1A08", turbo: "#FF6B3D" };

  // 位置は画面の幅・高さに対する割合 [x, y]
  const DEFAULTS = {
    ZL: [0.075, 0.095], L: [0.075, 0.315], ZR: [0.925, 0.095], R: [0.925, 0.315],
    LS: [0.29, 0.36], DP: [0.27, 0.78], FACE: [0.73, 0.32], RS: [0.71, 0.74],
    MINUS: [0.40, 0.17], PLUS: [0.60, 0.17], T: [0.445, 0.36], H: [0.555, 0.36],
    L3: [0.44, 0.60], R3: [0.56, 0.60], GEAR: [0.46, 0.06], EDIT: [0.54, 0.06], SWITCH: [0.50, 0.885],
  };
  const SHAPES = ["標準", "丸", "角丸", "四角"];

  const cv = document.getElementById("padCanvas");
  const g = cv.getContext("2d");
  let W = 0, H = 0, dpr = 1;
  let specs = {};

  function loadLayout() {
    specs = {};
    for (const [id, d] of Object.entries(DEFAULTS)) specs[id] = [d[0], d[1], 1, 0];
    const saved = load("padLayout", {});
    for (const [id, v] of Object.entries(saved)) {
      if (specs[id] && Array.isArray(v) && v.length >= 3) {
        specs[id] = [clamp(v[0], 0, 1), clamp(v[1], 0, 1), clamp(v[2], 0.4, 2.5), clamp(v[3] || 0, 0, 3) | 0];
      }
    }
  }
  function saveLayout() { store("padLayout", specs); }
  function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }

  // ---- 部品 ----
  function btn(label, bit, kind, face) { return { label, fixedBit: bit, kind: kind || "normal", face, id: "", cx: 0, cy: 0, r: 0, rect: null }; }
  const bA = btn("A", 0, "face", "A"), bB = btn("B", 0, "face", "B"), bX = btn("X", 0, "face", "X"), bY = btn("Y", 0, "face", "Y");
  const bL = btn("L", LB, "shoulder"), bR = btn("R", RB, "shoulder"), bZL = btn("ZL", LT, "shoulder"), bZR = btn("ZR", RT, "shoulder");
  const bMinus = btn("−", BACK), bPlus = btn("+", START), bHome = btn("H", GUIDE), bTurbo = btn("T", 0, "turbo");
  const bL3 = btn("L3", LTHUMB), bR3 = btn("R3", RTHUMB);
  const bSwitch = btn("⌨", 0, "switch"), bGear = btn("⚙", 0, "gear"), bEdit = btn("✎", 0, "edit");
  const faces = [bA, bB, bX, bY];
  const circles = [bA, bB, bX, bY, bMinus, bPlus, bHome, bTurbo, bL3, bR3, bGear, bEdit];
  const rects = [bL, bR, bZL, bZR, bSwitch];
  const isRect = (b) => b.kind === "shoulder" || b.kind === "switch";

  function bitOf(b) {
    if (b.kind !== "face") return b.fixedBit;
    if (prefs.positional) return { A: XB, B: XA, X: XY, Y: XX }[b.face]; // Switchの位置 → Xboxの同じ位置
    return { A: XA, B: XB, X: XX, Y: XY }[b.face];
  }

  function stick(label) { return { label, homeX: 0, homeY: 0, cx: 0, cy: 0, r: 0, kx: 0, ky: 0, active: false }; }
  const sL = stick("L"), sR = stick("R");
  let dcx = 0, dcy = 0, dOff = 0, dR = 0, dpadBits = 0, faceCx = 0, faceCy = 0, faceOff = 0;
  const bounds = {};

  // 画面の端に少し余白をとる
  const MX = 12, MY = 10;
  const CW = () => W - MX * 2, CH = () => H - MY * 2;
  function layout() {
    const sp = (id) => specs[id];
    const cx = (id) => MX + sp(id)[0] * CW(), cy = (id) => MY + sp(id)[1] * CH(), sc = (id) => sp(id)[2];
    const bound = (id, x, y, hw, hh) => { bounds[id] = { l: x - hw, t: y - hh, r: x + hw, b: y + hh }; };
    const rectBtn = (b, id, w, h) => {
      b.id = id;
      const x = cx(id), y = cy(id), s = sc(id);
      b.rect = { l: x - w * s / 2, t: y - h * s / 2, r: x + w * s / 2, b: y + h * s / 2 };
      bound(id, x, y, w * s / 2, h * s / 2);
    };
    rectBtn(bZL, "ZL", 0.15 * CW(), 0.19 * CH()); rectBtn(bL, "L", 0.15 * CW(), 0.21 * CH());
    rectBtn(bZR, "ZR", 0.15 * CW(), 0.19 * CH()); rectBtn(bR, "R", 0.15 * CW(), 0.21 * CH());
    rectBtn(bSwitch, "SWITCH", 0.20 * CW(), 0.17 * CH());
    const stk = (s, id) => {
      s.homeX = cx(id); s.homeY = cy(id); s.r = CH() * 0.17 * sc(id);
      if (!s.active) { s.cx = s.homeX; s.cy = s.homeY; }
      bound(id, s.homeX, s.homeY, s.r, s.r);
    };
    stk(sL, "LS"); stk(sR, "RS");
    dcx = cx("DP"); dcy = cy("DP"); dOff = CH() * 0.115 * sc("DP"); dR = CH() * 0.06 * sc("DP");
    bound("DP", dcx, dcy, dOff + dR, dOff + dR);
    faceCx = cx("FACE"); faceCy = cy("FACE"); faceOff = CH() * 0.13 * sc("FACE");
    const fr = CH() * 0.066 * sc("FACE");
    bY.cx = faceCx; bY.cy = faceCy - faceOff; bB.cx = faceCx; bB.cy = faceCy + faceOff;
    bX.cx = faceCx - faceOff; bX.cy = faceCy; bA.cx = faceCx + faceOff; bA.cy = faceCy;
    faces.forEach((f) => { f.r = fr; f.id = "FACE"; });
    bound("FACE", faceCx, faceCy, faceOff + fr, faceOff + fr);
    const circ = (b, id, base) => { b.id = id; b.cx = cx(id); b.cy = cy(id); b.r = CH() * base * sc(id); bound(id, b.cx, b.cy, b.r, b.r); };
    circ(bMinus, "MINUS", 0.05); circ(bPlus, "PLUS", 0.05); circ(bTurbo, "T", 0.062); circ(bHome, "H", 0.062);
    circ(bL3, "L3", 0.055); circ(bR3, "R3", 0.055); circ(bGear, "GEAR", 0.045); circ(bEdit, "EDIT", 0.045);
  }

  // ---- 入力 ----
  const owners = new Map(); // pointerId → {type, btn|stick}
  let auto = 0, globalTurbo = false, lastLine = "";

  function hit(x, y) {
    let best = null, bestD = Infinity;
    for (const b of circles) {
      const d = Math.hypot(x - b.cx, y - b.cy);
      if (d <= b.r * 1.3 && d / b.r < bestD) { bestD = d / b.r; best = b; }
    }
    if (best) return { type: "b", btn: best };
    for (const b of rects) {
      const p = 10, r = b.rect;
      if (x >= r.l - p && x <= r.r + p && y >= r.t - p && y <= r.b + p) return { type: "b", btn: b };
    }
    if (Math.hypot(x - dcx, y - dcy) < dOff + dR * 1.9) return { type: "d" };
    const zone = prefs.floatingSticks ? 2.1 : 1.5;
    const dl = Math.hypot(x - sL.homeX, y - sL.homeY) / sL.r;
    const dr = Math.hypot(x - sR.homeX, y - sR.homeY) / sR.r;
    if (dl < zone && dl <= dr) return { type: "s", stick: sL };
    if (dr < zone) return { type: "s", stick: sR };
    if (Math.hypot(x - faceCx, y - faceCy) < faceOff + bA.r * 1.8) {
      let m = faces[0];
      for (const f of faces) if (Math.hypot(x - f.cx, y - f.cy) < Math.hypot(x - m.cx, y - m.cy)) m = f;
      return { type: "b", btn: m };
    }
    return null;
  }

  function dpadDir(x, y) {
    const dx = x - dcx, dy = y - dcy;
    if (Math.hypot(dx, dy) < dOff * 0.3) return 0;
    const a = Math.atan2(-dy, dx) * 180 / Math.PI;
    const sector = Math.floor(((a + 360 + 22.5) % 360) / 45);
    return [RIGHT, RIGHT | UP, UP, UP | LEFT, LEFT, LEFT | DOWN, DOWN, DOWN | RIGHT][sector];
  }

  function held() {
    let m = dpadBits;
    for (const o of owners.values()) if (o.type === "b" && ["normal", "face", "shoulder"].includes(o.btn.kind)) m |= bitOf(o.btn);
    return m;
  }

  function axes(s) {
    if (s.r <= 0) return [0, 0];
    const nx = s.kx / s.r, ny = -s.ky / s.r;
    const m = Math.hypot(nx, ny);
    const dz = 0.08;
    if (m <= dz || m === 0) return [0, 0];
    const norm = clamp((m - dz) / (1 - dz), 0, 1);
    const k = Math.min(norm, 1) / m;
    return [clamp(Math.round(nx * k * 32767), -32767, 32767), clamp(Math.round(ny * k * 32767), -32767, 32767)];
  }

  function sendState(force) {
    const [lx, ly] = axes(sL), [rx, ry] = axes(sR);
    const line = "GP " + held().toString(16) + " " + auto.toString(16) + " " + (globalTurbo ? 1 : 0) + " " +
      Math.round(prefs.turboRate) + " " + lx + " " + ly + " " + rx + " " + ry;
    if (force || line !== lastLine) { Link.send(line); lastLine = line; }
  }

  // T単体 → 全体ターボ (押している間連打) のON/OFF。ボタンを押しながらT → そのボタンの自動連打のON/OFF
  function turboPressed() {
    const others = held() & TURBO_ELIGIBLE;
    if (others) auto ^= others;
    else globalTurbo = !globalTurbo;
  }

  function stickSet(s, x, y) {
    let dx = x - s.cx, dy = y - s.cy;
    const l = Math.hypot(dx, dy);
    if (l > s.r) { dx = dx / l * s.r; dy = dy / l * s.r; }
    s.kx = dx; s.ky = dy;
  }

  // ---- 編集 ----
  let editing = false, selected = null, dragging = null, dragOff = [0, 0];

  function editHit(x, y) {
    let best = null, area = Infinity;
    for (const [id, b] of Object.entries(bounds)) {
      if (x >= b.l - 8 && x <= b.r + 8 && y >= b.t - 8 && y <= b.b + 8) {
        const a = (b.r - b.l) * (b.b - b.t);
        if (a < area) { area = a; best = id; }
      }
    }
    return best;
  }

  function setEditing(on) {
    editing = on;
    selected = null;
    document.getElementById("editBar").hidden = !on;
    if (!on) saveLayout();
    draw();
  }
  document.getElementById("edDone").onclick = () => setEditing(false);
  document.getElementById("edSmall").onclick = () => { if (selected) { specs[selected][2] = clamp(specs[selected][2] - 0.1, 0.4, 2.5); layout(); draw(); } };
  document.getElementById("edBig").onclick = () => { if (selected) { specs[selected][2] = clamp(specs[selected][2] + 0.1, 0.4, 2.5); layout(); draw(); } };
  document.getElementById("edShape").onclick = () => {
    if (!selected) { toast("形を変える部品を選んでください"); return; }
    specs[selected][3] = ((specs[selected][3] | 0) + 1) % 4;
    toast("形: " + SHAPES[specs[selected][3]]);
    draw();
  };
  document.getElementById("edReset").onclick = () => {
    if (!confirm("配置を初期に戻しますか？")) return;
    store("padLayout", {}); loadLayout(); layout(); draw();
  };

  // ---- タッチ ----
  function pos(e) { const r = cv.getBoundingClientRect(); return [e.clientX - r.left, e.clientY - r.top]; }

  cv.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    cv.setPointerCapture(e.pointerId);
    const [x, y] = pos(e);
    if (editing) {
      const id = editHit(x, y);
      selected = id;
      if (id) { dragging = { id, pid: e.pointerId }; dragOff = [MX + specs[id][0] * CW() - x, MY + specs[id][1] * CH() - y]; }
      draw();
      return;
    }
    const o = hit(x, y);
    if (!o) return;
    if (o.type === "b") {
      const k = o.btn.kind;
      if (k === "switch") { Views.show("kbView"); return; }
      if (k === "gear") { Views.show("moreView"); return; }
      if (k === "edit") { setEditing(true); return; }
      if (k === "turbo") { turboPressed(); sendState(); draw(); return; }
      owners.set(e.pointerId, o);
    } else if (o.type === "d") {
      owners.set(e.pointerId, o);
      dpadBits = dpadDir(x, y);
    } else if (o.type === "s") {
      for (const v of owners.values()) if (v.type === "s" && v.stick === o.stick) return; // 同じスティックに2本目の指
      owners.set(e.pointerId, o);
      const s = o.stick;
      s.active = true;
      if (prefs.floatingSticks) { s.cx = x; s.cy = y; } else { s.cx = s.homeX; s.cy = s.homeY; }
      stickSet(s, x, y);
    }
    sendState();
    draw();
  });
  cv.addEventListener("pointermove", (e) => {
    const [x, y] = pos(e);
    if (editing) {
      if (dragging && dragging.pid === e.pointerId) {
        const id = dragging.id;
        specs[id][0] = clamp((x + dragOff[0] - MX) / CW(), 0, 1);
        specs[id][1] = clamp((y + dragOff[1] - MY) / CH(), 0, 1);
        layout(); draw();
      }
      return;
    }
    const o = owners.get(e.pointerId);
    if (!o) return;
    if (o.type === "d") { const nd = dpadDir(x, y); if (nd !== dpadBits) dpadBits = nd; }
    else if (o.type === "s") stickSet(o.stick, x, y);
    else if (o.type === "b" && ["face"].includes(o.btn.kind)) {
      // ABXY は指をずらして隣のボタンへ移れる
      const n = hit(x, y);
      if (n && n.type === "b" && n.btn.kind === "face") o.btn = n.btn;
    }
    sendState();
    draw();
  });
  const up = (e) => {
    if (editing) { if (dragging && dragging.pid === e.pointerId) dragging = null; return; }
    const o = owners.get(e.pointerId);
    if (!o) return;
    owners.delete(e.pointerId);
    if (o.type === "d") dpadBits = 0;
    else if (o.type === "s") { const s = o.stick; s.active = false; s.kx = s.ky = 0; s.cx = s.homeX; s.cy = s.homeY; }
    sendState();
    draw();
  };
  cv.addEventListener("pointerup", up);
  cv.addEventListener("pointercancel", up);

  function releaseAll() {
    owners.clear(); dpadBits = 0;
    for (const s of [sL, sR]) { s.active = false; s.kx = s.ky = 0; s.cx = s.homeX; s.cy = s.homeY; }
    lastLine = "";
  }

  // ---- 描画 ----
  function shapePath(id, l, t, r, b, round) {
    const shape = specs[id] ? specs[id][3] | 0 : 0;
    const w = r - l, h = b - t;
    g.beginPath();
    if (shape === 1 || (shape === 0 && round)) g.ellipse((l + r) / 2, (t + b) / 2, w / 2, h / 2, 0, 0, Math.PI * 2);
    else {
      const rr = shape === 2 ? Math.min(w, h) * 0.28 : shape === 3 ? 3 : 14;
      g.roundRect ? g.roundRect(l, t, w, h, rr) : g.rect(l, t, w, h);
    }
  }
  function label(text, x, y, size, color) {
    g.fillStyle = color;
    g.font = "700 " + Math.round(size) + "px -apple-system, 'Hiragino Sans', sans-serif";
    g.textAlign = "center"; g.textBaseline = "middle";
    g.fillText(text, x, y + 1);
  }
  function isAuto(b) { const bit = bitOf(b); return bit && (auto & bit); }
  function circleBtn(b, on, text, scale) {
    g.lineWidth = 3;
    shapePath(b.id, b.cx - b.r, b.cy - b.r, b.cx + b.r, b.cy + b.r, true);
    g.fillStyle = on ? C.accent : C.surface; g.fill();
    g.strokeStyle = C.accent; g.stroke();
    if (isAuto(b)) {
      shapePath(b.id, b.cx - b.r - 5, b.cy - b.r - 5, b.cx + b.r + 5, b.cy + b.r + 5, true);
      g.strokeStyle = C.turbo; g.stroke();
    }
    label(text || b.label, b.cx, b.cy, b.r * (scale || 0.9), on ? C.accentInk : C.accent);
  }
  function rectBtn(b, on) {
    const r = b.rect;
    g.lineWidth = 3;
    shapePath(b.id, r.l, r.t, r.r, r.b, false);
    g.fillStyle = on ? C.accent : C.surface; g.fill();
    g.strokeStyle = C.accent; g.stroke();
    if (isAuto(b)) { shapePath(b.id, r.l + 5, r.t + 5, r.r - 5, r.b - 5, false); g.strokeStyle = C.turbo; g.stroke(); }
    label(b.label, (r.l + r.r) / 2, (r.t + r.b) / 2, Math.min(H * 0.07, (r.b - r.t) * 0.42), on ? C.accentInk : C.accent);
  }
  function drawStick(s) {
    const idle = prefs.floatingSticks && !s.active && !editing;
    g.lineWidth = 3;
    g.setLineDash(idle ? [8, 8] : []);
    g.beginPath(); g.arc(s.cx, s.cy, s.r, 0, Math.PI * 2);
    g.fillStyle = C.surface; g.fill(); g.strokeStyle = idle ? C.line : C.accent; g.stroke();
    g.setLineDash([]);
    g.beginPath(); g.arc(s.cx + s.kx, s.cy + s.ky, s.r * 0.45, 0, Math.PI * 2);
    g.fillStyle = s.active ? C.accent : C.surface2; g.fill(); g.strokeStyle = C.accent; g.stroke();
    label(s.label, s.cx + s.kx, s.cy + s.ky, s.r * 0.32, s.active ? C.accentInk : C.accent);
  }
  function draw() {
    if (!W) return;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.fillStyle = C.bg; g.fillRect(0, 0, W, H);
    const pressed = (b) => { for (const o of owners.values()) if (o.type === "b" && o.btn === b) return true; return false; };
    drawStick(sL); drawStick(sR);
    // 十字キー
    const dirs = [[UP, 0, -1, "▲"], [DOWN, 0, 1, "▼"], [LEFT, -1, 0, "◀"], [RIGHT, 1, 0, "▶"]];
    for (const [bit, ox, oy, lab] of dirs) {
      const t = { id: "DP", cx: dcx + ox * dOff, cy: dcy + oy * dOff, r: dR, label: lab, kind: "dp", fixedBit: bit };
      circleBtn(t, (dpadBits & bit) !== 0, lab, 0.75);
    }
    for (const b of circles) {
      if (b.kind === "turbo") {
        circleBtn(b, globalTurbo, "T");
        continue;
      }
      circleBtn(b, pressed(b));
    }
    for (const b of rects) rectBtn(b, pressed(b));
    if ((globalTurbo || auto) && !editing) {
      label(globalTurbo ? "連打: 押している間" : "自動連打あり", W / 2, H * 0.97, 13, C.turbo);
    }
    if (editing) {
      for (const [id, b] of Object.entries(bounds)) {
        g.setLineDash([6, 6]); g.lineWidth = id === selected ? 3 : 1.5;
        g.strokeStyle = id === selected ? C.ink : C.sub;
        g.strokeRect(b.l - 4, b.t - 4, b.r - b.l + 8, b.b - b.t + 8);
      }
      g.setLineDash([]);
      label(selected ? "ドラッグで移動・上のボタンで大きさと形" : "動かしたい部品に触れてください", W / 2, H * 0.97, 13, C.ink);
    }
  }

  function resize() {
    const r = cv.getBoundingClientRect();
    if (!r.width || !r.height) return;
    dpr = window.devicePixelRatio || 1;
    W = r.width; H = r.height;
    cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr);
    const portrait = H > W;
    document.getElementById("rotate").hidden = !portrait;
    cv.style.visibility = portrait ? "hidden" : "visible";
    layout();
    draw();
  }

  document.getElementById("padBack").onclick = () => Views.show("kbView");

  loadLayout();
  window.addEventListener("resize", () => { if (Views.current === "padView") setTimeout(resize, 50); });
  Views.on("padView", (on) => {
    if (on) { setTimeout(resize, 30); lastLine = ""; sendState(true); }
    else { releaseAll(); if (editing) setEditing(false); Link.send("GX"); }
  });
  // PCとつなぎ直したら、今の状態を送り直す
  Link.onState.push((s) => { if (s === "open" && Views.current === "padView") { lastLine = ""; sendState(true); } });
  Link.on("GP ERR", (b) => toast("コントローラーを使えません: " + unb64(b)));

  return {
    prefsChanged() { lastLine = ""; if (Views.current === "padView") { layout(); draw(); sendState(); } },
    resetLayout() { store("padLayout", {}); loadLayout(); layout(); draw(); },
  };
})();
