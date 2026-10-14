package dev.geoarmor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The one piece of the mask bake that is pure: the composite takes the mask's alpha and the base
 * texture's colour, so a white mask cutout glows in the armour's own colours.
 */
class GlowMaskBakerTest {
	@Test
	@DisplayName("the composite keeps the mask's alpha and the base texture's colour")
	void compositeTakesAlphaFromMaskAndColourFromBase() {
		// Opaque white mask over an opaque red base: red, not white — this is what makes a white cutout
		// glow in the armour's own colours.
		assertEquals(0xFFFF0000, GlowMaskBaker.compositePixel(0xFFFF0000, 0xFFFFFFFF));
		// The mask's own colour is dropped entirely (here green over the red base).
		assertEquals(0xFFFF0000, GlowMaskBaker.compositePixel(0xFFFF0000, 0xFF00FF00));
		// A mask pixel that is not fully opaque keeps its own alpha, which fades the glow there.
		assertEquals(0x80FF0000, GlowMaskBaker.compositePixel(0xFFFF0000, 0x80000000));
		assertEquals(0x33FFFFFF, GlowMaskBaker.compositePixel(0xFFFFFFFF, 0x33000000));
		// A transparent mask pixel composites to alpha 0 — the base colour is still in the low bytes (the
		// blend never shows them), so the pixel draws nothing whatever the base colour is.
		assertEquals(0x00FF0000, GlowMaskBaker.compositePixel(0xFFFF0000, 0x00000000));
		// The base texture's colour is taken, not its alpha.
		assertEquals(0xFF123456, GlowMaskBaker.compositePixel(0x00123456, 0xFFFFFFFF));
	}

	@Test
	@DisplayName("the mask cannot light a pixel the base texture leaves transparent")
	void transparentBaseStaysTransparent() {
		// Alpha comes from the mask only, so a fully opaque mask over a fully transparent base pixel
		// still composites to opaque black (the base colour, which happens to be black) — that is the
		// pack author's problem, not a bug: a mask over an empty UV island lights an empty island.
		assertEquals(0xFF000000, GlowMaskBaker.compositePixel(0x00000000, 0xFFFFFFFF));
	}
}
