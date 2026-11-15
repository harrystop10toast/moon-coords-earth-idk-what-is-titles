#!/usr/bin/env python3
"""Read a local Copernicus GLO-90 GeoTIFF tile through the system libtiff."""

from __future__ import annotations

import ctypes
import math
import statistics
import struct
from pathlib import Path

from build_index import EARTH_RADIUS_M
from fetch_dem_tiles import copdem_tile_name
from tiff_support import get_uint32, read_tiff_tile, tiff_library

NODATA = -32767.0
NEIGHBORHOOD_RADIUS = 5


def _pixel(buffer: ctypes.Array, row: int, column: int, tile_width: int) -> float:
    value = struct.unpack_from("=f", buffer, (row * tile_width + column) * 4)[0]
    if not math.isfinite(value) or value == NODATA:
        return math.nan
    return value


def sample_copdem_tile(path: Path, latitude: float, longitude: float) -> dict:
    """Sample the center pixel and a roughly 1 km neighborhood from a 1-degree GLO-90 tile."""
    expected_name = copdem_tile_name(latitude, longitude)
    if path.stem != expected_name:
        raise ValueError(f"{path.name} is not the expected tile {expected_name}.")

    lat_cell = min(math.floor(latitude), 89)
    east_longitude = ((longitude + 180) % 360) - 180
    lon_cell = math.floor(east_longitude)
    tile = tiff_library()
    tiff = tile.TIFFOpen(str(path.resolve()).encode(), b"r")
    if not tiff:
        raise OSError(f"Could not open Copernicus GeoTIFF: {path}")

    try:
        width = get_uint32(tile, tiff, 256)
        height = get_uint32(tile, tiff, 257)
        tile_width = get_uint32(tile, tiff, 322)
        tile_height = get_uint32(tile, tiff, 323)
        if width == 0 or height == 0 or tile_width == 0 or tile_height == 0:
            raise ValueError("Copernicus GeoTIFF has invalid image or tile dimensions.")
        if math.ceil(width / tile_width) * math.ceil(height / tile_height) != 1:
            raise ValueError("This reader currently supports one-tile 1-degree GLO-90 images only.")

        buffer = read_tiff_tile(tile, tiff, 0, 0, tile_width, tile_height, 4)

        column = min(width - 1, max(0, int((east_longitude - lon_cell) * width)))
        row = min(height - 1, max(0, int((lat_cell + 1 - latitude) * height)))
        elevation = _pixel(buffer, row, column, tile_width)
        pixel_x_m = (
            EARTH_RADIUS_M
            * math.radians(1 / width)
            * math.cos(math.radians(latitude))
        )
        pixel_y_m = EARTH_RADIUS_M * math.radians(1 / height)
        result = {
            "tile": expected_name,
            "image_width": width,
            "image_height": height,
            "pixel_size_x_m": pixel_x_m,
            "pixel_size_y_m": pixel_y_m,
            "pixel_row": row,
            "pixel_column": column,
            "elevation_m": None if math.isnan(elevation) else elevation,
            "neighborhood_pixels": 0,
            "local_relief_m": None,
            "roughness_std_m": None,
            "slope_deg": None,
            "aspect_deg": None,
            "curvature_km_inv": None,
        }
        radius = NEIGHBORHOOD_RADIUS
        if row < radius + 1 or column < radius + 1 or row + radius + 1 >= height or column + radius + 1 >= width:
            result["neighborhood_status"] = (
                "Not calculated: the ~1 km neighborhood crosses the 1-degree tile edge; "
                "neighboring tile data is required."
            )
            return result

        values = [
            _pixel(buffer, y, x, tile_width)
            for y in range(row - radius, row + radius + 1)
            for x in range(column - radius, column + radius + 1)
        ]
        if any(math.isnan(value) for value in values):
            result["neighborhood_status"] = (
                "Not calculated: the ~1 km neighborhood contains no-data pixels."
            )
            return result

        north = _pixel(buffer, row - 1, column, tile_width)
        south = _pixel(buffer, row + 1, column, tile_width)
        west = _pixel(buffer, row, column - 1, tile_width)
        east = _pixel(buffer, row, column + 1, tile_width)
        northwest = _pixel(buffer, row - 1, column - 1, tile_width)
        northeast = _pixel(buffer, row - 1, column + 1, tile_width)
        southwest = _pixel(buffer, row + 1, column - 1, tile_width)
        southeast = _pixel(buffer, row + 1, column + 1, tile_width)
        dx = (east - west) / (2 * pixel_x_m)
        dy = (north - south) / (2 * pixel_y_m)
        dxx = (east - 2 * elevation + west) / pixel_x_m**2
        dyy = (north - 2 * elevation + south) / pixel_y_m**2
        dxy = (northeast - northwest - southeast + southwest) / (4 * pixel_x_m * pixel_y_m)
        slope_squared = dx * dx + dy * dy
        curvature = (
            (1 + dy * dy) * dxx - 2 * dx * dy * dxy + (1 + dx * dx) * dyy
        ) / (2 * (1 + slope_squared) ** 1.5)
        result.update(
            {
                "neighborhood_pixels": len(values),
                "local_relief_m": max(values) - min(values),
                "roughness_std_m": statistics.pstdev(values),
                "slope_deg": math.degrees(math.atan(math.hypot(dx, dy))),
                "aspect_deg": math.degrees(math.atan2(-dx, -dy)) % 360,
                "curvature_km_inv": curvature * 1000,
                "neighborhood_status": "11×11 pixels (~1 km footprint); DSM includes vegetation and structures.",
            }
        )
        return result
    finally:
        tile.TIFFClose(tiff)
