import unittest

from fetch_dem_tiles import copdem_tile_name


class CopernicusTileNameTests(unittest.TestCase):
    def test_northern_eastern_tile(self):
        self.assertEqual(
            "Copernicus_DSM_COG_30_N50_00_E014_00_DEM",
            copdem_tile_name(50.4, 14.9),
        )

    def test_southern_western_tile_uses_lower_left_degree(self):
        self.assertEqual(
            "Copernicus_DSM_COG_30_S34_00_W071_00_DEM",
            copdem_tile_name(-33.9, -70.7),
        )

    def test_normalizes_dateline_and_pole_boundaries(self):
        self.assertEqual(
            "Copernicus_DSM_COG_30_S90_00_W180_00_DEM",
            copdem_tile_name(-90, 180),
        )
        self.assertEqual(
            "Copernicus_DSM_COG_30_N89_00_E000_00_DEM",
            copdem_tile_name(90, 0),
        )

    def test_rejects_invalid_coordinates(self):
        with self.assertRaises(ValueError):
            copdem_tile_name(90.1, 0)
        with self.assertRaises(ValueError):
            copdem_tile_name(0, float("nan"))


if __name__ == "__main__":
    unittest.main()
