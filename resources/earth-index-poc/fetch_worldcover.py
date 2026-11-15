#!/usr/bin/env python3
"""Download one ESA WorldCover 2021 v200 3-degree map tile."""

from __future__ import annotations

import math
import os
import re
import tempfile
from pathlib import Path
from urllib.error import URLError
from urllib.request import Request, urlopen

BASE_URL = "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map"
TILE_PATTERN = re.compile(r"^[NS]\d{2}[EW]\d{3}$")


def worldcover_tile_name(latitude: float, longitude: float) -> str:
    if not math.isfinite(latitude) or not -90 <= latitude <= 90:
        raise ValueError("Latitude must be finite and between -90 and 90.")
    if not math.isfinite(longitude) or not -180 <= longitude <= 180:
        raise ValueError("Longitude must be finite and between -180 and 180.")

    south = min(math.floor(latitude / 3) * 3, 87)
    west = min(math.floor(longitude / 3) * 3, 177)
    latitude_prefix = "N" if south >= 0 else "S"
    longitude_prefix = "E" if west >= 0 else "W"
    return f"{latitude_prefix}{abs(south):02d}{longitude_prefix}{abs(west):03d}"


def fetch_worldcover_tile(tile_name: str, output_dir: Path) -> tuple[Path, str]:
    if not TILE_PATTERN.fullmatch(tile_name):
        raise ValueError(f"Invalid WorldCover 3-degree tile name: {tile_name}")
    output_dir.mkdir(parents=True, exist_ok=True)
    filename = f"ESA_WorldCover_10m_2021_v200_{tile_name}_Map.tif"
    destination = output_dir / filename
    if destination.is_file() and destination.stat().st_size > 0:
        return destination, f"Using cached ESA WorldCover tile {tile_name}."

    url = f"{BASE_URL}/{filename}"
    request = Request(url, headers={"User-Agent": "earth-index-poc/1.0"})
    temporary_path: Path | None = None
    try:
        with urlopen(request, timeout=60) as response:
            expected_size = response.headers.get("Content-Length")
            with tempfile.NamedTemporaryFile(
                mode="wb",
                dir=output_dir,
                prefix=f"{filename}.",
                suffix=".part",
                delete=False,
            ) as temporary:
                temporary_path = Path(temporary.name)
                while chunk := response.read(1024 * 1024):
                    temporary.write(chunk)
            actual_size = temporary_path.stat().st_size
            if expected_size is not None and actual_size != int(expected_size):
                raise OSError(
                    f"Incomplete WorldCover download: expected {expected_size} bytes, "
                    f"received {actual_size}."
                )
            with temporary_path.open("rb") as downloaded:
                if downloaded.read(4) not in (b"II*\x00", b"MM\x00*"):
                    raise OSError("Downloaded WorldCover tile is not a TIFF file.")
            os.replace(temporary_path, destination)
    except (OSError, URLError, TimeoutError, ValueError):
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
        raise

    return destination, f"Downloaded ESA WorldCover tile {tile_name} ({actual_size:,} bytes)."


def fetch_worldcover_for_point(
    latitude: float, longitude: float, output_dir: Path
) -> tuple[Path, str]:
    return fetch_worldcover_tile(worldcover_tile_name(latitude, longitude), output_dir)
