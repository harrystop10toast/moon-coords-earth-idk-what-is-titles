package org.example;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Set lever to 0 for Earth or 1 for Moon. Change LAT and LON to inspect another location. */
public final class Main {
    public static final int lever = 1;
    public static final double LAT = 30, LON = 90;
    static final String NA = "/";
    static final String LUNAR_NAMED_CRATER_URL =
            "https://asc-planetarynames-data.s3.us-west-2.amazonaws.com/MOON_nomenclature_center_pts.kmz";
    static final String LUNAR_GEOLOGY_QUERY_URL =
            "https://services.arcgis.com/v01gqwM5QqNysAAi/arcgis/rest/services/Features_Fortezzo_et_al_2020_Unified_Map_of_the_Moon/FeatureServer/7/query";
    static final String LUNAR_MAGNETIC_MAP_URL =
            "https://pds-ppi.igpp.ucla.edu/data/lunar_crust_magnetic.field_map/data/Bm_30km_65S65N_0E360E.tab";
    static final String LUNAR_GRS_COMPOSITION_MAP_URL =
            "https://pds-geosciences.wustl.edu/lunar/lp-l-grs-5-elem-abundance-v1/lp_9001/data/lpgrs_high1_elem_abundance_2deg.tab";
    static final String LUNAR_GRAIL_RASTER_BASE =
            "https://pds-geosciences.wustl.edu/grail/grail-l-lgrs-5-rdr-v1/grail_1001/rsdmap/";
    static final String LUNAR_GRAIL_ANOMALY_RASTER =
            LUNAR_GRAIL_RASTER_BASE + "gggrx_1200a_anom_l1200.img";
    static final String LUNAR_GRAIL_BOUGUER_RASTER =
            LUNAR_GRAIL_RASTER_BASE + "gggrx_1200a_boug_l1200.img";
    static final int LUNAR_GRAIL_GRID_PIXELS_PER_DEGREE = 16;
    static final int LUNAR_GRAIL_GRID_COLUMNS = 5_760;
    static final int LUNAR_GRAIL_GRID_ROWS = 2_880;
    static final int LUNAR_GRAIL_GRID_RECORD_BYTES = LUNAR_GRAIL_GRID_COLUMNS * Float.BYTES;
    static final String LUNAR_LOLA_DEM_RASTER =
            "https://imbrium.mit.edu/DATA/LOLA_GDR/CYLINDRICAL/FLOAT_IMG/LDEM_128_FLOAT.IMG";
    static final int LUNAR_LOLA_DEM_PIXELS_PER_DEGREE = 128;
    static final int LUNAR_LOLA_DEM_COLUMNS = 46_080;
    static final int LUNAR_LOLA_DEM_ROWS = 23_040;
    static final int LUNAR_LOLA_DEM_RECORD_BYTES = LUNAR_LOLA_DEM_COLUMNS * Float.BYTES;
    static final double LUNAR_LOLA_DEM_PIXEL_SIZE_M = 236.901;
    static final double LUNAR_MAP_RADIUS_M = 1_737_400;
    static final double LUNAR_GEOLOGY_MAX_LAT = 57.0;
    static final int MAX_ATTEMPTS = 2;
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(8);
    static final Duration OSM_TIMEOUT = Duration.ofSeconds(8);
    static final long MAX_RESPONSE_BYTES = 20_000_000L;
    static final Path CACHE_DIR = Path.of(".date-deletor-cache");
    static final Path LUNAR_STATIC_MAP_CACHE_DIR = CACHE_DIR.resolve("lunar-static-maps");
    public static final Path REPORT_FILE = Path.of("site-reports.json");
    public static Path reportFile = REPORT_FILE;
    static final ObjectMapper JSON = new ObjectMapper();
    static final String CACHE_MARKER = "_dateDeletorCache";
    static final String[] OVERPASS_ENDPOINTS = {
            "https://overpass-api.de/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
    };
    static final ExecutorService FETCH_POOL = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "date-deletor-fetch");
        t.setDaemon(true);
        return t;
    });
    // Use the default HttpClient executor — sharing FETCH_POOL deadlocks when all worker threads
    // block inside HTTP.send waiting for that same pool to process the response.
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Object STORE_LOCK = new Object();
    private static final Object OVERPASS_LOCK = new Object();
    private static final Object LUNAR_STATIC_MAP_LOCK = new Object();
    private static volatile Instant overpassDownUntil = Instant.EPOCH;
    private static volatile LunarMagneticGrid cachedLunarMagneticGrid;
    private static volatile LunarCompositionGrid cachedLunarCompositionGrid;

    public static void main(String[] args) {
        if (args != null && args.length > 0 && "--batch".equals(args[0])) {
            if (args.length != 3) {
                System.err.println("Usage: java Main --batch <coordinates.csv|tsv> <new-output.jsonl>");
                return;
            }
            ensureGeoapifyKeyFile(geoapifyKeyFilePath());
            try {
                BatchStats stats = runBatch(Path.of(args[1]), Path.of(args[2]));
                System.err.printf(Locale.US, "Batch complete: %d sites written, %d invalid rows skipped.%n",
                        stats.succeeded(), stats.invalid());
            } catch (IOException e) {
                System.err.println("Batch failed: " + e.getMessage());
            }
            return;
        }
        int l = lever;
        double lat = LAT, lon = LON;
        if (args != null && args.length >= 3) {
            try {
                l = Integer.parseInt(args[0]);
                lat = Double.parseDouble(args[1]);
                lon = Double.parseDouble(args[2]);
            } catch (NumberFormatException e) {
                System.err.println("Usage: java Main [lever: 0|1] [lat] [lon]");
                return;
            }
        } else if (args != null && args.length == 2) {
            try {
                lat = Double.parseDouble(args[0]);
                lon = Double.parseDouble(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Usage: java Main [lever: 0|1] [lat] [lon]");
                return;
            }
        }
        ensureGeoapifyKeyFile(geoapifyKeyFilePath());
        runSite(l, lat, lon);
    }

    public static void runSite(int lever, double lat, double lon) {
        if (lever == 1) { moonReport(lat, lon); return; }
        if (lever != 0) { System.err.println("lever must be 0 (Earth) or 1 (Moon)."); return; }
        earthReport(lat, lon);
    }

    public static void earthReport() { earthReport(LAT, LON); }

    public static void earthReport(double lat, double lon) {
        if (!validCoordinate(lat, lon)) { System.err.println("LAT must be [-90,90] and LON [-180,180]."); return; }
        if (printSavedReportIfPresent("earth", lat, lon)) return;
        EarthSiteResult result = collectEarthSite(lat, lon);
        Source elevation = result.elevation(), geology = result.geology(), soil = result.soil(), power = result.power();
        Source weather = result.weather(), air = result.air(), gbif = result.gbif(), osm = result.osm();
        Source emit = result.emit(), mrds = result.mrds(), geomagnetic = result.geomagnetic();
        ObjectNode readableOutput = report(lat, lon, result.terrain(), geology, soil, power, weather, air, gbif, osm,
                emit, mrds, geomagnetic, result.sources(), result.start(), result.end(), result.data());
        persistReport("earth", lat, lon, result.data(), readableOutput);
    }

    static EarthSiteResult collectEarthSite(double lat, double lon) {
        if (!validCoordinate(lat, lon)) throw new IllegalArgumentException("Invalid Earth coordinate.");
        LocalDate end = LocalDate.now().minusDays(14), start = end.minusDays(364);
        CompletableFuture<Source> elevationF = asyncGet("Open-Meteo elevation", elevationUrl(lat, lon));
        CompletableFuture<Source> geologyF = asyncGet("Macrostrat geology", "https://macrostrat.org/api/v2/geologic_units/map?lat=" + coordinate(lat) + "&lng=" + coordinate(lon) + "&response=long");
        CompletableFuture<Source> soilF = soilGridsUnsupportedAt(lat)
                ? CompletableFuture.completedFuture(new Source("ISRIC SoilGrids", null, SourceState.NOT_APPLICABLE,
                        "not queried: SoilGrids does not model surface soils on the Antarctic ice sheet or Arctic Ocean"))
                : asyncGet("ISRIC SoilGrids", soilGridsUrl(lat, lon));
        CompletableFuture<Source> powerF = asyncGet("NASA POWER climate", powerUrl(lat, lon, start, end));
        CompletableFuture<Source> weatherF = asyncGet("Open-Meteo weather", weatherUrl(lat, lon));
        CompletableFuture<Source> airF = asyncGet("Open-Meteo air quality", airQualityUrl(lat, lon));
        CompletableFuture<Source> gbifF = asyncGet("GBIF occurrences", gbifUrl(lat, lon));
        CompletableFuture<Source> osmF = CompletableFuture.supplyAsync(() -> getOsm(lat, lon), FETCH_POOL);
        CompletableFuture<Source> emitF = asyncGet("NASA EMIT mineral map coverage", emitUrl(lat, lon));
        CompletableFuture<Source> mrdsF = asyncGet("USGS MRDS mineral occurrences", mrdsUrl(lat, lon));
        Source elevation = elevationF.join(), geology = geologyF.join(), soil = soilF.join(), power = powerF.join();
        Terrain t = terrain(elevation.json);
        CompletableFuture<Source> geomagneticF = CompletableFuture.supplyAsync(
                () -> noaaGeomagneticSource(lat, lon, t.ok() ? t.elevation : 0, LocalDate.now()), FETCH_POOL);
        Source weather = weatherF.join(), air = airF.join(), gbif = gbifF.join(), osm = osmF.join();
        Source emit = emitF.join(), mrds = mrdsF.join(), geomagnetic = geomagneticF.join();
        List<Source> sources = List.of(elevation, geology, soil, power, weather, air, gbif, osm, emit, mrds, geomagnetic);
        ObjectNode data = earthData(lat, lon, t, geology, soil, power, weather, air, gbif, osm, emit, mrds,
                geomagnetic, start, end);
        return new EarthSiteResult(lat, lon, elevation, geology, soil, power, weather, air, gbif, osm, emit, mrds,
                geomagnetic, t, sources, data, start, end);
    }

    static BatchStats runBatch(Path input, Path output) throws IOException {
        if (output.toAbsolutePath().normalize().equals(reportFile.toAbsolutePath().normalize())) {
            throw new IOException("Batch JSONL output and site-reports.json must be different files.");
        }
        Map<SiteKey, ObjectNode> savedReports = savedReportsByCoordinate("earth");
        BatchStats stats = processBatch(input, output, (lat, lon) -> {
            ObjectNode saved = savedReports.get(siteKey("earth", lat, lon));
            return saved == null ? batchSiteJson(collectEarthSite(lat, lon)) : saved.deepCopy();
        });
        try {
            int appended = appendBatchReports(output);
            if (appended > 0) System.err.printf(Locale.US, "Appended %d Earth reports to %s.%n", appended, reportFile);
        } catch (IOException e) {
            System.err.println("Batch JSONL was written, but reports were not appended to " + reportFile + ": " + e.getMessage());
        }
        return stats;
    }

    record ReportSchema(int version, boolean reports, boolean legacySites) {}
    record SiteKey(String body, double latitude, double longitude) {}
    @FunctionalInterface interface ReportRecordWriter { void write(JsonGenerator generator) throws IOException; }

    static int appendBatchReports(Path jsonl) throws IOException {
        synchronized (STORE_LOCK) {
            ObjectNode store = loadReportStore();
            if (store == null) throw new IOException("Cannot read the existing report store.");
            Set<SiteKey> known = reportKeys(store.path("reports"));
            List<ObjectNode> additions = new ArrayList<>();
            try (BufferedReader reader = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JsonNode row = JSON.readTree(line);
                    if (!isBatchReport(row)) continue;
                    SiteKey key = siteKey(row);
                    if (key != null && known.add(key) && row instanceof ObjectNode object) {
                        additions.add(object.deepCopy());
                    }
                }
            }
            if (additions.isEmpty()) return 0;
            return appendReportRecords(additions.size(), generator -> {
                for (ObjectNode row : additions) generator.writeTree(row);
            });
        }
    }

    static int appendReportRecords(int additions, ReportRecordWriter writeNewReports) throws IOException {
        synchronized (STORE_LOCK) {
            Path target = reportFile.toAbsolutePath().normalize();
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            ReportSchema schema = Files.exists(target) && Files.size(target) > 0
                    ? inspectReportSchema(target) : new ReportSchema(0, false, false);
            boolean keepExisting = schema.version == 3 && schema.reports;
            boolean resetLegacy = schema.version < 3 && schema.legacySites;
            if (!keepExisting && !resetLegacy && (schema.version != 0 || schema.reports || schema.legacySites)) {
                throw new IOException("Existing report store is invalid or uses an unsupported schema.");
            }
            Path temporary = Files.createTempFile(parent, "site-reports-", ".tmp");
            try {
                try (JsonGenerator generator = JSON.getFactory().createGenerator(Files.newBufferedWriter(temporary, StandardCharsets.UTF_8))) {
                    generator.setPrettyPrinter(reportPrettyPrinter());
                    if (keepExisting) {
                        copyExistingReportsAndAppend(target, generator, writeNewReports);
                    } else {
                        generator.writeStartObject();
                        generator.writeNumberField("v", 3);
                        generator.writeArrayFieldStart("reports");
                        writeNewReports.write(generator);
                        generator.writeEndArray();
                        generator.writeEndObject();
                    }
                }
                try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
                if (resetLegacy) System.err.println("Resetting legacy site reports; prior entries used merged records without reliable per-run metadata.");
                return additions;
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    static int countBatchReports(Path jsonl) throws IOException {
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode row = JSON.readTree(line);
                if (isBatchReport(row)) count++;
            }
        }
        return count;
    }

    static boolean isBatchReport(JsonNode row) {
        return row != null && row.isObject() && "earth".equals(row.path("body").asText())
                && row.path("data").isObject();
    }

    static void writeBatchReports(Path jsonl, JsonGenerator generator) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode row = JSON.readTree(line);
                if (isBatchReport(row)) generator.writeTree(row);
            }
        }
    }

    static ReportSchema inspectReportSchema(Path path) throws IOException {
        int version = 0;
        boolean reports = false, legacySites = false;
        try (JsonParser parser = JSON.getFactory().createParser(path.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IOException("Report store root is not an object.");
            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                if (token != JsonToken.FIELD_NAME) throw new IOException("Malformed report store.");
                String name = parser.currentName();
                token = parser.nextToken();
                if (token == null) throw new IOException("Malformed report store.");
                if ("v".equals(name)) {
                    if (!token.isNumeric()) throw new IOException("Invalid report-store version.");
                    version = parser.getIntValue();
                } else if ("reports".equals(name)) {
                    if (token != JsonToken.START_ARRAY) throw new IOException("Report list is not an array.");
                    reports = true;
                    parser.skipChildren();
                } else if ("sites".equals(name)) {
                    if (token != JsonToken.START_ARRAY) throw new IOException("Legacy site list is not an array.");
                    legacySites = true;
                    parser.skipChildren();
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) throw new IOException("Unexpected trailing content in report store.");
        } catch (JsonProcessingException e) {
            throw new IOException("Existing report store contains invalid JSON.", e);
        }
        return new ReportSchema(version, reports, legacySites);
    }

    static void copyExistingReportsAndAppend(Path existing, JsonGenerator generator, ReportRecordWriter writeNewReports) throws IOException {
        try (JsonParser parser = JSON.getFactory().createParser(existing.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IOException("Report store root is not an object.");
            generator.writeStartObject();
            JsonToken token;
            boolean wroteReports = false;
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                if (token != JsonToken.FIELD_NAME) throw new IOException("Malformed report store.");
                String name = parser.currentName();
                token = parser.nextToken();
                if ("reports".equals(name)) {
                    if (token != JsonToken.START_ARRAY) throw new IOException("Report list is not an array.");
                    generator.writeArrayFieldStart("reports");
                    while (parser.nextToken() != JsonToken.END_ARRAY) generator.copyCurrentStructure(parser);
                    writeNewReports.write(generator);
                    generator.writeEndArray();
                    wroteReports = true;
                } else if ("v".equals(name)) {
                    parser.skipChildren();
                    generator.writeNumberField("v", 3);
                } else {
                    generator.writeFieldName(name);
                    generator.copyCurrentStructure(parser);
                }
            }
            if (!wroteReports) throw new IOException("Version 3 report store has no reports array.");
            generator.writeEndObject();
        }
    }

    static BatchStats processBatch(Path input, Path output, BatchSiteProcessor processor) throws IOException {
        Path in = input.toAbsolutePath().normalize(), out = output.toAbsolutePath().normalize();
        if (in.equals(out)) throw new IOException("Input and output paths must be different.");
        if (!Files.isRegularFile(in)) throw new IOException("Coordinate input is not a readable file: " + input);
        if (Files.exists(out)) throw new FileAlreadyExistsException(out.toString());
        Path parent = out.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "date-deletor-batch-", ".tmp");
        int attempted = 0, succeeded = 0, invalid = 0;
        Set<SiteKey> seenCoordinates = new HashSet<>();
        try {
            try (BufferedReader reader = Files.newBufferedReader(in, StandardCharsets.UTF_8);
                 BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                String line;
                int lineNumber = 0, latitudeColumn = 0, longitudeColumn = 1;
                boolean firstRecord = true;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
                    List<String> fields;
                    try {
                        fields = parseDelimitedLine(line);
                    } catch (IllegalArgumentException e) {
                        attempted++;
                        invalid++;
                        writeBatchError(writer, lineNumber, e.getMessage());
                        continue;
                    }
                    if (firstRecord) {
                        firstRecord = false;
                        int[] header = coordinateHeader(fields);
                        if (header != null) {
                            latitudeColumn = header[0];
                            longitudeColumn = header[1];
                            continue;
                        }
                    }
                    attempted++;
                    double[] coordinates;
                    try {
                        coordinates = parseCoordinateFields(fields, latitudeColumn, longitudeColumn);
                        if (!validCoordinate(coordinates[0], coordinates[1])) {
                            throw new IllegalArgumentException("Latitude/longitude outside Earth coordinate limits.");
                        }
                    } catch (IllegalArgumentException e) {
                        invalid++;
                        writeBatchError(writer, lineNumber, e.getMessage());
                        continue;
                    }
                    if (!seenCoordinates.add(siteKey("earth", coordinates[0], coordinates[1]))) continue;
                    ObjectNode result = processor.process(coordinates[0], coordinates[1]);
                    writer.write(JSON.writeValueAsString(result));
                    writer.newLine();
                    succeeded++;
                    if (attempted % 100 == 0) {
                        System.err.printf(Locale.US, "Processed %d coordinate rows (%d written, %d invalid).%n",
                                attempted, succeeded, invalid);
                    }
                }
            }
            Files.move(temporary, out);
            return new BatchStats(attempted, succeeded, invalid);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static ObjectNode batchSiteJson(EarthSiteResult result) {
        ObjectNode row = JSON.createObjectNode();
        row.put("body", "earth");
        row.put("id", UUID.randomUUID().toString());
        row.put("generated_at_utc", Instant.now().toString());
        row.put("latitude", result.latitude());
        row.put("longitude", result.longitude());
        ObjectNode coordinate = row.putObject("coordinate");
        coordinate.put("latitude_deg", result.latitude());
        coordinate.put("longitude_deg", result.longitude());
        coordinate.put("longitude_convention", "signed_minus_180_to_180");
        String title = String.format(Locale.US, "EARTH SITE REPORT — %.5f, %.5f",
                result.latitude(), result.longitude());
        String summary = "Best-effort public data. / means unavailable or not supportable at point scale; it does not mean absent.";
        ArrayNode context = earthReportContext(result.latitude(), result.longitude(), result.terrain(),
                result.weather(), result.air(), result.gbif(), result.osm(), result.emit(), result.mrds(),
                result.power());
        row.set("report_output", readableReportOutput(title, summary, result.data().path("metrics"),
                context, result.sources(), null));
        row.set("data", result.data());
        ArrayNode statuses = row.putArray("sources");
        for (Source source : result.sources()) {
            ObjectNode status = statuses.addObject();
            status.put("name", source.name);
            status.put("state", source.state.name());
            status.put("status", source.status);
        }
        return row;
    }

    static void writeBatchError(BufferedWriter writer, int lineNumber, String message) throws IOException {
        ObjectNode error = JSON.createObjectNode();
        error.put("inputLine", lineNumber);
        error.put("error", message == null || message.isBlank() ? "Invalid coordinate row." : message);
        writer.write(JSON.writeValueAsString(error));
        writer.newLine();
    }

    static List<String> parseDelimitedLine(String line) {
        char delimiter = detectDelimiter(line);
        if (delimiter == '\0') {
            return Arrays.asList(line.trim().split("\\s+"));
        }
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == delimiter && !quoted) {
                fields.add(field.toString().trim());
                field.setLength(0);
            } else {
                field.append(ch);
            }
        }
        if (quoted) throw new IllegalArgumentException("Unclosed quoted field.");
        fields.add(field.toString().trim());
        if (!fields.isEmpty() && fields.get(0).startsWith("\uFEFF")) {
            fields.set(0, fields.get(0).substring(1));
        }
        return fields;
    }

    static char detectDelimiter(String line) {
        int commas = 0, semicolons = 0, tabs = 0;
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') i++;
                else quoted = !quoted;
            } else if (!quoted) {
                if (ch == ',') commas++;
                else if (ch == ';') semicolons++;
                else if (ch == '\t') tabs++;
            }
        }
        if (tabs > 0) return '\t';
        if (semicolons > commas) return ';';
        return commas > 0 ? ',' : semicolons > 0 ? ';' : '\0';
    }

    static int[] coordinateHeader(List<String> fields) {
        int lat = -1, lon = -1;
        for (int i = 0; i < fields.size(); i++) {
            String name = fields.get(i).trim().toLowerCase(Locale.ROOT);
            if (Set.of("lat", "latitude").contains(name)) lat = i;
            if (Set.of("lon", "lng", "long", "longitude").contains(name)) lon = i;
        }
        return lat >= 0 && lon >= 0 ? new int[]{lat, lon} : null;
    }

    static double[] parseCoordinateFields(List<String> fields, int latitudeColumn, int longitudeColumn) {
        if (latitudeColumn >= fields.size() || longitudeColumn >= fields.size()) {
            throw new IllegalArgumentException("Row does not contain latitude and longitude columns.");
        }
        try {
            double lat = Double.parseDouble(fields.get(latitudeColumn));
            double lon = Double.parseDouble(fields.get(longitudeColumn));
            if (!Double.isFinite(lat) || !Double.isFinite(lon)) throw new NumberFormatException();
            return new double[]{lat, lon};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Latitude and longitude must be finite numbers.");
        }
    }

    @FunctionalInterface
    interface BatchSiteProcessor { ObjectNode process(double lat, double lon); }

    record BatchStats(int attempted, int succeeded, int invalid) {}

    record EarthSiteResult(double latitude, double longitude, Source elevation, Source geology, Source soil, Source power, Source weather, Source air,
                           Source gbif, Source osm, Source emit, Source mrds, Source geomagnetic, Terrain terrain,
                           List<Source> sources, ObjectNode data, LocalDate start, LocalDate end) {}
    record LunarTerrain(int count, int localCount, double searchHalfWidthDeg, double nearestElevation,
                        double nearestDistance, double relief, double slope, double aspect, double roughness) {
        static LunarTerrain none() {
            return new LunarTerrain(0, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN);
        }
    }
    record DivinerChannelStats(int channel, int count, double minKelvin, double maxKelvin, double meanKelvin,
                               int qcaQmiZeroCount, double qcaQmiZeroMinKelvin, double qcaQmiZeroMaxKelvin,
                               double qcaQmiZeroMeanKelvin, int daytimeCount, double daytimeMeanKelvin,
                               int nighttimeCount, double nighttimeMeanKelvin, double dayNightContrastKelvin) {}
    record DivinerStats(int recordCount, int validTemperatureCount, int calibrationBestCount,
                        int geometryBestCount, int miscellaneousBestCount, int allFlagsBestCount,
                        String qcaFlagCounts, String qgeFlagCounts, String qmiFlagCounts,
                        String firstDate, String lastDate,
                        List<DivinerChannelStats> channels) {
        static DivinerStats none() {
            return new DivinerStats(0, 0, 0, 0, 0, 0, "", "", "", "", "", List.of());
        }
    }
    record LunarEphemerisSample(LocalDateTime time, double azimuthDeg, double elevationDeg,
                                double solarDistanceAu) {}
    record LunarRasterCell(int row, int column, long byteOffset) {}
    record LunarDemCell(int row, int column) {}
    record PdsTextMap(String text, boolean cached) {}
    record LunarMagneticGrid(double[][] magnitude, double[][] radial, double[][] east, double[][] north,
                             boolean cached) {}
    record LunarCompositionPixel(int index, double minLatitude, double maxLatitude,
                                 double minLongitude, double maxLongitude, double[] estimates,
                                 double[] standardDeviations) {}
    record LunarCompositionGrid(List<LunarCompositionPixel> pixels, boolean cached) {}

    /** PDS ODE supplies coverage; local GDS queries and raster samples provide site measurements. */
    public static void moonReport() { moonReport(LAT, LON); }

    public static void moonReport(double lat, double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90) {
            System.err.println("For Moon mode, LAT must be [-90,90] and LON must be finite."); return;
        }
        if (printSavedReportIfPresent("moon", lat, lon)) return;
        // ODE product queries require ihid + iid + pt together. Global/catalog products omit the
        // lat/lon box so one cache entry serves every coordinate; footprint products keep the box.
        CompletableFuture<Source> lolaF = asyncMoon("LRO LOLA archive coverage", "LRO", "LOLA", "GDRDEM", lat, lon, false);
        CompletableFuture<Source> divinerF = asyncMoon("LRO Diviner archive coverage", "LRO", "DLRE", "GCP", lat, lon, false);
        CompletableFuture<Source> lrocF = asyncMoon("LRO LROC imagery", "LRO", "LROC", "CDRNAC4", lat, lon, true);
        CompletableFuture<Source> lendF = asyncMoon("LRO LEND archive coverage", "LRO", "LEND", "RDRDLD4", lat, lon, false);
        CompletableFuture<Source> lampF = asyncMoon("LRO LAMP archive coverage", "LRO", "LAMP", "GDR", lat, lon, false);
        CompletableFuture<Source> minirfF = asyncMoon("LRO Mini-RF radar", "LRO", "MRFLRO", "MAPCDR", lat, lon, true);
        CompletableFuture<Source> m3F = asyncMoon("Chandrayaan-1 M³ mineralogy", "CH1-ORB", "M3", "REFIMG", lat, lon, true);
        CompletableFuture<Source> grailF = asyncMoon("GRAIL archive coverage", "GRAIL", "LGRS", "SHADR", lat, lon, false);
        CompletableFuture<Source> grailSamplesF = CompletableFuture.supplyAsync(
                () -> lunarGrailGridData(lat, lon), FETCH_POOL);
        CompletableFuture<Source> lolaDemF = CompletableFuture.supplyAsync(
                () -> lunarLdemNeighborhoodData(lat, lon), FETCH_POOL);
        CompletableFuture<Source> lolaShotsF = CompletableFuture.supplyAsync(() -> lunarGdsData("LOLA local topography", "lolardr", lat, lon), FETCH_POOL);
        CompletableFuture<Source> divinerShotsF = CompletableFuture.supplyAsync(() -> lunarGdsData("Diviner local observations", "divinerrdr", lat, lon), FETCH_POOL);
        CompletableFuture<Source> horizonsF = asyncGet("JPL Horizons lunar illumination", lunarHorizonsUrl(lat, lon));
        CompletableFuture<Source> craterCatalogF = CompletableFuture.supplyAsync(
                () -> get("USGS IAU lunar named-crater catalog", LUNAR_NAMED_CRATER_URL,
                        Duration.ofSeconds(60), 2), FETCH_POOL);
        CompletableFuture<Source> geologyF = CompletableFuture.supplyAsync(
                () -> lunarGeologyData(lat, lon), FETCH_POOL);
        CompletableFuture<Source> magneticF = CompletableFuture.supplyAsync(
                () -> lunarMagneticSample(lat, lon), FETCH_POOL);
        CompletableFuture<Source> compositionF = CompletableFuture.supplyAsync(
                () -> lunarCompositionSample(lat, lon), FETCH_POOL);
        Source lola = lolaF.join(), diviner = divinerF.join(), lroc = lrocF.join(), lend = lendF.join();
        Source lamp = lampF.join(), minirf = minirfF.join(), m3 = m3F.join(), grail = grailF.join();
        Source grailSamples = grailSamplesF.join();
        Source lolaDem = lolaDemF.join();
        Source lolaShots = lolaShotsF.join(), divinerShots = divinerShotsF.join(), horizons = horizonsF.join();
        Source craterCatalog = craterCatalogF.join(), lunarGeology = geologyF.join();
        Source magnetic = magneticF.join(), composition = compositionF.join();
        List<Source> sources = List.of(lola, diviner, lroc, lend, lamp, minirf, m3, grail, grailSamples, lolaDem, lolaShots, divinerShots,
                horizons, craterCatalog, lunarGeology, magnetic, composition);

        double eastLon = ((lon % 360) + 360) % 360;
        ObjectNode data = moonData(lola, diviner, lroc, lend, lamp, minirf, m3, grail,
                lolaShots, divinerShots, horizons, craterCatalog, lunarGeology,
                grailSamples, lolaDem, magnetic, composition, lat, lon);
        String title = String.format(Locale.US, "MOON SITE REPORT — %.5f°, %.5f°E", lat, eastLon);
        String summary = "Best-effort lunar data. / means unavailable or not supportable at point scale.";
        String recommendation = "Next, rank Earth candidates using scale-matched terrain and landform first, then surface materials, thermal/solar conditions, resources, and impact setting. Weight by data quality and return several candidates with per-factor differences and uncertainty; this report does not yet select an Earth coordinate.";
        ArrayNode context = lunarReportContext(lola, lroc, minirf, m3, lend, lamp, diviner, grailSamples,
                magnetic, composition, lolaDem, lunarGeology, craterCatalog);
        ObjectNode readableOutput = readableReportOutput(title, summary, data.path("metrics"), context,
                sources, recommendation);
        System.out.println(title);
        System.out.println(summary);
        printMetricGroups(data.path("metrics"));
        printReportContext("Additional lunar context", context);
        printSourceStatuses(sources);
        persistReport("moon", lat, eastLon, data, readableOutput);
        line("Earth analog matching recommendation", recommendation);
    }

    static String lunarGdsUrl(String query, double lat, double lon) {
        return lunarGdsUrl(query, lat, lon, .005);
    }

    static String lunarGdsUrl(String query, double lat, double lon, double halfWidthDeg) {
        if (!Double.isFinite(halfWidthDeg) || halfWidthDeg <= 0 || halfWidthDeg > 1) {
            throw new IllegalArgumentException("Lunar GDS search half-width must be in (0, 1] degrees.");
        }
        double eastLon = ((lon % 360) + 360) % 360;
        double west = eastLon - halfWidthDeg, east = eastLon + halfWidthDeg;
        if (west < 0) west += 360;
        if (east >= 360) east -= 360;
        StringBuilder url = new StringBuilder("https://oderest.rsl.wustl.edu/livegds/?query=")
                .append(query).append("&results=csv")
                .append("&minlat=").append(String.format(Locale.US, "%.5f", Math.max(-90, lat - halfWidthDeg)))
                .append("&maxlat=").append(String.format(Locale.US, "%.5f", Math.min(90, lat + halfWidthDeg)))
                .append("&westernlon=").append(String.format(Locale.US, "%.5f", west))
                .append("&easternlon=").append(String.format(Locale.US, "%.5f", east));
        if ("divinerrdr".equals(query)) url.append("&channel=ffffftttt");
        return url.toString();
    }

    static Source lunarGeologyData(double lat, double lon) {
        String name = "USGS unified lunar geology";
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > LUNAR_GEOLOGY_MAX_LAT) {
            return new Source(name, null, SourceState.EMPTY,
                    "outside the published Mercator layer extent (about ±57° latitude)");
        }
        return get(name, lunarGeologyUrl(lat, lon), Duration.ofSeconds(30), 2);
    }

    static String lunarGeologyUrl(double lat, double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) >= 90) {
            throw new IllegalArgumentException("Lunar geology coordinates must be finite and within the map projection.");
        }
        double[] point = lunarMapPoint(lat, lon);
        double x = point[0], y = point[1];
        String geometry = String.format(Locale.US, "%.3f,%.3f", x, y);
        return LUNAR_GEOLOGY_QUERY_URL + "?geometry=" + encode(geometry)
                + "&geometryType=esriGeometryPoint&spatialRel=esriSpatialRelIntersects"
                + "&outFields=FIRST_UnitSymbol%2CFIRST_UnitAge%2CFIRST_UnitName%2CDescription%2CInterpretation"
                + "&returnGeometry=true&maxAllowableOffset=3000&f=json";
    }

    static double[] lunarMapPoint(double lat, double lon) {
        double eastLon = ((lon % 360) + 360) % 360;
        if (eastLon > 180) eastLon -= 360;
        double latitudeRadians = Math.toRadians(lat);
        return new double[]{
                LUNAR_MAP_RADIUS_M * Math.toRadians(eastLon),
                LUNAR_MAP_RADIUS_M * Math.log(Math.tan(Math.PI / 4 + latitudeRadians / 2))
        };
    }

    static ObjectNode lunarGeologySample(Source source, double lat, double lon) {
        ObjectNode result = JSON.createObjectNode();
        if (source == null || source.state != SourceState.DATA || source.json == null
                || !Double.isFinite(lat) || !Double.isFinite(lon)) return result;
        JsonNode features = source.json.path("features");
        if (!features.isArray() || features.isEmpty()) return result;
        JsonNode feature = features.get(0);
        JsonNode attributes = feature.path("attributes");
        ObjectNode unit = result.putObject("mapped_unit");
        copyText(attributes, unit, "FIRST_UnitSymbol", "symbol");
        copyText(attributes, unit, "FIRST_UnitAge", "age");
        copyText(attributes, unit, "FIRST_UnitName", "name");
        copyText(attributes, unit, "Description", "description");
        copyText(attributes, unit, "Interpretation", "interpretation");
        double[] point = lunarMapPoint(lat, lon);
        double distance = lunarMappedBoundaryDistanceMeters(feature.path("geometry"), point[0], point[1], lat);
        if (Double.isFinite(distance)) unit.put("boundary_distance_km", Math.round(distance / 1_000));
        return result;
    }

    static void copyText(JsonNode from, ObjectNode to, String sourceKey, String targetKey) {
        JsonNode value = from == null ? null : from.path(sourceKey);
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            to.put(targetKey, value.asText());
        }
    }

    static double lunarMappedBoundaryDistanceMeters(JsonNode geometry, double x, double y, double latitude) {
        JsonNode rings = geometry == null ? null : geometry.path("rings");
        if (rings == null || !rings.isArray()) return Double.NaN;
        double scale = Math.cos(Math.toRadians(latitude));
        double nearest = Double.POSITIVE_INFINITY;
        for (JsonNode ring : rings) {
            if (!ring.isArray() || ring.size() < 2) continue;
            for (int i = 0; i < ring.size(); i++) {
                JsonNode start = ring.get(i), end = ring.get((i + 1) % ring.size());
                if (!start.isArray() || start.size() < 2 || !end.isArray() || end.size() < 2
                        || !start.get(0).isNumber() || !start.get(1).isNumber()
                        || !end.get(0).isNumber() || !end.get(1).isNumber()) continue;
                double x1 = start.get(0).asDouble(), y1 = start.get(1).asDouble();
                double dx = end.get(0).asDouble() - x1, dy = end.get(1).asDouble() - y1;
                double lengthSquared = dx * dx + dy * dy;
                double fraction = lengthSquared == 0 ? 0
                        : Math.max(0, Math.min(1, ((x - x1) * dx + (y - y1) * dy) / lengthSquared));
                double distance = Math.hypot((x - (x1 + fraction * dx)) * scale,
                        (y - (y1 + fraction * dy)) * scale);
                nearest = Math.min(nearest, distance);
            }
        }
        return Double.isFinite(nearest) ? nearest : Double.NaN;
    }

    static String lunarHorizonsUrl(double lat, double lon) {
        LocalDateTime start = LocalDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0);
        String startTime = start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US));
        long cycleSeconds = Math.round(29.530589 * 86_400);
        String stopTime = start.plusSeconds(cycleSeconds).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US));
        return "https://ssd.jpl.nasa.gov/api/horizons.api?format=json"
                + "&COMMAND=" + encode("'10'")
                + "&CENTER=" + encode("'coord@301'")
                + "&COORD_TYPE=" + encode("'GEODETIC'")
                + "&SITE_COORD=" + encode("'" + String.format(Locale.US, "%.6f,%.6f,0", ((lon % 360) + 360) % 360, lat) + "'")
                + "&START_TIME=" + encode("'" + startTime + "'")
                + "&STOP_TIME=" + encode("'" + stopTime + "'")
                + "&STEP_SIZE=" + encode("'6 h'")
                + "&QUANTITIES=" + encode("'4,20'");
    }

    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    static Source lunarGdsData(String name, String query, double lat, double lon) {
        double halfWidthDeg = .005;
        Source result = get(name + " query", lunarGdsUrl(query, lat, lon, halfWidthDeg), Duration.ofSeconds(60), 2);
        if ("lolardr".equals(query) && result.state == SourceState.EMPTY) {
            halfWidthDeg = .05;
            result = get(name + " expanded query", lunarGdsUrl(query, lat, lon, halfWidthDeg), Duration.ofSeconds(60), 2);
        }
        if (result.state != SourceState.DATA || result.json == null) return result;
        JsonNode links = result.json.path("resultFiles");
        String wanted = "divinerrdr".equals(query) ? ".tab" : "topo_simple_csv.csv";
        for (JsonNode link : links) {
            String url = link.asText();
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.contains(wanted) && (!"divinerrdr".equals(query) || lower.contains("divinerrdr"))) {
                Source table = get(name, url, Duration.ofSeconds(60), 2);
                if (table.state != SourceState.DATA || table.json == null) return table;
                if (result.json.path("count").canConvertToInt() && table.json instanceof ObjectNode data) {
                    data.put("query_count", result.json.path("count").asInt());
                    data.put("query_halfwidth_deg", halfWidthDeg);
                }
                return table;
            }
        }
        return new Source(name, result.json, SourceState.API_ERROR, "GDS response did not include expected data file");
    }

    public static String odeUrl(String mission, String instrument, String productType, double lat, double lon, String results) {
        return odeUrl(mission, instrument, productType, lat, lon, results, true);
    }

    public static String odeUrl(String mission, String instrument, String productType, double lat, double lon, String results, boolean includeLocation) {
        double eastLon = ((lon % 360) + 360) % 360, delta = .05;
        StringBuilder sb = new StringBuilder("https://oderest.rsl.wustl.edu/live2/?query=product");
        sb.append("&results=").append(results == null || results.isBlank() ? "m" : results);
        sb.append("&output=JSON&limit=5&target=moon");
        if (mission != null && !mission.isBlank()) sb.append("&ihid=").append(mission);
        if (instrument != null && !instrument.isBlank()) sb.append("&iid=").append(instrument);
        if (productType != null && !productType.isBlank()) sb.append("&pt=").append(productType);
        if (includeLocation) {
            sb.append("&minlat=").append(String.format(Locale.US, "%.5f", Math.max(-90, lat - delta)));
            sb.append("&maxlat=").append(String.format(Locale.US, "%.5f", Math.min(90, lat + delta)));
            sb.append("&westernlon=").append(String.format(Locale.US, "%.5f", eastLon - delta));
            sb.append("&easternlon=").append(String.format(Locale.US, "%.5f", eastLon + delta));
        }
        return sb.toString();
    }

    static CompletableFuture<Source> asyncMoon(String name, String mission, String instrument, String productType, double lat, double lon, boolean preferLocation) {
        return CompletableFuture.supplyAsync(() -> moonProduct(name, mission, instrument, productType, lat, lon, preferLocation), FETCH_POOL);
    }

    static Source moonProduct(String name, String mission, String instrument, String productType, double lat, double lon, List<Source> sources) {
        Source s = moonProduct(name, mission, instrument, productType, lat, lon, true);
        if (sources != null) sources.add(s);
        return s;
    }

    static Source moonProduct(String name, String mission, String instrument, String productType, double lat, double lon, boolean preferLocation) {
        if (preferLocation) {
            Source local = get(name, odeUrl(mission, instrument, productType, lat, lon, "m", true));
            if (local.state == SourceState.DATA || local.state == SourceState.EMPTY) return local;
            Source catalog = get(name + " archive coverage", odeUrl(mission, instrument, productType, lat, lon, "m", false));
            if (catalog.state == SourceState.DATA || catalog.state == SourceState.EMPTY) return catalog;
            return local;
        }
        return get(name, odeUrl(mission, instrument, productType, lat, lon, "m", false));
    }

    static LunarRasterCell lunarGrailRasterCell(double lat, double lon) {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90 || !Double.isFinite(lon)) {
            throw new IllegalArgumentException("Invalid lunar gravity-grid coordinate.");
        }
        double eastLon = ((lon % 360) + 360) % 360;
        int column = Math.min(LUNAR_GRAIL_GRID_COLUMNS - 1,
                (int) Math.floor(eastLon * LUNAR_GRAIL_GRID_PIXELS_PER_DEGREE));
        int row = Math.min(LUNAR_GRAIL_GRID_ROWS - 1,
                Math.max(0, (int) Math.floor((90 - lat) * LUNAR_GRAIL_GRID_PIXELS_PER_DEGREE)));
        long offset = (long) row * LUNAR_GRAIL_GRID_RECORD_BYTES + (long) column * Float.BYTES;
        return new LunarRasterCell(row, column, offset);
    }

    static Source lunarGrailGridData(double lat, double lon) {
        String name = "GRAIL local grid samples";
        LunarRasterCell cell;
        try {
            cell = lunarGrailRasterCell(lat, lon);
        } catch (IllegalArgumentException e) {
            return new Source(name, null, SourceState.API_ERROR, e.getMessage());
        }
        String cacheKey = LUNAR_GRAIL_ANOMALY_RASTER + "|" + LUNAR_GRAIL_BOUGUER_RASTER
                + "|row=" + cell.row + "|column=" + cell.column;
        Source cached = readCache(name, cacheKey);
        if (cached != null) return cached;
        try {
            float anomaly = lunarPdsRasterFloat(LUNAR_GRAIL_ANOMALY_RASTER, cell.byteOffset);
            float bouguer = lunarPdsRasterFloat(LUNAR_GRAIL_BOUGUER_RASTER, cell.byteOffset);
            if (!Float.isFinite(anomaly) || !Float.isFinite(bouguer)) {
                throw new IOException("non-finite value in GRAIL raster cell");
            }
            ObjectNode values = JSON.createObjectNode();
            values.put("anomaly_mgal", anomaly);
            values.put("bouguer_disturbance_mgal", bouguer);
            values.put("grid_row", cell.row);
            values.put("grid_column", cell.column);
            writeCache(cacheKey, JSON.writeValueAsString(values));
            return new Source(name, values, SourceState.DATA, "ok (two PDS byte-range samples; nearest grid cell)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Source failed = new Source(name, null, SourceState.NETWORK_ERROR, "interrupted");
            writeFailureCache(cacheKey, failed);
            return failed;
        } catch (IOException | RuntimeException e) {
            String status = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            SourceState state = status.startsWith("HTTP ") || status.startsWith("invalid ")
                    || status.startsWith("non-finite") ? SourceState.API_ERROR : SourceState.NETWORK_ERROR;
            Source failed = new Source(name, null, state, status);
            writeFailureCache(cacheKey, failed);
            return failed;
        }
    }

    static Source lunarMagneticSample(double lat, double lon) {
        String name = "LP/Kaguya crustal magnetic grid";
        if (!Double.isFinite(lat) || lat < -90 || lat > 90 || !Double.isFinite(lon)) {
            return new Source(name, null, SourceState.API_ERROR, "invalid lunar coordinate");
        }
        if (lat < -65 || lat > 65) {
            return new Source(name, null, SourceState.EMPTY, "PDS grid coverage is limited to 65°S–65°N");
        }
        try {
            LunarMagneticGrid grid = loadLunarMagneticGrid();
            ObjectNode values = sampleLunarMagneticGrid(grid, lat, lon);
            if (values == null) {
                return new Source(name, null, SourceState.EMPTY, "no reliable vector data in the surrounding map cells");
            }
            values.put("source_url", LUNAR_MAGNETIC_MAP_URL);
            values.put("product", "LP/Kaguya lunar crustal magnetic field map");
            values.put("altitude_km", 30);
            values.put("grid_spacing_latitude_deg", 0.5);
            values.put("grid_spacing_longitude_deg", 2d / 3);
            values.put("effective_resolution_latitude_deg", 5d / 3);
            values.put("effective_resolution_longitude_deg", 10d / 3);
            values.put("interpolation", "bilinear interpolation of local ENR field components");
            return new Source(name, values, SourceState.DATA, grid.cached()
                    ? "ok (local PDS static map cache; bilinear sample for this coordinate)"
                    : "ok (PDS static map downloaded locally; bilinear sample for this coordinate)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Source(name, null, SourceState.NETWORK_ERROR, "interrupted");
        } catch (IOException | RuntimeException e) {
            String status = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            SourceState state = status.startsWith("HTTP ") || status.startsWith("invalid ")
                    ? SourceState.API_ERROR : SourceState.NETWORK_ERROR;
            return new Source(name, null, state, status);
        }
    }

    static Source lunarCompositionSample(double lat, double lon) {
        String name = "Lunar Prospector GRS elemental abundance map";
        if (!Double.isFinite(lat) || lat < -90 || lat > 90 || !Double.isFinite(lon)) {
            return new Source(name, null, SourceState.API_ERROR, "invalid lunar coordinate");
        }
        try {
            LunarCompositionGrid grid = loadLunarCompositionGrid();
            ObjectNode values = sampleLunarCompositionGrid(grid, lat, lon);
            if (values == null) {
                return new Source(name, null, SourceState.EMPTY, "no abundance pixel contains this coordinate");
            }
            values.put("source_url", LUNAR_GRS_COMPOSITION_MAP_URL);
            values.put("product", "LP GRS high-altitude 1 elemental abundance, 2° equal-area bins");
            values.put("observation_altitude_km", 100);
            values.put("effective_instrument_resolution_deg", 5);
            values.put("interpolation", "value from the containing equal-area pixel; no interpolation");
            return new Source(name, values, SourceState.DATA, grid.cached()
                    ? "ok (local PDS abundance map cache; containing pixel sampled for this coordinate)"
                    : "ok (PDS abundance map downloaded locally; containing pixel sampled)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Source(name, null, SourceState.NETWORK_ERROR, "interrupted");
        } catch (IOException | RuntimeException e) {
            String status = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            SourceState state = status.startsWith("HTTP ") || status.startsWith("invalid ")
                    ? SourceState.API_ERROR : SourceState.NETWORK_ERROR;
            return new Source(name, null, state, status);
        }
    }

    static LunarMagneticGrid loadLunarMagneticGrid() throws IOException, InterruptedException {
        LunarMagneticGrid grid = cachedLunarMagneticGrid;
        if (grid != null) return grid;
        synchronized (LUNAR_STATIC_MAP_LOCK) {
            grid = cachedLunarMagneticGrid;
            if (grid == null) {
                PdsTextMap product = readPdsTextMap("lp-kaguya-crustal-magnetic-30km-v1.tab",
                        LUNAR_MAGNETIC_MAP_URL);
                grid = parseLunarMagneticGrid(product.text(), product.cached());
                cachedLunarMagneticGrid = grid;
            }
        }
        return grid;
    }

    static LunarCompositionGrid loadLunarCompositionGrid() throws IOException, InterruptedException {
        LunarCompositionGrid grid = cachedLunarCompositionGrid;
        if (grid != null) return grid;
        synchronized (LUNAR_STATIC_MAP_LOCK) {
            grid = cachedLunarCompositionGrid;
            if (grid == null) {
                PdsTextMap product = readPdsTextMap("lunar-prospector-grs-high1-2deg-v1.tab",
                        LUNAR_GRS_COMPOSITION_MAP_URL);
                grid = parseLunarCompositionGrid(product.text(), product.cached());
                cachedLunarCompositionGrid = grid;
            }
        }
        return grid;
    }

    static PdsTextMap readPdsTextMap(String fileName, String url) throws IOException, InterruptedException {
        Files.createDirectories(LUNAR_STATIC_MAP_CACHE_DIR);
        Path target = LUNAR_STATIC_MAP_CACHE_DIR.resolve(fileName);
        synchronized (LUNAR_STATIC_MAP_LOCK) {
            if (Files.isRegularFile(target) && Files.size(target) > 0) {
                return new PdsTextMap(Files.readString(target, StandardCharsets.UTF_8), true);
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90))
                    .header("Accept", "text/plain, application/octet-stream")
                    .header("User-Agent", "DateDeletor/1.0 coordinate report").GET().build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body.length == 0 || body.length > MAX_RESPONSE_BYTES) {
                throw new IOException("invalid PDS map response size: " + body.length);
            }
            Path temporary = Files.createTempFile(LUNAR_STATIC_MAP_CACHE_DIR, "pds-map-", ".tmp");
            try {
                Files.write(temporary, body);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target);
                } catch (FileAlreadyExistsException e) {
                    Files.deleteIfExists(temporary);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return new PdsTextMap(new String(body, StandardCharsets.UTF_8), false);
        }
    }

    static LunarMagneticGrid parseLunarMagneticGrid(String text, boolean cached) throws IOException {
        int latitudeCount = 261, longitudeCount = 540;
        double[][] magnitude = new double[latitudeCount][longitudeCount];
        double[][] radial = new double[latitudeCount][longitudeCount];
        double[][] east = new double[latitudeCount][longitudeCount];
        double[][] north = new double[latitudeCount][longitudeCount];
        int count = 0;
        for (String line : text.split("\\R")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 6) continue;
            double longitude, latitude, fieldMagnitude, fieldRadial, fieldEast, fieldNorth;
            try {
                longitude = Double.parseDouble(fields[0]);
                latitude = Double.parseDouble(fields[1]);
                fieldMagnitude = Double.parseDouble(fields[2]);
                fieldRadial = Double.parseDouble(fields[3]);
                fieldEast = Double.parseDouble(fields[4]);
                fieldNorth = Double.parseDouble(fields[5]);
            } catch (NumberFormatException e) {
                continue;
            }
            int longitudeIndex = (int) Math.round(normalizeDegrees(longitude) * 1.5);
            int latitudeIndex = (int) Math.round((latitude + 65) * 2);
            if (longitudeIndex == longitudeCount) longitudeIndex = 0;
            if (longitudeIndex < 0 || longitudeIndex >= longitudeCount
                    || latitudeIndex < 0 || latitudeIndex >= latitudeCount
                    || Math.abs(normalizeDegrees(longitude) - longitudeIndex * (2d / 3)) > 0.002
                    || Math.abs(latitude - (-65 + latitudeIndex * 0.5)) > 0.002
                    || !Double.isFinite(fieldMagnitude) || !Double.isFinite(fieldRadial)
                    || !Double.isFinite(fieldEast) || !Double.isFinite(fieldNorth)) {
                throw new IOException("invalid PDS magnetic grid coordinates or values");
            }
            magnitude[latitudeIndex][longitudeIndex] = fieldMagnitude;
            radial[latitudeIndex][longitudeIndex] = fieldRadial;
            east[latitudeIndex][longitudeIndex] = fieldEast;
            north[latitudeIndex][longitudeIndex] = fieldNorth;
            count++;
        }
        if (count != latitudeCount * longitudeCount) {
            throw new IOException("invalid PDS magnetic grid row count: " + count);
        }
        return new LunarMagneticGrid(magnitude, radial, east, north, cached);
    }

    static ObjectNode sampleLunarMagneticGrid(LunarMagneticGrid grid, double lat, double lon) {
        if (grid == null || !Double.isFinite(lat) || lat < -65 || lat > 65 || !Double.isFinite(lon)) return null;
        int latitudeCount = grid.magnitude().length;
        int longitudeCount = grid.magnitude()[0].length;
        double latitudePosition = (lat + 65) * 2;
        int latitude0 = Math.min(latitudeCount - 1, (int) Math.floor(latitudePosition));
        int latitude1 = Math.min(latitudeCount - 1, latitude0 + 1);
        double latitudeFraction = latitudePosition - latitude0;
        double longitudePosition = normalizeDegrees(lon) * 1.5;
        int longitude0 = Math.floorMod((int) Math.floor(longitudePosition), longitudeCount);
        int longitude1 = (longitude0 + 1) % longitudeCount;
        double longitudeFraction = longitudePosition - Math.floor(longitudePosition);
        double[][] weights = {
                {(1 - latitudeFraction) * (1 - longitudeFraction), (1 - latitudeFraction) * longitudeFraction},
                {latitudeFraction * (1 - longitudeFraction), latitudeFraction * longitudeFraction}
        };
        int[][] latitudes = {{latitude0, latitude0}, {latitude1, latitude1}};
        int[][] longitudes = {{longitude0, longitude1}, {longitude0, longitude1}};
        double[][] magnitudeCells = new double[2][2];
        double[][] radialCells = new double[2][2];
        double[][] eastCells = new double[2][2];
        double[][] northCells = new double[2][2];
        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 2; column++) {
                double weight = weights[row][column];
                int y = latitudes[row][column], x = longitudes[row][column];
                double cellMagnitude = grid.magnitude()[y][x];
                double cellRadial = grid.radial()[y][x], cellEast = grid.east()[y][x], cellNorth = grid.north()[y][x];
                if (weight > 1e-12 && (!(cellMagnitude > 0) || !Double.isFinite(cellRadial)
                        || !Double.isFinite(cellEast) || !Double.isFinite(cellNorth))) return null;
                magnitudeCells[row][column] = cellMagnitude;
                radialCells[row][column] = cellRadial;
                eastCells[row][column] = cellEast;
                northCells[row][column] = cellNorth;
            }
        }
        double magnitude = bilinear(magnitudeCells[0][0], magnitudeCells[0][1],
                magnitudeCells[1][0], magnitudeCells[1][1], longitudeFraction, latitudeFraction);
        double radial = bilinear(radialCells[0][0], radialCells[0][1],
                radialCells[1][0], radialCells[1][1], longitudeFraction, latitudeFraction);
        double east = bilinear(eastCells[0][0], eastCells[0][1],
                eastCells[1][0], eastCells[1][1], longitudeFraction, latitudeFraction);
        double north = bilinear(northCells[0][0], northCells[0][1],
                northCells[1][0], northCells[1][1], longitudeFraction, latitudeFraction);
        ObjectNode values = JSON.createObjectNode();
        values.put("magnitude_nT", magnitude);
        values.put("radial_nT", radial);
        values.put("east_nT", east);
        values.put("north_nT", north);
        values.put("declination_deg", normalizeDegrees(Math.toDegrees(Math.atan2(east, north))));
        values.put("inclination_deg", Math.toDegrees(Math.atan2(-radial, Math.hypot(east, north))));
        return values;
    }

    static LunarCompositionGrid parseLunarCompositionGrid(String text, boolean cached) throws IOException {
        List<LunarCompositionPixel> pixels = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 61) continue;
            double[] values = new double[61];
            try {
                for (int i = 0; i < values.length; i++) values[i] = Double.parseDouble(fields[i]);
            } catch (NumberFormatException e) {
                continue;
            }
            double[] estimates = Arrays.copyOfRange(values, 5, 16);
            double[] standardDeviations = new double[9];
            for (int parameter = 0; parameter < standardDeviations.length; parameter++) {
                int diagonalColumn = 16 + 9 * parameter - parameter * (parameter - 1) / 2;
                standardDeviations[parameter] = Math.sqrt(Math.max(0, values[diagonalColumn]));
            }
            pixels.add(new LunarCompositionPixel((int) values[0], values[1], values[2],
                    values[3], values[4], estimates, standardDeviations));
        }
        if (pixels.size() != 11_306) {
            throw new IOException("invalid PDS GRS abundance row count: " + pixels.size());
        }
        return new LunarCompositionGrid(List.copyOf(pixels), cached);
    }

    static ObjectNode sampleLunarCompositionGrid(LunarCompositionGrid grid, double lat, double lon) {
        if (grid == null || !Double.isFinite(lat) || lat < -90 || lat > 90 || !Double.isFinite(lon)) return null;
        double normalizedLongitude = ((lon + 180) % 360 + 360) % 360 - 180;
        for (LunarCompositionPixel pixel : grid.pixels()) {
            boolean inLatitude = lat >= pixel.minLatitude() && (lat < pixel.maxLatitude()
                    || lat == 90 && pixel.maxLatitude() == 90);
            boolean inLongitude = normalizedLongitude >= pixel.minLongitude()
                    && (normalizedLongitude < pixel.maxLongitude()
                    || normalizedLongitude == 180 && pixel.maxLongitude() == 180);
            if (!inLatitude || !inLongitude) continue;
            double[] estimate = pixel.estimates(), error = pixel.standardDeviations();
            double feoWtPercent = estimate[7] * 100;
            double tio2WtPercent = estimate[6] * 100;
            ObjectNode values = JSON.createObjectNode();
            values.put("pixel_index", pixel.index());
            values.put("latitude_min_deg", pixel.minLatitude());
            values.put("latitude_max_deg", pixel.maxLatitude());
            values.put("longitude_min_east_deg", pixel.minLongitude());
            values.put("longitude_max_east_deg", pixel.maxLongitude());
            values.put("binning", "2° equal-area pixels");
            values.put("FeO_wt_percent", feoWtPercent);
            values.put("FeO_wt_percent_stddev", error[5] * 100);
            values.put("Fe_element_wt_percent_derived", feoWtPercent * (55.845 / (55.845 + 15.999)));
            values.put("TiO2_wt_percent", tio2WtPercent);
            values.put("TiO2_wt_percent_stddev", error[4] * 100);
            values.put("Ti_element_wt_percent_derived", tio2WtPercent * (47.867 / (47.867 + 2 * 15.999)));
            values.put("MgO_wt_percent", estimate[2] * 100);
            values.put("Al2O3_wt_percent", estimate[3] * 100);
            values.put("SiO2_wt_percent", estimate[4] * 100);
            values.put("CaO_wt_percent", estimate[5] * 100);
            values.put("K_ppm", estimate[8]);
            values.put("K_ppm_stddev", error[6]);
            values.put("Th_ppm", estimate[9]);
            values.put("Th_ppm_stddev", error[7]);
            values.put("U_ppm", estimate[10]);
            values.put("U_ppm_stddev", error[8]);
            return values;
        }
        return null;
    }

    static double bilinear(double southwest, double southeast, double northwest, double northeast,
                           double xFraction, double yFraction) {
        double south = southwest * (1 - xFraction) + southeast * xFraction;
        double north = northwest * (1 - xFraction) + northeast * xFraction;
        return south * (1 - yFraction) + north * yFraction;
    }

    static float lunarPdsRasterFloat(String url, long offset) throws IOException, InterruptedException {
        byte[] sample = lunarPdsRasterBytes(url, offset, Float.BYTES);
        return ByteBuffer.wrap(sample).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    static byte[] lunarPdsRasterBytes(String url, long offset, int length) throws IOException, InterruptedException {
        if (offset < 0 || length <= 0) throw new IllegalArgumentException("Invalid PDS raster byte range.");
        long end = offset + length - 1;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(DEFAULT_TIMEOUT)
                .header("Accept", "application/octet-stream")
                .header("Range", "bytes=" + offset + "-" + end)
                .header("User-Agent", "DateDeletor/1.0 coordinate report").GET().build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 206) throw new IOException("HTTP " + response.statusCode() + " (PDS byte range not honored)");
            String contentRange = response.headers().firstValue("Content-Range").orElse("");
            if (!contentRange.startsWith("bytes " + offset + "-" + end + "/")) {
                throw new IOException("invalid Content-Range for PDS raster sample");
            }
            byte[] sample = body.readNBytes(length);
            if (sample.length != length) throw new IOException("short PDS raster sample");
            return sample;
        }
    }

    static LunarDemCell lunarLdemCell(double lat, double lon) {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90 || !Double.isFinite(lon)) {
            throw new IllegalArgumentException("Invalid lunar LOLA DEM coordinate.");
        }
        double eastLon = ((lon % 360) + 360) % 360;
        int column = Math.min(LUNAR_LOLA_DEM_COLUMNS - 1,
                (int) Math.floor(eastLon * LUNAR_LOLA_DEM_PIXELS_PER_DEGREE));
        int row = Math.min(LUNAR_LOLA_DEM_ROWS - 1,
                Math.max(0, (int) Math.floor((90 - lat) * LUNAR_LOLA_DEM_PIXELS_PER_DEGREE)));
        return new LunarDemCell(row, column);
    }

    static Source lunarLdemNeighborhoodData(double lat, double lon) {
        String name = "LOLA LDEM 128 ppd neighborhood";
        LunarDemCell cell;
        try {
            cell = lunarLdemCell(lat, lon);
        } catch (IllegalArgumentException e) {
            return new Source(name, null, SourceState.API_ERROR, e.getMessage());
        }
        String cacheKey = LUNAR_LOLA_DEM_RASTER + "|5x5|row=" + cell.row + "|column=" + cell.column;
        Source cached = readCache(name, cacheKey);
        if (cached != null) return cached;
        try {
            ObjectNode values = JSON.createObjectNode();
            ArrayNode grid = values.putArray("elevations_m");
            for (int rowOffset = -2; rowOffset <= 2; rowOffset++) {
                int row = Math.max(0, Math.min(LUNAR_LOLA_DEM_ROWS - 1, cell.row + rowOffset));
                int firstColumn = Math.floorMod(cell.column - 2, LUNAR_LOLA_DEM_COLUMNS);
                int firstCount = Math.min(5, LUNAR_LOLA_DEM_COLUMNS - firstColumn);
                long offset = ((long) row * LUNAR_LOLA_DEM_COLUMNS + firstColumn) * Float.BYTES;
                byte[] firstPart = lunarPdsRasterBytes(LUNAR_LOLA_DEM_RASTER, offset, firstCount * Float.BYTES);
                byte[] rowBytes = new byte[5 * Float.BYTES];
                System.arraycopy(firstPart, 0, rowBytes, 0, firstPart.length);
                if (firstCount < 5) {
                    long wrappedOffset = (long) row * LUNAR_LOLA_DEM_COLUMNS * Float.BYTES;
                    byte[] wrappedPart = lunarPdsRasterBytes(LUNAR_LOLA_DEM_RASTER, wrappedOffset,
                            (5 - firstCount) * Float.BYTES);
                    System.arraycopy(wrappedPart, 0, rowBytes, firstPart.length, wrappedPart.length);
                }
                ByteBuffer samples = ByteBuffer.wrap(rowBytes).order(ByteOrder.LITTLE_ENDIAN);
                ArrayNode gridRow = grid.addArray();
                for (int column = 0; column < 5; column++) {
                    float heightKm = samples.getFloat();
                    if (!Float.isFinite(heightKm)) throw new IOException("non-finite LOLA DEM sample");
                    gridRow.add(heightKm * 1000.0);
                }
            }
            values.put("row", cell.row);
            values.put("column", cell.column);
            values.put("cell_center_latitude_deg",
                    90 - (cell.row + 0.5) / LUNAR_LOLA_DEM_PIXELS_PER_DEGREE);
            values.put("cell_center_longitude_east_deg",
                    normalizeDegrees((cell.column + 0.5) / LUNAR_LOLA_DEM_PIXELS_PER_DEGREE));
            values.put("pixel_size_m_at_equator", LUNAR_LOLA_DEM_PIXEL_SIZE_M);
            values.put("reference_radius_m", 1_737_400);
            writeCache(cacheKey, JSON.writeValueAsString(values));
            return new Source(name, values, SourceState.DATA, "ok (5 PDS byte-range reads; 5x5 grid)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Source failed = new Source(name, null, SourceState.NETWORK_ERROR, "interrupted");
            writeFailureCache(cacheKey, failed);
            return failed;
        } catch (IOException | RuntimeException e) {
            String status = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            SourceState state = status.startsWith("HTTP ") || status.startsWith("invalid ")
                    || status.startsWith("non-finite") ? SourceState.API_ERROR : SourceState.NETWORK_ERROR;
            Source failed = new Source(name, null, state, status);
            writeFailureCache(cacheKey, failed);
            return failed;
        }
    }

    static String pds(Source source) {
        if (source == null) return NA;
        if (source.state == SourceState.EMPTY) return "PDS request succeeded; zero products found";
        if (source.state != SourceState.DATA || source.json == null) return NA;
        String detail = terms(source.json, Set.of("pdsid", "product_id", "productid", "product_type", "producttype", "pt", "iid", "instrument_name"), 3);
        return NA.equals(detail) ? "PDS product query succeeded; no compact metadata field found" : detail;
    }

    static String combinePds(Source... sources) {
        List<String> available = new ArrayList<>();
        for (Source source : sources) {
            if (source != null && source.state == SourceState.DATA) {
                available.add(source.name + " available");
            }
        }
        return available.isEmpty() ? NA : truncate(available, 3);
    }

    static LunarTerrain lunarTerrain(Source source, double siteLat, double siteLon) {
        JsonNode rows = source == null || source.json == null ? null : source.json.path("rows");
        if (source == null || source.state != SourceState.DATA || rows == null || !rows.isArray()) return LunarTerrain.none();
        List<double[]> points = new ArrayList<>();
        List<double[]> localPoints = new ArrayList<>();
        double nearestDistance = Double.POSITIVE_INFINITY, nearestElevation = Double.NaN;
        double siteEastLon = ((siteLon % 360) + 360) % 360;
        double cosLat = Math.cos(Math.toRadians(siteLat));
        for (JsonNode row : rows) {
            double lat = numberField(row, "Pt_Latitude", "latitude", "lat");
            double lon = numberField(row, "Pt_Longitude", "longitude", "lon");
            double elevation = numberField(row, "Topography", "topography", "elevation", "topo");
            if (!Double.isFinite(lat) || lat < -90 || lat > 90 || !Double.isFinite(lon) || !Double.isFinite(elevation)
                    || elevation == -9999 || elevation == -9998) continue;
            double dLon = ((lon - siteEastLon + 540) % 360) - 180;
            double x = 1_737_400 * cosLat * Math.toRadians(dLon);
            double y = 1_737_400 * Math.toRadians(lat - siteLat);
            double distance = Math.hypot(x, y);
            if (distance < nearestDistance) { nearestDistance = distance; nearestElevation = elevation; }
            double[] point = {x, y, elevation};
            points.add(point);
            if (distance <= 1_000) localPoints.add(point);
        }
        if (points.isEmpty()) return LunarTerrain.none();
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double[] point : localPoints) {
            min = Math.min(min, point[2]);
            max = Math.max(max, point[2]);
        }
        double relief = localPoints.isEmpty() ? Double.NaN : max - min;
        double slope = Double.NaN, aspect = Double.NaN, roughness = Double.NaN;
        if (localPoints.size() >= 3) {
            double[][] normal = new double[3][3];
            double[] rhs = new double[3];
            for (double[] point : localPoints) {
                double[] v = {point[0], point[1], 1};
                for (int i = 0; i < 3; i++) {
                    rhs[i] += v[i] * point[2];
                    for (int j = 0; j < 3; j++) normal[i][j] += v[i] * v[j];
                }
            }
            double[] plane = solve3x3(normal, rhs);
            if (plane != null) {
                double dzdx = plane[0], dzdy = plane[1];
                slope = Math.toDegrees(Math.atan(Math.hypot(dzdx, dzdy)));
                if (Math.hypot(dzdx, dzdy) > 1e-12) {
                    aspect = (Math.toDegrees(Math.atan2(-dzdx, -dzdy)) + 360) % 360;
                }
                if (localPoints.size() > 3) {
                    double squared = 0;
                    for (double[] point : localPoints) {
                        double residual = point[2] - (dzdx * point[0] + dzdy * point[1] + plane[2]);
                        squared += residual * residual;
                    }
                    roughness = Math.sqrt(squared / (localPoints.size() - 3));
                }
            }
        }
        double searchHalfWidth = source.json.path("query_halfwidth_deg").asDouble(.005);
        return new LunarTerrain(points.size(), localPoints.size(), searchHalfWidth, nearestElevation,
                nearestDistance, relief, slope, aspect, roughness);
    }

    static ArrayNode lunarLolaSamples(Source source, double siteLat, double siteLon) {
        ArrayNode samples = JSON.createArrayNode();
        JsonNode rows = source == null || source.json == null ? null : source.json.path("rows");
        if (source == null || source.state != SourceState.DATA || rows == null || !rows.isArray()) return samples;
        double siteEastLon = ((siteLon % 360) + 360) % 360;
        double cosLat = Math.cos(Math.toRadians(siteLat));
        for (JsonNode row : rows) {
            double lat = numberField(row, "Pt_Latitude", "latitude", "lat");
            double lon = numberField(row, "Pt_Longitude", "longitude", "lon");
            double elevation = numberField(row, "Topography", "topography", "elevation", "topo");
            if (!Double.isFinite(lat) || lat < -90 || lat > 90 || !Double.isFinite(lon) || !Double.isFinite(elevation)
                    || elevation == -9999 || elevation == -9998) continue;
            double dLon = ((lon - siteEastLon + 540) % 360) - 180;
            double x = 1_737_400 * cosLat * Math.toRadians(dLon);
            double y = 1_737_400 * Math.toRadians(lat - siteLat);
            ObjectNode sample = samples.addObject();
            sample.put("latitude_deg", lat);
            sample.put("longitude_east_deg", lon);
            sample.put("elevation_m", elevation);
            sample.put("distance_to_coordinate_m", Math.hypot(x, y));
        }
        return samples;
    }

    static ArrayNode lunarDivinerSamples(Source source) {
        ArrayNode samples = JSON.createArrayNode();
        JsonNode rows = source == null || source.json == null ? null : source.json.path("rows");
        if (source == null || source.state != SourceState.DATA || rows == null || !rows.isArray()) return samples;
        String[] fields = {"date", "utc", "c", "tb", "radiance", "clat", "clon", "cemis",
                "csunzen", "csunazi", "cloctime", "qca", "qge", "qmi"};
        String[] names = {"date", "utc", "channel", "brightness_temperature_k", "radiance_w_m2_sr",
                "latitude_deg", "longitude_east_deg", "emission_angle_deg", "solar_zenith_deg",
                "solar_azimuth_deg", "local_solar_time_h", "quality_calibration",
                "quality_geometry", "quality_misc"};
        for (JsonNode row : rows) {
            double channel = numberField(row, "c");
            if (!Double.isFinite(channel) || channel < 6 || channel > 9) continue;
            ObjectNode sample = samples.addObject();
            for (int i = 0; i < fields.length; i++) {
                JsonNode value = row.path(fields[i]);
                if (value.isTextual()) sample.put(names[i], value.asText());
                else if (value.isNumber()) {
                    double numeric = value.asDouble();
                    if ((fields[i].equals("tb") || fields[i].equals("radiance")) && numeric <= -9000) continue;
                    sample.put(names[i], numeric);
                }
            }
        }
        return samples;
    }

    static double numberField(JsonNode row, String... names) {
        for (String name : names) {
            JsonNode value = row.path(name);
            if (value.isNumber()) return value.asDouble();
            if (value.isTextual()) {
                try { return Double.parseDouble(value.asText()); } catch (NumberFormatException ignored) {}
            }
            Iterator<String> fields = row.fieldNames();
            while (fields.hasNext()) {
                String key = fields.next();
                if (key.equalsIgnoreCase(name)) {
                    value = row.path(key);
                    if (value.isNumber()) return value.asDouble();
                    if (value.isTextual()) {
                        try { return Double.parseDouble(value.asText()); } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        return Double.NaN;
    }

    static double[] solve3x3(double[][] matrix, double[] vector) {
        double[][] a = new double[3][4];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(matrix[i], 0, a[i], 0, 3);
            a[i][3] = vector[i];
        }
        for (int col = 0; col < 3; col++) {
            int pivot = col;
            for (int row = col + 1; row < 3; row++) if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) pivot = row;
            if (Math.abs(a[pivot][col]) < 1e-12) return null;
            double[] swap = a[col]; a[col] = a[pivot]; a[pivot] = swap;
            double divisor = a[col][col];
            for (int k = col; k < 4; k++) a[col][k] /= divisor;
            for (int row = 0; row < 3; row++) {
                if (row == col) continue;
                double factor = a[row][col];
                for (int k = col; k < 4; k++) a[row][k] -= factor * a[col][k];
            }
        }
        return new double[]{a[0][3], a[1][3], a[2][3]};
    }

    static String lunarTerrainSummary(Source source, double lat, double lon) {
        LunarTerrain terrain = lunarTerrain(source, lat, lon);
        if (terrain.count == 0) return NA;
        return String.format(Locale.US, "%d LOLA shots returned in ±%.3f° search box (%d within 1 km); nearest elevation %.1f m (%.1f m from coordinate), local relief %s, fitted slope/aspect %s / %s, detrended roughness %s",
                terrain.count, terrain.searchHalfWidthDeg, terrain.localCount, terrain.nearestElevation,
                terrain.nearestDistance, finite(terrain.relief) ? String.format(Locale.US, "%.1f m", terrain.relief) : NA,
                finite(terrain.slope) ? String.format(Locale.US, "%.2f°", terrain.slope) : NA,
                finite(terrain.aspect) ? String.format(Locale.US, "%.1f°", terrain.aspect) : NA,
                finite(terrain.roughness) ? String.format(Locale.US, "%.2f m", terrain.roughness) : NA);
    }

    static DivinerStats divinerStats(Source source) {
        JsonNode rows = source == null || source.json == null ? null : source.json.path("rows");
        if (source == null || source.state != SourceState.DATA || rows == null || !rows.isArray()) return DivinerStats.none();
        int records = 0, qcaZero = 0, qgeZero = 0, qmiZero = 0, allFlagsZero = 0, valid = 0;
        Map<Integer, Integer> qcaCounts = new TreeMap<>(), qgeCounts = new TreeMap<>(), qmiCounts = new TreeMap<>();
        int[] counts = new int[10], qcaQmiZeroCounts = new int[10], dayCounts = new int[10], nightCounts = new int[10];
        double[] sums = new double[10], mins = new double[10], maxes = new double[10];
        double[] qcaQmiZeroSums = new double[10], qcaQmiZeroMins = new double[10], qcaQmiZeroMaxes = new double[10];
        double[] daySums = new double[10], nightSums = new double[10];
        Arrays.fill(mins, Double.POSITIVE_INFINITY);
        Arrays.fill(maxes, Double.NEGATIVE_INFINITY);
        Arrays.fill(qcaQmiZeroMins, Double.POSITIVE_INFINITY);
        Arrays.fill(qcaQmiZeroMaxes, Double.NEGATIVE_INFINITY);
        LocalDate first = null, last = null;
        DateTimeFormatter pdsDate = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.US);
        for (JsonNode row : rows) {
            double channelValue = numberField(row, "c");
            if (!Double.isFinite(channelValue)) continue;
            int channel = (int) channelValue;
            if (channel < 6 || channel > 9) continue;
            records++;
            double qca = numberField(row, "qca"), qge = numberField(row, "qge"), qmi = numberField(row, "qmi");
            addQualityFlag(qcaCounts, qca);
            addQualityFlag(qgeCounts, qge);
            addQualityFlag(qmiCounts, qmi);
            if (qca == 0) qcaZero++;
            if (qge == 0) qgeZero++;
            if (qmi == 0) qmiZero++;
            boolean calibrationAndMiscBest = qca == 0 && qmi == 0;
            if (calibrationAndMiscBest && qge == 0) allFlagsZero++;
            double temperature = numberField(row, "tb");
            if (Double.isFinite(temperature) && temperature > 0 && temperature <= 450
                    && temperature != -9999 && temperature != -9998) {
                valid++;
                counts[channel]++;
                sums[channel] += temperature;
                mins[channel] = Math.min(mins[channel], temperature);
                maxes[channel] = Math.max(maxes[channel], temperature);
                if (calibrationAndMiscBest) {
                    qcaQmiZeroCounts[channel]++;
                    qcaQmiZeroSums[channel] += temperature;
                    qcaQmiZeroMins[channel] = Math.min(qcaQmiZeroMins[channel], temperature);
                    qcaQmiZeroMaxes[channel] = Math.max(qcaQmiZeroMaxes[channel], temperature);
                    double solarZenith = numberField(row, "csunzen");
                    if (Double.isFinite(solarZenith) && solarZenith < 90) {
                        dayCounts[channel]++;
                        daySums[channel] += temperature;
                    } else if (Double.isFinite(solarZenith) && solarZenith > 90) {
                        nightCounts[channel]++;
                        nightSums[channel] += temperature;
                    }
                }
            }
            String dateText = row.path("date").asText("");
            try {
                LocalDate date = LocalDate.parse(dateText, pdsDate);
                if (first == null || date.isBefore(first)) first = date;
                if (last == null || date.isAfter(last)) last = date;
            } catch (RuntimeException ignored) {}
        }
        List<DivinerChannelStats> channels = new ArrayList<>();
        for (int channel = 6; channel <= 9; channel++) {
            if (counts[channel] > 0) {
                channels.add(new DivinerChannelStats(channel, counts[channel], mins[channel], maxes[channel],
                        sums[channel] / counts[channel], qcaQmiZeroCounts[channel],
                        qcaQmiZeroCounts[channel] == 0 ? Double.NaN : qcaQmiZeroMins[channel],
                        qcaQmiZeroCounts[channel] == 0 ? Double.NaN : qcaQmiZeroMaxes[channel],
                        qcaQmiZeroCounts[channel] == 0 ? Double.NaN : qcaQmiZeroSums[channel] / qcaQmiZeroCounts[channel],
                        dayCounts[channel], dayCounts[channel] == 0 ? Double.NaN : daySums[channel] / dayCounts[channel],
                        nightCounts[channel], nightCounts[channel] == 0 ? Double.NaN : nightSums[channel] / nightCounts[channel],
                        dayCounts[channel] == 0 || nightCounts[channel] == 0 ? Double.NaN
                                : daySums[channel] / dayCounts[channel] - nightSums[channel] / nightCounts[channel]));
            }
        }
        return new DivinerStats(records, valid, qcaZero, qgeZero, qmiZero, allFlagsZero,
                qualityFlagSummary(qcaCounts), qualityFlagSummary(qgeCounts), qualityFlagSummary(qmiCounts),
                first == null ? "" : first.toString(), last == null ? "" : last.toString(), List.copyOf(channels));
    }

    static void addQualityFlag(Map<Integer, Integer> counts, double flag) {
        if (Double.isFinite(flag) && flag >= 0 && flag <= 255 && flag == Math.rint(flag)) {
            counts.merge((int) flag, 1, Integer::sum);
        }
    }

    static String qualityFlagSummary(Map<Integer, Integer> counts) {
        List<String> values = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            values.add(entry.getKey() + ":" + entry.getValue());
        }
        return String.join(",", values);
    }

    static String lunarObservationSummary(Source source) {
        DivinerStats stats = divinerStats(source);
        if (stats.recordCount == 0) return NA;
        List<String> byChannel = new ArrayList<>();
        for (DivinerChannelStats channel : stats.channels) {
            String qcaQmi = channel.qcaQmiZeroCount == 0 ? "qca/qmi=0 n=0" : String.format(Locale.US,
                    "qca/qmi=0 n=%d %.2f–%.2f K (mean %.2f K); qge retained",
                    channel.qcaQmiZeroCount, channel.qcaQmiZeroMinKelvin, channel.qcaQmiZeroMaxKelvin,
                    channel.qcaQmiZeroMeanKelvin);
            byChannel.add(String.format(Locale.US, "ch%d n=%d %.2f–%.2f K (mean %.2f K), %s",
                    channel.channel, channel.count, channel.minKelvin, channel.maxKelvin, channel.meanKelvin, qcaQmi));
        }
        String dates = stats.firstDate.isEmpty() ? "" : "; dates " + stats.firstDate + " to " + stats.lastDate;
        return String.format(Locale.US,
                "%d channel 6–9 records; %d positive finite brightness temperatures by band: %s; qca=0 %d/%d, qge=0 %d/%d, qmi=0 %d/%d, all flags zero %d/%d; flag counts qca{%s} qge{%s} qmi{%s}%s",
                stats.recordCount, stats.validTemperatureCount, String.join("; ", byChannel), stats.calibrationBestCount,
                stats.recordCount, stats.geometryBestCount, stats.recordCount,
                stats.miscellaneousBestCount, stats.recordCount, stats.allFlagsBestCount, stats.recordCount,
                stats.qcaFlagCounts, stats.qgeFlagCounts, stats.qmiFlagCounts, dates);
    }

    static String lunarThermalContrastSummary(Source source) {
        DivinerStats stats = divinerStats(source);
        List<String> byChannel = new ArrayList<>();
        for (DivinerChannelStats channel : stats.channels) {
            if (channel.daytimeCount == 0 || channel.nighttimeCount == 0) continue;
            byChannel.add(String.format(Locale.US, "ch%d day/night mean %.1f/%.1f K (n=%d/%d; Δ=%.1f K)",
                    channel.channel, channel.daytimeMeanKelvin, channel.nighttimeMeanKelvin,
                    channel.daytimeCount, channel.nighttimeCount, channel.dayNightContrastKelvin));
        }
        if (byChannel.isEmpty()) return NA;
        return "Historical Diviner brightness-temperature day/night contrast, qca=qmi=0 (qge retained): "
                + String.join("; ", byChannel) + "; orbital samples, not a complete diurnal cycle.";
    }

    static String lunarIlluminationSummary(Source source) {
        double[] sun = lunarSunAngles(source);
        if (sun == null) return NA;
        return String.format(Locale.US, "Sun elevation %.2f°, azimuth %.2f° (JPL Horizons, UTC)", sun[1], sun[0]);
    }

    static List<LunarEphemerisSample> lunarEphemerisSamples(Source source) {
        if (source == null || source.state != SourceState.DATA || source.json == null) return List.of();
        String text = source.json.path("result").asText("");
        int start = text.indexOf("$$SOE"), end = text.indexOf("$$EOE");
        if (start < 0 || end <= start) return List.of();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MMM-dd HH:mm", Locale.US);
        List<LunarEphemerisSample> samples = new ArrayList<>();
        for (String line : text.substring(start + 5, end).split("\\R")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 6) continue;
            LocalDateTime time;
            try { time = LocalDateTime.parse(fields[0] + " " + fields[1], formatter); }
            catch (RuntimeException ignored) { continue; }
            double[] values = new double[3];
            int count = 0;
            for (int i = 2; i < fields.length && count < values.length; i++) {
                try {
                    double value = Double.parseDouble(fields[i]);
                    values[count++] = value;
                }
                catch (NumberFormatException ignored) {}
            }
            if (count == 3 && Double.isFinite(values[0]) && Double.isFinite(values[1])
                    && Double.isFinite(values[2]) && values[2] > 0) {
                samples.add(new LunarEphemerisSample(time, values[0], values[1], values[2]));
            }
        }
        return List.copyOf(samples);
    }

    static double[] lunarSunAngles(Source source) {
        List<LunarEphemerisSample> samples = lunarEphemerisSamples(source);
        return samples.isEmpty() ? null : new double[]{samples.get(0).azimuthDeg, samples.get(0).elevationDeg};
    }

    static double lunarSunlightHours(Source source) {
        List<LunarEphemerisSample> samples = lunarEphemerisSamples(source);
        if (samples.size() < 2) return Double.NaN;
        double daylightHours = 0;
        for (int i = 1; i < samples.size(); i++) {
            LunarEphemerisSample before = samples.get(i - 1), after = samples.get(i);
            double intervalHours = Duration.between(before.time, after.time).toSeconds() / 3600.0;
            if (!(intervalHours > 0)) continue;
            double beforeElevation = before.elevationDeg, afterElevation = after.elevationDeg;
            if (beforeElevation >= 0 && afterElevation >= 0) daylightHours += intervalHours;
            else if ((beforeElevation >= 0) != (afterElevation >= 0)) {
                double daylightFraction = beforeElevation >= 0
                        ? beforeElevation / (beforeElevation - afterElevation)
                        : afterElevation / (afterElevation - beforeElevation);
                daylightHours += intervalHours * Math.max(0, Math.min(1, daylightFraction));
            }
        }
        return daylightHours;
    }

    static double lunarSolarCycleDays(Source source) {
        List<LunarEphemerisSample> samples = lunarEphemerisSamples(source);
        if (samples.size() < 2) return Double.NaN;
        return Duration.between(samples.get(0).time, samples.get(samples.size() - 1).time).toSeconds() / 86_400.0;
    }

    static double[] lunarSolarIrradiance(Source source) {
        List<LunarEphemerisSample> samples = lunarEphemerisSamples(source);
        if (samples.isEmpty()) return null;
        LunarEphemerisSample sample = samples.get(0);
        if (sample.elevationDeg <= 0) return new double[]{0, 0};
        double normal = 1_361.0 / (sample.solarDistanceAu * sample.solarDistanceAu);
        return new double[]{normal, normal * Math.sin(Math.toRadians(sample.elevationDeg))};
    }

    static String lunarDaylightSummary(JsonNode data) {
        JsonNode hours = data == null ? null : data.path("sunlight_hours_cycle");
        JsonNode days = data == null ? null : data.path("solar_cycle_days");
        if (hours == null || !hours.isNumber() || days == null || !days.isNumber()) return NA;
        return String.format(Locale.US,
                "%.1f h above a flat horizon over %.2f Earth days (JPL Horizons sampled every 6 h; local terrain horizon excluded)",
                hours.asDouble(), days.asDouble());
    }

    static boolean finite(double value) { return Double.isFinite(value); }

    static boolean validCoordinate(double lat, double lon) { return Double.isFinite(lat) && Double.isFinite(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180; }

    static CompletableFuture<Source> asyncGet(String name, String url) {
        return CompletableFuture.supplyAsync(() -> get(name, url), FETCH_POOL);
    }

    static Source get(String name, String url, List<Source> sources) {
        Source result = get(name, url);
        if (sources != null) sources.add(result);
        return result;
    }

    static Source get(String name, String url) {
        return get(name, url, DEFAULT_TIMEOUT, MAX_ATTEMPTS);
    }

    static Source get(String name, String url, Duration timeout, int maxAttempts) {
        if (isLunarNamedCraterUrl(url)) return getLunarNamedCraterCatalog(name, url, timeout, maxAttempts);
        Source cached = readCache(name, url);
        if (cached != null) return cached;
        String failure = "no response";
        HttpResponse<InputStream> response = null;
        int attempts = Math.max(1, maxAttempts);
        int usedAttempts = 0;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            usedAttempts = attempt;
            try {
                String accept = isLunarGdsUrl(url) ? "application/xml, text/xml"
                        : isLunarGdsFileUrl(url) ? "text/csv, text/plain, application/octet-stream, */*"
                        : "application/json, application/xml, text/csv, text/plain";
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                        .header("Accept", accept)
                        .header("User-Agent", "DateDeletor/1.0 coordinate report").GET().build();
                response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                long length = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                if (length > MAX_RESPONSE_BYTES) { response.body().close(); failure = "response too large"; break; }
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    String body = boundedBody(response.body());
                    JsonNode json = parseResponse(url, body);
                    SourceState state = payloadState(url, json);
                    if (state != SourceState.API_ERROR) {
                        writeCache(url, isElevationUrl(url) || isMrdsUrl(url) ? JSON.writeValueAsString(json) : body);
                        return new Source(name, json, state, "ok (attempt " + attempt + ")");
                    }
                    failure = "API error payload"; break;
                }
                response.body().close();
                failure = "HTTP " + response.statusCode();
                if (response.statusCode() != 429 && response.statusCode() < 500) break;
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); failure = "interrupted"; break;
            } catch (IOException | RuntimeException e) {
                failure = e.getClass().getSimpleName();
                // Timeouts are usually endpoint overload; retrying doubles wall-clock for every coordinate.
                if (failure.contains("Timeout") || failure.contains("timeout")) break;
            }
            if (attempt < attempts && !backoff(attempt, response)) { failure = "interrupted"; break; }
        }
        SourceState state = classifyFailure(failure);
        String status = usedAttempts > 1 ? failure + " after retry" : failure;
        Source failed = new Source(name, null, state, status);
        writeFailureCache(url, failed);
        return failed;
    }

    static Source getLunarNamedCraterCatalog(String name, String url, Duration timeout, int maxAttempts) {
        String cacheKey = url + "#craters-and-landforms-v2";
        Source cached = readCache(name, cacheKey);
        if (cached != null) return cached;
        String failure = "no response";
        HttpResponse<InputStream> response = null;
        int attempts = Math.max(1, maxAttempts), usedAttempts = 0;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            usedAttempts = attempt;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                        .header("Accept", "application/vnd.google-earth.kmz, application/zip, application/octet-stream")
                        .header("User-Agent", "DateDeletor/1.0 coordinate report").GET().build();
                response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                long length = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                if (length > MAX_RESPONSE_BYTES) {
                    response.body().close();
                    failure = "response too large";
                    break;
                }
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    JsonNode json = parseLunarNamedCraterCatalog(boundedBytes(response.body()));
                    SourceState state = payloadState(url, json);
                    if (state != SourceState.API_ERROR) {
                        writeCache(cacheKey, JSON.writeValueAsString(json));
                        return new Source(name, json, state, "ok (attempt " + attempt + ")");
                    }
                    failure = "invalid lunar crater catalog payload";
                    break;
                }
                response.body().close();
                failure = "HTTP " + response.statusCode();
                if (response.statusCode() != 429 && response.statusCode() < 500) break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = "interrupted";
                break;
            } catch (IOException | RuntimeException e) {
                failure = e.getClass().getSimpleName();
                if (failure.contains("Timeout") || failure.contains("timeout")) break;
            }
            if (attempt < attempts && !backoff(attempt, response)) {
                failure = "interrupted";
                break;
            }
        }
        Source failed = new Source(name, null, classifyFailure(failure),
                usedAttempts > 1 ? failure + " after retry" : failure);
        writeFailureCache(cacheKey, failed);
        return failed;
    }

    static byte[] boundedBytes(InputStream stream) throws IOException {
        try (stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (output.size() + read > MAX_RESPONSE_BYTES) throw new IOException("response too large");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    static ObjectNode parseLunarNamedCraterCatalog(byte[] body) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().toLowerCase(Locale.ROOT).endsWith(".kml")) continue;
                ByteArrayOutputStream kml = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    if (kml.size() + read > MAX_RESPONSE_BYTES * 5) throw new IOException("Lunar KML entry too large");
                    kml.write(buffer, 0, read);
                }
                Document document = secureXmlDocument(kml.toString(StandardCharsets.UTF_8));
                NodeList placemarks = document.getElementsByTagNameNS("*", "Placemark");
                ObjectNode catalog = JSON.createObjectNode();
                ArrayNode craters = catalog.putArray("craters");
                ArrayNode landforms = catalog.putArray("landforms");
                for (int i = 0; i < placemarks.getLength(); i++) {
                    if (!(placemarks.item(i) instanceof Element placemark)) continue;
                    String type = lunarKmlValue(placemark, "type");
                    if (type == null) continue;
                    String name = lunarKmlValue(placemark, "clean_name");
                    double lat = parseDouble(lunarKmlValue(placemark, "center_lat"));
                    double lon = parseDouble(lunarKmlValue(placemark, "center_lon"));
                    if (name == null || !Double.isFinite(lat) || lat < -90 || lat > 90 || !Double.isFinite(lon)) continue;
                    String normalizedType = type.toLowerCase(Locale.ROOT);
                    if (normalizedType.contains("crater")) {
                        double diameter = parseDouble(lunarKmlValue(placemark, "diameter"));
                        if (!Double.isFinite(diameter) || diameter <= 0) continue;
                        ObjectNode crater = craters.addObject();
                        crater.put("name", name);
                        crater.put("latitude_deg", lat);
                        crater.put("longitude_east_deg", normalizeDegrees(lon));
                        crater.put("diameter_km", diameter);
                    } else if (normalizedType.startsWith("mons") || normalizedType.startsWith("dorsum")
                            || normalizedType.startsWith("rima")) {
                        ObjectNode landform = landforms.addObject();
                        landform.put("name", name);
                        landform.put("type", type);
                        landform.put("latitude_deg", lat);
                        landform.put("longitude_east_deg", normalizeDegrees(lon));
                    }
                }
                catalog.put("feature_count", craters.size());
                catalog.put("landform_count", landforms.size());
                return catalog;
            }
            throw new IOException("USGS lunar crater KMZ has no KML entry");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not parse USGS lunar crater catalog", e);
        }
    }

    static String lunarKmlValue(Element placemark, String key) {
        NodeList values = placemark.getElementsByTagNameNS("*", "SimpleData");
        for (int i = 0; i < values.getLength(); i++) {
            if (!(values.item(i) instanceof Element value) || !key.equals(value.getAttribute("name"))) continue;
            return value.getTextContent().trim();
        }
        return null;
    }

    static double parseDouble(String value) {
        if (value == null || value.isBlank()) return Double.NaN;
        try { return Double.parseDouble(value.trim()); }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    static JsonNode parseResponse(String url, String body) throws IOException {
        if (isMrdsUrl(url)) return parseMrdsResponse(body);
        if (isLunarGdsUrl(url)) return parseLunarGdsResponse(body);
        if (isDivinerTableUrl(url)) return parseDivinerTable(body);
        if (isLunarGdsFileUrl(url)) return parseLunarGdsTable(body);
        if (!isElevationUrl(url)) return JSON.readTree(body);
        String parseableBody = body.replaceAll("(?i)(?<=[,\\[])\\s*nan\\s*(?=[,\\]])", "null");
        return JSON.readTree(parseableBody);
    }

    static ObjectNode parseLunarGdsResponse(String body) throws IOException {
        try {
            Document document = secureXmlDocument(body);
            Element root = document.getDocumentElement();
            String rootName = root == null ? "" : root.getLocalName();
            if (rootName == null && root != null) rootName = root.getNodeName();
            if (!"GDSResults".equals(rootName)) {
                return JSON.createObjectNode().put("error", "Unexpected lunar GDS response");
            }
            String status = xmlText(document, "Status");
            ObjectNode result = JSON.createObjectNode();
            result.put("status", status);
            result.put("statusNote", xmlText(document, "StatusNote"));
            try { result.put("count", Integer.parseInt(xmlText(document, "Count"))); }
            catch (NumberFormatException e) { result.put("count", -1); }
            ArrayNode files = result.putArray("resultFiles");
            NodeList entries = document.getElementsByTagName("ResultFile");
            for (int i = 0; i < entries.getLength(); i++) {
                if (!(entries.item(i) instanceof Element entry)) continue;
                String link = xmlText(entry, "URL");
                if (link != null && !link.isBlank()) files.add(link);
            }
            if (!"success".equalsIgnoreCase(status)) result.put("error", "Lunar GDS status: " + status);
            return result;
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException("Could not parse lunar GDS response", e);
        }
    }

    static ObjectNode parseLunarGdsTable(String body) throws IOException {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode rows = result.putArray("rows");
        List<String> headers = null;
        for (String line : body.split("\\R")) {
            String text = line.trim();
            if (text.isEmpty()) continue;
        if (text.startsWith("#")) continue;
            List<String> fields = parseDelimitedLine(text);
            if (headers == null) {
                headers = fields;
                continue;
            }
            if (fields.size() < headers.size()) continue;
            ObjectNode row = rows.addObject();
            for (int i = 0; i < headers.size(); i++) {
                String key = headers.get(i).trim();
                if (key.isEmpty()) continue;
                String value = fields.get(i).trim();
                try { row.put(key, Double.parseDouble(value)); }
                catch (NumberFormatException e) { row.put(key, value); }
            }
        }
        return result;
    }

    static ObjectNode parseDivinerTable(String body) {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode rows = result.putArray("rows");
        List<String> headers = null;
        for (String line : body.split("\\R")) {
            String text = line.trim();
            if (text.isEmpty()) continue;
            if (text.startsWith("#")) {
                String heading = text.substring(1).trim();
                if (heading.toLowerCase(Locale.ROOT).startsWith("date,")) {
                    List<String> candidate = parseDelimitedLine(heading);
                    if (candidate.stream().anyMatch(field -> "tb".equalsIgnoreCase(field.trim()))) headers = candidate;
                }
                continue;
            }
            if (headers == null) continue;
            List<String> fields;
            try { fields = parseDelimitedLine(text); }
            catch (IllegalArgumentException ignored) { continue; }
            if (fields.size() < headers.size()) continue;
            ObjectNode row = rows.addObject();
            for (int i = 0; i < headers.size(); i++) {
                String key = headers.get(i).trim();
                if (key.isEmpty()) continue;
                String value = fields.get(i).trim();
                try { row.put(key, Double.parseDouble(value)); }
                catch (NumberFormatException e) { row.put(key, value); }
            }
        }
        return result;
    }

    static Document secureXmlDocument(String body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(body)));
    }

    static String xmlText(Document document, String name) {
        NodeList matches = document.getElementsByTagNameNS("*", name);
        if (matches.getLength() == 0) matches = document.getElementsByTagName(name);
        return matches.getLength() == 0 ? "" : matches.item(0).getTextContent().trim();
    }

    static String xmlText(Element parent, String name) {
        NodeList matches = parent.getElementsByTagNameNS("*", name);
        if (matches.getLength() == 0) matches = parent.getElementsByTagName(name);
        return matches.getLength() == 0 ? "" : matches.item(0).getTextContent().trim();
    }

    static ObjectNode parseMrdsResponse(String body) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(body)));
            if (document.getElementsByTagNameNS("*", "ExceptionReport").getLength() > 0
                    || document.getElementsByTagNameNS("*", "ServiceExceptionReport").getLength() > 0
                    || document.getElementsByTagNameNS("*", "ServiceException").getLength() > 0) {
                ObjectNode error = JSON.createObjectNode();
                error.put("error", "MRDS WFS exception");
                return error;
            }
            Element root = document.getDocumentElement();
            String rootName = root == null ? "" : root.getLocalName();
            if (rootName == null && root != null) rootName = root.getNodeName();
            if (!"FeatureCollection".equals(rootName)) {
                ObjectNode error = JSON.createObjectNode();
                error.put("error", "Unexpected MRDS WFS response");
                return error;
            }
            ObjectNode result = JSON.createObjectNode();
            ArrayNode features = result.putArray("features");
            NodeList members = document.getElementsByTagNameNS("*", "featureMember");
            for (int i = 0; i < members.getLength(); i++) {
                if (!(members.item(i) instanceof Element member)) continue;
                NodeList sites = member.getElementsByTagNameNS("*", "mrds");
                if (sites.getLength() == 0 || !(sites.item(0) instanceof Element site)) continue;
                String position = mrdsChildText(site, "pos");
                String[] coordinates = position == null ? new String[0] : position.trim().split("\\s+");
                if (coordinates.length != 2) continue;
                double latitude, longitude;
                try {
                    latitude = Double.parseDouble(coordinates[0]);
                    longitude = Double.parseDouble(coordinates[1]);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (!validCoordinate(latitude, longitude)) continue;
                ObjectNode feature = features.addObject();
                feature.put("latitude", latitude);
                feature.put("longitude", longitude);
                putText(feature, "dep_id", mrdsChildText(site, "dep_id"));
                putText(feature, "site_name", mrdsChildText(site, "site_name"));
                putText(feature, "dev_stat", mrdsChildText(site, "dev_stat"));
                putText(feature, "code_list", mrdsChildText(site, "code_list"));
                putText(feature, "url", mrdsChildText(site, "url"));
            }
            return result;
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException("Could not parse USGS MRDS WFS response", e);
        }
    }

    static String mrdsChildText(Element parent, String localName) {
        NodeList matches = parent.getElementsByTagNameNS("*", localName);
        return matches.getLength() == 0 ? null : matches.item(0).getTextContent().trim();
    }

    static boolean isMrdsUrl(String url) {
        return url != null && url.startsWith("https://mrdata.usgs.gov/services/wfs/mrds?");
    }

    static boolean isLunarGdsUrl(String url) {
        return url != null && url.startsWith("https://oderest.rsl.wustl.edu/livegds/?");
    }

    static boolean isLunarGdsFileUrl(String url) {
        return url != null && (url.startsWith("https://ode.rsl.wustl.edu/") || url.startsWith("https://oderest.rsl.wustl.edu/"))
                && (url.toLowerCase(Locale.ROOT).contains(".csv") || url.toLowerCase(Locale.ROOT).contains(".tab"));
    }

    static boolean isLunarNamedCraterUrl(String url) {
        return LUNAR_NAMED_CRATER_URL.equals(url);
    }

    static boolean isDivinerTableUrl(String url) {
        return isLunarGdsFileUrl(url) && url.toLowerCase(Locale.ROOT).contains("divinerrdr") && url.toLowerCase(Locale.ROOT).contains(".tab");
    }

    static boolean isElevationUrl(String url) {
        return url != null && url.startsWith("https://api.open-meteo.com/v1/elevation?");
    }

    static Source getOsm(double lat, double lon) {
        String query = overpassQuery(lat, lon);
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String circuitKey = "overpass-circuit://" + sha256(query);
        List<String> failures = new ArrayList<>(OVERPASS_ENDPOINTS.length);
        Source cached = null;
        if (Instant.now().isBefore(overpassDownUntil)) {
            failures.add("Overpass circuit open");
        } else {
            cached = readCache("OpenStreetMap features", circuitKey);
            if (cached != null && cached.state != SourceState.API_ERROR && cached.state != SourceState.NETWORK_ERROR) return cached;
            if (cached != null) {
                failures.add("cached Overpass failure: " + cached.status);
                openOverpassCircuit();
            } else {
                for (String endpoint : OVERPASS_ENDPOINTS) {
                    String url = endpoint + "?data=" + encoded;
                    Source result = get("OpenStreetMap features", url, OSM_TIMEOUT, 1);
                    if (result.state == SourceState.DATA || result.state == SourceState.EMPTY) {
                        try {
                            writeCache(circuitKey, result.json == null ? "{\"elements\":[]}" : JSON.writeValueAsString(result.json));
                        } catch (IOException ignored) { }
                        return result;
                    }
                    failures.add(URI.create(endpoint).getHost() + ": " + result.status);
                }
                openOverpassCircuit();
                Source failed = new Source("OpenStreetMap features", null, SourceState.NETWORK_ERROR,
                        "Overpass mirrors unavailable (" + String.join("; ", failures) + ")");
                writeFailureCache(circuitKey, failed);
            }
        }

        ApiKey apiKey = loadGeoapifyKey();
        if (apiKey.value() != null) {
            Source places = get("Geoapify mapped features", geoapifyUrl(lat, lon, apiKey.value()));
            if (places.state == SourceState.DATA || places.state == SourceState.EMPTY) {
                JsonNode elements = geoapifyElements(places.json, lat, lon);
                if (elements != null) {
                    SourceState state = elements.path("elements").isEmpty() ? SourceState.EMPTY : SourceState.DATA;
                    return new Source("Geoapify mapped features", elements, state,
                            "fallback via " + apiKey.status() + "; " + places.status);
                }
            }
            failures.add("Geoapify: " + places.status);
        } else {
            failures.add(apiKey.status());
        }
        return new Source("Mapped features", null, SourceState.NETWORK_ERROR, String.join("; ", failures));
    }

    static ApiKey loadGeoapifyKey() {
        return loadGeoapifyKey(System.getenv("GEOAPIFY_API_KEY"), geoapifyKeyFilePath());
    }

    static Path geoapifyKeyFilePath() {
        String configuredPath = System.getenv("GEOAPIFY_API_KEY_FILE");
        return configuredPath == null || configuredPath.isBlank()
                ? Path.of(System.getProperty("user.home"), ".config", "date-deletor", "geoapify-api-key.txt")
                : Path.of(configuredPath);
    }

    static void ensureGeoapifyKeyFile(Path keyFile) {
        try {
            Path parent = keyFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            try {
                Files.createFile(keyFile, PosixFilePermissions.asFileAttribute(
                        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
            } catch (FileAlreadyExistsException ignored) {
            } catch (UnsupportedOperationException e) {
                try { Files.createFile(keyFile); }
                catch (FileAlreadyExistsException ignored) { }
            }
        } catch (IOException | SecurityException e) {
            System.err.println("Could not create Geoapify key file at " + keyFile + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    static ApiKey loadGeoapifyKey(String environmentKey, Path keyFile) {
        return loadApiKey(environmentKey, keyFile, 0, "Geoapify");
    }

    static ApiKey loadNoaaGeomagneticKey() {
        return loadNoaaGeomagneticKey(System.getenv("NOAA_GEOMAG_API_KEY"), geoapifyKeyFilePath());
    }

    static ApiKey loadNoaaGeomagneticKey(String environmentKey, Path keyFile) {
        return loadApiKey(environmentKey, keyFile, 1, "NOAA geomagnetic");
    }

    static ApiKey loadApiKey(String environmentKey, Path keyFile, int lineIndex, String service) {
        if (environmentKey != null && !environmentKey.isBlank()) {
            return new ApiKey(environmentKey.trim(), "environment variable");
        }
        try {
            List<String> lines = Files.readAllLines(keyFile, StandardCharsets.UTF_8);
            if (lines.size() <= lineIndex) {
                return new ApiKey(null, "key file " + keyFile + " has no " + service + " key on line " + (lineIndex + 1));
            }
            String value = lines.get(lineIndex).trim();
            if (value.isEmpty()) {
                return new ApiKey(null, "key file " + keyFile + " has an empty " + service + " key on line " + (lineIndex + 1));
            }
            return new ApiKey(value, "key file " + keyFile + " (line " + (lineIndex + 1) + ")");
        } catch (NoSuchFileException e) {
            return new ApiKey(null, service + " key file not found: " + keyFile);
        } catch (IOException | SecurityException e) {
            return new ApiKey(null, "cannot read " + service + " key file " + keyFile + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    static Source noaaGeomagneticSource(double lat, double lon, double elevationMeters, LocalDate date) {
        ApiKey key = loadNoaaGeomagneticKey();
        if (key.value() == null) {
            return new Source("NOAA geomagnetic field", null, SourceState.NOT_CONFIGURED, key.status());
        }
        return get("NOAA geomagnetic field",
                noaaGeomagneticUrl(lat, lon, elevationMeters, date, key.value()), Duration.ofSeconds(15), MAX_ATTEMPTS);
    }

    static String noaaGeomagneticUrl(double lat, double lon, double elevationMeters, LocalDate date, String apiKey) {
        String latHemisphere = lat < 0 ? "S" : "N";
        String lonHemisphere = lon < 0 ? "W" : "E";
        return "https://www.ngdc.noaa.gov/geomag-web/calculators/calculateIgrfwmm"
                + "?lat1=" + coordinate(Math.abs(lat))
                + "&lat1Hemisphere=" + latHemisphere
                + "&lon1=" + coordinate(Math.abs(lon))
                + "&lon1Hemisphere=" + lonHemisphere
                + "&coordinateSystem=M&elevation=" + coordinate(Math.max(-1000, elevationMeters))
                + "&elevationUnits=M&model=WMMHR"
                + "&startYear=" + date.getYear() + "&startMonth=" + date.getMonthValue() + "&startDay=" + date.getDayOfMonth()
                + "&endYear=" + date.getYear() + "&endMonth=" + date.getMonthValue() + "&endDay=" + date.getDayOfMonth()
                + "&dateStepSize=1&resultFormat=json&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
    }

    static String geoapifyUrl(double lat, double lon, String apiKey) {
        String categories = "natural.mountain.cave_entrance,natural.water,natural.water.spring,"
                + "natural.water.hot_spring,natural.water.river_system";
        String filter = String.format(Locale.US, "circle:%.6f,%.6f,5000", lon, lat);
        return "https://api.geoapify.com/v2/places?categories=" + URLEncoder.encode(categories, StandardCharsets.UTF_8)
                + "&filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8)
                + "&limit=500&apiKey=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
    }

    static JsonNode geoapifyElements(JsonNode response, double lat, double lon) {
        JsonNode features = response == null ? null : response.path("features");
        if (features == null || !features.isArray()) return null;
        ObjectNode result = JSON.createObjectNode();
        ArrayNode elements = result.putArray("elements");
        for (JsonNode feature : features) {
            JsonNode categories = feature.path("properties").path("categories");
            JsonNode coordinates = feature.path("geometry").path("coordinates");
            if (!categories.isArray() || !coordinates.isArray() || coordinates.size() < 2
                    || !coordinates.get(0).isNumber() || !coordinates.get(1).isNumber()) continue;
            double featureLon = coordinates.get(0).asDouble(), featureLat = coordinates.get(1).asDouble();
            double distance = distanceMeters(lat, lon, featureLat, featureLon);
            Set<String> tags = new LinkedHashSet<>();
            for (JsonNode category : categories) if (category.isTextual()) tags.add(category.asText());
            ObjectNode mappedTags = JSON.createObjectNode();
            if (tags.contains("natural.mountain.cave_entrance") && distance <= 5000) {
                mappedTags.put("natural", "cave_entrance");
            } else if (tags.contains("natural.water.hot_spring") && distance <= 1500) {
                mappedTags.put("natural", "hot_spring");
            } else if (tags.contains("natural.water.spring") && distance <= 1500) {
                mappedTags.put("natural", "spring");
            } else if (tags.contains("natural.water.river_system") && distance <= 1500) {
                mappedTags.put("waterway", "river");
            } else if (tags.contains("natural.water") && distance <= 1500) {
                mappedTags.put("natural", "water");
            }
            if (mappedTags.isEmpty()) continue;
            JsonNode name = feature.path("properties").path("name");
            if (name.isTextual() && !name.asText().isBlank()) mappedTags.set("name", name);
            elements.addObject().set("tags", mappedTags);
        }
        return result;
    }

    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_008.8 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    static void openOverpassCircuit() {
        synchronized (OVERPASS_LOCK) {
            Instant until = Instant.now().plus(Duration.ofMinutes(5));
            if (until.isAfter(overpassDownUntil)) overpassDownUntil = until;
        }
    }

    static SourceState classifyFailure(String failure) {
        if (failure.contains("Timeout") || failure.contains("timeout") || failure.equals("HTTP 408") || failure.equals("HTTP 504")) {
            return SourceState.NETWORK_ERROR;
        }
        if (failure.startsWith("HTTP") || failure.equals("API error payload") || failure.equals("response too large")) {
            return SourceState.API_ERROR;
        }
        return SourceState.NETWORK_ERROR;
    }

    public static SourceState payloadState(String url, JsonNode json) {
        if (json == null || !json.isContainerNode()) return SourceState.API_ERROR;
        if (json.hasNonNull("error") || json.hasNonNull("errors") || json.hasNonNull("Error") || json.hasNonNull("Errors")) return SourceState.API_ERROR;
        if (json.hasNonNull("remark") && json.path("remark").asText().toLowerCase(Locale.ROOT).contains("error")) return SourceState.API_ERROR;
        if (json.hasNonNull("statusCode") && json.path("statusCode").asInt() >= 400) return SourceState.API_ERROR;
        if (isMrdsUrl(url)) {
            JsonNode features = json.path("features");
            if (!features.isArray()) return SourceState.API_ERROR;
            return features.isEmpty() ? SourceState.EMPTY : SourceState.DATA;
        }
        if (isLunarNamedCraterUrl(url)) {
            JsonNode craters = json.path("craters");
            return craters.isArray() && !craters.isEmpty() ? SourceState.DATA : SourceState.API_ERROR;
        }
        if (isLunarGdsUrl(url)) {
            if (!"success".equalsIgnoreCase(json.path("status").asText())) return SourceState.API_ERROR;
            if (json.path("count").asInt(-1) == 0) return SourceState.EMPTY;
            return json.path("resultFiles").isArray() && !json.path("resultFiles").isEmpty()
                    ? SourceState.DATA : SourceState.API_ERROR;
        }
        if (isLunarGdsFileUrl(url)) {
            JsonNode rows = json.path("rows");
            if (!rows.isArray()) return SourceState.API_ERROR;
            return rows.isEmpty() ? SourceState.EMPTY : SourceState.DATA;
        }
        if (url != null && url.contains("cmr.earthdata.nasa.gov/search/granules.json")) {
            JsonNode entries = json.path("feed").path("entry");
            if (!entries.isArray()) return SourceState.API_ERROR;
            return entries.isEmpty() ? SourceState.EMPTY : SourceState.DATA;
        }
        if (url != null && url.contains("overpass") && json.path("elements").isArray() && json.path("elements").isEmpty()) return SourceState.EMPTY;
        if (json.has("ODEResults") || (url != null && url.contains("oderest.rsl.wustl.edu"))) return odeState(json);
        return SourceState.DATA;
    }

    public static boolean validApiPayload(JsonNode json) {
        if (json == null || !json.isContainerNode()) return false;
        if (json.hasNonNull("error") || json.hasNonNull("errors") || json.hasNonNull("Error") || json.hasNonNull("Errors")) return false;
        if (json.hasNonNull("remark") && json.path("remark").asText().toLowerCase(Locale.ROOT).contains("error")) return false;
        if (json.hasNonNull("statusCode") && json.path("statusCode").asInt() >= 400) return false;
        JsonNode ode = json.path("ODEResults");
        if (!ode.isMissingNode()) {
            return odeState(json) != SourceState.API_ERROR;
        }
        return true;
    }

    public static SourceState odeState(JsonNode json) {
        JsonNode ode = json.path("ODEResults");
        if (ode.isMissingNode() || !ode.isObject()) return SourceState.API_ERROR;
        if (ode.hasNonNull("Error") || ode.hasNonNull("Errors") || ode.hasNonNull("error") || ode.hasNonNull("errors")) return SourceState.API_ERROR;
        String status = ode.path("Status").asText("");
        if (!"SUCCESS".equalsIgnoreCase(status)) return SourceState.API_ERROR;

        JsonNode count = ode.path("Count");
        if (count.isNumber() && count.asInt() == 0) return SourceState.EMPTY;
        if (count.isTextual() && "0".equals(count.asText().trim())) return SourceState.EMPTY;

        JsonNode products = ode.path("Products");
        if (products.isMissingNode() || products.isNull()) return SourceState.EMPTY;
        if (products.isTextual()) return SourceState.EMPTY;
        JsonNode prod = products.path("Product");
        if (prod.isMissingNode() || prod.isNull()) return SourceState.EMPTY;
        if (prod.isArray() && prod.isEmpty()) return SourceState.EMPTY;
        if (prod.isObject() && prod.isEmpty()) return SourceState.EMPTY;
        return SourceState.DATA;
    }

    static String boundedBody(InputStream stream) throws IOException {
        try (stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (output.size() + read > MAX_RESPONSE_BYTES) throw new IOException("response too large");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    static Source readCache(String name, String url) {
        Path file = cacheFile(url);
        try {
            if (!Files.isRegularFile(file)) return null;
            Duration age = Duration.between(Files.getLastModifiedTime(file).toInstant(), Instant.now());
            if (age.isNegative()) return null;
            String body = Files.readString(file);
            JsonNode json = null;
            try { json = JSON.readTree(body); } catch (JsonProcessingException ignored) {}
            if (json != null && json.has(CACHE_MARKER)) {
                JsonNode marker = json.get(CACHE_MARKER);
                SourceState state = SourceState.valueOf(marker.path("state").asText("NETWORK_ERROR"));
                Duration ttl = failureCacheTtl(state, name);
                if (age.compareTo(ttl) > 0) return null;
                return new Source(name, null, state, "cache, " + age.toMinutes() + " min old — " + marker.path("status").asText("failed"));
            }
            if (age.compareTo(cacheTtl(name)) > 0) return null;
            if (isLunarGdsUrl(url) || isLunarGdsFileUrl(url)) json = parseResponse(url, body);
            if (json == null) return null;
            SourceState state = payloadState(url, json);
            if (state == SourceState.API_ERROR) {
                try { Files.deleteIfExists(file); } catch (IOException ignored) {}
                return null;
            }
            return new Source(name, json, state, "cache, " + age.toMinutes() + " min old");
        } catch (IOException | RuntimeException e) { return null; }
    }

    static void writeCache(String url, String body) {
        try {
            Files.createDirectories(CACHE_DIR);
            Path target = cacheFile(url), temporary = Files.createTempFile(CACHE_DIR, "response-", ".tmp");
            Files.writeString(temporary, body, StandardCharsets.UTF_8);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ignored) { }
    }

    static void writeFailureCache(String url, Source failed) {
        if (failed == null || (failed.state != SourceState.API_ERROR && failed.state != SourceState.NETWORK_ERROR)) return;
        try {
            ObjectNode marker = JSON.createObjectNode();
            ObjectNode body = marker.putObject(CACHE_MARKER);
            body.put("state", failed.state.name());
            body.put("status", failed.status);
            body.put("cachedAt", Instant.now().toString());
            writeCache(url, JSON.writeValueAsString(marker));
        } catch (IOException | RuntimeException ignored) { }
    }

    static Path cacheFile(String url) { return CACHE_DIR.resolve(sha256(url) + ".json"); }

    static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b)); return result.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    static Duration cacheTtl(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("weather") || lower.contains("air quality")) return Duration.ofMinutes(15);
        if (lower.contains("emit")) return Duration.ofHours(24);
        if (lower.contains("geoapify")) return Duration.ofHours(24);
        if (lower.contains("power")) return Duration.ofHours(24);
        return Duration.ofDays(30);
    }

    static Duration failureCacheTtl(SourceState state, String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.contains("openstreetmap") || lower.contains("overpass")) return Duration.ofMinutes(5);
        if (state == SourceState.NETWORK_ERROR) return Duration.ofMinutes(15);
        return Duration.ofHours(6);
    }

    static boolean backoff(int attempt, HttpResponse<?> response) {
        long waitMs = 200L * attempt;
        if (response != null) {
            Optional<String> retryAfter = response.headers().firstValue("Retry-After");
            if (retryAfter.isPresent()) {
                try {
                    long sec = Long.parseLong(retryAfter.get().trim());
                    waitMs = Math.max(waitMs, Math.min(sec * 1000L, 2000L));
                } catch (NumberFormatException ignored) {}
            }
        }
        try { Thread.sleep(waitMs); return true; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    /** Persist one independent, timestamped coordinate report with explicit planetary body. */
    public static void persistReport(String body, double latitude, double longitude, ObjectNode data) {
        persistReport(body, latitude, longitude, data, null);
    }

    static void persistReport(String body, double latitude, double longitude, ObjectNode data,
                              ObjectNode readableOutput) {
        try {
            if (!"earth".equalsIgnoreCase(body) && !"moon".equalsIgnoreCase(body)) {
                throw new IllegalArgumentException("Planetary body must be earth or moon.");
            }
            boolean lunar = "moon".equalsIgnoreCase(body);
            if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90 || !Double.isFinite(longitude)
                    || (!lunar && (longitude < -180 || longitude > 180))) {
                throw new IllegalArgumentException("Coordinates are outside the selected body's supported range.");
            }
            String normalizedBody = lunar ? "moon" : "earth";
            synchronized (STORE_LOCK) {
                if (findSavedReport(normalizedBody, latitude, longitude) != null) {
                    System.out.println("Reused existing " + normalizedBody + " site report from "
                            + reportFile.toAbsolutePath());
                    return;
                }
                ObjectNode record = JSON.createObjectNode();
                record.put("body", normalizedBody);
                record.put("id", UUID.randomUUID().toString());
                record.put("latitude", normalizeLat(latitude));
                record.put("longitude", normalizeLon(body, longitude));
                ObjectNode coordinate = record.putObject("coordinate");
                coordinate.put("latitude_deg", normalizeLat(latitude));
                coordinate.put("longitude_deg", normalizeLon(body, longitude));
                coordinate.put("longitude_convention", lunar ? "east_0_to_360" : "signed_minus_180_to_180");
                record.put("generated_at_utc", Instant.now().toString());
                if (readableOutput != null) {
                    ObjectNode output = readableOutput.deepCopy();
                    output.put("output_file", reportFile.toAbsolutePath().toString());
                    record.set("report_output", output);
                }
                record.set("data", data == null ? JSON.createObjectNode() : data.deepCopy());
                appendReportRecords(1, generator -> generator.writeTree(record));
                System.out.println("Saved " + normalizedBody + " site report to " + reportFile.toAbsolutePath());
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Report JSON was not written: " + e.getMessage());
        }
    }


    public static ObjectNode loadReportStore() throws IOException {
        if (!Files.exists(reportFile)) return emptyReportStore();
        String content = Files.readString(reportFile);
        if (content.isBlank()) return emptyReportStore();
        JsonNode parsed;
        try {
            parsed = JSON.readTree(content);
        } catch (JsonProcessingException e) {
            System.err.println("Existing " + reportFile + " is invalid; refusing to overwrite it.");
            return null;
        }
        if (!(parsed instanceof ObjectNode root)) {
            System.err.println("Existing " + reportFile + " is invalid; refusing to overwrite it.");
            return null;
        }
        int version = root.path("v").asInt(0);
        if (version < 3 && root.path("sites").isArray()) {
            System.err.println("Resetting legacy site reports; prior entries used merged records without reliable per-run metadata.");
            return emptyReportStore();
        }
        if (version != 3 || !root.path("reports").isArray()) {
            System.err.println("Existing " + reportFile + " has an unsupported report-store schema; refusing to overwrite it.");
            return null;
        }
        return root;
    }

    static boolean printSavedReportIfPresent(String body, double latitude, double longitude) {
        try {
            ObjectNode saved = findSavedReport(body, latitude, longitude);
            if (saved == null) return false;
            printSavedReport(saved);
            return true;
        } catch (IOException e) {
            System.err.println("Could not check saved reports in " + reportFile + ": " + e.getMessage());
            return false;
        }
    }

    static ObjectNode findSavedReport(String body, double latitude, double longitude) throws IOException {
        synchronized (STORE_LOCK) {
            ObjectNode store = loadReportStore();
            if (store == null) throw new IOException("Report store is invalid.");
            SiteKey wanted = siteKey(body, latitude, longitude);
            for (JsonNode report : store.path("reports")) {
                if (wanted.equals(siteKey(report))) return report instanceof ObjectNode object ? object : null;
            }
            return null;
        }
    }

    static Map<SiteKey, ObjectNode> savedReportsByCoordinate(String body) throws IOException {
        synchronized (STORE_LOCK) {
            ObjectNode store = loadReportStore();
            if (store == null) throw new IOException("Report store is invalid.");
            Map<SiteKey, ObjectNode> saved = new HashMap<>();
            for (JsonNode report : store.path("reports")) {
                SiteKey key = siteKey(report);
                if (key != null && body.equals(key.body()) && report instanceof ObjectNode object) {
                    saved.putIfAbsent(key, object.deepCopy());
                }
            }
            return saved;
        }
    }

    static Set<SiteKey> reportKeys(JsonNode reports) {
        Set<SiteKey> keys = new HashSet<>();
        if (reports != null && reports.isArray()) {
            for (JsonNode report : reports) {
                SiteKey key = siteKey(report);
                if (key != null) keys.add(key);
            }
        }
        return keys;
    }

    static SiteKey siteKey(JsonNode report) {
        if (report == null || !report.isObject()) return null;
        String body = report.path("body").asText("").toLowerCase(Locale.ROOT);
        JsonNode coordinate = report.path("coordinate");
        JsonNode latitude = coordinate.path("latitude_deg");
        if (!latitude.isNumber()) latitude = report.path("latitude");
        JsonNode longitude = coordinate.path("longitude_deg");
        if (!longitude.isNumber()) longitude = report.path("longitude");
        if (!("earth".equals(body) || "moon".equals(body)) || !latitude.isNumber() || !longitude.isNumber()) {
            return null;
        }
        double lat = latitude.asDouble(), lon = longitude.asDouble();
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return null;
        return siteKey(body, lat, lon);
    }

    static SiteKey siteKey(String body, double latitude, double longitude) {
        String normalizedBody = body.toLowerCase(Locale.ROOT);
        return new SiteKey(normalizedBody, normalizeLat(latitude), normalizeLon(normalizedBody, longitude));
    }

    static void printSavedReport(JsonNode record) {
        JsonNode output = record.path("report_output");
        if (!output.isObject()) {
            System.out.println(record.path("data").toPrettyString());
            return;
        }
        System.out.println("Using saved report from site-reports.json");
        System.out.println(output.path("title").asText("SITE REPORT"));
        System.out.println(output.path("summary").asText());
        JsonNode sections = output.path("metric_sections");
        if (sections.isArray()) {
            for (JsonNode group : sections) {
                if (group.path("label").asText().startsWith("Earth-analog match priority")) continue;
                section(group.path("label").asText("Measurements"));
                JsonNode measurements = group.path("measurements");
                if (measurements.isArray()) {
                    for (JsonNode metric : measurements) {
                        line(metric.path("label").asText("Measurement"), metric.path("value").asText(NA));
                    }
                }
            }
        }
        printReportContext("Additional context", output.path("additional_context"));
        JsonNode sources = output.path("source_status");
        System.out.println("\nSource status");
        if (sources.isArray()) {
            for (JsonNode source : sources) {
                System.out.println("- " + source.path("name").asText("Source") + ": "
                        + source.path("state").asText(NA) + " — " + source.path("status").asText(NA));
            }
        }
        if (output.path("recommendation").isTextual()) {
            line("Earth analog matching recommendation", output.path("recommendation").asText());
        }
    }

    static ObjectNode emptyReportStore() {
        ObjectNode root = JSON.createObjectNode();
        root.put("v", 3);
        root.putArray("reports");
        return root;
    }

    public static ObjectNode earthData(Terrain terrain, Source geology, Source soil, Source power, Source weather, Source air, Source gbif, Source osm, LocalDate start, LocalDate end) {
        return earthData(Double.NaN, Double.NaN, terrain, geology, soil, power, weather, air, gbif, osm, null, null, start, end);
    }

    public static ObjectNode earthData(double lat, double lon, Terrain terrain, Source geology, Source soil,
                                       Source power, Source weather, Source air, Source gbif, Source osm,
                                       Source emit, Source mrds, LocalDate start, LocalDate end) {
        return earthData(lat, lon, terrain, geology, soil, power, weather, air, gbif, osm, emit, mrds, null, start, end);
    }

    static ObjectNode earthData(double lat, double lon, Terrain terrain, Source geology, Source soil,
                                Source power, Source weather, Source air, Source gbif, Source osm,
                                Source emit, Source mrds, Source geomagnetic,
                                LocalDate start, LocalDate end) {
        ObjectNode d = JSON.createObjectNode();
        putText(d, "geo", surfaceGeology(lat, geology));
        putText(d, "soil", soilSummary(soil, lat));
        putText(d, "min", directMineralogyStatus(emit, lat));
        putText(d, "emit", emitCoverage(emit, lat));
        if (terrain != null && terrain.ok()) {
            d.put("elev", round2(terrain.elevation));
            d.put("relief", round2(terrain.relief));
            d.put("slope", round2(terrain.slope));
            d.put("aspect", round2(terrain.aspect));
            d.put("rough", round2(terrain.roughness));
            if (Double.isFinite(terrain.curvature)) d.put("curv", round6(terrain.curvature)); else putText(d, "curv", NA);
            if (Double.isFinite(lat)) d.put("normalg", round2(normalGravity(lat, terrain.elevation))); else putText(d, "normalg", NA);
        } else {
            putText(d, "elev", NA); putText(d, "relief", NA); putText(d, "slope", NA); putText(d, "aspect", NA); putText(d, "rough", NA);
            putText(d, "curv", NA); putText(d, "normalg", NA);
        }
        putText(d, "crater", NA);

        JsonNode cur = weather == null || weather.json == null ? null : weather.json.path("current");
        putNum(d, "temp", cur, "temperature_2m");
        putNum(d, "rh", cur, "relative_humidity_2m");
        putNum(d, "dew", cur, "dew_point_2m");
        putNum(d, "pressure", cur, "surface_pressure");
        putNum(d, "cloud", cur, "cloud_cover");
        putOptionalNum(d, "rh_forecast", hourlyMeanFuture(weather == null ? null : weather.json, "relative_humidity_2m"));
        putOptionalNum(d, "dew_forecast", hourlyMeanFuture(weather == null ? null : weather.json, "dew_point_2m"));
        putOptionalNum(d, "cloud_avg", hourlyMeanFuture(weather == null ? null : weather.json, "cloud_cover"));
        Double tmin = min(series(power == null ? null : power.json, "T2M_MIN"));
        Double tmax = max(series(power == null ? null : power.json, "T2M_MAX"));
        if (tmin != null) d.put("tmin", round2(tmin)); else putText(d, "tmin", NA);
        if (tmax != null) d.put("tmax", round2(tmax)); else putText(d, "tmax", NA);
        putOptionalNum(d, "tmean", avg(series(power == null ? null : power.json, "T2M")));
        putOptionalNum(d, "dtr", averageDailyRange(series(power == null ? null : power.json, "T2M_MAX"),
                series(power == null ? null : power.json, "T2M_MIN")));
        putOptionalNum(d, "skin", avg(series(power == null ? null : power.json, "TS")));
        putOptionalNum(d, "rh_avg", avg(series(power == null ? null : power.json, "RH2M")));
        putOptionalNum(d, "pressure_avg", avg(series(power == null ? null : power.json, "PS")));
        putOptionalNum(d, "dew_avg", avg(series(power == null ? null : power.json, "T2MDEW")));
        putText(d, "tday", dailyTemperature(weather == null ? null : weather.json));
        Double solar = avg(series(power == null ? null : power.json, "ALLSKY_SFC_SW_DWN"));
        if (solar != null) d.put("solar", round2(solar)); else putText(d, "solar", NA);
        putText(d, "sun_parts", solarComponents(weather == null ? null : weather.json));
        putText(d, "sunlight", sunlight(weather == null ? null : weather.json, lat, lon));
        JsonNode uvNode = weather == null || weather.json == null ? null : weather.json.path("daily").path("uv_index_max");
        if (uvNode != null && uvNode.isArray() && !uvNode.isEmpty() && uvNode.get(0).isNumber()) d.put("uv", round2(uvNode.get(0).asDouble()));
        else putText(d, "uv", NA);

        Double precip = sum(series(power == null ? null : power.json, "PRECTOTCORR"));
        if (precip != null) d.put("precip", round2(precip)); else putText(d, "precip", NA);
        Double forecastSnowfall = firstNumber(weather == null || weather.json == null
                ? null : weather.json.path("daily").path("snowfall_sum"));
        if (forecastSnowfall != null) d.put("snow", round2(forecastSnowfall));
        else if (cur != null && cur.path("snowfall").isNumber()) d.put("snow", round2(cur.path("snowfall").asDouble()));
        else putText(d, "snow", NA);
        putOptionalNum(d, "snow_d", hourlyValueAtOrAfter(weather == null ? null : weather.json, "snow_depth"));
        putText(d, "soiltemp", soilTemperatureProfile(weather == null ? null : weather.json));
        putText(d, "soilmoist", soilMoistureProfile(weather == null ? null : weather.json));
        putText(d, "et0", forecastEt0(weather == null ? null : weather.json));
        putText(d, "aridity", precipitationEt0Ratio(totalForecastPrecip(weather == null ? null : weather.json),
                totalForecastEt0(weather == null ? null : weather.json)));
        putText(d, "water", compactFeatures(osm == null ? null : osm.json, Set.of("water", "spring", "hot_spring", "waterway")));
        putText(d, "pfrost", Double.isFinite(lat) ? permafrostCoverage(lat) : NA);
        putText(d, "mrds", Double.isFinite(lat) && Double.isFinite(lon) ? mrdsSummary(mrds, lat, lon) : NA);

        putNum(d, "wind", cur, "wind_speed_10m");
        putNum(d, "wdir", cur, "wind_direction_10m");
        putNum(d, "gust", cur, "wind_gusts_10m");
        putOptionalNum(d, "wind100", hourlyMeanFuture(weather == null ? null : weather.json, "wind_speed_100m"));
        Double wmax = max(series(power == null ? null : power.json, "WS10M_MAX"));
        if (wmax != null) d.put("wmax", round2(wmax)); else putText(d, "wmax", NA);
        putOptionalNum(d, "wdir_avg", circularMean(series(power == null ? null : power.json, "WD10M")));
        JsonNode airCur = air == null || air.json == null ? null : air.json.path("current");
        putNum(d, "pm10", airCur, "pm10");
        putNum(d, "dust", airCur, "dust");
        putNum(d, "aod", airCur, "aerosol_optical_depth");
        putNum(d, "pm25", airCur, "pm2_5");
        putNum(d, "ozone", airCur, "ozone");
        putNum(d, "aqi", airCur, "us_aqi");
        putText(d, "eros", NA);

        if (gbif != null && gbif.json != null && gbif.json.path("count").isNumber()) d.put("gbif", gbif.json.path("count").asLong());
        else putText(d, "gbif", NA);
        putText(d, "bio", NA);
        putText(d, "cave", compactFeatures(osm == null ? null : osm.json, Set.of("cave_entrance", "adit")));
        putText(d, "sub", NA);

        if (terrain != null && terrain.ok()) {
            d.put("tscreen", terrain.slope >= 15 || terrain.roughness >= 10 ? "caution" : "ok");
        } else putText(d, "tscreen", NA);
        String screen = screening(weather == null ? null : weather.json, air == null ? null : air.json);
        d.put("wscreen", NA.equals(screen) ? NA : (screen.startsWith("Caution") ? "caution" : "ok"));
        String gate = earthGate(terrain, power, weather);
        d.put("gate", gate.startsWith("INCOMPLETE") ? "incomplete" : "insuff");
        d.put("go", "nogo");
        d.set("metrics", earthMetrics(lat, lon, terrain, geology, soil, power, weather, air, gbif, osm,
                emit, mrds, geomagnetic, start, end));
        return d;
    }

    public static ObjectNode moonData(Source lola, Source diviner, Source lroc, Source lend, Source lamp, Source minirf, Source m3, Source grail) {
        return moonData(lola, diviner, lroc, lend, lamp, minirf, m3, grail, null, null, null, Double.NaN, Double.NaN);
    }

    public static ObjectNode moonData(Source lola, Source diviner, Source lroc, Source lend, Source lamp,
                                      Source minirf, Source m3, Source grail, Source lolaShots,
                                      Source divinerShots, Source horizons, double lat, double lon) {
        return moonData(lola, diviner, lroc, lend, lamp, minirf, m3, grail, lolaShots,
                divinerShots, horizons, null, lat, lon);
    }

    public static ObjectNode moonData(Source lola, Source diviner, Source lroc, Source lend, Source lamp,
                                      Source minirf, Source m3, Source grail, Source lolaShots,
                                      Source divinerShots, Source horizons, Source craterCatalog,
                                      double lat, double lon) {
        return moonData(lola, diviner, lroc, lend, lamp, minirf, m3, grail, lolaShots,
                divinerShots, horizons, craterCatalog, null, lat, lon);
    }

    public static ObjectNode moonData(Source lola, Source diviner, Source lroc, Source lend, Source lamp,
                                      Source minirf, Source m3, Source grail, Source lolaShots,
                                      Source divinerShots, Source horizons, Source craterCatalog,
                                      Source lunarGeology, double lat, double lon) {
        return moonData(lola, diviner, lroc, lend, lamp, minirf, m3, grail, lolaShots,
                divinerShots, horizons, craterCatalog, lunarGeology, null, lat, lon);
    }

    public static ObjectNode moonData(Source lola, Source diviner, Source lroc, Source lend, Source lamp,
                                      Source minirf, Source m3, Source grail, Source lolaShots,
                                      Source divinerShots, Source horizons, Source craterCatalog,
                                      Source lunarGeology, Source grailSamples,
                                      double lat, double lon) {
        return moonData(lola, diviner, lroc, lend, lamp, minirf, m3, grail, lolaShots, divinerShots,
                horizons, craterCatalog, lunarGeology, grailSamples, null, null, null, lat, lon);
    }

    public static ObjectNode moonData(Source lola, Source diviner, Source lroc, Source lend, Source lamp,
                                      Source minirf, Source m3, Source grail, Source lolaShots,
                                      Source divinerShots, Source horizons, Source craterCatalog,
                                      Source lunarGeology, Source grailSamples,
                                      Source lolaDem, double lat, double lon) {
        return moonData(lola, diviner, lroc, lend, lamp, minirf, m3, grail, lolaShots, divinerShots,
                horizons, craterCatalog, lunarGeology, grailSamples, lolaDem,
                null, null, lat, lon);
    }

    static ObjectNode moonData(Source lola, Source diviner, Source lroc, Source lend, Source lamp,
                               Source minirf, Source m3, Source grail, Source lolaShots,
                               Source divinerShots, Source horizons, Source craterCatalog,
                               Source lunarGeology, Source grailSamples,
                               Source lolaDem, Source lunarMagnetic, Source lunarComposition,
                               double lat, double lon) {
        ObjectNode d = JSON.createObjectNode();
        putCov(d, "m3", m3);
        putCov(d, "minirf", minirf);
        putText(d, "comp", NA);
        putCov(d, "lola", lola);
        putCov(d, "lroc", lroc);
        LunarTerrain terrain = lunarTerrain(lolaShots, lat, lon);
        if (terrain.count > 0) {
            d.put("lola_n", terrain.count);
            d.put("lola_local_n", terrain.localCount);
            d.put("lola_box_halfwidth_deg", terrain.searchHalfWidthDeg);
            d.put("lola_local_radius_m", 1_000);
            d.put("elev", round2(terrain.nearestElevation));
            d.put("elev_m", round2(terrain.nearestElevation));
            d.put("elev_dist", round2(terrain.nearestDistance));
            d.put("elev_dist_m", round2(terrain.nearestDistance));
            putOptionalNum(d, "relief", terrain.relief);
            putOptionalNum(d, "relief_m", terrain.relief);
            putOptionalNum(d, "slope", terrain.slope);
            putOptionalNum(d, "slope_deg", terrain.slope);
            putOptionalNum(d, "aspect", terrain.aspect);
            putOptionalNum(d, "aspect_deg", terrain.aspect);
            putOptionalNum(d, "rough", terrain.roughness);
            putOptionalNum(d, "rough_m", terrain.roughness);
            d.put("lola_reference_radius_m", 1_737_400);
            d.set("lola_samples", lunarLolaSamples(lolaShots, lat, lon));
        } else {
            putText(d, "topo", NA);
        }
        Terrain demTerrain = lunarLdemTerrain(lolaDem, lat);
        double demElevation = lunarLdemCenterElevation(lolaDem);
        if (Double.isFinite(demElevation)) {
            d.put("lola_dem_elevation_m", round2(demElevation));
            d.set("lola_dem_elevations_m", lolaDem.json.path("elevations_m"));
            d.set("lola_dem_center_latitude_deg", lolaDem.json.path("cell_center_latitude_deg"));
            d.set("lola_dem_center_longitude_east_deg", lolaDem.json.path("cell_center_longitude_east_deg"));
            d.put("elev", round2(demElevation));
            d.put("elev_m", round2(demElevation));
            d.put("lola_reference_radius_m", 1_737_400);
        }
        if (demTerrain.ok()) {
            d.put("lola_dem_slope_deg", round2(demTerrain.slope));
            d.put("lola_dem_aspect_deg", round2(demTerrain.aspect));
            d.put("lola_dem_roughness_m", round2(demTerrain.roughness));
            d.put("lola_dem_relief_m", round2(demTerrain.relief));
            d.put("lola_dem_curvature_km", demTerrain.curvature * 1_000);
            d.put("slope", round2(demTerrain.slope));
            d.put("slope_deg", round2(demTerrain.slope));
            d.put("aspect", round2(demTerrain.aspect));
            d.put("aspect_deg", round2(demTerrain.aspect));
            d.put("rough", round2(demTerrain.roughness));
            d.put("rough_m", round2(demTerrain.roughness));
            d.put("relief", round2(demTerrain.relief));
            d.put("relief_m", round2(demTerrain.relief));
        }
        putCov(d, "diviner", diviner);
        putCov(d, "lamp", lamp);
        putText(d, "temp", NA);
        DivinerStats thermalStats = divinerStats(divinerShots);
        if (thermalStats.recordCount > 0) {
            d.put("diviner_n", thermalStats.recordCount);
            d.put("diviner_channel_mask", "ffffftttt");
            d.put("diviner_temperature_units", "brightness_temperature_k");
            d.put("diviner_tb_n", thermalStats.validTemperatureCount);
            d.put("diviner_qca0_n", thermalStats.calibrationBestCount);
            d.put("diviner_qge0_n", thermalStats.geometryBestCount);
            d.put("diviner_qmi0_n", thermalStats.miscellaneousBestCount);
            d.put("diviner_qall0_n", thermalStats.allFlagsBestCount);
            d.put("diviner_qca_flags", thermalStats.qcaFlagCounts);
            d.put("diviner_qge_flags", thermalStats.qgeFlagCounts);
            d.put("diviner_qmi_flags", thermalStats.qmiFlagCounts);
            if (!thermalStats.firstDate.isEmpty()) {
                d.put("diviner_start", thermalStats.firstDate);
                d.put("diviner_end", thermalStats.lastDate);
            }
            for (DivinerChannelStats channel : thermalStats.channels) {
                String prefix = "div" + channel.channel;
                d.put(prefix + "_n", channel.count);
                d.put(prefix + "_min_k", round2(channel.minKelvin));
                d.put(prefix + "_max_k", round2(channel.maxKelvin));
                d.put(prefix + "_mean_k", round2(channel.meanKelvin));
                d.put(prefix + "_qca0_qmi0_n", channel.qcaQmiZeroCount);
                putOptionalNum(d, prefix + "_qca0_qmi0_min_k", channel.qcaQmiZeroMinKelvin);
                putOptionalNum(d, prefix + "_qca0_qmi0_max_k", channel.qcaQmiZeroMaxKelvin);
                putOptionalNum(d, prefix + "_qca0_qmi0_mean_k", channel.qcaQmiZeroMeanKelvin);
            }
            d.set("diviner_observations", lunarDivinerSamples(divinerShots));
        }
        double[] sun = lunarSunAngles(horizons);
        if (sun != null) {
            d.put("sun_az", round2(sun[0]));
            d.put("sun_az_deg", round2(sun[0]));
            d.put("sun_alt", round2(sun[1]));
            d.put("sun_alt_deg", round2(sun[1]));
            List<LunarEphemerisSample> ephemeris = lunarEphemerisSamples(horizons);
            if (!ephemeris.isEmpty()) d.put("solar_distance_au", round2(ephemeris.get(0).solarDistanceAu));
            double[] irradiance = lunarSolarIrradiance(horizons);
            if (irradiance != null) {
                d.put("solar_normal_w_m2", round2(irradiance[0]));
                d.put("solar_horizontal_w_m2", round2(irradiance[1]));
                d.put("solar_diffuse_w_m2", 0);
            }
            putOptionalNum(d, "sunlight_hours_cycle", lunarSunlightHours(horizons));
            putOptionalNum(d, "solar_cycle_days", lunarSolarCycleDays(horizons));
        } else putText(d, "illum", NA);
        double gravityReferenceElevation = finite(terrain.nearestElevation) ? terrain.nearestElevation : 0;
        d.put("gravity_ref_elevation_m", round2(gravityReferenceElevation));
        double gravityReference = lunarReferenceGravity(lat, gravityReferenceElevation);
        putOptionalNum(d, "gravity_ref_m_s2", gravityReference);
        putOptionalNum(d, "gravity_ref_mgal", gravityReference * 100_000);
        if (grailSamples != null && grailSamples.state == SourceState.DATA && grailSamples.json != null) {
            putNum(d, "grail_anomaly_mgal", grailSamples.json, "anomaly_mgal");
            putNum(d, "grail_bouguer_mgal", grailSamples.json, "bouguer_disturbance_mgal");
            if (grailSamples.json.path("grid_row").isNumber()) d.set("grail_grid_row", grailSamples.json.path("grid_row"));
            if (grailSamples.json.path("grid_column").isNumber()) d.set("grail_grid_column", grailSamples.json.path("grid_column"));
        }
        ObjectNode nearestCrater = lunarNearestNamedCrater(craterCatalog, lat, lon);
        if (!nearestCrater.isEmpty()) d.set("nearest_named_crater", nearestCrater);
        ObjectNode mappedUnit = lunarGeologySample(lunarGeology, lat, lon);
        if (!mappedUnit.isEmpty()) d.set("mapped_unit", mappedUnit.path("mapped_unit"));
        if (lunarMagnetic != null && lunarMagnetic.state == SourceState.DATA && lunarMagnetic.json != null) {
            d.set("lunar_magnetic", lunarMagnetic.json);
        }
        if (lunarComposition != null && lunarComposition.state == SourceState.DATA && lunarComposition.json != null) {
            d.set("lunar_composition", lunarComposition.json);
        }
        putCov(d, "lend", lend);
        putText(d, "ice", NA);
        d.put("atmo", "exosphere");
        d.put("weather", "not_applicable");
        putText(d, "dust", NA);
        d.put("bio", "not_applicable");
        d.put("cave", (lroc != null && lroc.state == SourceState.DATA) || (minirf != null && minirf.state == SourceState.DATA) ? "ok" : NA);
        putText(d, "tube", NA);
        d.put("tin", (lola != null && lola.state == SourceState.DATA) || (lroc != null && lroc.state == SourceState.DATA) ? "ok" : NA);
        d.put("therm", (diviner != null && diviner.state == SourceState.DATA) || (lamp != null && lamp.state == SourceState.DATA) || (lend != null && lend.state == SourceState.DATA) ? "ok" : NA);
        d.put("go", "nogo");
        d.set("metrics", moonMetrics(d, lat, lon, divinerShots, horizons));
        return d;
    }

    static void putCov(ObjectNode d, String key, Source source) {
        if (source == null) { putText(d, key, NA); return; }
        if (source.state == SourceState.DATA) d.put(key, "ok");
        else if (source.state == SourceState.EMPTY) d.put(key, "empty");
        else putText(d, key, NA);
    }

    static void putText(ObjectNode d, String key, String value) {
        d.put(key, value == null || value.isBlank() ? NA : value);
    }

    static void putNum(ObjectNode d, String key, JsonNode parent, String field) {
        if (parent != null && parent.path(field).isNumber()) d.put(key, round2(parent.path(field).asDouble()));
        else putText(d, key, NA);
    }

    static String compactFeatures(JsonNode node, Set<String> wanted) {
        JsonNode e = node == null ? null : node.path("elements");
        if (e == null || !e.isArray()) return NA;
        int n = 0;
        for (JsonNode x : e) {
            JsonNode t = x.path("tags");
            for (String w : wanted) {
                if (t.has(w) || w.equals(t.path("natural").asText()) || w.equals(t.path("man_made").asText())) { n++; break; }
            }
        }
        return n == 0 ? "0 mapped records" : Integer.toString(n);
    }


    static double round2(double x) { return Math.round(x * 100.0) / 100.0; }

    public static double normalizeLat(double lat) {
        return lat == 0.0 ? 0.0 : lat;
    }

    public static double normalizeLon(String body, double lon) {
        if (lon == 0.0) return 0.0;
        if ("moon".equalsIgnoreCase(body)) {
            double l = ((lon % 360) + 360) % 360;
            return l == 0.0 ? 0.0 : l;
        }
        return lon;
    }

    public static void writeReportStore(ObjectNode root) throws IOException {
        Path abs = reportFile.toAbsolutePath();
        Path parent = abs.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "site-reports-", ".tmp");
        try {
            try (JsonGenerator generator = JSON.getFactory().createGenerator(
                    Files.newBufferedWriter(temporary, StandardCharsets.UTF_8))) {
                generator.setPrettyPrinter(reportPrettyPrinter());
                generator.writeTree(root);
            }
            try { Files.move(temporary, reportFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, reportFile, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
        }
    }

    static DefaultPrettyPrinter reportPrettyPrinter() {
        return new DefaultPrettyPrinter().withArrayIndenter(new DefaultIndenter("  ", "\n"));
    }

    public static String elevationUrl(double lat, double lon) {
        double latStep = 100d / 111_320d, lonStep = 100d / (111_320d * Math.max(.01, Math.cos(Math.toRadians(lat))));
        List<String> lats = new ArrayList<>(), lons = new ArrayList<>();
        for (int row = -2; row <= 2; row++) {
            for (int col = -2; col <= 2; col++) {
                double[] point = normalizeElevationPoint(lat - row * latStep, lon + col * lonStep);
                lats.add(String.format(Locale.US, "%.6f", point[0]));
                lons.add(String.format(Locale.US, "%.6f", point[1]));
            }
        }
        return "https://api.open-meteo.com/v1/elevation?latitude=" + String.join(",", lats) + "&longitude=" + String.join(",", lons);
    }

    static double[] normalizeElevationPoint(double lat, double lon) {
        while (lat > 90 || lat < -90) {
            if (lat > 90) lat = 180 - lat;
            else lat = -180 - lat;
            lon += 180;
        }
        lon = ((lon + 180) % 360 + 360) % 360 - 180;
        return new double[]{lat, lon};
    }

    static String gbifUrl(double lat, double lon) {
        double south = Math.max(-90, lat - .1), north = Math.min(90, lat + .1);
        double west = Math.max(-180, lon - .1), east = Math.min(180, lon + .1);
        return "https://api.gbif.org/v1/occurrence/search?decimalLatitude=" + coordinate(south) + "," + coordinate(north)
                + "&decimalLongitude=" + coordinate(west) + "," + coordinate(east) + "&limit=0&facet=kingdomKey&facetLimit=5";
    }

    static String soilGridsUrl(double lat, double lon) {
        return "https://rest.isric.org/soilgrids/v2.0/properties/query?lon=" + coordinate(lon) + "&lat=" + coordinate(lat)
                + "&property=sand&property=clay&property=silt&property=soc&property=phh2o&property=bdod"
                + "&property=cfvo&property=cec&property=nitrogen&depth=0-5cm&value=mean";
    }

    static String powerUrl(double lat, double lon, LocalDate start, LocalDate end) {
        String parameters = "T2M,T2M_MAX,T2M_MIN,T2MDEW,T2MWET,TS,RH2M,PS,WS10M,WS10M_MAX,WS10M_MIN,WD10M,PRECTOTCORR,ALLSKY_SFC_SW_DWN,ALLSKY_SFC_LW_DWN";
        return "https://power.larc.nasa.gov/api/temporal/daily/point?parameters=" + parameters
                + "&community=RE&longitude=" + coordinate(lon) + "&latitude=" + coordinate(lat)
                + "&start=" + date(start) + "&end=" + date(end) + "&format=JSON";
    }

    static String weatherUrl(double lat, double lon) {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + coordinate(lat) + "&longitude=" + coordinate(lon)
                + "&timezone=auto&forecast_days=2"
                + "&current=temperature_2m,relative_humidity_2m,dew_point_2m,precipitation,snowfall,cloud_cover,surface_pressure,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m"
                + "&hourly=temperature_2m,relative_humidity_2m,dew_point_2m,cloud_cover,soil_temperature_0cm,soil_temperature_6cm,soil_temperature_18cm,soil_temperature_54cm"
                + ",soil_moisture_0_to_1cm,soil_moisture_1_to_3cm,soil_moisture_3_to_9cm,soil_moisture_9_to_27cm,soil_moisture_27_to_81cm"
                + ",snow_depth,visibility,uv_index,shortwave_radiation,direct_radiation,diffuse_radiation,sunshine_duration"
                + ",wind_speed_10m,wind_speed_100m,wind_direction_10m,wind_direction_100m,et0_fao_evapotranspiration"
                + "&daily=daylight_duration,sunshine_duration,uv_index_max,shortwave_radiation_sum,temperature_2m_max"
                + ",temperature_2m_min,temperature_2m_mean,precipitation_sum,et0_fao_evapotranspiration,snowfall_sum,wind_gusts_10m_max";
    }

    static String airQualityUrl(double lat, double lon) {
        return "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + coordinate(lat)
                + "&longitude=" + coordinate(lon)
                + "&current=pm10,pm2_5,dust,aerosol_optical_depth,us_aqi,ozone&forecast_days=1";
    }

    static String emitUrl(double lat, double lon) {
        return "https://cmr.earthdata.nasa.gov/search/granules.json?short_name=EMITL2BMIN&point="
                + coordinate(lon) + "," + coordinate(lat) + "&page_size=10&sort_key=-start_date";
    }

    static String mrdsUrl(double lat, double lon) {
        double radius = 10_000d;
        double latDelta = radius / 111_320d;
        double cosine = Math.max(.01, Math.abs(Math.cos(Math.toRadians(lat))));
        double lonDelta = Math.min(180d, radius / (111_320d * cosine));
        double south = Math.max(-90, lat - latDelta), north = Math.min(90, lat + latDelta);
        double west = Math.max(-180, lon - lonDelta), east = Math.min(180, lon + lonDelta);
        return "https://mrdata.usgs.gov/services/wfs/mrds?service=WFS&version=1.1.0&request=GetFeature"
                + "&typeName=mrds&bbox=" + coordinate(south) + "," + coordinate(west) + ","
                + coordinate(north) + "," + coordinate(east) + ",urn:ogc:def:crs:EPSG::4326&maxFeatures=100";
    }

    static String coordinate(double value) {
        return String.format(Locale.US, "%.6f", value);
    }

    static String emitCoverage(Source source) {
        JsonNode entries = source == null || source.json == null ? null : source.json.path("feed").path("entry");
        if (entries == null || !entries.isArray()) return NA;
        if (entries.isEmpty()) return "No EMIT L2B scene returned at this point (not evidence of no minerals)";
        String date = entries.get(0).path("time_start").asText("");
        if (date.length() >= 10) date = date.substring(0, 10);
        else date = "date unavailable";
        String count = entries.size() >= 10 ? "10+" : Integer.toString(entries.size());
        return count + " EMIT L2B scene(s) intersect; latest " + date
                + " (60 m protected product; pixel mineral IDs not extracted here)";
    }

    static String directMineralogyStatus(Source source) {
        JsonNode entries = source == null || source.json == null ? null : source.json.path("feed").path("entry");
        return source != null && source.state == SourceState.DATA && entries != null && entries.isArray() && !entries.isEmpty()
                ? "EMIT scene(s) found; pixel mineral IDs have not been extracted"
                : NA;
    }

    static String permafrostCoverage(double lat) {
        if (lat > 30) {
            return "ESA CCI v5.0 annual 1 km model raster covers north of 30°N (1997–2023); site pixel not sampled";
        }
        if (lat <= -60) {
            return "ESA CCI has an Antarctica permafrost product; site pixel not sampled";
        }
        return "ESA CCI northern product does not cover this latitude; local permafrost value unavailable";
    }

    static String mrdsSummary(Source source, double lat, double lon) {
        if (source == null || source.json == null || !source.json.path("features").isArray()) return NA;
        List<JsonNode> nearby = new ArrayList<>();
        for (JsonNode feature : source.json.path("features")) {
            if (!feature.path("latitude").isNumber() || !feature.path("longitude").isNumber()) continue;
            double distance = distanceMeters(lat, lon, feature.path("latitude").asDouble(), feature.path("longitude").asDouble());
            if (distance <= 10_000) nearby.add(feature);
        }
        nearby.sort(Comparator.comparingDouble(feature ->
                distanceMeters(lat, lon, feature.path("latitude").asDouble(), feature.path("longitude").asDouble())));
        if (nearby.isEmpty()) {
            return "0 USGS MRDS catalog sites within 10 km (not evidence of absence; systematic updates ceased in 2011)";
        }
        List<String> details = new ArrayList<>();
        for (JsonNode feature : nearby) {
            double distanceKm = distanceMeters(lat, lon, feature.path("latitude").asDouble(), feature.path("longitude").asDouble()) / 1000d;
            String name = safeLabel(feature.path("site_name").asText("unnamed site"));
            String status = safeLabel(feature.path("dev_stat").asText(""));
            String commodities = mrdsCommodities(feature.path("code_list").asText(""));
            details.add(name + " [" + (status.isBlank() ? "status unknown" : "catalog status: " + status)
                    + (commodities.isBlank() ? "" : "; commodities: " + commodities)
                    + "; " + n(distanceKm) + " km]");
        }
        String count = nearby.size() >= 100 ? "100+" : Integer.toString(nearby.size());
        String capped = source.json.path("features").size() >= 100 ? " Query reached its 100-record cap." : "";
        return count + " historical USGS MRDS site(s) within 10 km (systematic updates ceased in 2011; not current activity or reserve evidence):"
                + capped + " "
                + truncate(details, 3);
    }

    static String mrdsCommodities(String codes) {
        if (codes == null || codes.isBlank()) return "";
        List<String> commodities = new ArrayList<>();
        for (String code : codes.trim().toUpperCase(Locale.ROOT).split("\\s+")) {
            commodities.add(switch (code) {
                case "ZN" -> "Zn";
                case "PB" -> "Pb";
                case "CU" -> "Cu";
                case "AG" -> "Ag";
                case "CO" -> "Co";
                case "AU" -> "Au";
                default -> code;
            });
        }
        return String.join(", ", commodities);
    }

    static String safeLabel(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("\\p{Cntrl}", " ").trim();
        return cleaned.length() > 80 ? cleaned.substring(0, 77) + "..." : cleaned;
    }

    public static String overpassQuery(double lat, double lon) {
        String a = String.format(Locale.US, "%.6f", lat), b = String.format(Locale.US, "%.6f", lon);
        String near5 = "(around:5000," + a + "," + b + ")", near1 = "(around:1500," + a + "," + b + ")";
        return "[out:json][timeout:6];("
                + "nwr[\"natural\"=\"cave_entrance\"]" + near5 + ";"
                + "nwr[\"man_made\"=\"adit\"]" + near5 + ";"
                + "nwr[\"natural\"=\"spring\"]" + near1 + ";"
                + "nwr[\"natural\"=\"hot_spring\"]" + near1 + ";"
                + "nwr[\"natural\"=\"water\"]" + near1 + ";"
                + "way[\"waterway\"=\"river\"]" + near1 + ";"
                + "way[\"waterway\"=\"stream\"]" + near1 + ";"
                + ");out tags center 50;";
    }

    public static String overpassUrl(double lat, double lon) {
        return OVERPASS_ENDPOINTS[0] + "?data=" + URLEncoder.encode(overpassQuery(lat, lon), StandardCharsets.UTF_8);
    }

    static Terrain terrain(JsonNode node) {
        JsonNode values = node == null ? null : node.path("elevation");
        if (values == null || !values.isArray() || values.size() != 25) return Terrain.none();
        double[] z = new double[25];
        for (int i = 0; i < 25; i++) { if (!values.get(i).isNumber()) return Terrain.none(); z[i] = values.get(i).asDouble(); }
        return terrainFromElevations(z, 100d);
    }

    static double lunarLdemCenterElevation(Source source) {
        JsonNode rows = source == null || source.state != SourceState.DATA || source.json == null
                ? null : source.json.path("elevations_m");
        JsonNode center = rows != null && rows.isArray() && rows.size() == 5
                && rows.path(2).isArray() && rows.path(2).size() == 5 ? rows.path(2).path(2) : null;
        return center != null && center.isNumber() && Double.isFinite(center.asDouble())
                ? center.asDouble() : Double.NaN;
    }

    static Terrain lunarLdemTerrain(Source source, double siteLat) {
        JsonNode rows = source == null || source.state != SourceState.DATA || source.json == null
                ? null : source.json.path("elevations_m");
        if (rows == null || !rows.isArray() || rows.size() != 5 || !Double.isFinite(siteLat)
                || siteLat < -90 || siteLat > 90) return Terrain.none();
        double[] elevations = new double[25];
        for (int row = 0; row < 5; row++) {
            JsonNode samples = rows.get(row);
            if (!samples.isArray() || samples.size() != 5) return Terrain.none();
            for (int column = 0; column < 5; column++) {
                JsonNode sample = samples.get(column);
                if (!sample.isNumber() || !Double.isFinite(sample.asDouble())) return Terrain.none();
                elevations[row * 5 + column] = sample.asDouble();
            }
        }
        double eastWestSpacing = LUNAR_LOLA_DEM_PIXEL_SIZE_M * Math.cos(Math.toRadians(siteLat));
        if (eastWestSpacing < 0.01) return Terrain.none();
        return terrainFromElevations(elevations, eastWestSpacing, LUNAR_LOLA_DEM_PIXEL_SIZE_M);
    }

    static Terrain terrainFromElevations(double[] z, double spacing) {
        return terrainFromElevations(z, spacing, spacing);
    }

    static Terrain terrainFromElevations(double[] z, double eastWestSpacing, double northSouthSpacing) {
        if (z == null || z.length != 25 || !Double.isFinite(eastWestSpacing) || eastWestSpacing <= 0
                || !Double.isFinite(northSouthSpacing) || northSouthSpacing <= 0) return Terrain.none();
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum = 0;
        for (double x : z) { if (!Double.isFinite(x)) return Terrain.none(); min = Math.min(min, x); max = Math.max(max, x); sum += x; }
        double mean = sum / 25, variance = 0; for (double x : z) variance += (x - mean) * (x - mean);
        double dx = (z[13] - z[11]) / (2 * eastWestSpacing), dy = (z[7] - z[17]) / (2 * northSouthSpacing);
        double dxx = (z[13] - 2 * z[12] + z[11]) / (eastWestSpacing * eastWestSpacing);
        double dyy = (z[17] - 2 * z[12] + z[7]) / (northSouthSpacing * northSouthSpacing);
        double dxy = (z[8] - z[6] - z[18] + z[16]) / (4 * eastWestSpacing * northSouthSpacing);
        double slopeSquared = dx * dx + dy * dy;
        double curvature = ((1 + dy * dy) * dxx - 2 * dx * dy * dxy + (1 + dx * dx) * dyy)
                / (2 * Math.pow(1 + slopeSquared, 1.5));
        return new Terrain(z[12], Math.toDegrees(Math.atan(Math.hypot(dx, dy))),
                (Math.toDegrees(Math.atan2(-dx, dy)) + 360) % 360, max - min,
                Math.sqrt(variance / 25), curvature);
    }

    static ObjectNode report(double lat, double lon, Terrain terrain, Source geology, Source soil, Source power,
                             Source weather, Source air, Source gbif, Source osm, Source emit, Source mrds,
                             Source geomagnetic, List<Source> sources, LocalDate start, LocalDate end,
                             ObjectNode data) {
        String title = String.format(Locale.US, "EARTH SITE REPORT — %.5f, %.5f", lat, lon);
        String summary = "Best-effort public data. / means unavailable or not supportable at point scale; it does not mean absent.";
        ArrayNode context = earthReportContext(lat, lon, terrain, weather, air, gbif, osm, emit, mrds, power);
        ObjectNode readableOutput = readableReportOutput(title, summary, data.path("metrics"), context, sources, null);
        System.out.println(title);
        System.out.println(summary);
        printMetricGroups(data.path("metrics"));
        printReportContext("Additional mapped and operational context", context);
        printSourceStatuses(sources);
        return readableOutput;
    }

    static ArrayNode earthReportContext(double lat, double lon, Terrain terrain, Source weather, Source air,
                                        Source gbif, Source osm, Source emit, Source mrds,
                                        Source power) {
        ArrayNode entries = JSON.createArrayNode();
        addReportContext(entries, "nearby_gbif_occurrence_records", "Nearby GBIF occurrence records",
                gbif(gbif == null ? null : gbif.json));
        addReportContext(entries, "mapped_surface_water_or_spring_features", "Mapped surface-water or spring features",
                features(osm == null ? null : osm.json, Set.of("water", "spring", "hot_spring", "waterway")));
        addReportContext(entries, "mapped_caves_or_adits_within_5km", "Mapped caves or adits within 5 km",
                features(osm == null ? null : osm.json, Set.of("cave_entrance", "adit")));
        addReportContext(entries, "nasa_emit_mineral_map_coverage", "NASA EMIT mineral-map coverage",
                emitCoverage(emit, lat));
        addReportContext(entries, "usgs_mrds_mineral_occurrences_10km", "Known mineral occurrences (USGS MRDS, 10 km)",
                mrdsSummary(mrds, lat, lon));
        addReportContext(entries, "terrain_screening", "Terrain screening", terrain != null && terrain.ok()
                ? (terrain.slope >= 15 || terrain.roughness >= 10 ? "Caution" : "Lower local terrain concern")
                    + "; slope " + degrees(terrain.slope) + ", roughness " + metres(terrain.roughness) : NA);
        addReportContext(entries, "weather_visibility_screening", "Weather / visibility screening",
                screening(weather == null ? null : weather.json, air == null ? null : air.json));
        addReportContext(entries, "data_completeness_gate", "Operational data-completeness gate",
                earthGate(terrain, power, weather));
        addReportContext(entries, "operational_conclusion", "Operational conclusion",
                "NO-GO for safety-critical use until independent, mission-certified analysis verifies this site.");
        return entries;
    }

    static ArrayNode lunarReportContext(Source lola, Source lroc, Source minirf, Source m3,
                                        Source lend, Source lamp, Source diviner, Source grailSamples,
                                        Source magnetic, Source composition, Source lolaDem,
                                        Source lunarGeology, Source craterCatalog) {
        ArrayNode entries = JSON.createArrayNode();
        addReportContext(entries, "lola_topography_product_coverage", "LOLA topography product coverage", pds(lola));
        addReportContext(entries, "lroc_mini_rf_radar_coverage", "LROC imagery / Mini-RF radar coverage",
                combinePds(lroc, minirf));
        addReportContext(entries, "m3_mineral_map_coverage", "M³ mineral-map coverage", pds(m3));
        addReportContext(entries, "lend_lamp_coverage", "LEND hydrogen-proxy coverage / LAMP UV coverage",
                combinePds(lend, lamp));
        addReportContext(entries, "diviner_archive_coverage", "Diviner archive coverage", pds(diviner));
        addReportContext(entries, "grail_gravity_grid_samples", "GRAIL gravity-grid samples",
                grailSamples.state == SourceState.DATA
                        ? "GRGM1200A anomaly and Bouguer grids; nearest cells from 16-pixel/degree PDS rasters (model resolution is coarser than pixel spacing)."
                        : grailSamples.status);
        addReportContext(entries, "lunar_crustal_magnetic_field_grid", "Lunar crustal magnetic field grid",
                magnetic.state == SourceState.DATA
                        ? "LP/Kaguya vector map sampled locally from the PDS static grid at 30 km altitude."
                        : magnetic.status);
        addReportContext(entries, "lunar_prospector_grs_composition_grid", "Lunar Prospector GRS composition grid",
                composition.state == SourceState.DATA
                        ? "High-altitude GRS abundance map; the containing equal-area pixel is sampled locally."
                        : composition.status);
        addReportContext(entries, "lola_gridded_dem", "LOLA gridded DEM",
                lolaDem.state == SourceState.DATA
                        ? "LDEM_128_FLOAT; 128 pixels/degree, 5×5 neighborhood read from the PDS ODE-linked MIT raster."
                        : lolaDem.status);
        addReportContext(entries, "pds_archive_cache_scope", "PDS archive-cache scope",
                "Archive-coverage ages—including product-wide queries and global fallbacks after empty footprint searches—refer to shared product metadata, not site measurements. Local LOLA DEM/track, Diviner, and GRAIL samples are queried separately.");
        addReportContext(entries, "usgs_unified_geologic_map", "USGS unified geologic map",
                lunarGeology.state == SourceState.DATA
                        ? "1:5,000,000 vector map; unit boundaries and attributes are map-scale estimates."
                        : lunarGeology.status);
        addReportContext(entries, "usgs_geology_geometry_caveat", "USGS geology geometry caveat",
                "The 1:5,000,000 map is regional interpretation; returned vectors are simplified for distance estimates and are not site-survey data.");
        addReportContext(entries, "diviner_measurement_caveat", "Diviner measurement caveat",
                "Historical channel-specific brightness temperatures are from orbital overpasses; qca/qmi=0 day/night contrasts retain qge flags and do not represent a complete diurnal or annual cycle.");
        addReportContext(entries, "lola_neighborhood_caveat", "LOLA neighborhood caveat",
                "Terrain metrics prefer a 5×5 LDEM_128_FLOAT grid (~237 m/pixel at the equator); it is pixel-registered, in the DE421 Mean Earth/Polar Axis frame, and may have artifacts at 45° latitude-band joins. LOLA shot samples remain separately listed.");
        addReportContext(entries, "lunar_illumination_method", "Lunar illumination method",
                "JPL Horizons Sun azimuth/elevation sampled every 6 hours over one 29.53-Earth-day synodic cycle; daylight integration uses a flat horizon.");
        addReportContext(entries, "named_crater_catalog", "Named crater catalog",
                craterCatalog.state == SourceState.DATA
                        ? craterCatalog.json.path("craters").size() + " named crater centers from the USGS/IAU Gazetteer; distances use spherical circular-rim estimates."
                        : craterCatalog.status);
        addReportContext(entries, "lunar_magnetic_map_caveat", "Lunar magnetic map caveat",
                "The LP/Kaguya map is a smoothed crustal-field model at 30 km altitude (not a surface measurement); reported vector angles are derived from interpolated local components.");
        addReportContext(entries, "lunar_grs_composition_caveat", "Lunar GRS composition caveat",
                "The 2° equal-area bins come from observations at about 100 km altitude with an effective footprint near 5°; reported values are regional estimates, not site assays.");
        addReportContext(entries, "individual_numeric_samples", "Individual numeric samples",
                "See data.lola_dem_elevations_m, data.lola_samples, data.diviner_observations, data.lunar_magnetic, and data.lunar_composition in the JSON report.");
        addReportContext(entries, "data_completeness_gate", "Operational data-completeness gate",
                "INCOMPLETE / NO-GO: archive coverage and neighborhood samples do not certify a landing site.");
        addReportContext(entries, "operational_conclusion", "Operational conclusion",
                "NO-GO for safety-critical use until independent, mission-certified analysis verifies this site.");
        return entries;
    }

    static void addReportContext(ArrayNode entries, String key, String label, String value) {
        ObjectNode entry = entries.addObject();
        entry.put("key", key);
        entry.put("label", label);
        entry.put("value", value == null || value.isBlank() ? NA : value);
    }

    static void printReportContext(String sectionTitle, JsonNode entries) {
        section(sectionTitle);
        if (entries == null || !entries.isArray()) return;
        for (JsonNode entry : entries) line(entry.path("label").asText(), entry.path("value").asText(NA));
    }

    static ObjectNode readableReportOutput(String title, String summary, JsonNode metrics,
                                           ArrayNode additionalContext, List<Source> sources,
                                           String recommendation) {
        ObjectNode output = JSON.createObjectNode();
        output.put("format_version", 1);
        output.put("title", title);
        output.put("summary", summary);
        output.set("metric_sections", readableMetricSections(metrics));
        output.set("additional_context", additionalContext.deepCopy());
        output.set("source_status", sourceStatusRecords(sources));
        if (recommendation != null && !recommendation.isBlank()) output.put("recommendation", recommendation);
        return output;
    }

    static ArrayNode readableMetricSections(JsonNode metrics) {
        ArrayNode sections = JSON.createArrayNode();
        if (metrics == null || !metrics.isObject()) return sections;
        metrics.fields().forEachRemaining(group -> {
            ObjectNode section = sections.addObject();
            section.put("key", simpleJsonKey(group.getKey()));
            section.put("label", group.getKey());
            ArrayNode measurements = section.putArray("measurements");
            if (group.getValue().isObject()) {
                group.getValue().fields().forEachRemaining(metric -> {
                    ObjectNode measurement = measurements.addObject();
                    measurement.put("key", simpleJsonKey(metric.getKey()));
                    measurement.put("label", metric.getKey());
                    measurement.set("value", metric.getValue().deepCopy());
                });
            }
        });
        return sections;
    }

    static String simpleJsonKey(String label) {
        String key = label == null ? "" : label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return key.replaceAll("^_+|_+$", "");
    }

    static ArrayNode sourceStatusRecords(List<Source> sources) {
        ArrayNode records = JSON.createArrayNode();
        if (sources == null) return records;
        for (Source source : sources) {
            if (source == null) continue;
            ObjectNode record = records.addObject();
            record.put("name", source.name);
            record.put("state", source.state.name());
            record.put("status", source.status);
        }
        return records;
    }

    static ObjectNode earthMetrics(double lat, double lon, Terrain terrain, Source geology, Source soil,
                                   Source power, Source weather, Source air, Source gbif, Source osm,
                                   Source emit, Source mrds, Source geomagnetic,
                                   LocalDate start, LocalDate end) {
        ObjectNode groups = JSON.createObjectNode();
        String period = start == null || end == null ? "NASA POWER period" : start.getYear() + "–" + end.getYear();
        String dateRange = start == null || end == null ? "NASA POWER period" : start + " to " + end;
        ObjectNode g = groups.putObject("Elevation and terrain");
        metric(g, "Elevation", terrain != null && terrain.ok() ? metres(terrain.elevation) : NA);
        metric(g, "Slope", terrain != null && terrain.ok() ? degrees(terrain.slope) : NA);
        metric(g, "Aspect", terrain != null && terrain.ok() ? degrees(terrain.aspect) : NA);
        metric(g, "Surface roughness (elevation standard deviation)", terrain != null && terrain.ok() ? metres(terrain.roughness) : NA);
        metric(g, "Terrain curvature", terrain != null && terrain.ok() && Double.isFinite(terrain.curvature)
                ? n(terrain.curvature * 1000) + " km⁻¹" : NA);
        metric(g, "Local relief (5×5 elevation grid)", terrain != null && terrain.ok() ? metres(terrain.relief) : NA);
        metric(g, "Local topographic class (slope/relief heuristic)",
                terrain != null && terrain.ok() ? localTopographicClass(terrain.slope, terrain.relief) : NA);

        JsonNode w = weather == null ? null : weather.json;
        JsonNode p = power == null ? null : power.json;
        JsonNode current = w == null ? null : w.path("current");
        g = groups.putObject("Temperature and climate");
        metric(g, "Air temperature (current)", currentMetric(current, "temperature_2m", "°C"));
        metric(g, "Air temperature (hourly forecast mean)", optionalUnit(hourlyMeanFuture(w, "temperature_2m"), "°C"));
        metric(g, "Air temperature (daily forecast min/max/mean)", dailyTemperature(w));
        metric(g, "Soil temperature by depth", soilTemperatureProfile(w));
        metric(g, "Diurnal temperature range (daily mean)", optionalUnit(averageDailyRange(
                series(p, "T2M_MAX"), series(p, "T2M_MIN")), "°C"));
        metric(g, "Annual temperature range (NASA POWER, " + period + ")",
                range(p, "T2M_MIN", "T2M_MAX", "°C"));
        metric(g, "Annual mean air temperature", optionalUnit(avg(series(p, "T2M")), "°C"));
        metric(g, "Surface skin temperature (annual mean)", optionalUnit(avg(series(p, "TS")), "°C"));

        g = groups.putObject("Solar and radiation");
        metric(g, "Shortwave solar radiation (NASA POWER mean)", average(p, "ALLSKY_SFC_SW_DWN", "kWh/m²/day"));
        metric(g, "Direct radiation (forecast mean)", optionalUnit(hourlyMeanFuture(w, "direct_radiation"), "W/m²"));
        metric(g, "Diffuse radiation (forecast mean)", optionalUnit(hourlyMeanFuture(w, "diffuse_radiation"), "W/m²"));
        metric(g, "UV index (daily forecast maximum)", daily(w, "uv_index_max"));
        metric(g, "Sunshine duration (forecast)", sunshineDuration(w));
        metric(g, "Sun elevation", solarPositionMetric(w, lat, lon, 0));
        metric(g, "Sun azimuth", solarPositionMetric(w, lat, lon, 1));
        metric(g, "Day length", daylightDuration(w, lat));
        metric(g, "Cloud cover (current / forecast mean)", currentAndForecastMean(w, "cloud_cover", "%"));

        g = groups.putObject("Atmosphere");
        metric(g, "Surface pressure (current)", currentMetric(current, "surface_pressure", "hPa"));
        metric(g, "Relative humidity (current)", currentMetric(current, "relative_humidity_2m", "%"));
        metric(g, "Relative humidity (forecast mean)", optionalUnit(hourlyMeanFuture(w, "relative_humidity_2m"), "%"));
        metric(g, "Dew point (current)", currentMetric(current, "dew_point_2m", "°C"));
        metric(g, "Aerosol optical depth", currentMetric(airCurrent(air), "aerosol_optical_depth", ""));
        metric(g, "Dust concentration", currentMetric(airCurrent(air), "dust", "μg/m³"));
        metric(g, "PM10", currentMetric(airCurrent(air), "pm10", "μg/m³"));
        metric(g, "PM2.5", currentMetric(airCurrent(air), "pm2_5", "μg/m³"));
        metric(g, "Ozone", currentMetric(airCurrent(air), "ozone", "μg/m³"));

        g = groups.putObject("Wind and dust processes");
        metric(g, "Wind speed at 10 m (current)", currentMetric(current, "wind_speed_10m", "km/h"));
        metric(g, "Wind speed at 100 m (forecast mean)", optionalUnit(hourlyMeanFuture(w, "wind_speed_100m"), "km/h"));
        metric(g, "Wind gusts at 10 m (current)", currentMetric(current, "wind_gusts_10m", "km/h"));
        metric(g, "Wind direction at 10 m (current)", currentMetric(current, "wind_direction_10m", "°"));
        metric(g, "Fraction of time above saltation threshold",
                "Not estimated; threshold depends on surface grain size and roughness.");

        g = groups.putObject("Water and moisture");
        metric(g, "Precipitation (daily forecast totals)", dailyValues(w, "precipitation_sum", "mm"));
        metric(g, "Precipitation (NASA POWER total, " + dateRange + ")",
                total(p, "PRECTOTCORR", "mm"));
        metric(g, "Soil moisture by depth (forecast)", soilMoistureProfile(w));
        metric(g, "Evapotranspiration (forecast reference ET0)", forecastEt0(w));
        metric(g, "Snow depth (forecast)", snowDepth(w));
        metric(g, "Snowfall (forecast)", snowfall(w));
        metric(g, "Snow water equivalent", "Not provided by Open-Meteo; snow depth is reported separately and is not converted without a local snow-density measurement.");
        metric(g, "Aridity index proxy (forecast precipitation / reference ET0)",
                precipitationEt0Ratio(totalForecastPrecip(w), totalForecastEt0(w)));

        JsonNode soilJson = soil == null ? null : soil.json;
        g = groups.putObject("Soil properties");
        metric(g, "Clay fraction (0–5 cm)", soilProperty(soil, soilJson, "clay", lat));
        metric(g, "Sand fraction (0–5 cm)", soilProperty(soil, soilJson, "sand", lat));
        metric(g, "Silt fraction (0–5 cm)", soilProperty(soil, soilJson, "silt", lat));
        metric(g, "Soil pH (0–5 cm)", soilProperty(soil, soilJson, "phh2o", lat));
        metric(g, "Organic carbon (0–5 cm)", soilProperty(soil, soilJson, "soc", lat));
        metric(g, "Bulk density (0–5 cm)", soilProperty(soil, soilJson, "bdod", lat));
        metric(g, "Coarse fragment fraction (0–5 cm)", soilProperty(soil, soilJson, "cfvo", lat));
        metric(g, "Cation exchange capacity (0–5 cm)", soilProperty(soil, soilJson, "cec", lat));
        metric(g, "Nitrogen content (0–5 cm)", soilProperty(soil, soilJson, "nitrogen", lat));

        g = groups.putObject("Vegetation and surface cover");
        String vegetationStatus = isPolarIceSurface(lat)
                ? "Not applicable at the polar ice-covered surface."
                : "Not sampled; satellite image time-series extraction is not configured.";
        metric(g, "NDVI", vegetationStatus);
        metric(g, "EVI", vegetationStatus);
        metric(g, "Leaf area index", vegetationStatus);
        metric(g, "Land surface albedo", isPolarIceSurface(lat)
                ? "Not sampled; no site-specific polar satellite product is configured."
                : "Not sampled; a site-specific satellite product is not configured.");
        metric(g, "Land surface temperature", isPolarIceSurface(lat)
                ? "Not sampled; no site-specific polar satellite product is configured."
                : "Not sampled; a site-specific satellite product is not configured.");

        JsonNode geologyJson = geology == null ? null : geology.json;
        g = groups.putObject("Geology and minerals");
        String geologyAtSurface = surfaceGeology(lat, geology);
        metric(g, "Mapped geologic unit (Macrostrat)", geologyAtSurface);
        metric(g, "Lithology class", isPolarIceSurface(lat)
                ? "Not supportable at the surface: bedrock is covered by polar ice." : terms(geologyJson, Set.of("lith", "lithology"), 3));
        metric(g, "Mapped rock age (class; numeric age unavailable)", isPolarIceSurface(lat)
                ? "Not supportable at the surface: sub-ice geology is not sampled." : terms(geologyJson, Set.of("age"), 3));
        metric(g, "Unit thickness", isPolarIceSurface(lat)
                ? "Not supportable: no exposed mapped geologic unit; bedrock is under polar ice."
                : "Not sampled by the configured Macrostrat point query.");
        metric(g, "Elemental/oxide composition (Fe, Ti, Th, K)",
                "Not sampled; the configured Earth services do not provide a comparable point-scale elemental assay.");
        metric(g, "Distance to nearest mapped unit boundary", isPolarIceSurface(lat)
                ? "Not supportable at the surface: no exposed mapped unit at this polar coordinate."
                : "Not sampled; no mapped-unit boundary geometry is available.");
        metric(g, "Direct mineralogy", directMineralogyStatus(emit, lat));

        g = groups.putObject("Volcanic and impact features");
        metric(g, "Distance to nearest volcano", "Not sampled; a georeferenced volcano-catalog search is not configured.");
        metric(g, "Distance to nearest known impact crater", "Not sampled; a point-scale global crater-catalog search is not configured.");

        g = groups.putObject("Gravity and magnetics");
        metric(g, "Local gravity estimate (latitude/elevation model)", terrain != null && terrain.ok()
                ? n(normalGravity(lat, terrain.elevation)) + " mGal; excludes local anomalies" : NA);
        metric(g, "Magnetic field intensity", geomagneticMetric(geomagnetic,
                Set.of("totalintensity", "totalfieldintensity", "totalfield"), "nT"));
        metric(g, "Magnetic vector (north, east, down components)", earthGeomagneticVector(geomagnetic));
        metric(g, "Magnetic declination", geomagneticMetric(geomagnetic,
                Set.of("declination", "declinationangle"), "°"));
        metric(g, "Magnetic inclination", geomagneticMetric(geomagnetic,
                Set.of("inclination", "dip"), "°"));

        g = groups.putObject("Cryosphere");
        metric(g, "Distance to nearest glacier", lat <= -89.5
                ? "0 km; coordinate is on the Antarctic ice sheet (not an inventory-derived distance)"
                : lat >= 89.5 ? "Not applicable; coordinate is on Arctic Ocean sea ice."
                : "Not sampled; a glacier-inventory distance query is not configured.");
        metric(g, "Permafrost probability", isPolarIceSurface(lat)
                ? "Not a surface-soil quantity at this ice-covered polar site; sub-ice ground is not sampled."
                : "Not point-sampled; " + permafrostCoverage(lat));

        g = groups.putObject("Location metadata");
        metric(g, "Latitude", coordinate(lat) + "°");
        metric(g, "Longitude", coordinate(lon) + "°");
        metric(g, "Distance to coast", "Not sampled; no coastline geometry is loaded for point-distance calculation.");
        metric(g, "Distance to nearest road", "Not sampled; mapped-feature requests do not currently query roads.");
        metric(g, "Distance to nearest settlement", "Not sampled; no settlement-distance search is configured.");
        metric(g, "Population density", "Not sampled; WorldPop point extraction is not configured for polar sites.");
        metric(g, "Nearest Open-Meteo forecast grid point", modelGridPoint(weather == null ? null : weather.json, lat, lon));
        metric(g, "Coordinate note", Math.abs(lat) == 90
                ? "At the geographic pole, longitude is a coordinate convention and does not identify a distinct ground point."
                : NA);
        return groups;
    }

    static ObjectNode moonMetrics(ObjectNode data, double lat, double lon, Source divinerShots,
                                  Source horizons) {
        ObjectNode groups = JSON.createObjectNode();
        ObjectNode g = groups.putObject("Elevation and terrain");
        metric(g, "Elevation (LOLA; lunar reference radius 1,737.4 km)", lunarElevationMetric(data));
        metric(g, "Slope", lunarDemOrTrackMetric(data, "lola_dem_slope_deg", "slope_deg", "°"));
        metric(g, "Aspect", lunarDemOrTrackMetric(data, "lola_dem_aspect_deg", "aspect_deg", "°"));
        metric(g, "Surface roughness (LOLA local grid)", lunarDemOrTrackMetric(data, "lola_dem_roughness_m", "rough_m", "m"));
        metric(g, "Terrain curvature (LOLA local grid)", lunarCurvatureMetric(data));
        metric(g, "Local relief (LOLA local grid)", lunarDemOrTrackMetric(data, "lola_dem_relief_m", "relief_m", "m"));
        metric(g, "Local topographic class (slope/relief heuristic)",
                localTopographicClass(lunarMetricNumber(data, "lola_dem_slope_deg", "slope_deg"),
                        lunarMetricNumber(data, "lola_dem_relief_m", "relief_m")));
        metric(g, "Mapped landform / terrain type (USGS 1:5M)",
                lunarMappedTerrainClass(data == null ? null : data.path("mapped_unit")));

        g = groups.putObject("Temperature and climate");
        metric(g, "Regolith temperature at multiple depths",
                "Not sampled; Diviner observations are surface brightness temperatures, not a subsurface profile.");
        metric(g, "Diurnal temperature range (Diviner day/night brightness-temperature proxy)",
                lunarThermalContrastSummary(divinerShots));
        metric(g, "Annual surface-temperature range",
                "Not estimated; local orbital samples span multiple years but are irregular in solar illumination and do not form a calibrated annual climatology.");
        metric(g, "Surface temperature observations (Diviner channels 6–9)",
                lunarObservationSummary(divinerShots));

        g = groups.putObject("Solar and radiation");
        metric(g, "Shortwave solar radiation (instantaneous horizontal surface flux)",
                lunarSolarMetric(data, "solar_horizontal_w_m2", "W/m²", "JPL Horizons geometry; no lunar atmosphere"));
        metric(g, "Direct radiation (instantaneous direct-normal flux)",
                lunarSolarMetric(data, "solar_normal_w_m2", "W/m²", "solar-distance-adjusted; zero while Sun is below horizon"));
        metric(g, "Ultraviolet irradiance (LAMP)",
                "Not point-sampled; archive coverage alone is not a calibrated surface UV-flux value.");
        metric(g, "Sunshine duration", lunarDaylightSummary(data));
        double[] sun = lunarSunAngles(horizons);
        metric(g, "Sun elevation", sun == null ? NA : degrees(sun[1]));
        metric(g, "Sun azimuth", sun == null ? NA : degrees(sun[0]));
        metric(g, "Day length", "29.53 Earth days per lunar solar cycle (synodic month); local terrain can shorten direct illumination.");

        g = groups.putObject("Water and volatile resources");
        metric(g, "Lunar hydrogen / volatile proxy (LEND)", lunarHydrogenMetric(data));
        metric(g, "Cold-trap / ice potential",
                "Not point-sampled; neutron and UV archive coverage does not confirm extractable ice.");

        g = groups.putObject("Surface optical properties");
        metric(g, "Surface reflectance / albedo (LOLA 1064 nm)",
                "Not point-sampled; reflectance products are not extracted by the configured LOLA query.");

        JsonNode unit = data == null ? null : data.path("mapped_unit");
        g = groups.putObject("Geology and minerals");
        metric(g, "Mapped surface unit (USGS 1:5M)",
                mappedUnitText(unit, "name"));
        metric(g, "Mapped unit age", mappedUnitText(unit, "age"));
        metric(g, "Mapped unit description / interpretation", mappedUnitDescription(unit));
        metric(g, "Mapped unit thickness",
                "Not provided by the map; thickness is not inferred from polygon area.");
        metric(g, "Elemental/oxide composition (Fe, Ti, Th, K)", lunarCompositionMetric(data));
        metric(g, "Distance to nearest mapped unit boundary", lunarUnitBoundaryMetric(unit));

        g = groups.putObject("Volcanic and impact features");
        metric(g, "Distance to nearest named crater (USGS/IAU Gazetteer)", lunarNearestCraterMetric(data));

        g = groups.putObject("Gravity and magnetics");
        metric(g, "Lunar reference surface gravity (spherical model)",
                lunarGravityMetric(data));
        metric(g, "Local gravity anomaly (GRAIL GRGM1200A)", lunarGravityGridMetric(data, "grail_anomaly_mgal",
                "nearest 16-pixel/degree cell; zero-rotation reference sphere, radius 1,738 km"));
        metric(g, "Bouguer gravity disturbance (GRAIL GRGM1200A)",
                lunarGravityGridMetric(data, "grail_bouguer_mgal",
                        "nearest 16-pixel/degree cell; 2,500 kg/m³ uniform crust correction"));
        metric(g, "Magnetic field intensity (LP/Kaguya crustal model)",
                lunarMagneticMetric(data, "magnitude_nT", "nT"));
        metric(g, "Magnetic vector (east, north, radial outward)",
                lunarMagneticVectorMetric(data));
        metric(g, "Magnetic declination (derived from local vector)",
                lunarMagneticMetric(data, "declination_deg", "°"));
        metric(g, "Magnetic inclination (positive down; derived from local vector)",
                lunarMagneticMetric(data, "inclination_deg", "°"));

        g = groups.putObject("Location metadata");
        metric(g, "Latitude", coordinate(lat) + "°");
        metric(g, "Longitude", coordinate(((lon % 360) + 360) % 360) + "°E");
        return groups;
    }

    static String lunarHydrogenMetric(JsonNode data) {
        return data != null && "ok".equals(data.path("lend").asText())
                ? "LEND product coverage reported; local hydrogen abundance and ice concentration are not extracted."
                : NA;
    }

    static String lunarGravityGridMetric(JsonNode data, String key, String caveat) {
        String value = numericMetric(data, key, "mGal");
        return NA.equals(value) ? NA : value + " (GRAIL PDS grid; " + caveat
                + "; grid spacing ≈1.90 km at the equator, effective model resolution is coarser)";
    }

    static String mappedUnitText(JsonNode unit, String field) {
        JsonNode value = unit == null ? null : unit.path(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : NA;
    }

    static String mappedUnitDescription(JsonNode unit) {
        String description = mappedUnitText(unit, "description");
        String interpretation = mappedUnitText(unit, "interpretation");
        if (NA.equals(description) && NA.equals(interpretation)) return NA;
        String summary = NA.equals(description) ? interpretation
                : NA.equals(interpretation) ? description : description + " Interpretation: " + interpretation;
        return summary.length() <= 400 ? summary : summary.substring(0, 397) + "...";
    }

    static String lunarUnitBoundaryMetric(JsonNode unit) {
        JsonNode distance = unit == null ? null : unit.path("boundary_distance_km");
        if (distance == null || !distance.isNumber()) return NA;
        return distance.asDouble() < 1
                ? "<1 km (approximate; generalized USGS 1:5M map geometry)"
                : "about " + Math.round(distance.asDouble()) + " km (approximate; generalized USGS 1:5M map geometry)";
    }

    static String lunarMappedTerrainClass(JsonNode unit) {
        String name = mappedUnitText(unit, "name");
        String description = mappedUnitText(unit, "description");
        String interpretation = mappedUnitText(unit, "interpretation");
        if (NA.equals(name) && NA.equals(description) && NA.equals(interpretation)) return NA;
        String text = (name + " " + description + " " + interpretation).toLowerCase(Locale.ROOT);
        String classification;
        if (text.contains("ejecta") && (text.contains("hummock") || text.contains("ridge"))) {
            classification = "Impact-basin ejecta; locally hummocky/ridged terrain";
        } else if (text.contains("ejecta")) {
            classification = "Impact ejecta";
        } else if (text.contains("rille") || text.contains("rima")) {
            classification = "Rille or channel terrain";
        } else if (text.contains("mare") || text.contains("basaltic plain")) {
            classification = "Mare / basaltic plains";
        } else if (text.contains("highland") || text.contains("terra")) {
            classification = "Lunar highlands";
        } else if (text.contains("mountain") || text.contains("mons") || text.contains("montes")
                || text.contains("dorsum") || text.contains("ridge")) {
            classification = "Ridges or mountainous terrain";
        } else if (text.contains("crater")) {
            classification = "Impact-crater terrain";
        } else if (text.contains("plain")) {
            classification = "Plains";
        } else {
            classification = "Mapped unit; landform class is not explicit in the map attributes";
        }
        return classification + " (regional USGS 1:5M map interpretation)";
    }

    static String localTopographicClass(Double slope, Double relief) {
        if (slope == null || relief == null || !Double.isFinite(slope) || !Double.isFinite(relief)) return NA;
        String classification = slope < 2 && relief < 30 ? "Low-relief plains"
                : slope < 5 && relief < 100 ? "Gently rolling terrain"
                : slope < 15 && relief < 500 ? "Hills or rolling terrain"
                : "Steep or rugged terrain";
        return classification + " (local slope/relief heuristic; not a mapped landform)";
    }

    static Double lunarMetricNumber(JsonNode data, String primary, String fallback) {
        JsonNode value = data == null ? null : data.path(primary);
        if (value != null && value.isNumber() && Double.isFinite(value.asDouble())) return value.asDouble();
        value = data == null ? null : data.path(fallback);
        return value != null && value.isNumber() && Double.isFinite(value.asDouble()) ? value.asDouble() : null;
    }

    static String lunarMagneticMetric(JsonNode data, String field, String units) {
        JsonNode magnetic = data == null ? null : data.path("lunar_magnetic");
        JsonNode value = magnetic == null ? null : magnetic.path(field);
        if (value == null || !value.isNumber()) return NA;
        return n(value.asDouble()) + " " + units + " (smoothed LP/Kaguya crustal-field map at 30 km altitude)";
    }

    static String lunarMagneticVectorMetric(JsonNode data) {
        JsonNode magnetic = data == null ? null : data.path("lunar_magnetic");
        if (magnetic == null || !magnetic.isObject()
                || !magnetic.path("east_nT").isNumber()
                || !magnetic.path("north_nT").isNumber()
                || !magnetic.path("radial_nT").isNumber()) return NA;
        return String.format(Locale.US, "E %+.3f, N %+.3f, radial-out %+.3f nT (LP/Kaguya model, 30 km altitude)",
                magnetic.path("east_nT").asDouble(), magnetic.path("north_nT").asDouble(),
                magnetic.path("radial_nT").asDouble());
    }

    static String lunarCompositionMetric(JsonNode data) {
        JsonNode composition = data == null ? null : data.path("lunar_composition");
        if (composition == null || !composition.isObject()) return NA;
        List<String> values = new ArrayList<>();
        addCompositionValue(values, composition, "FeO_wt_percent", "FeO", " wt%");
        addCompositionValue(values, composition, "TiO2_wt_percent", "TiO₂", " wt%");
        addCompositionValue(values, composition, "Fe_element_wt_percent_derived", "derived Fe", " wt%");
        addCompositionValue(values, composition, "Ti_element_wt_percent_derived", "derived Ti", " wt%");
        addCompositionValue(values, composition, "K_ppm", "K", " ppm");
        addCompositionValue(values, composition, "Th_ppm", "Th", " ppm");
        addCompositionValue(values, composition, "MgO_wt_percent", "MgO", " wt%");
        addCompositionValue(values, composition, "Al2O3_wt_percent", "Al₂O₃", " wt%");
        addCompositionValue(values, composition, "SiO2_wt_percent", "SiO₂", " wt%");
        addCompositionValue(values, composition, "CaO_wt_percent", "CaO", " wt%");
        return values.isEmpty() ? NA : String.join("; ", values)
                + " (LP GRS 2° equal-area pixel; ~5° effective footprint; regional estimate)";
    }

    static void addCompositionValue(List<String> values, JsonNode source, String field, String label, String units) {
        JsonNode value = source.path(field);
        if (value.isNumber()) values.add(label + " " + n(value.asDouble()) + units);
    }

    static void printMetricGroups(JsonNode groups) {
        if (groups == null || !groups.isObject()) return;
        groups.fields().forEachRemaining(group -> {
            section(group.getKey());
            if (group.getValue().isObject()) {
                group.getValue().fields().forEachRemaining(metric -> line(metric.getKey(), metric.getValue().asText(NA)));
            }
        });
    }

    static void metric(ObjectNode group, String name, String value) {
        group.put(name, value == null || value.isBlank() ? NA : value);
    }

    static String currentMetric(JsonNode node, String field, String units) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.isNumber()
                ? n(value.asDouble()) + (units == null || units.isBlank() ? "" : " " + units) : NA;
    }

    static JsonNode airCurrent(Source source) {
        return source == null || source.json == null ? null : source.json.path("current");
    }

    static String numericMetric(JsonNode data, String key, String unit) {
        JsonNode value = data == null ? null : data.path(key);
        if (value == null || !value.isNumber()) return NA;
        return n(value.asDouble()) + (unit.isBlank() ? "" : " " + unit);
    }

    static String lunarElevationMetric(JsonNode data) {
        String demElevation = numericMetric(data, "lola_dem_elevation_m", "m");
        if (!NA.equals(demElevation)) {
            JsonNode centerLat = data.path("lola_dem_center_latitude_deg");
            JsonNode centerLon = data.path("lola_dem_center_longitude_east_deg");
            String center = centerLat.isNumber() && centerLon.isNumber()
                    ? String.format(Locale.US, " centered at %.6f°, %.6f°E",
                            centerLat.asDouble(), centerLon.asDouble()) : "";
            return demElevation + " (LOLA LDEM_128_FLOAT; nearest 128-pixel/degree cell"
                    + center + ", 1,737.4 km reference radius)";
        }
        String elevation = numericMetric(data, "elev_m", "m");
        if (NA.equals(elevation)) return NA;
        String distance = numericMetric(data, "elev_dist_m", "m");
        JsonNode width = data == null ? null : data.path("lola_box_halfwidth_deg");
        String search = width != null && width.isNumber()
                ? String.format(Locale.US, " ±%.3f° search", width.asDouble()) : " search";
        return elevation + " (nearest LOLA shot " + distance + " away;" + search + ")";
    }

    static String lunarDemOrTrackMetric(JsonNode data, String demKey, String trackKey, String unit) {
        String demValue = numericMetric(data, demKey, unit);
        if (!NA.equals(demValue)) return demValue + " (LOLA LDEM_128_FLOAT; 5×5 local neighborhood)";
        return numericMetric(data, trackKey, unit);
    }

    static String lunarCurvatureMetric(JsonNode data) {
        JsonNode value = data == null ? null : data.path("lola_dem_curvature_km");
        if (value != null && value.isNumber()) {
            return String.format(Locale.US, "%.4g km⁻¹ (LOLA LDEM_128_FLOAT; 5×5 local finite-difference estimate)",
                    value.asDouble());
        }
        return "Not estimated; the local gridded DEM sample is unavailable.";
    }

    static String lunarSolarMetric(JsonNode data, String key, String unit, String note) {
        String value = numericMetric(data, key, unit);
        return NA.equals(value) ? NA : value + " (" + note + ")";
    }

    static String lunarGravityMetric(JsonNode data) {
        String value = numericMetric(data, "gravity_ref_m_s2", "m/s²");
        String mgal = numericMetric(data, "gravity_ref_mgal", "mGal");
        if (NA.equals(value)) return NA;
        String elevation = numericMetric(data, "gravity_ref_elevation_m", "m");
        return value + " (" + mgal + "; spherical reference at " + elevation
                + ", excludes local GRAIL mass anomalies)";
    }

    static String soilProperty(JsonNode node, String property) {
        JsonNode layers = node == null ? null : node.path("properties").path("layers");
        if (layers == null || !layers.isArray()) return NA;
        for (JsonNode layer : layers) {
            if (!property.equals(layer.path("name").asText())) continue;
            JsonNode raw = layer.path("depths").path(0).path("values").path("mean");
            if (!raw.isNumber()) return NA;
            double divisor = layer.path("unit_measure").path("d_factor").asDouble(1);
            if (!(divisor > 0)) divisor = 1;
            String units = layer.path("unit_measure").path("target_units").asText("");
            if (property.equals("phh2o")) units = "";
            return n(raw.asDouble() / divisor) + (units.isBlank() || units.equals("-") ? "" : " " + units);
        }
        return NA;
    }

    static boolean isPolarIceSurface(double lat) {
        return lat <= -89.5 || lat >= 89.5;
    }

    static boolean soilGridsUnsupportedAt(double lat) {
        return isPolarIceSurface(lat);
    }

    static String soilCoverageLimitation(double lat) {
        return lat <= -89.5
                ? "Not applicable: SoilGrids does not model surface soils on the Antarctic ice sheet."
                : "Not applicable: SoilGrids does not model surface soils at the Arctic Ocean pole.";
    }

    static String soilSummary(Source source, double lat) {
        return soilGridsUnsupportedAt(lat) ? soilCoverageLimitation(lat)
                : source == null || source.state == SourceState.DATA
                ? soil(source == null ? null : source.json) : soilSourceStatus(source);
    }

    static String soilProperty(Source source, JsonNode node, String property, double lat) {
        if (soilGridsUnsupportedAt(lat)) return soilCoverageLimitation(lat);
        if (source != null && source.state != SourceState.DATA) return soilSourceStatus(source);
        return soilProperty(node, property);
    }

    static String soilSourceStatus(Source source) {
        return switch (source.state) {
            case NOT_APPLICABLE -> "Not applicable: SoilGrids does not model surface soils at this location.";
            case NOT_CONFIGURED -> "Not sampled: SoilGrids is not configured for this location.";
            case NETWORK_ERROR, API_ERROR -> "Unavailable: SoilGrids request failed (" + source.status + ").";
            case EMPTY -> "No SoilGrids estimate returned for this location.";
            case DATA -> soil(source.json);
        };
    }

    static String surfaceGeology(double lat, Source geology) {
        if (isPolarIceSurface(lat)) {
            return "Not available at surface: " + (lat <= -89.5 ? "Antarctic ice sheet" : "Arctic Ocean sea ice")
                    + " covers the site; sub-ice geology is not sampled.";
        }
        return terms(geology == null ? null : geology.json,
                Set.of("name", "unit_name", "lith", "lithology", "age"), 3);
    }

    static String directMineralogyStatus(Source source, double lat) {
        return Math.abs(lat) > 52.5
                ? "Not available: NASA EMIT is on the ISS and does not cover this latitude."
                : directMineralogyStatus(source);
    }

    static String emitCoverage(Source source, double lat) {
        return Math.abs(lat) > 52.5
                ? "Not available: NASA EMIT is on the ISS and does not cover this latitude."
                : emitCoverage(source);
    }

    static String modelGridPoint(JsonNode weather, double lat, double lon) {
        if (weather == null || !weather.path("latitude").isNumber() || !weather.path("longitude").isNumber()) return NA;
        double gridLat = weather.path("latitude").asDouble(), gridLon = weather.path("longitude").asDouble();
        return coordinate(gridLat) + "°, " + coordinate(gridLon) + "°; "
                + n(distanceMeters(lat, lon, gridLat, gridLon) / 1000d) + " km from requested coordinate";
    }

    static String geomagneticMetric(Source source, Set<String> fieldNames, String units) {
        if (source == null) return "Not sampled; NOAA geomagnetic data is not included in this data-only report.";
        if (source.state != SourceState.DATA || source.json == null) {
            return "Unavailable: " + source.status;
        }
        Double value = findNamedNumber(source.json, fieldNames);
        return value == null ? "NOAA response did not contain this field."
                : n(value) + " " + units + " (NOAA WMMHR model)";
    }

    static String earthGeomagneticVector(Source source) {
        if (source == null || source.state != SourceState.DATA || source.json == null) return NA;
        Double north = findNamedNumber(source.json, Set.of("northintensity", "northcomponent", "northcomp"));
        Double east = findNamedNumber(source.json, Set.of("eastintensity", "eastcomponent", "eastcomp"));
        Double down = findNamedNumber(source.json,
                Set.of("verticalintensity", "verticalcomponent", "downintensity", "downcomponent"));
        if (north == null || east == null || down == null) return "NOAA response did not contain all vector components.";
        return String.format(Locale.US, "N %+.1f, E %+.1f, down %+.1f nT (NOAA WMMHR model)",
                north, east, down);
    }

    static Double findNamedNumber(JsonNode node, Set<String> fieldNames) {
        if (node == null) return null;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String normalized = field.getKey().replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
                if (fieldNames.contains(normalized)) {
                    JsonNode value = field.getValue();
                    if (value.isNumber()) return value.asDouble();
                    if (value.isTextual()) {
                        try { return Double.parseDouble(value.asText()); }
                        catch (NumberFormatException ignored) { }
                    }
                }
            }
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                Double value = findNamedNumber(children.next(), fieldNames);
                if (value != null) return value;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                Double value = findNamedNumber(child, fieldNames);
                if (value != null) return value;
            }
        }
        return null;
    }

    static String sunshineDuration(JsonNode weather) {
        Double seconds = sumArray(weather == null ? null : weather.path("daily").path("sunshine_duration"));
        return seconds == null ? NA : n(seconds / 3600d) + " h (forecast period)";
    }

    static String daylightDuration(JsonNode weather, double lat) {
        LocalDateTime reference = forecastReferenceTime(weather);
        if (reference == null || !Double.isFinite(lat)) return NA;
        return n(dayLengthHours(reference.toLocalDate(), lat)) + " h (computed for forecast date)";
    }

    static String solarPositionMetric(JsonNode weather, double lat, double lon, int index) {
        LocalDateTime reference = forecastReferenceTime(weather);
        if (reference == null || !Double.isFinite(lat) || !Double.isFinite(lon)) return NA;
        double[] position = solarPosition(reference, weather.path("utc_offset_seconds").asInt(0), lat, lon);
        return degrees(position[index]);
    }

    static String dailyValues(JsonNode weather, String field, String unit) {
        JsonNode dates = weather == null ? null : weather.path("daily").path("time");
        JsonNode values = weather == null ? null : weather.path("daily").path(field);
        if (values == null || !values.isArray()) return NA;
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (!values.get(i).isNumber()) continue;
            String date = dates != null && dates.isArray() && i < dates.size() ? dates.get(i).asText() : "day " + (i + 1);
            rows.add(date + " " + n(values.get(i).asDouble()) + " " + unit);
        }
        return rows.isEmpty() ? NA : String.join("; ", rows);
    }

    static String snowDepth(JsonNode weather) {
        return optionalUnit(hourlyValueAtOrAfter(weather, "snow_depth"), "m");
    }

    static String snowfall(JsonNode weather) {
        Double value = firstNumber(weather == null ? null : weather.path("daily").path("snowfall_sum"));
        if (value == null) value = firstNumber(weather == null ? null : weather.path("current").path("snowfall"));
        return optionalUnit(value, "cm");
    }

    static void printSourceStatuses(List<Source> sources) {
        System.out.println("\nSource status");
        if (sources == null) return;
        for (Source source : sources) {
            if (source != null) System.out.println("- " + source.name + ": " + source.state + " — " + source.status);
        }
    }

    static String soil(JsonNode node) {
        JsonNode layers = node == null ? null : node.path("properties").path("layers");
        if (layers == null || !layers.isArray()) return NA;
        List<String> out = new ArrayList<>();
        for (JsonNode layer : layers) {
            JsonNode raw = layer.path("depths").path(0).path("values").path("mean");
            if (!raw.isNumber()) continue;
            String name = layer.path("name").asText("soil");
            double divisor = layer.path("unit_measure").path("d_factor").asDouble(1);
            if (!(divisor > 0)) divisor = 1;
            double value = raw.asDouble() / divisor;
            String units = layer.path("unit_measure").path("target_units").asText("");
            String label = switch (name) {
                case "bdod" -> "bulk density";
                case "cec" -> "CEC";
                case "cfvo" -> "coarse fragments";
                case "phh2o" -> "pH";
                case "soc" -> "organic carbon";
                default -> name;
            };
            if (name.equals("phh2o")) units = "";
            String suffix = units.isBlank() || units.equals("-") ? "" : " " + units;
            out.add(label + "=" + n(value) + suffix);
        }
        return out.isEmpty() ? NA : String.join(", ", out);
    }

    static void putOptionalNum(ObjectNode target, String key, Double value) {
        if (value != null && Double.isFinite(value)) target.put(key, round2(value));
        else putText(target, key, NA);
    }

    static Double averageDailyRange(JsonNode maxima, JsonNode minima) {
        if (maxima == null || minima == null || !maxima.isObject() || !minima.isObject()) return null;
        double total = 0;
        int count = 0;
        Iterator<Map.Entry<String, JsonNode>> days = maxima.fields();
        while (days.hasNext()) {
            Map.Entry<String, JsonNode> entry = days.next();
            JsonNode low = minima.get(entry.getKey());
            if (entry.getValue().isNumber() && low != null && low.isNumber()
                    && entry.getValue().asDouble() > -900 && low.asDouble() > -900) {
                total += entry.getValue().asDouble() - low.asDouble();
                count++;
            }
        }
        return count == 0 ? null : total / count;
    }

    static Double circularMean(JsonNode values) {
        if (values == null || !values.isObject()) return null;
        double x = 0, y = 0;
        int count = 0;
        for (JsonNode value : values) {
            if (!value.isNumber() || value.asDouble() <= -900) continue;
            double radians = Math.toRadians(value.asDouble());
            x += Math.cos(radians);
            y += Math.sin(radians);
            count++;
        }
        if (count == 0 || (Math.abs(x) < 1e-12 && Math.abs(y) < 1e-12)) return null;
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    static LocalDateTime forecastReferenceTime(JsonNode weather) {
        String text = weather == null ? "" : weather.path("current").path("time").asText("");
        try { return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME); }
        catch (RuntimeException ignored) { return null; }
    }

    static Double hourlyValueAtOrAfter(JsonNode weather, String variable) {
        JsonNode times = weather == null ? null : weather.path("hourly").path("time");
        JsonNode values = weather == null ? null : weather.path("hourly").path(variable);
        if (times == null || values == null || !times.isArray() || !values.isArray()) return null;
        LocalDateTime cutoff = forecastReferenceTime(weather);
        for (int i = 0; i < Math.min(times.size(), values.size()); i++) {
            if (!values.get(i).isNumber()) continue;
            if (cutoff == null) return values.get(i).asDouble();
            try {
                if (!LocalDateTime.parse(times.get(i).asText(), DateTimeFormatter.ISO_LOCAL_DATE_TIME).isBefore(cutoff)) {
                    return values.get(i).asDouble();
                }
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    static Double hourlyMeanFuture(JsonNode weather, String variable) {
        JsonNode times = weather == null ? null : weather.path("hourly").path("time");
        JsonNode values = weather == null ? null : weather.path("hourly").path(variable);
        if (times == null || values == null || !times.isArray() || !values.isArray()) return null;
        LocalDateTime cutoff = forecastReferenceTime(weather);
        double total = 0;
        int count = 0;
        for (int i = 0; i < Math.min(times.size(), values.size()); i++) {
            JsonNode value = values.get(i);
            if (!value.isNumber()) continue;
            if (cutoff != null) {
                try {
                    if (LocalDateTime.parse(times.get(i).asText(), DateTimeFormatter.ISO_LOCAL_DATE_TIME).isBefore(cutoff)) continue;
                } catch (RuntimeException ignored) { continue; }
            }
            total += value.asDouble();
            count++;
        }
        return count == 0 ? null : total / count;
    }

    static String soilTemperatureProfile(JsonNode weather) {
        return profile(weather, new String[][]{
                {"soil_temperature_0cm", "0 cm"}, {"soil_temperature_6cm", "6 cm"},
                {"soil_temperature_18cm", "18 cm"}, {"soil_temperature_54cm", "54 cm"}
        }, "°C");
    }

    static String soilMoistureProfile(JsonNode weather) {
        return profile(weather, new String[][]{
                {"soil_moisture_0_to_1cm", "0–1 cm"}, {"soil_moisture_1_to_3cm", "1–3 cm"},
                {"soil_moisture_3_to_9cm", "3–9 cm"}, {"soil_moisture_9_to_27cm", "9–27 cm"},
                {"soil_moisture_27_to_81cm", "27–81 cm"}
        }, "m³/m³");
    }

    static String profile(JsonNode weather, String[][] variables, String units) {
        List<String> values = new ArrayList<>();
        for (String[] variable : variables) {
            Double value = hourlyValueAtOrAfter(weather, variable[0]);
            if (value != null) values.add(variable[1] + "=" + n(value) + " " + units);
        }
        return values.isEmpty() ? NA : String.join(", ", values);
    }

    static Double totalForecastEt0(JsonNode weather) {
        return sumArray(weather == null ? null : weather.path("daily").path("et0_fao_evapotranspiration"));
    }

    static Double totalForecastPrecip(JsonNode weather) {
        return sumArray(weather == null ? null : weather.path("daily").path("precipitation_sum"));
    }

    static Double sumArray(JsonNode values) {
        if (values == null || !values.isArray()) return null;
        double total = 0;
        int count = 0;
        for (JsonNode value : values) {
            if (value.isNumber() && value.asDouble() > -900) {
                total += value.asDouble();
                count++;
            }
        }
        return count == 0 ? null : total;
    }

    static String forecastEt0(JsonNode weather) {
        Double value = totalForecastEt0(weather);
        return value == null ? NA : n(value) + " mm (forecast days)";
    }

    static String precipitationEt0Ratio(Double precipitation, Double et0) {
        if (precipitation == null || et0 == null || et0 <= 0) return NA;
        return n(precipitation / et0) + " (forecast precipitation / reference ET0; not a climate index)";
    }

    static String dailyTemperature(JsonNode weather) {
        JsonNode daily = weather == null ? null : weather.path("daily");
        if (daily == null) return NA;
        JsonNode dates = daily.path("time"), lows = daily.path("temperature_2m_min");
        JsonNode highs = daily.path("temperature_2m_max"), means = daily.path("temperature_2m_mean");
        int days = Math.max(dates.isArray() ? dates.size() : 0,
                Math.max(lows.isArray() ? lows.size() : 0, highs.isArray() ? highs.size() : 0));
        List<String> forecasts = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            List<String> values = new ArrayList<>();
            if (lows.isArray() && i < lows.size() && lows.get(i).isNumber()) values.add("min " + n(lows.get(i).asDouble()));
            if (highs.isArray() && i < highs.size() && highs.get(i).isNumber()) values.add("max " + n(highs.get(i).asDouble()));
            if (means.isArray() && i < means.size() && means.get(i).isNumber()) values.add("mean " + n(means.get(i).asDouble()));
            if (!values.isEmpty()) {
                String date = dates.isArray() && i < dates.size() && dates.get(i).isTextual() ? dates.get(i).asText() : "day " + (i + 1);
                forecasts.add(date + ": " + String.join(", ", values) + " °C");
            }
        }
        return forecasts.isEmpty() ? NA : String.join("; ", forecasts);
    }

    static Double firstNumber(JsonNode values) {
        return values != null && values.isArray() && !values.isEmpty() && values.get(0).isNumber()
                ? values.get(0).asDouble() : null;
    }

    static String firstText(JsonNode values) {
        return values != null && values.isArray() && !values.isEmpty() && values.get(0).isTextual()
                ? values.get(0).asText() : "";
    }

    static String solarComponents(JsonNode weather) {
        Double direct = hourlyMeanFuture(weather, "direct_radiation");
        Double diffuse = hourlyMeanFuture(weather, "diffuse_radiation");
        Double sunshine = sumArray(weather == null ? null : weather.path("daily").path("sunshine_duration"));
        List<String> values = new ArrayList<>();
        if (direct != null) values.add("direct " + n(direct) + " W/m²");
        if (diffuse != null) values.add("diffuse " + n(diffuse) + " W/m²");
        if (sunshine != null) values.add("sunshine " + n(sunshine / 3600d) + " h forecast");
        return values.isEmpty() ? NA : String.join("; ", values);
    }

    static String sunlight(JsonNode weather, double lat, double lon) {
        LocalDateTime reference = forecastReferenceTime(weather);
        if (reference == null || !Double.isFinite(lat) || !Double.isFinite(lon)) return NA;
        int offsetSeconds = weather.path("utc_offset_seconds").asInt(0);
        double[] sun = solarPosition(reference, offsetSeconds, lat, lon);
        double computedDaylight = dayLengthHours(reference.toLocalDate(), lat);
        Double modelDaylight = firstNumber(weather.path("daily").path("daylight_duration"));
        Double modelSunshine = firstNumber(weather.path("daily").path("sunshine_duration"));
        return "sun elevation " + n(sun[0]) + "° / azimuth " + n(sun[1]) + "°; day length "
                + n(computedDaylight) + " h computed"
                + (modelDaylight == null ? "" : " (Open-Meteo " + n(modelDaylight / 3600d) + " h)")
                + (modelSunshine == null ? "" : "; sunshine " + n(modelSunshine / 3600d) + " h forecast");
    }

    static double[] solarPosition(LocalDateTime localTime, int utcOffsetSeconds, double lat, double lon) {
        double julianDay = localTime.toInstant(ZoneOffset.ofTotalSeconds(utcOffsetSeconds)).getEpochSecond() / 86400d + 2440587.5;
        double century = (julianDay - 2451545.0) / 36525d;
        double geomMeanLong = normalizeDegrees(280.46646 + century * (36000.76983 + century * .0003032));
        double geomMeanAnomaly = 357.52911 + century * (35999.05029 - .0001537 * century);
        double eccentricity = .016708634 - century * (.000042037 + .0000001267 * century);
        double anomalyRadians = Math.toRadians(geomMeanAnomaly);
        double equationCenter = Math.sin(anomalyRadians) * (1.914602 - century * (.004817 + .000014 * century))
                + Math.sin(2 * anomalyRadians) * (.019993 - .000101 * century)
                + Math.sin(3 * anomalyRadians) * .000289;
        double trueLong = geomMeanLong + equationCenter;
        double omega = 125.04 - 1934.136 * century;
        double apparentLong = trueLong - .00569 - .00478 * Math.sin(Math.toRadians(omega));
        double meanObliquity = 23 + (26 + (21.448 - century * (46.815 + century * (.00059 - century * .001813))) / 60) / 60;
        double obliquity = meanObliquity + .00256 * Math.cos(Math.toRadians(omega));
        double declination = Math.asin(Math.sin(Math.toRadians(obliquity)) * Math.sin(Math.toRadians(apparentLong)));
        double y = Math.tan(Math.toRadians(obliquity) / 2);
        y *= y;
        double equationTime = 4 * Math.toDegrees(y * Math.sin(2 * Math.toRadians(geomMeanLong))
                - 2 * eccentricity * Math.sin(anomalyRadians)
                + 4 * eccentricity * y * Math.sin(anomalyRadians) * Math.cos(2 * Math.toRadians(geomMeanLong))
                - .5 * y * y * Math.sin(4 * Math.toRadians(geomMeanLong))
                - 1.25 * eccentricity * eccentricity * Math.sin(2 * anomalyRadians));
        double localMinutes = localTime.getHour() * 60 + localTime.getMinute() + localTime.getSecond() / 60d;
        double trueSolarMinutes = (localMinutes + equationTime + 4 * lon - utcOffsetSeconds / 60d) % 1440;
        if (trueSolarMinutes < 0) trueSolarMinutes += 1440;
        double hourAngle = Math.toRadians(trueSolarMinutes / 4 - 180);
        double latitude = Math.toRadians(lat);
        double cosineZenith = Math.sin(latitude) * Math.sin(declination)
                + Math.cos(latitude) * Math.cos(declination) * Math.cos(hourAngle);
        double zenith = Math.acos(Math.max(-1, Math.min(1, cosineZenith)));
        double azimuth = (Math.toDegrees(Math.atan2(Math.sin(hourAngle),
                Math.cos(hourAngle) * Math.sin(latitude) - Math.tan(declination) * Math.cos(latitude))) + 180) % 360;
        return new double[]{90 - Math.toDegrees(zenith), azimuth};
    }

    static double dayLengthHours(LocalDate date, double lat) {
        double day = date.getDayOfYear();
        double gamma = 2 * Math.PI / (date.isLeapYear() ? 366 : 365) * (day - 1);
        double declination = .006918 - .399912 * Math.cos(gamma) + .070257 * Math.sin(gamma)
                - .006758 * Math.cos(2 * gamma) + .000907 * Math.sin(2 * gamma)
                - .002697 * Math.cos(3 * gamma) + .00148 * Math.sin(3 * gamma);
        double argument = -Math.tan(Math.toRadians(lat)) * Math.tan(declination);
        if (argument <= -1) return 24;
        if (argument >= 1) return 0;
        return 24 * Math.acos(argument) / Math.PI;
    }

    static double normalizeDegrees(double value) {
        return ((value % 360) + 360) % 360;
    }

    static double normalGravity(double lat, double elevationMeters) {
        double sine = Math.sin(Math.toRadians(lat));
        double sineSquared = sine * sine;
        double seaLevel = 9.7803253359 * (1 + .00193185265241 * sineSquared)
                / Math.sqrt(1 - .00669437999013 * sineSquared);
        return (seaLevel - .000003086 * elevationMeters) * 100_000d;
    }

    static double lunarReferenceGravity(double lat, double elevationMeters) {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90 || !Double.isFinite(elevationMeters)) return Double.NaN;
        double radius = 1_737_400 + elevationMeters;
        if (!(radius > 0)) return Double.NaN;
        double rotationRate = 2 * Math.PI / (27.321661 * 86_400);
        double cosine = Math.cos(Math.toRadians(lat));
        return 4.9048695e12 / (radius * radius) - rotationRate * rotationRate * radius * cosine * cosine;
    }

    static ObjectNode lunarNearestNamedCrater(Source source, double siteLat, double siteLon) {
        ObjectNode nearest = JSON.createObjectNode();
        JsonNode craters = source == null || source.state != SourceState.DATA || source.json == null
                ? null : source.json.path("craters");
        if (craters == null || !craters.isArray() || !Double.isFinite(siteLat) || !Double.isFinite(siteLon)) return nearest;
        double closestRimDistance = Double.POSITIVE_INFINITY;
        for (JsonNode crater : craters) {
            double lat = numberField(crater, "latitude_deg");
            double lon = numberField(crater, "longitude_east_deg");
            double diameter = numberField(crater, "diameter_km");
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || !Double.isFinite(diameter) || diameter <= 0) continue;
            double centerDistance = lunarSurfaceDistanceKm(siteLat, siteLon, lat, lon);
            double rimDistance = Math.max(0, centerDistance - diameter / 2);
            if (rimDistance >= closestRimDistance) continue;
            closestRimDistance = rimDistance;
            nearest.put("name", crater.path("name").asText("Unnamed"));
            nearest.put("rim_distance_km", round2(rimDistance));
            nearest.put("center_distance_km", round2(centerDistance));
            nearest.put("diameter_km", round2(diameter));
            nearest.put("latitude_deg", lat);
            nearest.put("longitude_east_deg", lon);
        }
        return nearest;
    }

    static double lunarSurfaceDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1), phi2 = Math.toRadians(lat2);
        double deltaPhi = phi2 - phi1;
        double deltaLon = Math.toRadians(((lon2 - lon1 + 540) % 360) - 180);
        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return 2 * 1_737.4 * Math.asin(Math.sqrt(Math.max(0, Math.min(1, a))));
    }

    static String lunarNearestCraterMetric(JsonNode data) {
        JsonNode crater = data == null ? null : data.path("nearest_named_crater");
        if (crater == null || !crater.isObject() || !crater.path("name").isTextual()) {
            return "Not sampled; the USGS/IAU named-crater catalog is unavailable.";
        }
        return String.format(Locale.US, "%s; approximate rim distance %.2f km (center %.2f km, diameter %.2f km; spherical circular-rim estimate)",
                crater.path("name").asText(), crater.path("rim_distance_km").asDouble(),
                crater.path("center_distance_km").asDouble(), crater.path("diameter_km").asDouble());
    }

    static double round6(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
    static String air(JsonNode node) {
        JsonNode x = node == null ? null : node.path("current");
        if (x == null) return NA;
        List<String> out = new ArrayList<>();
        add(out, x, "pm10", "PM10 ", " μg/m³");
        add(out, x, "pm2_5", "PM2.5 ", " μg/m³");
        add(out, x, "dust", "dust ", " μg/m³");
        add(out, x, "aerosol_optical_depth", "AOD ", "");
        add(out, x, "ozone", "ozone ", " μg/m³");
        add(out, x, "us_aqi", "US AQI ", "");
        return out.isEmpty() ? NA : String.join(", ", out);
    }

    static String pairAverages(JsonNode source, String firstKey, String secondKey,
                               String firstLabel, String secondLabel, String firstUnit, String secondUnit) {
        Double a = avg(series(source, firstKey)), b = avg(series(source, secondKey));
        List<String> values = new ArrayList<>();
        if (a != null) values.add(firstLabel + " " + n(a) + " " + firstUnit);
        if (b != null) values.add(secondLabel + " " + n(b) + " " + secondUnit);
        return values.isEmpty() ? NA : String.join(" / ", values);
    }

    static String forecastHumidityAndDewPoint(JsonNode weather) {
        Double humidity = hourlyMeanFuture(weather, "relative_humidity_2m");
        Double dewPoint = hourlyMeanFuture(weather, "dew_point_2m");
        List<String> values = new ArrayList<>();
        if (humidity != null) values.add(n(humidity) + " % RH");
        if (dewPoint != null) values.add(n(dewPoint) + " °C dew point");
        return values.isEmpty() ? NA : String.join(" / ", values);
    }

    static String optionalUnit(Double value, String units) {
        return value == null ? NA : n(value) + " " + units;
    }

    static String forecastPrecipEt0(JsonNode weather) {
        Double precipitation = totalForecastPrecip(weather), et0 = totalForecastEt0(weather);
        return precipitation == null && et0 == null ? NA
                : (precipitation == null ? "precipitation unavailable" : n(precipitation) + " mm precipitation")
                + " / " + (et0 == null ? "ET0 unavailable" : n(et0) + " mm reference ET0");
    }

    static String windDirectionAndUpperSpeed(JsonNode weather) {
        JsonNode current = weather == null ? null : weather.path("current");
        List<String> values = new ArrayList<>();
        if (current != null && current.path("wind_direction_10m").isNumber()) {
            values.add(n(current.path("wind_direction_10m").asDouble()) + "° direction");
        }
        Double speed = hourlyMeanFuture(weather, "wind_speed_100m");
        if (speed != null) values.add(n(speed) + " km/h at 100 m (forecast mean)");
        return values.isEmpty() ? NA : String.join(" / ", values);
    }

    static String currentAndForecastMean(JsonNode weather, String variable, String unit) {
        JsonNode current = weather == null ? null : weather.path("current").path(variable);
        Double forecast = hourlyMeanFuture(weather, variable);
        List<String> values = new ArrayList<>();
        if (current != null && current.isNumber()) values.add("current " + n(current.asDouble()) + " " + unit);
        if (forecast != null) values.add("forecast mean " + n(forecast) + " " + unit);
        return values.isEmpty() ? NA : String.join(" / ", values);
    }
    static String gbif(JsonNode node) { if (node == null || !node.path("count").isNumber()) return NA; List<String> groups = new ArrayList<>(); for (JsonNode x : node.path("facets").path(0).path("counts")) groups.add(x.path("name").asText()); return node.path("count").asLong()+" records in ±0.1°"+(groups.isEmpty()?"":"; groups: "+truncate(groups,5)); }
    static String features(JsonNode node, Set<String> wanted) { JsonNode e=node==null?null:node.path("elements"); if(e==null||!e.isArray()) return NA; List<String> out=new ArrayList<>(); for(JsonNode x:e){JsonNode t=x.path("tags"); boolean hit=false; for(String w:wanted) if(t.has(w)||w.equals(t.path("natural").asText())||w.equals(t.path("man_made").asText())) hit=true; if(hit) out.add(t.path("name").asText(t.path("natural").asText(t.path("man_made").asText("feature"))));} return out.isEmpty()?"0 mapped records (not evidence of absence)":out.size()+" mapped: "+truncate(out,5); }
    static String pair(JsonNode node,String a,String b,String au,String bu) { JsonNode x=node==null?null:node.path("current"); if(x==null)return NA; List<String> out=new ArrayList<>(); if(x.path(a).isNumber())out.add(n(x.path(a).asDouble())+" "+au); if(x.path(b).isNumber())out.add(n(x.path(b).asDouble())+" "+bu); return out.isEmpty()?NA:String.join(" / ",out); }
    static String snow(JsonNode node) {
        Double snowfall = firstNumber(node == null ? null : node.path("daily").path("snowfall_sum"));
        if (snowfall == null) snowfall = firstNumber(node == null ? null : node.path("current").path("snowfall"));
        Double depth = hourlyValueAtOrAfter(node, "snow_depth");
        String a = snowfall == null ? NA : n(snowfall) + " cm";
        String b = depth == null ? NA : n(depth) + " m";
        return NA.equals(a) && NA.equals(b) ? NA : a + " / " + b;
    }
    static String earthGate(Terrain terrain, Source power, Source weather) { List<String> missing=new ArrayList<>(); if(terrain==null||!terrain.ok())missing.add("terrain"); if(power==null||power.state!=SourceState.DATA)missing.add("climate"); if(weather==null||weather.state!=SourceState.DATA)missing.add("current weather"); return missing.isEmpty()?"Inputs present, but still INSUFFICIENT / NO-GO for a safety-critical decision.":"INCOMPLETE / NO-GO: missing "+String.join(", ",missing)+"."; }
    static String screening(JsonNode weather, JsonNode air) {
        JsonNode current = weather == null ? null : weather.path("current");
        if (current == null) return NA;
        List<String> warnings = new ArrayList<>();
        if (current.path("wind_gusts_10m").asDouble(-1) >= 50) warnings.add("high gusts");
        Double visibility = hourlyValueAtOrAfter(weather, "visibility");
        if (visibility != null && visibility < 2000) warnings.add("low visibility");
        if (air != null && air.path("current").path("dust").asDouble(-1) >= 100) warnings.add("elevated dust");
        return warnings.isEmpty() ? "No automatic threshold triggered; this is not a safety clearance."
                : "Caution: " + String.join(", ", warnings) + ".";
    }
    static String daily(JsonNode node, String field) {
        JsonNode values = node == null ? null : node.path("daily").path(field);
        return values == null || !values.isArray() || values.isEmpty() || !values.get(0).isNumber()
                ? NA : "forecast " + field + " " + n(values.get(0).asDouble());
    }
    static JsonNode series(JsonNode n,String key){return n==null?null:n.path("properties").path("parameter").path(key);} static String range(JsonNode n,String a,String b,String u){Double lo=min(series(n,a)),hi=max(series(n,b));return lo==null||hi==null?NA:n(lo)+" to "+n(hi)+" "+u;} static String total(JsonNode n,String p,String u){Double x=sum(series(n,p));return x==null?NA:n(x)+" "+u;} static String average(JsonNode n,String p,String u){Double x=avg(series(n,p));return x==null?NA:n(x)+" "+u+" average";} static String maximum(JsonNode n,String p,String u){Double x=max(series(n,p));return x==null?NA:n(x)+" "+u;}
    static Double sum(JsonNode x){if(x==null||!x.isObject())return null;double s=0;int c=0;for(JsonNode v:x)if(v.isNumber()&&v.asDouble()>-900){s+=v.asDouble();c++;}return c==0?null:s;} static Double avg(JsonNode x){if(x==null||!x.isObject())return null;double s=0;int c=0;for(JsonNode v:x)if(v.isNumber()&&v.asDouble()>-900){s+=v.asDouble();c++;}return c==0?null:s/c;} static Double min(JsonNode x){if(x==null||!x.isObject())return null;Double r=null;for(JsonNode v:x)if(v.isNumber()&&v.asDouble()>-900)r=r==null?v.asDouble():Math.min(r,v.asDouble());return r;} static Double max(JsonNode x){if(x==null||!x.isObject())return null;Double r=null;for(JsonNode v:x)if(v.isNumber()&&v.asDouble()>-900)r=r==null?v.asDouble():Math.max(r,v.asDouble());return r;}
    static String terms(JsonNode node,Set<String> keys,int limit){Set<String> out=new LinkedHashSet<>(); collect(node,keys,out,limit);return out.isEmpty()?NA:truncate(out,limit);} static void collect(JsonNode node,Set<String> keys,Set<String> out,int limit){if(node==null||out.size()>=limit)return;if(node.isObject())node.fields().forEachRemaining(e->{if(keys.contains(e.getKey())&&e.getValue().isValueNode()){String v=e.getValue().asText();if(!v.isBlank()&&v.length()<100)out.add(e.getKey()+"="+v);}collect(e.getValue(),keys,out,limit);});else if(node.isArray())for(JsonNode x:node)collect(x,keys,out,limit);} static String truncate(Iterable<String> xs,int limit){List<String>out=new ArrayList<>();int more=0;for(String x:xs)if(out.size()<limit)out.add(x);else more++;return out.isEmpty()?NA:String.join(", ",out)+(more>0?" (and "+more+" more)":"");}
    static void add(List<String> out,JsonNode x,String key,String before,String after){if(x.path(key).isNumber())out.add(before+n(x.path(key).asDouble())+after);} static void section(String s){System.out.println("\n"+s);} static void line(String k,String v){System.out.println("- "+k+": "+(v==null||v.isBlank()?NA:v));} static String n(double x){return String.format(Locale.US,"%.2f",x);} static String metres(double x){return n(x)+" m";} static String degrees(double x){return n(x)+"°";} static String date(LocalDate x){return x.format(DateTimeFormatter.BASIC_ISO_DATE);}
    public enum SourceState { DATA, EMPTY, NOT_APPLICABLE, NOT_CONFIGURED, API_ERROR, NETWORK_ERROR }
    static record ApiKey(String value, String status) {}
    public record Source(String name, JsonNode json, SourceState state, String status) {}
    public static final class Terrain {
        public final double elevation, slope, aspect, relief, roughness, curvature;
        public Terrain(double e, double s, double a, double r, double q, double c) {
            elevation = e; slope = s; aspect = a; relief = r; roughness = q; curvature = c;
        }
        public static Terrain none() {
            return new Terrain(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        public boolean ok() { return Double.isFinite(elevation); }
    }
}
