package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Asserts {@link Rng} against the prototype's golden vectors.
 *
 * <p>Doubles are compared by their {@code *_bits} field, never the decimal form — a decimal
 * round-trip through two languages' formatters is not a proof of equality, and the failure this
 * suite exists to catch is exactly one bit wide.
 *
 * <p>The vectors are read from {@code toy_geology_model/porting/golden_vectors.json} in place
 * rather than from a copy under test resources, so there is one file and it cannot drift. A failure
 * here is not a test to update: per PORTING_SPEC §6, any diff is a deliberate world-breaking
 * change.
 */
class RngGoldenVectorTest {

    private static JsonObject vectors;

    @BeforeAll
    static void loadVectors() throws IOException {
        Path path = locate();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            vectors = JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static Path locate() {
        String configured = System.getProperty("granularity.goldenVectors");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        // Fallback for runs launched straight from an IDE without the Gradle system property.
        return Path.of("toy_geology_model", "porting", "golden_vectors.json");
    }

    private static JsonArray section(String name) {
        JsonArray array = vectors.getAsJsonArray(name);
        if (array == null) {
            throw new AssertionError("Vector file has no '" + name + "' section");
        }
        return array;
    }

    private static long asUnsignedHex(JsonElement element) {
        return Long.parseUnsignedLong(element.getAsString(), 16);
    }

    private static double asDoubleBits(JsonObject object, String bitsField) {
        return Double.longBitsToDouble(asUnsignedHex(object.get(bitsField)));
    }

    private static String hex(long value) {
        return String.format("%016x", value);
    }

    @TestFactory
    @DisplayName("mix64 — splitmix64 finalizer")
    List<DynamicTest> mix64Vectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement element : section("mix64")) {
            JsonObject vector = element.getAsJsonObject();
            long input = asUnsignedHex(vector.get("input"));
            long expected = asUnsignedHex(vector.get("output"));
            tests.add(dynamicTest("mix64(" + hex(input) + ")", () ->
                    assertEquals(hex(expected), hex(Rng.mix64(input)),
                            "A failure here is almost always >> where the contract says >>>")));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("positionHash — includes negative and int-boundary coordinates")
    List<DynamicTest> positionHashVectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement element : section("position_hash")) {
            JsonObject vector = element.getAsJsonObject();
            long y = vector.get("y").getAsLong();
            long x = vector.get("x").getAsLong();
            long salt = vector.get("salt").getAsLong();
            long expected = asUnsignedHex(vector.get("hash"));
            tests.add(dynamicTest("positionHash(y=" + y + ", x=" + x + ", salt=" + salt + ")", () ->
                    assertEquals(hex(expected), hex(Rng.positionHash(y, x, salt)))));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("uniform — compared by mantissa bits")
    List<DynamicTest> uniformVectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement element : section("uniform")) {
            JsonObject vector = element.getAsJsonObject();
            long y = vector.get("y").getAsLong();
            long x = vector.get("x").getAsLong();
            long salt = vector.get("salt").getAsLong();
            long tick = vector.get("tick").getAsLong();
            long stream = vector.get("stream").getAsLong();
            long expectedBits = asUnsignedHex(vector.get("uniform_bits"));

            String name = "uniform(y=" + y + ", x=" + x + ", salt=" + salt
                    + ", tick=" + tick + ", stream=" + stream + ")";
            tests.add(dynamicTest(name, () -> {
                double actual = Rng.uniform(Rng.positionHash(y, x, salt), tick, stream);
                assertEquals(hex(expectedBits), hex(Double.doubleToRawLongBits(actual)));
            }));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("stochasticFloor — inputs reconstructed from bits, including u at exactly 0.5")
    List<DynamicTest> stochasticFloorVectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement element : section("stochastic_floor")) {
            JsonObject vector = element.getAsJsonObject();
            double value = asDoubleBits(vector, "value_bits");
            double u = asDoubleBits(vector, "u_bits");
            long expected = vector.get("result").getAsLong();
            tests.add(dynamicTest("stochasticFloor(" + value + ", " + u + ")", () ->
                    assertEquals(expected, Rng.stochasticFloor(value, u))));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("stratifiedSplit — non-negative bins summing exactly to the total")
    List<DynamicTest> stratifiedSplitVectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement element : section("stratified_split")) {
            JsonObject vector = element.getAsJsonObject();
            long total = vector.get("total").getAsLong();
            JsonArray cumfracJson = vector.getAsJsonArray("cumfrac");
            double[] cumfrac = new double[cumfracJson.size()];
            for (int i = 0; i < cumfrac.length; i++) {
                cumfrac[i] = cumfracJson.get(i).getAsDouble();
            }
            double u = asDoubleBits(vector, "u_bits");

            JsonArray expectedJson = vector.getAsJsonArray("split");
            long[] expected = new long[expectedJson.size()];
            for (int i = 0; i < expected.length; i++) {
                expected[i] = expectedJson.get(i).getAsLong();
            }

            tests.add(dynamicTest("stratifiedSplit(" + total + ", u=" + u + ")", () -> {
                long[] actual = Rng.stratifiedSplit(total, cumfrac, u);
                assertEquals(List.of(box(expected)), List.of(box(actual)));

                long sum = 0;
                for (long bin : actual) {
                    assertEquals(true, bin >= 0, "bin went negative: " + bin);
                    sum += bin;
                }
                assertEquals(total, sum, "split must be exactly conservative");
            }));
        }
        return tests;
    }

    private static Long[] box(long[] values) {
        Long[] boxed = new Long[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        return boxed;
    }

    @Test
    @DisplayName("the file holds 117 vectors; 7 end-to-end runs belong to the transport port")
    void endToEndRunsAreDeferredToTheTransportPhase() {
        int unit = section("mix64").size()
                + section("position_hash").size()
                + section("uniform").size()
                + section("stochastic_floor").size()
                + section("stratified_split").size();
        int runs = section("transport_runs").size() + section("voxel_runs").size();

        assertEquals(110, unit, "deterministic-core vectors, all asserted above");
        assertEquals(7, runs, "end-to-end runs, asserted when transport.py and voxel.py are ported");
        assertEquals(117, unit + runs);

        // Stated as an assertion rather than a comment so that the day someone ports the flux rule,
        // this test is sitting in the suite telling them which vectors are still unclaimed. The
        // runs catch neighbour-order and two-phase errors that the unit vectors cannot
        // (PORTING_SPEC §6, steps 6-7).
    }
}
