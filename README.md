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

Gravity and magnetics are useful context, not direct analog scores. Earth atmosphere, weather, vegetation, and population/infrastructure are excluded from cross-body matching. The CLI reports site measurements; it does not yet select the closest Earth coordinate.
