// PhonePad ブラウザ版 — キーボード画面の配置
// 部品ごとに位置・大きさ・表示を変えられる (コントローラーと同じ考え方)。
// 位置は画面に対する割合 [x, y, 幅, 高さ, 隠す] で、マウス・片手(右手用)・片手(左手用) ごとに保存する。
// 文字を打っている間は、決まった並び (#kbCard) に切り替える。
"use strict";

const KbStage = (() => {
  const stage = $("stage"), ov = $("kbOverlay"), handle = $("kbHandle");
  const R = (x, y, w, h, off) => [x, y, w, h, off ? 1 : 0];
  // id → { el, name, mode: "" (いつも) | "tp" (マウス) | "one" (片手), fixed: 隠せない, custom }
  const parts = new Map();
  function addPart(id, el, name, mode, opts) {
    el.classList.add("part");
    el.dataset.p = id;
    if (el.parentNode !== stage) stage.insertBefore(el, ov);
    parts.set(id, Object.assign({ el, name, mode: mode || "" }, opts || {}));
    return el;
  }

  // ---- 部品 ----
  const KEYS1 = [["esc", "Esc"], ["tab", "Tab"], ["ctrl", "Ctrl"], ["shift", "Shift"], ["alt", "Alt"], ["win", "Win"], ["fn", "Fn"]];
  const KEYS2 = [["left", "←"], ["up", "↑"], ["down", "↓"], ["right", "→"], ["bksp", "⌫"], ["del", "Del"], ["enter", "⏎"]];
  const NAMES = { esc: "Esc", tab: "Tab", ctrl: "Ctrl", shift: "Shift", alt: "Alt", win: "Win", fn: "Fn（ファンクションキー）",
    left: "←", up: "↑", down: "↓", right: "→", bksp: "⌫（1文字消す）", del: "Del", enter: "⏎（Enter）" };

  addPart("pad", $("pad"), "マウス（トラックパッド）", "tp");
  addPart("ohPad", $("ohPad"), "マウス（片手）", "one");
  addPart("input", $("fakeInput"), "入力欄", "", { fixed: true });
  addPart("mirror", $("mirrorPart"), "PCの入力欄（PCで入力中の文字）");

  const plain = (label, fn) => {
    const b = document.createElement("button");
    b.textContent = label;
    b.onclick = fn;
    return b;
  };
  addPart("star", plain("★", () => $("starBtn").onclick()), "★ ショートカット");
  const keyMap = { esc: "ESC", tab: "TAB", left: "LEFT", up: "UP", down: "DOWN", right: "RIGHT", bksp: "BACK", del: "DEL", enter: "ENTER" };
  const modMap = { ctrl: CTRL, shift: SHIFT, alt: ALT, win: WIN };
  const repeatKeys = new Set(["left", "up", "down", "right", "bksp", "del"]);
  for (const [id, label] of KEYS1.concat(KEYS2)) {
    let el;
    if (modMap[id]) el = modButton(label, modMap[id]);
    else if (id === "fn") el = plain("Fn", () => { $("fnSheet").hidden = false; });
    else el = keyButton(label, () => sendKey(keyMap[id]), repeatKeys.has(id));
    addPart(id, el, NAMES[id]);
  }
  addPart("oneMode", $("ohEnter"), "「片手操作」ボタン", "tp");
  addPart("mbL", $("mbL"), "左ボタン", "tp");
  addPart("mbM", $("mbM"), "長押しボタン（スクロール・中クリック）", "tp");
  addPart("mbR", $("mbR"), "右ボタン", "tp");
  addPart("hand", $("ohHand"), "左手用・右手用の切り替え", "one");
  addPart("ohExit", $("ohExit"), "「片手をやめる」ボタン", "one");
  addPart("ohUp", $("ohUp"), "▲ スクロール", "one");
  addPart("ohDown", $("ohDown"), "▼ スクロール", "one");
  addPart("ohRight", $("ohRight"), "右クリック", "one");
  addPart("ohGrab", $("ohGrab"), "つかむ（ドラッグ）", "one");
  addPart("ohLeft", $("ohLeft"), "左クリック", "one");

  // ファンクションキーなど (Fn から開く)
  for (const [l, k] of fnKeys) {
    $("fnSheetKeys").append(keyButton(l, () => { sendKey(k); $("fnSheet").hidden = true; }));
  }
  $("fnSheetClose").onclick = () => { $("fnSheet").hidden = true; };
  $("fnSheet").addEventListener("click", (e) => { if (e.target === $("fnSheet")) $("fnSheet").hidden = true; });

  // PCの入力欄 (配置で表示にしたとき)
  Mirror.boxes.push({ box: $("mirror2"), title: $("mirrorTitle2") });
  mirrorTap($("mirror2"));

  // 入力欄の見た目の部品: タップすると決まった並びに切り替えて、本物の入力欄に文字を打てるようにする
  $("fakeInput").addEventListener("click", () => {
    if (editing) return;
    kbView.classList.add("typing");
    const t = $("typer");
    t.focus();
    if (document.activeElement !== t) kbView.classList.remove("typing");
  });

  // ---- 自分で追加したキー ----
  let custom = load("kbCustom", []);
  function runCustom(c) {
    if (c.r) Link.send("RUN " + b64(c.r));
    else Link.send("KY " + c.k + " " + (c.m | Mods.consume()));
  }
  function addCustomPart(c) {
    const el = keyButton(c.l, () => runCustom(c));
    addPart(c.id, el, "追加したキー「" + c.l + "」", "", { custom: c });
  }
  custom.forEach(addCustomPart);

  // ---- 配置 ----
  function defaults(mode) {
    const land = mode.endsWith(".land"), m = mode.replace(".land", "");
    const L = { input: R(.02, .01, .80, .075), star: R(.84, .01, .14, .075), mirror: R(.02, .255, .96, .13, 1) };
    KEYS1.forEach(([id], i) => { L[id] = R(.02 + i * .137, .10, .127, .065); });
    KEYS2.forEach(([id], i) => { L[id] = R(.02 + i * .137, .175, .127, .065); });
    if (m === "tp") {
      Object.assign(L, { pad: R(.02, .255, .96, .60), oneMode: R(.74, .27, .22, .05),
        mbL: R(.02, .87, .31, .12), mbM: R(.345, .87, .31, .12), mbR: R(.67, .87, .31, .12) });
    } else {
      // 右手用: ボタンの列を右 (親指の側) に。左手用は左右を入れ替える
      const o = { ohPad: R(.02, .255, .66, .735), hand: R(.04, .27, .24, .05),
        ohExit: R(.70, .255, .28, .06), ohUp: R(.70, .325, .28, .12), ohDown: R(.70, .455, .28, .12),
        ohRight: R(.70, .585, .28, .11), ohGrab: R(.70, .705, .28, .11), ohLeft: R(.70, .825, .28, .165) };
      if (m === "oneL") for (const k in o) o[k][0] = +(1 - o[k][0] - o[k][2]).toFixed(3);
      Object.assign(L, o);
    }
    if (prefs.fieldAlways) {
      // 以前「PCの入力欄を常に表示」にしていた人は、入力欄を出してマウスを下に詰める
      for (const id in L) {
        const r = L[id];
        if (r[1] < 0.25) continue;
        r[1] = +(0.385 + (r[1] - 0.255) * (0.615 / 0.745)).toFixed(3);
        r[3] = +(r[3] * (0.615 / 0.745)).toFixed(3);
      }
      L.mirror = R(.02, .255, .96, .12);
    }
    if (land) {
      // 横向き: 入力欄とキーを左半分に、マウスを右半分に並べ直す
      for (const id in L) {
        const r = L[id];
        if (id === "mirror") { L[id] = R(.01, .56, .48, .4, r[4]); continue; }
        if (r[1] < 0.25) L[id] = [+(.01 + r[0] * .48).toFixed(3), +(.02 + r[1] * 2).toFixed(3), +(r[2] * .48).toFixed(3), +(r[3] * 2).toFixed(3), r[4]];
        else L[id] = [+(.51 + r[0] * .48).toFixed(3), +(.02 + (r[1] - .255) / .745 * .96).toFixed(3), +(r[2] * .48).toFixed(3), +(r[3] / .745 * .96).toFixed(3), r[4]];
      }
    }
    for (const c of custom) L[c.id] = (c.rect || R(.35, .45, .3, .07)).slice();
    return L;
  }
  // 縦向き・横向きでも別々に保存する (横向きは ".land")
  const modeNow = () => (prefs.oneHand ? (prefs.oneHandLeft ? "oneL" : "oneR") : "tp") + (window.innerWidth > window.innerHeight ? ".land" : "");
  let cur = "", L = {};
  function loadLayout(m) {
    const L = defaults(m);
    const saved = load("kbLayout." + m, {});
    for (const id in saved) {
      const r = saved[id];
      if (L[id] && Array.isArray(r) && r.length >= 4 && r.slice(0, 4).every((v) => typeof v === "number" && isFinite(v))) L[id] = [r[0], r[1], r[2], r[3], r[4] ? 1 : 0];
    }
    return L;
  }
  function save() { store("kbLayout." + cur, L); }
  function inMode(p, m) { return p.mode === "" || (p.mode === "tp") === m.startsWith("tp"); }

  function place(id) {
    const p = parts.get(id), r = L[id];
    if (!p || !r) return;
    const s = p.el.style;
    s.left = r[0] * 100 + "%"; s.top = r[1] * 100 + "%"; s.width = r[2] * 100 + "%"; s.height = r[3] * 100 + "%";
    p.el.classList.toggle("off", !!r[4]);
  }
  function refresh() {
    const m = modeNow();
    if (m !== cur) { cur = m; L = loadLayout(m); sel = null; }
    for (const [id, p] of parts) {
      const show = inMode(p, cur) && !!L[id];
      p.el.classList.toggle("nm", !show);
      if (show) place(id);
    }
    paintSel();
    updateMirrorVisibility();
  }
  // 「PCの入力欄」を表示にしているときは、PCの入力欄を読み続ける
  mirrorPartShown = () => !!(L.mirror && !L.mirror[4]);

  // ---- 配置を変える ----
  let editing = false, sel = null, drag = null;
  function setEditing(on) {
    if (on && typing) $("typer").blur();
    editing = on;
    sel = null; drag = null;
    stage.classList.toggle("editing", on);
    ov.hidden = !on;
    document.body.classList.toggle("kbEditing", on);
    $("kbEditBar").hidden = !on;
    $("topBar").hidden = on;
    paintSel();
  }
  function paintSel() {
    for (const [id, p] of parts) p.el.classList.toggle("sel", editing && id === sel);
    const p = sel && parts.get(sel);
    $("kbSel").textContent = p ? p.name : "部品をタップして選ぶ";
    $("kbHide").textContent = p && p.custom ? "削除" : p && L[sel][4] ? "表示" : "隠す";
    placeHandle();
  }
  function placeHandle() {
    const r = sel && L[sel];
    handle.hidden = !(editing && r);
    if (handle.hidden) return;
    const b = stage.getBoundingClientRect();
    // 右下の角 (画面の外にはみ出さないように)
    handle.style.left = Math.min((r[0] + r[2]) * b.width, b.width - 14) + "px";
    handle.style.top = Math.min((r[1] + r[3]) * b.height, b.height - 14) + "px";
  }
  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
  const snap = (v) => Math.round(v / 0.005) * 0.005;
  const at = (e) => { const b = stage.getBoundingClientRect(); return { x: (e.clientX - b.left) / b.width, y: (e.clientY - b.top) / b.height, b }; };

  ov.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    const { x, y } = at(e);
    if (e.target === handle && sel) {
      drag = { kind: "size", id: sel, sx: x, sy: y, r: L[sel].slice() };
    } else {
      // 触れた場所にある部品のうち、いちばん小さいもの (大きなマウスの上のボタンも選べるように)
      let best = null, area = Infinity;
      for (const [id, p] of parts) {
        const r = L[id];
        if (!r || !inMode(p, cur)) continue;
        if (x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3] && r[2] * r[3] < area) { best = id; area = r[2] * r[3]; }
      }
      sel = best;
      drag = best ? { kind: "move", id: best, sx: x, sy: y, r: L[best].slice() } : null;
      paintSel();
    }
    if (drag) ov.setPointerCapture(e.pointerId);
  });
  ov.addEventListener("pointermove", (e) => {
    if (!drag) return;
    const { x, y, b } = at(e);
    const r = L[drag.id], o = drag.r;
    if (drag.kind === "move") {
      r[0] = clamp(snap(o[0] + x - drag.sx), 0, 1 - o[2]);
      r[1] = clamp(snap(o[1] + y - drag.sy), 0, 1 - o[3]);
    } else {
      r[2] = clamp(snap(o[2] + x - drag.sx), 30 / b.width, 1 - o[0]);
      r[3] = clamp(snap(o[3] + y - drag.sy), 26 / b.height, 1 - o[1]);
    }
    place(drag.id);
    placeHandle();
  });
  const endDrag = () => { if (drag) { drag = null; save(); } };
  ov.addEventListener("pointerup", endDrag);
  ov.addEventListener("pointercancel", endDrag);

  $("kbEdit").onclick = () => setEditing(true);
  $("kbDone").onclick = () => setEditing(false);
  $("kbHide").onclick = () => {
    const p = sel && parts.get(sel);
    if (!p) { toast("変えたい部品をタップして選んでください"); return; }
    if (p.fixed) { toast("入力欄は隠せません（動かす・大きさを変えることはできます）"); return; }
    if (p.custom) {
      if (!confirm("「" + p.custom.l + "」を削除しますか？")) return;
      custom = custom.filter((c) => c !== p.custom);
      store("kbCustom", custom);
      p.el.remove();
      parts.delete(sel);
      delete L[sel];
      sel = null;
      save();
      paintSel();
      return;
    }
    L[sel][4] = L[sel][4] ? 0 : 1;
    place(sel);
    save();
    paintSel();
    updateMirrorVisibility();
  };
  $("kbReset").onclick = () => {
    if (!confirm("この画面の配置を初期に戻しますか？（追加したキーも消えます）")) return;
    resetAll(false);
  };
  function resetAll(all) {
    for (const c of custom) { const p = parts.get(c.id); if (p) p.el.remove(); parts.delete(c.id); }
    custom = [];
    store("kbCustom", custom);
    if (all) ["tp", "oneR", "oneL"].forEach((m) => { unstore("kbLayout." + m); unstore("kbLayout." + m + ".land"); });
    else unstore("kbLayout." + cur);
    cur = "";
    refresh();
  }
  // PC から配置が届いたとき・アプリごとの配置に切り替わったとき
  function reload() {
    if (editing) setEditing(false);
    for (const c of custom) { const p = parts.get(c.id); if (p) p.el.remove(); parts.delete(c.id); }
    custom = load("kbCustom", []);
    custom.forEach(addCustomPart);
    cur = "";
    refresh();
  }
  $("kbResetAll").onclick = () => {
    if (!confirm("キーボード画面の配置を、マウス・片手操作の全部で初期に戻しますか？（追加したキーも消えます）")) return;
    resetAll(true);
    toast("初期配置に戻しました");
  };

  // キーを追加
  $("kbAdd").onclick = () => { $("ckLabel").value = $("ckKeys").value = $("ckRun").value = ""; $("ckSheet").hidden = false; };
  $("ckCancel").onclick = () => { $("ckSheet").hidden = true; };
  $("ckSheet").addEventListener("click", (e) => { if (e.target === $("ckSheet")) $("ckSheet").hidden = true; });
  $("ckOk").onclick = () => {
    const keys = $("ckKeys").value.trim(), run = $("ckRun").value.trim();
    const l = $("ckLabel").value.trim() || keys || run;
    if (!keys && !run) { toast("キーか、開くアプリ・サイトを入れてください"); return; }
    const c = { id: "c" + Date.now().toString(36), l: l.slice(0, 24), k: "", m: 0, r: "" };
    if (run) c.r = run;
    else {
      const k = parseCombo(keys);
      if (!k) { toast("キーの書き方が分かりません（例: F5、Ctrl+C、Alt+Tab）"); return; }
      c.k = k.k; c.m = k.m;
    }
    c.rect = R(.35, .45, .3, .07);
    custom.push(c);
    store("kbCustom", custom);
    addCustomPart(c);
    L[c.id] = c.rect.slice();
    save();
    $("ckSheet").hidden = true;
    refresh();
    sel = c.id;
    paintSel();
    toast("「" + c.l + "」を追加しました。動かして好きな場所に置いてください");
  };

  // ---- 画面の切り替え ----
  Views.on("kbView", (on) => {
    $("kbEdit").hidden = !on;
    if (!on && editing) setEditing(false);
    if (on) requestAnimationFrame(placeHandle);
  });
  $("kbEdit").hidden = Views.current !== "kbView";
  // 向きが変わったら、その向きの配置にする
  window.addEventListener("resize", () => { if (!typing && !drag && modeNow() !== cur) refresh(); placeHandle(); });
  window.addEventListener("pp-resize", placeHandle);

  // Android の Chrome などでは、キーボードを「戻る」で閉じても入力欄から外れないので、
  // キーボードが閉じたら入力をやめて、自分の配置に戻す
  let fullH = window.visualViewport ? window.visualViewport.height : 0, lastW = window.innerWidth, kbSeen = false;
  window.addEventListener("pp-resize", () => {
    const vv = window.visualViewport;
    if (!vv) return;
    if (window.innerWidth !== lastW) { lastW = window.innerWidth; fullH = vv.height; }
    if (!typing) { fullH = Math.max(fullH, vv.height); kbSeen = false; return; }
    if (vv.height < fullH - 120) kbSeen = true;
    else if (kbSeen) { kbSeen = false; $("typer").blur(); }
  });

  refresh();
  return { refresh, reload, get editing() { return editing; } };
})();
