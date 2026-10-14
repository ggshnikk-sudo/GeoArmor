package dev.geoarmor;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure merge for the settings index: files merge by item id, and the same id in two different files is
 * an authoring mistake where the later (higher-priority) file wins. Feeds the same {@code perFile}
 * shape {@link ArmorSettingsIndex#merge} consumes, without a game.
 */
class ArmorIndexMergeTest {
	@Test
	@DisplayName("distinct files and items merge into one map")
	void mergesDisjointFiles() {
		Map<Identifier, Map<String, ArmorSettings.Entry>> perFile = new LinkedHashMap<>();
		perFile.put(id("config/armor/a.json"), Map.of("minecraft:iron_helmet", entry("minecraft:iron_helmet", "geo/a.geo.json")));
		perFile.put(id("config/armor/b.json"), Map.of("minecraft:iron_chestplate", entry("minecraft:iron_chestplate", "geo/b.geo.json")));

		Map<String, ArmorSettings.Entry> merged = ArmorSettingsIndex.merge(perFile);

		assertEquals(2, merged.size());
		assertTrue(merged.containsKey("minecraft:iron_helmet"));
		assertTrue(merged.containsKey("minecraft:iron_chestplate"));
	}

	@Test
	@DisplayName("the same item id in two files is reported and the last file in iteration order wins")
	void conflictingItemKeepsLastFile() {
		Map<Identifier, Map<String, ArmorSettings.Entry>> perFile = new LinkedHashMap<>();
		perFile.put(id("config/armor/a.json"), Map.of("minecraft:iron_helmet", entry("minecraft:iron_helmet", "geo/a.geo.json")));
		perFile.put(id("config/armor/b.json"), Map.of("minecraft:iron_helmet", entry("minecraft:iron_helmet", "geo/b.geo.json")));

		Map<String, ArmorSettings.Entry> merged = ArmorSettingsIndex.merge(perFile);

		assertEquals(1, merged.size());
		assertEquals(Identifier.fromNamespaceAndPath("mypack", "geo/b.geo.json"), merged.get("minecraft:iron_helmet").geo());
	}

	@Test
	@DisplayName("an empty input yields an empty result")
	void emptyInput() {
		assertTrue(ArmorSettingsIndex.merge(Map.of()).isEmpty());
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("test", path);
	}

	private static ArmorSettings.Entry entry(String itemId, String geo) {
		return new ArmorSettings.Entry(
				itemId,
				Identifier.fromNamespaceAndPath("mypack", geo),
				Identifier.fromNamespaceAndPath("mypack", "textures/armor/x.png"),
				null,
				ArmorSettings.DEFAULT_IDLE,
				ArmorSettings.Display.DEFAULT,
				false,
				null
		);
	}

}
