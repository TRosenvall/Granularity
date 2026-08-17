package com.tarosie.granularity.content;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every block we ship must have a name.
 *
 * <p>{@link FinishNamingTest} covers the blocks that carry a finish, against a list kept by hand.
 * This covers <b>all</b> of them, against no list at all: the blockstate files on disk are the set of
 * blocks that can appear in the world, and a block with no name key shows the player
 * {@code block.granularity.stonecutter} where a name should be. Nothing errors, nothing logs — the
 * failure mode this repo's rule about silent wiring is written about.
 *
 * <p>Driven off the resource directory rather than the registry so it needs no running game, and so
 * that adding a block is enough to be caught: there is no list here to forget to update.
 */
class BlockNamingTest {

    private static JsonObject lang() {
        try (InputStream in = BlockNamingTest.class.getClassLoader()
                .getResourceAsStream("assets/granularity/lang/en_us.json")) {
            if (in == null) {
                return fail("en_us.json is not on the classpath");
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception failed) {
            return fail(failed);
        }
    }

    /** Every block that has a blockstate, which is every block that can be placed. */
    private static List<String> blocks() {
        try {
            URL directory = BlockNamingTest.class.getClassLoader()
                    .getResource("assets/granularity/blockstates");
            if (directory == null) {
                return fail("no blockstates on the classpath — has the resource task run?");
            }
            List<String> names = new ArrayList<>();
            try (Stream<Path> files = Files.list(Path.of(directory.toURI()))) {
                files.map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".json"))
                        .map(name -> name.substring(0, name.length() - ".json".length()))
                        .sorted()
                        .forEach(names::add);
            }
            return names;
        } catch (Exception failed) {
            return fail(failed);
        }
    }

    @Test
    @DisplayName("every block with a blockstate has a name key")
    void everyBlockIsNamed() {
        JsonObject lang = lang();
        List<String> blocks = blocks();
        assertTrue(blocks.size() > 10, "found only " + blocks + " — the listing is not working");
        for (String block : blocks) {
            assertTrue(lang.has("block.granularity." + block),
                    "missing name key block.granularity." + block
                            + " — the player would be shown the raw key, and nothing would log it");
        }
    }
}
