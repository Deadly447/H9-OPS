// H9 OPS terrain layer. Draws the terrain file the app serves at /terrain/z/x/y:
//  - elevation tiles (Terrarium or Terrain-RGB): hill shading and contour lines,
//    computed here from the heights, plus the elevation at the map's centre;
//  - picture tiles (a topo map): shown as they are.
// Offline like the rest of the page: every tile comes from the app.
"use strict";

(function () {
  const FT = 3.28084;
  const MIN_ZOOM = 9;                 // below this, shading and contours are noise at phone size
  const CACHE = 48;                   // decoded elevation tiles kept

  // Contour spacing by map zoom, feet; every fifth line is an index line.
  function interval(z) {
    if (z >= 14) return 40;
    if (z >= 13) return 80;
    if (z >= 11) return 200;
    if (z >= 10) return 400;
    return 1000;
  }

  function exaggeration(z) {
    return z <= 10 ? 2.5 : z <= 11 ? 2 : z <= 12 ? 1.6 : 1.3;
  }

  function decoder(kind) {
    return kind === "mapbox"
      ? (r, g, b) => -10000 + (r * 65536 + g * 256 + b) * 0.1
      : (r, g, b) => r * 256 + g + b / 256 - 32768;
  }

  // Leaflet puts attributions in as HTML; the file's own text is shown as text only.
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => "&#" + c.charCodeAt(0) + ";");

  function create(map, t) {
    const off = t.px >= 512 ? 1 : 0;
    const v = encodeURIComponent(t.v || "1");
    const url = (z, x, y) => "/terrain/" + z + "/" + x + "/" + y + "?v=" + v;
    const bounds = t.bounds ? L.latLngBounds([t.bounds[0], t.bounds[1]], [t.bounds[2], t.bounds[3]]) : undefined;
    if (!map.getPane("terrain")) {
      const p = map.createPane("terrain");
      p.style.zIndex = 250;
      p.style.pointerEvents = "none";
    }

    if (t.kind === "picture") {
      return {
        layer: L.tileLayer("/terrain/{z}/{x}/{y}?v=" + v, {
          pane: "terrain", tileSize: t.px, zoomOffset: -off, minNativeZoom: t.min + off,
          maxNativeZoom: t.max + off, maxZoom: 18, bounds, attribution: t.attr ? esc(t.attr) : "",
        }),
        elevation: null,
      };
    }

    // ---- elevation: decoded tiles, cached
    const decode = decoder(t.kind);
    const cache = new Map();          // "z/x/y" -> Promise<{w, h, e: Float32Array} | null>
    const canvas = document.createElement("canvas");
    const cx = canvas.getContext("2d", { willReadFrequently: true });

    function dem(z, x, y) {
      const key = z + "/" + x + "/" + y;
      let p = cache.get(key);
      if (p) {
        cache.delete(key);
        cache.set(key, p);            // most recently used last
        return p;
      }
      p = fetch(url(z, x, y))
        .then((r) => (r.ok ? r.blob() : null))
        .then((b) => (b && b.size ? createImageBitmap(b) : null))
        .then((bm) => {
          if (!bm) return null;
          const w = bm.width, h = bm.height;
          canvas.width = w;
          canvas.height = h;
          cx.clearRect(0, 0, w, h);
          cx.drawImage(bm, 0, 0);
          if (bm.close) bm.close();
          const d = cx.getImageData(0, 0, w, h).data;
          const e = new Float32Array(w * h);
          for (let i = 0, j = 0; i < e.length; i++, j += 4) {
            e[i] = d[j + 3] < 250 ? NaN : decode(d[j], d[j + 1], d[j + 2]);
          }
          return { w, h, e };
        })
        .catch((e) => { console.error("terrain: " + (e && e.message)); return null; });
      cache.set(key, p);
      while (cache.size > CACHE) cache.delete(cache.keys().next().value);
      return p;
    }

    // Height at (u, v) pixels of a tile, bilinear, clamped to the tile.
    function sample(tl, u, v) {
      const w = tl.w, h = tl.h;
      u = Math.min(w - 1, Math.max(0, u));
      v = Math.min(h - 1, Math.max(0, v));
      const x0 = Math.floor(u), y0 = Math.floor(v);
      const x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
      const fx = u - x0, fy = v - y0, e = tl.e;
      const a = e[y0 * w + x0], b = e[y0 * w + x1], c = e[y1 * w + x0], d = e[y1 * w + x1];
      return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
    }

    // Metres at a point, from the most detailed zoom; null outside the file or not loaded yet.
    function elevation(lat, lon) {
      const z = t.max, n = 2 ** z;
      const fx = (lon + 180) / 360 * n;
      const r = lat * Math.PI / 180;
      const fy = (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n;
      const x = Math.floor(fx), y = Math.floor(fy);
      if (x < 0 || y < 0 || x >= n || y >= n) return Promise.resolve(null);
      return dem(z, x, y).then((tl) => {
        if (!tl) return null;
        const m = sample(tl, (fx - x) * tl.w - 0.5, (fy - y) * tl.h - 0.5);
        return isFinite(m) ? m : null;
      });
    }

    const Layer = L.GridLayer.extend({
      createTile(coords, done) {
        const ratio = Math.min(2, window.devicePixelRatio || 1);
        const N = Math.round(256 * ratio);
        const tile = document.createElement("canvas");
        tile.width = tile.height = N;
        const z = coords.z;
        const d = Math.min(t.max, z - off);
        if (z < MIN_ZOOM || d < t.min) {
          setTimeout(() => done(null, tile), 0);
          return tile;
        }
        const per = 2 ** (z - d);      // 256-px output tiles across one elevation tile
        const X = Math.floor(coords.x / per), Y = Math.floor(coords.y / per);
        dem(d, X, Y).then((tl) => {
          try {
            if (tl) draw(tile, tl, coords, per, X, Y, N);
          } catch (e) {
            console.error("terrain tile: " + e.message);
          }
          done(null, tile);
        });
        return tile;
      },
    });

    // Shading and contours for one output tile, from one elevation tile.
    function draw(tile, tl, coords, per, X, Y, N) {
      const z = coords.z;
      const G = N + 2;                // grid with a one-pixel border, for slopes and joined contours
      const grid = new Float32Array(G * G);
      const sx = (coords.x - X * per), sy = (coords.y - Y * per);
      for (let j = 0; j < G; j++) {
        const v = (sy + (j - 1 + 0.5) / N) / per * tl.h - 0.5;
        for (let i = 0; i < G; i++) {
          const u = (sx + (i - 1 + 0.5) / N) / per * tl.w - 0.5;
          grid[j * G + i] = sample(tl, u, v);
        }
      }
      const g = tile.getContext("2d");

      // Hill shading: light from the north-west, 45° up (Horn's method).
      const lat = map.unproject(L.point((coords.x + 0.5) * 256, (coords.y + 0.5) * 256), z).lat;
      const cell = 40075016.686 * Math.cos(lat * Math.PI / 180) / (256 * 2 ** z) * (256 / N);
      const zf = exaggeration(z);
      const zen = Math.PI / 4, az = (360 - 315 + 90) * Math.PI / 180;
      const flat = Math.cos(zen);
      const img = g.createImageData(N, N);
      const px = img.data;
      for (let j = 0; j < N; j++) {
        for (let i = 0; i < N; i++) {
          const o = (j + 1) * G + (i + 1);
          const a = grid[o - G - 1], b = grid[o - G], c = grid[o - G + 1];
          const dd = grid[o - 1], f = grid[o + 1];
          const gg = grid[o + G - 1], h = grid[o + G], ii = grid[o + G + 1];
          const dx = ((c + 2 * f + ii) - (a + 2 * dd + gg)) / (8 * cell);
          const dy = ((gg + 2 * h + ii) - (a + 2 * b + c)) / (8 * cell);
          if (!isFinite(dx) || !isFinite(dy)) continue;
          const slope = Math.atan(zf * Math.sqrt(dx * dx + dy * dy));
          const aspect = Math.atan2(dy, -dx);
          const s = Math.cos(zen) * Math.cos(slope) + Math.sin(zen) * Math.sin(slope) * Math.cos(az - aspect);
          const q = (j * N + i) * 4;
          if (s < flat) {
            px[q + 3] = Math.min(0.55, (flat - s) * 1.2) * 255;               // shadow: black
          } else {
            px[q] = px[q + 1] = px[q + 2] = 255;
            px[q + 3] = Math.min(0.22, (s - flat) * 0.8) * 255;               // lit slope: white
          }
        }
      }
      g.putImageData(img, 0, 0);

      // Contour lines (marching squares over the grid, in feet).
      const step = interval(z), index = step * 5;
      const minor = new Path2D(), major = new Path2D();
      for (let j = 0; j < G - 1; j++) {
        for (let i = 0; i < G - 1; i++) {
          const o = j * G + i;
          const tl0 = grid[o] * FT, tr = grid[o + 1] * FT, br = grid[o + G + 1] * FT, bl = grid[o + G] * FT;
          if (!(isFinite(tl0) && isFinite(tr) && isFinite(br) && isFinite(bl))) continue;
          const lo = Math.min(tl0, tr, br, bl), hi = Math.max(tl0, tr, br, bl);
          if (hi - lo < 1e-6) continue;
          const x0 = i - 1 + 0.5, y0 = j - 1 + 0.5;    // grid point (i, j) sits at this canvas pixel
          for (let lv = Math.ceil(lo / step) * step; lv <= hi; lv += step) {
            const path = lv % index === 0 ? major : minor;
            segments(path, lv, tl0, tr, br, bl, x0, y0);
          }
        }
      }
      g.lineCap = "round";
      g.strokeStyle = "rgba(214,160,96,0.38)";
      g.lineWidth = 0.8 * (N / 256);
      g.stroke(minor);
      g.strokeStyle = "rgba(232,180,112,0.75)";
      g.lineWidth = 1.5 * (N / 256);
      g.stroke(major);
    }

    // The crossing points of level lv on a cell's four edges, joined in pairs.
    function segments(path, lv, a, b, c, d, x, y) {
      const pts = [];
      const cross = (p, q, x1, y1, x2, y2) => {
        if ((p < lv) !== (q < lv)) {
          const f = (lv - p) / (q - p);
          pts.push(x1 + (x2 - x1) * f, y1 + (y2 - y1) * f);
        }
      };
      cross(a, b, x, y, x + 1, y);             // top
      cross(b, c, x + 1, y, x + 1, y + 1);     // right
      cross(c, d, x + 1, y + 1, x, y + 1);     // bottom
      cross(d, a, x, y + 1, x, y);             // left
      for (let k = 0; k + 3 < pts.length; k += 4) {
        path.moveTo(pts[k], pts[k + 1]);
        path.lineTo(pts[k + 2], pts[k + 3]);
      }
    }

    const attribution = t.attr ? "Terrain: " + esc(t.attr) : "Terrain";
    return {
      layer: new Layer({ pane: "terrain", tileSize: 256, maxZoom: 18, bounds, attribution }),
      elevation,
    };
  }

  window.H9terrain = { create, FT };
})();
