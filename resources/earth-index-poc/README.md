# Earth terrain index prototype

This standalone proof of concept builds a reusable, local terrain candidate index and Earth report. It does not modify or call `Main.java`, and the report makes no per-coordinate API requests.

## Build

Requires Python 3.10+. Indexing, downloading, and ETOPO5 reporting use Python's standard library. To sample downloaded Copernicus GeoTIFFs, the optional system `libtiff` library is required (`brew install libtiff` on macOS).

```sh
python3 build_index.py
python3 fetch_etopo2022.py
python3 fetch_dem_tiles.py --point 50.4 14.9 --point 43.0 1.0
python3 fast_earth_report.py 50.4 14.9 --download-dem --download-worldcover --download-glim
python3 match_earth_analogs.py 0 0
python3 -m unittest discover -s tests
```

Downloaded/generated files under `data/` are intentionally excluded from GitHub; create them locally with `build_index.py` and the optional download flags rather than committing hundreds of megabytes of rasters.

## Moon → Earth analog match (top 5)

`match_earth_analogs.py` takes a Moon coordinate, reads terrain features from the matching lunar site report, and ranks the prebuilt Earth land-cell index. It defaults to up to five geographically diverse Earth coordinates with per-factor reasons; strict terrain/material filters may return fewer or no matches. Use numeric Moon latitude and longitude values. Do not type `#lat #lon` literally—`#` starts a shell comment.

```sh
python3 match_earth_analogs.py 12.5 281.0
python3 match_earth_analogs.py 0 0 --json
python3 match_earth_analogs.py --from-report ../site-reports.json
```

Replace the example numbers with the Moon latitude and longitude you want to query; these are lunar, not Earth coordinates. `--top N` changes the default result limit of five. If the matching lunar report is not already saved, the script tries the Gradle wrapper in the parent directory. In the GitHub `resources/` layout, generate a Moon report with the Java CLI first, then pass its path explicitly, for example `python3 match_earth_analogs.py 12.5 281.0 --from-report ../earth-moon-site-reports/site-reports.json`.

For a fresh GitHub checkout in the `resources/` layout, from `resources/earth-index-poc` build the Earth index and fetch the small GLiM material layer before matching:

```sh
python3 build_index.py
python3 fast_earth_report.py 12.5 281.0 --download-glim
python3 match_earth_analogs.py 12.5 281.0 --from-report ../earth-moon-site-reports/site-reports.json
```

Multi-aspect score (local only):

1. **Terrain hard gate** — slope within ±3°, roughness within ~3×; otherwise candidate is rejected. If nothing clears the gate / min agreement, status is `no_good_analog_found` (honest empty result).
2. **Material** — mare → GLiM basic volcanic (`vb`); highland → anorthosite/plagioclase-rich proxies (`pa`/`mt`/`pb`); **unknown → unverified, not scored**.
3. **Latitude / solar** — sun-angle mismatch **penalty** only.
4. **Arid/barren suitability** — percentile proxy for extreme lunar thermal sites (not Kelvin equality).
5. **Impact** — only if an Earth crater is proximal and size-compatible.

Not scored: magnetic nT, absolute elevation, SoilGrids, weather.

The first run downloads NOAA's public ETOPO5 global elevation grid (about 18.7 MB). It then builds `data/earth-terrain-index.sqlite3`; both downloaded data and the generated database are ignored by Git. Subsequent runs reuse the download. The index contains 10-arc-minute land candidates and coarse slope, local-relief, and roughness features. Mean elevation is retained for context only, not for Earth–Moon similarity scoring.

`fetch_etopo2022.py` downloads NOAA's improved global 60-arc-second surface GeoTIFF (about 466 MB) once. This global layer is used for elevation, bathymetry, and a coarse terrain fallback. It is not required if you only want the smaller ETOPO5 report.

## Fast single-coordinate Earth report

After the one-time index build, `python3 fast_earth_report.py <latitude> <longitude>` reads local datasets and prints a report in the Moon report's JSON shape without calling point APIs. It saves reports in `data/earth-fast-reports.json`; exact duplicate coordinates reuse their saved report, and `--refresh` replaces it. If the matching Copernicus GLO-90 tile is cached and `libtiff` is available, the report samples its elevation and 11×11 (~1 km) terrain neighborhood; it also includes the coarse ETOPO5 result for comparison. Cached ESA WorldCover and GLiM data are sampled for land cover and broad lithology.

To fetch the needed public 1° DEM tile once and then sample it, add `--download-dem`:

```sh
python3 fast_earth_report.py 50.4 14.9 --download-dem
```

This downloads only the one tile for that coordinate (unless already cached), not global GLO-90. Later runs use the cached file without networking. `--download-dem` refreshes an older saved report so the new terrain values are included.

Fetch the corresponding ESA WorldCover tile and the small global GLiM raster on the same report command:

```sh
python3 fast_earth_report.py 50.4 14.9 --download-worldcover --download-glim
```

WorldCover downloads only the coordinate's 3° × 3° 2021 map tile (often around 100 MB), not the ~117 GB global collection. GLiM is a single ~39 kB archive containing a global 0.5° categorical grid. Both are stored under `data/` and reused locally; `--refresh` replaces an older saved report. WorldCover is land cover, while GLiM is regional lithology—not mineral chemistry. WorldCover is CC BY 4.0; GLiM is CC BY 3.0 and requires citation to [PANGAEA DOI 10.1594/PANGAEA.788537](https://doi.org/10.1594/PANGAEA.788537).

MODIS MOD11A2 and MCD43A3 remain unintegrated. NASA’s data granules are available through LP DAAC but require an Earthdata account for download; the prototype intentionally does not collect credentials. Do not treat browse JPEGs as scientific temperature or albedo measurements.

This is deliberately a fast local prototype, not a complete Earth copy of the Moon report. Copernicus GLO-90 is a surface model, so buildings and vegetation affect heights; its 90 m neighborhood is not identical to the lunar data's footprint. ETOPO 2022 terrain neighborhoods are regional (~9 km); ETOPO5 summaries are much coarser (10 arc-minutes, roughly 18 km north-to-south). Every other unimplemented field says why it is missing and lists recommended data sources in `report_output.recommended_downloads`. See [DATA_SOURCES.md](DATA_SOURCES.md) for dataset roles, chunks, and limitations.

`fetch_dem_tiles.py` resolves candidate coordinates to Copernicus GLO-90's 1° public tile IDs, checks provider coverage using its tile list, and downloads each unique COG once. For many candidates, pass a CSV with `latitude` and `longitude` columns using `--points-csv candidates.csv`; downloads run with four workers by default.

ETOPO5 is an older global relief product, and NOAA documents lower-resolution source data in some regions. The 10-arc-minute cells are roughly 18 km north-to-south, with longitude width varying by latitude. This is a fast ingestion/indexing experiment, not yet a validated lunar analog ranking. Do not compare terrain summaries directly with lunar measurements taken at a different spatial baseline; harmonize scales and add source-quality weights first.

See [DATA_SOURCES.md](DATA_SOURCES.md) for bulk/tile sources and the proposed coarse-to-fine chunk strategy.

**ETOPO5 source:** NOAA NCEI, [format notes](https://www.ngdc.noaa.gov/mgg/global/relief/ETOPO5/TOPO/ETOPO5/ETOPO5.txt), [ETOPO5.DAT](https://www.ngdc.noaa.gov/mgg/global/relief/ETOPO5/TOPO/ETOPO5.DAT).
