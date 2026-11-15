#!/usr/bin/env python3
"""Sample the global NOAA ETOPO 2022 60-arc-second surface GeoTIFF."""

from __future__ import annotations

import ctypes
import math
import statistics
import struct
from pathlib import Path

from build_index import EARTH_RADIUS_M
from tiff_support import get_uint16, get_uint32, read_tiff_tile, tiff_library

WIDTH = 21_600
HEIGHT = 10_800
PIXEL_SIZE_DEGREES = 1 / 60
NODATA = -99_999.0
NEIGHBORHOOD_RADIUS = 2


def sample_etopo2022(path: Path, latitude: float, longitude: float) -> dict:
    if not path.is_file():
        raise FileNotFoundError(f"ETOPO 2022 raster not found: {path}")
    if not math.isfinite(latitude) or not -90 <= latitude <= 90:
        raise ValueError("Latitude must be finite and between -90 and 90.")
    if not math.isfinite(longitude) or not -180 <= longitude <= 180:
        raise ValueError("Longitude must be finite and between -180 and 180.")

    library = tiff_library()
    tiff = library.TIFFOpen(str(path.resolve()).encode(), b"r")
    if not tiff:
        raise OSError(f"Could not open ETOPO 2022 GeoTIFF: {path}")
    try:
        width = get_uint32(library, tiff, 256)
        height = get_uint32(library, tiff, 257)
        tile_width = get_uint32(library, tiff, 322)
        tile_height = get_uint32(library, tiff, 323)
        bits_per_sample = get_uint16(library, tiff, 258)
        sample_format = get_uint16(library, tiff, 339)
        if (width, height) != (WIDTH, HEIGHT):
            raise ValueError(
                f"Unexpected ETOPO 2022 raster dimensions: {width}×{height}."
            )
        if bits_per_sample != 32 or sample_format != 3:
            raise ValueError("ETOPO 2022 raster must contain 32-bit floating-point samples.")

        column = round((longitude + 180) / PIXEL_SIZE_DEGREES) % width
        row = min(height - 1, max(0, round((90 - latitude) / PIXEL_SIZE_DEGREES)))
        cache: dict[tuple[int, int], ctypes.Array] = {}

        def pixel(pixel_row: int, pixel_column: int) -> float:
            pixel_column %= width
            if not 0 <= pixel_row < height:
                return math.nan
            tile_x = pixel_column // tile_width * tile_width
            tile_y = pixel_row // tile_height * tile_height
            tile_key = (tile_x, tile_y)
            if tile_key not in cache:
                cache[tile_key] = read_tiff_tile(
                    library, tiff, tile_x, tile_y, tile_width, tile_height, 4
                )
            tile_buffer = cache[tile_key]
            local_row = pixel_row - tile_y
            local_column = pixel_column - tile_x
            value = struct.unpack_from(
                "=f", tile_buffer, (local_row * tile_width + local_column) * 4
            )[0]
            return value if math.isfinite(value) and value != NODATA else math.nan

        elevation = pixel(row, column)
        result = {
            "elevation_m": None if math.isnan(elevation) else elevation,
            "latitude_deg": latitude,
            "longitude_deg": longitude,
            "sample_latitude_deg": 90 - row * PIXEL_SIZE_DEGREES,
            "sample_longitude_deg": ((-180 + column * PIXEL_SIZE_DEGREES + 180) % 360) - 180,
            "pixel_size_degrees": PIXEL_SIZE_DEGREES,
            "pixel_size_y_m": EARTH_RADIUS_M * math.radians(PIXEL_SIZE_DEGREES),
            "neighborhood_pixels": 0,
            "local_relief_m": None,
            "roughness_std_m": None,
            "slope_deg": None,
            "aspect_deg": None,
            "curvature_km_inv": None,
        }
        radius = NEIGHBORHOOD_RADIUS
        if row < radius + 1 or row + radius + 1 >= height:
            result["neighborhood_status"] = (
                "Not calculated: the 5×5 neighborhood reaches the raster's polar edge."
            )
            return result
        values = [
            pixel(row + dy, column + dx)
            for dy in range(-radius, radius + 1)
            for dx in range(-radius, radius + 1)
        ]
        if any(math.isnan(value) for value in values):
            result["neighborhood_status"] = (
                "Not calculated: the 5×5 ETOPO 2022 neighborhood contains no-data values."
            )
            return result

        north = pixel(row - 1, column)
        south = pixel(row + 1, column)
        west = pixel(row, column - 1)
        east = pixel(row, column + 1)
        northwest = pixel(row - 1, column - 1)
        northeast = pixel(row - 1, column + 1)
        southwest = pixel(row + 1, column - 1)
        southeast = pixel(row + 1, column + 1)
        pixel_x_m = result["pixel_size_y_m"] * math.cos(math.radians(latitude))
        if pixel_x_m <= 0.01:
            result["neighborhood_status"] = (
                "Not calculated: east-west grid spacing is too small at this latitude."
            )
            return result
        dx = (east - west) / (2 * pixel_x_m)
        dy = (north - south) / (2 * result["pixel_size_y_m"])
        dxx = (east - 2 * elevation + west) / pixel_x_m**2
        dyy = (north - 2 * elevation + south) / result["pixel_size_y_m"]**2
        dxy = (northeast - northwest - southeast + southwest) / (
            4 * pixel_x_m * result["pixel_size_y_m"]
        )
        slope_squared = dx * dx + dy * dy
        curvature = (
            (1 + dy * dy) * dxx - 2 * dx * dy * dxy + (1 + dx * dx) * dyy
        ) / (2 * (1 + slope_squared) ** 1.5)
        result.update(
            {
                "neighborhood_pixels": len(values),
                "pixel_size_x_m": pixel_x_m,
                "local_relief_m": max(values) - min(values),
                "roughness_std_m": statistics.pstdev(values),
                "slope_deg": math.degrees(math.atan(math.hypot(dx, dy))),
                "aspect_deg": math.degrees(math.atan2(-dx, -dy)) % 360,
                "curvature_km_inv": curvature * 1000,
                "neighborhood_status": "5×5 one-arc-minute regional neighborhood (~9 km across).",
            }
        )
        return result
    finally:
        library.TIFFClose(tiff)
