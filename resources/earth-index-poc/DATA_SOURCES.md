# Earth analog data sources and chunk plan

“All Earth data” is not a finite downloadable product. This catalog focuses on global or near-global products that can support a lunar analog search, and records where Earth-only variables should be excluded. Start with a compact global prefilter; download detailed tiles only for shortlisted areas.

| Priority / role | Authoritative source | Coverage and chunks | Use and limits |
|---|---|---|---|
| 1 — Global terrain prefilter | [NOAA ETOPO 2022](https://www.ncei.noaa.gov/products/etopo-global-relief-model) | Global 60-arc-second surface GeoTIFF is one ~466 MB file; 15/30-arc-second files are larger. | The prototype downloads and samples the 60s surface raster for global elevation/bathymetry and regional terrain; not a high-resolution landing-site DEM. |
| 1 — Candidate terrain refinement | [Copernicus DEM GLO-90](https://registry.opendata.aws/copernicus-dem/) | Public, global 90 m Cloud-Optimized GeoTIFFs in 1° tiles; tile names are listed in [`tileList.txt`](https://copernicus-dem-90m.s3.amazonaws.com/tileList.txt). | Fetch tiles only around candidate coordinates. The fast report samples a cached tile when system `libtiff` is available. It is a **surface model (DSM)**, so vegetation/buildings affect elevations; not bare-earth terrain. |
| 1 — Finer candidate terrain | [Copernicus DEM GLO-30 Public](https://registry.opendata.aws/copernicus-dem/) | 30 m COGs in 1° tiles, but public coverage is incomplete. | Use only where a tile is listed/available. Never treat missing tiles as water or flat terrain. |
| 2 — Broad geology | [GLiM Global Lithological Map](https://www.geo.uni-hamburg.de/en/geologie/forschung/aquatische-geochemie/glim.html) | Global 0.5° raster archive (~39 kB zipped); finer global polygons are also described by the project. | The prototype downloads and samples the 0.5° grid. Coarse mapped lithology only; not local mineral chemistry or a soil assay. Cite [PANGAEA DOI 10.1594/PANGAEA.788537](https://doi.org/10.1594/PANGAEA.788537); CC-BY-3.0. |
| 3 — Surface thermal regime | [MODIS MOD11A2 v6.1](https://developers.google.com/earth-engine/datasets/catalog/MODIS_061_MOD11A2) | Global, 1 km, 8-day composites; native MODIS sinusoidal tiles are 1200 × 1200 km (h/v grid). | Day/night land-surface temperature with QA. Summarize multiple seasons/years; it is not Earth air temperature and has cloud/coverage gaps. |
| 3 — Reflectance / albedo proxy | [MODIS MCD43A4 v6.1](https://developers.google.com/earth-engine/datasets/catalog/MODIS_061_MCD43A4) and [MCD43A3](https://developers.google.com/earth-engine/datasets/catalog/MODIS_061_MCD43A3) | Global, 500 m, daily products from a 16-day retrieval period; native sinusoidal h/v tiles. | Nadir reflectance and black-/white-sky albedo bands. Useful surface optical proxies, not direct lunar-equivalent calibrated reflectance. |
| 4 — Land / bare-ground mask | [ESA WorldCover 2021](https://www.esa-worldcover.org/en/data-access) | 10 m; 2,651 3° × 3° map tiles, about 117 GB globally. | The prototype downloads and samples only the requested coordinate's tile. Useful to flag water, vegetation, snow/ice, built-up areas, and barren land; a mask/stratifier, not a lunar similarity score. CC-BY-4.0. |
| Context only | [ISRIC SoilGrids](https://docs.isric.org/globaldata/soilgrids/) | 250 m global layers; WebDAV global VRTs or WCS subsets. ISRIC currently reports its REST API is paused. | Earth-specific soil properties do not equal lunar regolith. Do not bulk-download these layers for the initial cross-body score. |
| Compute instead of downloading | Solar position plus a DEM-derived terrain horizon | Calculate at each candidate coordinate and requested date/time. | Potential clear-sky geometry is comparable; observed Earth surface radiation includes atmosphere/cloud effects and should not be equated directly to lunar irradiance. |

## Features with no comparable global Earth layer

Earth weather, precipitation, soil moisture, vegetation indices, population, roads, and settlements are Earth-only context and should not inflate an Earth–Moon similarity score. Earth gravity and magnetic-field models exist, but their values are context-only because the bodies' baseline environments differ. There is no globally uniform, high-resolution Earth surface-mineral map that is directly equivalent to lunar elemental assays; GLiM and MODIS reflectance are broad material/optical proxies, not chemical measurements. Terrestrial impact-crater catalogs are incomplete and strongly biased by erosion, burial, and discovery, so crater matches need a coverage caveat.

## Chunking strategy

1. Use ETOPO 2022 for global coarse terrain lookup and ETOPO5's smaller index for fast candidate prefiltering.
2. Score terrain-compatible cells and retain a broad shortlist.
3. Use `fetch_dem_tiles.py` to fetch only the needed 1° Copernicus GLO-90 tiles. Reuse each tile for every candidate inside it; `fast_earth_report.py` samples a cached tile locally.
4. For final candidates, retrieve the corresponding MODIS h/v time-series tiles, then aggregate to a common footprint and time window. The prototype samples WorldCover and GLiM locally but does not yet process MODIS; NASA MODIS granules require Earthdata authentication.
5. Cache each chunk by dataset, version, and tile ID. Record missing coverage and QA; never substitute zero for missing data.

The chunk geometry differs by dataset: Copernicus uses 1° tiles, MODIS uses a sinusoidal h/v grid, and WorldCover uses 3° tiles. The processor must reproject/aggregate each product to one declared candidate-cell system before ranking. Avoid downloading every dated MODIS image or every 10 m WorldCover tile at startup.

## Sources

- NOAA ETOPO product, resolution, and bulk downloads: [NCEI ETOPO Global Relief Model](https://www.ncei.noaa.gov/products/etopo-global-relief-model).
- Copernicus DEM coverage, COG format, tile naming, and licensing: [AWS Open Data Registry](https://registry.opendata.aws/copernicus-dem/) and [tile/readme documentation](https://copernicus-dem-30m.s3.amazonaws.com/readme.html).
- MODIS MOD11A2 and MCD43 products, pixel scale, cadence, and QA: NASA LP DAAC dataset documentation mirrored in the [Earth Engine catalog](https://developers.google.com/earth-engine/datasets/catalog/MODIS_061_MOD11A2) and [MCD43A4 catalog](https://developers.google.com/earth-engine/datasets/catalog/MODIS_061_MCD43A4).
- ESA WorldCover tile sizes, global volume, validation, and license: [WorldCover data access](https://www.esa-worldcover.org/en/data-access).
- SoilGrids resolution, uncertainty, license, and access status: [ISRIC SoilGrids documentation](https://docs.isric.org/globaldata/soilgrids/).
