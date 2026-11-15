#!/usr/bin/env python3
"""Sample ESA WorldCover 2021 v200 map tiles using system libtiff."""

from __future__ import annotations

import ctypes
import math
import re
from collections import Counter
from pathlib import Path

from tiff_support import get_uint16, get_uint32, read_tiff_tile, tiff_library

TILE_PATTERN = re.compile(r"_([NS])(\d{2})([EW])(\d{3})_Map\.tif$")
TILE_SIZE_DEGREES = 3
NEIGHBORHOOD_RADIUS = 5
EARTH_RADIUS_M = 6_371_008.8
CLASS_NAMES = {
    10: "Tree cover",
    20: "Shrubland",
    30: "Grassland",
    40: "Cropland",
    50: "Built-up",
    60: "Bare / sparse vegetation",
    70: "Snow and ice",
    80: "Permanent water bodies",
    90: "Herbaceous wetland",
    95: "Mangroves",
    100: "Moss and lichen",
}
SOURCE_URL = "https://esa-worldcover.org/en/data-access"


def _tile_origin(path: Path) -> tuple[int, int]:
    match = TILE_PATTERN.search(path.name)
    if not match:
        raise ValueError(f"Unrecognized WorldCover tile filename: {path.name}")
    lat_sign, lat_value, lon_sign, lon_value = match.groups()
    south = int(lat_value) * (1 if lat_sign == "N" else -1)
    west = int(lon_value) * (1 if lon_sign == "E" else -1)
    return south, west


def sample_worldcover_tile(path: Path, latitude: float, longitude: float) -> dict:
    if not path.is_file():
        raise FileNotFoundError(f"WorldCover tile not found: {path}")
    if not math.isfinite(latitude) or not -90 <= latitude <= 90:
        raise ValueError("Latitude must be finite and between -90 and 90.")
    if not math.isfinite(longitude) or not -180 <= longitude <= 180:
        raise ValueError("Longitude must be finite and between -180 and 180.")

    south, west = _tile_origin(path)
    north = south + TILE_SIZE_DEGREES
    east = west + TILE_SIZE_DEGREES
    if not south <= latitude <= north or not west <= longitude <= east:
        raise ValueError("Coordinate is outside the WorldCover tile bounds.")

    library = tiff_library()
    tiff = library.TIFFOpen(str(path.resolve()).encode(), b"r")
    if not tiff:
        raise OSError(f"Could not open WorldCover GeoTIFF: {path}")
    try:
        width = get_uint32(library, tiff, 256)
        height = get_uint32(library, tiff, 257)
        tile_width = get_uint32(library, tiff, 322)
        tile_height = get_uint32(library, tiff, 323)
        bits_per_sample = get_uint16(library, tiff, 258)
        samples_per_pixel = get_uint16(library, tiff, 277)
        sample_format = get_uint16(library, tiff, 339)
        if width == 0 or height == 0 or tile_width == 0 or tile_height == 0:
            raise ValueError("WorldCover GeoTIFF has invalid image or tile dimensions.")
        if (bits_per_sample, samples_per_pixel, sample_format) != (8, 1, 1):
            raise ValueError(
                "WorldCover map must be a single-band, 8-bit unsigned integer raster."
            )

        column = min(
            width - 1,
            max(0, int((longitude - west) / TILE_SIZE_DEGREES * width)),
        )
        row = min(
            height - 1,
            max(0, int((north - latitude) / TILE_SIZE_DEGREES * height)),
        )
        tile_cache: dict[tuple[int, int], ctypes.Array] = {}

        def pixel(pixel_row: int, pixel_column: int) -> int | None:
            if not 0 <= pixel_row < height or not 0 <= pixel_column < width:
                return None
            tile_x = pixel_column // tile_width * tile_width
            tile_y = pixel_row // tile_height * tile_height
            key = (tile_x, tile_y)
            if key not in tile_cache:
                tile_cache[key] = read_tiff_tile(
                    library, tiff, tile_x, tile_y, tile_width, tile_height, 1
                )
            local_row = pixel_row - tile_y
            local_column = pixel_column - tile_x
            value = tile_cache[key][local_row * tile_width + local_column][0]
            return None if value == 0 else value

        point_class = pixel(row, column)
        radius = NEIGHBORHOOD_RADIUS
        neighborhood = [
            pixel(row + dy, column + dx)
            for dy in range(-radius, radius + 1)
            for dx in range(-radius, radius + 1)
        ]
        valid_values = [value for value in neighborhood if value is not None]
        counts = Counter(valid_values)
        dominant_class = counts.most_common(1)[0][0] if counts else None
        neighborhood_mix = [
            {
                "class_value": class_value,
                "class_name": CLASS_NAMES.get(class_value, "Unclassified"),
                "pixel_count": count,
                "fraction": count / len(valid_values),
            }
            for class_value, count in counts.most_common()
        ]
        return {
            "tile": path.stem,
            "source_url": SOURCE_URL,
            "license": "CC-BY-4.0",
            "reference_year": 2021,
            "class_value": point_class,
            "class_name": CLASS_NAMES.get(point_class, "No-data / unclassified"),
            "dominant_class_value": dominant_class,
            "dominant_class_name": CLASS_NAMES.get(
                dominant_class, "No-data / unclassified"
            ),
            "neighborhood_pixels": len(valid_values),
            "neighborhood_total_pixels": len(neighborhood),
            "class_counts": dict(sorted(counts.items())),
            "neighborhood_mix": neighborhood_mix,
            "pixel_size_degrees_x": TILE_SIZE_DEGREES / width,
            "pixel_size_degrees_y": TILE_SIZE_DEGREES / height,
            "pixel_size_x_m": (
                EARTH_RADIUS_M
                * math.cos(math.radians(latitude))
                * math.radians(TILE_SIZE_DEGREES / width)
            ),
            "pixel_size_y_m": (
                EARTH_RADIUS_M * math.radians(TILE_SIZE_DEGREES / height)
            ),
        }
    finally:
        library.TIFFClose(tiff)
