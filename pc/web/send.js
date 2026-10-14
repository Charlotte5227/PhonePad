// PhonePad PC — 「スマホに送る」の画面 (ファイル・スマホで開く・お知らせ・スマホを探す)
"use strict";

const Send = (() => {
  const { el } = Ed;
  const $ = (id) => document.getElementById(id);
  let devices = [], chosen = new Set(), lastKey = "";

  async function postJSON(u, v) {
    const r = await fetch(u, { method: "POST", headers: { "Content-Type": "application/json" }, body: v == null ? "" : JSON.stringify(v) });
    const t = await r.text();
    if (!r.ok) throw new Error(t || r.statusText);
    return t ? JSON.parse(t) : null;
  }
  const toParam = () => [...chosen].join(",");
  function need() {
    if (!devices.length) { alert("つながっているスマホがありません"); return false; }
    if (!chosen.size) { alert("送り先のスマホを選んでください"); return false; }
    return true;
  }
  function flash(id, text) {
    const e = $(id);
    e.textContent = text;
    clearTimeout(e._t);
    e._t = setTimeout(() => { e.textContent = ""; }, 5000);
  }

  // 接続中のスマホ (状態の更新のたびに呼ばれる)
  function setDevices(list) {
    const key = JSON.stringify(list.map((d) => [d.id, d.name]));
    if (key === lastKey) return;
    const before = new Set(devices.map((d) => d.id));
    devices = list;
    lastKey = key;
    // 新しくつながったスマホは選んでおく
    for (const d of list) if (!before.has(d.id)) chosen.add(d.id);
    for (const id of [...chosen]) if (!list.find((d) => d.id === id)) chosen.delete(id);
    render();
  }
  function render() {
    const box = $("sendTo");
    box.textContent = "";
    $("sendNone").hidden = devices.length > 0;
    for (const d of devices) {
      box.append(el("button", { class: chosen.has(d.id) ? "on" : "", text: (chosen.has(d.id) ? "✓ " : "") + d.name, onclick: () => {
        if (chosen.has(d.id)) chosen.delete(d.id); else chosen.add(d.id);
        render();
      } }));
    }
  }

  // ---- ファイル ----
  function upload(file, dev) {
    return new Promise((resolve) => {
      const row = el("div", { class: "fileRow" });
      const name = el("span", { class: "fn", text: file.name + " → " + dev.name });
      const bar = el("progress", { max: 100, value: 0 });
      const st = el("span", { class: "fs", text: "送っています…" });
      row.append(name, bar, st);
      $("fileList").prepend(row);
      const x = new XMLHttpRequest();
      x.open("POST", "/api/send/file?to=" + dev.id + "&name=" + encodeURIComponent(file.name));
      x.upload.onprogress = (e) => { if (e.lengthComputable) bar.value = Math.round((e.loaded / e.total) * 100); };
      x.onload = () => {
        if (x.status < 300) { bar.value = 100; st.textContent = "送りました"; st.className = "fs ok"; }
        else { st.textContent = x.responseText || "送れませんでした"; st.className = "fs warn"; }
        resolve();
      };
      x.onerror = () => { st.textContent = "送れませんでした"; st.className = "fs warn"; resolve(); };
      x.send(file);
    });
  }
  async function sendFiles(files) {
    if (!files.length || !need()) return;
    for (const f of files) for (const id of chosen) {
      const d = devices.find((x) => x.id === id);
      if (d) await upload(f, d);
    }
  }

  // ---- お知らせのタイマー ----
  async function loadTimers() {
    let list = [];
    try { list = await (await fetch("/api/send/timers", { cache: "no-store" })).json(); } catch (e) {}
    const box = $("timerList");
    box.textContent = "";
    for (const t of list) {
      const at = new Date(t.at);
      box.append(el("div", { class: "fileRow" },
        el("span", { class: "fn", text: String(at.getHours()).padStart(2, "0") + ":" + String(at.getMinutes()).padStart(2, "0") + " に「" + (t.text || t.title) + "」" }),
        el("button", { text: "取り消す", onclick: async () => { await fetch("/api/send/timers?cancel=" + t.id, { method: "POST" }); loadTimers(); } })));
    }
  }

  async function show() {
    loadTimers();
    try {
      const s = await (await fetch("/api/send/settings", { cache: "no-store" })).json();
      $("recvDir").textContent = s.recvDir;
      $("notifyDl").checked = !!s.notifyDownloads;
      $("notifyDl").disabled = !s.downloads;
    } catch (e) {}
  }

  function init() {
    const drop = $("drop");
    drop.addEventListener("dragover", (e) => { e.preventDefault(); drop.classList.add("over"); });
    drop.addEventListener("dragleave", () => drop.classList.remove("over"));
    drop.addEventListener("drop", (e) => { e.preventDefault(); drop.classList.remove("over"); sendFiles([...e.dataTransfer.files]); });
    $("pickFile").onclick = () => $("fileIn").click();
    $("fileIn").onchange = () => { sendFiles([...$("fileIn").files]); $("fileIn").value = ""; };
    // ページのどこに落としても、別のタブで開いてしまわないように
    window.addEventListener("dragover", (e) => e.preventDefault());
    window.addEventListener("drop", (e) => e.preventDefault());

    $("openGo").onclick = async () => {
      const u = $("openUrl").value.trim();
      if (!u || !need()) return;
      try { const r = await postJSON("/api/send/open?to=" + toParam(), { url: u }); flash("openMsg", r.sent + " 台のスマホに送りました"); } catch (e) { alert(e.message); }
    };
    $("openUrl").onkeydown = (e) => { if (e.key === "Enter") $("openGo").click(); };

    $("ntGo").onclick = async () => {
      const text = $("ntText").value.trim(), title = $("ntTitle").value.trim();
      if (!text && !title) { alert("お知らせの文を入れてください"); return; }
      const delay = parseInt($("ntDelay").value, 10) || 0;
      if (!delay && !need()) return;
      try {
        const r = await postJSON("/api/send/notify?to=" + (delay ? "" : toParam()), { title, text, delay: delay * 60 });
        flash("ntMsg", delay ? delay + " 分後に、つながっているスマホへ送ります" : r.sent + " 台のスマホに送りました");
        $("ntText").value = "";
        loadTimers();
      } catch (e) { alert(e.message); }
    };
    $("notifyDl").onchange = () => postJSON("/api/send/settings", { notifyDownloads: $("notifyDl").checked });

    $("ringOn").onclick = async () => { if (!need()) return; const r = await postJSON("/api/send/ring?on=1&to=" + toParam()); flash("ringMsg", r.sent + " 台のスマホを鳴らしています"); };
    $("ringOff").onclick = async () => { await postJSON("/api/send/ring?on=0"); flash("ringMsg", "止めました"); };
    $("openRecv").onclick = () => fetch("/api/send/open-folder", { method: "POST" });
  }

  return { setDevices, show, init };
})();
