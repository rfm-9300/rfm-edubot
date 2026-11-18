/* The Chatbot page's board (/app → Chatbot): a canvas that shows a flow's steps as cards joined by lines, which people
   pan, zoom, rearrange and wire up by dragging. It knows nothing about flows: chatbot.js draws the cards into the world
   (`[data-node]`, each with outputs `[data-port]` and an input `[data-in]`), lists the links, and decides what each
   gesture means through the callbacks given to create(). Places are board pixels at 100%: a card's top left corner.
   Controls inside the canvas that aren't part of the drawing (toolbars, menus) carry `data-board-ui`.
   FlowBoard.layout() arranges a graph in columns, left to right, for a flow nobody arranged by hand. */
(function () {
  const GRID = 8;
  const DOT = 24;
  const MIN_ZOOM = 0.3;
  const MAX_ZOOM = 1.6;
  const SLOP = 4;
  const EDGE = 36;
  /** Room the toolbars take along the canvas's top and bottom, and a margin at its sides. */
  const INSET = { top: 60, bottom: 56, side: 24 };
  const SVG = 'http://www.w3.org/2000/svg';

  const snap = v => Math.round(v / GRID) * GRID;
  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

  /** A line from an output at (x1, y1) to an input at (x2, y2): out to the right, in from the left, looping round when it goes back. */
  function curve(x1, y1, x2, y2) {
    const dx = x2 - x1;
    const bend = dx > 24 ? Math.max(36, dx * 0.45) : 72 + Math.min(180, Math.abs(y2 - y1) * 0.3 + Math.abs(dx) * 0.2);
    return `M${x1},${y1} C${x1 + bend},${y1} ${x2 - bend},${y2} ${x2},${y2}`;
  }

  /**
   * Mounts a board on [canvas], which holds `[data-world]` (the cards and an `svg[data-edges]` with a `g[data-edge-layer]`
   * and a `path[data-temp-edge]`). Options: editable, view ({x, y, k}), canTarget(id), and the callbacks onView(view),
   * onSelect({node} | {edge} | null), onMove(id, x, y), onConnect(from, port, to), onDropEmpty(from, port, at, client),
   * onPortClick(from, port, client) and onBackground(at, client) (a double click on the empty board).
   */
  function create(canvas, opts = {}) {
    const world = canvas.querySelector('[data-world]');
    const svg = canvas.querySelector('[data-edges]');
    const layer = svg.querySelector('[data-edge-layer]');
    const temp = svg.querySelector('[data-temp-edge]');
    const view = { x: 0, y: 0, k: 1, ...(opts.view || {}) };
    /** id → { el, x, y, w, h, ports: Map(key → {dx, dy}), in: {dx, dy} } */
    const nodes = new Map();
    let edges = [];
    const drawn = new Map();
    const pointers = new Map();
    let gesture = null;
    let swallowClick = false;
    let raf = 0;

    function applyView(quiet) {
      world.style.transform = `translate(${view.x}px, ${view.y}px) scale(${view.k})`;
      canvas.style.backgroundSize = `${DOT * view.k}px ${DOT * view.k}px`;
      canvas.style.backgroundPosition = `${view.x}px ${view.y}px`;
      if (!quiet) opts.onView?.({ ...view });
    }

    function setView(next) {
      view.x = next.x;
      view.y = next.y;
      view.k = clamp(next.k, MIN_ZOOM, MAX_ZOOM);
      applyView();
    }

    /** Zooms to [k] keeping the point under the client position (cx, cy) where it is. */
    function zoomAt(k, cx, cy) {
      const r = canvas.getBoundingClientRect();
      const px = cx - r.left;
      const py = cy - r.top;
      const wx = (px - view.x) / view.k;
      const wy = (py - view.y) / view.k;
      view.k = clamp(k, MIN_ZOOM, MAX_ZOOM);
      view.x = px - wx * view.k;
      view.y = py - wy * view.k;
      applyView();
    }

    function zoomBy(factor) {
      const r = canvas.getBoundingClientRect();
      zoomAt(view.k * factor, r.left + r.width / 2, r.top + r.height / 2);
    }

    const toWorld = (cx, cy) => {
      const r = canvas.getBoundingClientRect();
      return { x: (cx - r.left - view.x) / view.k, y: (cy - r.top - view.y) / view.k };
    };
    /** A board point as pixels from the canvas's top left corner. */
    const toCanvas = (x, y) => ({ x: view.x + x * view.k, y: view.y + y * view.k });

    function bounds() {
      let x0 = Infinity; let y0 = Infinity; let x1 = -Infinity; let y1 = -Infinity;
      for (const n of nodes.values()) {
        x0 = Math.min(x0, n.x); y0 = Math.min(y0, n.y);
        x1 = Math.max(x1, n.x + n.w); y1 = Math.max(y1, n.y + n.h);
      }
      return Number.isFinite(x0) ? { x: x0, y: y0, w: x1 - x0, h: y1 - y0 } : null;
    }

    /** The part of the canvas the cards are shown in: clear of the toolbars along its top and bottom. */
    const area = () => {
      const w = canvas.clientWidth;
      const h = canvas.clientHeight;
      return { left: INSET.side, top: INSET.top, w: w - INSET.side * 2, h: h - INSET.top - INSET.bottom, ok: w > 0 && h > 0 };
    };

    /** The zoom that shows every card, at 100% at most. */
    const fitZoom = (b, a) => Math.min(a.w / Math.max(b.w, 1), a.h / Math.max(b.h, 1), 1);

    /** Shows every card, at 100% at most; a flow too big even at the smallest zoom starts at its top left. */
    function fit() {
      const b = bounds();
      const a = area();
      if (!b || !a.ok) return;
      view.k = clamp(fitZoom(b, a), MIN_ZOOM, MAX_ZOOM);
      view.x = a.left + (b.w * view.k > a.w ? 0 : (a.w - b.w * view.k) / 2) - b.x * view.k;
      view.y = a.top + (b.h * view.k > a.h ? 0 : (a.h - b.h * view.k) / 2) - b.y * view.k;
      applyView();
    }

    /**
     * The first view of a flow: all of it when it fits at a size that can be read, else card [focus] (its start) at
     * that size, on the left, with the cards beside it, to pan from.
     */
    function home(focus, readable = 0.8) {
      const b = bounds();
      const a = area();
      const n = nodes.get(focus);
      if (!b || !a.ok) return;
      if (!n || fitZoom(b, a) >= readable - 0.05) return fit();
      view.k = readable;
      view.x = a.left - b.x * view.k;
      // Level with the start where the flow is taller than the canvas, else the whole height in the middle.
      view.y = b.h * view.k > a.h
        ? clamp(a.top + a.h / 2 - (n.y + n.h / 2) * view.k, a.top + a.h - (b.y + b.h) * view.k, a.top - b.y * view.k)
        : a.top + (a.h - b.h * view.k) / 2 - b.y * view.k;
      applyView();
    }

    /** Brings card [id] into view, if it isn't already. */
    function reveal(id) {
      const n = nodes.get(id);
      const a = area();
      if (!n || !a.ok) return;
      const at = toCanvas(n.x, n.y);
      const right = at.x + n.w * view.k;
      const bottom = at.y + Math.min(n.h, 240) * view.k;
      if (at.x >= a.left && at.y >= a.top && right <= a.left + a.w && bottom <= a.top + a.h) return;
      view.x = a.left + a.w / 2 - (n.x + n.w / 2) * view.k;
      view.y = Math.min(a.top + a.h / 2 - (n.y + n.h / 2) * view.k, a.top - n.y * view.k);
      applyView();
    }

    // ── Cards ────────────────────────────────────────────────────────────────────────────────────

    /** Finds the cards in the world and measures them. */
    function scan() {
      nodes.clear();
      world.querySelectorAll('[data-node]').forEach(el => {
        nodes.set(el.dataset.node, { el, x: 0, y: 0, w: 0, h: 0, ports: new Map(), in: { dx: 0, dy: 18 } });
      });
      measure();
    }

    /** Reads the size of the cards [ids] (all of them by default) and where their inputs and outputs are. */
    function measure(ids) {
      for (const [id, n] of nodes) {
        if (ids && !ids.includes(id)) continue;
        n.w = n.el.offsetWidth;
        n.h = n.el.offsetHeight;
        const box = n.el.getBoundingClientRect();
        const at = el => {
          const r = el.getBoundingClientRect();
          return { dx: (r.left + r.width / 2 - box.left) / view.k, dy: (r.top + r.height / 2 - box.top) / view.k };
        };
        n.ports = new Map([...n.el.querySelectorAll('[data-port]')].map(p => [p.dataset.port, at(p)]));
        const input = n.el.querySelector('[data-in]');
        if (input) n.in = at(input);
      }
    }

    function place(id, x, y) {
      const n = nodes.get(id);
      if (!n) return;
      n.x = x;
      n.y = y;
      n.el.style.transform = `translate(${x}px, ${y}px)`;
    }

    /** Moves card [id] and the lines that touch it. */
    function moveNode(id, x, y) {
      place(id, x, y);
      route(new Set([id]));
    }

    const position = id => { const n = nodes.get(id); return n ? { x: n.x, y: n.y } : null; };
    const box = id => { const n = nodes.get(id); return n ? { x: n.x, y: n.y, w: n.w, h: n.h } : null; };
    /** What layout() needs to know about each card. */
    const sizes = () => [...nodes].map(([id, n]) => ({ id, w: n.w, h: n.h, inDy: n.in.dy, ports: n.ports }));

    // ── Lines ────────────────────────────────────────────────────────────────────────────────────

    /** Draws [list] ({ id, from, port, to, cls }), replacing the lines drawn before. */
    function setEdges(list) {
      edges = list;
      layer.textContent = '';
      drawn.clear();
      for (const e of list) {
        const g = document.createElementNS(SVG, 'g');
        g.setAttribute('class', `flow-edge${e.cls ? ` ${e.cls}` : ''}`);
        g.dataset.edge = e.id;
        const hit = document.createElementNS(SVG, 'path');
        hit.setAttribute('class', 'flow-edge__hit');
        const line = document.createElementNS(SVG, 'path');
        line.setAttribute('class', 'flow-edge__line');
        g.append(hit, line);
        layer.append(g);
        drawn.set(e.id, g);
      }
      route();
    }

    function ends(e) {
      const a = nodes.get(e.from);
      const b = nodes.get(e.to);
      const p = a?.ports.get(e.port);
      if (!p || !b) return null;
      return [a.x + p.dx, a.y + p.dy, b.x + b.in.dx, b.y + b.in.dy];
    }

    /** Redraws the lines touching [only] (a set of card ids), or all of them. */
    function route(only) {
      for (const e of edges) {
        if (only && !only.has(e.from) && !only.has(e.to)) continue;
        const g = drawn.get(e.id);
        const at = g && ends(e);
        if (!g) continue;
        g.style.display = at ? '' : 'none';
        if (!at) continue;
        const d = curve(...at);
        g.firstChild.setAttribute('d', d);
        g.lastChild.setAttribute('d', d);
      }
      fitSvg();
    }

    /** Sizes the svg over every card (and [extra], a point being dragged to), so its lines can be drawn and clicked. */
    function fitSvg(extra) {
      const b = bounds() || { x: 0, y: 0, w: 1, h: 1 };
      let x0 = b.x; let y0 = b.y; let x1 = b.x + b.w; let y1 = b.y + b.h;
      if (extra) { x0 = Math.min(x0, extra.x); y0 = Math.min(y0, extra.y); x1 = Math.max(x1, extra.x); y1 = Math.max(y1, extra.y); }
      const m = 320;
      x0 -= m; y0 -= m; x1 += m; y1 += m;
      svg.setAttribute('viewBox', `${x0} ${y0} ${x1 - x0} ${y1 - y0}`);
      svg.style.left = `${x0}px`;
      svg.style.top = `${y0}px`;
      svg.style.width = `${x1 - x0}px`;
      svg.style.height = `${y1 - y0}px`;
    }

    /** The middle of line [id], in board pixels. */
    function midpoint(id) {
      const line = drawn.get(id)?.lastChild;
      if (!line || drawn.get(id).style.display === 'none') return null;
      const p = line.getPointAtLength(line.getTotalLength() / 2);
      return { x: p.x, y: p.y };
    }

    // ── Gestures ─────────────────────────────────────────────────────────────────────────────────

    function targetAt(cx, cy, from) {
      const el = document.elementFromPoint(cx, cy)?.closest('[data-node]');
      if (!el || !canvas.contains(el)) return null;
      const id = el.dataset.node;
      return opts.canTarget && !opts.canTarget(id, from) ? null : id;
    }

    function markTarget(id) {
      for (const [key, n] of nodes) n.el.classList.toggle('is-target', key === id);
    }

    function drawTemp(cx, cy) {
      const g = gesture;
      const a = nodes.get(g.id);
      const p = a?.ports.get(g.port);
      if (!p) return;
      const w = toWorld(cx, cy);
      temp.setAttribute('d', curve(a.x + p.dx, a.y + p.dy, w.x, w.y));
      temp.style.display = '';
      fitSvg(w);
    }

    function down(e) {
      swallowClick = false;
      if (e.pointerType === 'mouse' && e.button !== 0) return;
      if (e.target.closest('[data-board-ui]')) return;
      pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
      if (pointers.size === 2) {
        if (gesture?.moved && gesture.type !== 'pan') return;
        endAutoPan();
        const [a, b] = [...pointers.values()];
        gesture = { type: 'pinch', d0: Math.hypot(a.x - b.x, a.y - b.y) || 1, k0: view.k, mx: (a.x + b.x) / 2, my: (a.y + b.y) / 2, vx: view.x, vy: view.y, moved: true };
        return;
      }
      if (pointers.size > 2) return;
      const port = opts.editable && e.target.closest('[data-port]');
      const node = e.target.closest('[data-node]');
      const base = { x0: e.clientX, y0: e.clientY, cx: e.clientX, cy: e.clientY, vx: view.x, vy: view.y, moved: false };
      if (port && node) {
        gesture = { ...base, type: 'connect', id: node.dataset.node, port: port.dataset.port, el: port };
      } else if (node && opts.editable) {
        const at = position(node.dataset.node);
        const w = toWorld(e.clientX, e.clientY);
        gesture = { ...base, type: 'node', id: node.dataset.node, start: at, grab: { x: w.x - at.x, y: w.y - at.y } };
      } else {
        gesture = { ...base, type: 'pan' };
      }
      gesture.pointerId = e.pointerId;
    }

    function move(e) {
      const p = pointers.get(e.pointerId);
      if (p) { p.x = e.clientX; p.y = e.clientY; }
      const g = gesture;
      if (!g || !p) return;
      if (g.type === 'pinch') return pinch();
      g.cx = e.clientX;
      g.cy = e.clientY;
      if (!g.moved && Math.hypot(e.clientX - g.x0, e.clientY - g.y0) < SLOP) return;
      if (!g.moved) {
        g.moved = true;
        // Only a drag keeps the pointer: a capture from the press on would send the click to the canvas instead of
        // the card or line that was clicked.
        try { canvas.setPointerCapture(g.pointerId); } catch { /* the pointer is already gone */ }
        canvas.classList.add(g.type === 'pan' ? 'is-panning' : 'is-dragging');
        if (g.type === 'node') nodes.get(g.id)?.el.classList.add('is-dragging');
        if (g.type === 'connect') {
          g.el.classList.add('is-active');
          // A line already leaving this output fades while it is moved elsewhere.
          for (const ed of edges) if (ed.from === g.id && ed.port === g.port) drawn.get(ed.id)?.classList.add('is-rewiring');
        }
      }
      if (g.type === 'pan') {
        view.x = g.vx + e.clientX - g.x0;
        view.y = g.vy + e.clientY - g.y0;
        applyView();
        return;
      }
      follow();
      autoPan();
    }

    /** Puts what is being dragged under the pointer. */
    function follow() {
      const g = gesture;
      if (!g) return;
      if (g.type === 'node') {
        const w = toWorld(g.cx, g.cy);
        moveNode(g.id, snap(w.x - g.grab.x), snap(w.y - g.grab.y));
      } else if (g.type === 'connect') {
        drawTemp(g.cx, g.cy);
        markTarget(targetAt(g.cx, g.cy, g.id));
      }
    }

    /** Near the canvas's edge, a drag moves the board along. */
    function autoPan() {
      const g = gesture;
      const r = canvas.getBoundingClientRect();
      const push = (v, lo, hi) => (v < lo + EDGE ? 1 : v > hi - EDGE ? -1 : 0);
      g.push = { x: push(g.cx, r.left, r.right), y: push(g.cy, r.top, r.bottom) };
      if ((g.push.x || g.push.y) && !raf) {
        const step = () => {
          const now = gesture;
          if (!now?.push || (!now.push.x && !now.push.y)) { raf = 0; return; }
          view.x += now.push.x * 10;
          view.y += now.push.y * 10;
          applyView();
          follow();
          raf = requestAnimationFrame(step);
        };
        raf = requestAnimationFrame(step);
      }
    }

    function endAutoPan() {
      if (raf) cancelAnimationFrame(raf);
      raf = 0;
    }

    function pinch() {
      const g = gesture;
      const [a, b] = [...pointers.values()];
      if (!b) return;
      const r = canvas.getBoundingClientRect();
      const k = clamp(g.k0 * (Math.hypot(a.x - b.x, a.y - b.y) / g.d0), MIN_ZOOM, MAX_ZOOM);
      const wx = (g.mx - r.left - g.vx) / g.k0;
      const wy = (g.my - r.top - g.vy) / g.k0;
      view.k = k;
      view.x = (a.x + b.x) / 2 - r.left - wx * k;
      view.y = (a.y + b.y) / 2 - r.top - wy * k;
      applyView();
    }

    function up(e) {
      if (!pointers.has(e.pointerId)) return;
      pointers.delete(e.pointerId);
      const g = gesture;
      if (!g) return;
      if (g.type === 'pinch') {
        if (!pointers.size) gesture = null;
        else gesture = { type: 'idle', moved: true };
        swallowClick = true;
        return;
      }
      if (g.type === 'idle') { if (!pointers.size) gesture = null; return; }
      gesture = null;
      endAutoPan();
      canvas.classList.remove('is-panning', 'is-dragging');
      if (g.moved) swallowClick = true;
      if (g.type === 'node' && g.moved) {
        nodes.get(g.id)?.el.classList.remove('is-dragging');
        const at = position(g.id);
        if (at.x !== g.start.x || at.y !== g.start.y) opts.onMove?.(g.id, at.x, at.y);
      }
      if (g.type === 'connect') {
        swallowClick = true;
        temp.style.display = 'none';
        g.el.classList.remove('is-active');
        markTarget(null);
        layer.querySelectorAll('.is-rewiring').forEach(el => el.classList.remove('is-rewiring'));
        const r = g.el.getBoundingClientRect();
        if (!g.moved) return opts.onPortClick?.(g.id, g.port, { x: r.left + r.width / 2, y: r.top + r.height / 2 });
        const target = e.type === 'pointercancel' ? null : targetAt(e.clientX, e.clientY, g.id);
        if (target) opts.onConnect?.(g.id, g.port, target);
        else if (e.type !== 'pointercancel') opts.onDropEmpty?.(g.id, g.port, toWorld(e.clientX, e.clientY), { x: e.clientX, y: e.clientY });
      }
    }

    /** A click that an earlier handler answered by drawing the page again: this board, or what was clicked, is gone. */
    const stale = e => !canvas.isConnected || !canvas.contains(e.target);

    function click(e) {
      if (swallowClick) {
        swallowClick = false;
        e.stopPropagation();
        e.preventDefault();
        return;
      }
      if (stale(e) || e.target.closest('[data-board-ui]')) return;
      const node = e.target.closest('[data-node]');
      const edge = e.target.closest('[data-edge]');
      if (node) opts.onSelect?.({ node: node.dataset.node });
      else if (edge) opts.onSelect?.({ edge: edge.dataset.edge });
      else opts.onSelect?.(null);
    }

    function dblclick(e) {
      const under = document.elementFromPoint(e.clientX, e.clientY);
      if (stale(e) || !under || under.closest('[data-board-ui], [data-node], [data-edge]')) return;
      opts.onBackground?.(toWorld(e.clientX, e.clientY), { x: e.clientX, y: e.clientY });
    }

    function wheel(e) {
      if (e.target.closest('[data-board-ui]')) return;
      e.preventDefault();
      const unit = e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? canvas.clientHeight : 1;
      if (e.ctrlKey || e.metaKey) {
        zoomAt(view.k * clamp(Math.exp(-e.deltaY * unit * 0.01), 0.8, 1.25), e.clientX, e.clientY);
      } else {
        view.x -= e.deltaX * unit;
        view.y -= e.deltaY * unit;
        applyView();
      }
    }

    canvas.addEventListener('pointerdown', down);
    canvas.addEventListener('pointermove', move);
    canvas.addEventListener('pointerup', up);
    canvas.addEventListener('pointercancel', up);
    canvas.addEventListener('click', click);
    canvas.addEventListener('dblclick', dblclick);
    canvas.addEventListener('wheel', wheel, { passive: false });
    applyView(true);

    return {
      scan, measure, place, moveNode, position, box, sizes, setEdges, route, midpoint,
      fit, home, reveal, zoomBy, setView, toWorld, toCanvas,
      zoomTo: k => { const r = canvas.getBoundingClientRect(); zoomAt(k, r.left + r.width / 2, r.top + r.height / 2); },
      view: () => ({ ...view }),
      /** Whether a drag or pinch is under way, when a redraw would cut it short. */
      busy: () => !!gesture,
    };
  }

  /**
   * Arranges a graph in columns, left to right: each card one column after the furthest card that leads to it, and
   * level with the outputs that lead to it where there is room. Lines that go back (loops) don't count. Cards no root
   * reaches come in their own bands underneath. nodes: [{ id, w, h, inDy }], edges: [{ from, to, dy }] (dy: the
   * output's height in its card), roots: ids. Returns a Map of id → { x, y }.
   */
  function layout({ nodes, edges, roots, gapX = 88, gapY = 32 }) {
    const byId = new Map(nodes.map(n => [n.id, n]));
    const out = new Map(nodes.map(n => [n.id, []]));
    const into = new Map(nodes.map(n => [n.id, 0]));
    for (const e of edges) {
      if (!byId.has(e.from) || !byId.has(e.to)) continue;
      out.get(e.from).push(e);
      into.set(e.to, into.get(e.to) + 1);
    }
    const state = new Map();
    const band = new Map();
    const order = [];
    const post = [];
    const back = new Set();
    const bands = [];
    const visit = (id, b) => {
      state.set(id, 1);
      band.set(id, b);
      order.push(id);
      for (const e of out.get(id)) {
        if (state.get(e.to) === 1) back.add(e);
        else if (!state.has(e.to)) visit(e.to, b);
      }
      state.set(id, 2);
      post.push(id);
    };
    const start = id => { const from = order.length; visit(id, bands.length); bands.push(order.slice(from)); };
    roots.forEach(id => { if (byId.has(id) && !state.has(id)) start(id); });
    nodes.forEach(n => { if (!state.has(n.id) && !into.get(n.id)) start(n.id); });
    nodes.forEach(n => { if (!state.has(n.id)) start(n.id); });

    const rank = new Map(order.map((id, i) => [id, i]));
    const col = new Map(order.map(id => [id, 0]));
    for (const id of [...post].reverse()) {
      for (const e of out.get(id)) {
        if (back.has(e) || band.get(e.to) !== band.get(id)) continue;
        col.set(e.to, Math.max(col.get(e.to), col.get(id) + 1));
      }
    }

    const pos = new Map();
    let top = 0;
    for (const ids of bands) {
      const cols = new Map();
      ids.forEach(id => { const c = col.get(id); if (!cols.has(c)) cols.set(c, []); cols.get(c).push(id); });
      const keys = [...cols.keys()].sort((a, b) => a - b);
      const colX = new Map();
      let x = 0;
      for (const c of keys) {
        colX.set(c, x);
        x += Math.max(...cols.get(c).map(id => byId.get(id).w)) + gapX;
      }
      let bottom = top;
      for (const c of keys) {
        const want = new Map();
        for (const id of cols.get(c)) {
          const ys = edges.filter(e => e.to === id && pos.has(e.from) && !back.has(e) && band.get(e.from) === band.get(id)).map(e => pos.get(e.from).y + e.dy);
          if (ys.length) want.set(id, ys.reduce((s, v) => s + v, 0) / ys.length - (byId.get(id).inDy || 0));
        }
        const sorted = [...cols.get(c)].sort((a, b) => (want.get(a) ?? Infinity) - (want.get(b) ?? Infinity) || rank.get(a) - rank.get(b));
        let y = top;
        const placed = sorted.map(id => {
          const at = Math.max(y, want.get(id) ?? y);
          y = at + byId.get(id).h + gapY;
          return { id, at };
        });
        // Pull the column back up by how far it was pushed down on average, but not above the band.
        const pushed = placed.filter(p => want.has(p.id));
        if (pushed.length) {
          const up = Math.min(pushed.reduce((s, p) => s + p.at - want.get(p.id), 0) / pushed.length, placed[0].at - top);
          if (up > 0) placed.forEach(p => { p.at -= up; });
        }
        for (const p of placed) {
          pos.set(p.id, { x: snap(colX.get(c)), y: snap(p.at) });
          bottom = Math.max(bottom, p.at + byId.get(p.id).h);
        }
      }
      top = snap(bottom + gapY * 2);
    }
    return pos;
  }

  window.FlowBoard = { create, layout, snap, GRID };
})();
