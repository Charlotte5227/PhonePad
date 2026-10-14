// PhonePad PC — スマホの設定をPCで変える編集画面 (★ショートカット・キーボード画面・コントローラー)
// どの編集画面も「対象」{ kind: "android"|"web", items: {キー: 値}, save(changed) } を受け取って描く。
// 値の形はスマホとやりとりする形 (JSON の文字列。"" は最初の状態)。
"use strict";

const Ed = (() => {
  const el = (tag, attrs, ...kids) => {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (k === "class") e.className = v;
      else if (k === "text") e.textContent = v;
      else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
      else if (v !== undefined && v !== null && v !== false) e.setAttribute(k, v === true ? "" : v);
    }
    for (const c of kids.flat()) if (c != null) e.append(c);
    return e;
  };
  const parse = (s, def) => { if (!s) return def; try { return JSON.parse(s); } catch (e) { return def; } };
  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
  const r3 = (v) => Math.round(v * 1000) / 1000;
  const SVGNS = "http://www.w3.org/2000/svg";
  const svg = (tag, attrs) => { const e = document.createElementNS(SVGNS, tag); for (const [k, v] of Object.entries(attrs || {})) e.setAttribute(k, v); return e; };

  // 保存ボタンと「変更あり」の表示
  function saveBar(onSave, onDiscard) {
    const msg = el("span", { class: "savemsg" });
    const btn = el("button", { class: "primary", text: "保存してスマホに送る", disabled: true });
    const undo = el("button", { text: "変更を取り消す", disabled: true });
    const bar = el("div", { class: "savebar" }, msg, undo, btn);
    let dirty = false;
    const api = {
      el: bar,
      set dirty(v) { dirty = v; btn.disabled = !v; undo.disabled = !v; msg.textContent = v ? "まだ保存していない変更があります" : ""; },
      get dirty() { return dirty; },
      done(text) { this.dirty = false; msg.textContent = text; setTimeout(() => { if (!dirty) msg.textContent = ""; }, 4000); },
    };
    btn.onclick = async () => { btn.disabled = true; try { await onSave(); } catch (e) { alert("保存できませんでした: " + e.message); api.dirty = true; } };
    undo.onclick = () => onDiscard();
    return api;
  }

  // ================================================================ キーの名前
  const CTRL = 1, SHIFT = 2, ALT = 4, WIN = 8;
  const SPECIAL = {
    ENTER: "Enter", ESC: "Esc", TAB: "Tab", SPACE: "Space", BACK: "Backspace", DEL: "Delete", INS: "Insert",
    HOME: "Home", END: "End", PGUP: "PageUp", PGDN: "PageDown", UP: "↑", DOWN: "↓", LEFT: "←", RIGHT: "→",
    PRTSC: "PrintScreen", KANJI: "半角/全角", WIN: "Win", MENU: "アプリキー", VOLUP: "音量＋", VOLDOWN: "音量－",
    VOLMUTE: "消音", MPLAY: "再生/一時停止",
  };
  for (let i = 1; i <= 12; i++) SPECIAL["F" + i] = "F" + i;
  function keyName(k) {
    if (k && k[0] === "C" && k.length > 1 && !SPECIAL[k]) {
      try { return String.fromCodePoint(parseInt(k.slice(1), 16)).toUpperCase(); } catch (e) { return k; }
    }
    return SPECIAL[k] || k;
  }
  function describe(s) {
    if (s.r) return /^https?:/i.test(s.r) ? "サイトを開く: " + s.r : "アプリを開く: " + s.r.split(/[\\/]/).pop();
    const parts = [];
    if (s.m & CTRL) parts.push("Ctrl");
    if (s.m & SHIFT) parts.push("Shift");
    if (s.m & ALT) parts.push("Alt");
    if (s.m & WIN) parts.push("Win");
    if (s.k !== "WIN" || !parts.length) parts.push(keyName(s.k));
    return parts.join(" + ");
  }
  // キーボードで押したキー → スマホとやりとりするキーの名前
  function keyFromEvent(e) {
    const map = { Enter: "ENTER", Escape: "ESC", Tab: "TAB", " ": "SPACE", Backspace: "BACK", Delete: "DEL", Insert: "INS",
      Home: "HOME", End: "END", PageUp: "PGUP", PageDown: "PGDN", ArrowUp: "UP", ArrowDown: "DOWN", ArrowLeft: "LEFT",
      ArrowRight: "RIGHT", PrintScreen: "PRTSC", ContextMenu: "MENU", AudioVolumeUp: "VOLUP", AudioVolumeDown: "VOLDOWN",
      AudioVolumeMute: "VOLMUTE", MediaPlayPause: "MPLAY" };
    if (map[e.key]) return map[e.key];
    if (/^F([1-9]|1[0-2])$/.test(e.key)) return e.key;
    if (["Control", "Shift", "Alt", "Meta", "OS"].includes(e.key)) return null;
    // 文字キー: Shift などで変わった文字ではなく、キーそのもの (code) を使う
    const c = e.code || "";
    let ch = null;
    if (/^Key[A-Z]$/.test(c)) ch = c.slice(3).toLowerCase();
    else if (/^Digit[0-9]$/.test(c)) ch = c.slice(5);
    else if ([...e.key].length === 1) ch = e.key.toLowerCase();
    return ch ? "C" + ch.codePointAt(0).toString(16) : null;
  }
  const DEFAULT_SC = [
    ["コピー", "C63", CTRL], ["貼り付け", "C76", CTRL], ["切り取り", "C78", CTRL], ["元に戻す", "C7a", CTRL],
    ["やり直し", "C79", CTRL], ["全て選択", "C61", CTRL], ["保存", "C73", CTRL], ["検索", "C66", CTRL],
    ["ウィンドウ切替", "TAB", ALT], ["閉じる", "F4", ALT], ["エクスプローラー", "C65", WIN], ["デスクトップ", "C64", WIN],
    ["スクリーンショット", "C73", WIN | SHIFT], ["タスクマネージャー", "ESC", CTRL | SHIFT], ["新しいタブ", "C74", CTRL], ["タブを閉じる", "C77", CTRL],
  ].map(([l, k, m]) => ({ l, k, m, r: "" }));

  // PCのアプリ一覧から選ぶ
  let appsCache = null;
  async function pickApp() {
    if (!appsCache) {
      try { appsCache = await (await fetch("/api/apps")).json(); } catch (e) { appsCache = []; }
    }
    return new Promise((resolve) => {
      const q = el("input", { type: "search", placeholder: "名前で絞り込み" });
      const list = el("div", { class: "applist" });
      const close = (v) => { dlg.remove(); resolve(v); };
      const render = () => {
        list.textContent = "";
        const f = q.value.trim().toLowerCase();
        for (const a of appsCache.filter((a) => !f || a.n.toLowerCase().includes(f)).slice(0, 300)) {
          list.append(el("button", { text: a.n, onclick: () => close(a) }));
        }
        if (!list.children.length) list.append(el("p", { class: "note", text: "見つかりません" }));
      };
      q.oninput = render;
      const dlg = el("div", { class: "modal" }, el("div", { class: "box" },
        el("h2", { text: "PCのアプリから選ぶ" }), q, list,
        el("div", { class: "actions" }, el("button", { text: "やめる", onclick: () => close(null) }))));
      document.body.append(dlg);
      render();
      q.focus();
    });
  }

  // ================================================================ ★ショートカット
  function shortcuts(box, target) {
    let list, editing = -1;
    const reset = () => { list = parse(target.items.shortcuts, null) || DEFAULT_SC.map((s) => ({ ...s })); editing = -1; };
    reset();
    const bar = saveBar(async () => {
      const v = JSON.stringify(list);
      await target.save({ shortcuts: v });
      target.items.shortcuts = v;
      bar.done("保存しました");
    }, () => { reset(); bar.dirty = false; render(); });
    const grid = el("div", { class: "scgrid" });
    const form = el("div", { class: "scform" });
    const changed = () => { bar.dirty = true; render(); };

    function render() {
      grid.textContent = "";
      list.forEach((s, i) => {
        grid.append(el("button", { class: "sc" + (i === editing ? " on" : ""), onclick: () => { editing = i; render(); } },
          el("b", { text: s.l || "（名前なし）" }), el("small", { text: describe(s) })));
      });
      grid.append(el("button", { class: "sc add", onclick: () => { list.push({ l: "", k: "", m: 0, r: "" }); editing = list.length - 1; changed(); } },
        el("b", { text: "＋ 追加" })));
      renderForm();
    }

    function renderForm() {
      form.textContent = "";
      const s = list[editing];
      if (!s) { form.append(el("p", { class: "note", text: "変えたいボタンを押してください。並び順はスマホの★の一覧と同じです。" })); return; }
      const name = el("input", { type: "text", value: s.l, placeholder: "ボタンの名前（例: エクスプローラー）" });
      name.oninput = () => { s.l = name.value; bar.dirty = true; grid.children[editing].querySelector("b").textContent = s.l || "（名前なし）"; };
      const isRun = !!s.r;
      const tKey = el("button", { text: "キー操作", class: isRun ? "" : "on", onclick: () => { s.r = ""; changed(); } });
      const tRun = el("button", { text: "アプリ・サイトを開く", class: isRun ? "on" : "", onclick: () => { if (!s.r) s.r = "https://"; changed(); } });
      const body = el("div", {});
      if (isRun) {
        const run = el("input", { type: "text", value: s.r, placeholder: "https://… または アプリ・ファイルの場所" });
        run.oninput = () => { s.r = run.value; bar.dirty = true; };
        body.append(el("label", { class: "lab", text: "開くもの" }), run,
          el("div", { class: "actions" }, el("button", { text: "PCのアプリから選ぶ", onclick: async () => {
            const a = await pickApp();
            if (a) { s.r = a.p; if (!s.l) s.l = a.n; changed(); }
          } })));
      } else {
        const mods = el("div", { class: "chips" });
        for (const [lab, bit] of [["Ctrl", CTRL], ["Shift", SHIFT], ["Alt", ALT], ["Win", WIN]]) {
          mods.append(el("button", { text: lab, class: s.m & bit ? "on" : "", onclick: () => { s.m ^= bit; changed(); } }));
        }
        const cap = el("button", { class: "capture", text: s.k ? describe(s) : "ここを押してから、PCのキーボードでキーを押す" });
        cap.onclick = () => { cap.classList.add("listening"); cap.textContent = "キーを押してください…（Esc 以外の、送りたいキー）"; cap.focus(); };
        cap.onkeydown = (e) => {
          if (!cap.classList.contains("listening")) return;
          e.preventDefault();
          const k = keyFromEvent(e);
          if (!k) return;
          s.k = k;
          s.m = (e.ctrlKey ? CTRL : 0) | (e.shiftKey ? SHIFT : 0) | (e.altKey ? ALT : 0) | (e.metaKey ? WIN : 0) | (s.m & WIN);
          cap.classList.remove("listening");
          changed();
        };
        cap.onblur = () => { if (cap.classList.contains("listening")) { cap.classList.remove("listening"); cap.textContent = s.k ? describe(s) : "ここを押してから、PCのキーボードでキーを押す"; } };
        const special = el("select", {}, el("option", { value: "", text: "特別なキーから選ぶ…" }),
          ...Object.entries(SPECIAL).map(([k, n]) => el("option", { value: k, text: n })));
        special.onchange = () => { if (special.value) { s.k = special.value; changed(); } };
        body.append(el("label", { class: "lab", text: "一緒に押すキー" }), mods,
          el("label", { class: "lab", text: "キー" }), cap, special,
          el("p", { class: "note", text: "Win キーや Alt+Tab など、PCが先に反応してしまう組み合わせは、上のボタンで Win・Alt を選んでから、キーだけを押してください。" }));
      }
      const move = (d) => { const j = editing + d; if (j < 0 || j >= list.length) return; [list[editing], list[j]] = [list[j], list[editing]]; editing = j; changed(); };
      form.append(
        el("label", { class: "lab", text: "名前" }), name,
        el("div", { class: "chips", style: "margin-top:10px" }, tKey, tRun), body,
        el("div", { class: "actions" },
          el("button", { text: "← 前へ", onclick: () => move(-1) }), el("button", { text: "後ろへ →", onclick: () => move(1) }),
          el("button", { class: "danger", text: "このボタンを削除", onclick: () => { list.splice(editing, 1); editing = -1; changed(); } })),
      );
    }

    box.textContent = "";
    box.append(
      el("div", { class: "row" }, el("p", { class: "note", style: "margin:0", text: "スマホの ★ に出るボタンです。押したときにPCで行う操作を選びます。" }),
        el("button", { text: "最初の一覧に戻す", onclick: () => { if (confirm("★ショートカットを最初の一覧に戻しますか？")) { list = DEFAULT_SC.map((s) => ({ ...s })); editing = -1; changed(); } } })),
      el("div", { class: "scwrap" }, grid, form), bar.el);
    render();
    return bar;
  }

  // ================================================================ 機能 (≡ のメニュー・お気に入り)
  const FEATURES = [
    ["kb", "⌨", "キーボード・マウス"], ["pad", "🎮", "コントローラー"], ["screen", "🖥", "PCの画面"],
    ["shortcuts", "★", "ショートカット"], ["volume", "🔊", "音量・音"], ["clipboard", "📋", "クリップボード"],
    ["files", "📎", "ファイルをPCへ送る"], ["power", "⏻", "PCの電源"], ["pcinfo", "📊", "PCの状態"],
    ["kbedit", "✎", "キーボード画面の配置"], ["set_mouse", "🖱", "マウスの設定"], ["set_kb", "⌨", "キーボードの設定"],
    ["set_pad", "🎮", "コントローラーの設定"], ["set_screen", "🖥", "PCの画面の設定"], ["set_receive", "📥", "PCから受け取る"],
    ["set_other", "⚙", "その他の設定"], ["help", "？", "使い方"],
  ].map(([id, icon, label]) => ({ id, icon, label }));
  // ブラウザ版にない機能
  const features = (kind) => FEATURES.filter((f) => !(kind === "web" && f.id === "set_receive"))
    .map((f) => (kind === "web" && f.id === "help" ? { ...f, label: "使い方・このページについて" } : f));
  // お気に入り (キーボード画面に置く機能のボタン) を、3つずつ並べて置く位置
  const favRect = (n) => [r3(0.04 + (n % 3) * 0.31), r3(0.33 + (Math.floor(n / 3) % 6) * 0.075), 0.29, 0.06, 0];
  const favEntry = (f, n) => ({ id: "f_" + f.id, l: f.icon + " " + f.label, k: "", m: 0, r: "", f: f.id, rect: favRect(n) });

  // ≡ のメニューの並びとお気に入り
  function menu(box, target) {
    let order, custom;
    const list = features(target.kind);
    const reset = () => {
      const ids = parse(target.items.menuOrder, []) || [];
      order = ids.map((id) => list.find((f) => f.id === id)).filter(Boolean);
      for (const f of list) if (!order.includes(f)) order.push(f);
      custom = parse(target.items.kbCustom, []) || [];
    };
    reset();
    let customChanged = false, orderChanged = false;
    const bar = saveBar(async () => {
      const items = {};
      if (orderChanged) items.menuOrder = JSON.stringify(order.map((f) => f.id));
      if (customChanged) items.kbCustom = JSON.stringify(custom);
      await target.save(items);
      Object.assign(target.items, items);
      customChanged = orderChanged = false;
      bar.done("保存しました");
    }, () => { reset(); customChanged = orderChanged = false; bar.dirty = false; render(); });
    const ul = el("div", { class: "menulist" });
    function render() {
      ul.textContent = "";
      order.forEach((f, i) => {
        const fav = custom.some((c) => c.f === f.id);
        const mv = (d) => { const j = i + d; if (j < 0 || j >= order.length) return; [order[i], order[j]] = [order[j], order[i]]; orderChanged = true; bar.dirty = true; render(); };
        ul.append(el("div", { class: "menurow" },
          el("span", { class: "mi", text: f.icon }), el("span", { class: "ml", text: f.label }),
          f.id === "kb" ? el("span", { class: "favx" }) : el("button", { class: "fav" + (fav ? " on" : ""), title: fav ? "キーボード画面から外す" : "キーボード画面に置く", text: fav ? "★ 置いています" : "☆ 置く", onclick: () => {
            if (fav) custom = custom.filter((c) => c.f !== f.id);
            else custom.push(favEntry(f, custom.filter((c) => c.f).length));
            customChanged = true; bar.dirty = true; render();
          } }),
          el("button", { text: "↑", onclick: () => mv(-1) }), el("button", { text: "↓", onclick: () => mv(1) })));
      });
    }
    box.textContent = "";
    box.append(
      el("div", { class: "row" }, el("p", { class: "note", style: "margin:0", text: "スマホの ≡ のメニューに並ぶ順番と、キーボード画面に置く機能（お気に入り）です。置いたボタンの場所は「キーボード画面」で変えられます。" }),
        el("button", { text: "最初の並びに戻す", onclick: () => { order = list.slice(); orderChanged = true; bar.dirty = true; render(); } })),
      ul, bar.el);
    render();
    return bar;
  }

  // ================================================================ キーボード画面の配置
  const R = (x, y, w, h, off) => [x, y, w, h, off ? 1 : 0];
  const KEYS1 = [["esc", "Esc"], ["tab", "Tab"], ["ctrl", "Ctrl"], ["shift", "Shift"], ["alt", "Alt"], ["win", "Win"], ["fn", "Fn"]];
  const KEYS2 = [["left", "←"], ["up", "↑"], ["down", "↓"], ["right", "→"], ["bksp", "⌫"], ["del", "Del"], ["enter", "⏎"]];
  const KB_LABEL = { input: "入力欄", star: "★", mirror: "PCの入力欄", pad: "マウス", ohPad: "マウス（片手）", oneMode: "片手操作",
    mbL: "左", mbM: "長押し", mbR: "右", ohExit: "片手をやめる", ohUp: "▲ スクロール", ohDown: "▼ スクロール", ohRight: "右クリック",
    ohGrab: "つかむ", ohLeft: "左クリック" };
  for (const [id, l] of KEYS1.concat(KEYS2)) KB_LABEL[id] = l;
  function kbModes(kind) {
    return kind === "web"
      ? [["tp", "マウス"], ["oneR", "片手（右手）"], ["oneL", "片手（左手）"], ["tp.land", "マウス・横向き"], ["oneR.land", "片手（右）・横向き"], ["oneL.land", "片手（左）・横向き"]]
      : [["tp", "マウス"], ["stick", "スティック"], ["oneR", "片手（右手）"], ["oneL", "片手（左手）"]];
  }
  // スマホと同じ初期配置
  function kbDefaults(kind, mode, custom) {
    const land = mode.endsWith(".land"), m = mode.replace(".land", "");
    const web = kind === "web";
    const L = web
      ? { input: R(.02, .01, .80, .075), star: R(.84, .01, .14, .075), mirror: R(.02, .255, .96, .13, 1) }
      : { input: R(.02, .01, .96, .075), star: R(.84, .01, .14, .075, 1), mirror: R(.02, .255, .96, .13, 1) };
    KEYS1.forEach(([id], i) => { L[id] = R(r3(.02 + i * .137), .10, .127, .065); });
    KEYS2.forEach(([id], i) => { L[id] = R(r3(.02 + i * .137), .175, .127, .065); });
    if (m === "tp" || m === "stick") {
      if (web) Object.assign(L, { pad: R(.02, .255, .96, .60), oneMode: R(.74, .27, .22, .05), mbL: R(.02, .87, .31, .12), mbM: R(.345, .87, .31, .12), mbR: R(.67, .87, .31, .12) });
      else {
        const st = m === "stick";
        Object.assign(L, { pad: R(.02, .255, .96, .62), stickMode: R(.04, .27, .30, .05), oneMode: R(.66, .27, .30, .05),
          mbL: R(.02, .885, .30, .105, !st), mbM: st ? R(.335, .885, .33, .105) : R(.02, .885, .96, .105), mbR: R(.68, .885, .30, .105, !st) });
      }
    } else {
      const o = { ohPad: R(.02, .255, .66, .735), hand: R(.04, .27, web ? .24 : .26, web ? .05 : .055), ohExit: R(.70, .255, .28, .06),
        ohUp: R(.70, .325, .28, .12), ohDown: R(.70, .455, .28, .12), ohRight: R(.70, .585, .28, .11), ohGrab: R(.70, .705, .28, .11), ohLeft: R(.70, .825, .28, .165) };
      if (m === "oneL") for (const k in o) o[k][0] = r3(1 - o[k][0] - o[k][2]);
      Object.assign(L, o);
    }
    if (land) {
      for (const id in L) {
        const r = L[id];
        if (id === "mirror") { L[id] = R(.01, .56, .48, .4, r[4]); continue; }
        if (r[1] < 0.25) L[id] = [r3(.01 + r[0] * .48), r3(.02 + r[1] * 2), r3(r[2] * .48), r3(r[3] * 2), r[4]];
        else L[id] = [r3(.51 + r[0] * .48), r3(.02 + (r[1] - .255) / .745 * .96), r3(r[2] * .48), r3(r[3] / .745 * .96), r[4]];
      }
    }
    for (const c of custom) L[c.id] = (c.rect || R(.35, .45, .3, .07)).slice();
    return L;
  }
  // 名前の前の絵文字 (「🔊 音量・音」の「🔊」)。絵文字のないボタンは null
  function iconOf(c) {
    const l = c.l || "", i = l.indexOf(" ");
    if (i <= 0 || !l.slice(i + 1).trim()) return null;
    const t = l.slice(0, i);
    return c.f || /\p{So}|\p{Extended_Pictographic}/u.test(t) ? t : null;
  }
  function kbLabel(id, mode, custom) {
    if (id === "stickMode") return mode.startsWith("stick") ? "マウス操作" : "スティック操作";
    if (id === "hand") return mode.startsWith("oneL") ? "＞ 右手用" : "＜ 左手用";
    const c = custom.find((c) => c.id === id);
    if (c) return c.io && iconOf(c) ? iconOf(c) : c.l;
    return KB_LABEL[id] || id;
  }

  function keyboard(box, target) {
    const modes = kbModes(target.kind);
    let mode = modes[0][0], custom, layouts, sel = null;
    const reset = () => {
      custom = parse(target.items.kbCustom, []);
      layouts = {};
      sel = null;
    };
    reset();
    const lay = () => {
      if (!layouts[mode]) {
        const L = kbDefaults(target.kind, mode, custom);
        const saved = parse(target.items["kbLayout." + mode], {});
        for (const id in saved) if (L[id] && Array.isArray(saved[id]) && saved[id].length >= 4) L[id] = [saved[id][0], saved[id][1], saved[id][2], saved[id][3], saved[id][4] ? 1 : 0];
        layouts[mode] = L;
      }
      return layouts[mode];
    };
    let customChanged = false;
    const bar = saveBar(async () => {
      const items = {};
      for (const [m, L] of Object.entries(layouts)) items["kbLayout." + m] = JSON.stringify(L);
      if (customChanged) items.kbCustom = JSON.stringify(custom);
      await target.save(items);
      Object.assign(target.items, items);
      customChanged = false;
      bar.done("保存しました");
    }, () => { reset(); customChanged = false; bar.dirty = false; render(); });

    const tabs = el("div", { class: "chips" });
    const land = () => mode.endsWith(".land");
    const stage = el("div", { class: "kbstage" });
    const side = el("div", { class: "kbside" });

    function render() {
      tabs.textContent = "";
      for (const [m, n] of modes) tabs.append(el("button", { text: n, class: m === mode ? "on" : "", onclick: () => { mode = m; sel = null; render(); } }));
      stage.classList.toggle("land", land());
      stage.classList.toggle("web", target.kind === "web");
      drawStage();
      drawSide();
    }
    function partEl(id, r) {
      const p = el("div", { class: "kbpart" + (r[4] ? " off" : "") + (id === sel ? " sel" : "") + (id === "pad" || id === "ohPad" || id === "mirror" ? " big" : ""),
        style: `left:${r[0] * 100}%;top:${r[1] * 100}%;width:${r[2] * 100}%;height:${r[3] * 100}%` },
      el("span", { text: kbLabel(id, mode, custom) }));
      p.dataset.id = id;
      return p;
    }
    function drawStage() {
      stage.textContent = "";
      const L = lay();
      // 大きい部品を下に
      const ids = Object.keys(L).sort((a, b) => L[b][2] * L[b][3] - L[a][2] * L[a][3]);
      for (const id of ids) stage.append(partEl(id, L[id]));
      if (sel && L[sel]) {
        const r = L[sel];
        stage.append(el("div", { class: "kbhandle", style: `left:${(r[0] + r[2]) * 100}%;top:${(r[1] + r[3]) * 100}%` }));
      }
    }
    function drawSide() {
      side.textContent = "";
      const L = lay();
      const c = sel && custom.find((c) => c.id === sel);
      if (!sel || !L[sel]) {
        side.append(el("p", { class: "note", text: "部品を押して選び、ドラッグで動かします。右下の丸をドラッグすると大きさが変わります。" }));
      } else {
        side.append(el("h3", { text: c ? c.l : kbLabel(sel, mode, custom) }));
        const r = L[sel];
        if (c) {
          side.append(el("p", { class: "note", text: c.f ? "お気に入り（押すとこの機能が開きます）" : describe(c) }),
            el("div", { class: "actions" },
              // 絵文字のあるボタン: 絵文字だけにする・名前も出す
              iconOf(c) ? el("button", { text: c.io ? "名前も表示する" : "絵文字だけにする", onclick: () => {
                if (c.io) delete c.io; else c.io = 1;
                customChanged = true; bar.dirty = true; render();
              } }) : null,
              el("button", { class: "danger", text: "このキーを削除", onclick: () => {
              if (!confirm("「" + c.l + "」を削除しますか？（全部の画面から消えます）")) return;
              custom = custom.filter((x) => x !== c);
              for (const L2 of Object.values(layouts)) delete L2[c.id];
              customChanged = true; sel = null; bar.dirty = true; render();
            } })));
        } else if (sel === "input") {
          side.append(el("p", { class: "note", text: "入力欄は隠せません（動かす・大きさを変えることはできます）。" }));
        } else {
          side.append(el("div", { class: "actions" }, el("button", { text: r[4] ? "表示する" : "隠す", onclick: () => { r[4] = r[4] ? 0 : 1; bar.dirty = true; render(); } })));
        }
        side.append(el("p", { class: "note", text: `位置 ${Math.round(r[0] * 100)}%, ${Math.round(r[1] * 100)}%　大きさ ${Math.round(r[2] * 100)}% × ${Math.round(r[3] * 100)}%` }));
      }
      // 機能のボタン (お気に入り) を置く
      const fsel = el("select", {}, ...features(target.kind).filter((f) => f.id !== "kb" && !custom.some((c) => c.f === f.id))
        .map((f) => el("option", { value: f.id, text: f.icon + " " + f.label })));
      side.append(el("h3", { text: "機能のボタンを置く", style: "margin-top:18px" }),
        el("p", { class: "note", style: "margin:0 0 6px", text: "押すとその機能が開くボタンです（スマホの ≡ のメニューの ☆ と同じ）。" }),
        el("div", { class: "actions", style: "margin-top:0" }, fsel, el("button", { text: "置く", onclick: () => {
          const f = features(target.kind).find((x) => x.id === fsel.value);
          if (!f) return;
          const c = favEntry(f, custom.filter((x) => x.f).length);
          custom.push(c);
          for (const L2 of Object.values(layouts)) L2[c.id] = c.rect.slice();
          customChanged = true; sel = c.id; bar.dirty = true; render();
        } })));
      // キーを追加
      side.append(el("h3", { text: "キーを追加", style: "margin-top:18px" }));
      const name = el("input", { type: "text", placeholder: "ボタンの名前（例: 更新）" });
      let pending = null;
      const cap = el("button", { class: "capture", text: "ここを押してから、キーを押す" });
      cap.onclick = () => { cap.classList.add("listening"); cap.textContent = "キーを押してください…"; cap.focus(); };
      cap.onkeydown = (e) => {
        if (!cap.classList.contains("listening")) return;
        e.preventDefault();
        const k = keyFromEvent(e);
        if (!k) return;
        pending = { k, m: (e.ctrlKey ? CTRL : 0) | (e.shiftKey ? SHIFT : 0) | (e.altKey ? ALT : 0) | (e.metaKey ? WIN : 0) };
        cap.classList.remove("listening");
        cap.textContent = describe({ ...pending, r: "" });
      };
      const run = el("input", { type: "text", placeholder: "または開くアプリ・サイト（https://…）" });
      side.append(name, cap, run, el("div", { class: "actions" },
        el("button", { text: "アプリから選ぶ", onclick: async () => { const a = await pickApp(); if (a) { run.value = a.p; if (!name.value) name.value = a.n; } } }),
        el("button", { class: "primary", text: "追加", onclick: () => {
          const r = run.value.trim();
          if (!pending && !r) { alert("キーを押すか、開くアプリ・サイトを入れてください"); return; }
          const c = { id: "c" + Date.now().toString(36), l: (name.value.trim() || (r ? r.split(/[\\/]/).pop() : describe({ ...pending, r: "" }))).slice(0, 24),
            k: r ? "" : pending.k, m: r ? 0 : pending.m, r, rect: [0.35, 0.45, 0.3, 0.07, 0] };
          custom.push(c);
          for (const L2 of Object.values(layouts)) L2[c.id] = c.rect.slice();
          customChanged = true; sel = c.id; bar.dirty = true; render();
        } })));
      side.append(el("div", { class: "actions", style: "margin-top:18px" }, el("button", { text: "この画面を初期配置に戻す", onclick: () => {
        if (!confirm("「" + modes.find((x) => x[0] === mode)[1] + "」の配置を初期に戻しますか？")) return;
        layouts[mode] = kbDefaults(target.kind, mode, custom); sel = null; bar.dirty = true; render();
      } })));
    }

    // ドラッグで動かす・大きさを変える
    let drag = null;
    stage.addEventListener("pointerdown", (e) => {
      const b = stage.getBoundingClientRect();
      const x = (e.clientX - b.left) / b.width, y = (e.clientY - b.top) / b.height;
      const L = lay();
      if (e.target.classList.contains("kbhandle") && sel) drag = { kind: "size", id: sel, sx: x, sy: y, r: L[sel].slice() };
      else {
        let best = null, area = Infinity;
        for (const [id, r] of Object.entries(L)) if (x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3] && r[2] * r[3] < area) { best = id; area = r[2] * r[3]; }
        sel = best;
        drag = best ? { kind: "move", id: best, sx: x, sy: y, r: L[best].slice() } : null;
        render();
      }
      if (drag) stage.setPointerCapture(e.pointerId);
    });
    stage.addEventListener("pointermove", (e) => {
      if (!drag) return;
      const b = stage.getBoundingClientRect();
      const x = (e.clientX - b.left) / b.width, y = (e.clientY - b.top) / b.height;
      const r = lay()[drag.id], o = drag.r, snap = (v) => Math.round(v / 0.005) * 0.005;
      if (drag.kind === "move") { r[0] = r3(clamp(snap(o[0] + x - drag.sx), 0, 1 - o[2])); r[1] = r3(clamp(snap(o[1] + y - drag.sy), 0, 1 - o[3])); }
      else { r[2] = r3(clamp(snap(o[2] + x - drag.sx), 0.04, 1 - o[0])); r[3] = r3(clamp(snap(o[3] + y - drag.sy), 0.03, 1 - o[1])); }
      bar.dirty = true;
      drawStage();
    });
    const end = () => { if (drag) { drag = null; drawSide(); } };
    stage.addEventListener("pointerup", end);
    stage.addEventListener("pointercancel", end);

    box.textContent = "";
    box.append(tabs, el("div", { class: "kbwrap" }, el("div", { class: "phone" + (target.kind === "web" ? " webp" : "") }, stage), side), bar.el);
    render();
    return bar;
  }

  // ================================================================ コントローラーの配置
  const PAD_DEFAULTS = {
    ZL: [0.075, 0.095], L: [0.075, 0.315], ZR: [0.925, 0.095], R: [0.925, 0.315],
    LS: [0.29, 0.36], DP: [0.27, 0.78], FACE: [0.73, 0.32], RS: [0.71, 0.74],
    MINUS: [0.40, 0.17], PLUS: [0.60, 0.17], T: [0.445, 0.36], H: [0.555, 0.36],
    L3: [0.44, 0.60], R3: [0.56, 0.60], GEAR: [0.46, 0.06], EDIT: [0.54, 0.06], SWITCH: [0.50, 0.885],
  };
  const PAD_NAMES = { ZL: "ZL", L: "L", ZR: "ZR", R: "R", LS: "Lスティック", RS: "Rスティック", DP: "十字キー", FACE: "ABXY",
    MINUS: "−", PLUS: "+", T: "T（連打）", H: "H（ホーム）", L3: "L3", R3: "R3", GEAR: "≡ メニュー", EDIT: "✎ 配置", SWITCH: "⌨ 切替" };
  const SHAPES = ["標準", "丸", "角丸", "四角"];
  const AREA_KEYS = ["A", "B", "X", "Y", "L", "R", "ZL", "ZR", "+", "−", "H", "L3", "R3", "↑", "↓", "←", "→"];
  const PW = 760, PH = 352; // 横向きのスマホの画面 (このくらいの大きさで描く)

  function controller(box, target) {
    let specs, areas, sel = null, drawKey = null;
    const reset = () => {
      specs = {};
      for (const [id, d] of Object.entries(PAD_DEFAULTS)) specs[id] = [d[0], d[1], 1, 0, 0];
      const saved = parse(target.items.padLayout, {});
      for (const [id, v] of Object.entries(saved || {})) {
        if (specs[id] && Array.isArray(v) && v.length >= 3) specs[id] = [clamp(v[0], 0, 1), clamp(v[1], 0, 1), clamp(v[2], 0.4, 2.5), clamp(v[3] || 0, 0, 3) | 0, v[4] ? 1 : 0];
      }
      areas = (parse(target.items.padAreas, []) || []).filter((a) => a && AREA_KEYS.includes(a.k) && Array.isArray(a.p) && a.p.length >= 6);
      sel = null; drawKey = null;
    };
    reset();
    const bar = saveBar(async () => {
      const items = { padLayout: JSON.stringify(specs), padAreas: JSON.stringify(areas) };
      await target.save(items);
      Object.assign(target.items, items);
      bar.done("保存しました");
    }, () => { reset(); bar.dirty = false; render(); });

    const pic = svg("svg", { viewBox: `0 0 ${PW} ${PH}`, class: "padsvg" });
    const side = el("div", { class: "kbside" });
    const X = (u) => u * PW, Y = (v) => v * PH;

    function shapeEl(id, cx, cy, w, h, round, label, cls) {
      const s = specs[id] ? specs[id][3] | 0 : 0;
      let e;
      if (s === 1 || (s === 0 && round)) e = svg("ellipse", { cx, cy, rx: w / 2, ry: h / 2 });
      else e = svg("rect", { x: cx - w / 2, y: cy - h / 2, width: w, height: h, rx: s === 2 ? Math.min(w, h) * 0.28 : s === 3 ? 3 : 12 });
      e.setAttribute("class", "pb " + (cls || ""));
      const g = svg("g", {});
      g.append(e);
      if (label) { const t = svg("text", { x: cx, y: cy + 5, "text-anchor": "middle" }); t.textContent = label; g.append(t); }
      return g;
    }
    function partG(id) {
      const [u, v, s, , hidden] = specs[id];
      const cx = X(u), cy = Y(v);
      const g = svg("g", { class: "part" + (hidden ? " off" : "") + (id === sel ? " sel" : ""), "data-id": id });
      const add = (n) => g.append(n);
      const C = (r, lab) => add(shapeEl(id, cx, cy, r * 2, r * 2, true, lab));
      switch (id) {
        case "ZL": case "ZR": add(shapeEl(id, cx, cy, 0.15 * PW * s, 0.19 * PH * s, false, id)); break;
        case "L": case "R": add(shapeEl(id, cx, cy, 0.15 * PW * s, 0.21 * PH * s, false, id)); break;
        case "SWITCH": add(shapeEl(id, cx, cy, 0.20 * PW * s, 0.17 * PH * s, false, "⌨")); break;
        case "LS": case "RS": { const r = PH * 0.17 * s; add(svg("circle", { cx, cy, r, class: "pb stick" })); const t = svg("text", { x: cx, y: cy + 5, "text-anchor": "middle" }); t.textContent = id === "LS" ? "L" : "R"; add(t); break; }
        case "DP": { const o = PH * 0.115 * s, r = PH * 0.06 * s; for (const [dx, dy, l] of [[0, -1, "↑"], [0, 1, "↓"], [-1, 0, "←"], [1, 0, "→"]]) add(shapeEl(id, cx + dx * o, cy + dy * o, r * 2, r * 2, true, l)); break; }
        case "FACE": { const o = PH * 0.13 * s, r = PH * 0.066 * s; for (const [dx, dy, l] of [[0, -1, "Y"], [0, 1, "B"], [-1, 0, "X"], [1, 0, "A"]]) add(shapeEl(id, cx + dx * o, cy + dy * o, r * 2, r * 2, true, l)); break; }
        case "MINUS": C(PH * 0.05 * s, "−"); break;
        case "PLUS": C(PH * 0.05 * s, "+"); break;
        case "T": C(PH * 0.062 * s, "T"); break;
        case "H": C(PH * 0.062 * s, "H"); break;
        case "L3": C(PH * 0.055 * s, "L3"); break;
        case "R3": C(PH * 0.055 * s, "R3"); break;
        case "GEAR": C(PH * 0.045 * s, "≡"); break;
        case "EDIT": C(PH * 0.045 * s, "✎"); break;
      }
      return g;
    }
    function drawPic() {
      pic.textContent = "";
      pic.append(svg("rect", { x: 0, y: 0, width: PW, height: PH, rx: 18, class: "padbg" }));
      areas.forEach((a, i) => {
        const pts = [];
        for (let j = 0; j < a.p.length; j += 2) pts.push(X(a.p[j]) + "," + Y(a.p[j + 1]));
        pic.append(svg("polygon", { points: pts.join(" "), class: "area" + (sel === "AREA:" + i ? " sel" : ""), "data-id": "AREA:" + i }));
        let sx = 0, sy = 0;
        for (let j = 0; j < a.p.length; j += 2) { sx += a.p[j]; sy += a.p[j + 1]; }
        const t = svg("text", { x: X(sx / (a.p.length / 2)), y: Y(sy / (a.p.length / 2)) + 6, "text-anchor": "middle", class: "areatext" });
        t.textContent = a.k;
        pic.append(t);
      });
      for (const id of Object.keys(PAD_DEFAULTS)) pic.append(partG(id));
      if (drawPts) pic.append(svg("polyline", { points: drawPts.map((p) => X(p[0]) + "," + Y(p[1])).join(" "), class: "drawing" }));
    }
    function drawSide() {
      side.textContent = "";
      if (drawKey) {
        side.append(el("h3", { text: "エリアを描く: " + drawKey }), el("p", { class: "note", text: "左の画面の上を、マウスでなぞって囲んでください。指を離すと（ボタンを離すと）できあがります。" }),
          el("div", { class: "actions" }, el("button", { text: "やめる", onclick: () => { drawKey = null; render(); } })));
        return;
      }
      if (sel && sel.startsWith("AREA:")) {
        const i = +sel.slice(5);
        side.append(el("h3", { text: "エリア: " + areas[i].k }), el("div", { class: "actions" },
          el("button", { class: "danger", text: "このエリアを消す", onclick: () => { areas.splice(i, 1); sel = null; bar.dirty = true; render(); } })));
      } else if (sel) {
        const s = specs[sel];
        side.append(el("h3", { text: PAD_NAMES[sel] }),
          el("div", { class: "actions" },
            el("button", { text: "小さく", onclick: () => { s[2] = Math.round(clamp(s[2] - 0.1, 0.4, 2.5) * 10) / 10; bar.dirty = true; render(); } }),
            el("button", { text: "大きく", onclick: () => { s[2] = Math.round(clamp(s[2] + 0.1, 0.4, 2.5) * 10) / 10; bar.dirty = true; render(); } }),
            el("button", { text: "形: " + SHAPES[s[3] | 0], onclick: () => { s[3] = ((s[3] | 0) + 1) % 4; bar.dirty = true; render(); } }),
            el("button", { text: s[4] ? "表示する" : "隠す", onclick: () => {
              if (!s[4] && (sel === "EDIT" || sel === "SWITCH" || sel === "GEAR")) { alert("≡・✎・⌨ は隠せません（スマホでメニューを開いたり、配置を変えたり、キーボードに戻ったりできなくなるため）"); return; }
              s[4] = s[4] ? 0 : 1; bar.dirty = true; render();
            } })),
          el("p", { class: "note", text: `大きさ ${Math.round(s[2] * 100)}%（マウスのホイールでも変えられます）` }));
      } else {
        side.append(el("p", { class: "note", text: "部品を押して選び、ドラッグで動かします。" }));
      }
      const keySel = el("select", {}, ...AREA_KEYS.map((k) => el("option", { value: k, text: k })));
      side.append(el("h3", { text: "自由な形のエリア", style: "margin-top:18px" }),
        el("p", { class: "note", text: "なぞって囲んだ場所を、好きなボタンにできます（例: 右半分を全部 A）。" }),
        el("div", { class: "actions" }, keySel, el("button", { text: "エリアを描く", onclick: () => { drawKey = keySel.value; sel = null; render(); } })),
        el("div", { class: "actions", style: "margin-top:18px" }, el("button", { text: "初期配置に戻す", onclick: () => {
          if (!confirm("コントローラーの配置を初期に戻しますか？（エリアも消えます）")) return;
          for (const [id, d] of Object.entries(PAD_DEFAULTS)) specs[id] = [d[0], d[1], 1, 0, 0];
          areas = []; sel = null; bar.dirty = true; render();
        } })));
    }
    function render() { drawPic(); drawSide(); }

    let drag = null, drawPts = null;
    const at = (e) => { const b = pic.getBoundingClientRect(); return [clamp((e.clientX - b.left) / b.width, 0, 1), clamp((e.clientY - b.top) / b.height, 0, 1)]; };
    pic.addEventListener("pointerdown", (e) => {
      const [u, v] = at(e);
      pic.setPointerCapture(e.pointerId);
      if (drawKey) { drawPts = [[u, v]]; return; }
      const g = e.target.closest("[data-id]");
      sel = g ? g.dataset.id : null;
      if (sel && !sel.startsWith("AREA:")) drag = { id: sel, du: specs[sel][0] - u, dv: specs[sel][1] - v };
      render();
    });
    pic.addEventListener("pointermove", (e) => {
      const [u, v] = at(e);
      if (drawPts) {
        const l = drawPts[drawPts.length - 1];
        if (Math.hypot((u - l[0]) * PW, (v - l[1]) * PH) > 8) { drawPts.push([u, v]); drawPic(); }
        return;
      }
      if (!drag) return;
      const s = specs[drag.id];
      s[0] = r3(clamp(u + drag.du, 0, 1)); s[1] = r3(clamp(v + drag.dv, 0, 1));
      bar.dirty = true;
      drawPic();
    });
    const up = () => {
      if (drawPts) {
        if (drawPts.length >= 3) { areas.push({ k: drawKey, p: drawPts.flat().map(r3) }); bar.dirty = true; }
        drawPts = null; drawKey = null; render();
        return;
      }
      drag = null;
    };
    pic.addEventListener("pointerup", up);
    pic.addEventListener("pointercancel", up);
    pic.addEventListener("wheel", (e) => {
      if (!sel || sel.startsWith("AREA:")) return;
      e.preventDefault();
      const s = specs[sel];
      s[2] = Math.round(clamp(s[2] + (e.deltaY < 0 ? 0.1 : -0.1), 0.4, 2.5) * 10) / 10;
      bar.dirty = true; render();
    }, { passive: false });

    box.textContent = "";
    box.append(el("div", { class: "padwrap" }, el("div", { class: "phone land" }, pic), side), bar.el);
    render();
    return bar;
  }

  return { shortcuts, keyboard, controller, menu, features, el, parse, pickApp, describe };
})();
