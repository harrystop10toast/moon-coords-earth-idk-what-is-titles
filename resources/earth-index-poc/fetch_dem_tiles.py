#!/usr/bin/env python3
"""Fetch only the 1-degree Copernicus GLO-90 tiles needed by candidate points."""

from __future__ import annotations

import argparse
import csv
import math
import os
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA_DIR = ROOT / "data"
TILE_LIST_URL = "https://copernicus-dem-90m.s3.amazonaws.com/tileList.txt"
TILE_BASE_URL = "https://copernicus-dem-90m.s3.amazonaws.com"
TILE_LIST_PATH = DATA_DIR / "copernicus-dem-90m-tileList.txt"


def copdem_tile_name(latitude: float, longitude: float) -> str:
    if not math.isfinite(latitude) or not -90 <= latitude <= 90:
        raise ValueError("Latitude must be finite and between -90 and 90.")
    if not math.isfinite(longitude):
        raise ValueError("Longitude must be finite.")
    lat_cell = min(math.floor(latitude), 89)
    east_lon = ((longitude + 180) % 360) - 180
    lon_cell = math.floor(east_lon)
    lat_prefix = "N" if lat_cell >= 0 else "S"
    lon_prefix = "E" if lon_cell >= 0 else "W"
    tile = (
        f"Copernicus_DSM_COG_30_{lat_prefix}{abs(lat_cell):02d}_00_"
        f"{lon_prefix}{abs(lon_cell):03d}_00_DEM"
    )
    return tile


def read_points_file(path: Path) -> list[tuple[float, float]]:
    with path.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        if not reader.fieldnames:
            raise ValueError(f"{path} must have a CSV header.")
        fields = {name.strip().lower(): name for name in reader.fieldnames}
        lat_field = next(
            (fields[name] for name in ("latitude", "lat", "latitude_deg") if name in fields),
            None,
        )
        lon_field = next(
            (fields[name] for name in ("longitude", "lon", "longitude_deg", "longitude_east_deg") if name in fields),
            None,
        )
        if lat_field is None or lon_field is None:
            raise ValueError(f"{path} must contain latitude/longitude columns.")
        points = []
        for line_number, row in enumerate(reader, start=2):
            try:
                points.append((float(row[lat_field]), float(row[lon_field])))
            except (TypeError, ValueError) as error:
                raise ValueError(f"Invalid coordinate on CSV line {line_number}.") from error
    return points


def get_tile_list(path: Path = TILE_LIST_PATH) -> set[str]:
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        request = urllib.request.Request(
            TILE_LIST_URL, headers={"User-Agent": "earth-index-poc/1.0"}
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            payload = response.read()
        temporary = path.with_suffix(path.suffix + ".tmp")
        try:
            temporary.write_bytes(payload)
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)
    return {
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    }


def is_tiff(path: Path) -> bool:
    with path.open("rb") as downloaded:
        signature = downloaded.read(4)
    return signature in (b"II*\x00", b"MM\x00*")


def fetch_tile(tile_name: str, output_dir: Path) -> tuple[str, str]:
    output_dir.mkdir(parents=True, exist_ok=True)
    destination = output_dir / f"{tile_name}.tif"
    if destination.exists() and destination.stat().st_size > 0 and is_tiff(destination):
        return tile_name, "cached"
    partial = destination.with_suffix(".tif.part")
    partial.unlink(missing_ok=True)
    url = f"{TILE_BASE_URL}/{tile_name}/{tile_name}.tif"
    request = urllib.request.Request(url, headers={"User-Agent": "earth-index-poc/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=90) as response, partial.open("wb") as out:
            if response.headers.get_content_type() == "text/html":
                raise OSError("Tile endpoint returned an HTML page, not a COG.")
            expected_size = response.headers.get("Content-Length")
            total = 0
            while chunk := response.read(1024 * 1024):
                out.write(chunk)
                total += len(chunk)
        if expected_size is not None and total != int(expected_size):
            raise OSError(f"Incomplete tile: received {total} of {expected_size} bytes.")
        if total < 8 or not is_tiff(partial):
            raise OSError("Downloaded object is not a TIFF.")
        os.replace(partial, destination)
        return tile_name, f"downloaded ({total:,} bytes)"
    except Exception:
        partial.unlink(missing_ok=True)
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--point",
        nargs=2,
        action="append",
        type=float,
        metavar=("LATITUDE", "LONGITUDE"),
        help="candidate point; may be supplied more than once",
    )
    parser.add_argument(
        "--points-csv",
        type=Path,
        help="CSV file with latitude/longitude columns (e.g. top candidate cells)",
    )
    parser.add_argument("--workers", type=int, default=4, help="parallel tile downloads (1-8)")
    parser.add_argument(
        "--output",
        type=Path,
        default=DATA_DIR / "copernicus-dem-90m",
        help="tile output directory",
    )
    args = parser.parse_args()
    if not 1 <= args.workers <= 8:
        parser.error("--workers must be between 1 and 8.")

    points = [tuple(point) for point in (args.point or [])]
    if args.points_csv:
        try:
            points.extend(read_points_file(args.points_csv))
        except (OSError, ValueError) as error:
            parser.error(str(error))
    if not points:
        parser.error("Provide --point and/or --points-csv.")

    try:
        tiles = list(dict.fromkeys(copdem_tile_name(lat, lon) for lat, lon in points))
        catalog = get_tile_list()
    except (OSError, ValueError) as error:
        print(f"Could not resolve requested tiles: {error}", file=sys.stderr)
        return 1

    unavailable = [tile for tile in tiles if tile not in catalog]
    if unavailable:
        print(
            "No public GLO-90 tile listed for: " + ", ".join(unavailable),
            file=sys.stderr,
        )
        return 1

    failures: list[str] = []
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {
            pool.submit(fetch_tile, tile, args.output): tile for tile in tiles
        }
        for future in as_completed(futures):
            tile = futures[future]
            try:
                name, status = future.result()
                print(f"{name}: {status}")
            except Exception as error:
                failures.append(f"{tile}: {error}")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"Ready: {len(tiles)} unique GLO-90 tile(s) in {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
