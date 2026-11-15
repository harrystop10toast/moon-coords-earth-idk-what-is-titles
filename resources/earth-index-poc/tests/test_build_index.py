import math
import unittest

from build_index import slope_degrees, summarize_block


class TerrainFeatureTests(unittest.TestCase):
    def test_summarizes_land_only_when_three_of_four_samples_are_land(self):
        count, mean, relief, roughness = summarize_block([10, 20, 30, -100])
        self.assertEqual(3, count)
        self.assertEqual(20, mean)
        self.assertEqual(20, relief)
        self.assertAlmostEqual(math.sqrt(200 / 3), roughness)

    def test_rejects_mixed_water_land_block(self):
        count, mean, relief, roughness = summarize_block([10, 20, -30, -40])
        self.assertEqual(2, count)
        self.assertTrue(math.isnan(mean))
        self.assertTrue(math.isnan(relief))
        self.assertTrue(math.isnan(roughness))

    def test_flat_neighbor_elevations_have_zero_slope(self):
        self.assertAlmostEqual(0, slope_degrees(100, 100, 100, 100, 30))

    def test_polar_longitude_slope_is_not_reported(self):
        self.assertIsNone(slope_degrees(0, 100, 0, 0, 89.9))


if __name__ == "__main__":
    unittest.main()
