#!/usr/bin/env python3
"""Download NOAA's global ETOPO 2022 60-arc-second surface-elevation GeoTIFF."""

from __future__ import annotations

import argparse
import os
import shutil
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUTPUT_PATH = ROOT / "data/etopo2022/ETOPO_2022_v1_60s_N90W180_surface.tif"
SOURCE_URL = (
    "https://www.ngdc.noaa.gov/mgg/global/relief/ETOPO2022/data/60s/"
    "60s_surface_elev_gtif/ETOPO_2022_v1_60s_N90W180_surface.tif"
)
SOURCE_BYTES = 465_969_062


def download(path: Path = OUTPUT_PATH) -> None:
    if path.exists() and path.stat().st_size == SOURCE_BYTES:
        print(f"Using existing NOAA ETOPO 2022 raster: {path}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(path.parent).free < SOURCE_BYTES + 16 * 1024 * 1024:
        raise OSError("Not enough free disk space for the 466 MB ETOPO 2022 download.")
    temporary = path.with_suffix(path.suffix + ".part")
    temporary.unlink(missing_ok=True)
    request = urllib.request.Request(
        SOURCE_URL, headers={"User-Agent": "earth-index-poc/1.0"}
    )
    try:
        with urllib.request.urlopen(request, timeout=90) as response, temporary.open("wb") as target:
            if response.headers.get("Content-Type", "").lower().startswith("text/html"):
                raise OSError("NOAA returned an HTML page instead of the TIFF raster.")
            content_length = response.headers.get("Content-Length")
            if content_length is not None and int(content_length) != SOURCE_BYTES:
                raise OSError(f"Unexpected ETOPO 2022 size: {content_length} bytes.")
            total = 0
            while chunk := response.read(4 * 1024 * 1024):
                target.write(chunk)
                total += len(chunk)
                if total % (64 * 1024 * 1024) < 4 * 1024 * 1024:
                    print(f"Downloaded {total:,}/{SOURCE_BYTES:,} bytes", flush=True)
        if total != SOURCE_BYTES:
            raise OSError(f"Incomplete ETOPO 2022 download: {total:,} bytes.")
        with temporary.open("rb") as source:
            if source.read(4) not in (b"II*\x00", b"MM\x00*"):
                raise OSError("Downloaded ETOPO 2022 file does not have a TIFF signature.")
        os.replace(temporary, path)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise
    print(f"Saved NOAA ETOPO 2022: {path} ({total:,} bytes)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUTPUT_PATH)
    args = parser.parse_args()
    try:
        download(args.output)
    except OSError as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
