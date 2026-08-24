/*
 * Embeddable website chat widget for the multi-tenant bot.
 * Usage: <script src="https://HOST/widget/widget.js" data-key="PUBLIC_KEY" defer></script>
 *
 * Optional data-* attributes:
 *   data-title        Assistant name               (default "Chat")
 *   data-subtitle     Header supporting text       (default "Typically replies instantly")
 *   data-accent       Brand color                  (default #2563eb)
 *   data-position     "left" or "right"           (default "right")
 *   data-theme        "light", "dark", or "auto" (default "light")
 *   data-launcher     Launcher button label        (default "Chat with us")
 *   data-placeholder  Composer placeholder         (default "Type a message...")
 *   data-welcome      First bot message            (default none)
 *   data-typing       Typing indicator label       (default "Typing")
 *   data-error        Connection error text        (default "Connection lost. Reconnecting...")
 *   data-open-label   Launcher accessibility label (default "Open chat")
 *   data-close-label  Close accessibility label    (default "Close chat")
 *   data-send-label   Send accessibility label     (default "Send message")
 *   data-branding     Footer text; empty hides it   (default "Powered by thebots.lab")
 */
(function () {
  "use strict";

  var script = document.currentScript;
  if (!script) return;
  var key = script.getAttribute("data-key");
  if (!key) {
    console.error("[chat-widget] missing data-key attribute");
    return;
  }

  function option(name, fallback, allowEmpty) {
    var value = script.getAttribute("data-" + name);
    return value === null || (!allowEmpty && !value) ? fallback : value;
  }

  function accentInk(hex) {
    var value = String(hex).replace("#", "");
    if (!/^[0-9a-f]{6}$/i.test(value)) return "#ffffff";
    var red = parseInt(value.slice(0, 2), 16);
    var green = parseInt(value.slice(2, 4), 16);
    var blue = parseInt(value.slice(4, 6), 16);
    return (red * 299 + green * 587 + blue * 114) / 1000 > 155 ? "#111827" : "#ffffff";
  }

  var srcUrl = new URL(script.src, window.location.href);
  var assetBase = srcUrl.href.substring(0, srcUrl.href.lastIndexOf("/"));
  var wsProto = srcUrl.protocol === "https:" ? "wss:" : "ws:";
  var wsBase = wsProto + "//" + srcUrl.host;
  var instanceId = "tbl-chat-" + Math.random().toString(36).slice(2, 9);
  var position = option("position", "right") === "left" ? "left" : "right";
  var theme = option("theme", "light");
  if (["light", "dark", "auto"].indexOf(theme) === -1) theme = "light";

  var labels = {
    title: option("title", "Chat"),
    subtitle: option("subtitle", "Typically replies instantly"),
    accent: option("accent", "#2563eb"),
    launcher: option("launcher", "Chat with us", true),
    placeholder: option("placeholder", "Type a message..."),
    welcome: option("welcome", "", true),
    typing: option("typing", "Typing"),
    error: option("error", "Connection lost. Reconnecting..."),
    open: option("open-label", "Open chat"),
    close: option("close-label", "Close chat"),
    send: option("send-label", "Send message"),
    branding: option("branding", "Powered by thebots.lab", true),
  };

  var sessionStorageKey = "tbl-chat-session:" + key;
  var sessionId = null;
  try { sessionId = localStorage.getItem(sessionStorageKey); } catch (e) {}

  var ws = null;
  var connected = false;
  var connecting = false;
  var reconnectTimer = null;
  var reconnectDelay = 1000;
  var welcomeShown = false;

  var link = document.createElement("link");
  link.rel = "stylesheet";
  link.href = assetBase + "/widget.css";
  document.head.appendChild(link);

  var root = document.createElement("div");
  root.className = "tbl-chat-root tbl-position-" + position + " tbl-theme-" + theme;
  root.style.setProperty("--tbl-accent", labels.accent);
  root.style.setProperty("--tbl-accent-ink", accentInk(labels.accent));
  root.innerHTML =
    '<button class="tbl-chat-bubble" type="button" aria-expanded="false" aria-controls="' + instanceId + '-panel" aria-label="' + escapeAttr(labels.open) + '">' +
      '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11.5c0 4.14-3.8 7.5-8.5 7.5-1.22 0-2.38-.23-3.43-.64L4 20l1.24-3.28C3.84 15.36 3 13.55 3 11.5 3 7.36 6.8 4 11.5 4S20 7.36 20 11.5Z"/><path d="M8 11.5h.01M11.5 11.5h.01M15 11.5h.01"/></svg>' +
      (labels.launcher ? '<span>' + escapeHTML(labels.launcher) + '</span>' : '') +
    '</button>' +
    '<section class="tbl-chat-panel" id="' + instanceId + '-panel" role="dialog" aria-modal="false" aria-labelledby="' + instanceId + '-title" hidden>' +
      '<header class="tbl-chat-header">' +
        '<div class="tbl-chat-avatar" aria-hidden="true">' + escapeHTML(labels.title.charAt(0).toUpperCase() || "C") + '</div>' +
        '<div class="tbl-chat-identity"><strong id="' + instanceId + '-title">' + escapeHTML(labels.title) + '</strong><span><i class="tbl-status-dot"></i>' + escapeHTML(labels.subtitle) + '</span></div>' +
        '<button class="tbl-chat-close" type="button" aria-label="' + escapeAttr(labels.close) + '"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m7 7 10 10M17 7 7 17"/></svg></button>' +
      '</header>' +
      '<div class="tbl-chat-status" role="status" aria-live="polite"></div>' +
      '<div class="tbl-chat-body" role="log" aria-live="polite" aria-relevant="additions"></div>' +
      '<footer class="tbl-chat-footer">' +
        '<div class="tbl-chat-composer"><textarea class="tbl-chat-input" rows="1" maxlength="2000"></textarea>' +
        '<button class="tbl-chat-send" type="button" aria-label="' + escapeAttr(labels.send) + '" disabled>' +
          '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m4 4 17 8-17 8 3-8-3-8Zm3 8h14"/></svg>' +
        '</button></div>' +
        (labels.branding ? '<div class="tbl-chat-branding">' + escapeHTML(labels.branding) + '</div>' : '') +
      '</footer>' +
    '</section>';
  document.body.appendChild(root);

  var bubble = root.querySelector(".tbl-chat-bubble");
  var panel = root.querySelector(".tbl-chat-panel");
  var closeBtn = root.querySelector(".tbl-chat-close");
  var body = root.querySelector(".tbl-chat-body");
  var input = root.querySelector(".tbl-chat-input");
  var sendBtn = root.querySelector(".tbl-chat-send");
  var status = root.querySelector(".tbl-chat-status");
  input.placeholder = labels.placeholder;

  function escapeHTML(value) {
    return String(value).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  }

  function escapeAttr(value) { return escapeHTML(value); }

  function addMessage(text, cls) {
    var el = document.createElement("div");
    el.className = "tbl-msg " + cls;
    el.textContent = text;
    body.appendChild(el);
    body.scrollTop = body.scrollHeight;
    return el;
  }

  var typingEl = null;
  function showTyping() {
    if (typingEl) return;
    typingEl = document.createElement("div");
    typingEl.className = "tbl-typing";
    typingEl.setAttribute("aria-label", labels.typing);
    typingEl.innerHTML = "<span></span><span></span><span></span>";
    body.appendChild(typingEl);
    body.scrollTop = body.scrollHeight;
  }

  function hideTyping() {
    if (typingEl) { typingEl.remove(); typingEl = null; }
  }

  function updateComposer() {
    sendBtn.disabled = !connected || !input.value.trim();
  }

  function setConnectionState(next, message) {
    connected = next;
    root.classList.toggle("tbl-connected", next);
    status.textContent = message || "";
    status.classList.toggle("tbl-chat-status--visible", Boolean(message));
    updateComposer();
  }

  function connect() {
    if (connecting || connected) return;
    connecting = true;
    var url = wsBase + "/chat/ws?key=" + encodeURIComponent(key) + (sessionId ? "&session=" + encodeURIComponent(sessionId) : "");
    ws = new WebSocket(url);

    ws.onopen = function () {
      connecting = false;
      reconnectDelay = 1000;
      setConnectionState(true, "");
    };

    ws.onmessage = function (evt) {
      var frame;
      try { frame = JSON.parse(evt.data); } catch (e) { return; }
      if (frame.type === "session") {
        sessionId = frame.sessionId;
        try { localStorage.setItem(sessionStorageKey, sessionId); } catch (e) {}
      } else if (frame.type === "bot_message") {
        hideTyping();
        addMessage(frame.text, "tbl-msg-bot");
      } else if (frame.type === "error") {
        hideTyping();
        addMessage(frame.message, "tbl-msg-error");
      }
    };

    ws.onclose = function () {
      connecting = false;
      hideTyping();
      setConnectionState(false, labels.error);
      clearTimeout(reconnectTimer);
      reconnectTimer = setTimeout(connect, reconnectDelay);
      reconnectDelay = Math.min(reconnectDelay * 2, 15000);
    };

    ws.onerror = function () { try { ws.close(); } catch (e) {} };
  }

  function sendMessage() {
    var text = input.value.trim();
    if (!text || !connected || !ws || ws.readyState !== WebSocket.OPEN) return;
    addMessage(text, "tbl-msg-user");
    input.value = "";
    input.style.height = "auto";
    updateComposer();
    showTyping();
    ws.send(JSON.stringify({ type: "user_message", text: text, clientMsgId: Date.now().toString() }));
  }

  function openPanel() {
    root.classList.add("tbl-open");
    panel.hidden = false;
    bubble.setAttribute("aria-expanded", "true");
    if (!welcomeShown && labels.welcome) {
      addMessage(labels.welcome, "tbl-msg-bot");
      welcomeShown = true;
    }
    connect();
    window.setTimeout(function () { input.focus(); }, 50);
  }

  function closePanel() {
    root.classList.remove("tbl-open");
    panel.hidden = true;
    bubble.setAttribute("aria-expanded", "false");
    bubble.focus();
  }

  bubble.addEventListener("click", openPanel);
  closeBtn.addEventListener("click", closePanel);
  sendBtn.addEventListener("click", sendMessage);
  input.addEventListener("input", function () {
    input.style.height = "auto";
    input.style.height = Math.min(input.scrollHeight, 104) + "px";
    updateComposer();
  });
  input.addEventListener("keydown", function (event) {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      sendMessage();
    }
  });
  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape" && root.classList.contains("tbl-open")) closePanel();
  });
})();
