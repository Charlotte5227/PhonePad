// PhonePad ブラウザ版 — PCの画面の表示と操作
"use strict";

(function () {
  const cv = document.getElementById("scrCanvas");
  const g = cv.getContext("2d");
  const stat = document.getElementById("scrStat");
  let W = 0, H = 0, dpr = 1;
  let img = null; // 今表示している画像 (ImageBitmap か Image)
  let iw = 0, ih = 0;
  let zoom = 1, panX = 0, panY = 0;
  let active = false, waiting = false, lastReq = 0, frames = 0, fpsT = 0, reqTimer = 0;

  // ---- 上のボタン ----
  const ctrlBtn = document.getElementById("scrCtrl");
  const qualBtn = document.getElementById("scrQual");
  const dragBtn = document.getElementById("scrDrag");
  const back = document.createElement("button");
  back.textContent = "← 戻る";
  back.onclick = () => Views.show("kbView");
  ctrlBtn.parentNode.insertBefore(back, ctrlBtn);
  function paintButtons() {
    ctrlBtn.textContent = prefs.scrControl ? "操作: ON" : "操作: OFF";
    ctrlBtn.classList.toggle("on", prefs.scrControl);
    qualBtn.textContent = "画質: " + ["低", "標準", "高"][prefs.scrQuality];
    dragBtn.textContent = (prefs.scrDragCursor ? "☑" : "☐") + " なぞってカーソル移動";
    dragBtn.classList.toggle("on", prefs.scrDragCursor);
    dragBtn.hidden = !prefs.scrControl;
  }
  ctrlBtn.onclick = () => {
    prefs.scrControl = !prefs.scrControl; savePrefs(); paintButtons();
    if (prefs.scrControl) toast("タップ=クリック　長押し=右クリック　長押しして動かす=ドラッグ　2本指でなぞる=スクロール　2本指で広げる=拡大　1本指でなぞる=" + (prefs.scrDragCursor ? "カーソル移動" : "スクロール"));
  };
  qualBtn.onclick = () => { prefs.scrQuality = (prefs.scrQuality + 1) % 3; savePrefs(); paintButtons(); };
  dragBtn.onclick = () => { prefs.scrDragCursor = !prefs.scrDragCursor; savePrefs(); paintButtons(); };
  paintButtons();

  // ---- 文字入力 ----
  // 下に入力パネルを出す。画面はパネルの上に収まり、最後にタップした場所を拡大して見やすくする
  const input = document.getElementById("scrInput");
  const panel = document.getElementById("scrPanel");
  const echoEl = document.getElementById("scrEcho");
  const view = document.getElementById("scrView");
  let echo = "";
  function showEcho() {
    echoEl.textContent = "";
    const t = document.createElement("span");
    t.textContent = "送った文字: ";
    const b = document.createElement("b");
    b.textContent = echo.slice(-40).replace(/\n/g, "⏎") || "（まだありません）";
    echoEl.append(t, b);
  }
  function onSent(t) {
    if (t === null) echo = echo.slice(0, -1);
    else echo += t;
    showEcho();
  }
  attachTyper(input, null, onSent);
  const keys = document.getElementById("scrKeys");
  keys.append(
    keyButton("Esc", () => { sendKey("ESC"); }), keyButton("Tab", () => sendKey("TAB")),
    modButton("Ctrl", CTRL), modButton("Shift", SHIFT), modButton("Alt", ALT), modButton("Win", WIN),
    keyButton("←", () => sendKey("LEFT"), true), keyButton("↑", () => sendKey("UP"), true),
    keyButton("↓", () => sendKey("DOWN"), true), keyButton("→", () => sendKey("RIGHT"), true),
    keyButton("⌫", () => { sendKey("BACK"); onSent(null); }, true), keyButton("Del", () => sendKey("DEL"), true),
    keyButton("⏎", () => { sendKey("ENTER"); onSent("\n"); }),
  );
  function openPanel() {
    panel.hidden = false;
    view.classList.add("typingScr");
    showEcho();
    input.focus();
    setTimeout(() => { resize(); focusLast(); }, 350);
  }
  function closePanel() {
    input.blur();
    panel.hidden = true;
    view.classList.remove("typingScr");
    setTimeout(() => { resize(); restoreZoom(); }, 50);
  }
  document.getElementById("scrKb").addEventListener("pointerdown", (e) => e.preventDefault());
  document.getElementById("scrKb").onclick = () => (panel.hidden ? openPanel() : closePanel());
  document.getElementById("scrKbClose").onclick = closePanel;

  // ---- 画像の取得 ----
  // 画像は操作とは別の通信路で受け取る。届いたらすぐ次を頼み (撮影と表示を重ねる)、
  // PCの画面が変わっていないときは、PCは空の返事だけを返す
  function request() {
    clearTimeout(reqTimer);
    if (!active || waiting || !Link.mediaReady) return;
    const wait = 50 - (performance.now() - lastReq); // 最大でも1秒に20コマ
    if (wait > 0) { reqTimer = setTimeout(request, wait); return; }
    const [w, q] = [[640, 40], [960, 55], [1440, 70]][prefs.scrQuality];
    waiting = true;
    lastReq = performance.now();
    Link.sendMedia("S", enc.encode(w + " " + q));
    // 返事が来ないときはもう一度
    reqTimer = setTimeout(() => { waiting = false; request(); }, 3000);
  }
  let decoding = false, pending = null;
  async function show(bytes) {
    if (decoding) { pending = bytes; return; } // 表示が追いつかないときは最新のものだけ
    decoding = true;
    try {
      const blob = new Blob([bytes], { type: "image/jpeg" });
      let bmp;
      if (window.createImageBitmap) bmp = await createImageBitmap(blob);
      else bmp = await new Promise((res, rej) => { const im = new Image(); im.onload = () => res(im); im.onerror = rej; im.src = URL.createObjectURL(blob); });
      if (img && img.close) img.close();
      if (img && img.src && img.src.startsWith("blob:")) URL.revokeObjectURL(img.src);
      img = bmp; iw = bmp.width; ih = bmp.height;
      draw();
      frames++;
    } catch (e) {}
    decoding = false;
    if (pending) { const p = pending; pending = null; show(p); }
  }
  Link.onData("S", (bytes) => {
    waiting = false;
    if (bytes.length === 0) {
      // 変化なし: 少し待ってから次を頼む
      clearTimeout(reqTimer);
      reqTimer = setTimeout(request, 80);
    } else {
      request();
      show(bytes.slice());
    }
    const now = performance.now();
    if (now - fpsT >= 1000) { stat.textContent = frames ? frames + "コマ/秒" : "変化なし"; frames = 0; fpsT = now; }
  });
  Link.onMediaReady.push(() => { if (active) { waiting = false; request(); } });
  Link.onState.push((s) => { if (s !== "open" && active) stat.textContent = "PCに接続していません"; });

  // ---- 表示 ----
  function base() { return Math.min(W / iw, H / ih); }
  function clampPan() {
    const s = base() * zoom, dw = iw * s, dh = ih * s;
    const mx = Math.max(0, (dw - W) / 2), my = Math.max(0, (dh - H) / 2);
    panX = Math.max(-mx, Math.min(mx, panX));
    panY = Math.max(-my, Math.min(my, panY));
  }
  function geom() {
    clampPan();
    const s = base() * zoom;
    return { s, x: (W - iw * s) / 2 + panX, y: (H - ih * s) / 2 + panY };
  }
  function draw() {
    if (!W) return;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.fillStyle = "#000"; g.fillRect(0, 0, W, H);
    if (!img) {
      g.fillStyle = "#A99D91"; g.font = "15px -apple-system, sans-serif"; g.textAlign = "center";
      g.fillText("PCの画面を読み込んでいます…", W / 2, H / 2);
      return;
    }
    const m = geom();
    g.imageSmoothingQuality = "high";
    g.drawImage(img, m.x, m.y, iw * m.s, ih * m.s);
  }
  // 表示上の位置 → PC画面上の位置 (0〜65535)
  function toPc(x, y) {
    if (!img) return null;
    const m = geom();
    const u = (x - m.x) / (iw * m.s), v = (y - m.y) / (ih * m.s);
    if (u < 0 || u > 1 || v < 0 || v > 1) return null;
    return [Math.round(u * 65535), Math.round(v * 65535)];
  }
  function sendMove(x, y) { const p = toPc(x, y); if (!p) return false; Link.send("MA " + p[0] + " " + p[1]); return true; }
  function noteTap(x, y) { const p = toPc(x, y); if (p) { lastU = p[0] / 65535; lastV = p[1] / 65535; } }

  function resize() {
    const r = cv.getBoundingClientRect();
    if (!r.width) return;
    dpr = window.devicePixelRatio || 1;
    W = r.width; H = r.height;
    cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr);
    draw();
  }
  window.addEventListener("resize", () => { if (active) setTimeout(resize, 50); });
  window.addEventListener("pp-resize", () => { if (active) { resize(); if (!panel.hidden) focusLast(); } });

  // 最後にタップした場所 (PC画面上の割合)。文字入力のときはそこを拡大する
  let lastU = -1, lastV = -1, saved = null;
  function focusLast() {
    if (!img || lastU < 0 || !prefs.scrAutoZoom) return;
    if (!saved) saved = { zoom, panX, panY };
    zoom = Math.max(zoom, 2.2);
    const s = base() * zoom;
    panX = iw * s * (0.5 - lastU);
    panY = ih * s * (0.5 - lastV);
    draw();
  }
  function restoreZoom() {
    if (!saved) return;
    ({ zoom, panX, panY } = saved);
    saved = null;
    draw();
  }
  Views.on("scrView", (on) => {
    active = on;
    Link.needMedia("screen", on);
    if (on) { setTimeout(resize, 30); stat.textContent = ""; fpsT = performance.now(); frames = 0; waiting = false; request(); }
    else { clearTimeout(reqTimer); if (!panel.hidden) closePanel(); }
  });

  // ---- タッチ ----
  const pts = new Map();
  const slop = 10;
  let downX = 0, downY = 0, lastX = 0, lastY = 0, downT = 0;
  let moved = false, multi = false, longPressed = false, dragging = false, scrolling = false, cursorMoving = false;
  let maxPts = 0, twoMode = 0, pinchDist = 0, pinchZoom = 1, startMid = null, mid = null, lastTap = 0, lpTimer = 0;
  let wv = 0, wh = 0;
  const control = () => prefs.scrControl;
  const zoomed = () => zoom > 1.05;
  function scroll(dx, dy) {
    const k = 6 * prefs.scrollSpeed;
    wv += dy * k; wh += -dx * k;
    const sv = Math.abs(wv) >= 15 ? Math.trunc(wv) : 0, sh = Math.abs(wh) >= 15 ? Math.trunc(wh) : 0;
    if (sv || sh) { Link.send("MW " + sv + " " + sh); wv -= sv; wh -= sh; }
  }
  function pos(e) { const r = cv.getBoundingClientRect(); return [e.clientX - r.left, e.clientY - r.top]; }
  function two() {
    const a = [...pts.values()];
    return { d: Math.hypot(a[0][0] - a[1][0], a[0][1] - a[1][1]), x: (a[0][0] + a[1][0]) / 2, y: (a[0][1] + a[1][1]) / 2 };
  }

  cv.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    cv.setPointerCapture(e.pointerId);
    const p = pos(e);
    pts.set(e.pointerId, p);
    maxPts = Math.max(maxPts, pts.size);
    if (pts.size === 1) {
      [downX, downY] = p; [lastX, lastY] = p; downT = e.timeStamp;
      moved = multi = longPressed = dragging = scrolling = cursorMoving = false;
      maxPts = 1; wv = wh = 0;
      clearTimeout(lpTimer);
      lpTimer = setTimeout(() => { if (!moved && !multi && control()) longPressed = true; }, 450);
    } else if (pts.size === 2) {
      clearTimeout(lpTimer);
      if (dragging) { Link.send("MB L 0"); dragging = false; }
      multi = true; twoMode = 0; wv = wh = 0;
      const t = two();
      pinchDist = Math.max(1, t.d); pinchZoom = zoom; startMid = mid = { x: t.x, y: t.y };
    }
  });
  cv.addEventListener("pointermove", (e) => {
    if (!pts.has(e.pointerId)) return;
    const p = pos(e);
    pts.set(e.pointerId, p);
    if (multi && pts.size >= 2) {
      const t = two();
      if (twoMode === 0) {
        // 指の間隔が変わったら拡大縮小、そろって動いたらスクロール
        if (Math.abs(t.d - pinchDist) > 28) twoMode = 1;
        else if (Math.hypot(t.x - startMid.x, t.y - startMid.y) > 16) {
          if (control()) { twoMode = 2; sendMove(startMid.x, startMid.y); } else twoMode = 1;
        }
      }
      if (twoMode === 1 && img) {
        const nz = Math.max(1, Math.min(6, pinchZoom * t.d / pinchDist));
        const f = nz / zoom;
        panX = (panX - (t.x - W / 2)) * f + (t.x - W / 2) + (t.x - mid.x);
        panY = (panY - (t.y - H / 2)) * f + (t.y - H / 2) + (t.y - mid.y);
        zoom = nz;
        draw();
      } else if (twoMode === 2) scroll(t.x - mid.x, t.y - mid.y);
      if (twoMode) mid = { x: t.x, y: t.y };
      return;
    }
    if (multi) return;
    const [x, y] = p;
    if (!moved && Math.hypot(x - downX, y - downY) > slop) {
      moved = true;
      clearTimeout(lpTimer);
      if (control() && !longPressed) {
        if (prefs.scrDragCursor) cursorMoving = true;
        else if (!zoomed()) { scrolling = true; sendMove(downX, downY); }
      }
    }
    if (control() && longPressed) {
      if (!dragging) { sendMove(downX, downY); Link.send("MB L 1"); dragging = true; }
      sendMove(x, y);
    } else if (cursorMoving) sendMove(x, y);
    else if (scrolling) scroll(x - lastX, y - lastY);
    else if (moved) { panX += x - lastX; panY += y - lastY; draw(); }
    lastX = x; lastY = y;
  });
  const up = (e) => {
    if (!pts.has(e.pointerId)) return;
    pts.delete(e.pointerId);
    if (pts.size > 0) return;
    clearTimeout(lpTimer);
    const quick = e.timeStamp - downT < 300;
    if (e.type === "pointercancel") { if (dragging) Link.send("MB L 0"); }
    else if (multi) {
      // 2本指で軽くタップ → 右クリック、3本指 → 中クリック
      if (control() && twoMode === 0 && quick && sendMove(startMid.x, startMid.y)) Link.send(maxPts >= 3 ? "MC M" : "MC R");
    } else if (dragging) { sendMove(lastX, lastY); Link.send("MB L 0"); }
    else if (control() && longPressed) { if (sendMove(downX, downY)) Link.send("MC R"); }
    else if (control() && !moved) { if (sendMove(downX, downY)) { Link.send("MC L"); noteTap(downX, downY); } }
    else if (!control() && !moved) {
      // 操作OFFのときは、ダブルタップで拡大／元に戻す
      const now = e.timeStamp;
      if (now - lastTap < 300) {
        if (zoomed()) { zoom = 1; panX = panY = 0; } else { zoom = 2.5; panX = -(downX - W / 2) * 1.5; panY = -(downY - H / 2) * 1.5; }
        lastTap = 0; draw();
      } else lastTap = now;
    }
    dragging = scrolling = cursorMoving = false;
  };
  cv.addEventListener("pointerup", up);
  cv.addEventListener("pointercancel", up);
})();
