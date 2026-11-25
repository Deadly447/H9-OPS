#!/usr/bin/env python3
"""
h9topo.py - download a USGS topo map for an area into one .mbtiles file for H9 OPS.

    python h9topo.py --bbox=-109.80,37.50,-108.89,38.23 --maxzoom=15 topo.mbtiles

--bbox is west,south,east,north in degrees (the same order as pmtiles).
Needs only Python 3 (nothing to install). If it stops partway, run the same
command again: squares already saved are skipped.

USGS topo maps are public domain (USGS The National Map).
"""
import argparse
import math
import sqlite3
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

URL = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}"
AGENT = "h9topo/1.0 (H9 OPS offline topo map)"
THREADS = 4          # be polite to the USGS server


def tile_range(west, south, east, north, z):
    n = 2 ** z

    def tx(lon):
        return min(n - 1, max(0, int((lon + 180) / 360 * n)))

    def ty(lat):
        r = math.radians(lat)
        return min(n - 1, max(0, int((1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * n)))

    return tx(west), tx(east), ty(north), ty(south)


def fetch(z, x, y, tries=4):
    url = URL.format(z=z, x=x, y=y)
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": AGENT})
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None          # no map there (outside the US)
            err = e
        except Exception as e:       # timeouts, dropped connections
            err = e
        time.sleep(2 * (attempt + 1))
    raise RuntimeError("couldn't download %d/%d/%d: %s" % (z, x, y, err))


def kind(b):
    if b[:3] == b"\xff\xd8\xff":
        return "jpg"
    if b[:4] == b"\x89PNG":
        return "png"
    return None


def main():
    ap = argparse.ArgumentParser(description="Download a USGS topo map into an MBTiles file for H9 OPS.")
    ap.add_argument("output", help="the file to make, e.g. topo.mbtiles")
    ap.add_argument("--bbox", required=True, help="west,south,east,north in degrees")
    ap.add_argument("--minzoom", type=int, default=10)
    ap.add_argument("--maxzoom", type=int, default=15)
    a = ap.parse_args()
    try:
        west, south, east, north = (float(v) for v in a.bbox.split(","))
    except ValueError:
        sys.exit("--bbox must be four numbers: west,south,east,north")
    if not (west < east and south < north):
        sys.exit("--bbox must be west,south,east,north (west smaller than east, south smaller than north)")
    if not (0 <= a.minzoom <= a.maxzoom <= 16):
        sys.exit("zooms must be between 0 and 16, minzoom no bigger than maxzoom")

    jobs = []
    for z in range(a.minzoom, a.maxzoom + 1):
        x0, x1, y0, y1 = tile_range(west, south, east, north, z)
        jobs += [(z, x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]

    db = sqlite3.connect(a.output)
    db.execute("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
    db.execute("CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, "
               "tile_row INTEGER, tile_data BLOB)")
    db.execute("CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_column, tile_row)")
    have = set(db.execute("SELECT zoom_level, tile_column, tile_row FROM tiles"))
    todo = [(z, x, y) for (z, x, y) in jobs if (z, x, (2 ** z - 1) - y) not in have]
    print("%d map squares for this area, %d already saved, %d to download."
          % (len(jobs), len(jobs) - len(todo), len(todo)))

    fmt = db.execute("SELECT value FROM metadata WHERE name='format'").fetchone()
    fmt = fmt[0] if fmt else None
    done = size = 0
    start = time.time()
    with ThreadPoolExecutor(THREADS) as pool:
        for (z, x, y), data in zip(todo, pool.map(lambda j: fetch(*j), todo)):
            done += 1
            if data:
                fmt = fmt or kind(data)
                db.execute("INSERT OR REPLACE INTO tiles VALUES (?, ?, ?, ?)", (z, x, (2 ** z - 1) - y, data))
                size += len(data)
            if done % 100 == 0 or done == len(todo):
                db.commit()
                rate = done / max(1e-6, time.time() - start)
                left = (len(todo) - done) / max(1e-6, rate)
                print("\r  %d / %d  (%.0f MB, about %d min left)   "
                      % (done, len(todo), size / 1e6, left / 60), end="", flush=True)
    print()

    meta = {
        "name": "USGS Topo", "type": "baselayer", "version": "1", "format": fmt or "jpg",
        "description": "USGS topographic map (The National Map)",
        "attribution": "USGS The National Map",
        "bounds": "%f,%f,%f,%f" % (west, south, east, north),
        "minzoom": str(a.minzoom), "maxzoom": str(a.maxzoom),
    }
    db.execute("DELETE FROM metadata")
    db.executemany("INSERT INTO metadata VALUES (?, ?)", meta.items())
    db.commit()
    total = db.execute("SELECT COUNT(*), SUM(LENGTH(tile_data)) FROM tiles").fetchone()
    db.close()
    print("Done: %s, %d squares, %.0f MB. Copy it to the phone and choose it in "
          "H9 OPS -> Map file... -> Terrain." % (a.output, total[0], (total[1] or 0) / 1e6))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nStopped. Run the same command again to carry on where it left off.")
