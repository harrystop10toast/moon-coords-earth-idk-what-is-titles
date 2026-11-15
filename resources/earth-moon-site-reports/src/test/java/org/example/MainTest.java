package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @Test
    void validatesEarthCoordinates() {
        assertTrue(Main.validCoordinate(43.07, -89.40));
        assertFalse(Main.validCoordinate(90.01, 0));
        assertFalse(Main.validCoordinate(0, -180.01));
    }

    @Test
    void derivesTerrainFromFiveByFiveGrid() {
        double[] grid = new double[25];
        for (int row = 0; row < 5; row++) for (int col = 0; col < 5; col++) grid[row * 5 + col] = col * 10;
        Main.Terrain terrain = Main.terrainFromElevations(grid, 100);
        assertTrue(terrain.ok());
        assertEquals(20, terrain.elevation, 0.001);
        assertEquals(Math.toDegrees(Math.atan(0.1)), terrain.slope, 0.001);
        assertEquals(40, terrain.relief, 0.001);
    }

    @Test
    void derivesCurvatureAndNormalGravityWithExpectedUnits() {
        double[] bowl = new double[25];
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 5; col++) {
                bowl[row * 5 + col] = (row - 2) * (row - 2) + (col - 2) * (col - 2);
            }
        }
        Main.Terrain terrain = Main.terrainFromElevations(bowl, 100);
        assertTrue(terrain.curvature > 0);
        assertTrue(Main.normalGravity(35, 900) > 970_000);
        assertEquals(1.624, Main.lunarReferenceGravity(43, 0), 0.01);
        assertTrue(Double.isNaN(Main.lunarReferenceGravity(91, 0)));
        assertTrue(Main.normalGravity(35, 900) < 990_000);
    }

    @Test
    void derivesMoonTerrainFromTheGriddedLolaDem() throws Exception {
        assertEquals(new Main.LunarDemCell(10_240, 1_280), Main.lunarLdemCell(10, 10));
        assertEquals(Main.lunarLdemCell(10, 10), Main.lunarLdemCell(10, 370));
        ObjectNode dem = Main.JSON.createObjectNode();
        ArrayNode grid = dem.putArray("elevations_m");
        for (int row = 0; row < 5; row++) {
            ArrayNode samples = grid.addArray();
            for (int column = 0; column < 5; column++) {
                samples.add((row - 2) * (row - 2) + (column - 2) * (column - 2));
            }
        }
        dem.put("cell_center_latitude_deg", 9.99609375);
        dem.put("cell_center_longitude_east_deg", 10.00390625);
        Main.Source source = new Main.Source("LOLA DEM", dem, Main.SourceState.DATA, "ok");
        Main.Terrain terrain = Main.lunarLdemTerrain(source, 10);
        assertEquals(0, terrain.elevation, 0.001);
        assertTrue(terrain.curvature > 0);

        ObjectNode report = Main.moonData(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, source, 10, 10);
        JsonNode metrics = report.path("metrics").path("Elevation and terrain");
        assertTrue(metrics.path("Elevation (LOLA; lunar reference radius 1,737.4 km)")
                .asText().contains("LDEM_128_FLOAT"));
        assertTrue(metrics.path("Terrain curvature (LOLA local grid)").asText().contains("finite-difference"));
        assertTrue(report.path("lola_dem_elevations_m").isArray());
    }

    @Test
    void truncatesListsWithoutInventingValues() {
        assertEquals("a, b (and 1 more)", Main.truncate(List.of("a", "b", "c"), 2));
        assertEquals("/", Main.truncate(List.of(), 2));
    }

    @Test
    void rejectsApiErrorPayloadsBeforeReportingThem() throws Exception {
        assertFalse(Main.validApiPayload(Main.JSON.readTree("{\"error\": \"bad request\"}")));
        assertFalse(Main.validApiPayload(Main.JSON.readTree("{\"Error\": \"bad request\"}")));
        assertFalse(Main.validApiPayload(Main.JSON.readTree("{\"ODEResults\": {\"Error\": \"bad request\"}}")));
        assertFalse(Main.validApiPayload(Main.JSON.readTree("{\"ODEResults\": {\"Status\": \"ERROR\"}}")));
        assertFalse(Main.validApiPayload(Main.JSON.readTree("{\"ODEResults\": {\"Status\": \"FAILED\"}}")));
        assertFalse(Main.validApiPayload(Main.JSON.readTree("{\"remark\": \"runtime error: query timed out\"}")));
        assertTrue(Main.validApiPayload(Main.JSON.readTree("{\"current\": {\"temperature_2m\": 1}}")));
        assertTrue(Main.validApiPayload(Main.JSON.readTree("{\"ODEResults\": {\"Status\": \"SUCCESS\", \"Products\": {\"Product\": []}}}")));
    }

    @Test
    void distinguishesOdeErrorEmptyAndProductResults() throws Exception {
        assertEquals(Main.SourceState.API_ERROR, Main.payloadState("https://oderest.rsl.wustl.edu/live2", Main.JSON.readTree("{\"ODEResults\": {\"Status\": \"ERROR\"}}")));
        assertEquals(Main.SourceState.API_ERROR, Main.payloadState("https://oderest.rsl.wustl.edu/live2", Main.JSON.readTree("{\"ODEResults\": {\"Status\": \"SUCCESS\", \"Error\": \"some error\"}}")));
        assertEquals(Main.SourceState.EMPTY, Main.payloadState("https://oderest.rsl.wustl.edu/live2", Main.JSON.readTree("{\"ODEResults\": {\"Status\": \"SUCCESS\", \"Products\": {\"Product\": []}}}")));
        assertEquals(Main.SourceState.EMPTY, Main.payloadState("https://oderest.rsl.wustl.edu/live2", Main.JSON.readTree("{\"ODEResults\": {\"Status\": \"SUCCESS\", \"Count\": 0}}")));
        assertEquals(Main.SourceState.DATA, Main.payloadState("https://oderest.rsl.wustl.edu/live2", Main.JSON.readTree("{\"ODEResults\": {\"Status\": \"SUCCESS\", \"Products\": {\"Product\": [{\"pdsid\": \"x\"}]}}}")));
        assertEquals(Main.SourceState.DATA, Main.payloadState("https://oderest.rsl.wustl.edu/live2", Main.JSON.readTree("{\"ODEResults\": {\"Status\": \"SUCCESS\", \"Products\": {\"Product\": {\"pdsid\": \"single_product\"}}}}")));
    }

    @Test
    void combinePdsOnlyIncludesSuccessfulProductMatches() throws Exception {
        Main.Source data = new Main.Source("S1", Main.JSON.readTree("{\"pdsid\": \"1\"}"), Main.SourceState.DATA, "ok");
        Main.Source empty = new Main.Source("S2", Main.JSON.readTree("{\"ODEResults\": {\"Status\": \"SUCCESS\", \"Products\": {\"Product\": []}}}"), Main.SourceState.EMPTY, "ok");
        Main.Source err = new Main.Source("S3", null, Main.SourceState.API_ERROR, "error");

        assertEquals("S1 available", Main.combinePds(data, empty, err));
        assertEquals("/", Main.combinePds(empty, err));
        assertEquals("PDS request succeeded; zero products found", Main.pds(empty));
    }

    @Test
    void reusesExactCoordinateReportsAndKeepsBodiesSeparate(@TempDir Path tempDir) throws Exception {
        Path originalReportFile = Main.reportFile;
        try {
            Main.reportFile = tempDir.resolve("test-reports.json");

            ObjectNode full = Main.JSON.createObjectNode();
            full.put("geo", "sandstone");
            full.put("elev", 263.0);
            full.put("temp", 6.8);
            full.put("go", "nogo");
            Main.persistReport("earth", 43.07, -89.40, full);

            ObjectNode store = Main.loadReportStore();
            ArrayNode reports = (ArrayNode) store.get("reports");
            assertEquals(3, store.path("v").asInt());
            assertEquals(1, reports.size());
            assertEquals("earth", reports.get(0).path("body").asText());
            assertEquals(43.07, reports.get(0).path("latitude").asDouble(), 0.00001);
            assertEquals(-89.4, reports.get(0).path("longitude").asDouble(), 0.00001);
            assertTrue(reports.get(0).path("generated_at_utc").isTextual());
            assertEquals("sandstone", reports.get(0).path("data").path("geo").asText());
            assertFalse(reports.get(0).has("sources"));
            assertFalse(reports.get(0).path("data").has("payload"));

            ObjectNode partial = Main.JSON.createObjectNode();
            partial.put("geo", "/");
            partial.put("elev", 263.0);
            partial.put("temp", -1.0);
            partial.put("soil", "sand=40");
            partial.put("go", "nogo");
            Main.persistReport("earth", 43.07, -89.40, partial);

            Main.persistReport("moon", 43.07, -89.40, partial);
            ArrayNode appended = (ArrayNode) Main.loadReportStore().get("reports");
            assertEquals(2, appended.size(), "An exact body/coordinate pair should only be stored once");
            assertEquals("earth", appended.get(0).path("body").asText());
            assertEquals("moon", appended.get(1).path("body").asText());
            assertEquals("sandstone", appended.get(0).path("data").path("geo").asText());
            assertEquals("/", appended.get(1).path("data").path("geo").asText());
            String formatted = Files.readString(Main.reportFile);
            assertEquals(2, formatted.lines().filter(line -> line.trim().startsWith("\"body\"")).count());
        } finally {
            Main.reportFile = originalReportFile;
        }
    }

    @Test
    void persistsReadableReportSectionsAndSourceStatusesWithBodyFirst(@TempDir Path tempDir) throws Exception {
        Path originalReportFile = Main.reportFile;
        try {
            Main.reportFile = tempDir.resolve("readable-reports.json");
            ObjectNode metrics = Main.JSON.createObjectNode();
            metrics.putObject("Elevation and terrain").put("Terrain class", "Low-relief plains")
                    .put("Slope", "1.25°");
            ArrayNode context = Main.JSON.createArrayNode();
            Main.addReportContext(context, "data_completeness_gate", "Operational data-completeness gate", "INCOMPLETE");
            Main.Source source = new Main.Source("LOLA DEM", Main.JSON.createObjectNode(),
                    Main.SourceState.DATA, "sampled at requested coordinate");
            ObjectNode readable = Main.readableReportOutput("MOON SITE REPORT", "Best-effort lunar data.",
                    metrics, context, List.of(source), "Return ranked Earth analog candidates.");

            Main.persistReport("moon", 35, 361, Main.JSON.createObjectNode().set("metrics", metrics), readable);

            JsonNode record = Main.loadReportStore().path("reports").get(0);
            assertEquals("body", record.fieldNames().next());
            assertEquals("moon", record.path("body").asText());
            assertEquals(1, record.path("coordinate").path("longitude_deg").asDouble(), 0.001);
            assertEquals("east_0_to_360", record.path("coordinate").path("longitude_convention").asText());
            JsonNode output = record.path("report_output");
            assertEquals("MOON SITE REPORT", output.path("title").asText());
            assertEquals("elevation_and_terrain", output.path("metric_sections").get(0).path("key").asText());
            assertEquals("terrain_class", output.path("metric_sections").get(0)
                    .path("measurements").get(0).path("key").asText());
            assertEquals("Low-relief plains", output.path("metric_sections").get(0)
                    .path("measurements").get(0).path("value").asText());
            assertEquals("INCOMPLETE", output.path("additional_context").get(0).path("value").asText());
            assertEquals("LOLA DEM", output.path("source_status").get(0).path("name").asText());
            assertEquals("Return ranked Earth analog candidates.", output.path("recommendation").asText());
            assertEquals(Main.reportFile.toAbsolutePath().toString(), output.path("output_file").asText());

            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            try {
                System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
                assertTrue(Main.printSavedReportIfPresent("moon", 35, 1));
            } finally {
                System.setOut(originalOut);
            }
            assertTrue(captured.toString(StandardCharsets.UTF_8).contains("Using saved report from site-reports.json"));
            assertTrue(captured.toString(StandardCharsets.UTF_8).contains("Low-relief plains"));
        } finally {
            Main.reportFile = originalReportFile;
        }
    }

    @Test
    void prettyPrintsEachReportAndNestedArraysOnSeparateLines(@TempDir Path tempDir) throws Exception {
        Path originalReportFile = Main.reportFile;
        try {
            Main.reportFile = tempDir.resolve("formatted-reports.json");
            ObjectNode store = Main.emptyReportStore();
            ObjectNode moon = store.withArray("reports").addObject();
            moon.put("body", "moon");
            moon.set("samples", Main.JSON.createArrayNode().add(1).add(2));
            ObjectNode earth = store.withArray("reports").addObject();
            earth.put("body", "earth");
            earth.set("samples", Main.JSON.createArrayNode().add(3).add(4));

            Main.writeReportStore(store);

            String contents = Files.readString(Main.reportFile);
            assertTrue(contents.contains("\"reports\" : [\n"));
            assertTrue(contents.contains("\"samples\" : [\n"));
            assertEquals(2, contents.lines().filter(line -> line.trim().startsWith("\"body\"")).count());
            assertEquals(store, Main.loadReportStore());
        } finally {
            Main.reportFile = originalReportFile;
        }
    }

    @Test
    void treatsEmptyReportAsNewStoreButProtectsCorruptNonemptyReport(@TempDir Path tempDir) throws Exception {
        Path originalReportFile = Main.reportFile;
        try {
            Main.reportFile = tempDir.resolve("empty-report.json");
            Files.writeString(Main.reportFile, "");
            ObjectNode empty = Main.loadReportStore();
            assertEquals(3, empty.path("v").asInt());
            assertTrue(empty.path("reports").isArray());
            assertTrue(empty.path("reports").isEmpty());
            Main.persistReport("earth", 35, 1, Main.JSON.createObjectNode().put("temp", 12));
            assertEquals(1, Main.loadReportStore().path("reports").size());

            Files.writeString(Main.reportFile, "{broken");
            assertNull(Main.loadReportStore());
            assertEquals("{broken", Files.readString(Main.reportFile));
        } finally {
            Main.reportFile = originalReportFile;
        }
    }

    @Test
    void resetsLegacyMergedReportsButKeepsAppendingFreshRecords(@TempDir Path tempDir) throws Exception {
        Path originalReportFile = Main.reportFile;
        try {
            Main.reportFile = tempDir.resolve("legacy-reports.json");
            Files.writeString(Main.reportFile, """
                    {"v":2,"sites":[
                      {"b":"e","lat":35,"lon":1,"d":{"elev":939}},
                      {"b":"m","lat":35,"lon":1,"d":{"elev":-1676}}
                    ]}
                    """);
            ObjectNode migrated = Main.loadReportStore();
            assertEquals(3, migrated.path("v").asInt());
            assertTrue(migrated.path("reports").isEmpty());
            Main.persistReport("moon", 35, 1, Main.JSON.createObjectNode().put("elev_m", -1676));
            ObjectNode saved = Main.loadReportStore();
            assertFalse(saved.has("sites"));
            assertEquals(1, saved.path("reports").size());
            assertEquals("moon", saved.path("reports").get(0).path("body").asText());
            assertEquals(-1676, saved.path("reports").get(0).path("data").path("elev_m").asInt());
        } finally {
            Main.reportFile = originalReportFile;
        }
    }

    @Test
    void streamsBatchReportChunksIntoTheVersionedStore(@TempDir Path tempDir) throws Exception {
        Path originalReportFile = Main.reportFile;
        try {
            Main.reportFile = tempDir.resolve("site-reports.json");
            ObjectNode store = Main.emptyReportStore();
            store.put("label", "preserve");
            store.withArray("reports").addObject()
                    .put("id", "old").put("body", "moon").put("latitude", 5).put("longitude", 6);
            Main.writeReportStore(store);

            ObjectNode earth = Main.JSON.createObjectNode();
            earth.put("id", "batch-1");
            earth.put("body", "earth");
            earth.put("latitude", 35);
            earth.put("longitude", 1);
            earth.put("generated_at_utc", "2026-11-14T00:00:00Z");
            earth.set("data", Main.JSON.createObjectNode().put("elev", 939));
            ObjectNode invalid = Main.JSON.createObjectNode().put("inputLine", 2).put("error", "bad coordinate");
            Path jsonl = tempDir.resolve("batch.jsonl");
            Files.writeString(jsonl, Main.JSON.writeValueAsString(earth) + "\n"
                    + Main.JSON.writeValueAsString(earth) + "\n"
                    + Main.JSON.writeValueAsString(invalid) + "\n");

            assertEquals(1, Main.appendBatchReports(jsonl));
            ObjectNode saved = Main.loadReportStore();
            assertEquals("preserve", saved.path("label").asText());
            assertEquals(2, saved.path("reports").size());
            assertEquals("moon", saved.path("reports").get(0).path("body").asText());
            assertEquals("earth", saved.path("reports").get(1).path("body").asText());
            assertEquals(939, saved.path("reports").get(1).path("data").path("elev").asInt());
            assertEquals(0, Main.appendBatchReports(jsonl), "Re-appending an existing batch must add no duplicates");
        } finally {
            Main.reportFile = originalReportFile;
        }
    }

    @Test
    void elevationUrlGeneratesDistinctGridPoints() {
        String url = Main.elevationUrl(43.07, -89.40);
        int latIndex = url.indexOf("latitude=");
        int lonIndex = url.indexOf("&longitude=");
        String latsStr = url.substring(latIndex + 9, lonIndex);
        String[] lats = latsStr.split(",");
        assertEquals(25, lats.length);
        assertNotEquals(lats[0], lats[lats.length - 1], "Elevation grid points must not collapse to identical values");
    }

    @Test
    void elevationUrlKeepsPoleAndDatelineGridCoordinatesInRange() {
        String url = Main.elevationUrl(90, 180);
        String[] parts = url.substring(url.indexOf("latitude=") + 9).split("&longitude=");
        String[] lats = parts[0].split(",");
        String[] lons = parts[1].split(",");
        assertEquals(25, lats.length);
        assertEquals(25, lons.length);
        for (String value : lats) assertTrue(Double.parseDouble(value) >= -90 && Double.parseDouble(value) <= 90);
        for (String value : lons) assertTrue(Double.parseDouble(value) >= -180 && Double.parseDouble(value) <= 180);
    }

    @Test
    void emitCoverageQueryUsesTheSitePointAtFullPrecision() {
        String url = Main.emitUrl(35.1234567, 1.2345674);
        assertTrue(url.contains("short_name=EMITL2BMIN"));
        assertTrue(url.contains("point=1.234567,35.123457"));

        JsonNode response = Main.JSON.createObjectNode().set("feed",
                Main.JSON.createObjectNode().set("entry", Main.JSON.createArrayNode().add(
                        Main.JSON.createObjectNode().put("time_start", "2026-05-29T11:51:10Z"))));
        Main.Source source = new Main.Source("EMIT", response, Main.SourceState.DATA, "ok");
        assertTrue(Main.emitCoverage(source).contains("latest 2026-05-29"));
        assertTrue(Main.emitCoverage(source).contains("pixel mineral IDs not extracted"));
        assertTrue(Main.directMineralogyStatus(source).contains("not been extracted"));
        assertEquals("/", Main.directMineralogyStatus(null));
    }

    @Test
    void mrdsQueryUsesWfsLatitudeLongitudeAxisOrderAndTenKilometerEnvelope() {
        String url = Main.mrdsUrl(35, 1);
        assertTrue(url.contains("typeName=mrds"));
        assertTrue(url.contains("urn:ogc:def:crs:EPSG::4326"));
        String[] bbox = url.substring(url.indexOf("bbox=") + 5).split("&")[0].split(",");
        assertEquals(5, bbox.length);
        assertTrue(Double.parseDouble(bbox[0]) < 35, "WFS 1.1 EPSG:4326 uses latitude first");
        assertTrue(Double.parseDouble(bbox[1]) < 1);
        assertTrue(Double.parseDouble(bbox[2]) > 35);
        assertTrue(Double.parseDouble(bbox[3]) > 1);
    }

    @Test
    void parsesAndSummarizesHistoricalMrdsRecordsWithinRadius() throws Exception {
        String xml = """
                <wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs"
                  xmlns:gml="http://www.opengis.net/gml" xmlns:ms="http://mapserver.gis.umn.edu/mapserver">
                  <gml:featureMember><ms:mrds>
                    <ms:geometry><gml:Point><gml:pos>34.999320 1.001760</gml:pos></gml:Point></ms:geometry>
                    <ms:dep_id>10073044</ms:dep_id><ms:site_name>El Abed - Oued Zaunder Mines</ms:site_name>
                    <ms:dev_stat>Producer</ms:dev_stat><ms:code_list> ZN PB CU AG CO AU</ms:code_list>
                  </ms:mrds></gml:featureMember>
                  <gml:featureMember><ms:mrds>
                    <ms:geometry><gml:Point><gml:pos>35.100000 1.000000</gml:pos></gml:Point></ms:geometry>
                    <ms:dep_id>outside</ms:dep_id><ms:site_name>Outside radius</ms:site_name>
                  </ms:mrds></gml:featureMember>
                </wfs:FeatureCollection>
                """;
        JsonNode parsed = Main.parseMrdsResponse(xml);
        assertEquals(2, parsed.path("features").size());
        assertEquals(Main.SourceState.DATA, Main.payloadState(Main.mrdsUrl(35, 1), parsed));

        Main.Source source = new Main.Source("USGS MRDS", parsed, Main.SourceState.DATA, "ok");
        String summary = Main.mrdsSummary(source, 35, 1);
        assertTrue(summary.contains("1 historical USGS MRDS site"));
        assertTrue(summary.contains("El Abed - Oued Zaunder Mines"));
        assertTrue(summary.contains("Zn, Pb, Cu, Ag, Co, Au"));
        assertFalse(summary.contains("Outside radius"));
        assertTrue(summary.contains("not current activity"));
    }

    @Test
    void doesNotTreatMrdsWfsErrorsAsEmptyCatalogResults() throws Exception {
        JsonNode parsed = Main.parseMrdsResponse("""
                <ows:ExceptionReport xmlns:ows="http://www.opengis.net/ows">
                  <ows:Exception exceptionCode="InvalidParameterValue"/>
                </ows:ExceptionReport>
                """);
        assertEquals(Main.SourceState.API_ERROR, Main.payloadState(Main.mrdsUrl(35, 1), parsed));
    }

    @Test
    void permafrostSummaryNamesProductCoverageWithoutInventingPointValue() {
        String summary = Main.permafrostCoverage(35);
        assertTrue(summary.contains("north of 30°N"));
        assertTrue(summary.contains("site pixel not sampled"));
    }

    @Test
    void parsesOpenMeteoMissingElevationValuesWithoutMarkingNetworkFailure() throws Exception {
        String url = "https://api.open-meteo.com/v1/elevation?latitude=90&longitude=1";
        ObjectNode response = (ObjectNode) Main.parseResponse(url, "{\"elevation\":[0,nan,0]}");
        assertTrue(response.path("elevation").get(1).isNull());
        assertEquals(Main.SourceState.DATA, Main.payloadState(url, response));

        String cachedJson = Main.JSON.writeValueAsString(response);
        assertTrue(cachedJson.contains("null"));
        assertFalse(cachedJson.contains("nan"));
        assertDoesNotThrow(() -> Main.JSON.readTree(cachedJson));
    }

    @Test
    void gbifUrlClampsBoundingBoxAtCoordinateLimits() {
        String url = Main.gbifUrl(90, 180);
        assertTrue(url.contains("decimalLatitude=89.900000,90.000000"));
        assertTrue(url.contains("decimalLongitude=179.900000,180.000000"));
        url = Main.gbifUrl(-90, -180);
        assertTrue(url.contains("decimalLatitude=-90.000000,-89.900000"));
        assertTrue(url.contains("decimalLongitude=-180.000000,-179.900000"));
    }

    @Test
    void expandedEarthRequestUrlsIncludeRequestedVariables() {
        String weather = Main.weatherUrl(35.1234567, 1.2345674);
        assertTrue(weather.contains("latitude=35.123457&longitude=1.234567"));
        assertTrue(weather.contains("soil_temperature_54cm"));
        assertTrue(weather.contains("soil_moisture_27_to_81cm"));
        assertTrue(weather.contains("direct_radiation,diffuse_radiation"));
        assertTrue(weather.contains("wind_speed_100m"));
        assertTrue(weather.contains("et0_fao_evapotranspiration"));

        String soil = Main.soilGridsUrl(35, 1);
        for (String property : List.of("sand", "clay", "silt", "soc", "phh2o", "bdod", "cfvo", "cec", "nitrogen")) {
            assertTrue(soil.contains("property=" + property));
        }
        String power = Main.powerUrl(35, 1, java.time.LocalDate.of(2025, 1, 1), java.time.LocalDate.of(2025, 12, 31));
        for (String parameter : List.of("T2MDEW", "TS", "RH2M", "PS", "WS10M_MAX", "WD10M")) {
            assertTrue(power.contains(parameter));
        }
        assertTrue(Main.airQualityUrl(35, 1).contains("pm2_5"));
        assertTrue(Main.airQualityUrl(35, 1).contains("ozone"));
    }

    @Test
    void rescalesSoilGridsValuesUsingTheReturnedFactors() throws Exception {
        JsonNode response = Main.JSON.readTree("""
                {"properties":{"layers":[
                  {"name":"sand","unit_measure":{"d_factor":10,"target_units":"g/kg"},"depths":[{"values":{"mean":4360}}]},
                  {"name":"phh2o","unit_measure":{"d_factor":10,"target_units":"pH*10"},"depths":[{"values":{"mean":74}}]},
                  {"name":"soc","unit_measure":{"d_factor":10,"target_units":"g/kg"},"depths":[{"values":{"mean":1990}}]}
                ]}}
                """);
        String soil = Main.soil(response);
        assertTrue(soil.contains("sand=436.00 g/kg"));
        assertTrue(soil.contains("pH=7.40"));
        assertTrue(soil.contains("organic carbon=199.00 g/kg"));
    }

    @Test
    void computesSunPositionAndDayLengthFromTimeAndCoordinates() throws Exception {
        JsonNode weather = Main.JSON.readTree("""
                {"current":{"time":"2026-03-20T12:00"},"utc_offset_seconds":0,
                 "daily":{"daylight_duration":[43200],"sunshine_duration":[36000]}}
                """);
        double[] sun = Main.solarPosition(java.time.LocalDateTime.of(2026, 3, 20, 12, 0), 0, 0, 0);
        assertTrue(sun[0] > 85 && sun[0] <= 90);
        assertEquals(12, Main.dayLengthHours(java.time.LocalDate.of(2026, 3, 20), 0), 0.2);
        assertTrue(Main.sunlight(weather, 0, 0).contains("sun elevation"));
    }

    @Test
    void reportsBothForecastDaysAndUsesTheCurrentSnowDepth() throws Exception {
        JsonNode weather = Main.JSON.readTree("""
                {"current":{"time":"2026-01-01T10:00"},
                 "hourly":{"time":["2026-01-01T10:00","2026-01-01T11:00"],"snow_depth":[0.1,0.2]},
                 "daily":{"time":["2026-01-01","2026-01-02"],"temperature_2m_min":[-2,-1],
                          "temperature_2m_max":[3,4],"temperature_2m_mean":[0,2],"snowfall_sum":[0.5]}}
                """);
        String forecast = Main.dailyTemperature(weather);
        assertTrue(forecast.contains("2026-01-01"));
        assertTrue(forecast.contains("2026-01-02"));
        assertTrue(Main.snow(weather).contains("0.10 m"));
        assertEquals(List.of("35", "1"), Main.parseDelimitedLine("35   1"));
        assertEquals(List.of("A, site", "35", "1"), Main.parseDelimitedLine("\"A, site\";35;1"));
    }

    @Test
    void omitsUnpairedEarthquakeMetrics() {
        ObjectNode data = Main.earthData(Main.Terrain.none(), null, null, null, null, null, null, null,
                null, null);
        assertFalse(data.path("metrics").path("Volcanic and impact features").has("Earthquake count"));
        assertFalse(data.has("eq"));
    }

    @Test
    void streamsCsvCoordinatesToJsonlWithoutOverwritingExistingOutput(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("coordinates.csv");
        Path output = tempDir.resolve("results.jsonl");
        Files.writeString(input, "name,longitude,latitude\nA,1,35\nB,not-a-number,4\nC,2,36\n");
        Main.BatchStats stats = Main.processBatch(input, output, (lat, lon) ->
                Main.JSON.createObjectNode().put("latitude", lat).put("longitude", lon));

        assertEquals(3, stats.attempted());
        assertEquals(2, stats.succeeded());
        assertEquals(1, stats.invalid());
        List<String> rows = Files.readAllLines(output);
        assertEquals(3, rows.size());
        assertEquals(35, Main.JSON.readTree(rows.get(0)).path("latitude").asDouble());
        assertEquals(1, Main.JSON.readTree(rows.get(0)).path("longitude").asDouble());
        assertEquals(3, Main.JSON.readTree(rows.get(1)).path("inputLine").asInt());
        assertEquals(36, Main.JSON.readTree(rows.get(2)).path("latitude").asDouble());

        assertThrows(java.io.IOException.class, () -> Main.processBatch(input, output, (lat, lon) ->
                Main.JSON.createObjectNode()));
        assertEquals(3, Files.readAllLines(output).size());
    }

    @Test
    void skipsRepeatedCoordinatesWithinBatch(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("duplicates.csv");
        Path output = tempDir.resolve("unique.jsonl");
        Files.writeString(input, "latitude,longitude\n35,1\n35.0,1.0\n36,2\n");
        List<String> processed = new ArrayList<>();

        Main.BatchStats stats = Main.processBatch(input, output, (lat, lon) -> {
            processed.add(lat + "," + lon);
            return Main.JSON.createObjectNode().put("latitude", lat).put("longitude", lon);
        });

        assertEquals(3, stats.attempted());
        assertEquals(2, stats.succeeded());
        assertEquals(List.of("35.0,1.0", "36.0,2.0"), processed);
        assertEquals(2, Files.readAllLines(output).size());
    }

    @Test
    void odeUrlBuildsValidPdsRestParameters() {
        String url = Main.odeUrl("LRO", "DLRE", "GCP", 43.07, -89.40, "m", true);
        assertTrue(url.contains("results=m"), "Must query metadata (results=m)");
        assertTrue(url.contains("ihid=LRO"));
        assertTrue(url.contains("iid=DLRE"));
        assertTrue(url.contains("pt=GCP"), "ODE requires ihid+iid+pt together");
        assertTrue(url.contains("westernlon="));
        assertTrue(url.contains("easternlon="));

        String catalog = Main.odeUrl("GRAIL", "LGRS", "SHADR", 43.07, 270.60, "m", false);
        assertTrue(catalog.contains("ihid=GRAIL"));
        assertTrue(catalog.contains("iid=LGRS"));
        assertTrue(catalog.contains("pt=SHADR"));
        assertFalse(catalog.contains("westernlon="), "Catalog/global queries omit location box");
        assertFalse(catalog.contains("minlat="));
    }

    @Test
    void overpassQueryAvoidsHeavyRegexScans() {
        String q = Main.overpassQuery(43.07, -89.40);
        assertTrue(q.contains("[timeout:6]"));
        assertFalse(q.contains("natural\"~"), "Regex natural scans are too slow for batch use");
        assertFalse(q.contains("nwr[\"waterway\"]"), "Unfiltered waterway scans are too slow");
        assertTrue(q.contains("cave_entrance"));
        assertTrue(q.contains("waterway\"=\"river\""));
    }

    @Test
    void geoapifyFallbackMapsSupportedFeaturesWithinTheirSearchRadii() throws Exception {
        String url = Main.geoapifyUrl(90, 1, "test-key");
        assertTrue(url.contains("apiKey=test-key"));
        assertTrue(url.contains("natural.mountain.cave_entrance"));
        assertTrue(url.contains("filter=circle%3A1.000000%2C90.000000%2C5000"));

        JsonNode response = Main.JSON.readTree("""
                {"features":[
                  {"properties":{"name":"Cave","categories":["natural.mountain.cave_entrance"]},"geometry":{"coordinates":[1,89.99]}},
                  {"properties":{"name":"Hot spring","categories":["natural.water.hot_spring","natural.water"]},"geometry":{"coordinates":[1,89.999]}},
                  {"properties":{"name":"Far spring","categories":["natural.water.spring","natural.water"]},"geometry":{"coordinates":[1,89.98]}},
                  {"properties":{"name":"River","categories":["natural.water.river_system","natural.water"]},"geometry":{"coordinates":[1,89.99]}}
                ]}
                """);
        JsonNode mapped = Main.geoapifyElements(response, 90, 1);
        assertEquals(3, mapped.path("elements").size(), "Keep the cave within 5 km and water features within 1.5 km");
        assertTrue(Main.features(mapped, Set.of("cave_entrance", "adit")).startsWith("1 mapped:"));
        assertTrue(Main.features(mapped, Set.of("water", "spring", "hot_spring", "waterway")).startsWith("2 mapped:"));
    }

    @Test
    void loadsGeoapifyKeyFromExternalFileWithoutPrintingIt(@TempDir Path tempDir) throws Exception {
        Path keyFile = tempDir.resolve("geoapify-api-key.txt");
        Files.writeString(keyFile, "test-key\n");
        Main.ApiKey key = Main.loadGeoapifyKey(null, keyFile);
        assertEquals("test-key", key.value());
        assertTrue(key.status().startsWith("key file "));

        Main.ApiKey missing = Main.loadGeoapifyKey(null, tempDir.resolve("missing.txt"));
        assertNull(missing.value());
        assertTrue(missing.status().contains("key file not found"));
    }

    @Test
    void loadsNoaaKeyFromSecondLineWithoutChangingGeoapifyKey(@TempDir Path tempDir) throws Exception {
        Path keyFile = tempDir.resolve("api-keys.txt");
        Files.writeString(keyFile, "geoapify-secret\nnoaa-secret\n");
        assertEquals("geoapify-secret", Main.loadGeoapifyKey(null, keyFile).value());
        Main.ApiKey noaa = Main.loadNoaaGeomagneticKey(null, keyFile);
        assertEquals("noaa-secret", noaa.value());
        assertTrue(noaa.status().contains("line 2"));
        assertEquals("environment-secret",
                Main.loadNoaaGeomagneticKey("environment-secret", keyFile).value());
        Files.writeString(keyFile, "geoapify-secret\n");
        Main.ApiKey missing = Main.loadNoaaGeomagneticKey(null, keyFile);
        assertNull(missing.value());
        assertTrue(missing.status().contains("no NOAA geomagnetic key on line 2"));
    }

    @Test
    void buildsNoaaWmmUrlWithHemisphereAndSiteElevation() {
        String url = Main.noaaGeomagneticUrl(43, -79, 185, java.time.LocalDate.of(2026, 11, 14), "a+b");
        assertTrue(url.contains("lat1=43.000000&lat1Hemisphere=N"));
        assertTrue(url.contains("lon1=79.000000&lon1Hemisphere=W"));
        assertTrue(url.contains("elevation=185.000000&elevationUnits=M"));
        assertTrue(url.contains("model=WMMHR"));
        assertTrue(url.contains("resultFormat=json&key=a%2Bb"));
    }

    @Test
    void parsesNoaaGeomagneticJsonMetrics() throws Exception {
        JsonNode response = Main.JSON.readTree("""
                {"results":[{"declination":-8.42,"inclination":67.15,"totalIntensity":52123.4}]}
                """);
        Main.Source source = new Main.Source("NOAA", response, Main.SourceState.DATA, "ok");
        assertEquals("-8.42 ° (NOAA WMMHR model)",
                Main.geomagneticMetric(source, Set.of("declination", "declinationangle"), "°"));
        assertEquals("52123.40 nT (NOAA WMMHR model)",
                Main.geomagneticMetric(source, Set.of("totalintensity", "totalfieldintensity", "totalfield"), "nT"));
    }

    @Test
    void createsGeoapifyKeyFileOnlyWhenMissing(@TempDir Path tempDir) throws Exception {
        Path keyFile = tempDir.resolve("config/geoapify-api-key.txt");
        Main.ensureGeoapifyKeyFile(keyFile);
        assertTrue(Files.isRegularFile(keyFile));
        assertEquals("", Files.readString(keyFile));

        Files.writeString(keyFile, "existing-key");
        Main.ensureGeoapifyKeyFile(keyFile);
        assertEquals("existing-key", Files.readString(keyFile));
    }

    @Test
    void emptyOverpassResultsAreReportedAsNoMappedRecords() throws Exception {
        JsonNode empty = Main.JSON.readTree("{\"elements\":[]}");
        assertEquals(Main.SourceState.EMPTY, Main.payloadState("https://overpass-api.de/api/interpreter", empty));
        assertEquals("0 mapped records (not evidence of absence)", Main.features(empty, Set.of("spring")));
    }

    @Test
    void earthDataUsesShortKeysAndScalars() {
        Main.Terrain terrain = Main.terrainFromElevations(new double[]{
                1,1,1,1,1, 1,1,1,1,1, 1,1,10,1,1, 1,1,1,1,1, 1,1,1,1,1
        }, 100);
        ObjectNode d = Main.earthData(terrain, null, null, null, null, null, null, null, null, null);
        assertTrue(d.has("elev"));
        assertTrue(d.has("slope"));
        assertEquals("nogo", d.path("go").asText());
        assertEquals("/", d.path("min").asText());
        assertEquals("10.00 m", d.path("metrics").path("Elevation and terrain").path("Elevation").asText());
        assertTrue(d.path("metrics").has("Temperature and climate"));
        assertTrue(d.path("metrics").has("Location metadata"));
        assertFalse(d.has("payload"));
        assertFalse(d.has("y0"), "Should not record unneeded metadata years");
        assertFalse(d.has("y1"), "Should not record unneeded metadata years");
    }

    @Test
    void omitsAnalogMatchGuidanceFromPerSiteMetrics() {
        ObjectNode earth = Main.earthData(Main.Terrain.none(), null, null, null, null,
                null, null, null, null, null);
        ObjectNode moon = Main.moonData(null, null, null, null, null, null, null, null,
                null, null, null, 35, 1);
        assertEquals("Elevation and terrain", earth.path("metrics").fieldNames().next());
        assertEquals("Elevation and terrain", moon.path("metrics").fieldNames().next());
        assertFalse(earth.path("metrics").has("Earth-analog match priority (ranked; guidance, not a score)"));
        assertFalse(moon.path("metrics").has("Earth-analog match priority (ranked; guidance, not a score)"));
    }

    @Test
    void polarEarthReportExplainsMetricsOutsideTheirDataCoverage() {
        ObjectNode data = Main.earthData(-90, 80, Main.Terrain.none(), null, null, null, null,
                null, null, null, null, null, null, null, null);
        JsonNode metrics = data.path("metrics");
        assertTrue(metrics.path("Soil properties").path("Clay fraction (0–5 cm)").asText().contains("Not applicable"));
        assertTrue(metrics.path("Geology and minerals").path("Mapped geologic unit (Macrostrat)").asText()
                .contains("Antarctic ice sheet"));
        assertTrue(metrics.path("Geology and minerals").path("Direct mineralogy").asText()
                .contains("ISS"));
        assertEquals("0 km; coordinate is on the Antarctic ice sheet (not an inventory-derived distance)",
                metrics.path("Cryosphere").path("Distance to nearest glacier").asText());
        assertTrue(Main.soilGridsUnsupportedAt(-90));
        assertFalse(Main.soilGridsUnsupportedAt(-89));
    }

    @Test
    void reportsOpenMeteoGridCoordinateWhenItDiffersFromRequestedPole() throws Exception {
        JsonNode weather = Main.JSON.readTree("""
                {"latitude":-89.80668,"longitude":77.14285}
                """);
        String result = Main.modelGridPoint(weather, -90, 80);
        assertTrue(result.contains("-89.806680°"));
        assertTrue(result.contains("km from requested coordinate"));
    }

    @Test
    void distinguishesUnavailableSoilService() {
        Main.Source soil = new Main.Source("SoilGrids", null, Main.SourceState.NETWORK_ERROR, "HTTP 503");
        assertTrue(Main.soilSourceStatus(soil).contains("HTTP 503"));
    }

    @Test
    void moonDataExcludesUnreportedSensors() {
        ObjectNode d = Main.moonData(null, null, null, null, null, null, null, null,
                null, null, null, 35, 1);
        JsonNode metrics = d.path("metrics");
        assertFalse(d.has("grail"), "Moon data should not record sensors outside the report sections");
        assertEquals("nogo", d.path("go").asText());
        assertFalse(metrics.has("Atmosphere"));
        assertFalse(metrics.has("Wind and dust processes"));
        assertFalse(metrics.has("Soil properties"));
        assertFalse(metrics.has("Vegetation and surface cover"));
        assertFalse(metrics.path("Location metadata").has("Population density"));
        assertFalse(metrics.toString().contains("Not applicable"));
        assertEquals("35.000000°", metrics.path("Location metadata").path("Latitude").asText());
        assertTrue(metrics.has("Geology and minerals"));
        assertTrue(d.path("gravity_ref_m_s2").isNumber());
        assertTrue(metrics.path("Solar and radiation").path("Day length").asText().contains("29.53"));
        assertTrue(metrics.path("Gravity and magnetics")
                .path("Lunar reference surface gravity (spherical model)").asText().contains("spherical reference"));
    }

    @Test
    void mapsMoonCoordinatesToTheCorrectGrailGridCell() {
        Main.LunarRasterCell cell = Main.lunarGrailRasterCell(10, 10);
        assertEquals(1280, cell.row());
        assertEquals(160, cell.column());
        assertEquals(29_491_840L, cell.byteOffset());
        assertEquals(cell, Main.lunarGrailRasterCell(10, 370));
        assertEquals(cell, Main.lunarGrailRasterCell(10, -350));
        assertEquals(0, Main.lunarGrailRasterCell(90, 0).row());
        assertEquals(Main.LUNAR_GRAIL_GRID_ROWS - 1, Main.lunarGrailRasterCell(-90, 0).row());
        assertThrows(IllegalArgumentException.class, () -> Main.lunarGrailRasterCell(90.1, 0));
    }

    @Test
    void reportsGrailAnomalyAndBouguerPointSamplesSeparately() throws Exception {
        Main.Source samples = new Main.Source("GRAIL local grid samples", Main.JSON.readTree("""
                {"anomaly_mgal":-49.43464,"bouguer_disturbance_mgal":84.96935,"grid_row":1280,"grid_column":160}
                """), Main.SourceState.DATA, "ok");
        ObjectNode data = Main.moonData(null, null, null, null, null, null, null, null,
                null, null, null, null, null, samples, 10, 10);
        JsonNode gravity = data.path("metrics").path("Gravity and magnetics");

        assertTrue(gravity.path("Local gravity anomaly (GRAIL GRGM1200A)").asText().contains("-49.43 mGal"));
        assertTrue(gravity.path("Bouguer gravity disturbance (GRAIL GRGM1200A)").asText().contains("84.97 mGal"));
        assertTrue(gravity.path("Local gravity anomaly (GRAIL GRGM1200A)").asText().contains("nearest 16-pixel/degree cell"));
        assertTrue(data.path("grail_grid_row").isNumber());
    }

    @Test
    void parsesLunarGdsXmlAndCsvPointData() throws Exception {
        String xml = """
                <GDSResults><Status>success</Status><StatusNote>ok</StatusNote><Count>2</Count>
                  <ResultFiles><ResultFile><URL>https://ode.rsl.wustl.edu/moon/topo_simple_csv.csv</URL></ResultFile></ResultFiles>
                </GDSResults>
                """;
        String queryUrl = Main.lunarGdsUrl("lolardr", 35, 1);
        JsonNode query = Main.parseResponse(queryUrl, xml);
        assertEquals(Main.SourceState.DATA, Main.payloadState(queryUrl, query));
        assertEquals(2, query.path("count").asInt());
        assertTrue(query.path("resultFiles").get(0).asText().endsWith("topo_simple_csv.csv"));

        String csv = """
                Pt_Longitude,Pt_Latitude,Topography
                1.000,35.000,-1680
                1.001,35.000,-1670
                1.000,35.001,-1690
                1.001,35.001,-1680
                """;
        String fileUrl = "https://ode.rsl.wustl.edu/moon/topo_simple_csv.csv";
        JsonNode table = Main.parseResponse(fileUrl, csv);
        assertEquals(Main.SourceState.DATA, Main.payloadState(fileUrl, table));
        Main.Source shots = new Main.Source("LOLA", table, Main.SourceState.DATA, "ok");
        Main.LunarTerrain terrain = Main.lunarTerrain(shots, 35, 1);
        assertEquals(4, terrain.count());
        assertEquals(4, terrain.localCount());
        assertEquals(0, terrain.nearestDistance(), 0.001);
        assertTrue(terrain.relief() > 0);
        assertTrue(Double.isFinite(terrain.slope()));
        assertTrue(Main.lunarTerrainSummary(shots, 35, 1).contains("4 LOLA shots"));
        ObjectNode compact = Main.moonData(null, null, null, null, null, null, null, null,
                shots, null, null, 35, 1);
        assertEquals(4, compact.path("lola_samples").size());
        assertTrue(compact.path("lola_samples").get(0).path("elevation_m").isNumber());
        assertTrue(compact.path("lola_samples").get(0).path("distance_to_coordinate_m").isNumber());
    }

    @Test
    void lunarTerrainUsesOnlyNearbyShotsForLocalRelief() {
        ObjectNode table = Main.JSON.createObjectNode();
        ArrayNode rows = table.putArray("rows");
        addLolaRow(rows, 0, 0, 0);
        addLolaRow(rows, 0, .003, 20);
        addLolaRow(rows, .003, 0, 30);
        addLolaRow(rows, .004, .004, 40);
        addLolaRow(rows, .04, .04, 10_000);
        table.put("query_halfwidth_deg", .05);
        Main.Source source = new Main.Source("LOLA", table, Main.SourceState.DATA, "ok");

        Main.LunarTerrain terrain = Main.lunarTerrain(source, 0, 0);

        assertEquals(5, terrain.count());
        assertEquals(4, terrain.localCount());
        assertEquals(.05, terrain.searchHalfWidthDeg(), 0.0001);
        assertEquals(40, terrain.relief(), 0.001);
    }

    static void addLolaRow(ArrayNode rows, double lat, double lon, double elevation) {
        ObjectNode row = rows.addObject();
        row.put("Pt_Latitude", lat);
        row.put("Pt_Longitude", lon);
        row.put("Topography", elevation);
    }

    @Test
    void parsesHorizonsSunAzimuthAndElevation() throws Exception {
        JsonNode response = Main.JSON.readTree("""
                {"result":"Sun Observer-LT range\\n$$SOE\\n 2026-Nov-15 00:11 *m 136.18 -1.02 0.988 0.0\\n$$EOE"}
                """);
        Main.Source horizons = new Main.Source("Horizons", response, Main.SourceState.DATA, "ok");
        double[] angles = Main.lunarSunAngles(horizons);
        assertNotNull(angles);
        assertEquals(136.18, angles[0], 0.001);
        assertEquals(-1.02, angles[1], 0.001);
        assertTrue(Main.lunarIlluminationSummary(horizons).contains("elevation -1.02°"));
        assertArrayEquals(new double[]{0, 0}, Main.lunarSolarIrradiance(horizons));
    }

    @Test
    void parsesUsGsLunarCraterCatalogAndFindsNearestNamedRim() throws Exception {
        String kml = """
                <kml xmlns="http://www.opengis.net/kml/2.2"><Document>
                  <Placemark><name>Large crater</name><ExtendedData><SchemaData>
                    <SimpleData name="clean_name">Large crater</SimpleData>
                    <SimpleData name="type">Crater, craters</SimpleData>
                    <SimpleData name="center_lat">0</SimpleData>
                    <SimpleData name="center_lon">359.9</SimpleData>
                    <SimpleData name="diameter">10</SimpleData>
                  </SchemaData></ExtendedData></Placemark>
                  <Placemark><name>Small crater</name><ExtendedData><SchemaData>
                    <SimpleData name="clean_name">Small crater</SimpleData>
                    <SimpleData name="type">Crater, craters</SimpleData>
                    <SimpleData name="center_lat">0</SimpleData>
                    <SimpleData name="center_lon">0.1</SimpleData>
                    <SimpleData name="diameter">1</SimpleData>
                  </SchemaData></ExtendedData></Placemark>
                  <Placemark><name>Not a crater</name><ExtendedData><SchemaData>
                    <SimpleData name="clean_name">Not a crater</SimpleData>
                    <SimpleData name="type">Mare, maria</SimpleData>
                    <SimpleData name="center_lat">0</SimpleData>
                    <SimpleData name="center_lon">0.05</SimpleData>
                    <SimpleData name="diameter">20</SimpleData>
                  </SchemaData></ExtendedData></Placemark>
                  <Placemark><name>Named rille</name><ExtendedData><SchemaData>
                    <SimpleData name="clean_name">Named rille</SimpleData>
                    <SimpleData name="type">Rima, rimae</SimpleData>
                    <SimpleData name="center_lat">0</SimpleData>
                    <SimpleData name="center_lon">0.05</SimpleData>
                  </SchemaData></ExtendedData></Placemark>
                </Document></kml>
                """;
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("moon.kml"));
            zip.write(kml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        JsonNode catalog = Main.parseLunarNamedCraterCatalog(bytes.toByteArray());
        Main.Source source = new Main.Source("USGS/IAU", catalog, Main.SourceState.DATA, "ok");

        assertEquals(2, catalog.path("craters").size());
        assertEquals(1, catalog.path("landforms").size());
        JsonNode nearest = Main.lunarNearestNamedCrater(source, 0, .05);
        assertEquals("Large crater", nearest.path("name").asText());
        assertEquals(0, nearest.path("rim_distance_km").asDouble(), 0.001);
        assertTrue(Main.lunarNearestCraterMetric(Main.JSON.createObjectNode()
                .set("nearest_named_crater", nearest)).contains("spherical circular-rim estimate"));
    }

    @Test
    void pointSamplesUsGsLunarGeologyAndEstimatesMappedBoundaryDistance() throws Exception {
        JsonNode response = Main.JSON.readTree("""
                {"features":[{"attributes":{
                  "FIRST_UnitSymbol":"Iif",
                  "FIRST_UnitAge":"Imbrian",
                  "FIRST_UnitName":"Imbrium Fra Mauro Formation Unit",
                  "Description":"Hummocky ejecta deposits.",
                  "Interpretation":"Imbrium basin ejecta."
                },"geometry":{"rings":[[
                  [-1000,-1000],[1000,-1000],[1000,1000],[-1000,1000],[-1000,-1000]
                ]]}}]}
                """);
        Main.Source source = new Main.Source("USGS geology", response, Main.SourceState.DATA, "ok");
        assertEquals(1000, Main.lunarMappedBoundaryDistanceMeters(
                response.path("features").get(0).path("geometry"), 0, 0, 0), 0.001);
        assertTrue(Main.lunarGeologyUrl(43, 281).contains("geometry=-2395544.684%2C1446977.368"));
        ObjectNode data = Main.moonData(null, null, null, null, null, null, null, null,
                null, null, null, null, source, 0, 0);

        assertEquals("Imbrian", data.path("mapped_unit").path("age").asText());
        assertEquals(1, data.path("mapped_unit").path("boundary_distance_km").asDouble(), 0.001);
        assertTrue(data.path("metrics").path("Geology and minerals")
                .path("Mapped surface unit (USGS 1:5M)").asText().contains("Imbrium Fra Mauro"));
        assertTrue(data.path("metrics").path("Geology and minerals")
                .path("Distance to nearest mapped unit boundary").asText().contains("approximate"));
        assertTrue(data.path("metrics").path("Elevation and terrain")
                .path("Mapped landform / terrain type (USGS 1:5M)").asText().contains("Impact-basin ejecta"));
        assertFalse(data.has("mapped_linear_features"));
    }

    @Test
    void bilinearlySamplesLunarMagneticComponentsAndHandlesLongitudeWrap() {
        int rows = 261, columns = 540;
        double[][] magnitude = new double[rows][columns];
        double[][] radial = new double[rows][columns];
        double[][] east = new double[rows][columns];
        double[][] north = new double[rows][columns];
        int latitudeIndex = 130, nextLatitudeIndex = 131;
        int longitudeIndex = 0, nextLongitudeIndex = 1;
        double[][] radialValues = {{1, 3}, {5, 7}};
        double[][] eastValues = {{2, 4}, {6, 8}};
        double[][] northValues = {{3, 5}, {7, 9}};
        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 2; column++) {
                int y = row == 0 ? latitudeIndex : nextLatitudeIndex;
                int x = column == 0 ? longitudeIndex : nextLongitudeIndex;
                magnitude[y][x] = 100;
                radial[y][x] = radialValues[row][column];
                east[y][x] = eastValues[row][column];
                north[y][x] = northValues[row][column];
            }
        }
        Main.LunarMagneticGrid grid = new Main.LunarMagneticGrid(magnitude, radial, east, north, false);

        JsonNode sample = Main.sampleLunarMagneticGrid(grid, 0.25, 2d / 6);
        JsonNode wrappedSample = Main.sampleLunarMagneticGrid(grid, 0.25, 360 + 2d / 6);

        assertEquals(4, sample.path("radial_nT").asDouble(), 0.001);
        assertEquals(5, sample.path("east_nT").asDouble(), 0.001);
        assertEquals(6, sample.path("north_nT").asDouble(), 0.001);
        assertEquals(100, sample.path("magnitude_nT").asDouble(), 0.001);
        assertEquals(sample.path("magnitude_nT").asDouble(), wrappedSample.path("magnitude_nT").asDouble(), 0.001);
        assertNull(Main.sampleLunarMagneticGrid(grid, 1, 1), "zero-valued cells are no-data");
    }

    @Test
    void samplesLunarProspectorCompositionFromContainingEqualAreaPixel() {
        double[] estimates = new double[11];
        estimates[2] = 0.10;
        estimates[3] = 0.15;
        estimates[4] = 0.45;
        estimates[5] = 0.12;
        estimates[6] = 0.02;
        estimates[7] = 0.08;
        estimates[8] = 1_320.3;
        estimates[9] = 3.2465;
        estimates[10] = 0.9;
        double[] standardDeviations = new double[9];
        standardDeviations[4] = 0.001;
        standardDeviations[5] = 0.005;
        standardDeviations[6] = 2;
        standardDeviations[7] = 0.2;
        Main.LunarCompositionPixel pixel = new Main.LunarCompositionPixel(
                42, 10, 12, 10, 12, estimates, standardDeviations);
        Main.LunarCompositionGrid grid = new Main.LunarCompositionGrid(List.of(pixel), true);

        JsonNode sample = Main.sampleLunarCompositionGrid(grid, 11, 370);

        assertEquals(42, sample.path("pixel_index").asInt());
        assertEquals(8, sample.path("FeO_wt_percent").asDouble(), 0.001);
        assertEquals(0.5, sample.path("FeO_wt_percent_stddev").asDouble(), 0.001);
        assertEquals(2, sample.path("TiO2_wt_percent").asDouble(), 0.001);
        assertEquals(10, sample.path("MgO_wt_percent").asDouble(), 0.001);
        assertEquals(1_320.3, sample.path("K_ppm").asDouble(), 0.001);
        assertEquals(3.2465, sample.path("Th_ppm").asDouble(), 0.001);
        assertTrue(Main.lunarCompositionMetric(Main.JSON.createObjectNode()
                .set("lunar_composition", sample)).contains("FeO 8.00 wt%"));
        assertNull(Main.sampleLunarCompositionGrid(grid, 13, 10));
    }

    @Test
    void includesSampledLunarMagneticsAndCompositionInComparableMetrics() throws Exception {
        Main.Source magnetic = new Main.Source("LP/Kaguya", Main.JSON.readTree("""
                {"magnitude_nT":0.606,"radial_nT":-0.2983,"east_nT":-0.1286,"north_nT":0.3964,
                 "declination_deg":-18.0,"inclination_deg":35.0}
                """), Main.SourceState.DATA, "ok");
        Main.Source composition = new Main.Source("LP GRS", Main.JSON.readTree("""
                {"FeO_wt_percent":7.8475,"TiO2_wt_percent":1.6256,"K_ppm":1320.3,"Th_ppm":3.2465}
                """), Main.SourceState.DATA, "ok");

        ObjectNode data = Main.moonData(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, magnetic, composition, 10, 10);
        JsonNode metrics = data.path("metrics");

        assertTrue(metrics.path("Gravity and magnetics")
                .path("Magnetic field intensity (LP/Kaguya crustal model)").asText().contains("0.61 nT"));
        assertTrue(metrics.path("Gravity and magnetics")
                .path("Magnetic vector (east, north, radial outward)").asText().contains("E -0.129, N +0.396"));
        assertTrue(metrics.path("Geology and minerals")
                .path("Elemental/oxide composition (Fe, Ti, Th, K)").asText().contains("FeO 7.85 wt%"));
    }

    @Test
    void skipsLunarGeologyQueriesOutsidePublishedMercatorExtent() {
        Main.Source source = Main.lunarGeologyData(70, 0);
        assertEquals(Main.SourceState.EMPTY, source.state());
        assertTrue(source.status().contains("Mercator layer extent"));
    }

    @Test
    void estimatesLunarDaylightOverHorizonsEphemerisSamples() throws Exception {
        JsonNode response = Main.JSON.readTree("""
                {"result":"$$SOE\\n2026-Nov-14 00:00 i 0 -10 1.0 0.1\\n2026-Nov-14 06:00 i 10 10 1.0 0.1\\n2026-Nov-14 12:00 i 20 20 1.0 0.1\\n2026-Nov-14 18:00 i 30 -10 1.0 0.1\\n2026-Nov-15 00:00 i 40 -20 1.0 0.1\\n2026-Nov-15 06:00 i 50 10 1.0 0.1\\n$$EOE"}
                """);
        Main.Source horizons = new Main.Source("Horizons", response, Main.SourceState.DATA, "ok");

        assertEquals(15, Main.lunarSunlightHours(horizons), 0.001);
        assertEquals(1.25, Main.lunarSolarCycleDays(horizons), 0.001);
        ObjectNode data = Main.moonData(null, null, null, null, null, null, null, null,
                null, null, horizons, 35, 1);
        assertEquals(15, data.path("sunlight_hours_cycle").asDouble(), 0.01);
        assertEquals(1, data.path("solar_distance_au").asDouble(), 0.001);
        assertTrue(data.path("metrics").path("Solar and radiation").path("Sunshine duration").asText()
                .contains("15.0 h above a flat horizon"));
        assertArrayEquals(new double[]{1361, 680.5}, Main.lunarSolarIrradiance(
                new Main.Source("Horizons", Main.JSON.readTree("""
                        {"result":"$$SOE\\n2026-Nov-14 00:00 i 0 30 1.0 0.1\\n$$EOE"}
                        """), Main.SourceState.DATA, "ok")), 0.001);
    }

    @Test
    void derivesDivinerDayNightThermalContrastFromBestCalibrationRows() throws Exception {
        String tableText = """
                # date, utc, c, tb, qca, qge, qmi, csunzen
                "14-Nov-2026", "00:00:00", 6, 300.0, 000, 012, 000, 30
                "15-Nov-2026", "01:00:00", 6, 100.0, 000, 012, 000, 120
                "16-Nov-2026", "02:00:00", 6, 400.0, 001, 012, 000, 30
                """;
        String tableUrl = "https://oderest.rsl.wustl.edu/output/DivinerRDR_test.tab";
        JsonNode table = Main.parseResponse(tableUrl, tableText);
        Main.Source source = new Main.Source("Diviner", table, Main.SourceState.DATA, "ok");

        Main.DivinerChannelStats stats = Main.divinerStats(source).channels().get(0);

        assertEquals(1, stats.daytimeCount());
        assertEquals(300, stats.daytimeMeanKelvin(), 0.001);
        assertEquals(1, stats.nighttimeCount());
        assertEquals(100, stats.nighttimeMeanKelvin(), 0.001);
        assertEquals(200, stats.dayNightContrastKelvin(), 0.001);
        assertTrue(Main.lunarThermalContrastSummary(source).contains("Δ=200.0 K"));
    }

    @Test
    void parsesDivinerThermalRecordsAndKeepsQualityFlagsWithNumericSummaries() throws Exception {
        String tableText = """
                # Header
                # Header
                # Header
                # date, utc, c, tb, qca, qge, qmi
                "14-Nov-2026", "00:00:00", 6, 250.5, 000, 000, 000
                "15-Nov-2026", "01:00:00", 7, 100.0, 000, 012, 000
                "13-Nov-2026", "02:00:00", 8, -9999, 001, 000, 000
                """;
        String tableUrl = "https://oderest.rsl.wustl.edu/output/DivinerRDR_test.tab";
        JsonNode table = Main.parseResponse(tableUrl, tableText);
        assertEquals(Main.SourceState.DATA, Main.payloadState(tableUrl, table));
        Main.Source source = new Main.Source("Diviner", table, Main.SourceState.DATA, "ok");
        Main.DivinerStats stats = Main.divinerStats(source);
        assertEquals(3, stats.recordCount());
        assertEquals(2, stats.validTemperatureCount());
        assertEquals(250.5, stats.channels().get(0).meanKelvin(), 0.001);
        assertEquals(1, stats.channels().get(0).qcaQmiZeroCount());
        assertEquals(100.0, stats.channels().get(1).minKelvin(), 0.001);
        assertEquals(1, stats.channels().get(1).qcaQmiZeroCount());
        assertEquals("2026-11-13", stats.firstDate());
        assertEquals("2026-11-15", stats.lastDate());
        assertTrue(Main.lunarObservationSummary(source).contains("qge=0 2/3"));
        assertTrue(Main.lunarObservationSummary(source).contains("all flags zero 1/3"));
        assertEquals("0:2,1:1", stats.qcaFlagCounts());
        assertEquals("0:2,12:1", stats.qgeFlagCounts());

        ObjectNode compact = Main.moonData(null, null, null, null, null, null, null, null,
                null, source, null, 35, 1);
        assertEquals(3, compact.path("diviner_n").asInt());
        assertEquals(3, compact.path("diviner_observations").size());
        assertEquals(250.5, compact.path("diviner_observations").get(0).path("brightness_temperature_k").asDouble(), 0.001);
        assertEquals(250.5, compact.path("div6_mean_k").asDouble(), 0.001);
        assertEquals(250.5, compact.path("div6_qca0_qmi0_mean_k").asDouble(), 0.001);
        assertEquals(100.0, compact.path("div7_min_k").asDouble(), 0.001);
        assertEquals("0:2,12:1", compact.path("diviner_qge_flags").asText());
    }

    @Test
    void buildsSmallLunarGdsQueriesForTerrainAndThermalData() {
        String lola = Main.lunarGdsUrl("lolardr", 35, 1);
        assertTrue(lola.contains("results=csv"));
        assertTrue(lola.contains("minlat=34.99500"));
        assertTrue(lola.contains("maxlat=35.00500"));
        String expanded = Main.lunarGdsUrl("lolardr", 43, 281, .05);
        assertTrue(expanded.contains("minlat=42.95000"));
        assertTrue(expanded.contains("maxlat=43.05000"));
        assertTrue(expanded.contains("westernlon=280.95000"));
        assertTrue(expanded.contains("easternlon=281.05000"));
        String primeMeridian = Main.lunarGdsUrl("lolardr", 35, 0);
        assertTrue(primeMeridian.contains("westernlon=359.99500"));
        assertTrue(primeMeridian.contains("easternlon=0.00500"));
        assertTrue(Main.lunarGdsUrl("divinerrdr", 35, 1).contains("channel=ffffftttt"));
    }
}
