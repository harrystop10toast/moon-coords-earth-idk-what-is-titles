# Moon / Earth coordinate tools

The standalone Java site-report CLI lives in [`resources/earth-moon-site-reports`](resources/earth-moon-site-reports). It is kept separate from the main project.

## Setup

Install JDK 17 or newer, then:

```sh
cd resources/earth-moon-site-reports
./gradlew test
./gradlew run --args="1 43 281"
```

The first argument selects the body (`0` Earth, `1` Moon); the next two are latitude and longitude. Optional `GEOAPIFY_API_KEY` and `NOAA_GEOMAG_API_KEY` environment variables enable their respective enhanced data sources. Never commit API keys.

## Earth-analog comparison priority

1. **Terrain identity / landform** — plains, ridges, mountains, cratered terrain.
2. **Terrain geometry** — slope, relief, roughness, aspect, and curvature at matching scales.
3. **Surface geology and materials** — mapped units and mineral/oxide composition.
4. **Thermal regime** — compare surface temperatures under similar illumination; Earth air temperature is not lunar surface temperature.
5. **Solar exposure** — radiation, Sun angles, and illuminated duration.
6. **Water / volatile potential** — compare cautiously; rainfall is not equivalent to lunar hydrogen or confirmed ice.
7. **Impact setting** — crater density and nearby impact features at comparable map resolution.

Gravity and magnetics are useful context, not direct analog scores. Earth atmosphere, weather, vegetation, and population/infrastructure are excluded from cross-body matching.

## Earth analog finder

The standalone local matcher and its setup instructions are in [`resources/earth-index-poc`](resources/earth-index-poc). It ranks up to five Earth analogs for a Moon coordinate; filtering can yield fewer or no matches. To run it from a fresh checkout:

```sh
cd resources/earth-moon-site-reports
./gradlew run --args="1 12.5 281.0"
cd ../earth-index-poc
python3 build_index.py
python3 fast_earth_report.py 12.5 281.0 --download-glim
python3 match_earth_analogs.py 12.5 281.0 --from-report ../earth-moon-site-reports/site-reports.json
```

Replace `12.5 281.0` with the Moon latitude and longitude. Do not type `#lat #lon` literally in a shell because `#` starts a comment. Generated/downloaded rasters and indexes under `data/` are intentionally not committed; the Earth-index README documents how to fetch them locally.

The matcher can also be run directly from its folder when the matching Moon report is saved where it expects it:

```sh
cd resources/earth-index-poc
python3 match_earth_analogs.py 12.5 281.0
```
