package dev.geoarmor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The debug pivot line is what tells "the piece rotates in place" apart from "the bone is missing" (the
 * latter is {@link PackGeoArmorRenderer}'s warning), and the README quotes its exact shape — so the
 * format is pinned here, not just inside the renderer.
 */
class PackGeoArmorRendererTest {
	@Test
	@DisplayName("a pivot is printed as the README shows it, with the mirroring's -0.0 collapsed")
	void pivotIsPrintedInTheDocumentedShape() {
		assertEquals("armorBody=[0.0, 24.0, 0.0]", PackGeoArmorRenderer.formatPivot("armorBody", -0.0f, 24.0f, 0.0f));
		// The x mirroring negates on load, so a file-side [5, 22, 0] reaches the line as -5.0 (README:
		// "числа по X идут со знаком, обратным .geo.json").
		assertEquals("armorLeftArm=[-5.0, 22.0, 0.0]", PackGeoArmorRenderer.formatPivot("armorLeftArm", -5.0f, 22.0f, 0.0f));
		assertEquals("armorLeftBoot=[-2.0, 12.0, 0.0]", PackGeoArmorRenderer.formatPivot("armorLeftBoot", -2.0f, 12.0f, 0.0f));
	}
}
