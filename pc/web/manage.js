// PhonePad PC — 「スマホの設定」「アプリごとの配置」「バックアップ」の画面
"use strict";

const Manage = (() => {
  const { el, parse } = Ed;
  const $ = (id) => document.getElementById(id);
  const KIND = { android: "アプリ", web: "ブラウザ版" };
  let phones = [];
  let curPhone = null, curTab = "menu", curBar = null;

  async function getJSON(u) { const r = await fetch(u, { cache: "no-store" }); if (!r.ok) throw new Error(await r.text()); return r.json(); }
  async function postJSON(u, v) {
    const r = await fetch(u, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(v) });
    if (!r.ok) throw new Error((await r.text()) || r.statusText);
    const t = await r.text();
    return t ? JSON.parse(t) : null;
  }
  // 保存していない変更を捨ててよいか
  function leaveOK() { return !curBar || !curBar.dirty || confirm("保存していない変更があります。捨てて移りますか？"); }
  const nameOf = (p) => p.alias || p.name || "スマホ";
  const ago = (t) => {
    const d = (Date.now() - new Date(t).getTime()) / 1000;
    if (d < 90) return "さっき";
    if (d < 3600) return Math.round(d / 60) + "分前";
    if (d < 86400) return Math.round(d / 3600) + "時間前";
    return Math.round(d / 86400) + "日前";
  };

  // ================================================================ スマホの設定
  async function loadPhones() {
    try { phones = await getJSON("/api/phones"); } catch (e) { return; }
  }
  async function showPhones() {
    await loadPhones();
    if (!curPhone || !phones.find((p) => p.id === curPhone)) curPhone = phones.length ? phones[0].id : null;
    renderPhoneList();
    renderPhone();
  }
  function renderPhoneList() {
    const box = $("phoneList");
    box.textContent = "";
    $("phonesEmpty").hidden = phones.length > 0;
    for (const p of phones) {
      box.append(el("button", { class: "phonechip" + (p.id === curPhone ? " on" : ""), onclick: () => {
        if (p.id === curPhone || !leaveOK()) return;
        curPhone = p.id; renderPhoneList(); renderPhone();
      } },
      el("span", { class: "dot" + (p.connected ? " live" : "") }),
      el("span", {}, el("b", { text: nameOf(p) }), el("small", { text: (KIND[p.kind] || "") + "・" + (p.connected ? "接続中" : ago(p.lastSeen)) }))));
    }
  }
  function renderPhone() {
    const box = $("phoneBody");
    box.textContent = "";
    curBar = null;
    const p = phones.find((x) => x.id === curPhone);
    if (!p) return;
    const pend = Object.keys(p.pending || {}).length;
    const note = p.connected
      ? "接続中です。保存すると、すぐスマホに反映されます。"
      : "いまはつながっていません。保存した内容は、次にこのスマホがつながったときに届きます。" + (pend ? "（まだ届けていない変更があります）" : "");
    const tabs = el("div", { class: "tabs2" });
    const body = el("div", { class: "edbody" });
    const TABS = [["menu", "≡ メニュー・お気に入り"], ["sc", "★ ショートカット"], ["kb", "キーボード画面"], ["pad", "コントローラー"], ["more", "その他"]];
    for (const [k, n] of TABS) {
      tabs.append(el("button", { text: n, class: k === curTab ? "on" : "", onclick: () => { if (k === curTab || !leaveOK()) return; curTab = k; renderPhone(); } }));
    }
    box.append(el("p", { class: "note", style: "margin-top:0", text: note }), tabs, body);
    const target = {
      kind: p.kind, items: p.items,
      async save(items) {
        await postJSON("/api/phones/set", { id: p.id, items });
        Object.assign(p.items, items);
      },
    };
    if (curTab === "menu") curBar = Ed.menu(body, target);
    else if (curTab === "sc") curBar = Ed.shortcuts(body, target);
    else if (curTab === "kb") curBar = Ed.keyboard(body, target);
    else if (curTab === "pad") curBar = Ed.controller(body, target);
    else {
      const others = phones.filter((x) => x.id !== p.id);
      const to = el("select", {}, ...others.map((x) => el("option", { value: x.id, text: nameOf(x) + "（" + (KIND[x.kind] || "") + "）" })));
      const parts = [["sc", "★ショートカット"], ["kb", "キーボード画面の配置"], ["pad", "コントローラーの配置"]].map(([v, n]) =>
        el("label", { class: "check inline" }, el("input", { type: "checkbox", value: v, checked: true }), el("span", { text: n })));
      body.append(
        el("section", { class: "sub" }, el("h3", { text: "このスマホの設定を、別のスマホへ写す" }),
          others.length
            ? el("div", {}, el("div", { class: "actions" }, el("span", { text: "写す先:" }), to), el("div", { class: "actions" }, parts),
              el("div", { class: "actions" }, el("button", { class: "primary", text: "写す", onclick: async () => {
                const sel = parts.map((l) => l.querySelector("input")).filter((i) => i.checked).map((i) => i.value);
                if (!sel.length) return;
                if (!confirm("「" + to.selectedOptions[0].text + "」の設定を、このスマホの設定で上書きしますか？")) return;
                try { await postJSON("/api/phones/copy", { from: p.id, to: to.value, parts: sel }); alert("写しました。つながっていればすぐ、いなければ次につないだときに届きます。"); } catch (e) { alert(e.message); }
                showPhones();
              } })))
            : el("p", { class: "note", text: "ほかのスマホがまだありません。機種変更したときは、新しいスマホを一度つないでから、ここで写せます。" })),
        el("section", { class: "sub" }, el("h3", { text: "名前を変える" }),
          el("p", { class: "note", text: "同じ機種が並んで分かりにくいときに、PCの画面での呼び名を付けられます（スマホの名前は変わりません）。" }),
          el("div", { class: "actions" }, el("button", { text: "名前を変える", onclick: async () => {
            const n = prompt("このスマホの呼び名（空にすると元の名前）", p.alias || p.name);
            if (n === null) return;
            await fetch("/api/phones/rename?id=" + encodeURIComponent(p.id) + "&name=" + encodeURIComponent(n), { method: "POST" });
            showPhones();
          } }))),
        el("section", { class: "sub" }, el("h3", { text: "このスマホを一覧から消す" }),
          el("p", { class: "note", text: "使わなくなったスマホを消します。次にこのスマホがつながると、また一覧に出ます（そのときはスマホの設定がそのまま使われます）。" }),
          el("div", { class: "actions" }, el("button", { class: "danger", text: "一覧から消す", onclick: async () => {
            if (!confirm("「" + nameOf(p) + "」を一覧から消しますか？")) return;
            await fetch("/api/phones/forget?id=" + encodeURIComponent(p.id), { method: "POST" });
            curPhone = null; showPhones();
          } }))));
    }
  }

  // ================================================================ アプリごとの配置
  let prof = null, profEditing = null, profTab = "pad";
  const PARTS = [["pad", "コントローラーの配置"], ["kb", "キーボード画面の配置"], ["sc", "★ショートカット"]];
  const partOf = (k) => (k === "shortcuts" ? "sc" : k === "kbCustom" || k.startsWith("kbLayout.") ? "kb" : k.startsWith("pad") ? "pad" : "");

  async function showProfiles() {
    try { prof = await getJSON("/api/profiles"); } catch (e) { return; }
    await loadPhones();
    renderProfiles();
  }
  async function saveProfiles() {
    await postJSON("/api/profiles", { autoOff: prof.autoOff, profiles: prof.profiles });
  }
  // 前に出ているアプリの表示だけ、定期的に更新
  async function refreshLive() {
    if ($("p-profiles").hidden || !prof) return;
    try {
      const s = await getJSON("/api/profiles");
      prof.fgExe = s.fgExe; prof.fgTitle = s.fgTitle; prof.active = s.active; prof.recent = s.recent;
      renderLive();
    } catch (e) {}
  }
  function renderLive() {
    const a = prof.profiles.find((p) => p.id === prof.active);
    $("profLive").textContent = prof.supported === false ? "（このPCでは前に出ているアプリを調べられません）"
      : "いま前に出ているアプリ: " + (prof.fgExe || "なし") + (prof.fgTitle && prof.fgTitle !== prof.fgExe ? "（" + prof.fgTitle + "）" : "")
      + "　→　" + (a ? "「" + a.name + "」の配置を使っています" : "いつもの配置");
  }
  function renderProfiles() {
    const box = $("profList");
    box.textContent = "";
    $("autoSwitch").checked = !prof.autoOff;
    renderLive();
    if (profEditing) { renderProfileEditor(); return; }
    if (!prof.profiles.length) box.append(el("p", { class: "note", text: "まだありません。下の「新しく作る」から作ってください。" }));
    for (const p of prof.profiles) {
      const parts = [...new Set(Object.keys(p.items || {}).map(partOf))].filter(Boolean).map((k) => PARTS.find((x) => x[0] === k)[1]);
      const apps = el("div", { class: "chips" });
      for (const a of p.apps) apps.append(el("span", { class: "appchip" }, a, el("button", { class: "x", title: "外す", text: "×", onclick: async () => {
        p.apps = p.apps.filter((x) => x !== a); await saveProfiles(); renderProfiles();
      } })));
      const addSel = el("select", {}, el("option", { value: "", text: "＋ アプリを足す…" }),
        ...(prof.recent || []).filter((r) => !p.apps.includes(r.exe)).map((r) => el("option", { value: r.exe, text: r.exe + (r.title ? "（" + r.title.slice(0, 30) + "）" : "") })),
        el("option", { value: "*", text: "名前を入れる…" }));
      addSel.onchange = async () => {
        let v = addSel.value;
        if (v === "*") v = (prompt("アプリの実行ファイル名（例: game.exe）") || "").trim().toLowerCase();
        if (!v) { addSel.value = ""; return; }
        if (!v.includes(".")) v += ".exe";
        if (!p.apps.includes(v)) p.apps.push(v);
        await saveProfiles(); renderProfiles();
      };
      const on = el("input", { type: "checkbox", checked: !p.off });
      on.onchange = async () => { p.off = !on.checked; await saveProfiles(); showProfiles(); };
      box.append(el("section", { class: "card prof" + (p.id === prof.active ? " active" : "") },
        el("div", { class: "row" }, el("h2", { style: "margin:0", text: p.name + (p.id === prof.active ? "　（使用中）" : "") }),
          el("label", { class: "check inline" }, on, el("span", { text: "使う" }))),
        el("p", { class: "note", text: "含むもの: " + (parts.join("、") || "なし") }),
        el("div", { class: "lab" }, "このアプリが前に出ているときに切り替えます"), apps, addSel,
        el("div", { class: "actions" },
          el("button", { class: "primary", text: "配置を変える", onclick: () => { profEditing = p.id; profTab = parts.length ? Object.keys(p.items).map(partOf).find(Boolean) || "pad" : "pad"; renderProfiles(); } }),
          el("button", { text: "名前を変える", onclick: async () => { const n = prompt("名前", p.name); if (n && n.trim()) { p.name = n.trim(); await saveProfiles(); renderProfiles(); } } }),
          el("button", { class: "danger", text: "削除", onclick: async () => {
            if (!confirm("「" + p.name + "」を削除しますか？")) return;
            prof.profiles = prof.profiles.filter((x) => x !== p); await saveProfiles(); renderProfiles();
          } }))));
    }
    // 新しく作る
    const name = el("input", { type: "text", placeholder: "名前（例: 原神、動画編集）" });
    const src = el("select", {}, ...phones.map((x) => el("option", { value: x.id, text: nameOf(x) + "（" + (KIND[x.kind] || "") + "）の今の設定から" })),
      el("option", { value: "", text: "最初の配置から" }));
    const checks = PARTS.map(([v, n]) => el("label", { class: "check inline" }, el("input", { type: "checkbox", value: v, checked: v !== "sc" }), el("span", { text: n })));
    const app = el("select", {}, el("option", { value: "", text: "切り替えるアプリ（あとで足すこともできます）" }),
      ...(prof.recent || []).map((r) => el("option", { value: r.exe, text: r.exe + (r.title ? "（" + r.title.slice(0, 30) + "）" : "") })));
    box.append(el("section", { class: "card" }, el("h2", { text: "新しく作る" }),
      el("p", { class: "note", style: "margin-top:0", text: "アプリの一覧には、最近PCで前に出したアプリが出ます。切り替えたいゲームやアプリを一度開いてから選んでください。" }),
      name, el("div", { class: "actions" }, src), el("div", { class: "actions" }, checks), el("div", { class: "actions" }, app),
      el("div", { class: "actions" }, el("button", { class: "primary", text: "作る", onclick: async () => {
        const n = name.value.trim();
        if (!n) { alert("名前を入れてください"); return; }
        const want = checks.map((l) => l.querySelector("input")).filter((i) => i.checked).map((i) => i.value);
        if (!want.length) { alert("含めるものを選んでください"); return; }
        const ph = phones.find((x) => x.id === src.value);
        const items = {};
        if (ph) for (const [k, v] of Object.entries(ph.items)) if (want.includes(partOf(k))) items[k] = v;
        if (want.includes("pad")) { items.padLayout = items.padLayout || ""; items.padAreas = items.padAreas || ""; }
        if (want.includes("kb")) { items.kbCustom = items.kbCustom || ""; items["kbLayout.tp"] = items["kbLayout.tp"] || ""; }
        if (want.includes("sc")) items.shortcuts = items.shortcuts || "";
        delete items.padPresets;
        prof.profiles.push({ id: "p" + Date.now().toString(36), name: n, apps: app.value ? [app.value] : [], items, kind: ph ? ph.kind : "android" });
        await saveProfiles(); showProfiles();
      } }))));
  }
  function renderProfileEditor() {
    const box = $("profList");
    const p = prof.profiles.find((x) => x.id === profEditing);
    if (!p) { profEditing = null; renderProfiles(); return; }
    const has = new Set(Object.keys(p.items).map(partOf));
    const tabs = el("div", { class: "tabs2" });
    for (const [k, n] of PARTS) {
      if (!has.has(k)) continue;
      tabs.append(el("button", { text: n, class: k === profTab ? "on" : "", onclick: () => { if (k === profTab || !leaveOK()) return; profTab = k; renderProfiles(); } }));
    }
    if (!has.has(profTab)) profTab = [...has][0];
    const body = el("div", { class: "edbody" });
    box.append(el("section", { class: "card" },
      el("div", { class: "row" }, el("h2", { style: "margin:0", text: "「" + p.name + "」の配置" }),
        el("button", { text: "← 一覧に戻る", onclick: () => { if (!leaveOK()) return; profEditing = null; curBar = null; renderProfiles(); } })),
      el("p", { class: "note", text: "保存すると、このアプリを使っている間のスマホにすぐ反映されます。スマホの側で変えた場合も、ここに反映されます。" }),
      tabs, body));
    const target = {
      kind: p.kind || "android", items: p.items,
      async save(items) { Object.assign(p.items, items); await saveProfiles(); },
    };
    curBar = profTab === "sc" ? Ed.shortcuts(body, target) : profTab === "kb" ? Ed.keyboard(body, target) : Ed.controller(body, target);
  }

  // ================================================================ バックアップ
  async function showBackup() {
    await loadPhones();
    const from = $("cpFrom"), to = $("cpTo");
    for (const s of [from, to]) {
      s.textContent = "";
      for (const p of phones) s.append(el("option", { value: p.id, text: nameOf(p) + "（" + (KIND[p.kind] || "") + "・" + (p.connected ? "接続中" : ago(p.lastSeen)) + "）" }));
    }
    if (phones.length > 1) to.selectedIndex = 1;
    $("cpBox").hidden = phones.length < 2;
    $("cpNone").hidden = phones.length >= 2;
  }
  function init() {
    $("autoSwitch").onchange = async () => { prof.autoOff = !$("autoSwitch").checked; await saveProfiles(); showProfiles(); };
    $("restoreFile").onchange = async () => {
      const f = $("restoreFile").files[0];
      if (!f) return;
      let data;
      try { data = JSON.parse(await f.text()); } catch (e) { alert("PhonePad のバックアップではないようです"); return; }
      if (!confirm("バックアップから戻しますか？\nスマホの配置・ショートカットと、アプリごとの配置が、バックアップの内容になります。")) { $("restoreFile").value = ""; return; }
      try {
        const r = await postJSON("/api/restore", data);
        $("restoreMsg").textContent = "戻しました（スマホ " + r.phones + " 台、アプリごとの配置 " + r.profiles + " 件）。つながっているスマホにはすぐ、ほかのスマホには次につないだときに届きます。";
      } catch (e) { alert(e.message); }
      $("restoreFile").value = "";
      showBackup();
    };
    $("cpGo").onclick = async () => {
      const from = $("cpFrom").value, to = $("cpTo").value;
      if (from === to) { alert("写す元と写す先が同じです"); return; }
      const parts = [...document.querySelectorAll("#cpParts input:checked")].map((i) => i.value);
      if (!parts.length) return;
      if (!confirm("「" + $("cpTo").selectedOptions[0].text + "」の設定を上書きしますか？")) return;
      try { await postJSON("/api/phones/copy", { from, to, parts }); $("cpMsg").textContent = "写しました。"; } catch (e) { alert(e.message); }
    };
    setInterval(refreshLive, 1500);
    window.addEventListener("beforeunload", (e) => { if (curBar && curBar.dirty) { e.preventDefault(); e.returnValue = ""; } });
  }

  return { showPhones, showProfiles, showBackup, init, leaveOK, get dirty() { return !!(curBar && curBar.dirty); }, clear() { curBar = null; } };
})();
