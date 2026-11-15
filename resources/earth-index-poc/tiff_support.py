#!/usr/bin/env python3
"""Small libtiff bindings shared by local GeoTIFF readers."""

from __future__ import annotations

import ctypes
import ctypes.util
from functools import lru_cache


@lru_cache(maxsize=1)
def tiff_library():
    library_name = ctypes.util.find_library("tiff")
    if not library_name:
        raise RuntimeError(
            "libtiff is required to sample GeoTIFFs; on macOS install it with `brew install libtiff`."
        )
    library = ctypes.CDLL(library_name)
    library.TIFFOpen.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
    library.TIFFOpen.restype = ctypes.c_void_p
    library.TIFFGetField.argtypes = [ctypes.c_void_p, ctypes.c_uint32]
    library.TIFFGetField.restype = ctypes.c_int
    library.TIFFReadTile.argtypes = [
        ctypes.c_void_p,
        ctypes.c_void_p,
        ctypes.c_uint32,
        ctypes.c_uint32,
        ctypes.c_uint32,
        ctypes.c_uint16,
    ]
    library.TIFFReadTile.restype = ctypes.c_ssize_t
    library.TIFFClose.argtypes = [ctypes.c_void_p]
    return library


def get_uint32(library, tiff, tag: int) -> int:
    value = ctypes.c_uint32()
    if not library.TIFFGetField(tiff, tag, ctypes.byref(value)):
        raise ValueError(f"GeoTIFF is missing required tag {tag}.")
    return value.value


def get_uint16(library, tiff, tag: int) -> int:
    value = ctypes.c_uint16()
    if not library.TIFFGetField(tiff, tag, ctypes.byref(value)):
        raise ValueError(f"GeoTIFF is missing required tag {tag}.")
    return value.value


def read_tiff_tile(library, tiff, x: int, y: int, width: int, height: int, bytes_per_sample: int):
    buffer = ctypes.create_string_buffer(width * height * bytes_per_sample)
    decoded_bytes = library.TIFFReadTile(tiff, buffer, x, y, 0, 0)
    if decoded_bytes != width * height * bytes_per_sample:
        raise OSError(f"Could not decode GeoTIFF tile ({decoded_bytes} bytes).")
    return buffer
