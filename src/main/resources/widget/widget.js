/*
 * Embeddable website chat widget for the multi-tenant bot.
 * Usage: <script src="https://HOST/widget/widget.js" data-key="PUBLIC_KEY" defer></script>
 *
 * Optional data-* attributes (all user-facing strings live here, never inline — the product is
 * multi-language, so a tenant can localize without code changes):
 *   data-title        Header title         (default "Chat")
 *   data-accent       Accent color         (default #2563eb)
 *   data-placeholder  Input placeholder    (default "Type a message...")
 *   data-welcome      First bot bubble shown on open (default none)
 *   data-typing       Typing indicator a11y label (default "typing")
 *   data-error        Connection error text (default "Connection lost. Reconnecting...")
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

  // Resolve host + asset base from the script's own URL so WS and CSS share the bot's origin.
  var srcUrl = new URL(script.src, window.location.href);
  var assetBase = srcUrl.href.substring(0, srcUrl.href.lastIndexOf("/")); // .../widget
  var wsProto = srcUrl.protocol === "https:" ? "wss:" : "ws:";
  var wsBase = wsProto + "//" + srcUrl.host;

  var labels = {
    title: script.getAttribute("data-title") || "Chat",
    accent: script.getAttribute("data-accent") || "#2563eb",
    placeholder: script.getAttribute("data-placeholder") || "Type a message...",
    welcome: script.getAttribute("data-welcome") || "",
    typing: script.getAttribute("data-typing") || "typing",
    error: script.getAttribute("data-error") || "Connection lost. Reconnecting...",
  };

  var sessionStorageKey = "tbl-chat-session:" + key;
  var sessionId = null;
  try { sessionId = localStorage.getItem(sessionStorageKey); } catch (e) {}

  var ws = null;
  var connected = false;
  var reconnectDelay = 1000;
  var welcomeShown = false;

  // --- DOM ---------------------------------------------------------------
  var link = document.createElement("link");
  link.rel = "stylesheet";
  link.href = assetBase + "/widget.css";
  document.head.appendChild(link);

  var root = document.createElement("div");
  root.className = "tbl-chat-root";
  root.style.setProperty("--tbl-accent", labels.accent);
  root.innerHTML =
    '<button class="tbl-chat-bubble" aria-label="Open chat">' +
      '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 3C6.5 3 2 6.8 2 11.5c0 2.2 1 4.2 2.7 5.7-.1 1.2-.5 2.4-1.3 3.4 1.6-.2 3-.8 4.1-1.6 1.4.5 2.9.8 4.5.8 5.5 0 10-3.8 10-8.5S17.5 3 12 3z"/></svg>' +
    '</button>' +
    '<div class="tbl-chat-panel" role="dialog" aria-label="' + escapeAttr(labels.title) + '">' +
      '<div class="tbl-chat-header"><span></span><button class="tbl-chat-close" aria-label="Close chat">&times;</button></div>' +
      '<div class="tbl-chat-body"></div>' +
      '<div class="tbl-chat-footer">' +
        '<input class="tbl-chat-input" type="text" />' +
        '<button class="tbl-chat-send" aria-label="Send">' +
          '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M3 20.5l18-8.5L3 3.5 3 10l13 2-13 2z"/></svg>' +
        '</button>' +
      '</div>' +
    '</div>';
  document.body.appendChild(root);

  var bubble = root.querySelector(".tbl-chat-bubble");
  var panel = root.querySelector(".tbl-chat-panel");
  var closeBtn = root.querySelector(".tbl-chat-close");
  var body = root.querySelector(".tbl-chat-body");
  var input = root.querySelector(".tbl-chat-input");
  var sendBtn = root.querySelector(".tbl-chat-send");
  root.querySelector(".tbl-chat-header span").textContent = labels.title;
  input.placeholder = labels.placeholder;

  // --- UI helpers --------------------------------------------------------
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

  function escapeAttr(s) { return String(s).replace(/"/g, "&quot;"); }

  // --- WebSocket ---------------------------------------------------------
  function connect() {
    var url = wsBase + "/chat/ws?key=" + encodeURIComponent(key) + (sessionId ? "&session=" + encodeURIComponent(sessionId) : "");
    ws = new WebSocket(url);

    ws.onopen = function () {
      connected = true;
      reconnectDelay = 1000;
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
      connected = false;
      hideTyping();
      setTimeout(connect, reconnectDelay);
      reconnectDelay = Math.min(reconnectDelay * 2, 15000);
    };

    ws.onerror = function () { try { ws.close(); } catch (e) {} };
  }

  function sendMessage() {
    var text = input.value.trim();
    if (!text) return;
    if (!connected || !ws || ws.readyState !== WebSocket.OPEN) {
      addMessage(labels.error, "tbl-msg-error");
      return;
    }
    addMessage(text, "tbl-msg-user");
    input.value = "";
    showTyping();
    ws.send(JSON.stringify({ type: "user_message", text: text, clientMsgId: Date.now().toString() }));
  }

  // --- Events ------------------------------------------------------------
  function openPanel() {
    root.classList.add("tbl-open");
    if (!welcomeShown && labels.welcome) { addMessage(labels.welcome, "tbl-msg-bot"); welcomeShown = true; }
    input.focus();
  }
  bubble.addEventListener("click", openPanel);
  closeBtn.addEventListener("click", function () { root.classList.remove("tbl-open"); });
  sendBtn.addEventListener("click", sendMessage);
  input.addEventListener("keydown", function (e) { if (e.key === "Enter") { e.preventDefault(); sendMessage(); } });

  connect();
})();
