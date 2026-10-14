package dev.geoarmor;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser contract for per-armor-item render settings: {@code geo} is required, {@code texture}
 * defaults to beside the geometry, {@code animation}/{@code idle} are optional, and authoring mistakes
 * are collected as problems. Pure over Gson — no game singletons.
 */
class ArmorSettingsTest {
	@Test
	@DisplayName("display is the identity when unwritten, and is read as written when present")
	void display() {
		ArmorSettings.Entry plain = only(parse("""
				{"armor": {"minecraft:iron_helmet": {"geo": "mypack:hood"}}}
				"""));
		assertTrue(plain.display().isDefault());

		ArmorSettings.Entry placed = only(parse("""
				{"armor": {"minecraft:iron_helmet": {
					"geo": "mypack:hood",
					"display": {"translate": [1.5, -2.75, 0], "rotate": [0, 90, 0], "scale": 1.6}
				}}}
				"""));

		assertEquals(1.5F, placed.display().translateX());
		assertEquals(-2.75F, placed.display().translateY());
		assertEquals(0.0F, placed.display().translateZ());
		assertEquals(90.0F, placed.display().rotateY());
		assertEquals(1.6F, placed.display().scaleX());
		assertEquals(1.6F, placed.display().scaleY());
		assertEquals(1.6F, placed.display().scaleZ());
	}

	@Test
	@DisplayName("display.scale accepts one number or three; an unreadable display is reported, not fatal")
	void displayScaleAndMalformed() {
		ArmorSettings.Entry uniform = only(parse("""
				{"armor": {"minecraft:iron_helmet": {"geo": "mypack:hood", "display": {"scale": 2}}}}
				"""));
		assertEquals(2.0F, uniform.display().scaleX());
		assertEquals(2.0F, uniform.display().scaleZ());

		ArmorSettings.Entry perAxis = only(parse("""
				{"armor": {"minecraft:iron_helmet": {"geo": "mypack:hood", "display": {"scale": [2, 3, 4]}}}}
				"""));
		assertEquals(2.0F, perAxis.display().scaleX());
		assertEquals(4.0F, perAxis.display().scaleZ());

		ArmorSettings.Result shortVector = parse("""
				{"armor": {"minecraft:iron_helmet": {"geo": "mypack:hood", "display": {"translate": [1, 2]}}}}
				""");
		assertEquals(1, shortVector.problems().size());
		assertTrue(shortVector.items().get("minecraft:iron_helmet").display().isDefault());

		ArmorSettings.Result notAnObject = parse("""
				{"armor": {"minecraft:iron_helmet": {"geo": "mypack:hood", "display": "nope"}}}
				""");
		assertEquals(1, notAnObject.problems().size());
		assertTrue(notAnObject.items().get("minecraft:iron_helmet").display().isDefault());
	}

	@Test
	@DisplayName("a minimal valid entry needs only geo; texture defaults beside the geo, idle defaults")
	void minimalEntry() {
		ArmorSettings.Result result = parse("""
				{"armor": {"minecraft:iron_helmet": {"geo": "mypack:geo/hood.geo.json"}}}
				""");

		assertEquals(List.of(), result.problems(), result.problems().toString());
		assertTrue(result.declared());

		ArmorSettings.Entry entry = only(result);
		assertEquals("minecraft:iron_helmet", entry.itemId());
		assertEquals(Identifier.fromNamespaceAndPath("mypack", "hood"), entry.geo());
		assertEquals(Identifier.fromNamespaceAndPath("mypack", "textures/armor/hood.png"), entry.texture());
		assertNull(entry.animation());
		assertEquals(ArmorSettings.DEFAULT_IDLE, entry.idle());
	}

	@Test
	@DisplayName("an explicit texture, animation and idle are used as written, the animation reduced to its key")
	void explicitFields() {
		ArmorSettings.Entry entry = only(parse("""
				{"armor": {"minecraft:iron_helmet": {
					"geo": "mypack:geo/hood.geo.json",
					"texture": "mypack:textures/armor/blue.png",
					"animation": "mypack:animations/hood.animation.json",
					"idle": "animation.hood.idle"
				}}}
				"""));

		assertEquals(Identifier.fromNamespaceAndPath("mypack", "textures/armor/blue.png"), entry.texture());
		assertEquals(Identifier.fromNamespaceAndPath("mypack", "hood"), entry.animation());
		assertEquals("animation.hood.idle", entry.idle());
	}

	@Test
	@DisplayName("an entry without geo is skipped with a problem; the rest still load")
	void missingGeoIsSkipped() {
		ArmorSettings.Result result = parse("""
				{"armor": {
					"minecraft:iron_helmet": {"texture": "mypack:textures/armor/x.png"},
					"minecraft:iron_chestplate": {"geo": "mypack:geo/chest.geo.json"}
				}}
				""");

		assertEquals(1, result.problems().size());
		assertTrue(result.problems().get(0).contains("geo"));
		assertEquals(1, result.items().size());
		assertTrue(result.items().containsKey("minecraft:iron_chestplate"));
	}

	@Test
	@DisplayName("a geo that is not a string, or not a usable id, is skipped with a problem")
	void badGeo() {
		ArmorSettings.Result numeric = parse("""
				{"armor": {"minecraft:iron_helmet": {"geo": 7}}}
				""");
		assertEquals(1, numeric.problems().size());
		assertTrue(numeric.items().isEmpty());

		ArmorSettings.Result unparseable = parse("""
				{"armor": {"minecraft:iron_helmet": {"geo": "not an id"}}}
				""");
		assertEquals(1, unparseable.problems().size());
		assertTrue(unparseable.problems().get(0).contains("not a usable id"));
		assertTrue(unparseable.items().isEmpty());
	}

	@Test
	@DisplayName("an invalid texture falls back to beside the geo, with a problem")
	void badTextureFallsBack() {
		ArmorSettings.Result result = parse("""
				{"armor": {"minecraft:iron_helmet": {"geo": "mypack:geo/hood.geo.json", "texture": "not an id"}}}
				""");

		assertEquals(1, result.problems().size());
		assertEquals(
				Identifier.fromNamespaceAndPath("mypack", "textures/armor/hood.png"),
				only(result).texture()
		);
	}

	@Test
	@DisplayName("an unparseable animation is reported and treated as absent (static)")
	void badAnimationIsDropped() {
		ArmorSettings.Result result = parse("""
				{"armor": {"minecraft:iron_helmet": {"geo": "mypack:geo/hood.geo.json", "animation": 42}}}
				""");

		assertEquals(1, result.problems().size());
		assertNull(only(result).animation());
	}

	@Test
	@DisplayName("translucent and glow are off unless asked for")
	void renderModes() {
		ArmorSettings.Entry plain = only(parse("""
				{"armor": {"minecraft:iron_helmet": {"geo": "mypack:hood"}}}
				"""));
		assertFalse(plain.translucent());
		assertNull(plain.glow());

		ArmorSettings.Entry on = only(parse("""
				{"armor": {"minecraft:iron_helmet": {
					"geo": "mypack:hood",
					"texture": "mypack:textures/armor/blue.png",
					"translucent": true,
					"glow": true
				}}}
				"""));
		assertTrue(on.translucent());
		// No explicit mask: the render layer derives <texture>_glowmask.png, which glowMask pins down.
		assertEquals(new ArmorSettings.Glow(ArmorSettings.GlowKind.GLOW, null), on.glow());
		assertEquals(
				Identifier.fromNamespaceAndPath("mypack", "textures/armor/blue_glowmask.png"),
				ArmorSettings.glowMask(on.texture())
		);
	}

	@Test
	@DisplayName("glow takes a mode string, and an object takes an explicit mask plus an optional mode")
	void glowForms() {
		ArmorSettings.Entry off = only(parse("""
				{"armor": {"minecraft:iron_helmet": {"geo": "mypack:hood", "glow": false}}}
				"""));
		assertNull(off.glow());

		ArmorSettings.Entry named = only(parse("""
				{"armor": {"minecraft:iron_helmet": {"geo": "mypack:hood", "glow": "glow"}}}
				"""));
		assertEquals(new ArmorSettings.Glow(ArmorSettings.GlowKind.GLOW, null), named.glow());

		ArmorSettings.Entry radiant = only(parse("""
				{"armor": {"minecraft:iron_helmet": {"geo": "mypack:hood", "glow": "radiant"}}}
				"""));
		assertEquals(new ArmorSettings.Glow(ArmorSettings.GlowKind.RADIANT, null), radiant.glow());

		ArmorSettings.Entry explicit = only(parse("""
				{"armor": {"minecraft:iron_helmet": {
					"geo": "mypack:hood",
					"glow": {"mask": "mypack:textures/armor/runes.png"}
				}}}
				"""));
		assertEquals(
				new ArmorSettings.Glow(ArmorSettings.GlowKind.GLOW, Identifier.fromNamespaceAndPath("mypack", "textures/armor/runes.png")),
				explicit.glow()
		);

		ArmorSettings.Entry explicitRadiant = only(parse("""
				{"armor": {"minecraft:iron_helmet": {
					"geo": "mypack:hood",
					"glow": {"mask": "mypack:textures/armor/runes.png", "mode": "radiant"}
				}}}
				"""));
		assertEquals(
				new ArmorSettings.Glow(ArmorSettings.GlowKind.RADIANT, Identifier.fromNamespaceAndPath("mypack", "textures/armor/runes.png")),
				explicitRadiant.glow()
		);
	}

	@Test
	@DisplayName("a malformed translucent or glow is reported and defaulted")
	void badRenderModes() {
		ArmorSettings.Result result = parse("""
				{"armor": {"minecraft:iron_helmet": {
					"geo": "mypack:hood",
					"translucent": "yes",
					"glow": 12
				}}}
				""");

		assertEquals(2, result.problems().size(), result.problems().toString());
		ArmorSettings.Entry entry = only(result);
		assertFalse(entry.translucent());
		assertNull(entry.glow());
	}

	@Test
	@DisplayName("a glow mask object without a usable mask, and an unknown mode name, are reported")
	void badGlow() {
		ArmorSettings.Result noMask = parse("""
				{"armor": {"minecraft:iron_helmet": {"geo": "mypack:hood", "glow": {"mode": "radiant"}}}}
				""");
		assertEquals(1, noMask.problems().size(), noMask.problems().toString());
		assertNull(only(noMask).glow());

		ArmorSettings.Result badMask = parse("""
				{"armor": {"minecraft:iron_helmet": {"geo": "mypack:hood", "glow": {"mask": 7}}}}
				""");
		assertEquals(1, badMask.problems().size(), badMask.problems().toString());
		assertNull(only(badMask).glow());

		ArmorSettings.Result badMode = parse("""
				{"armor": {"minecraft:iron_helmet": {"geo": "mypack:hood", "glow": "shiny"}}}
				""");
		assertEquals(1, badMode.problems().size(), badMode.problems().toString());
		assertNull(only(badMode).glow());
	}

	@Test
	@DisplayName("glowMask appends _glowmask before the extension")
	void glowMaskForms() {
		assertEquals(
				Identifier.fromNamespaceAndPath("mypack", "textures/armor/set_glowmask.png"),
				ArmorSettings.glowMask(Identifier.fromNamespaceAndPath("mypack", "textures/armor/set.png"))
		);
		assertEquals(
				Identifier.fromNamespaceAndPath("mypack", "textures/armor/set_glowmask.png"),
				ArmorSettings.glowMask(Identifier.fromNamespaceAndPath("mypack", "textures/armor/set"))
		);
	}

	@Test
	@DisplayName("a non-string idle is reported and the default is used")
	void badIdleFallsBack() {
		ArmorSettings.Result result = parse("""
				{"armor": {"minecraft:iron_helmet": {"geo": "mypack:geo/hood.geo.json", "idle": 3}}}
				""");

		assertEquals(1, result.problems().size());
		assertEquals(ArmorSettings.DEFAULT_IDLE, only(result).idle());
	}

	@Test
	@DisplayName("keys from other engines are ignored, not reported")
	void foreignKeysIgnored() {
		ArmorSettings.Result result = parse("""
				{"armor": {"minecraft:iron_helmet": {
					"geo": "mypack:geo/hood.geo.json",
					"trim": "mypack:armor/trim/hood_generic",
					"trimPatterns": false
				}}}
				""");

		assertEquals(List.of(), result.problems(), result.problems().toString());
		assertEquals(1, result.items().size());
	}

	@Test
	@DisplayName("textureBeside maps a geo key to textures/armor/<name>.png, dropping any directory")
	void textureBesideForms() {
		assertEquals(
				Identifier.fromNamespaceAndPath("mypack", "textures/armor/hood.png"),
				ArmorSettings.textureBeside(Identifier.fromNamespaceAndPath("mypack", "hood"))
		);
		assertEquals(
				Identifier.fromNamespaceAndPath("mypack", "textures/armor/hood.png"),
				ArmorSettings.textureBeside(Identifier.fromNamespaceAndPath("mypack", "geo/hood.geo.json"))
		);
		assertEquals(
				Identifier.fromNamespaceAndPath("mypack", "textures/armor/hat.png"),
				ArmorSettings.textureBeside(Identifier.fromNamespaceAndPath("mypack", "models/hat.geo.json"))
		);
		assertEquals(
				Identifier.fromNamespaceAndPath("mypack", "textures/armor/hat.png"),
				ArmorSettings.textureBeside(Identifier.fromNamespaceAndPath("mypack", "armor/hat"))
		);
	}

	@Test
	@DisplayName("geckoAssetId reduces every written spelling to the baked cache key and is idempotent")
	void geckoAssetIdForms() {
		Identifier key = Identifier.fromNamespaceAndPath("mypack", "hood");

		assertEquals(key, ArmorSettings.geckoAssetId(Identifier.fromNamespaceAndPath("mypack", "hood")));
		assertEquals(key, ArmorSettings.geckoAssetId(Identifier.fromNamespaceAndPath("mypack", "geo/hood.geo.json")));
		assertEquals(key, ArmorSettings.geckoAssetId(Identifier.fromNamespaceAndPath("mypack", "models/hood.geo.json")));
		assertEquals(key, ArmorSettings.geckoAssetId(Identifier.fromNamespaceAndPath("mypack", "geckolib/models/hood.geo.json")));
		assertEquals(key, ArmorSettings.geckoAssetId(Identifier.fromNamespaceAndPath("mypack", "geckolib/animations/hood.animation.json")));
		assertEquals(key, ArmorSettings.geckoAssetId(Identifier.fromNamespaceAndPath("mypack", "animations/hood.animation.json")));
		assertEquals(key, ArmorSettings.geckoAssetId(Identifier.fromNamespaceAndPath("mypack", "hood.json")));
		assertEquals(key, ArmorSettings.geckoAssetId(Identifier.fromNamespaceAndPath("mypack", "geckolib/models/hood")));
		assertEquals(key, ArmorSettings.geckoAssetId(key));
	}

	@Test
	@DisplayName("modelFile/animationFile give the paths GeckoLib bakes the key from, for validation")
	void assetFileLocations() {
		Identifier key = Identifier.fromNamespaceAndPath("mypack", "armor/hood");

		assertEquals(
				Identifier.fromNamespaceAndPath("mypack", "geckolib/models/armor/hood.geo.json"),
				ArmorSettings.modelFile(key)
		);
		assertEquals(
				Identifier.fromNamespaceAndPath("mypack", "geckolib/animations/armor/hood.animation.json"),
				ArmorSettings.animationFile(key)
		);
	}

	@Test
	@DisplayName("a root that is not an object reports declared=false, without throwing")
	void notAnObject() {
		ArmorSettings.Result result = parse("[1, 2, 3]");
		assertFalse(result.declared());
		assertTrue(result.items().isEmpty());
		assertEquals(1, result.problems().size());
	}

	@Test
	@DisplayName("a file without an armor object reports declared=false")
	void noArmorKey() {
		ArmorSettings.Result result = parse("{\"hats\": {}}");
		assertFalse(result.declared());
		assertTrue(result.items().isEmpty());
		assertEquals(1, result.problems().size());
	}

	@Test
	@DisplayName("a non-object entry is skipped with a problem")
	void entryNotAnObject() {
		ArmorSettings.Result result = parse("""
				{"armor": {"minecraft:iron_helmet": "hello"}}
				""");

		assertEquals(1, result.problems().size());
		assertTrue(result.items().isEmpty());
	}

	private static ArmorSettings.Entry only(ArmorSettings.Result result) {
		assertEquals(1, result.items().size(), () -> "expected one entry, got " + result.items().keySet());
		return result.items().values().iterator().next();
	}

	private static ArmorSettings.Result parse(String json) {
		return ArmorSettings.parse(
				Identifier.fromNamespaceAndPath("test", "config/armor/sample.json"),
				com.google.gson.JsonParser.parseString(json)
		);
	}
}
