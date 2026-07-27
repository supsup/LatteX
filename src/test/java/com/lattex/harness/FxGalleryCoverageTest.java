package com.lattex.harness;

import com.lattex.parse.Effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Build-failing contract between the production {@link Effect} vocabulary and
 * the visual catalogue in {@code examples/GALLERY.md}.
 *
 * <p>The gallery is documentation, not a byte-golden suite, but its coverage is
 * executable: every production effect other than {@code NONE} must declare its
 * authored directive, real interaction path, committed BrewShot artifact, and
 * representation. A future enum addition therefore cannot silently ship with
 * the catalogue still claiming completeness.
 */
class FxGalleryCoverageTest {

    enum Representation {
        MOTION_GIF,
        INTERACTION_STILL
    }

    record Specimen(String directive, String trigger, String asset,
                    Representation representation, String rationale) {
    }

    private static final Map<Effect, Specimen> SPECIMENS = specimens();

    private static Map<Effect, Specimen> specimens() {
        EnumMap<Effect, Specimen> m = new EnumMap<>(Effect.class);
        add(m, Effect.BOOM, "fx.click=boom", "trusted click", "boom.gif");
        add(m, Effect.PULSE, "fx.hover=pulse", "trusted hover", "pulse.gif");
        add(m, Effect.FADE, "fx.enter=fade", "page entry", "fade.gif");
        add(m, Effect.GLOW, "fx.click=glow", "trusted click", "glow.gif");
        add(m, Effect.LIGHTNING, "fx.click=lightning", "trusted click", "lightning.gif");
        add(m, Effect.STORM, "fx.hover=storm", "trusted hover", "storm.gif");
        add(m, Effect.HANDSCRIBE, "fx.enter=handscribe", "page entry", "handscribe.gif");
        add(m, Effect.HOLOGRAM, "fx.enter=hologram", "page entry", "hologram.gif");
        add(m, Effect.NEONSIGN, "fx.enter=neonsign", "page entry", "neonsign.gif");
        add(m, Effect.CRYSTALLIZE, "fx.enter=crystallize", "page entry", "crystallize.gif");
        add(m, Effect.BLUEPRINT, "fx.enter=blueprint", "page entry", "blueprint.gif");
        add(m, Effect.WOBBLE, "fx.enter=wobble", "page entry", "wobble.gif");
        add(m, Effect.GRAVWELL, "fx.enter=gravwell", "entry arms, trusted glyph click fires",
            "gravwell.gif");
        add(m, Effect.MATRIXRAIN, "fx.enter=matrixrain", "page entry", "matrixrain.gif");
        add(m, Effect.SUPERNOVA, "fx.click=supernova", "trusted click", "supernova.gif");
        add(m, Effect.INKDROP, "fx.enter=inkdrop", "page entry, compositor stream",
            "inkdrop.gif");
        add(m, Effect.DIFFUSION, "fx.hover=diffusion", "trusted hover", "diffusion.gif");
        add(m, Effect.REFRACTION, "fx.hover=refraction", "trusted moving pointer",
            "refraction.gif");
        add(m, Effect.TELEPORT, "fx.click=teleport", "trusted click", "teleport.gif");
        add(m, Effect.SHATTER, "fx.click=shatter", "trusted click", "shatter.gif");
        add(m, Effect.GLITCH, "fx.hover=glitch", "trusted hover", "glitch.gif");
        add(m, Effect.SPARKLER, "fx.enter=sparkler", "page entry", "sparkler.gif");
        add(m, Effect.QUANTUM, "fx.enter=quantum", "page entry", "quantum.gif");
        add(m, Effect.TYPESET, "fx.click=typeset", "trusted click", "typeset.gif");
        add(m, Effect.CONSTELLATION, "fx.enter=constellation", "page entry",
            "constellation.gif");
        m.put(Effect.THREAD, new Specimen(
            "fx.thread", "trusted glyph hover", "thread-preview.png",
            Representation.INTERACTION_STILL,
            "The armed still keeps all three matching glyphs visible at once, which reads the"
                + " semantic relationship more clearly than a looping transition."));
        add(m, Effect.PRECEDENCE, "fx.enter=precedence", "public play plus entry autoplay",
            "fx-play-precedence.gif");
        add(m, Effect.CANCEL, "fx.enter=cancel", "deterministic semantic entry", "cancel.gif");
        add(m, Effect.UNFOLD, "fx.click=unfold", "host flag plus trusted click toggle",
            "unfold.gif");
        return Map.copyOf(m);
    }

    private static void add(Map<Effect, Specimen> m, Effect effect, String directive,
                            String trigger, String asset) {
        m.put(effect, new Specimen(directive, trigger, asset, Representation.MOTION_GIF,
            "BrewShot records the production runtime through its " + trigger + " path."));
    }

    @Test
    void everyProductionEffectHasExactlyOneDeclaredVisualSpecimen() {
        EnumSet<Effect> expected = EnumSet.allOf(Effect.class);
        expected.remove(Effect.NONE);

        assertEquals(expected, SPECIMENS.keySet(),
            "the gallery manifest must cover every non-NONE production Effect exactly once");
        assertFalse(SPECIMENS.containsKey(Effect.NONE),
            "NONE is the deliberate no-effect token and must not masquerade as a specimen");

        Set<String> assets = new HashSet<>();
        for (Map.Entry<Effect, Specimen> entry : SPECIMENS.entrySet()) {
            Specimen specimen = entry.getValue();
            assertTrue(assets.add(specimen.asset()),
                "visual assets must be one-to-one; duplicate " + specimen.asset());
            assertFalse(specimen.directive().isBlank(), entry.getKey() + " directive is blank");
            assertFalse(specimen.trigger().isBlank(), entry.getKey() + " trigger is blank");
            assertFalse(specimen.rationale().isBlank(), entry.getKey() + " rationale is blank");
            String extension = specimen.representation() == Representation.MOTION_GIF
                ? ".gif" : ".png";
            assertTrue(specimen.asset().endsWith(extension),
                entry.getKey() + " representation/extension drift: " + specimen.asset());
        }
    }

    @Test
    void everyDeclaredAssetExistsAndTheGalleryLinksIt() throws IOException {
        Path examples = Path.of("examples");
        String gallery = Files.readString(examples.resolve("GALLERY.md"));

        for (Map.Entry<Effect, Specimen> entry : SPECIMENS.entrySet()) {
            Specimen specimen = entry.getValue();
            Path asset = examples.resolve(specimen.asset());
            assertTrue(Files.isRegularFile(asset),
                entry.getKey() + " points at a missing committed visual: " + asset);
            assertTrue(Files.size(asset) > 5_000,
                entry.getKey() + " visual is suspiciously small: " + asset);
            assertTrue(gallery.contains("`" + specimen.directive() + "`"),
                "GALLERY.md does not document " + entry.getKey() + " as `"
                    + specimen.directive() + "`");
            assertTrue(gallery.contains("]("
                    + specimen.asset() + ")"),
                "GALLERY.md does not link " + entry.getKey() + " asset " + specimen.asset());
        }
    }
}
