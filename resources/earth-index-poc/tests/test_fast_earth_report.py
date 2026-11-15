import json
import ctypes.util
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from unittest.mock import patch

from fast_earth_report import (
    GRID_COLUMNS,
    build_report,
    cell_location,
    download_copdem_if_requested,
    persist_report,
)
from copdem_reader import sample_copdem_tile
from etopo2022_reader import sample_etopo2022
from fetch_worldcover import worldcover_tile_name
from glim_reader import sample_glim
from worldcover_reader import sample_worldcover_tile


class FastEarthReportTests(unittest.TestCase):
    def make_index(self, path: Path, latitude: float, longitude: float) -> None:
        row, column, center_lat, center_lon = cell_location(latitude, longitude)
        with closing(sqlite3.connect(path)) as db:
            db.execute("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execute("CREATE TABLE earth_cells (id INTEGER PRIMARY KEY, latitude_deg REAL, "
                       "longitude_east_deg REAL, land_fraction REAL, mean_elevation_m REAL, "
                       "slope_deg REAL, local_relief_m REAL, roughness_std_m REAL)")
            db.executemany(
                "INSERT INTO metadata(key, value) VALUES (?, ?)",
                [
                    ("dataset", "NOAA ETOPO5"),
                    ("source_url", "https://example.test/etopo"),
                    ("source_resolution", "5 arc-minutes"),
                ],
            )
            db.execute(
                "INSERT INTO earth_cells VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    row * GRID_COLUMNS + column,
                    center_lat,
                    center_lon,
                    1,
                    123.5,
                    2.25,
                    12.0,
                    3.5,
                ),
            )
            db.commit()

    def test_cell_location_maps_both_dateline_conventions_to_same_cell(self):
        self.assertEqual(
            cell_location(0, -180)[:2],
            cell_location(0, 180)[:2],
        )

    def test_report_contains_coarse_local_measurements_and_unavailable_reasons(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            index_path = Path(temp_dir) / "index.sqlite3"
            self.make_index(index_path, 50.4, 14.9)
            report = build_report(
                50.4,
                14.9,
                index_path,
                Path(temp_dir) / "no-dem",
                etopo_path=Path(temp_dir) / "no-etopo.tif",
                worldcover_dir=Path(temp_dir) / "no-worldcover",
                glim_dir=Path(temp_dir) / "no-glim",
            )

        self.assertEqual("earth", report["body"])
        self.assertEqual("DATA", report["sources"][0]["state"])
        terrain = report["data"]["terrain"]
        self.assertTrue(terrain["land_candidate"])
        self.assertEqual(123.5, terrain["mean_elevation_m"])
        sections = report["report_output"]["metric_sections"]
        self.assertEqual("elevation_and_terrain", sections[0]["key"])
        self.assertIn("missing", sections[1]["measurements"][0]["value"])
        self.assertIn("Not gathered", sections[1]["measurements"][3]["value"])
        self.assertTrue(report["report_output"]["recommended_downloads"])

    def test_empty_cell_is_reported_as_unavailable_not_zero(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            index_path = Path(temp_dir) / "index.sqlite3"
            self.make_index(index_path, 50.4, 14.9)
            with closing(sqlite3.connect(index_path)) as db:
                row, column, _, _ = cell_location(50.4, 14.9)
                db.execute("DELETE FROM earth_cells WHERE id = ?", (row * GRID_COLUMNS + column,))
                db.commit()
            report = build_report(
                50.4,
                14.9,
                index_path,
                Path(temp_dir) / "no-dem",
                etopo_path=Path(temp_dir) / "no-etopo.tif",
                worldcover_dir=Path(temp_dir) / "no-worldcover",
                glim_dir=Path(temp_dir) / "no-glim",
            )

        value = report["report_output"]["metric_sections"][0]["measurements"][0]["value"]
        self.assertIn("Unavailable", value)
        self.assertNotIn('"value": "0', json.dumps(report))

    def test_bathymetry_without_land_candidate_is_not_classified_as_a_landform(self):
        sample = {
            "elevation_m": -4934.41,
            "neighborhood_pixels": 25,
            "slope_deg": 0.05,
            "aspect_deg": 356.85,
            "roughness_std_m": 2.81,
            "local_relief_m": 11.05,
            "curvature_km_inv": 0.0001,
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            index_path = Path(temp_dir) / "index.sqlite3"
            self.make_index(index_path, 0, 0)
            with closing(sqlite3.connect(index_path)) as db:
                row, column, _, _ = cell_location(0, 0)
                db.execute(
                    "DELETE FROM earth_cells WHERE id = ?",
                    (row * GRID_COLUMNS + column,),
                )
                db.commit()
            with patch(
                "fast_earth_report.local_etopo2022_sample",
                return_value=(sample, "DATA", "test bathymetry sample"),
            ):
                report = build_report(
                    0,
                    0,
                    index_path,
                    Path(temp_dir) / "no-dem",
                    etopo_path=Path(temp_dir) / "etopo.tif",
                    worldcover_dir=Path(temp_dir) / "no-worldcover",
                    glim_dir=Path(temp_dir) / "no-glim",
                )

        measurements = report["report_output"]["metric_sections"][0]["measurements"]
        classification = next(
            item["value"]
            for item in measurements
            if item["key"] == "local_topographic_class"
        )
        self.assertIn("Not assigned", classification)
        self.assertIn("may be bathymetry", classification)

    def test_persistence_reuses_duplicate_coordinate_unless_refreshed(self):
        first = {"body": "earth", "coordinate": {"latitude_deg": 1.0, "longitude_deg": 2.0}, "id": "first"}
        second = {"body": "earth", "coordinate": {"latitude_deg": 1.0, "longitude_deg": 2.0}, "id": "second"}
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "reports.json"
            persist_report(output, first)
            reused = persist_report(output, second)
            self.assertEqual("first", reused["id"])
            refreshed = persist_report(output, second, refresh=True)
            self.assertEqual("second", refreshed["id"])
            self.assertEqual(1, len(json.loads(output.read_text())["reports"]))

    def test_report_prefers_cached_copernicus_terrain_when_available(self):
        sample = {
            "tile": "sample",
            "pixel_size_x_m": 90.0,
            "pixel_size_y_m": 90.0,
            "elevation_m": 200.0,
            "neighborhood_pixels": 121,
            "neighborhood_status": "11×11 pixels (~1 km) available.",
            "slope_deg": 4.0,
            "aspect_deg": 180.0,
            "roughness_std_m": 2.0,
            "local_relief_m": 10.0,
            "curvature_km_inv": 0.1,
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            index_path = Path(temp_dir) / "index.sqlite3"
            self.make_index(index_path, 50.4, 14.9)
            with patch(
                "fast_earth_report.local_copdem_sample",
                return_value=(sample, "DATA", "cached sample"),
            ):
                report = build_report(
                    50.4,
                    14.9,
                    index_path,
                    Path(temp_dir) / "no-dem",
                    etopo_path=Path(temp_dir) / "no-etopo.tif",
                    worldcover_dir=Path(temp_dir) / "no-worldcover",
                    glim_dir=Path(temp_dir) / "no-glim",
                )

        self.assertEqual("cached sample", report["sources"][2]["status"])
        self.assertEqual("copernicus_glo90", report["data"]["terrain_metrics_source"])
        self.assertEqual(
            "Elevation (Copernicus GLO-90)",
            report["report_output"]["metric_sections"][0]["measurements"][0]["label"],
        )
        self.assertEqual(200.0, report["data"]["terrain"]["copernicus_glo90"]["elevation_m"])

    @unittest.skipUnless(
        ctypes.util.find_library("tiff")
        and (Path(__file__).parent.parent / "data/copernicus-dem-90m/Copernicus_DSM_COG_30_N50_00_E014_00_DEM.tif").is_file(),
        "requires local libtiff and the downloaded Copernicus test tile",
    )
    def test_sample_downloaded_copernicus_test_tile(self):
        tile = (
            Path(__file__).parent.parent
            / "data/copernicus-dem-90m/Copernicus_DSM_COG_30_N50_00_E014_00_DEM.tif"
        )
        sample = sample_copdem_tile(tile, 50.4, 14.9)
        self.assertAlmostEqual(233.22, sample["elevation_m"], places=2)
        self.assertEqual(121, sample["neighborhood_pixels"])
        self.assertGreaterEqual(sample["slope_deg"], 0)

    @unittest.skipUnless(
        ctypes.util.find_library("tiff")
        and (Path(__file__).parent.parent / "data/copernicus-dem-90m/Copernicus_DSM_COG_30_N43_00_E001_00_DEM.tif").is_file(),
        "requires local libtiff and the downloaded Copernicus boundary tile",
    )
    def test_tile_edge_reports_elevation_but_not_incomplete_neighborhood(self):
        tile = (
            Path(__file__).parent.parent
            / "data/copernicus-dem-90m/Copernicus_DSM_COG_30_N43_00_E001_00_DEM.tif"
        )
        sample = sample_copdem_tile(tile, 43.0, 1.0)
        self.assertIsNotNone(sample["elevation_m"])
        self.assertEqual(0, sample["neighborhood_pixels"])
        self.assertIn("tile edge", sample["neighborhood_status"])

    @unittest.skipUnless(
        ctypes.util.find_library("tiff")
        and (Path(__file__).parent.parent / "data/etopo2022/ETOPO_2022_v1_60s_N90W180_surface.tif").is_file(),
        "requires local libtiff and the downloaded NOAA ETOPO 2022 raster",
    )
    def test_sample_downloaded_etopo2022_global_raster(self):
        raster = (
            Path(__file__).parent.parent
            / "data/etopo2022/ETOPO_2022_v1_60s_N90W180_surface.tif"
        )
        sample = sample_etopo2022(raster, 50.4, 14.9)
        self.assertAlmostEqual(227.62, sample["elevation_m"], places=2)
        self.assertEqual(25, sample["neighborhood_pixels"])

    def test_optional_download_checks_public_tile_catalog(self):
        tile_name = "Copernicus_DSM_COG_30_N50_00_E014_00_DEM"
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch("fast_earth_report.get_tile_list", return_value={tile_name}), patch(
                "fast_earth_report.fetch_tile", return_value=(tile_name, "downloaded")
            ):
                state, status = download_copdem_if_requested(
                    50.4, 14.9, Path(temp_dir)
                )
        self.assertIsNone(state)
        self.assertEqual("downloaded", status)

    def test_worldcover_tile_name_uses_three_degree_grid(self):
        self.assertEqual("N48E012", worldcover_tile_name(50.4, 14.9))
        self.assertEqual("S03W003", worldcover_tile_name(-0.5, -0.5))
        self.assertEqual("N87E177", worldcover_tile_name(90, 180))

    def test_report_includes_local_worldcover_and_glim_samples(self):
        worldcover_sample = {
            "class_value": 30,
            "class_name": "Grassland",
            "dominant_class_value": 30,
            "dominant_class_name": "Grassland",
            "class_counts": {30: 121},
            "neighborhood_mix": [
                {
                    "class_value": 30,
                    "class_name": "Grassland",
                    "pixel_count": 121,
                    "fraction": 1.0,
                }
            ],
            "neighborhood_pixels": 121,
            "pixel_size_x_m": 6.0,
            "pixel_size_y_m": 9.0,
        }
        glim_sample = {
            "class_name": "Carbonate sedimentary rocks",
            "cell_center_latitude_deg": 50.25,
            "cell_center_longitude_deg": 14.75,
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            index_path = Path(temp_dir) / "index.sqlite3"
            self.make_index(index_path, 50.4, 14.9)
            with patch(
                "fast_earth_report.local_worldcover_sample",
                return_value=(worldcover_sample, "DATA", "test land-cover data"),
            ), patch(
                "fast_earth_report.local_glim_sample",
                return_value=(glim_sample, "DATA", "test lithology data"),
            ):
                report = build_report(
                    50.4,
                    14.9,
                    index_path,
                    Path(temp_dir) / "no-dem",
                    etopo_path=Path(temp_dir) / "no-etopo.tif",
                    worldcover_dir=Path(temp_dir) / "worldcover",
                    glim_dir=Path(temp_dir) / "glim",
                )

        measurements = report["report_output"]["metric_sections"][1]["measurements"]
        values = {item["key"]: item["value"] for item in measurements}
        self.assertIn("Grassland", values["surface_land_cover"])
        self.assertIn("Carbonate sedimentary rocks", values["mapped_lithology"])
        self.assertIn("100%", values["local_land_cover_mix"])

    def test_glim_reader_samples_categorical_grid_cell(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            raster = Path(temp_dir) / "glim.asc"
            legend = Path(temp_dir) / "Classnames.txt"
            raster_rows = []
            for row in range(360):
                values = [1] * 720
                if row == 79:
                    values[389] = 6
                raster_rows.append(" ".join(map(str, values)))
            raster.write_text(
                "ncols 720\nnrows 360\nxllcorner -180\nyllcorner -90\n"
                "cellsize 0.5\nNODATA_value -9999\n"
                + "\n".join(raster_rows)
                + "\n",
                encoding="ascii",
            )
            legend.write_text(
                '"OBJECTID";"Value_";"Count_";"xx"\n'
                '1;1;1;"su"\n6;6;1;"sc"\n',
                encoding="utf-8",
            )
            sample = sample_glim(raster, legend, 50.4, 14.9)

        self.assertEqual(6, sample["class_code"])
        self.assertEqual("sc", sample["class_abbreviation"])
        self.assertEqual("Carbonate sedimentary rocks", sample["class_name"])
        self.assertEqual(50.25, sample["cell_center_latitude_deg"])
        self.assertEqual(14.75, sample["cell_center_longitude_deg"])

    @unittest.skipUnless(
        ctypes.util.find_library("tiff")
        and (Path(__file__).parent.parent / "data/worldcover-2021/ESA_WorldCover_10m_2021_v200_N48E012_Map.tif").is_file(),
        "requires local libtiff and the downloaded WorldCover tile",
    )
    def test_sample_downloaded_worldcover_tile(self):
        tile = (
            Path(__file__).parent.parent
            / "data/worldcover-2021/ESA_WorldCover_10m_2021_v200_N48E012_Map.tif"
        )
        sample = sample_worldcover_tile(tile, 50.4, 14.9)
        self.assertEqual(30, sample["class_value"])
        self.assertEqual("Grassland", sample["class_name"])
        self.assertEqual(121, sample["neighborhood_pixels"])

    @unittest.skipUnless(
        (Path(__file__).parent.parent / "data/glim/glim_wgs84_0point5deg.txt.asc").is_file()
        and (Path(__file__).parent.parent / "data/glim/Classnames.txt").is_file(),
        "requires the downloaded GLiM raster",
    )
    def test_sample_downloaded_glim_raster(self):
        data_dir = Path(__file__).parent.parent / "data/glim"
        sample = sample_glim(
            data_dir / "glim_wgs84_0point5deg.txt.asc",
            data_dir / "Classnames.txt",
            50.4,
            14.9,
        )
        self.assertEqual("Carbonate sedimentary rocks", sample["class_name"])
        self.assertEqual(0.5, sample["cell_size_degrees"])


if __name__ == "__main__":
    unittest.main()
