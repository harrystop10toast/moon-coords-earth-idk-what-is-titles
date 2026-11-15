#!/usr/bin/env python3
"""Match a Moon site to the top Earth analogs using multi-aspect local scoring.

Scored aspects (local only — no per-cell Earth APIs):
  1. terrain shape (highest-confidence comparable metrics)
  2. material (mare requires basic/basaltic volcanic GLiM `vb`, not intermediate)
  3. latitude / solar regime (large |Δφ| is a hard penalty)
  4. arid / barren suitability for extreme lunar thermal sites (percentile, not K-equality)
  5. impact setting only when an Earth crater is actually proximal + size-compatible

Context only (not scored): magnetic nT, absolute elevation, SoilGrids, weather.
No Earth site matches a ~300 K lunar Diviner swing; thermal is ranked as arid
suitability percentile among candidates.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import sqlite3
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent
REPO_ROOT = ROOT.parent
INDEX_PATH = ROOT / "data" / "earth-terrain-index.sqlite3"
REPORT_PATH = REPO_ROOT / "site-reports.json"
GLIM_RASTER = ROOT / "data" / "glim" / "glim_wgs84_0point5deg.txt.asc"
GLIM_LEGEND = ROOT / "data" / "glim" / "Classnames.txt"
CRATER_CATALOG_PATH = ROOT / "data" / "earth-impact-craters.csv"

# Terrain is a hard gate, then remaining aspects rank survivors.
# Magnetic is never scored. Impact is optional (only if relevant).
# Material is omitted entirely when lunar class is "unknown" (unverified).
ASPECT_WEIGHTS = {
    "terrain": 0.40,
    "material": 0.28,
    "latitude_solar": 0.20,  # mismatch penalty only — never a reason to rank a bad terrain twin
    "arid_suitability": 0.12,
    "impact_setting": 0.10,
}

MARE_REQUIRED_GLIM = {"vb"}  # basic volcanic = basalt/mafic; NOT intermediate (vi)
MARE_ACCEPTABLE_GLIM = {"vb", "py"}
# Highland / anorthosite proxies in GLiM (no perfect anorthosite class):
# pa = acidic plutonic, pb = basic plutonic, mt = metamorphic.
HIGHLAND_PREFERRED_GLIM = {"pa", "pb", "mt"}
HIGHLAND_REJECT_GLIM = {"vb", "vi", "va", "py"}  # volcanic ≠ plagioclase-rich highland
IMPACT_MAX_DISTANCE_KM = 120.0
IMPACT_DIAMETER_RATIO_MAX = 4.0

# Hard terrain gate (shared-baseline intent; current index is still coarse ETOPO5).
TERRAIN_SLOPE_MAX_DELTA_DEG = 3.0
TERRAIN_ROUGHNESS_RATIO_MAX = 3.0
MIN_TERRAIN_AGREEMENT = 0.40
MIN_OVERALL_AGREEMENT = 0.50

TERRAIN_FEATURE_WEIGHTS = {
    "slope_deg": 0.55,
    "log_relief": 0.15,
    "log_rough": 0.20,
    "texture": 0.10,
}

PREFILTER_SLOPE_PAD_DEG = 3.0
PREFILTER_RELIEF_FACTOR = 8.0
PREFILTER_ROUGH_FACTOR = 8.0
SHORTLIST_LIMIT = 3000
DEFAULT_TOP_K = 5
DEFAULT_MIN_SEPARATION_DEG = 8.0
POLAR_ICE_LATITUDE = 70.0

# Rough boxes around known terrestrial anorthosite / plagioclase-rich massifs.
ANORTHOSITE_PROVINCES = (
    (43.5, 45.0, -75.5, -73.0),   # Adirondacks, NY
    (48.0, 49.5, -73.0, -70.5),   # Lac-Saint-Jean, Quebec
    (24.0, 26.5, 26.5, 31.0),     # Bushveld, South Africa
    (45.2, 45.6, -110.0, -109.5), # Stillwater, Montana
)

MARE_TOKENS = ("mare", "basaltic", "basalt", "lava", "volcanic", "flow")
HIGHLAND_TOKENS = ("highland", "anorthosite", "terra", "cratered")

# GLiM → proxy axes in [0, 1]. These are affinity scores, not assays.
GLIM_MAFIC = {
    "vb": 0.95,
    "vi": 0.80,
    "py": 0.75,
    "pb": 0.60,
    "pi": 0.45,
    "va": 0.30,
    "pa": 0.15,
    "mt": 0.35,
    "ss": 0.20,
    "sm": 0.20,
    "su": 0.15,
    "sc": 0.10,
    "ev": 0.05,
    "ig": 0.0,
    "wb": 0.0,
    "nd": 0.0,
}
GLIM_FELSIC = {
    "pa": 0.90,
    "va": 0.70,
    "mt": 0.55,
    "pi": 0.50,
    "pb": 0.35,
    "ss": 0.25,
    "sm": 0.25,
    "su": 0.20,
    "sc": 0.15,
    "vb": 0.10,
    "vi": 0.15,
    "py": 0.15,
    "ev": 0.05,
    "ig": 0.0,
    "wb": 0.0,
    "nd": 0.0,
}
# Crustal remanent-magnetism likelihood proxy (not Earth dipole field).
GLIM_MAGNETIC = {
    "vb": 0.85,
    "vi": 0.80,
    "va": 0.75,
    "py": 0.70,
    "pb": 0.65,
    "pi": 0.60,
    "pa": 0.55,
    "mt": 0.45,
    "ss": 0.20,
    "sm": 0.20,
    "su": 0.15,
    "sc": 0.10,
    "ev": 0.05,
    "ig": 0.05,
    "wb": 0.0,
    "nd": 0.0,
}


@dataclass
class MoonFeatures:
    latitude_deg: float
    longitude_east_deg: float
    slope_deg: float
    relief_m: float
    roughness_m: float
    elevation_m: float | None = None
    geology_name: str | None = None
    geology_interpretation: str | None = None
    geology_symbol: str | None = None
    geology_boundary_km: float | None = None
    feo_wt_percent: float | None = None
    tio2_wt_percent: float | None = None
    al2o3_wt_percent: float | None = None
    magnetic_magnitude_nT: float | None = None
    thermal_range_k: float | None = None
    thermal_mean_k: float | None = None
    crater_name: str | None = None
    crater_rim_km: float | None = None
    crater_diameter_km: float | None = None
    sunlight_hours_cycle: float | None = None
    material_class: str = "unknown"
    source: str = "unknown"

    @property
    def mafic_index(self) -> float:
        """0–1 mafic preference from composition + mapped unit."""
        if self.material_class == "mare":
            score = 0.82
        elif self.material_class == "highland":
            score = 0.22
        else:
            score = 0.40
        if self.feo_wt_percent is not None:
            score = 0.45 * score + 0.55 * min(1.0, max(0.0, self.feo_wt_percent / 16.0))
        if self.tio2_wt_percent is not None:
            score = 0.80 * score + 0.20 * min(1.0, max(0.0, self.tio2_wt_percent / 6.0))
        if self.al2o3_wt_percent is not None and self.material_class == "highland":
            score = 0.7 * score + 0.3 * (1.0 - min(1.0, max(0.0, self.al2o3_wt_percent / 30.0)))
        return min(1.0, max(0.0, score))

    @property
    def felsic_index(self) -> float:
        # Mapped mare basalt wins over coarse GRS Al2O3 (often highland-contaminated).
        if self.material_class == "mare":
            return 0.12
        if self.material_class == "highland":
            base = 0.7
        else:
            base = 0.4
        if self.al2o3_wt_percent is not None:
            base = 0.5 * base + 0.5 * min(1.0, max(0.0, self.al2o3_wt_percent / 28.0))
        return min(1.0, max(0.0, base))

    @property
    def composition_conflict(self) -> str | None:
        if self.material_class != "mare":
            return None
        high_al = self.al2o3_wt_percent is not None and self.al2o3_wt_percent >= 18.0
        low_ti = self.tio2_wt_percent is None or self.tio2_wt_percent < 0.5
        if high_al and low_ti:
            return (
                "GRS 2° pixel oxides look highland-contaminated "
                f"(Al2O3≈{self.al2o3_wt_percent:.1f} wt%, TiO2≈{(self.tio2_wt_percent or 0):.1f} wt%) "
                "while the mapped unit is mare basalt; material matching trusts the mapped unit."
            )
        return None

    @property
    def magnetic_regime(self) -> float:
        """0 quiet → 1 anomalous crustal field (regime class, not nT equality)."""
        if self.magnetic_magnitude_nT is None:
            return 0.35
        magnitude = max(0.0, self.magnetic_magnitude_nT)
        if magnitude < 2.0:
            return 0.15
        if magnitude < 10.0:
            return 0.45
        if magnitude < 50.0:
            return 0.75
        return 0.95

    @property
    def thermal_extremity(self) -> float:
        """0 mild → 1 extreme day/night span from Diviner when present."""
        if self.thermal_range_k is None:
            # Equatorial mare sites are typically extreme; fall back by latitude.
            return max(0.35, 1.0 - abs(self.latitude_deg) / 90.0)
        return min(1.0, max(0.0, self.thermal_range_k / 320.0))


@dataclass
class EarthStats:
    slope_mean: float
    slope_std: float
    log_relief_mean: float
    log_relief_std: float
    log_rough_mean: float
    log_rough_std: float
    texture_mean: float
    texture_std: float


@dataclass
class EarthImpactCrater:
    name: str
    latitude_deg: float
    longitude_deg: float
    diameter_km: float
    target_rock: str | None = None


@dataclass
class EarthCandidate:
    cell_id: int
    latitude_deg: float
    longitude_east_deg: float
    longitude_west_deg: float
    land_fraction: float
    mean_elevation_m: float
    slope_deg: float
    local_relief_m: float
    roughness_std_m: float
    distance: float = 0.0
    agreement_score: float = 0.0
    aspect_scores: dict[str, float] = field(default_factory=dict)
    aspect_agreement: dict[str, float] = field(default_factory=dict)
    contributions: dict[str, float] = field(default_factory=dict)
    active_aspects: list[str] = field(default_factory=list)
    glim_code: str | None = None
    glim_name: str | None = None
    nearest_crater_name: str | None = None
    nearest_crater_distance_km: float | None = None
    nearest_crater_diameter_km: float | None = None
    impact_relevant: bool = False
    arid_suitability: float = 0.0
    arid_percentile: float | None = None
    disqualified: bool = False
    disqualify_reason: str | None = None
    reasons: list[str] = field(default_factory=list)
    caveats: list[str] = field(default_factory=list)


def validate_moon_coordinate(latitude: float, longitude: float) -> tuple[float, float]:
    if not math.isfinite(latitude) or not -90 <= latitude <= 90:
        raise ValueError("Moon latitude must be finite and between -90 and 90.")
    if not math.isfinite(longitude):
        raise ValueError("Moon longitude must be finite.")
    east = longitude % 360.0
    if east < 0:
        east += 360.0
    return latitude, 0.0 if east == 0 else east


def east_to_west_longitude(longitude_east_deg: float) -> float:
    lon = ((longitude_east_deg + 180.0) % 360.0) - 180.0
    return 0.0 if abs(lon) < 1e-12 else lon


def terrain_class(slope_deg: float, relief_m: float) -> str:
    if slope_deg < 2 and relief_m < 50:
        return "flat plain"
    if slope_deg < 5 and relief_m < 150:
        return "gently undulating"
    if slope_deg < 10:
        return "rolling / moderate relief"
    if slope_deg < 15:
        return "hilly"
    return "steep / rugged"


def infer_material_class(
    name: str | None,
    interpretation: str | None,
    feo_wt_percent: float | None = None,
    al2o3_wt_percent: float | None = None,
) -> str:
    text = f"{name or ''} {interpretation or ''}".lower()
    if any(token in text for token in MARE_TOKENS):
        return "mare"
    if any(token in text for token in HIGHLAND_TOKENS):
        return "highland"
    # Oxides can hint, but without a mapped unit the class stays unknown for scoring.
    return "unknown"


def material_is_scored(moon: MoonFeatures) -> bool:
    """Unknown lunar class → material unverified and excluded from the score."""
    return moon.material_class in {"mare", "highland"}


def in_anorthosite_province(latitude: float, longitude_west: float) -> bool:
    for lat_lo, lat_hi, lon_lo, lon_hi in ANORTHOSITE_PROVINCES:
        if lat_lo <= latitude <= lat_hi and lon_lo <= longitude_west <= lon_hi:
            return True
    return False


def _finite_number(value: Any) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        number = float(value)
        return number if math.isfinite(number) else None
    if isinstance(value, str):
        try:
            number = float(value.strip().rstrip("°").split()[0])
        except (TypeError, ValueError, IndexError):
            return None
        return number if math.isfinite(number) else None
    return None


def _first_number(data: dict[str, Any], *keys: str) -> float | None:
    for key in keys:
        if key in data:
            number = _finite_number(data.get(key))
            if number is not None:
                return number
    return None


def _diviner_thermal(data: dict[str, Any]) -> tuple[float | None, float | None]:
    ranges: list[float] = []
    means: list[float] = []
    for channel in (6, 7, 8, 9):
        tmax = _first_number(data, f"div{channel}_qca0_qmi0_max_k", f"div{channel}_max_k")
        tmin = _first_number(data, f"div{channel}_qca0_qmi0_min_k", f"div{channel}_min_k")
        tmean = _first_number(data, f"div{channel}_qca0_qmi0_mean_k", f"div{channel}_mean_k")
        if tmax is not None and tmin is not None and tmax > tmin:
            ranges.append(tmax - tmin)
        if tmean is not None:
            means.append(tmean)
    thermal_range = max(ranges) if ranges else None
    thermal_mean = sum(means) / len(means) if means else None
    return thermal_range, thermal_mean


def features_from_moon_report(report: dict[str, Any]) -> MoonFeatures:
    if report.get("body") != "moon":
        raise ValueError("Report body must be 'moon'.")
    coordinate = report.get("coordinate") or {}
    latitude = float(coordinate.get("latitude_deg", report.get("latitude")))
    longitude = float(coordinate.get("longitude_deg", report.get("longitude")))
    latitude, longitude = validate_moon_coordinate(latitude, longitude)

    data = report.get("data") or {}
    slope = _first_number(data, "lola_dem_slope_deg", "slope_deg", "slope")
    relief = _first_number(data, "lola_dem_relief_m", "relief_m", "relief")
    rough = _first_number(data, "lola_dem_roughness_m", "rough_m", "rough")
    if slope is None or relief is None or rough is None:
        raise ValueError(
            "Moon report is missing slope/relief/roughness terrain features needed for matching."
        )

    mapped = data.get("mapped_unit") or {}
    if isinstance(mapped, dict) and "mapped_unit" in mapped:
        mapped = mapped.get("mapped_unit") or {}
    geology_name = mapped.get("name") if isinstance(mapped, dict) else None
    geology_interpretation = mapped.get("interpretation") if isinstance(mapped, dict) else None
    geology_symbol = mapped.get("symbol") if isinstance(mapped, dict) else None
    geology_boundary = (
        _finite_number(mapped.get("boundary_distance_km")) if isinstance(mapped, dict) else None
    )

    composition = data.get("lunar_composition") or {}
    magnetic = data.get("lunar_magnetic") or {}
    crater = data.get("nearest_named_crater") or {}
    thermal_range, thermal_mean = _diviner_thermal(data)

    return MoonFeatures(
        latitude_deg=latitude,
        longitude_east_deg=longitude,
        slope_deg=max(0.0, slope),
        relief_m=max(0.0, relief),
        roughness_m=max(0.0, rough),
        elevation_m=_first_number(data, "lola_dem_elevation_m", "elev_m", "elev"),
        geology_name=geology_name,
        geology_interpretation=geology_interpretation,
        geology_symbol=geology_symbol,
        geology_boundary_km=geology_boundary,
        feo_wt_percent=_finite_number(composition.get("FeO_wt_percent"))
        if isinstance(composition, dict)
        else None,
        tio2_wt_percent=_finite_number(composition.get("TiO2_wt_percent"))
        if isinstance(composition, dict)
        else None,
        al2o3_wt_percent=_finite_number(composition.get("Al2O3_wt_percent"))
        if isinstance(composition, dict)
        else None,
        magnetic_magnitude_nT=_finite_number(magnetic.get("magnitude_nT"))
        if isinstance(magnetic, dict)
        else None,
        thermal_range_k=thermal_range,
        thermal_mean_k=thermal_mean,
        crater_name=crater.get("name") if isinstance(crater, dict) else None,
        crater_rim_km=_finite_number(crater.get("rim_distance_km"))
        if isinstance(crater, dict)
        else None,
        crater_diameter_km=_finite_number(crater.get("diameter_km"))
        if isinstance(crater, dict)
        else None,
        sunlight_hours_cycle=_first_number(data, "sunlight_hours_cycle"),
        material_class=infer_material_class(
            geology_name,
            geology_interpretation,
            feo_wt_percent=_finite_number(composition.get("FeO_wt_percent"))
            if isinstance(composition, dict)
            else None,
            al2o3_wt_percent=_finite_number(composition.get("Al2O3_wt_percent"))
            if isinstance(composition, dict)
            else None,
        ),
        source="site-reports.json",
    )


def load_report_for_coordinate(
    path: Path, latitude: float, longitude: float
) -> dict[str, Any] | None:
    latitude, longitude = validate_moon_coordinate(latitude, longitude)
    if not path.is_file():
        return None
    root = json.loads(path.read_text(encoding="utf-8"))
    reports = root.get("reports") if isinstance(root, dict) else root
    if not isinstance(reports, list):
        return None
    best = None
    best_distance = float("inf")
    for report in reports:
        if not isinstance(report, dict) or report.get("body") != "moon":
            continue
        coordinate = report.get("coordinate") or {}
        try:
            report_lat = float(coordinate.get("latitude_deg", report.get("latitude")))
            report_lon = float(coordinate.get("longitude_deg", report.get("longitude"))) % 360.0
        except (TypeError, ValueError):
            continue
        distance = abs(report_lat - latitude) + abs(((report_lon - longitude + 180) % 360) - 180)
        if distance < best_distance:
            best = report
            best_distance = distance
    if best is not None and best_distance < 1e-6:
        return best
    return None


def fetch_moon_report(latitude: float, longitude: float, report_path: Path = REPORT_PATH) -> dict[str, Any]:
    latitude, longitude = validate_moon_coordinate(latitude, longitude)
    command = [
        str(REPO_ROOT / "gradlew"),
        "run",
        "--quiet",
        f"--args=1 {latitude} {longitude}",
    ]
    if not (REPO_ROOT / "gradlew").is_file():
        command = ["gradle", "run", "--quiet", f"--args=1 {latitude} {longitude}"]
    print(
        f"Fetching Moon site report via Java Main (lever=1) for {latitude:.5f}, {longitude:.5f}°E …",
        file=sys.stderr,
    )
    completed = subprocess.run(command, cwd=REPO_ROOT, capture_output=True, text=True)
    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout or "").strip()
        raise RuntimeError(f"Moon report fetch failed (exit {completed.returncode}): {detail[:800]}")
    report = load_report_for_coordinate(report_path, latitude, longitude)
    if report is None:
        raise RuntimeError(
            f"Moon report completed but {report_path} has no matching moon entry for "
            f"{latitude}, {longitude}."
        )
    return report


def load_earth_stats(db: sqlite3.Connection) -> EarthStats:
    rows = db.execute(
        """
        SELECT slope_deg, local_relief_m, roughness_std_m
        FROM earth_cells
        WHERE slope_deg IS NOT NULL
        """
    ).fetchall()
    if len(rows) < 100:
        raise RuntimeError("Earth index does not contain enough scored land cells.")

    slopes = [float(r[0]) for r in rows]
    log_reliefs = [math.log1p(max(0.0, float(r[1]))) for r in rows]
    log_roughs = [math.log1p(max(0.0, float(r[2]))) for r in rows]
    textures = [float(r[2]) / (float(r[1]) + 1.0) for r in rows]

    def mean_std(values: list[float]) -> tuple[float, float]:
        mean = sum(values) / len(values)
        variance = sum((value - mean) ** 2 for value in values) / len(values)
        return mean, max(math.sqrt(variance), 1e-6)

    slope_mean, slope_std = mean_std(slopes)
    log_relief_mean, log_relief_std = mean_std(log_reliefs)
    log_rough_mean, log_rough_std = mean_std(log_roughs)
    texture_mean, texture_std = mean_std(textures)
    return EarthStats(
        slope_mean=slope_mean,
        slope_std=slope_std,
        log_relief_mean=log_relief_mean,
        log_relief_std=log_relief_std,
        log_rough_mean=log_rough_mean,
        log_rough_std=log_rough_std,
        texture_mean=texture_mean,
        texture_std=texture_std,
    )


def _z(value: float, mean: float, std: float) -> float:
    return (value - mean) / std


def terrain_vector(slope: float, relief: float, rough: float, stats: EarthStats) -> dict[str, float]:
    """Legacy z-vector kept for stage-1 prefilter only."""
    texture = rough / (relief + 1.0)
    return {
        "slope_deg": _z(slope, stats.slope_mean, stats.slope_std),
        "log_relief": _z(math.log1p(max(0.0, relief)), stats.log_relief_mean, stats.log_relief_std),
        "log_rough": _z(math.log1p(max(0.0, rough)), stats.log_rough_mean, stats.log_rough_std),
        "texture": _z(texture, stats.texture_mean, stats.texture_std),
    }


def terrain_distance(moon_vec: dict[str, float], earth_vec: dict[str, float]) -> float:
    total = 0.0
    for key, weight in TERRAIN_FEATURE_WEIGHTS.items():
        total += abs(moon_vec[key] - earth_vec[key]) * weight
    return total


def terrain_distance_physical(
    moon_slope: float,
    moon_relief: float,
    moon_rough: float,
    earth_slope: float,
    earth_relief: float,
    earth_rough: float,
) -> float:
    """Physical terrain mismatch in [0, 1] after the hard slope/texture gate.

    Uses absolute slope degrees (not Earth-population z-scores). Z-scoring made
    1.7° vs 1.1° look like a huge miss because global Earth slopes are ~0.3° mean.
    """
    slope_term = min(
        1.0, abs(earth_slope - moon_slope) / max(TERRAIN_SLOPE_MAX_DELTA_DEG, 1e-6)
    )
    moon_tex = max(0.0, moon_rough) / (max(0.0, moon_relief) + 1.0)
    earth_tex = max(0.0, earth_rough) / (max(0.0, earth_relief) + 1.0)
    if moon_tex < 1e-6 and earth_tex < 1e-6:
        tex_term = 0.0
    else:
        ratio = max(moon_tex, earth_tex) / max(min(moon_tex, earth_tex), 1e-6)
        tex_term = min(
            1.0,
            max(0.0, (ratio - 1.0) / max(TERRAIN_ROUGHNESS_RATIO_MAX - 1.0, 1e-6)),
        )
    relief_ratio = (max(earth_relief, moon_relief) + 1.0) / (
        min(earth_relief, moon_relief) + 1.0
    )
    relief_term = min(1.0, max(0.0, math.log(relief_ratio) / math.log(8.0)))
    return 0.65 * slope_term + 0.20 * tex_term + 0.15 * relief_term


def angular_distance_deg(
    lat1: float, lon1_east: float, lat2: float, lon2_east: float
) -> float:
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    d_phi = phi2 - phi1
    d_lambda = math.radians(((lon2_east - lon1_east + 180) % 360) - 180)
    a = (
        math.sin(d_phi / 2) ** 2
        + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    )
    return math.degrees(2 * math.asin(min(1.0, math.sqrt(a))))


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    radius = 6371.0088
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    d_phi = phi2 - phi1
    d_lambda = math.radians(lon2 - lon1)
    a = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    return 2 * radius * math.asin(min(1.0, math.sqrt(a)))


def prefilter_bounds(moon: MoonFeatures) -> tuple[float, float, float, float, float, float]:
    slope_lo = max(0.0, moon.slope_deg - PREFILTER_SLOPE_PAD_DEG)
    slope_hi = moon.slope_deg + PREFILTER_SLOPE_PAD_DEG
    relief_lo = moon.relief_m / PREFILTER_RELIEF_FACTOR
    relief_hi = max(moon.relief_m * PREFILTER_RELIEF_FACTOR, moon.relief_m + 50.0)
    rough_lo = moon.roughness_m / PREFILTER_ROUGH_FACTOR
    rough_hi = max(moon.roughness_m * PREFILTER_ROUGH_FACTOR, moon.roughness_m + 25.0)
    return slope_lo, slope_hi, relief_lo, relief_hi, rough_lo, rough_hi


def load_candidate_rows(
    db: sqlite3.Connection, moon: MoonFeatures
) -> tuple[list[sqlite3.Row], set[int]]:
    """Return candidate rows plus cell ids that must survive terrain shortlisting."""
    slope_lo, slope_hi, relief_lo, relief_hi, rough_lo, rough_hi = prefilter_bounds(moon)
    db.row_factory = sqlite3.Row
    rows = db.execute(
        """
        SELECT id, latitude_deg, longitude_east_deg, land_fraction,
               mean_elevation_m, slope_deg, local_relief_m, roughness_std_m
        FROM earth_cells
        WHERE slope_deg IS NOT NULL
          AND slope_deg BETWEEN ? AND ?
          AND local_relief_m BETWEEN ? AND ?
          AND roughness_std_m BETWEEN ? AND ?
        """,
        (slope_lo, slope_hi, relief_lo, relief_hi, rough_lo, rough_hi),
    ).fetchall()
    if len(rows) < 200:
        rows = db.execute(
            """
            SELECT id, latitude_deg, longitude_east_deg, land_fraction,
                   mean_elevation_m, slope_deg, local_relief_m, roughness_std_m
            FROM earth_cells
            WHERE slope_deg IS NOT NULL
            """
        ).fetchall()

    force_keep: set[int] = set()
    # Pull terrain-compatible cells near similarly sized Earth impact structures.
    if moon.crater_diameter_km is not None:
        seen = {int(row["id"]) for row in rows}
        extras: list[sqlite3.Row] = []
        for crater in load_earth_impact_craters():
            if not (
                moon.crater_diameter_km / 4.0
                <= crater.diameter_km
                <= moon.crater_diameter_km * 4.0
            ):
                continue
            lon_east = crater.longitude_deg % 360.0
            if lon_east < 0:
                lon_east += 360.0
            nearby = db.execute(
                """
                SELECT id, latitude_deg, longitude_east_deg, land_fraction,
                       mean_elevation_m, slope_deg, local_relief_m, roughness_std_m
                FROM earth_cells
                WHERE slope_deg IS NOT NULL
                  AND slope_deg BETWEEN ? AND ?
                  AND local_relief_m BETWEEN ? AND ?
                  AND roughness_std_m BETWEEN ? AND ?
                  AND latitude_deg BETWEEN ? AND ?
                """,
                (
                    slope_lo,
                    slope_hi,
                    relief_lo,
                    relief_hi,
                    rough_lo,
                    rough_hi,
                    crater.latitude_deg - 1.25,
                    crater.latitude_deg + 1.25,
                ),
            ).fetchall()
            for row in nearby:
                # Filter longitude in Python to handle wrap safely.
                row_lon = float(row["longitude_east_deg"])
                delta_lon = abs(((row_lon - lon_east + 180.0) % 360.0) - 180.0)
                if delta_lon > 1.25:
                    continue
                cell_id = int(row["id"])
                force_keep.add(cell_id)
                if cell_id not in seen:
                    seen.add(cell_id)
                    extras.append(row)
        if extras:
            rows = list(rows) + extras
    return rows, force_keep


_GLIM_CACHE: dict[str, Any] | None = None
_CRATER_CACHE: list[EarthImpactCrater] | None = None


def _load_glim_cache() -> dict[str, Any] | None:
    global _GLIM_CACHE
    if _GLIM_CACHE is not None:
        return _GLIM_CACHE if _GLIM_CACHE else None
    if not GLIM_RASTER.is_file() or not GLIM_LEGEND.is_file():
        _GLIM_CACHE = {}
        return None
    try:
        from glim_reader import CLASS_NAMES, _read_header, _read_legend

        legend = _read_legend(GLIM_LEGEND)
        with GLIM_RASTER.open(encoding="ascii") as raster:
            header = _read_header(raster)
            grid = [list(map(int, raster.readline().split())) for _ in range(360)]
        _GLIM_CACHE = {"header": header, "grid": grid, "legend": legend, "names": CLASS_NAMES}
        return _GLIM_CACHE
    except Exception:
        _GLIM_CACHE = {}
        return None


def sample_glim_safe(latitude: float, longitude_west: float) -> tuple[str | None, str | None]:
    cache = _load_glim_cache()
    if not cache:
        return None, None
    try:
        header = cache["header"]
        row = min(359, max(0, int((90 - latitude) / header["cellsize"])))
        column = min(719, max(0, int((longitude_west + 180) / header["cellsize"])))
        code = cache["grid"][row][column]
        if code == int(header["nodata_value"]):
            return None, None
        abbreviation = cache["legend"].get(code)
        if abbreviation is None:
            return None, None
        return abbreviation, cache["names"].get(abbreviation, "Unrecognized GLiM class")
    except Exception:
        return None, None


def load_earth_impact_craters(path: Path = CRATER_CATALOG_PATH) -> list[EarthImpactCrater]:
    global _CRATER_CACHE
    if _CRATER_CACHE is not None:
        return _CRATER_CACHE
    craters: list[EarthImpactCrater] = []
    if not path.is_file():
        _CRATER_CACHE = craters
        return craters
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            try:
                lat = float(row["latitude"])
                lon = float(row["longitude"])
                diameter = float(row["diameter_km"])
            except (KeyError, TypeError, ValueError):
                continue
            if not math.isfinite(lat) or not math.isfinite(lon) or not math.isfinite(diameter):
                continue
            if diameter <= 0:
                continue
            craters.append(
                EarthImpactCrater(
                    name=row.get("crater_name") or "unknown",
                    latitude_deg=lat,
                    longitude_deg=lon,
                    diameter_km=diameter,
                    target_rock=(row.get("target_rock") or None),
                )
            )
    _CRATER_CACHE = craters
    return craters


def nearest_earth_crater(
    latitude: float, longitude_west: float, craters: list[EarthImpactCrater]
) -> tuple[EarthImpactCrater | None, float | None]:
    if not craters:
        return None, None
    best = None
    best_distance = float("inf")
    for crater in craters:
        distance = haversine_km(latitude, longitude_west, crater.latitude_deg, crater.longitude_deg)
        if distance < best_distance:
            best = crater
            best_distance = distance
    return best, best_distance


def arid_suitability(latitude: float, longitude_west: float, glim_code: str | None) -> float:
    """0 humid/vegetated-like → 1 arid barren-like. Local heuristic, not a climate model."""
    if glim_code in {"ig", "wb"} or abs(latitude) >= POLAR_ICE_LATITUDE:
        return 0.02
    abs_lat = abs(latitude)
    lon = longitude_west

    # Named arid provinces (rough bounding boxes).
    deserts = (
        (15, 36, -17, 60),    # Sahara + Arabia
        (12, 18, 32, 52),     # Horn / Afar dry corridor
        (22, 42, -120, -100), # SW North America deserts
        (-32, -18, -75, -67), # Atacama
        (-33, -18, 114, 150), # Australian deserts
        (35, 50, 50, 90),     # Central Asian deserts
        (18, 32, 70, 78),     # Thar fringe
        (-30, -18, 12, 25),   # Namib / Kalahari north
    )
    for lat_lo, lat_hi, lon_lo, lon_hi in deserts:
        if lat_lo <= latitude <= lat_hi and lon_lo <= lon <= lon_hi:
            return 0.92 if glim_code in MARE_ACCEPTABLE_GLIM | {"vb", "pb", "su", "ss"} else 0.82

    if 15 <= abs_lat <= 35:
        base = 0.55
    elif abs_lat < 10:
        base = 0.28  # wet tropics default; arid boxes above can override
    elif abs_lat <= 42:
        base = 0.18  # temperate humid belt (e.g. Black Sea / Anatolia coast)
    else:
        base = 0.08

    if glim_code in {"vb", "py", "pb"}:
        base += 0.05
    if glim_code in {"sc", "sm"} and 35 <= abs_lat <= 50:
        base -= 0.05
    return min(1.0, max(0.0, base))


def material_distance(moon: MoonFeatures, glim_code: str | None) -> float:
    """Distance in material space. Caller must not use this when class is unknown."""
    if glim_code in {None, "nd", "wb", "ig"}:
        return 1.2
    if moon.material_class == "mare":
        if glim_code == "vb":
            return 0.05
        if glim_code == "py":
            return 0.22
        if glim_code == "pb":
            return 0.40
        if glim_code == "vi":
            return 0.95
        if glim_code == "va":
            return 1.05
        return 1.15
    if moon.material_class == "highland":
        # Lunar highlands → plagioclase-rich / anorthositic massif proxies, not volcanics.
        if glim_code == "pa":
            return 0.08
        if glim_code == "mt":
            return 0.12
        if glim_code == "pb":
            return 0.18
        if glim_code in HIGHLAND_REJECT_GLIM:
            return 1.10
        return 0.65
    # unknown — should not be scored; treat as maximally unverified if called anyway
    return 1.0


def latitude_solar_distance(moon: MoonFeatures, earth_lat: float) -> float:
    """Sun-angle / day-length mismatch penalty. Large |Δφ| must hurt."""
    delta = abs(abs(moon.latitude_deg) - abs(earth_lat)) / 90.0
    return min(1.0, delta * delta * 2.8 + delta * 0.55)


def terrain_gate_ok(moon: MoonFeatures, candidate: EarthCandidate) -> tuple[bool, str | None]:
    """Require slope within ±3° and roughness texture within ~3× before ranking.

    Absolute roughness metres are not compared directly: lunar LDEM neighborhoods and
    Earth ETOPO5 cells have different baselines. Until a shared ~30 m DEM exists on both
    bodies, gate on slope (scale-stable) and roughness/relief texture ratio.
    """
    slope_delta = abs(candidate.slope_deg - moon.slope_deg)
    if slope_delta > TERRAIN_SLOPE_MAX_DELTA_DEG:
        return False, (
            f"slope Δ={slope_delta:.2f}° exceeds ±{TERRAIN_SLOPE_MAX_DELTA_DEG:.0f}° gate "
            f"(Earth {candidate.slope_deg:.2f}° vs Moon {moon.slope_deg:.2f}°)"
        )

    moon_tex = max(0.0, moon.roughness_m) / (max(0.0, moon.relief_m) + 1.0)
    earth_tex = max(0.0, candidate.roughness_std_m) / (
        max(0.0, candidate.local_relief_m) + 1.0
    )
    if moon_tex < 1e-6 and earth_tex < 1e-6:
        return True, None
    if moon_tex < 1e-6 or earth_tex < 1e-6:
        if max(moon_tex, earth_tex) > 0.5:
            return False, (
                f"roughness/relief texture not within {TERRAIN_ROUGHNESS_RATIO_MAX:.0f}× "
                f"(Earth {earth_tex:.3f} vs Moon {moon_tex:.3f})"
            )
        return True, None
    ratio = max(moon_tex, earth_tex) / min(moon_tex, earth_tex)
    if ratio > TERRAIN_ROUGHNESS_RATIO_MAX:
        return False, (
            f"roughness/relief texture ratio {ratio:.1f}× exceeds "
            f"{TERRAIN_ROUGHNESS_RATIO_MAX:.0f}× gate "
            f"(Earth {earth_tex:.3f} vs Moon {moon_tex:.3f}; "
            "absolute metres not gated across DEM baselines)"
        )
    return True, None


def impact_relevant(
    moon: MoonFeatures, crater_distance_km: float | None, crater_diameter_km: float | None
) -> bool:
    if moon.crater_rim_km is None or crater_distance_km is None:
        return False
    if crater_distance_km > IMPACT_MAX_DISTANCE_KM:
        return False
    if moon.crater_diameter_km is not None and crater_diameter_km is not None and crater_diameter_km > 0:
        ratio = max(moon.crater_diameter_km, crater_diameter_km) / min(
            moon.crater_diameter_km, crater_diameter_km
        )
        if ratio > IMPACT_DIAMETER_RATIO_MAX:
            return False
    return True


def impact_distance(
    moon: MoonFeatures, crater_distance_km: float | None, crater_diameter_km: float | None
) -> float:
    if not impact_relevant(moon, crater_distance_km, crater_diameter_km):
        return 0.0
    assert crater_distance_km is not None
    moon_prox = math.log1p(max(0.0, moon.crater_rim_km or 0.0))
    earth_prox = math.log1p(max(0.0, crater_distance_km))
    proximity = abs(moon_prox - earth_prox) / 5.0
    if moon.crater_diameter_km is not None and crater_diameter_km is not None:
        size = abs(math.log1p(moon.crater_diameter_km) - math.log1p(crater_diameter_km)) / 5.0
    else:
        size = 0.15
    return min(1.0, 0.7 * proximity + 0.3 * size)


def candidate_disqualify_reason(moon: MoonFeatures, candidate: EarthCandidate) -> str | None:
    gate_ok, gate_reason = terrain_gate_ok(moon, candidate)
    if not gate_ok:
        return gate_reason

    if candidate.glim_code in {"ig", "wb"}:
        return "ice/water surface"
    # Antarctic/Greenland ice-sheet latitudes are never lunar rock analogs.
    if abs(candidate.latitude_deg) >= POLAR_ICE_LATITUDE:
        return "polar ice-sheet latitude — not a lunar rock analog"

    if moon.material_class == "mare" and candidate.glim_code not in MARE_ACCEPTABLE_GLIM | {"pb"}:
        if candidate.glim_code == "vi":
            return "intermediate volcanic rocks are not mare basalt"
        if candidate.glim_code is not None:
            return f"GLiM {candidate.glim_code} is not basic/basaltic volcanic"

    if moon.material_class == "highland":
        if candidate.glim_code in HIGHLAND_REJECT_GLIM:
            return (
                f"GLiM {candidate.glim_code} is volcanic; highland/anorthosite analogs "
                "need plagioclase-rich plutonic/metamorphic rocks"
            )

    # Unknown class: do not score material, but still reject clear volcanic false-friends
    # when oxides look highland-like (high Al2O3, low FeO).
    if moon.material_class == "unknown":
        highland_like = (
            moon.al2o3_wt_percent is not None
            and moon.al2o3_wt_percent >= 18.0
            and (moon.feo_wt_percent is None or moon.feo_wt_percent < 8.0)
        )
        if highland_like and candidate.glim_code in HIGHLAND_REJECT_GLIM:
            return (
                "unknown lunar class with highland-like oxides cannot map to volcanic lithology"
            )

    if abs(moon.latitude_deg) <= 15 and moon.thermal_extremity >= 0.7:
        if abs(candidate.latitude_deg) > 32:
            return "latitude too high for equatorial lunar solar/thermal regime"
        if candidate.arid_suitability < 0.45:
            return "not arid/barren enough for extreme lunar thermal regime"
    return None


def score_candidate(
    moon: MoonFeatures,
    candidate: EarthCandidate,
    moon_terrain_vec: dict[str, float],
    stats: EarthStats,
) -> None:
    candidate.arid_suitability = arid_suitability(
        candidate.latitude_deg, candidate.longitude_west_deg, candidate.glim_code
    )
    if moon.material_class == "highland" and in_anorthosite_province(
        candidate.latitude_deg, candidate.longitude_west_deg
    ):
        candidate.arid_suitability = max(candidate.arid_suitability, 0.55)

    candidate.impact_relevant = impact_relevant(
        moon, candidate.nearest_crater_distance_km, candidate.nearest_crater_diameter_km
    )
    reason = candidate_disqualify_reason(moon, candidate)
    if reason:
        candidate.disqualified = True
        candidate.disqualify_reason = reason

    if abs(moon.latitude_deg) >= 60:
        # Polar lunar sites need exposed rock, not subtropical desert scoring.
        arid_target = 0.40
    elif moon.thermal_extremity >= 0.7:
        arid_target = 0.85
    else:
        arid_target = 0.55
    aspects: dict[str, float] = {
        "terrain": terrain_distance_physical(
            moon.slope_deg,
            moon.relief_m,
            moon.roughness_m,
            candidate.slope_deg,
            candidate.local_relief_m,
            candidate.roughness_std_m,
        ),
        "latitude_solar": latitude_solar_distance(moon, candidate.latitude_deg),
        "arid_suitability": abs(arid_target - candidate.arid_suitability),
    }
    active = ["terrain", "latitude_solar", "arid_suitability"]

    if material_is_scored(moon):
        aspects["material"] = material_distance(moon, candidate.glim_code)
        if (
            moon.material_class == "highland"
            and in_anorthosite_province(candidate.latitude_deg, candidate.longitude_west_deg)
            and candidate.glim_code in HIGHLAND_PREFERRED_GLIM
        ):
            aspects["material"] *= 0.5
        active.append("material")

    if candidate.impact_relevant:
        aspects["impact_setting"] = impact_distance(
            moon, candidate.nearest_crater_distance_km, candidate.nearest_crater_diameter_km
        )
        active.append("impact_setting")

    weight_sum = sum(ASPECT_WEIGHTS[key] for key in active)
    contributions = {
        key: aspects[key] * (ASPECT_WEIGHTS[key] / weight_sum) for key in active
    }
    distance = sum(contributions.values())
    agreement = {key: max(0.0, 1.0 - aspects[key]) for key in active}

    # Keep below-threshold candidates scored so they can serve as last-resort analogs.
    if agreement["terrain"] < MIN_TERRAIN_AGREEMENT:
        candidate.disqualified = True
        if candidate.disqualify_reason is None:
            candidate.disqualify_reason = (
                f"terrain agreement {agreement['terrain']:.0%} below "
                f"{MIN_TERRAIN_AGREEMENT:.0%} minimum"
            )

    # Rank by terrain-weighted agreement so arid/material cannot crown a terrain miss.
    # Latitude only enters as a penalty term (already a distance).
    agreement_score = sum(
        agreement[key] * (ASPECT_WEIGHTS[key] / weight_sum) for key in active
    )
    # Extra explicit terrain dominance: overall cannot exceed terrain agreement by much.
    agreement_score = min(agreement_score, agreement["terrain"] + 0.15)

    candidate.aspect_scores = aspects
    candidate.aspect_agreement = agreement
    candidate.contributions = contributions
    candidate.active_aspects = active
    candidate.distance = distance
    candidate.agreement_score = agreement_score


def explain_candidate(moon: MoonFeatures, candidate: EarthCandidate) -> None:
    labels = {
        "terrain": "terrain shape",
        "material": "material / basaltic lithology",
        "latitude_solar": "latitude / solar regime",
        "arid_suitability": "arid/barren suitability",
        "impact_setting": "impact-crater setting",
    }
    reasons: list[str] = []
    if candidate.aspect_agreement:
        ranked = sorted(candidate.aspect_agreement.items(), key=lambda item: -item[1])
        best = labels.get(ranked[0][0], ranked[0][0])
        worst = labels.get(ranked[-1][0], ranked[-1][0])
        reasons.append(
            f"Highest aspect agreement: {best} ({ranked[0][1]:.0%}); "
            f"lowest: {worst} ({ranked[-1][1]:.0%}). "
            f"(Shares below are mismatch contributions, not 'how much it matched'.)"
        )

    reasons.append(
        f"Terrain: Earth slope {candidate.slope_deg:.2f}° / relief {candidate.local_relief_m:.0f} m "
        f"/ roughness {candidate.roughness_std_m:.0f} m vs Moon "
        f"{moon.slope_deg:.2f}° / {moon.relief_m:.1f} m / {moon.roughness_m:.1f} m "
        f"({terrain_class(candidate.slope_deg, candidate.local_relief_m)} vs "
        f"{terrain_class(moon.slope_deg, moon.relief_m)}). "
        "Note: Earth cell baseline is ~10' ETOPO5; lunar values are local LDEM."
    )

    material_bits = [f"lunar class={moon.material_class}"]
    if moon.geology_interpretation:
        material_bits.append(moon.geology_interpretation)
    if moon.feo_wt_percent is not None:
        material_bits.append(f"FeO≈{moon.feo_wt_percent:.1f} wt%")
    if moon.tio2_wt_percent is not None:
        material_bits.append(f"TiO2≈{moon.tio2_wt_percent:.1f} wt%")
    if moon.al2o3_wt_percent is not None:
        material_bits.append(f"Al2O3≈{moon.al2o3_wt_percent:.1f} wt%")
    if not material_is_scored(moon):
        reasons.append(
            "Material: lunar class=unknown → unverified; material was excluded from the score."
            + (
                f" Earth GLiM at site: {candidate.glim_name} ({candidate.glim_code})."
                if candidate.glim_name
                else ""
            )
        )
    elif candidate.glim_name:
        target = (
            "anorthosite/plagioclase-rich plutonic-metamorphic proxies"
            if moon.material_class == "highland"
            else "basic/basaltic volcanic proxies"
        )
        reasons.append(
            "Material: "
            + ", ".join(material_bits)
            + f" → Earth GLiM {candidate.glim_name} ({candidate.glim_code}); "
            f"target family: {target}."
        )
    else:
        reasons.append("Material: " + ", ".join(material_bits) + " (no GLiM sample).")
    if moon.composition_conflict:
        reasons.append("Composition caveat: " + moon.composition_conflict)

    lat_delta = abs(abs(moon.latitude_deg) - abs(candidate.latitude_deg))
    reasons.append(
        f"Latitude/solar: |φ| Moon {abs(moon.latitude_deg):.1f}° vs Earth "
        f"{abs(candidate.latitude_deg):.1f}° (Δ={lat_delta:.1f}°). "
        f"Agreement {candidate.aspect_agreement.get('latitude_solar', 0):.0%}."
    )

    pct = candidate.arid_percentile
    pct_text = f"{pct:.0%} percentile among scored candidates" if pct is not None else "n/a"
    if moon.thermal_range_k is not None:
        reasons.append(
            f"Thermal: lunar Diviner span ≈ {moon.thermal_range_k:.0f} K cannot be matched in Kelvin "
            f"on Earth. Instead ranked arid/barren suitability={candidate.arid_suitability:.2f} "
            f"({pct_text}). No claim of equal diurnal amplitude."
        )
    else:
        reasons.append(
            f"Thermal/aridity: arid/barren suitability={candidate.arid_suitability:.2f} ({pct_text})."
        )

    if candidate.impact_relevant and candidate.nearest_crater_name:
        reasons.append(
            f"Impact setting (active): Moon near {moon.crater_name or 'named crater'} "
            f"({moon.crater_rim_km:.1f} km, Ø {moon.crater_diameter_km} km) ↔ Earth "
            f"{candidate.nearest_crater_name} ({candidate.nearest_crater_distance_km:.0f} km, "
            f"Ø {candidate.nearest_crater_diameter_km} km)."
        )
    elif moon.crater_rim_km is not None and candidate.nearest_crater_name:
        reasons.append(
            f"Impact setting: not scored — nearest Earth crater {candidate.nearest_crater_name} "
            f"is {candidate.nearest_crater_distance_km:.0f} km away "
            f"(Ø {candidate.nearest_crater_diameter_km} km) and is not a relevant analog to "
            f"lunar {moon.crater_name} ({moon.crater_rim_km:.1f} km, Ø {moon.crater_diameter_km} km)."
        )

    if moon.magnetic_magnitude_nT is not None:
        reasons.append(
            f"Magnetic: lunar crustal |B|≈{moon.magnetic_magnitude_nT:.2f} nT is context only. "
            "Earth's ~45,000 nT core field is not comparable; magnetic is not in the score."
        )

    if candidate.contributions and candidate.distance > 0:
        mismatch_share = {
            key: value / candidate.distance for key, value in candidate.contributions.items()
        }
        reasons.append(
            "Mismatch contribution shares (higher = hurt the rank more): "
            + ", ".join(
                f"{labels.get(k, k)}={v:.0%}"
                for k, v in sorted(mismatch_share.items(), key=lambda kv: -kv[1])
            )
            + "."
        )

    candidate.caveats = [
        "Earth terrain cells are ~10-arc-minute ETOPO5 summaries; lunar terrain is local LDEM — baselines differ.",
        "Mare queries require GLiM basic volcanic (vb); intermediate volcanic is rejected.",
        "No Earth site matches a ~300 K lunar temperature swing; arid suitability is a percentile proxy.",
        "Impact is scored only for proximal, size-compatible Earth craters; otherwise excluded.",
        "Magnetic nT values are never equated across bodies and are not scored.",
        "Absolute elevation, SoilGrids chemistry, weather, and infrastructure are excluded.",
    ]
    if candidate.disqualify_reason:
        reasons.append(f"Lower-confidence fallback: {candidate.disqualify_reason}.")
        candidate.caveats.append(
            f"Did not meet preferred analog filters: {candidate.disqualify_reason}."
        )
    if moon.composition_conflict:
        candidate.caveats.insert(0, moon.composition_conflict)
    candidate.reasons = reasons


def diversify(
    ranked: list[EarthCandidate], top_k: int, min_separation_deg: float
) -> list[EarthCandidate]:
    selected: list[EarthCandidate] = []
    for candidate in ranked:
        if all(
            angular_distance_deg(
                candidate.latitude_deg,
                candidate.longitude_east_deg,
                other.latitude_deg,
                other.longitude_east_deg,
            )
            >= min_separation_deg
            for other in selected
        ):
            selected.append(candidate)
        if len(selected) >= top_k:
            break
    if len(selected) < top_k:
        for candidate in ranked:
            if candidate in selected:
                continue
            selected.append(candidate)
            if len(selected) >= top_k:
                break
    return selected


def match_earth_analogs(
    moon: MoonFeatures,
    index_path: Path = INDEX_PATH,
    top_k: int = DEFAULT_TOP_K,
    min_separation_deg: float = DEFAULT_MIN_SEPARATION_DEG,
    enrich_glim: bool = True,
) -> dict[str, Any]:
    if not index_path.is_file():
        raise FileNotFoundError(
            f"Earth index not found: {index_path}. Run python3 build_index.py first."
        )

    with sqlite3.connect(index_path) as db:
        stats = load_earth_stats(db)
        raw_rows, force_keep = load_candidate_rows(db, moon)
        # Materialize rows before the connection closes.
        rows = [
            {
                "id": int(row["id"]),
                "latitude_deg": float(row["latitude_deg"]),
                "longitude_east_deg": float(row["longitude_east_deg"]),
                "land_fraction": float(row["land_fraction"]),
                "mean_elevation_m": float(row["mean_elevation_m"]),
                "slope_deg": float(row["slope_deg"]),
                "local_relief_m": float(row["local_relief_m"]),
                "roughness_std_m": float(row["roughness_std_m"]),
            }
            for row in raw_rows
        ]

    craters = load_earth_impact_craters()
    moon_terrain_vec = terrain_vector(moon.slope_deg, moon.relief_m, moon.roughness_m, stats)

    # Stage 1: cheap terrain ranking over the full prefilter.
    stage1: list[tuple[float, dict[str, float]]] = []
    forced_rows: list[dict[str, float]] = []
    for row in rows:
        distance = terrain_distance(
            moon_terrain_vec,
            terrain_vector(
                float(row["slope_deg"]),
                float(row["local_relief_m"]),
                float(row["roughness_std_m"]),
                stats,
            ),
        )
        if int(row["id"]) in force_keep:
            forced_rows.append(row)
        stage1.append((distance, row))
    stage1.sort(key=lambda item: item[0])
    stage1_rows = [row for _, row in stage1[: max(SHORTLIST_LIMIT, 4000)]]
    seen_stage = {int(row["id"]) for row in stage1_rows}
    for row in forced_rows:
        cell_id = int(row["id"])
        if cell_id not in seen_stage:
            stage1_rows.append(row)
            seen_stage.add(cell_id)

    # Stage 2: multi-aspect scoring with GLiM + impact catalog on the shortlist.
    scored: list[EarthCandidate] = []
    for row in stage1_rows:
        lon_east = float(row["longitude_east_deg"])
        lon_west = east_to_west_longitude(lon_east)
        lat = float(row["latitude_deg"])
        code = name = None
        if enrich_glim:
            code, name = sample_glim_safe(lat, lon_west)
        nearest, nearest_km = nearest_earth_crater(lat, lon_west, craters)
        candidate = EarthCandidate(
            cell_id=int(row["id"]),
            latitude_deg=lat,
            longitude_east_deg=lon_east,
            longitude_west_deg=lon_west,
            land_fraction=float(row["land_fraction"]),
            mean_elevation_m=float(row["mean_elevation_m"]),
            slope_deg=float(row["slope_deg"]),
            local_relief_m=float(row["local_relief_m"]),
            roughness_std_m=float(row["roughness_std_m"]),
            glim_code=code,
            glim_name=name,
            nearest_crater_name=None if nearest is None else nearest.name,
            nearest_crater_distance_km=nearest_km,
            nearest_crater_diameter_km=None if nearest is None else nearest.diameter_km,
        )
        score_candidate(moon, candidate, moon_terrain_vec, stats)
        scored.append(candidate)

    eligible = [item for item in scored if not item.disqualified]
    if moon.material_class == "mare":
        basaltic = [item for item in eligible if item.glim_code in MARE_REQUIRED_GLIM]
        if len(basaltic) >= max(top_k, 3):
            eligible = basaltic
        else:
            loose = [item for item in eligible if item.glim_code in MARE_ACCEPTABLE_GLIM | {"pb"}]
            if loose:
                eligible = loose
    elif moon.material_class == "highland":
        preferred = [item for item in eligible if item.glim_code in HIGHLAND_PREFERRED_GLIM]
        if len(preferred) >= max(top_k, 3):
            eligible = preferred

    # Keep only candidates that clear overall agreement after terrain gating.
    good = [
        item
        for item in eligible
        if item.agreement_score >= MIN_OVERALL_AGREEMENT
        and item.aspect_agreement.get("terrain", 0.0) >= MIN_TERRAIN_AGREEMENT
    ]

    # Prefer strong matches, then eligible weak matches, then the best remaining
    # scored Earth cell so a valid index always yields a useful coordinate.
    fallback_used = not good
    fallback_candidates = [
        item
        for item in scored
        if item.glim_code not in {"ig", "wb"}
        and abs(item.latitude_deg) < POLAR_ICE_LATITUDE
    ]
    ranked_candidates = good or eligible or fallback_candidates or scored
    ordered_arid = sorted(item.arid_suitability for item in ranked_candidates)
    for item in ranked_candidates:
        if not ordered_arid:
            item.arid_percentile = None
        else:
            rank = sum(1 for value in ordered_arid if value <= item.arid_suitability)
            item.arid_percentile = rank / len(ordered_arid)

    ranked_candidates.sort(key=lambda item: (-item.agreement_score, item.distance))
    selected = diversify(
        ranked_candidates, top_k=top_k, min_separation_deg=min_separation_deg
    )
    for candidate in selected:
        explain_candidate(moon, candidate)
        if fallback_used and not candidate.disqualify_reason:
            candidate.reasons.append(
                "Lower-confidence fallback: did not meet the preferred overall agreement threshold."
            )
            candidate.caveats.append(
                "Did not meet the preferred overall agreement threshold."
            )

    status = "ok" if selected else "no_good_analog_found"
    status_message = None
    if fallback_used and selected:
        status_message = (
            "No candidate met the preferred agreement and terrain thresholds. "
            "Showing the best available lower-confidence Earth analog; review its "
            "score and caveats."
        )
    elif not selected:
        status_message = (
            "No Earth land candidates with usable terrain data were found in the index."
        )

    return {
        "status": status,
        "status_message": status_message,
        "query": {
            "body": "moon",
            "coordinate": {
                "latitude_deg": moon.latitude_deg,
                "longitude_deg": moon.longitude_east_deg,
                "longitude_convention": "east_0_to_360",
            },
            "features": {
                "slope_deg": round(moon.slope_deg, 3),
                "relief_m": round(moon.relief_m, 3),
                "roughness_m": round(moon.roughness_m, 3),
                "elevation_m": None if moon.elevation_m is None else round(moon.elevation_m, 3),
                "terrain_class": terrain_class(moon.slope_deg, moon.relief_m),
                "material_class": moon.material_class,
                "mafic_index": round(moon.mafic_index, 3),
                "felsic_index": round(moon.felsic_index, 3),
                "geology_symbol": moon.geology_symbol,
                "geology_name": moon.geology_name,
                "geology_interpretation": moon.geology_interpretation,
                "geology_boundary_km": moon.geology_boundary_km,
                "feo_wt_percent": None
                if moon.feo_wt_percent is None
                else round(moon.feo_wt_percent, 2),
                "tio2_wt_percent": None
                if moon.tio2_wt_percent is None
                else round(moon.tio2_wt_percent, 2),
                "al2o3_wt_percent": None
                if moon.al2o3_wt_percent is None
                else round(moon.al2o3_wt_percent, 2),
                "composition_conflict": moon.composition_conflict,
                "magnetic_magnitude_nT": None
                if moon.magnetic_magnitude_nT is None
                else round(moon.magnetic_magnitude_nT, 2),
                "magnetic_regime": round(moon.magnetic_regime, 3),
                "thermal_range_k": None if moon.thermal_range_k is None else round(moon.thermal_range_k, 2),
                "thermal_extremity": round(moon.thermal_extremity, 3),
                "crater_name": moon.crater_name,
                "crater_rim_km": moon.crater_rim_km,
                "crater_diameter_km": moon.crater_diameter_km,
                "sunlight_hours_cycle": moon.sunlight_hours_cycle,
                "source": moon.source,
            },
        },
        "method": {
            "earth_index": str(index_path),
            "earth_impact_catalog": str(CRATER_CATALOG_PATH) if CRATER_CATALOG_PATH.is_file() else None,
            "earth_impact_craters_loaded": len(craters),
            "candidate_cells_considered": len(scored),
            "candidate_cells_eligible": len(eligible),
            "candidate_cells_good": len(good),
            "aspect_weights": ASPECT_WEIGHTS,
            "terrain_subweights": TERRAIN_FEATURE_WEIGHTS,
            "terrain_gate": {
                "max_slope_delta_deg": TERRAIN_SLOPE_MAX_DELTA_DEG,
                "max_roughness_ratio": TERRAIN_ROUGHNESS_RATIO_MAX,
                "min_terrain_agreement": MIN_TERRAIN_AGREEMENT,
                "min_overall_agreement": MIN_OVERALL_AGREEMENT,
                "baseline_note": (
                    "Gate uses available lunar LDEM vs Earth ETOPO5 cell stats; "
                    "a shared 30 m DEM baseline is the intended future upgrade."
                ),
            },
            "material_scored": material_is_scored(moon),
            "material_status": (
                "scored"
                if material_is_scored(moon)
                else "unverified_excluded_unknown_lunar_class"
            ),
            "scored_aspects": [
                "terrain (hard gate)",
                "material (mare/highland only; unknown excluded)",
                "latitude_solar (mismatch penalty)",
                "arid_suitability (percentile; not Kelvin equality)",
                "impact_setting (only if proximal + size-compatible)",
            ],
            "not_scored": [
                "magnetic field / remanence proxies",
                "absolute elevation",
                "Earth soil chemistry / SoilGrids",
                "weather / rainfall measurements",
                "Kelvin-equal thermal amplitude",
                "material when lunar class is unknown",
            ],
            "diversification_min_separation_deg": min_separation_deg,
            "notes": [
                "Preferred matches use terrain slope ±3°, roughness within 3×, and minimum agreement thresholds.",
                "If no preferred match clears the thresholds, the best available lower-confidence Earth candidate is returned.",
                "Highland maps to anorthosite/plagioclase-rich plutonic-metamorphic proxies (pa/mt/pb).",
                "Unknown lunar class: material unverified and excluded from the score.",
                "Latitude is a sun-angle mismatch penalty, not a positive match credit.",
                "Magnetic is context only and never scored.",
            ],
        },
        "matches": [
            {
                "rank": index + 1,
                "similarity_score": round(candidate.agreement_score, 4),
                "feature_distance": round(candidate.distance, 4),
                "coordinate": {
                    "latitude_deg": round(candidate.latitude_deg, 5),
                    "longitude_deg": round(candidate.longitude_west_deg, 5),
                    "longitude_convention": "east_negative_west_positive_minus180_to_180",
                    "longitude_east_deg": round(candidate.longitude_east_deg, 5),
                },
                "earth_features": {
                    "slope_deg": round(candidate.slope_deg, 3),
                    "local_relief_m": round(candidate.local_relief_m, 1),
                    "roughness_std_m": round(candidate.roughness_std_m, 1),
                    "mean_elevation_m": round(candidate.mean_elevation_m, 1),
                    "land_fraction": round(candidate.land_fraction, 3),
                    "terrain_class": terrain_class(candidate.slope_deg, candidate.local_relief_m),
                    "glim_lithology": candidate.glim_name,
                    "glim_code": candidate.glim_code,
                    "arid_suitability": round(candidate.arid_suitability, 3),
                    "arid_percentile": None
                    if candidate.arid_percentile is None
                    else round(candidate.arid_percentile, 3),
                    "nearest_impact_crater": candidate.nearest_crater_name,
                    "nearest_impact_distance_km": None
                    if candidate.nearest_crater_distance_km is None
                    else round(candidate.nearest_crater_distance_km, 1),
                    "nearest_impact_diameter_km": candidate.nearest_crater_diameter_km,
                    "impact_scored": candidate.impact_relevant,
                },
                "aspect_agreement": {
                    key: round(value, 4) for key, value in candidate.aspect_agreement.items()
                },
                "aspect_mismatch": {
                    key: round(value, 4) for key, value in candidate.aspect_scores.items()
                },
                "mismatch_contribution_share": {
                    key: round(value / candidate.distance, 3) if candidate.distance > 0 else 0.0
                    for key, value in candidate.contributions.items()
                },
                "deltas": {
                    "slope_deg": round(candidate.slope_deg - moon.slope_deg, 3),
                    "relief_m": round(candidate.local_relief_m - moon.relief_m, 1),
                    "roughness_m": round(candidate.roughness_std_m - moon.roughness_m, 1),
                    "abs_latitude_deg": round(
                        abs(candidate.latitude_deg) - abs(moon.latitude_deg), 3
                    ),
                },
                "why": candidate.reasons,
                "caveats": candidate.caveats,
            }
            for index, candidate in enumerate(selected)
        ],
    }


def print_human(result: dict[str, Any]) -> None:
    query = result["query"]
    features = query["features"]
    coord = query["coordinate"]
    print(
        f"Moon query {coord['latitude_deg']:.5f}°, {coord['longitude_deg']:.5f}°E "
        f"— {features['terrain_class']} / {features['material_class']} "
        f"(slope {features['slope_deg']}°, FeO {features.get('feo_wt_percent')}, "
        f"ΔT {features.get('thermal_range_k')} K)"
    )
    if features.get("geology_name"):
        print(
            f"Mapped unit: {features.get('geology_symbol') or '?'} {features['geology_name']}"
            f"{f' — {features['geology_interpretation']}' if features.get('geology_interpretation') else ''}"
        )
    if features.get("composition_conflict"):
        print(f"Composition caveat: {features['composition_conflict']}")
    if features.get("crater_name"):
        print(
            f"Nearest lunar crater: {features['crater_name']} "
            f"({features.get('crater_rim_km')} km to rim)"
        )
    print(
        f"Status: {result.get('status')} | "
        f"good {result['method'].get('candidate_cells_good', 0):,} / "
        f"eligible {result['method']['candidate_cells_eligible']:,} / "
        f"{result['method']['candidate_cells_considered']:,} considered; "
        f"material={result['method'].get('material_status')}"
    )
    if result.get("status_message"):
        print(result["status_message"])
    print(f"Scored aspects: {', '.join(result['method']['scored_aspects'])}\n")
    if not result["matches"]:
        print("No good analog found.\n")
        return
    for match in result["matches"]:
        c = match["coordinate"]
        e = match["earth_features"]
        print(
            f"#{match['rank']}  {c['latitude_deg']:.5f}, {c['longitude_deg']:.5f}  "
            f"(agreement {match['similarity_score']:.3f})"
        )
        print(
            f"    Terrain {e['terrain_class']}: slope {e['slope_deg']}°, "
            f"relief {e['local_relief_m']} m, roughness {e['roughness_std_m']} m"
        )
        if e.get("glim_lithology"):
            print(f"    Lithology: {e['glim_lithology']} ({e.get('glim_code')})")
        print(
            f"    Arid/barren suitability {e.get('arid_suitability')} "
            f"(percentile {e.get('arid_percentile')})"
        )
        if e.get("impact_scored") and e.get("nearest_impact_crater"):
            print(
                f"    Impact scored: {e['nearest_impact_crater']} "
                f"({e['nearest_impact_distance_km']} km)"
            )
        agrees = match.get("aspect_agreement") or {}
        print(
            "    Aspect agreement: "
            + ", ".join(f"{k}={v:.0%}" for k, v in sorted(agrees.items(), key=lambda kv: -kv[1]))
        )
        for reason in match["why"]:
            print(f"    • {reason}")
        print()


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("latitude", type=float, nargs="?", help="Moon latitude degrees")
    parser.add_argument(
        "longitude",
        type=float,
        nargs="?",
        help="Moon longitude degrees (east-positive; -180..180 or 0..360)",
    )
    parser.add_argument("--from-report", type=Path, default=None, help="moon site-reports.json path")
    parser.add_argument("--fetch-moon", action="store_true", help="refresh moon report via Java Main")
    parser.add_argument("--index", type=Path, default=INDEX_PATH, help="Earth SQLite index")
    parser.add_argument("--top", type=int, default=DEFAULT_TOP_K, help="number of matches")
    parser.add_argument(
        "--min-separation-deg",
        type=float,
        default=DEFAULT_MIN_SEPARATION_DEG,
        help="minimum geographic separation between Earth sites",
    )
    parser.add_argument("--json", action="store_true", help="print JSON only")
    parser.add_argument("--no-glim", action="store_true", help="skip GLiM lithology enrichment")
    parser.add_argument("--slope", type=float, help="override moon slope degrees")
    parser.add_argument("--relief", type=float, help="override moon relief metres")
    parser.add_argument("--roughness", type=float, help="override moon roughness metres")
    return parser


def resolve_moon_features(args: argparse.Namespace) -> MoonFeatures:
    report_path = args.from_report or REPORT_PATH

    if args.latitude is not None and args.longitude is not None:
        latitude, longitude = validate_moon_coordinate(args.latitude, args.longitude)
        report = None
        if args.fetch_moon:
            report = fetch_moon_report(latitude, longitude, report_path)
        else:
            report = load_report_for_coordinate(report_path, latitude, longitude)
            if report is None and not any(
                value is not None for value in (args.slope, args.relief, args.roughness)
            ):
                report = fetch_moon_report(latitude, longitude, report_path)
        if report is not None:
            features = features_from_moon_report(report)
            features.source = str(report_path)
        else:
            if args.slope is None or args.relief is None or args.roughness is None:
                raise ValueError(
                    "No moon report found; pass --fetch-moon or --slope/--relief/--roughness."
                )
            features = MoonFeatures(
                latitude_deg=latitude,
                longitude_east_deg=longitude,
                slope_deg=args.slope,
                relief_m=args.relief,
                roughness_m=args.roughness,
                source="cli-overrides",
            )
    elif args.from_report is not None or report_path.is_file():
        root = json.loads(report_path.read_text(encoding="utf-8"))
        reports = root.get("reports") if isinstance(root, dict) else root
        moon_reports = [
            report for report in reports if isinstance(report, dict) and report.get("body") == "moon"
        ]
        if not moon_reports:
            raise ValueError(f"No moon reports in {report_path}")
        features = features_from_moon_report(moon_reports[-1])
        features.source = str(report_path)
    else:
        raise ValueError("Provide moon latitude/longitude or --from-report.")

    if args.slope is not None:
        features.slope_deg = max(0.0, args.slope)
    if args.relief is not None:
        features.relief_m = max(0.0, args.relief)
    if args.roughness is not None:
        features.roughness_m = max(0.0, args.roughness)
    return features


def main(argv: list[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)
    try:
        moon = resolve_moon_features(args)
        result = match_earth_analogs(
            moon,
            index_path=args.index,
            top_k=args.top,
            min_separation_deg=args.min_separation_deg,
            enrich_glim=not args.no_glim,
        )
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    if args.json:
        print(json.dumps(result, indent=2))
    else:
        print_human(result)
        print("--- JSON ---")
        print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
