import sqlite3
import tempfile
import unittest
from pathlib import Path

from match_earth_analogs import (
    EarthCandidate,
    MoonFeatures,
    diversify,
    east_to_west_longitude,
    features_from_moon_report,
    impact_distance,
    infer_material_class,
    match_earth_analogs,
    material_distance,
    material_is_scored,
    terrain_class,
    terrain_gate_ok,
    validate_moon_coordinate,
)


def _write_index(path: Path, rows: list[tuple]) -> None:
    with sqlite3.connect(path) as db:
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
        db.executemany(
            "INSERT INTO earth_cells VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            rows,
        )
        db.commit()


class MatchEarthAnalogsTests(unittest.TestCase):
    def test_validates_and_wraps_moon_longitude(self):
        lat, lon = validate_moon_coordinate(0.0, -10.0)
        self.assertEqual(0.0, lat)
        self.assertAlmostEqual(350.0, lon)

    def test_east_west_conversion(self):
        self.assertAlmostEqual(-79.0, east_to_west_longitude(281.0))
        self.assertAlmostEqual(14.9, east_to_west_longitude(14.9))

    def test_material_class_from_mare_unit(self):
        self.assertEqual(
            "mare",
            infer_material_class("Upper Mare Unit", "Basaltic lava flows"),
        )
        self.assertEqual("highland", infer_material_class("Highland plains", None))

    def test_unknown_material_is_not_scored(self):
        moon = MoonFeatures(
            latitude_deg=70.0,
            longitude_east_deg=70.0,
            slope_deg=12.0,
            relief_m=160.0,
            roughness_m=40.0,
            material_class="unknown",
        )
        self.assertFalse(material_is_scored(moon))

    def test_features_extract_multi_aspect_fields(self):
        report = {
            "body": "moon",
            "coordinate": {"latitude_deg": 0.0, "longitude_deg": 0.0},
            "data": {
                "slope_deg": 1.69,
                "relief_m": 12.71,
                "rough_m": 4.38,
                "elev_m": -711.86,
                "div6_max_k": 390.0,
                "div6_min_k": 90.0,
                "mapped_unit": {
                    "symbol": "Im2",
                    "name": "Upper Mare Unit",
                    "interpretation": "Basaltic lava flows",
                    "boundary_distance_km": 13,
                },
                "lunar_composition": {
                    "FeO_wt_percent": 11.07,
                    "TiO2_wt_percent": 2.5,
                    "Al2O3_wt_percent": 19.7,
                },
                "lunar_magnetic": {"magnitude_nT": 0.75},
                "nearest_named_crater": {
                    "name": "Oppolzer",
                    "rim_distance_km": 27.64,
                    "diameter_km": 40.87,
                },
            },
        }
        features = features_from_moon_report(report)
        self.assertEqual("mare", features.material_class)
        self.assertAlmostEqual(300.0, features.thermal_range_k)
        self.assertGreater(features.mafic_index, 0.4)
        self.assertEqual("Oppolzer", features.crater_name)
        self.assertLess(features.magnetic_regime, 0.3)

    def test_material_requires_basic_volcanic_for_mare(self):
        moon = MoonFeatures(
            latitude_deg=0.0,
            longitude_east_deg=0.0,
            slope_deg=1.7,
            relief_m=12.0,
            roughness_m=4.0,
            material_class="mare",
            feo_wt_percent=14.0,
            tio2_wt_percent=3.0,
        )
        self.assertLess(material_distance(moon, "vb"), material_distance(moon, "vi"))
        self.assertLess(material_distance(moon, "vb"), material_distance(moon, "sc"))
        self.assertGreaterEqual(material_distance(moon, "vi"), 0.9)

    def test_highland_prefers_plutonic_not_volcanic(self):
        moon = MoonFeatures(
            latitude_deg=70.0,
            longitude_east_deg=70.0,
            slope_deg=12.0,
            relief_m=160.0,
            roughness_m=40.0,
            material_class="highland",
            al2o3_wt_percent=25.0,
            feo_wt_percent=5.0,
        )
        self.assertLess(material_distance(moon, "pa"), material_distance(moon, "va"))
        self.assertLess(material_distance(moon, "mt"), material_distance(moon, "vb"))
        self.assertGreaterEqual(material_distance(moon, "va"), 1.0)

    def test_terrain_gate_rejects_slope_and_roughness_outliers(self):
        moon = MoonFeatures(
            latitude_deg=70.0,
            longitude_east_deg=70.0,
            slope_deg=12.12,
            relief_m=163.1,
            roughness_m=42.8,
            material_class="unknown",
        )
        # Slope way off — hard reject.
        bad_slope = EarthCandidate(
            cell_id=1,
            latitude_deg=44.1,
            longitude_east_deg=81.5,
            longitude_west_deg=81.5,
            land_fraction=1.0,
            mean_elevation_m=1000.0,
            slope_deg=3.27,
            local_relief_m=1226.0,
            roughness_std_m=440.0,
        )
        ok, reason = terrain_gate_ok(moon, bad_slope)
        self.assertFalse(ok)
        self.assertIn("slope", reason or "")

        # Slope OK but texture (rough/relief) wildly different.
        bad_tex = EarthCandidate(
            cell_id=3,
            latitude_deg=60.0,
            longitude_east_deg=70.0,
            longitude_west_deg=70.0,
            land_fraction=1.0,
            mean_elevation_m=800.0,
            slope_deg=12.0,
            local_relief_m=10.0,
            roughness_std_m=200.0,
        )
        ok_tex, reason_tex = terrain_gate_ok(moon, bad_tex)
        self.assertFalse(ok_tex)
        self.assertIn("texture", reason_tex or "")

        good = EarthCandidate(
            cell_id=2,
            latitude_deg=60.0,
            longitude_east_deg=70.0,
            longitude_west_deg=70.0,
            land_fraction=1.0,
            mean_elevation_m=800.0,
            slope_deg=11.5,
            local_relief_m=180.0,
            roughness_std_m=50.0,
        )
        ok_good, _ = terrain_gate_ok(moon, good)
        self.assertTrue(ok_good)

    def test_latitude_penalizes_equator_vs_midlatitude(self):
        from match_earth_analogs import latitude_solar_distance

        moon = MoonFeatures(
            latitude_deg=0.0,
            longitude_east_deg=0.0,
            slope_deg=1.0,
            relief_m=10.0,
            roughness_m=4.0,
        )
        self.assertLess(latitude_solar_distance(moon, 8.0), latitude_solar_distance(moon, 41.0))
        self.assertGreater(latitude_solar_distance(moon, 41.0), 0.45)

    def test_impact_only_scores_relevant_proximal_craters(self):
        from match_earth_analogs import impact_relevant

        moon = MoonFeatures(
            latitude_deg=0.0,
            longitude_east_deg=0.0,
            slope_deg=1.0,
            relief_m=10.0,
            roughness_m=4.0,
            crater_rim_km=30.0,
            crater_diameter_km=40.0,
        )
        self.assertTrue(impact_relevant(moon, 25.0, 35.0))
        self.assertFalse(impact_relevant(moon, 800.0, 2.0))
        self.assertEqual(0.0, impact_distance(moon, 800.0, 2.0))
        self.assertGreater(impact_distance(moon, 25.0, 35.0), 0.0)

    def test_diversify_keeps_separated_sites(self):
        ranked = [
            EarthCandidate(
                cell_id=i,
                latitude_deg=lat,
                longitude_east_deg=lon,
                longitude_west_deg=east_to_west_longitude(lon),
                land_fraction=1.0,
                mean_elevation_m=0.0,
                slope_deg=1.0,
                local_relief_m=10.0,
                roughness_std_m=4.0,
                distance=float(i),
            )
            for i, (lat, lon) in enumerate(
                [(10.0, 10.0), (10.2, 10.1), (40.0, 100.0), (-20.0, 200.0), (60.0, 300.0)]
            )
        ]
        selected = diversify(ranked, top_k=3, min_separation_deg=8.0)
        self.assertEqual(3, len(selected))
        self.assertEqual(0, selected[0].cell_id)
        self.assertEqual(2, selected[1].cell_id)

    def test_no_good_analog_when_terrain_cannot_match(self):
        with tempfile.TemporaryDirectory() as tmp:
            index = Path(tmp) / "index.sqlite3"
            # Earth cells are all flat/smooth — cannot satisfy lunar slope 12° ±3.
            rows = [
                (i, 20.0 + (i % 50) * 0.5, (i * 10) % 360, 1.0, 500.0, 2.0, 20.0, 5.0)
                for i in range(120)
            ]
            _write_index(index, rows)
            moon = MoonFeatures(
                latitude_deg=70.0,
                longitude_east_deg=70.0,
                slope_deg=12.12,
                relief_m=163.1,
                roughness_m=42.8,
                material_class="unknown",
                feo_wt_percent=4.67,
                al2o3_wt_percent=20.9,
                thermal_range_k=257.0,
                source="test",
            )
            result = match_earth_analogs(
                moon,
                index_path=index,
                top_k=5,
                enrich_glim=False,
            )
            self.assertEqual("no_good_analog_found", result["status"])
            self.assertEqual([], result["matches"])
            self.assertIn("No good Earth analog found", result["status_message"])

    def test_terrain_matched_unknown_excludes_material_from_score(self):
        with tempfile.TemporaryDirectory() as tmp:
            index = Path(tmp) / "index.sqlite3"
            rows = []
            for i in range(120):
                lat = 55 + (i % 10)
                lon = (i * 13) % 360
                rows.append(
                    (i, lat, lon, 1.0, 700.0, 11.5 + (i % 3) * 0.3, 150.0, 40.0 + i % 5)
                )
            _write_index(index, rows)
            moon = MoonFeatures(
                latitude_deg=70.0,
                longitude_east_deg=70.0,
                slope_deg=12.0,
                relief_m=160.0,
                roughness_m=42.0,
                material_class="unknown",
                feo_wt_percent=4.7,
                al2o3_wt_percent=21.0,
                thermal_range_k=100.0,  # milder so arid gate is soft
                source="test",
            )
            result = match_earth_analogs(
                moon,
                index_path=index,
                top_k=3,
                min_separation_deg=3.0,
                enrich_glim=False,
            )
            self.assertEqual("unverified_excluded_unknown_lunar_class", result["method"]["material_status"])
            if result["matches"]:
                self.assertNotIn("material", result["matches"][0]["aspect_agreement"])
                self.assertGreaterEqual(
                    result["matches"][0]["aspect_agreement"].get("terrain", 0), 0.4
                )


if __name__ == "__main__":
    unittest.main()
