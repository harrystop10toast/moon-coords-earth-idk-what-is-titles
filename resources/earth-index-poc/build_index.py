#!/usr/bin/env python3
"""Download ETOPO5 and build a coarse, reusable Earth terrain index."""

from __future__ import annotations

import argparse
import array
import math
import os
import sqlite3
import statistics
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA_DIR = ROOT / "data"
SOURCE_PATH = DATA_DIR / "ETOPO5.DAT"
INDEX_PATH = DATA_DIR / "earth-terrain-index.sqlite3"
SOURCE_URL = (
    "https://www.ngdc.noaa.gov/mgg/global/relief/ETOPO5/TOPO/ETOPO5/ETOPO5.DAT"
)
SOURCE_BYTES = 18_662_400
SOURCE_ROWS = 2_160
SOURCE_COLUMNS = 4_320
SOURCE_STEP_DEGREES = 5 / 60
BLOCK_CELLS = 2
BLOCK_STEP_DEGREES = SOURCE_STEP_DEGREES * BLOCK_CELLS
LAND_FRACTION_THRESHOLD = 0.75
EARTH_RADIUS_M = 6_371_008.8


def summarize_block(elevations: list[int]) -> tuple[int, float, float, float]:
    """Return land count, mean elevation, range, and population standard deviation."""
    land = [float(value) for value in elevations if value >= 0]
    required_land = math.ceil(len(elevations) * LAND_FRACTION_THRESHOLD)
    if len(land) < required_land:
        return len(land), math.nan, math.nan, math.nan
    mean = statistics.fmean(land)
    return (
        len(land),
        mean,
        max(land) - min(land),
        statistics.pstdev(land),
    )


def slope_degrees(
    west: float,
    east: float,
    north: float,
    south: float,
    latitude: float,
    step_degrees: float = BLOCK_STEP_DEGREES,
) -> float | None:
    """Estimate slope from neighboring block means, or return None at gaps/poles."""
    if not all(math.isfinite(value) for value in (west, east, north, south)):
        return None
    cosine = math.cos(math.radians(latitude))
    if abs(cosine) < 0.05:
        return None
    step_m = EARTH_RADIUS_M * math.radians(step_degrees)
    dx = step_m * cosine
    dz_dx = (east - west) / (2 * dx)
    dz_dy = (north - south) / (2 * step_m)
    return math.degrees(math.atan(math.hypot(dz_dx, dz_dy)))


def download_source(path: Path = SOURCE_PATH) -> None:
    if path.exists() and path.stat().st_size == SOURCE_BYTES:
        print(f"Using existing ETOPO5 grid: {path} ({SOURCE_BYTES:,} bytes)")
        return

    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_suffix(path.suffix + ".part")
    partial.unlink(missing_ok=True)
    print(f"Downloading NOAA ETOPO5 from {SOURCE_URL}")
    try:
        with urllib.request.urlopen(SOURCE_URL, timeout=60) as response, partial.open("wb") as out:
            length = response.headers.get("Content-Length")
            if length is not None and int(length) != SOURCE_BYTES:
                raise OSError(f"Unexpected source size: HTTP reports {length} bytes")
            total = 0
            while chunk := response.read(1024 * 1024):
                out.write(chunk)
                total += len(chunk)
                print(f"\rDownloaded {total:,}/{SOURCE_BYTES:,} bytes", end="", flush=True)
        print()
        if total != SOURCE_BYTES:
            raise OSError(f"Download was incomplete: received {total:,} bytes")
        os.replace(partial, path)
    except Exception:
        partial.unlink(missing_ok=True)
        raise


def read_source(path: Path) -> array.array:
    if array.array("h").itemsize != 2:
        raise RuntimeError("This platform does not provide 16-bit signed short values.")
    if path.stat().st_size != SOURCE_BYTES:
        raise ValueError(f"{path} must be exactly {SOURCE_BYTES:,} bytes")
    values = array.array("h")
    with path.open("rb") as source:
        values.frombytes(source.read())
    if sys.byteorder == "little":
        values.byteswap()
    expected_cells = SOURCE_ROWS * SOURCE_COLUMNS
    if len(values) != expected_cells:
        raise ValueError(f"Expected {expected_cells:,} elevation samples, got {len(values):,}")
    return values


def build_index(source_path: Path = SOURCE_PATH, index_path: Path = INDEX_PATH) -> int:
    started = time.monotonic()
    values = read_source(source_path)
    block_rows = SOURCE_ROWS // BLOCK_CELLS
    block_columns = SOURCE_COLUMNS // BLOCK_CELLS
    block_count = block_rows * block_columns
    land_counts = bytearray(block_count)
    means = array.array("f", [math.nan]) * block_count
    reliefs = array.array("f", [math.nan]) * block_count
    roughness = array.array("f", [math.nan]) * block_count

    for block_row in range(block_rows):
        source_row = block_row * BLOCK_CELLS
        for block_column in range(block_columns):
            source_column = block_column * BLOCK_CELLS
            block = [
                values[(source_row + dy) * SOURCE_COLUMNS + source_column + dx]
                for dy in range(BLOCK_CELLS)
                for dx in range(BLOCK_CELLS)
            ]
            count, mean, relief, stddev = summarize_block(block)
            block_id = block_row * block_columns + block_column
            land_counts[block_id] = count
            if count >= BLOCK_CELLS * BLOCK_CELLS * LAND_FRACTION_THRESHOLD:
                means[block_id] = mean
                reliefs[block_id] = relief
                roughness[block_id] = stddev

    index_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = index_path.with_suffix(index_path.suffix + ".tmp")
    temporary.unlink(missing_ok=True)
    candidate_count = 0
    try:
        with sqlite3.connect(temporary) as db:
            db.execute("PRAGMA journal_mode=OFF")
            db.execute("PRAGMA synchronous=OFF")
            db.execute(
                """
                CREATE TABLE earth_cells (
                    id INTEGER PRIMARY KEY,
                    latitude_deg REAL NOT NULL,
                    longitude_east_deg REAL NOT NULL,
                    land_fraction REAL NOT NULL,
                    mean_elevation_m REAL NOT NULL,
                    slope_deg REAL,
                    local_relief_m REAL NOT NULL,
                    roughness_std_m REAL NOT NULL
                )
                """
            )
            db.execute("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            metadata = {
                "dataset": "NOAA ETOPO5",
                "source_url": SOURCE_URL,
                "source_resolution": "5 arc-minutes; regionally variable source resolution",
                "candidate_cell_size": "10 arc-minutes",
                "features": "land fraction, mean elevation, slope, local relief, roughness standard deviation",
                "caveat": (
                    "Coarse prefilter only; do not score against lunar metrics at a different "
                    "spatial baseline."
                ),
            }
            db.executemany(
                "INSERT INTO metadata(key, value) VALUES (?, ?)", metadata.items()
            )

            batch: list[tuple[object, ...]] = []
            insert = """
                INSERT INTO earth_cells(
                    id, latitude_deg, longitude_east_deg, land_fraction,
                    mean_elevation_m, slope_deg, local_relief_m, roughness_std_m
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """
            for block_row in range(block_rows):
                latitude = 90 - (block_row * BLOCK_CELLS + (BLOCK_CELLS - 1) / 2) * SOURCE_STEP_DEGREES
                north_row = block_row - 1
                south_row = block_row + 1
                if north_row < 0 or south_row >= block_rows:
                    continue
                for block_column in range(block_columns):
                    block_id = block_row * block_columns + block_column
                    count = land_counts[block_id]
                    if count < BLOCK_CELLS * BLOCK_CELLS * LAND_FRACTION_THRESHOLD:
                        continue

                    west_column = (block_column - 1) % block_columns
                    east_column = (block_column + 1) % block_columns
                    neighbors = (
                        means[block_row * block_columns + west_column],
                        means[block_row * block_columns + east_column],
                        means[north_row * block_columns + block_column],
                        means[south_row * block_columns + block_column],
                    )
                    slope = slope_degrees(*neighbors, latitude)
                    longitude = (
                        block_column * BLOCK_CELLS + (BLOCK_CELLS - 1) / 2
                    ) * SOURCE_STEP_DEGREES
                    batch.append(
                        (
                            block_id,
                            latitude,
                            longitude,
                            count / (BLOCK_CELLS * BLOCK_CELLS),
                            means[block_id],
                            slope,
                            reliefs[block_id],
                            roughness[block_id],
                        )
                    )
                    if len(batch) >= 5_000:
                        db.executemany(insert, batch)
                        candidate_count += len(batch)
                        batch.clear()
            if batch:
                db.executemany(insert, batch)
                candidate_count += len(batch)
            db.execute("CREATE INDEX earth_cells_lat_lon ON earth_cells(latitude_deg, longitude_east_deg)")
            db.commit()
        os.replace(temporary, index_path)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise

    print(
        f"Built {candidate_count:,} land candidates in {time.monotonic() - started:.1f}s: "
        f"{index_path} ({index_path.stat().st_size:,} bytes)"
    )
    return candidate_count


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=SOURCE_PATH, help="ETOPO5.DAT path")
    parser.add_argument("--index", type=Path, default=INDEX_PATH, help="output SQLite index path")
    args = parser.parse_args()
    download_source(args.source)
    build_index(args.source, args.index)


if __name__ == "__main__":
    main()
