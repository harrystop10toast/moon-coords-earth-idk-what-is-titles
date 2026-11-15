#!/usr/bin/env python3
"""Sample the GLiM v1.0 global 0.5-degree categorical raster."""

from __future__ import annotations

import csv
import math
from pathlib import Path

NODATA = -9999
SOURCE_URL = "https://doi.org/10.1594/PANGAEA.788537"
CLASS_NAMES = {
    "su": "Unconsolidated sediments",
    "vb": "Basic volcanic rocks",
    "ss": "Siliciclastic sedimentary rocks",
    "pb": "Basic plutonic rocks",
    "sm": "Mixed sedimentary rocks",
    "sc": "Carbonate sedimentary rocks",
    "va": "Acidic volcanic rocks",
    "mt": "Metamorphic rocks",
    "pa": "Acidic plutonic rocks",
    "vi": "Intermediate volcanic rocks",
    "wb": "Water bodies",
    "py": "Pyroclastic rocks",
    "pi": "Intermediate plutonic rocks",
    "ev": "Evaporites",
    "nd": "No data",
    "ig": "Ice and glaciers",
}


def _read_header(stream) -> dict[str, float]:
    header = {}
    for _ in range(6):
        line = stream.readline()
        if not line:
            raise ValueError("GLiM ASCII raster has an incomplete header.")
        parts = line.split()
        if len(parts) != 2:
            raise ValueError("GLiM ASCII raster has an invalid header.")
        header[parts[0].lower()] = float(parts[1])
    required = {"ncols", "nrows", "xllcorner", "yllcorner", "cellsize", "nodata_value"}
    if set(header) != required:
        raise ValueError("GLiM ASCII raster has unexpected header fields.")
    if (header["ncols"], header["nrows"], header["cellsize"]) != (720, 360, 0.5):
        raise ValueError("Unexpected GLiM grid geometry; expected global 0.5-degree data.")
    if (header["xllcorner"], header["yllcorner"]) != (-180, -90):
        raise ValueError("Unexpected GLiM grid origin; expected WGS84 longitude/latitude.")
    return header


def _read_legend(path: Path) -> dict[int, str]:
    result = {}
    with path.open(encoding="utf-8", newline="") as legend_file:
        reader = csv.DictReader(legend_file, delimiter=";")
        for item in reader:
            try:
                code = int(item["Value_"])
                abbreviation = item["xx"].strip().strip('"')
            except (KeyError, TypeError, ValueError) as error:
                raise ValueError(f"Invalid GLiM class legend: {path}") from error
            result[code] = abbreviation
    if not result:
        raise ValueError("GLiM class legend contains no class codes.")
    return result


def sample_glim(
    raster_path: Path, legend_path: Path, latitude: float, longitude: float
) -> dict:
    if not raster_path.is_file():
        raise FileNotFoundError(f"GLiM raster not found: {raster_path}")
    if not legend_path.is_file():
        raise FileNotFoundError(f"GLiM class legend not found: {legend_path}")
    if not math.isfinite(latitude) or not -90 <= latitude <= 90:
        raise ValueError("Latitude must be finite and between -90 and 90.")
    if not math.isfinite(longitude) or not -180 <= longitude <= 180:
        raise ValueError("Longitude must be finite and between -180 and 180.")

    with raster_path.open(encoding="ascii") as raster:
        header = _read_header(raster)
        row = min(359, max(0, int((90 - latitude) / header["cellsize"])))
        column = min(719, max(0, int((longitude + 180) / header["cellsize"])))
        values = None
        for _ in range(row + 1):
            line = raster.readline()
            if not line:
                raise ValueError("GLiM raster ended before the requested grid row.")
            values = line.split()
        if values is None or len(values) != 720:
            raise ValueError(f"GLiM raster row {row} does not contain 720 cells.")
        try:
            code = int(values[column])
        except ValueError as error:
            raise ValueError(f"Invalid GLiM class code at row {row}, column {column}.") from error

    result = {
        "dataset": "GLiM v1.0",
        "source_url": SOURCE_URL,
        "license": "CC-BY-3.0",
        "latitude_deg": latitude,
        "longitude_deg": longitude,
        "cell_center_latitude_deg": 90 - (row + 0.5) * header["cellsize"],
        "cell_center_longitude_deg": -180 + (column + 0.5) * header["cellsize"],
        "cell_size_degrees": header["cellsize"],
        "class_code": None if code == int(header["nodata_value"]) else code,
        "class_abbreviation": None,
        "class_name": None,
    }
    if result["class_code"] is None:
        return result

    abbreviation = _read_legend(legend_path).get(code)
    if abbreviation is None:
        raise ValueError(f"GLiM class code {code} is missing from its legend.")
    result["class_abbreviation"] = abbreviation
    result["class_name"] = CLASS_NAMES.get(abbreviation, "Unrecognized GLiM class")
    return result
