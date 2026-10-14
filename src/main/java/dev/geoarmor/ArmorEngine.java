package dev.geoarmor;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.equipment.Equippable;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The reload apply step: turns the merged pack settings into validated, live per-item registrations.
 *
 * <p>Runs on the main thread from the {@code Minecraft.tick} mixin ({@link #ensureFresh} — a volatile
 * dirty check), so the first apply happens before anything renders and every later apply lands on the
 * tick after an F3+T reload finished ({@link #markDirty}, wired by the same mixin file to the reload
 * future). The resource manager read here is therefore always the current one.
 *
 * <p>Unlike an engine that duck-types into vanilla's armor dispatcher permanently, GeoArmor is
 * consulted per render: {@link #get} is a plain map lookup in the capture mixin, and a definition that
 * disappears on reload simply drops the item from {@link #registry}, restoring vanilla rendering
 * immediately. There is no "registered forever" or invisible-armor failure mode to defend against.
 */
public final class ArmorEngine {
	/** Live item → registration. Rebuilt on every apply; a missing item renders vanilla. */
	private static volatile Map<Item, RegisteredArmor> registry = Map.of();

	/** Created-once registrations, kept across reloads so renderer/proxy identity and caches are stable. */
	private static final Map<Item, RegisteredArmor> persistent = new IdentityHashMap<>();

	/** Set by the reload mixin / startup, cleared by {@link #ensureFresh}. */
	private static volatile boolean dirty = true;

	private ArmorEngine() {
	}

	/** Main-thread tick hook: applies the (possibly changed) pack state once per reload. */
	public static void ensureFresh() {
		// Armor is rendered in-world; before a world is present the item-registry
		// components are not bound yet and getDefaultInstance() would throw.
		Minecraft mc = Minecraft.getInstance();

		if (mc.level == null || mc.player == null) {
			return;
		}

		if (dirty) {
			dirty = false;
			apply();
		}
	}

	/** Marks the pack state stale — called when a resource reload has finished applying. */
	public static void markDirty() {
		dirty = true;
	}

	/** The registration for an item, or {@code null} when it has no live pack definition. */
	@Nullable
	public static RegisteredArmor get(Item item) {
		return registry.get(item);
	}

	private static void apply() {
		ResourceManager resources = Minecraft.getInstance().getResourceManager();
		Map<String, ArmorSettings.Entry> settings = ArmorSettingsIndex.load(resources);

		Map<Item, RegisteredArmor> next = new IdentityHashMap<>();

		for (ArmorSettings.Entry entry : settings.values()) {
			ArmorDefinition definition = validate(resources, entry);

			if (definition == null) {
				continue;
			}

		Item item = definition.item();
		RegisteredArmor registration = persistent.computeIfAbsent(item, key -> {
			ProxyItem proxy = new ProxyItem(ResourceKey.create(
					Registries.ITEM,
					Identifier.fromNamespaceAndPath(GeoArmor.MOD_ID, "proxy_" + persistent.size())
			));
			return new RegisteredArmor(proxy, new PackGeoArmorRenderer(new PackGeoModel(proxy)));
		});

			registration.proxy().setDefinition(definition);
			next.put(item, registration);

			if (GeoArmor.DEBUG) {
				GeoArmor.LOGGER.info(
						"{} -> {} / {} (slot {}, animation {}, translucent {}, glow {})",
						item, definition.geo(), definition.texture(), definition.slot(),
						definition.animation() != null ? definition.animation() : "off",
						definition.translucent(), definition.glow() != null ? definition.glow().kind() + " " + definition.glow().mask() : "off"
				);
			}
		}

		registry = next;

		// Masks are baked lazily, per (base, mask) pair; a reload may have replaced the pack files.
		GlowMaskBaker.invalidate();

		if (GeoArmor.DEBUG) {
			GeoArmor.LOGGER.info("ArmorEngine: {} item(s) active", registry.size());
		}
	}

	/** Validates one pack entry against the live resource manager, or {@code null} when unusable. */
	@Nullable
	private static ArmorDefinition validate(ResourceManager resources, ArmorSettings.Entry entry) {
		Identifier itemId = Identifier.tryParse(entry.itemId());

		if (itemId == null) {
			GeoArmor.LOGGER.warn("{}: not a usable item id, entry skipped", entry.itemId());
			return null;
		}

		Item item = BuiltInRegistries.ITEM.get(itemId).map(reference -> (Item) reference.value()).orElse(null);

		if (item == null) {
			GeoArmor.LOGGER.warn("{}: no such item registered, entry skipped", entry.itemId());
			return null;
		}

		Equippable equippable = item.getDefaultInstance().get(DataComponents.EQUIPPABLE);

		if (equippable == null) {
			GeoArmor.LOGGER.warn("{}: item is not equippable armor, entry skipped", entry.itemId());
			return null;
		}

		Identifier modelFile = ArmorSettings.modelFile(entry.geo());

		if (!assetExists(resources, modelFile)) {
			GeoArmor.LOGGER.warn("{}: geo '{}' has no {}, entry skipped", entry.itemId(), entry.geo(), modelFile);
			return null;
		}

		Identifier animation = entry.animation();

		if (animation != null && !assetExists(resources, ArmorSettings.animationFile(animation))) {
			GeoArmor.LOGGER.warn("{}: animation '{}' has no {}, rendering static", entry.itemId(), animation, ArmorSettings.animationFile(animation));
			animation = null;
		}

		if (resources.getResource(entry.texture()).isEmpty()) {
			GeoArmor.LOGGER.warn("{}: texture '{}' does not exist, will render missing-texture", entry.itemId(), entry.texture());
		}

		ArmorSettings.Glow glow = entry.glow();

		if (glow != null) {
			// The explicit mask, or the derived <texture>_glowmask.png. Keeping the resolved id on the
			// definition means the render layer never has to re-derive it per frame.
			Identifier mask = glow.mask() != null ? glow.mask() : ArmorSettings.glowMask(entry.texture());

			if (resources.getResource(mask).isEmpty()) {
				// A glow without a mask has no shape, so the whole piece glows: the layer draws the base
				// texture itself. The warning is the only sign the pack is missing the mask file.
				GeoArmor.LOGGER.warn("{}: glow mask '{}' does not exist, the whole piece will glow", entry.itemId(), mask);
				glow = new ArmorSettings.Glow(glow.kind(), null);
			} else {
				glow = new ArmorSettings.Glow(glow.kind(), mask);
			}
		}

		return new ArmorDefinition(item, equippable.slot(), entry.geo(), entry.texture(), animation, entry.idle(), entry.display(), entry.translucent(), glow);
	}

	/**
	 * Whether GeckoLib will bake a file for this asset. GeckoLib keys assets by extension-stripped path
	 * and accepts a plain {@code .json} as well as the canonical double extension, so both spellings
	 * count as present.
	 */
	private static boolean assetExists(ResourceManager resources, Identifier canonical) {
		if (resources.getResource(canonical).isPresent()) {
			return true;
		}

		String path = canonical.getPath();
		int dot = path.indexOf('.');
		Identifier plain = canonical.withPath(dot < 0 ? path + ".json" : path.substring(0, dot) + ".json");
		return resources.getResource(plain).isPresent();
	}
}
