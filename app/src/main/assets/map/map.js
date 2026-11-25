// H9 OPS in-app map. Runs offline in the app's WebView: the page, the map file
// and the data all come from the app itself (no internet permission).
// Station names and comments come from the air: they are only ever put on the
// page as text (textContent), never as HTML.
"use strict";

(function () {
  // The app's bridge. In a plain browser (tests) a stub stands in for it.
  const B = window.H9 || {
    config: () => JSON.stringify(window.H9_TEST_CONFIG || {}),
    ready() {}, station() {}, navigate() {}, view() {}, me() {}, full() {}, longPress() {}, removeObject() {},
  };

  const el = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = String(text);
    return e;
  };
  const $ = (id) => document.getElementById(id);

  function age(ms) {
    const s = Math.max(0, (Date.now() - ms) / 1000);
    if (s < 60) return Math.floor(s) + "s";
    if (s < 3600) return Math.floor(s / 60) + "m";
    if (s < 86400) return Math.floor(s / 3600) + "h";
    return Math.floor(s / 86400) + "d";
  }

  const OLD_MS = 60 * 60 * 1000;      // dimmed after an hour without a new position
  const LABEL_ZOOM = 8;               // zoomed out further: small dots, no callsigns
  const COLORS = {};                  // kind -> CSS colour, read from the stylesheet

  let map, cfg = {}, trails = true, openName = null, hiName = null, pin = null;
  const st = new Map();               // name -> { d, m, c, t }
  let trailLayer, areaLayer, markLayer, meLayer, trackLayer, alertLayer, overlayLayer;
  let areasKey = "";

  function readColors() {
    const cs = getComputedStyle(document.body);
    for (const [k, v] of [["b", "--beacon"], ["s", "--shared"], ["e", "--enc"], ["o", "--obj"], ["me", "--me"], ["ov", "--overlay"]]) {
      COLORS[k] = cs.getPropertyValue(v).trim() || "#ffb000";
    }
  }

  // ---------------------------------------------------------------- setup

  function init(c) {
    cfg = c || {};
    document.body.classList.toggle("night", !!cfg.night);
    const root = document.documentElement.style;
    if (cfg.accent) root.setProperty("--accent", cfg.accent);
    if (cfg.text) root.setProperty("--text", cfg.text);
    if (cfg.muted) root.setProperty("--muted", cfg.muted);
    readColors();

    map = L.map("map", { zoomControl: false, attributionControl: true, preferCanvas: true,
      maxZoom: 18, minZoom: 2, worldCopyJump: true, tapHold: true });
    map.attributionControl.setPrefix(false);
    if (cfg.tiles) {
      // Plain z/x/y tiles that the app reads out of the PMTiles file (no byte ranges).
      protomapsL.leafletLayer({
        url: "/tiles/{z}/{x}/{y}.mvt?v=" + encodeURIComponent(cfg.v || "1"),
        flavor: "dark", lang: "en", maxDataZoom: cfg.maxZoom || 15,
        attribution: "© OpenStreetMap · Protomaps",
      }).addTo(map);
    } else {
      // No map file yet: a faint grid so moving and zooming still make sense, and a way to get one.
      const Grid = L.GridLayer.extend({
        createTile() {
          const t = document.createElement("canvas"), sz = this.getTileSize();
          t.width = sz.x; t.height = sz.y;
          const g = t.getContext("2d");
          g.strokeStyle = "rgba(255,255,255,0.10)";
          g.strokeRect(0.5, 0.5, sz.x, sz.y);
          return t;
        },
      });
      new Grid({ tileSize: 256 }).addTo(map);
      $("note").textContent = "No map for your area yet. Tap here to get one (stations still show).";
      $("note").hidden = false;
      $("note").style.cursor = "pointer";
      $("note").addEventListener("click", () => B.getMap && B.getMap());
    }
    overlayLayer = L.layerGroup().addTo(map);
    alertLayer = L.layerGroup().addTo(map);
    trailLayer = L.layerGroup().addTo(map);
    areaLayer = L.layerGroup().addTo(map);
    trackLayer = L.layerGroup().addTo(map);
    meLayer = L.layerGroup().addTo(map);
    markLayer = L.layerGroup().addTo(map);

    controls();
    const v = cfg.view;
    if (v && v.length === 3) map.setView([v[0], v[1]], v[2]);
    else if (cfg.bounds) map.fitBounds([[cfg.bounds[0], cfg.bounds[1]], [cfg.bounds[2], cfg.bounds[3]]]);
    else map.setView([39, -98], 4);
    labels();
    map.on("zoomend", labels);
    map.on("moveend", () => { const p = map.getCenter(); B.view(p.lat, p.lng, map.getZoom()); });
    map.on("popupclose", () => { openName = null; });
    // Long-press (or right-click): place an object or an alert area here.
    // (tapHold makes Leaflet raise it on a touch hold; the WebView may raise its own too, so once per hold)
    let lastHold = 0;
    map.on("contextmenu", (e) => {
      const now = Date.now();
      if (now - lastHold < 1000) return;
      lastHold = now;
      B.longPress(e.latlng.lat, e.latlng.lng);
    });
    $("track-close").onclick = clearTrack;
    B.ready();
  }

  function controls() {
    const Bar = L.Control.extend({
      options: { position: "topright" },
      onAdd() {
        const bar = L.DomUtil.create("div", "leaflet-bar");
        const btn = (text, label, fn) => {
          const b = el("button", null, text);
          b.setAttribute("aria-label", label);
          b.onclick = (e) => { e.stopPropagation(); fn(b); };
          bar.append(b);
          return b;
        };
        // No + and − buttons: pinch to zoom. Full screen first, so it shows even on a short map.
        btn("⛶", "Full screen", () => B.full());
        btn("◎", "My position", (b) => {
          b.classList.add("busy");
          setTimeout(() => b.classList.remove("busy"), 70000);   // no answer (permission refused)
          B.me();
        });
        btn("⤢", "Show all stations", fitAll);
        btn("〰", "Trails", (b) => { trails = !trails; b.classList.toggle("off", !trails); drawTrails(); });
        L.DomEvent.disableClickPropagation(bar);
        return bar;
      },
    });
    new Bar().addTo(map);
  }

  function labels() {
    map.getContainer().classList.toggle("nolabels", map.getZoom() < LABEL_ZOOM);
  }

  // ---------------------------------------------------------------- stations

  function icon(d) {
    const box = el("div", "mk " + (d.k || "b") + (Date.now() - d.t > OLD_MS ? " old" : "")
      + (d.n === hiName ? " hi" : ""));
    box.append(el("span", "ic", d.ic || "•"), el("span", "cs", d.n));
    return L.divIcon({ html: box, className: "", iconSize: null, iconAnchor: [12, 12], popupAnchor: [0, -12] });
  }

  function sameIcon(a, b) {
    return a && a.ic === b.ic && a.k === b.k && (Date.now() - a.t > OLD_MS) === (Date.now() - b.t > OLD_MS);
  }

  function setStations(list) {
    const seen = new Set();
    for (const d of list || []) {
      if (!isFinite(d.la) || !isFinite(d.lo)) continue;
      seen.add(d.n);
      let s = st.get(d.n);
      if (!s) {
        s = { d };
        s.m = L.marker([d.la, d.lo], { icon: icon(d), keyboard: false, riseOnHover: true }).addTo(markLayer);
        s.m.on("click", () => openStation(d.n));
        st.set(d.n, s);
      } else {
        const moved = s.d.la !== d.la || s.d.lo !== d.lo;
        if (moved) s.m.setLatLng([d.la, d.lo]);
        if (!sameIcon(s.d, d) || d.n === hiName) s.m.setIcon(icon(d));
        s.d = d;
      }
      area(s);
    }
    for (const [n, s] of st) {
      if (seen.has(n)) continue;
      markLayer.removeLayer(s.m);
      if (s.c) areaLayer.removeLayer(s.c);
      st.delete(n);
    }
    drawTrails();
    if (openName && st.has(openName)) openStation(openName, true);
  }

  // A rounded or approximate position: show the whole area it could be in.
  function area(s) {
    const d = s.d;
    if (!(d.r > 0)) { if (s.c) { areaLayer.removeLayer(s.c); s.c = null; } return; }
    const color = COLORS[d.k] || COLORS.b;
    if (!s.c) {
      s.c = L.circle([d.la, d.lo], { radius: d.r, color, weight: 1, dashArray: "4 4", fillOpacity: 0.07,
        interactive: false }).addTo(areaLayer);
    } else {
      s.c.setLatLng([d.la, d.lo]);
      s.c.setRadius(d.r);
      s.c.setStyle({ color });
    }
  }

  function drawTrails() {
    trailLayer.clearLayers();
    if (!trails) return;
    for (const s of st.values()) {
      const tr = s.d.tr;
      if (!tr || tr.length < 2) continue;
      L.polyline(tr, { color: COLORS[s.d.k] || COLORS.b, weight: 2.5, opacity: 0.55, interactive: false })
        .addTo(trailLayer);
    }
  }

  function popup(d) {
    const box = el("div", "pop");
    box.append(el("div", "pc", d.n));
    const facts = [d.sy, age(d.t) + " ago", d.m ? "placed by you" : d.v ? "via " + d.v : "direct"].filter(Boolean);
    box.append(el("div", "pl", facts.join(" · ")));
    if (d.c) box.append(el("div", "pm", d.c));
    const row = el("div", "pb");
    const a = el("button", "main", "Details");
    a.onclick = () => B.station(d.n);
    const nav = el("button", null, "Navigate");
    nav.onclick = () => B.navigate(d.la, d.lo, d.n);
    row.append(a, nav);
    if (d.m) {                       // an object this phone placed
      const rm = el("button", null, "Remove");
      rm.onclick = () => { map.closePopup(); B.removeObject(d.n); };
      row.append(rm);
    }
    box.append(row);
    return box;
  }

  function openStation(name, refresh) {
    const s = st.get(name);
    if (!s) return;
    openName = name;
    const p = s.m.getPopup();
    if (refresh && p && p.isOpen()) { p.setContent(popup(s.d)); p.setLatLng(s.m.getLatLng()); return; }
    s.m.unbindPopup();
    s.m.bindPopup(popup(s.d), { autoPanPadding: [40, 40], maxWidth: 260 }).openPopup();
  }

  // Alert areas: dashed circles with their name.
  function setAreas(list) {
    const key = JSON.stringify(list || []);
    if (key === areasKey) return;
    areasKey = key;
    alertLayer.clearLayers();
    for (const a of list || []) {
      const c = L.circle([a.la, a.lo], { radius: a.r, color: getComputedStyle(document.documentElement)
        .getPropertyValue("--accent").trim() || "#ffb000", weight: 1.5, dashArray: "8 6", fillOpacity: 0.04,
        interactive: false }).addTo(alertLayer);
      c.bindTooltip(el("span", null, "⚑ " + a.n), { permanent: true, direction: "center", className: "area-label" });
    }
  }

  // From the app: a station's Map button, or a position from a message.
  function focus(name, lat, lon) {
    const s = st.get(name);
    if (pin) { markLayer.removeLayer(pin); pin = null; }
    const prev = hiName;
    hiName = name;
    if (prev && st.has(prev)) st.get(prev).m.setIcon(icon(st.get(prev).d));
    // the station's marker if it is at that spot, otherwise a pin (an older or sent position)
    const onStation = s && Math.abs(s.d.la - lat) < 1e-5 && Math.abs(s.d.lo - lon) < 1e-5;
    const ll = L.latLng(lat, lon);
    map.setView(ll, Math.max(map.getZoom(), 13));
    if (onStation) {
      s.m.setIcon(icon(s.d));
      openStation(name);
    } else {
      pin = L.marker(ll, { icon: L.divIcon({ html: el("div", "pin"), className: "", iconSize: [16, 16],
        iconAnchor: [8, 8] }), keyboard: false }).addTo(markLayer);
      pin.bindTooltip(el("span", null, name), { permanent: true, direction: "right", offset: [10, 0] });
    }
  }

  function fitAll() {
    const pts = [];
    for (const s of st.values()) pts.push([s.d.la, s.d.lo]);
    if (pts.length) map.fitBounds(pts, { padding: [40, 40], maxZoom: 13 });
    else if (cfg.bounds) map.fitBounds([[cfg.bounds[0], cfg.bounds[1]], [cfg.bounds[2], cfg.bounds[3]]]);
  }

  // ---------------------------------------------------------------- me, tracks

  function setMe(lat, lon, acc) {
    document.querySelectorAll(".leaflet-bar button.busy").forEach((b) => b.classList.remove("busy"));
    if (!isFinite(lat) || !isFinite(lon)) return;
    meLayer.clearLayers();
    if (acc > 0) L.circle([lat, lon], { radius: acc, color: COLORS.me, weight: 1, fillOpacity: 0.12,
      interactive: false }).addTo(meLayer);
    L.circleMarker([lat, lon], { radius: 8, color: "#000", weight: 3, fillColor: COLORS.me, fillOpacity: 1,
      interactive: false }).addTo(meLayer);
    map.setView([lat, lon], Math.max(map.getZoom(), 14));
  }

  function meFailed() {
    document.querySelectorAll(".leaflet-bar button.busy").forEach((b) => b.classList.remove("busy"));
  }

  function setTrack(t) {
    trackLayer.clearLayers();
    const pts = [];
    if (t.me && t.me.length) {
      L.polyline(t.me, { color: COLORS.me, weight: 4, opacity: 0.9, interactive: false }).addTo(trackLayer);
      pts.push(...t.me);
    }
    for (const s of t.seen || []) {
      const m = L.circleMarker([s.la, s.lo], { radius: s.a ? 4 : 5, color: "#000", weight: 1.5,
        fillColor: COLORS.b, fillOpacity: s.a ? 0.5 : 1 }).addTo(trackLayer);
      m.bindTooltip(el("span", null, s.n), { direction: "top" });
      pts.push([s.la, s.lo]);
    }
    $("track-name").textContent = t.title || "Track";
    $("track").hidden = false;
    if (pts.length) map.fitBounds(pts, { padding: [40, 40], maxZoom: 15 });
  }

  function clearTrack() {
    trackLayer.clearLayers();
    $("track").hidden = true;
  }

  // ---------------------------------------------------------------- overlays (GPX / KML trail plans)

  // Names come from the user's files: text only, like everything else.
  function setOverlays(list, fitId) {
    overlayLayer.clearLayers();
    const c = COLORS.ov || "#7bd88f";
    let fit = null;
    for (const o of list || []) {
      const pts = [];
      for (const a of o.a || []) {
        L.polygon(a, { color: c, weight: 2, dashArray: "6 4", fillColor: c, fillOpacity: 0.08, interactive: false })
          .addTo(overlayLayer);
        pts.push(...a);
      }
      for (const l of o.l || []) {
        L.polyline(l, { color: "#000", weight: 6, opacity: 0.6, interactive: false }).addTo(overlayLayer);
        L.polyline(l, { color: c, weight: 3, opacity: 0.95, interactive: false }).addTo(overlayLayer);
        pts.push(...l);
      }
      for (const p of o.p || []) {
        const m = L.circleMarker([p.la, p.lo], { radius: 5, color: "#000", weight: 2, fillColor: c, fillOpacity: 1 })
          .addTo(overlayLayer);
        m.bindTooltip(el("span", null, p.n || o.n), { direction: "top" });
        pts.push([p.la, p.lo]);
      }
      if (fitId && o.id === fitId && pts.length) fit = pts;
    }
    if (fit) map.fitBounds(fit, { padding: [40, 40], maxZoom: 16 });
  }

  window.H9map = { init, setStations, setAreas, focus, setMe, meFailed, setTrack, clearTrack, setOverlays };

  let c = {};
  try { c = JSON.parse(B.config() || "{}"); } catch (e) { c = {}; }
  init(c);
})();
