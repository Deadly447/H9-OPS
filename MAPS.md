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
you made for another program. Raster maps and MBTiles files can't be read yet.

## Trail plans and search areas

GPX, KML and KMZ files (CalTopo, Gaia, onX, Google Earth, ATAK) go on top of
the map: **⋮ → Map overlays… → Add file…**.
