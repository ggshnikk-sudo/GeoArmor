package dev.geoarmor;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.StrictJsonParser;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads the per-armor settings from {@code assets/<ns>/config/armor/*.json} across every namespace.
 *
 * <p>Every file applies, several packs merge, and for one file name (same namespace + path) the
 * highest-priority pack wins. Files nested deeper than the directory are ignored, so a pack may keep
 * its own layout beside them without being parsed as settings by accident. Two <em>different</em> files
 * declaring the same item id is a pack authoring mistake and is reported rather than silently resolved;
 * the last file in sorted order wins.
 *
 * <p>{@link #load} is the game-facing entry (it touches {@link net.minecraft.client.Minecraft}'s
 * resource manager); the per-file collection is fed into the pure {@link #merge} so the priority
 * conflict handling is unit-testable without a running game.
 */
public final class ArmorSettingsIndex {
	/** Where the settings live inside a namespace, and what a direct child looks like. */
	public static final String DIRECTORY = "config/armor";
	private static final String PREFIX = DIRECTORY + "/";

	private ArmorSettingsIndex() {
	}

	/**
	 * Discovers, parses and merges every {@code config/armor/*.json} in the client resource manager.
	 * Logs all authoring problems; never throws. Returns the settings keyed by item id (empty when no
	 * pack declares any usable armor).
	 */
	public static Map<String, ArmorSettings.Entry> load(ResourceManager resources) {
		// listResourceStacks walks the directory recursively, so the id is the full path:
		// "config/armor/leather.json". Only a direct child counts, anything deeper is not ours to read.
		Map<Identifier, List<Resource>> files = resources.listResourceStacks(DIRECTORY, ArmorSettingsIndex::isDirectChild);

		// Sorted so the merged result does not depend on map iteration order; the same item id in two
		// files is a pack authoring mistake and is reported rather than silently resolved.
		Map<Identifier, Map<String, ArmorSettings.Entry>> perFile = new TreeMap<>();

		for (Map.Entry<Identifier, List<Resource>> entry : files.entrySet()) {
			List<Resource> stack = entry.getValue();

			if (stack.isEmpty()) {
				continue;
			}

			// getResourceStack orders by pack priority, so the last one is the winner.
			Identifier fileId = entry.getKey();
			ArmorSettings.Result parsed = parse(stack.get(stack.size() - 1), fileId);

			for (String problem : parsed.problems()) {
				GeoArmor.LOGGER.warn("{}: {}", fileId, problem);
			}

			if (!parsed.items().isEmpty()) {
				perFile.put(fileId, parsed.items());
			}
		}

		Map<String, ArmorSettings.Entry> merged = merge(perFile);

		if (!merged.isEmpty()) {
			GeoArmor.LOGGER.info("Loaded {} armor setting(s) from {}/{}", merged.size(), "assets/*", DIRECTORY);
		}

		return merged;
	}

	/**
	 * Merges per-file settings into one item map. Pure: unit-tested without a game. Logs a warning
	 * when the same item id is declared in more than one file and the last file in this map's iteration
	 * order wins.
	 */
	public static Map<String, ArmorSettings.Entry> merge(Map<Identifier, Map<String, ArmorSettings.Entry>> perFile) {
		Map<String, ArmorSettings.Entry> merged = new LinkedHashMap<>();
		Map<String, Identifier> origin = new HashMap<>();

		for (Map.Entry<Identifier, Map<String, ArmorSettings.Entry>> file : perFile.entrySet()) {
			for (Map.Entry<String, ArmorSettings.Entry> setting : file.getValue().entrySet()) {
				Identifier previous = origin.put(setting.getKey(), file.getKey());

				if (previous != null) {
					GeoArmor.LOGGER.warn(
							"Armor item '{}' is declared in both {} and {}; {} wins",
							setting.getKey(), previous, file.getKey(), file.getKey()
					);
				}

				merged.put(setting.getKey(), setting.getValue());
			}
		}

		return merged;
	}

	private static boolean isDirectChild(Identifier id) {
		String path = id.getPath();
		return path.startsWith(PREFIX) && !path.substring(PREFIX.length()).contains("/");
	}

	private static ArmorSettings.Result parse(Resource resource, Identifier fileId) {
		try (Reader reader = resource.openAsReader()) {
			return ArmorSettings.parse(fileId, StrictJsonParser.parse(reader));
		} catch (IOException | RuntimeException e) {
			GeoArmor.LOGGER.warn("{}: failed to read ({})", fileId, e.getMessage());
			return new ArmorSettings.Result(new LinkedHashMap<>(), List.of(fileId + ": unreadable"), false);
		}
	}
}
