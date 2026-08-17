package com.tarosie.granularity.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tarosie.granularity.content.CompositeShapes;
import com.tarosie.granularity.core.Composition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No recipe may give back more stone than it was given.
 *
 * <p>Design §12 makes conservation the rule, and until now it was a rule stated in prose inside the
 * recipe classes and checked nowhere. That is the wrong footing for the thing that decides whether a
 * duplication exploit exists: the arithmetic lives in four separate places — the crafting ratio, the
 * shape of the pattern, the hammer yield, and the nine-ness of a block — and any one of them moving
 * can open a loop that pays.
 *
 * <p>These tests read the real numbers rather than restating them. The block count comes from walking
 * the recipe's own pattern, so a pattern edit is caught even if nobody remembers this file exists.
 *
 * <h2>What "conserved" means here</h2>
 * A cut shape carries the <b>whole</b> composition of the block it came from, because a grain does not
 * divide — so conservation cannot be checked in compositions. It is checked in <b>grains under the
 * hammer</b>: the hammer is the only way back from a shape to its constituents, so what a shape is
 * worth is what it hands back. A recipe conserves when the grains you could hammer out of its output
 * are no more than the grains you could have hammered out of its input.
 *
 * <p>Coming out <i>behind</i> is fine and expected — that difference is the cost of cutting.
 */
class ConservationTest {

    /** A cobblestone is its nine grains, which is the whole premise. */
    private static final int BLOCK = Composition.SLOTS;

    @Test
    @DisplayName("cutting slabs cannot pay: three blocks in, six slabs out")
    void slabsDoNotPay() {
        int in = SlabRecipe.INPUTS * BLOCK;
        int out = SlabRecipe.OUTPUT * CompositeShapes.SLAB_GRAINS;

        assertTrue(out <= in, "six slabs hammer out to " + out + " grains from " + in + " put in");
        assertEquals(27, in);
        assertEquals(24, out, "three grains is what cutting three blocks into slabs costs");
    }

    @Test
    @DisplayName("cutting stairs and walls cannot pay either")
    void cutShapesDoNotPay() {
        for (CutShapeRecipe.Shape shape : CutShapeRecipe.Shape.values()) {
            // Counted off the pattern the recipe actually matches, so editing the staircase without
            // revisiting its yield is caught here rather than by a player.
            int in = shape.blocksConsumed() * BLOCK;
            int out = shape.yield() * grainsOf(shape);

            assertTrue(out <= in, shape + ": " + shape.yield() + " out of " + shape.blocksConsumed()
                    + " blocks hammers back to " + out + " grains against " + in + " put in");
        }
    }

    /**
     * The two full-height shapes are break-even, and there is no slack left in either.
     *
     * <p>The stair joined the wall here when its yield went from four to six. That was the fix for a
     * real anomaly rather than a tuning choice: at vanilla's four-from-six a stair cost a block and a
     * half, so it was <i>worth</i> thirteen grains — the one shape worth more than the stone it came
     * from, and enough to make {@code block -> 1 stair} at a stonecutter a press. Six-from-six prices
     * it at one block, matching the wall.
     *
     * <p>Both being exact means the shape axis has **no headroom**. Raising either constant by one, or
     * yielding a seventh of either, turns a decoration into a grain press. Pinned deliberately.
     */
    @Test
    @DisplayName("stairs and walls are exactly break-even, and have no slack left")
    void theFullHeightShapesAreExact() {
        for (CutShapeRecipe.Shape shape : CutShapeRecipe.Shape.values()) {
            int in = shape.blocksConsumed() * BLOCK;
            int out = shape.yield() * grainsOf(shape);

            assertEquals(in, out, shape + " is break-even; any change to it starts paying out");
            assertEquals(54, in, shape + " consumes six blocks");
            assertEquals(6, shape.yield(), shape + " yields six, one per block consumed");
        }
    }

    @Test
    @DisplayName("no shape is worth more than one block, which is what makes cutting one legal")
    void noShapeIsWorthMoreThanTheBlockItCameFrom() {
        // A shape is at most a whole block, and a stonecutter consumes exactly one input — so this is
        // precisely the condition that lets *any* shape be cut from a block at all. It is also the
        // invariant the stair violated until its recipe was fixed; see theFullHeightShapesAreExact.
        assertTrue(CompositeShapes.SLAB_GRAINS < BLOCK, "a slab is less than a block");
        assertTrue(CompositeShapes.WALL_GRAINS <= BLOCK, "a wall is at most a block");
        assertTrue(CompositeShapes.STAIRS_GRAINS <= BLOCK,
                "a stair is at most a block — it was 13, and that made it uncuttable");
    }

    /**
     * The one recipe in the family priced in whole blocks, so the one that could pay by the block.
     *
     * <p>Read off the shipped JSON rather than restated, for the same reason {@code blocksConsumed}
     * is: the pattern and the yield are two numbers in two files, and nothing else notices when one
     * of them moves. Adding a third stone to the pattern without revisiting
     * {@code STONECUTTER_GRAINS} would quietly turn three blocks into two, which is a grain press
     * pointed the other way and equally worth catching.
     *
     * <p>Two is also load-bearing beyond the arithmetic: a stonecutter stores exactly two
     * compositions and draws each in its own half of the bench, so a pattern asking for three stones
     * would silently discard one. That is the failure this pins.
     */
    @Test
    @DisplayName("a stonecutter hands back exactly the two blocks it was built from")
    void theStonecutterIsBreakEven() {
        int stones = stoneIn("data/granularity/recipe/stonecutter.json");

        assertEquals(2, stones, "a stonecutter is two stones — one for each half of its bench");
        assertEquals(stones * BLOCK, CompositeShapes.STONECUTTER_GRAINS,
                "a stonecutter is break-even; change the pattern and this number has to follow");
        assertEquals(18, CompositeShapes.STONECUTTER_GRAINS);
    }

    /**
     * No cut at the stonecutter may pay — walked over the shipped files, not the generator.
     *
     * <p>This is the one that needed writing. A stonecutter consumes <b>exactly one</b> input however
     * many it yields, so unlike a crafting recipe there is no pattern to count: the arithmetic is
     * entirely "grains of the output form, times the count, against grains of the input form". Vanilla
     * cuts a stair from a single stone and we cannot, because a stair here is 13 grains and a block is
     * 9 — six blocks make four stairs, so a stair genuinely holds a block and a half.
     *
     * <p>Reading the JSON rather than asking {@code gen_cut_recipes.py} is the point. The generator
     * refuses to write a paying cut, but a recipe can also be hand-written, shipped by a datapack, or
     * left behind by an edited generator, and this catches all three.
     */
    @Test
    @DisplayName("no cut at the stonecutter yields more grains than it consumed")
    void noCutPays() {
        java.util.List<java.nio.file.Path> cuts = cutRecipes();
        assertTrue(cuts.size() > 20, "found only " + cuts.size() + " cut recipes — is the path right?");

        int reshaping = 0;
        for (java.nio.file.Path path : cuts) {
            com.google.gson.JsonObject json = read(path);
            String fromForm = json.has("from_form") ? json.get("from_form").getAsString() : "block";
            String toForm = json.has("to_form") ? json.get("to_form").getAsString() : "block";
            int count = json.has("count") ? json.get("count").getAsInt() : 1;
            int in = grainsOfForm(fromForm);
            int out = grainsOfForm(toForm) * count;

            assertTrue(out <= in, path.getFileName() + ": " + count + " " + toForm + " hammers back to "
                    + out + " grains from a " + fromForm + "'s " + in);
            if (!fromForm.equals(toForm)) {
                reshaping++;
            }
        }
        assertTrue(reshaping > 0, "no reshaping cuts found, so this test proved nothing");
    }

    /**
     * Working a style on in the crafting grid may not multiply the stone.
     *
     * <p>An {@code apply_style} recipe keeps the form and changes only the surface, so its arithmetic
     * is simply "blocks out against blocks in" — and because it is a crafting recipe rather than a cut,
     * the pattern is what states the input count. Four smooth blocks into four brick blocks is
     * break-even; four into five would be a press wearing a very ordinary-looking recipe.
     */
    @Test
    @DisplayName("working a style on in the grid never yields more blocks than it consumed")
    void applyingAStyleDoesNotMultiply() {
        java.util.List<java.nio.file.Path> recipes = recipesMatching(json ->
                "granularity:apply_style".equals(json.has("type")
                        ? json.get("type").getAsString() : null));
        assertTrue(!recipes.isEmpty(), "no apply_style recipes found, so this test proved nothing");

        for (java.nio.file.Path path : recipes) {
            com.google.gson.JsonObject json = read(path);
            int in = 0;
            for (com.google.gson.JsonElement row : json.getAsJsonArray("pattern")) {
                in += (int) row.getAsString().chars().filter(c -> c == '#').count();
            }
            int out = json.getAsJsonObject("result").get("count").getAsInt();

            assertTrue(out <= in, path.getFileName() + ": " + in + " blocks in, " + out + " out");
            assertEquals(in, out, path.getFileName()
                    + ": working a surface neither creates nor destroys stone, so this should be exact");
        }
    }

    /** What one of a form hammers back to. Mirrors {@code Form.grains()} without loading a Block. */
    private static int grainsOfForm(String form) {
        return switch (form) {
            case "block" -> BLOCK;
            case "slab" -> CompositeShapes.SLAB_GRAINS;
            case "stairs" -> CompositeShapes.STAIRS_GRAINS;
            case "wall" -> CompositeShapes.WALL_GRAINS;
            default -> org.junit.jupiter.api.Assertions.fail("unknown form in a recipe: " + form);
        };
    }

    private static java.util.List<java.nio.file.Path> cutRecipes() {
        return recipesMatching(json ->
                "granularity:stone_cut".equals(json.has("type") ? json.get("type").getAsString() : null));
    }

    /**
     * Every shipped recipe file whose JSON satisfies the test.
     *
     * <p>Selected by <b>type</b> rather than by filename. A recipe that pays does not have to be
     * politely named for it, and picking files off a prefix would have missed one dropped in under any
     * other name — which is exactly the case a conservation test exists to catch.
     */
    private static java.util.List<java.nio.file.Path> recipesMatching(
            java.util.function.Predicate<com.google.gson.JsonObject> wanted) {
        try {
            java.net.URL directory = ConservationTest.class.getClassLoader()
                    .getResource("data/granularity/recipe");
            if (directory == null) {
                return org.junit.jupiter.api.Assertions.fail("no recipes on the classpath");
            }
            try (java.util.stream.Stream<java.nio.file.Path> files =
                         java.nio.file.Files.list(java.nio.file.Path.of(directory.toURI()))) {
                return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .filter(p -> wanted.test(read(p)))
                        .sorted()
                        .toList();
            }
        } catch (Exception failed) {
            return org.junit.jupiter.api.Assertions.fail(failed);
        }
    }

    private static com.google.gson.JsonObject read(java.nio.file.Path path) {
        try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(path)) {
            return com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception failed) {
            return org.junit.jupiter.api.Assertions.fail(failed);
        }
    }

    /** How many stone slots a shaped recipe's pattern has — the '#' key, by convention here. */
    private static int stoneIn(String recipe) {
        try (java.io.InputStream in = ConservationTest.class.getClassLoader()
                .getResourceAsStream(recipe)) {
            if (in == null) {
                return org.junit.jupiter.api.Assertions.fail(recipe + " is not on the classpath");
            }
            com.google.gson.JsonObject json = com.google.gson.JsonParser
                    .parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
            int stone = 0;
            for (com.google.gson.JsonElement row : json.getAsJsonArray("pattern")) {
                stone += (int) row.getAsString().chars().filter(c -> c == '#').count();
            }
            return stone;
        } catch (Exception failed) {
            return org.junit.jupiter.api.Assertions.fail(failed);
        }
    }

    @Test
    @DisplayName("nine grains make a block and a block hammers back to nine")
    void theBaseConversionIsExact()  {
        // The one conversion that must be exactly neutral in both directions, because it is the
        // definition of what a block is rather than a recipe with a cost.
        assertEquals(BLOCK, Composition.SLOTS);
        assertEquals(9, BLOCK, "nine is the fixed-point denominator the whole system rests on");
    }

    private static int grainsOf(CutShapeRecipe.Shape shape) {
        return switch (shape) {
            case STAIRS -> CompositeShapes.STAIRS_GRAINS;
            case WALL -> CompositeShapes.WALL_GRAINS;
        };
    }
}
