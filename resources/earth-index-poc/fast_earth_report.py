#!/usr/bin/env python3
"""Generate a fast, local-only Earth coordinate report from the terrain index."""

from __future__ import annotations

import argparse
import json
import math
import os
import sqlite3
import tempfile
import uuid
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path

from copdem_reader import sample_copdem_tile
from etopo2022_reader import sample_etopo2022
from fetch_dem_tiles import copdem_tile_name, fetch_tile, get_tile_list
from fetch_glim import download_glim
from fetch_worldcover import fetch_worldcover_for_point, worldcover_tile_name
from glim_reader import sample_glim
from worldcover_reader import sample_worldcover_tile

ROOT = Path(__file__).resolve().parent
DATA_DIR = ROOT / "data"
INDEX_PATH = DATA_DIR / "earth-terrain-index.sqlite3"
REPORT_PATH = DATA_DIR / "earth-fast-reports.json"
DEM_DIR = DATA_DIR / "copernicus-dem-90m"
ETOPO2022_PATH = DATA_DIR / "etopo2022/ETOPO_2022_v1_60s_N90W180_surface.tif"
WORLDCOVER_DIR = DATA_DIR / "worldcover-2021"
GLIM_DIR = DATA_DIR / "glim"
GLIM_RASTER_PATH = GLIM_DIR / "glim_wgs84_0point5deg.txt.asc"
GLIM_LEGEND_PATH = GLIM_DIR / "Classnames.txt"
EARTH_RADIUS_M = 6_371_008.8
GRID_ROWS = 1_080
GRID_COLUMNS = 2_160
CELL_SIZE_DEGREES = 1 / 6
FIRST_LATITUDE_CENTER = 90 - CELL_SIZE_DEGREES / 4
FIRST_LONGITUDE_CENTER = CELL_SIZE_DEGREES / 4


def validate_coordinate(latitude: float, longitude: float) -> tuple[float, float]:
    if not math.isfinite(latitude) or not -90 <= latitude <= 90:
        raise ValueError("Latitude must be finite and between -90 and 90.")
    if not math.isfinite(longitude) or not -180 <= longitude <= 180:
        raise ValueError("Longitude must be finite and between -180 and 180.")
    return (0.0 if latitude == 0 else latitude, 0.0 if longitude == 0 else longitude)


def cell_location(latitude: float, longitude: float) -> tuple[int, int, float, float]:
    """Return nearest coarse ETOPO5 index row, column, and cell-center coordinates."""
    latitude, longitude = validate_coordinate(latitude, longitude)
    row = round((FIRST_LATITUDE_CENTER - latitude) / CELL_SIZE_DEGREES)
    east_longitude = longitude % 360
    column = round((east_longitude - FIRST_LONGITUDE_CENTER) / CELL_SIZE_DEGREES)
    row = min(max(row, 0), GRID_ROWS - 1)
    column %= GRID_COLUMNS
    center_latitude = FIRST_LATITUDE_CENTER - row * CELL_SIZE_DEGREES
    center_longitude = (FIRST_LONGITUDE_CENTER + column * CELL_SIZE_DEGREES) % 360
    return row, column, center_latitude, center_longitude


def load_cell(index_path: Path, row: int, column: int) -> tuple[dict | None, dict]:
    cell_id = row * GRID_COLUMNS + column
    uri = index_path.resolve().as_uri() + "?mode=ro"
    with closing(sqlite3.connect(uri, uri=True)) as db:
        metadata = dict(db.execute("SELECT key, value FROM metadata"))
        row_data = db.execute(
            """
            SELECT latitude_deg, longitude_east_deg, land_fraction,
                   mean_elevation_m, slope_deg, local_relief_m, roughness_std_m
            FROM earth_cells WHERE id = ?
            """,
            (cell_id,),
        ).fetchone()
        if row_data is None:
            return None, metadata
        columns = (
            "latitude_deg",
            "longitude_east_deg",
            "land_fraction",
            "mean_elevation_m",
            "slope_deg",
            "local_relief_m",
            "roughness_std_m",
        )
        cell = dict(zip(columns, row_data))
        cell["id"] = cell_id
        return cell, metadata


def measurement(key: str, label: str, value: str) -> dict[str, str]:
    return {"key": key, "label": label, "value": value}


def metric_section(key: str, label: str, measurements: list[dict[str, str]]) -> dict:
    return {"key": key, "label": label, "measurements": measurements}


def source_status(name: str, state: str, status: str) -> dict[str, str]:
    return {"name": name, "state": state, "status": status}


def local_topographic_class(slope: float | None, relief: float, basis: str) -> str:
    if slope is None:
        return "Unavailable; neighboring terrain samples are missing."
    if slope < 5 and relief < 100:
        classification = "Low-relief plains"
    elif slope < 15 and relief < 500:
        classification = "Hills or rolling terrain"
    else:
        classification = "Steep or rugged terrain"
    return f"{classification} ({basis} heuristic; not a mapped landform)"


def local_copdem_sample(
    latitude: float, longitude: float, dem_dir: Path
) -> tuple[dict | None, str, str]:
    tile_name = copdem_tile_name(latitude, longitude)
    tile_path = dem_dir / f"{tile_name}.tif"
    if not tile_path.is_file():
        return (
            None,
            "NOT_DOWNLOADED",
            f"No local tile. Fetch only this 1° tile with "
            f"`python3 fetch_dem_tiles.py --point {latitude:.6f} {longitude:.6f}`.",
        )
    try:
        sample = sample_copdem_tile(tile_path, latitude, longitude)
    except (OSError, RuntimeError, ValueError) as error:
        return None, "ERROR", f"Local tile could not be sampled: {error}"
    if sample["elevation_m"] is None:
        return sample, "EMPTY", "The local tile has no valid elevation at this coordinate."
    status = f"Sampled local GLO-90 tile {tile_name}; 90 m class DSM."
    if sample["neighborhood_pixels"] == 121:
        status += " 11×11 neighborhood used for roughly 1 km terrain metrics."
    else:
        status += f' Local terrain metrics unavailable: {sample["neighborhood_status"]}'
    return sample, "DATA", status


def download_copdem_if_requested(
    latitude: float, longitude: float, dem_dir: Path
) -> tuple[str | None, str | None]:
    tile_name = copdem_tile_name(latitude, longitude)
    if (dem_dir / f"{tile_name}.tif").is_file():
        return None, None
    try:
        if tile_name not in get_tile_list():
            return "NOT_AVAILABLE", f"No public GLO-90 tile listed for {tile_name}."
        _, status = fetch_tile(tile_name, dem_dir)
        return None, status
    except (OSError, RuntimeError, ValueError) as error:
        return "NETWORK_ERROR", f"Could not download GLO-90 tile {tile_name}: {error}"


def local_etopo2022_sample(
    latitude: float, longitude: float, raster_path: Path
) -> tuple[dict | None, str, str]:
    if not raster_path.is_file():
        return (
            None,
            "NOT_DOWNLOADED",
            "Global raster not present. Download the one-time 466 MB source with `python3 fetch_etopo2022.py`.",
        )
    try:
        sample = sample_etopo2022(raster_path, latitude, longitude)
    except (OSError, RuntimeError, ValueError) as error:
        return None, "ERROR", f"Local ETOPO 2022 raster could not be sampled: {error}"
    if sample["elevation_m"] is None:
        return sample, "EMPTY", "ETOPO 2022 has no valid sample at this coordinate."
    return (
        sample,
        "DATA",
        "Sampled local NOAA ETOPO 2022 60-arc-second surface raster; EGM2008 height datum.",
    )


def local_worldcover_sample(
    latitude: float, longitude: float, worldcover_dir: Path
) -> tuple[dict | None, str, str]:
    tile_name = worldcover_tile_name(latitude, longitude)
    tile_path = (
        worldcover_dir
        / f"ESA_WorldCover_10m_2021_v200_{tile_name}_Map.tif"
    )
    if not tile_path.is_file():
        return (
            None,
            "NOT_DOWNLOADED",
            f"No local WorldCover tile. Fetch it with `python3 fast_earth_report.py "
            f"{latitude:.6f} {longitude:.6f} --download-worldcover`.",
        )
    try:
        sample = sample_worldcover_tile(tile_path, latitude, longitude)
    except (OSError, RuntimeError, ValueError) as error:
        return None, "ERROR", f"Local WorldCover tile could not be sampled: {error}"
    if sample["class_value"] is None:
        return sample, "EMPTY", "WorldCover has no valid map class at this coordinate."
    return (
        sample,
        "DATA",
        f"Sampled local ESA WorldCover 2021 v200 10 m tile {tile_name}; "
        "land-cover classification, not geology; 76.7% global validation accuracy is not point-level confidence.",
    )


def download_worldcover_if_requested(
    latitude: float, longitude: float, worldcover_dir: Path
) -> tuple[str | None, str | None]:
    tile_name = worldcover_tile_name(latitude, longitude)
    tile_path = worldcover_dir / f"ESA_WorldCover_10m_2021_v200_{tile_name}_Map.tif"
    if tile_path.is_file():
        return None, None
    try:
        _, status = fetch_worldcover_for_point(latitude, longitude, worldcover_dir)
        return None, status
    except (OSError, RuntimeError, ValueError) as error:
        return "NETWORK_ERROR", f"Could not download WorldCover tile {tile_name}: {error}"


def local_glim_sample(
    latitude: float, longitude: float, glim_dir: Path
) -> tuple[dict | None, str, str]:
    raster_path = glim_dir / GLIM_RASTER_PATH.name
    legend_path = glim_dir / GLIM_LEGEND_PATH.name
    if not raster_path.is_file() or not legend_path.is_file():
        return (
            None,
            "NOT_DOWNLOADED",
            "Local GLiM raster is missing. Fetch the small global 0.5° data file with "
            "`python3 fast_earth_report.py <latitude> <longitude> --download-glim`.",
        )
    try:
        sample = sample_glim(raster_path, legend_path, latitude, longitude)
    except (OSError, RuntimeError, ValueError) as error:
        return None, "ERROR", f"Local GLiM raster could not be sampled: {error}"
    if sample["class_code"] is None:
        return sample, "EMPTY", "GLiM has no mapped lithology for this 0.5° cell."
    return (
        sample,
        "DATA",
        "Sampled GLiM v1.0 global lithology grid at 0.5° resolution; coarse mapped rock type only.",
    )


def download_glim_if_requested(glim_dir: Path) -> tuple[str | None, str | None]:
    if (glim_dir / GLIM_RASTER_PATH.name).is_file() and (
        glim_dir / GLIM_LEGEND_PATH.name
    ).is_file():
        return None, None
    try:
        return None, download_glim(glim_dir)
    except (OSError, RuntimeError, ValueError) as error:
        return "NETWORK_ERROR", f"Could not download GLiM data: {error}"


def build_report(
    latitude: float,
    longitude: float,
    index_path: Path,
    dem_dir: Path = DEM_DIR,
    download_dem: bool = False,
    etopo_path: Path = ETOPO2022_PATH,
    worldcover_dir: Path = WORLDCOVER_DIR,
    download_worldcover: bool = False,
    glim_dir: Path = GLIM_DIR,
    download_glim_data: bool = False,
) -> dict:
    latitude, longitude = validate_coordinate(latitude, longitude)
    if not index_path.is_file():
        raise FileNotFoundError(
            f"Local terrain index not found: {index_path}. "
            "Build it once with `python3 build_index.py`."
        )
    row, column, center_latitude, center_longitude = cell_location(latitude, longitude)
    cell, metadata = load_cell(index_path, row, column)
    download_state, download_status = (
        download_copdem_if_requested(latitude, longitude, dem_dir)
        if download_dem
        else (None, None)
    )
    if download_state is None:
        copdem, copdem_state, copdem_status = local_copdem_sample(
            latitude, longitude, dem_dir
        )
    else:
        copdem, copdem_state, copdem_status = None, download_state, download_status
    etopo2022, etopo_state, etopo_status = local_etopo2022_sample(
        latitude, longitude, etopo_path
    )
    worldcover_download_state, worldcover_download_status = (
        download_worldcover_if_requested(latitude, longitude, worldcover_dir)
        if download_worldcover
        else (None, None)
    )
    if worldcover_download_state is None:
        worldcover, worldcover_state, worldcover_status = local_worldcover_sample(
            latitude, longitude, worldcover_dir
        )
    else:
        worldcover, worldcover_state, worldcover_status = (
            None,
            worldcover_download_state,
            worldcover_download_status or "WorldCover download failed.",
        )
    glim_download_state, glim_download_status = (
        download_glim_if_requested(glim_dir) if download_glim_data else (None, None)
    )
    if glim_download_state is None:
        glim, glim_state, glim_status = local_glim_sample(
            latitude, longitude, glim_dir
        )
    else:
        glim, glim_state, glim_status = (
            None,
            glim_download_state,
            glim_download_status or "GLiM download failed.",
        )
    timestamp = datetime.now(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")
    cell_description = (
        f"Nearest ETOPO5 index cell centered at {center_latitude:.5f}°, "
        f"{center_longitude:.5f}°E; 10-arc-minute cell, coarse regional summary."
    )

    if cell is None:
        coarse_measurements = [
            measurement(
                "coarse_elevation_etopo5",
                "Coarse elevation (NOAA ETOPO5)",
                "Unavailable: no land candidate in the ETOPO5 index cell. "
                "This may be water or a mixed coastal cell; it is not a high-resolution land/water classification.",
            ),
            measurement("coarse_slope_etopo5", "Coarse slope (NOAA ETOPO5)", "Unavailable: no indexed land cell."),
            measurement("coarse_roughness_etopo5", "Coarse roughness (NOAA ETOPO5)", "Unavailable: no indexed land cell."),
            measurement("coarse_relief_etopo5", "Coarse relief (NOAA ETOPO5)", "Unavailable: no indexed land cell."),
        ]
        terrain_data = {"cell_id": row * GRID_COLUMNS + column, "land_candidate": False}
        terrain_state = "EMPTY"
        terrain_status = "No land candidate at this coarse index cell; no remote request was made."
    else:
        slope = cell["slope_deg"]
        coarse_measurements = [
            measurement(
                "coarse_elevation_etopo5",
                "Coarse elevation (NOAA ETOPO5)",
                f'{cell["mean_elevation_m"]:.2f} m above mean sea level ({cell_description})',
            ),
            measurement(
                "coarse_slope_etopo5",
                "Coarse slope (NOAA ETOPO5)",
                "Unavailable at this cell; neighboring coarse land-cell samples are incomplete."
                if slope is None
                else f"{slope:.2f}° (neighboring 10-arc-minute ETOPO5 cell means)",
            ),
            measurement(
                "coarse_roughness_etopo5",
                "Coarse roughness (NOAA ETOPO5)",
                f'{cell["roughness_std_m"]:.2f} m (four 5-arc-minute samples within a 10-arc-minute cell)',
            ),
            measurement(
                "coarse_relief_etopo5",
                "Coarse relief (NOAA ETOPO5)",
                f'{cell["local_relief_m"]:.2f} m (four samples within a 10-arc-minute cell)',
            ),
        ]
        terrain_data = {"cell_id": cell["id"], "land_candidate": True, **cell}
        terrain_state = "DATA"
        terrain_status = (
            f'Local NOAA ETOPO5 index; {cell["land_fraction"]:.0%} land fraction, '
            "10-arc-minute candidate cell. No point APIs were called."
        )

    terrain_measurements = []
    copdem_has_elevation = copdem is not None and copdem["elevation_m"] is not None
    copdem_has_neighborhood = (
        copdem_has_elevation and copdem["neighborhood_pixels"] == 121
    )
    etopo_has_elevation = etopo2022 is not None and etopo2022["elevation_m"] is not None
    etopo_has_neighborhood = etopo_has_elevation and etopo2022["neighborhood_pixels"] == 25
    if copdem_has_elevation:
        terrain_measurements.append(
            measurement(
                "elevation_copernicus_glo90",
                "Elevation (Copernicus GLO-90)",
                f'{copdem["elevation_m"]:.2f} m (nearest GLO-90 pixel; '
                f'pixel spacing about {copdem["pixel_size_x_m"]:.1f}×{copdem["pixel_size_y_m"]:.1f} m; '
                "surface model, not bare-earth terrain)",
            )
        )
    elif etopo_has_elevation:
        terrain_measurements.append(
            measurement(
                "elevation_etopo2022",
                "Elevation (NOAA ETOPO 2022)",
                f'{etopo2022["elevation_m"]:.2f} m (nearest 1-arc-minute grid node; '
                "EGM2008 height datum, global land/sea-surface relief)",
            )
        )
    elif cell is not None:
        terrain_measurements.append(
            measurement(
                "elevation_etopo5",
                "Elevation (NOAA ETOPO5)",
                f'{cell["mean_elevation_m"]:.2f} m above mean sea level ({cell_description})',
            )
        )
    else:
        terrain_measurements.append(
            measurement(
                "elevation",
                "Elevation",
                "Unavailable: no valid local DEM sample or indexed ETOPO5 land cell.",
            )
        )

    if copdem_has_neighborhood:
        metrics_source = copdem
        metrics_label = "Copernicus GLO-90"
        metrics_key = "copernicus_glo90"
        metrics_scale = "~1 km 11×11 neighborhood; DSM includes vegetation/structures"
    elif etopo_has_neighborhood:
        metrics_source = etopo2022
        metrics_label = "NOAA ETOPO 2022"
        metrics_key = "etopo2022"
        metrics_scale = "~9 km 5×5 one-arc-minute regional neighborhood"
    elif cell is not None:
        metrics_source = cell
        metrics_label = "NOAA ETOPO5"
        metrics_key = "etopo5"
        metrics_scale = "10-arc-minute coarse cells (~18 km north-south)"
    else:
        metrics_source = None
        metrics_label = "Unavailable"
        metrics_key = "unavailable"
        metrics_scale = "terrain samples unavailable"

    if metrics_source is None:
        slope = aspect = roughness = relief = curvature = None
    elif metrics_key == "copernicus_glo90":
        slope = metrics_source["slope_deg"]
        aspect = metrics_source["aspect_deg"]
        roughness = metrics_source["roughness_std_m"]
        relief = metrics_source["local_relief_m"]
        curvature = metrics_source["curvature_km_inv"]
    elif metrics_key == "etopo2022":
        slope = metrics_source["slope_deg"]
        aspect = metrics_source["aspect_deg"]
        roughness = metrics_source["roughness_std_m"]
        relief = metrics_source["local_relief_m"]
        curvature = metrics_source["curvature_km_inv"]
    else:
        slope = metrics_source["slope_deg"]
        aspect = None
        roughness = metrics_source["roughness_std_m"]
        relief = metrics_source["local_relief_m"]
        curvature = None

    terrain_measurements.extend(
        [
            measurement(
                f"slope_{metrics_key}",
                f"Slope ({metrics_label})",
                "Unavailable: terrain neighborhood is incomplete."
                if slope is None
                else f"{slope:.2f}° ({metrics_scale})",
            ),
            measurement(
                f"aspect_{metrics_key}",
                f"Downslope aspect ({metrics_label})",
                "Unavailable: aspect is not derived from this data source."
                if aspect is None
                else f"{aspect:.2f}° clockwise from north ({metrics_scale})",
            ),
            measurement(
                f"roughness_{metrics_key}",
                f"Surface roughness ({metrics_label})",
                "Unavailable: terrain neighborhood is incomplete."
                if roughness is None
                else f"{roughness:.2f} m standard deviation ({metrics_scale})",
            ),
            measurement(
                f"relief_{metrics_key}",
                f"Local relief ({metrics_label})",
                "Unavailable: terrain neighborhood is incomplete."
                if relief is None
                else f"{relief:.2f} m ({metrics_scale})",
            ),
            measurement(
                f"curvature_{metrics_key}",
                f"Terrain curvature ({metrics_label})",
                "Unavailable: curvature is not derived from this data source."
                if curvature is None
                else f"{curvature:.4f} km⁻¹ (local finite-difference estimate)",
            ),
        ]
    )
    terrain_measurements.append(
        measurement(
            "local_topographic_class",
            "Local topographic class",
            "Not assigned: the coarse ETOPO5 index has no land candidate here; "
            "the ETOPO 2022 sample may be bathymetry, not a landform."
            if cell is None and not copdem_has_elevation
            else "Unavailable: no terrain neighborhood is available."
            if slope is None or relief is None
            else local_topographic_class(slope, relief, metrics_scale),
        )
    )
    if metrics_key != "etopo5":
        terrain_measurements.extend(coarse_measurements)
    terrain_measurements.append(
        measurement(
            "terrain_scale_note",
            "Terrain scale / method",
            metrics_scale,
        )
    )
    terrain_data["copernicus_glo90"] = copdem
    terrain_data["etopo2022_surface"] = etopo2022

    unavailable = "Not gathered in fast-local mode; see recommended downloads."
    land_cover_value = (
        f'{worldcover["class_name"]} (ESA WorldCover 2021 v200, '
        "10 m map; nearest pixel; 76.7% global validation accuracy, not point-level confidence)"
        if worldcover and worldcover["class_value"] is not None
        else worldcover_status
    )
    if worldcover and worldcover["dominant_class_value"] is not None:
        class_mix = ", ".join(
            f'{item["class_name"]} {item["fraction"]:.0%}'
            for item in worldcover["neighborhood_mix"][:3]
        )
        land_cover_mix = (
            f'{class_mix} ({worldcover["neighborhood_pixels"]} valid samples; approximately '
            f'{worldcover["pixel_size_x_m"] * 11:.0f} × '
            f'{worldcover["pixel_size_y_m"] * 11:.0f} m)'
        )
    else:
        land_cover_mix = worldcover_status
    mapped_lithology = (
        f'{glim["class_name"]} (GLiM v1.0; 0.5° grid cell centered at '
        f'{glim["cell_center_latitude_deg"]:.2f}°, '
        f'{glim["cell_center_longitude_deg"]:.2f}°; broad mapped lithology, '
        "not point mineralogy)"
        if glim and glim["class_name"]
        else glim_status
    )
    sections = [
        metric_section("elevation_and_terrain", "Elevation and terrain", terrain_measurements),
        metric_section(
            "geology_and_surface_materials",
            "Geology and surface materials",
            [
                measurement("mapped_lithology", "Mapped lithology (GLiM)", mapped_lithology),
                measurement(
                    "surface_land_cover",
                    "Surface land cover (ESA WorldCover)",
                    land_cover_value,
                ),
                measurement(
                    "local_land_cover_mix",
                    "Local land-cover mix",
                    land_cover_mix,
                ),
                measurement("soil_proxies", "Soil properties", unavailable),
                measurement(
                    "elemental_composition",
                    "Elemental composition / direct mineralogy",
                    "No globally consistent Earth surface assay directly comparable to lunar elemental maps is configured.",
                ),
            ],
        ),
        metric_section(
            "temperature_and_solar",
            "Temperature and solar environment",
            [
                measurement("surface_temperature", "Historical surface temperature", unavailable),
                measurement("surface_albedo", "Surface albedo / reflectance", unavailable),
                measurement(
                    "solar_geometry",
                    "Solar geometry",
                    "Not gathered; can be calculated locally for a requested date without downloading a global dataset.",
                ),
            ],
        ),
        metric_section(
            "water_and_resources",
            "Water and resources",
            [
                measurement("surface_water", "Surface-water features", unavailable),
                measurement("soil_moisture", "Soil moisture", unavailable),
                measurement(
                    "volatile_resources",
                    "Resources comparable to lunar volatiles",
                    "No direct Earth counterpart; terrestrial water/soil products are context, not a lunar resource analogue.",
                ),
            ],
        ),
        metric_section(
            "impact_and_subsurface",
            "Impact and subsurface features",
            [
                measurement("crater_distance", "Distance to known impact crater", unavailable),
                measurement("caves", "Caves or underground features", unavailable),
            ],
        ),
        metric_section(
            "gravity_and_magnetics",
            "Gravity and magnetics",
            [
                measurement("gravity_anomaly", "Local gravity anomaly", unavailable),
                measurement("magnetic_field", "Magnetic field", unavailable),
            ],
        ),
    ]
    downloads = [
        {
            "dataset": "NOAA ETOPO 2022 60-arc-second surface GeoTIFF",
            "role": "Global terrain and bathymetry fallback",
            "chunk": "One global ~466 MB raster",
            "url": "https://www.ncei.noaa.gov/products/etopo-global-relief-model",
            "status": (
                "Local raster sampled by this report; EGM2008 height datum."
                if etopo_state == "DATA"
                else f"{etopo_status} Run `python3 fetch_etopo2022.py`."
                if etopo_state == "NOT_DOWNLOADED"
                else etopo_status
            ),
        },
        {
            "dataset": "Copernicus DEM GLO-90",
            "role": "Higher-resolution candidate terrain",
            "chunk": "One 1° tile per candidate area",
            "url": "https://registry.opendata.aws/copernicus-dem/",
            "status": (
                "Sampled automatically when its local tile exists; otherwise fetch the candidate's 1° tile."
                if copdem_state == "DATA"
                else copdem_status
            ),
        },
        {
            "dataset": "MODIS MOD11A2 v6.1",
            "role": "Historical day/night land-surface temperature",
            "chunk": "NASA LP DAAC sinusoidal h/v tiles",
            "url": "https://lpdaac.usgs.gov/products/mod11a2v061/",
            "status": (
                "Not downloaded: NASA data granules require Earthdata authentication; "
                "no account credentials are configured. No local sampler is implemented yet."
            ),
        },
        {
            "dataset": "MODIS MCD43A3 v6.1",
            "role": "Albedo proxy",
            "chunk": "NASA LP DAAC sinusoidal h/v tiles",
            "url": "https://lpdaac.usgs.gov/products/mcd43a3v061/",
            "status": (
                "Not downloaded: NASA data granules require Earthdata authentication; "
                "no account credentials are configured. No local sampler is implemented yet."
            ),
        },
        {
            "dataset": "GLiM Global Lithological Map",
            "role": "Broad mapped rock type",
            "chunk": "One global ~38.7 kB archive; 0.5° grid",
            "url": "https://doi.org/10.1594/PANGAEA.788537",
            "status": (
                "Local 0.5° lithology cell sampled."
                if glim_state == "DATA"
                else glim_status
            ),
        },
        {
            "dataset": "ESA WorldCover 2021",
            "role": "Land-cover mask for shortlisted areas",
            "chunk": "3° × 3° tiles",
            "url": "https://www.esa-worldcover.org/en/data-access",
            "status": (
                "Local 10 m land-cover tile sampled."
                if worldcover_state == "DATA"
                else worldcover_status
            ),
        },
    ]
    sources = [
        source_status("NOAA ETOPO5 local terrain index", terrain_state, terrain_status),
        source_status("NOAA ETOPO 2022 60-arc-second raster", etopo_state, etopo_status),
        source_status(
            "Copernicus DEM GLO-90",
            copdem_state,
            copdem_status,
        ),
        source_status("ESA WorldCover 2021 v200", worldcover_state, worldcover_status),
        source_status(
            "MODIS thermal/albedo",
            "NOT_DOWNLOADED",
            "NASA data granules require Earthdata authentication; no credentials or local sampler are configured.",
        ),
        source_status("GLiM geology", glim_state, glim_status),
        source_status(
            "Earth point APIs",
            "NOT_QUERIED",
            "Fast-local mode makes no live API requests; weather, current conditions, and catalog lookups are omitted.",
        ),
    ]
    report_output = {
        "format_version": 1,
        "title": f"FAST EARTH SITE REPORT — {latitude:.5f}, {longitude:.5f}",
        "summary": (
            "Local-data report. No live point APIs are queried; --download-dem, "
            "--download-worldcover, and --download-glim fetch only the required data. "
            + (
            "Copernicus GLO-90 local terrain is sampled where its tile is cached; ETOPO 2022 supplies global context."
                if copdem_has_elevation
            else "ETOPO 2022 supplies global terrain when its raster is present; ETOPO5 is the coarse fallback. Unavailable fields are explicitly identified."
            if etopo_has_elevation
            else "Available terrain falls back to coarse ETOPO5 summaries; unavailable fields are explicitly identified."
            )
        ),
        "metric_sections": sections,
        "additional_context": [
            {
                "key": "fast_mode",
                "label": "Fast mode",
                "value": "SQLite point lookup; typically completes in under a second after the one-time index build.",
            },
            {
                "key": "terrain_resolution",
                "label": "Terrain resolution caveat",
                "value": (
                    "Copernicus GLO-90 supplies DSM values at ~90 m; 11×11 pixels (~1 km) are used when the neighborhood is available. Vegetation/buildings affect heights."
                    if metrics_key == "copernicus_glo90"
                    else "ETOPO 2022 is a global 1-arc-minute raster; its 5×5 terrain neighborhood spans ~9 km, not landing scale."
                    if metrics_key == "etopo2022"
                    else "ETOPO5 source samples are 5 arc-minutes; this index aggregates them to 10 arc-minute cells (roughly 18 km north-south). Not landing-scale terrain."
                ),
            },
            {
                "key": "land_cover_quality",
                "label": "Land-cover quality caveat",
                "value": "ESA reports 76.7% overall validation accuracy for the 2021 map; this is not a confidence score for this coordinate.",
            },
            {
                "key": "recommended_downloads",
                "label": "Data to download for broader coverage",
                "value": "See report_output.recommended_downloads for sources, chunk sizes, and current integration status.",
            },
        ],
        "source_status": sources,
        "recommended_downloads": downloads,
    }
    data = {
        "dataset": metadata.get("dataset", "NOAA ETOPO5"),
        "source_url": metadata.get("source_url", "https://www.ncei.noaa.gov/products/etopo-global-relief-model"),
        "source_resolution": metadata.get("source_resolution", "5 arc-minutes"),
        "cell_size": "10 arc-minutes",
        "latitude_deg": latitude,
        "longitude_deg": longitude,
        "cell_center_latitude_deg": center_latitude,
        "cell_center_longitude_east_deg": center_longitude,
        "terrain_metrics_source": metrics_key,
        "terrain": terrain_data,
        "worldcover": worldcover,
        "glim": glim,
    }
    report = {
        "body": "earth",
        "id": str(uuid.uuid4()),
        "latitude": latitude,
        "longitude": longitude,
        "coordinate": {
            "latitude_deg": latitude,
            "longitude_deg": longitude,
            "longitude_convention": "signed_minus_180_to_180",
        },
        "generated_at_utc": timestamp,
        "report_output": report_output,
        "data": data,
        "sources": sources,
    }
    return report


def report_key(report: dict) -> tuple[float, float] | None:
    if report.get("body") != "earth":
        return None
    coordinate = report.get("coordinate", {})
    try:
        return validate_coordinate(
            float(coordinate["latitude_deg"]), float(coordinate["longitude_deg"])
        )
    except (KeyError, TypeError, ValueError):
        return None


def persist_report(path: Path, report: dict, refresh: bool = False) -> dict:
    records: list[dict] = []
    if path.exists():
        with path.open(encoding="utf-8") as existing:
            store = json.load(existing)
        if not isinstance(store, dict) or not isinstance(store.get("reports"), list):
            raise ValueError(f"{path} must contain a JSON object with a reports array.")
        records = [item for item in store["reports"] if isinstance(item, dict)]

    key = report_key(report)
    for index, saved in enumerate(records):
        if report_key(saved) == key:
            if not refresh:
                return saved
            records[index] = report
            break
    else:
        records.append(report)

    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=path.parent,
            prefix=f"{path.name}.",
            suffix=".tmp",
            delete=False,
        ) as temporary:
            temporary_path = Path(temporary.name)
            json.dump({"format_version": 1, "reports": records}, temporary, indent=2)
            temporary.write("\n")
        os.replace(temporary_path, path)
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("latitude", type=float)
    parser.add_argument("longitude", type=float)
    parser.add_argument("--index", type=Path, default=INDEX_PATH, help="local SQLite terrain index")
    parser.add_argument("--output", type=Path, default=REPORT_PATH, help="JSON report store")
    parser.add_argument("--dem-dir", type=Path, default=DEM_DIR, help="directory containing downloaded GLO-90 tiles")
    parser.add_argument("--etopo2022", type=Path, default=ETOPO2022_PATH, help="local ETOPO 2022 60-arc-second raster")
    parser.add_argument(
        "--worldcover-dir",
        type=Path,
        default=WORLDCOVER_DIR,
        help="directory containing downloaded ESA WorldCover tiles",
    )
    parser.add_argument(
        "--glim-dir",
        type=Path,
        default=GLIM_DIR,
        help="directory containing the GLiM raster and class legend",
    )
    parser.add_argument(
        "--download-dem",
        action="store_true",
        help="download the coordinate's public GLO-90 tile if it is not cached",
    )
    parser.add_argument(
        "--download-worldcover",
        action="store_true",
        help="download the coordinate's public WorldCover tile if it is not cached",
    )
    parser.add_argument(
        "--download-glim",
        action="store_true",
        help="download and unpack the global public GLiM 0.5-degree raster if missing",
    )
    parser.add_argument("--refresh", action="store_true", help="replace a saved report for this coordinate")
    args = parser.parse_args()

    try:
        report = build_report(
            args.latitude,
            args.longitude,
            args.index,
            args.dem_dir,
            args.download_dem,
            args.etopo2022,
            args.worldcover_dir,
            args.download_worldcover,
            args.glim_dir,
            args.download_glim,
        )
        report = persist_report(
            args.output,
            report,
            args.refresh
            or args.download_dem
            or args.download_worldcover
            or args.download_glim,
        )
    except (FileNotFoundError, OSError, ValueError, sqlite3.Error, json.JSONDecodeError) as error:
        parser.error(str(error))
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
