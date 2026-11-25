# Getting a map for H9 OPS

H9 OPS shows stations on an offline map. The app has no internet access, so it
can't download maps itself: you make one map file for your area on a computer,
once, and copy it to the phone. After that the map works anywhere, with no
signal.

The map is a **PMTiles** file cut out of [Protomaps](https://protomaps.com)'
free world map, which is built from OpenStreetMap data.

## The quick way (from the app)

1. In H9 OPS open the **Map** tab and move to your area (or just allow
   location), then **⋮ → Map file… → Get a map for your area…**. You can also
   tap the "No map for your area yet" note on the Map tab.
2. Pick a size: 15, 40 or 100 miles each way. The app shows the exact command
   for that box.
3. Tap **Send to computer…** (email it to yourself, for example) or **Copy**.
4. On the computer, get the free **pmtiles** tool from
   <https://github.com/protomaps/go-pmtiles/releases>. Download the file for
   your system (Windows `x86_64.zip`, Mac `Darwin` zip, Linux `Linux_x86_64`
   tar.gz) and unzip it. It's a single program, nothing to install.
5. Open a terminal (Windows: PowerShell) in that folder and paste the command.
   On Windows start it with `.\pmtiles.exe` instead of `pmtiles`. It looks like:

   ```
   pmtiles extract https://build.protomaps.com/20261122.pmtiles h9-map.pmtiles --bbox=-105.5338,39.7826,-104.9662,40.2174
   ```

   It downloads only your area and writes `h9-map.pmtiles`.
6. Copy `h9-map.pmtiles` to the phone (USB cable, a cloud drive, email to
   yourself) and in H9 OPS choose **⋮ → Map file… → Choose the map file…**.
   The app keeps its own copy, so you can delete the one you copied.

## Choosing the area by hand

Go to <https://bboxfinder.com>, draw a rectangle around your area and copy the
**Box** numbers (west, south, east, north). Then run:

```
pmtiles extract https://build.protomaps.com/YYYYMMDD.pmtiles h9-map.pmtiles --bbox=WEST,SOUTH,EAST,NORTH
```

`YYYYMMDD` is a recent build date from <https://maps.protomaps.com/builds>
(builds from the last week are always there). Add `--dry-run` first to see how
big the file will be without downloading it.

## How big?

| Area | File |
|---|---|
| 15 miles each way (a town and its trails) | about 20–60 MB |
| 40 miles each way (a county) | about 100–300 MB |
| 100 miles each way (a region) | about 0.5–1.5 GB |

Cities make bigger files than open country. To make one smaller, add
`--maxzoom=14` (a little less detail when zoomed all the way in).

## Already have a map?

Any **vector** PMTiles file from the Protomaps basemap works, for example one
you made for another program. Picture maps (topo maps, satellite) go under
Terrain instead (below).

## Terrain

The street map shows roads, trails, water and places, but not the shape of the
ground. Add a second file, **terrain**, and the map gets:

- **hill shading**, so ridges and drainages stand out;
- **contour lines**, every 40 ft zoomed in (200 ft index lines) up to every
  1,000 ft zoomed out;
- **the elevation under the crosshair** in the middle of the map, in feet.

Tap **⛰** on the map to show or hide it. The terrain is
[Mapterhorn](https://mapterhorn.com)'s free elevation data (from Copernicus
and other open sources), about 15 m detail in the US.

### The quick way (from the app)

**⋮ → Map file… → Get terrain for your area…** works like the street map: pick
15, 40 or 100 miles, send the command to your computer and run it with the
same pmtiles tool. It looks like:

```
pmtiles extract https://download.mapterhorn.com/planet.pmtiles h9-terrain.pmtiles --maxzoom=12 --bbox=-105.5338,39.7826,-104.9662,40.2174
```

Copy `h9-terrain.pmtiles` to the phone and choose it under **⋮ → Map file… →
Terrain → Choose a terrain or topo file…**.

| Area | Terrain file (rough) |
|---|---|
| 15 miles each way | 10–30 MB |
| 40 miles each way | 50–150 MB |
| 100 miles each way (`--maxzoom=11`) | 100–300 MB |

Add `--dry-run` to see the real size before downloading.

### A USGS topo map instead

Any **picture map** works too, shown as it is over the street map: a USGS topo
map, a satellite map, a scanned trail map. It must be an **MBTiles** or
**PMTiles** file of PNG, JPEG or WebP tiles. USGS topo maps are public domain;
one way to make the file, with the free [QGIS](https://qgis.org):

1. In QGIS, **Browser → XYZ Tiles → New Connection**, with the URL
   `https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}`,
   and add it to the map.
2. Zoom to your area.
3. **Processing → Toolbox → Raster tools → Generate XYZ tiles (MBTiles)**.
   Extent: *use map canvas extent*; minimum zoom 10, maximum zoom 15 or 16.
   Run it and save the `.mbtiles` file.
4. Copy it to the phone and choose it under **Terrain**.

Zoom 16 over a big area makes a very large file: keep the area tight or the
maximum zoom at 15.

The app works out by itself which kind of file it is (elevation or picture).
Elevation files in Terrain-RGB encoding (Mapbox style) work too.

## Trail plans and search areas

GPX, KML and KMZ files (CalTopo, Gaia, onX, Google Earth, ATAK) go on top of
the map: **⋮ → Map overlays… → Add file…**.
