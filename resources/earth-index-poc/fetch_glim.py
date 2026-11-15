#!/usr/bin/env python3
"""Download and unpack the public GLiM 0.5-degree raster from PANGAEA."""

from __future__ import annotations

import os
import tempfile
import zipfile
from pathlib import Path
from urllib.error import URLError
from urllib.request import Request, urlopen

GLIM_URL = "https://hdl.handle.net/10013/epic.39939.d001"
ARCHIVE_NAME = "hartmann-moosdorf_2012.zip"
RASTER_NAME = "glim_wgs84_0point5deg.txt.asc"
LEGEND_NAME = "Classnames.txt"


def _write_member(archive: zipfile.ZipFile, member: str, destination: Path) -> None:
    data = archive.read(member)
    if not data:
        raise OSError(f"GLiM archive member is empty: {member}")
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            dir=destination.parent,
            prefix=f"{destination.name}.",
            suffix=".part",
            delete=False,
        ) as temporary:
            temporary_path = Path(temporary.name)
            temporary.write(data)
        os.replace(temporary_path, destination)
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def download_glim(output_dir: Path) -> str:
    output_dir.mkdir(parents=True, exist_ok=True)
    archive_path = output_dir / ARCHIVE_NAME
    raster_path = output_dir / RASTER_NAME
    legend_path = output_dir / LEGEND_NAME

    if not archive_path.is_file():
        request = Request(GLIM_URL, headers={"User-Agent": "earth-index-poc/1.0"})
        temporary_path: Path | None = None
        try:
            with urlopen(request, timeout=60) as response:
                with tempfile.NamedTemporaryFile(
                    mode="wb",
                    dir=output_dir,
                    prefix=f"{ARCHIVE_NAME}.",
                    suffix=".part",
                    delete=False,
                ) as temporary:
                    temporary_path = Path(temporary.name)
                    while chunk := response.read(1024 * 1024):
                        temporary.write(chunk)
            with zipfile.ZipFile(temporary_path) as archive:
                required = {RASTER_NAME, LEGEND_NAME}
                if not required.issubset(archive.namelist()):
                    raise OSError("PANGAEA GLiM ZIP is missing its raster or class legend.")
                bad_member = archive.testzip()
                if bad_member is not None:
                    raise OSError(f"Corrupt GLiM archive member: {bad_member}")
            os.replace(temporary_path, archive_path)
        except (OSError, URLError, TimeoutError, zipfile.BadZipFile):
            if temporary_path is not None:
                temporary_path.unlink(missing_ok=True)
            raise

    try:
        with zipfile.ZipFile(archive_path) as archive:
            required = {RASTER_NAME, LEGEND_NAME}
            if not required.issubset(archive.namelist()):
                raise OSError("Cached PANGAEA GLiM ZIP is missing its raster or class legend.")
            _write_member(archive, RASTER_NAME, raster_path)
            _write_member(archive, LEGEND_NAME, legend_path)
    except zipfile.BadZipFile as error:
        raise OSError(f"Cached GLiM archive is invalid: {archive_path}") from error

    return (
        f"Downloaded and unpacked GLiM v1.0 0.5-degree raster "
        f"({archive_path.stat().st_size:,} byte archive; CC-BY-3.0)."
    )
