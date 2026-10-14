package dev.geoarmor;

import net.fabricmc.api.ClientModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GeoArmor — a resource-pack-driven GeckoLib armor engine for Minecraft.
 *
 * <p>Vanilla armor is one fixed model per slot; this mod lets a resource pack assign a GeckoLib
 * (Bedrock) {@code .geo.json} model, a texture and optional animations to <em>any</em> armor item —
 * vanilla or modded — replacing the vanilla armor layer for that item with a real, animated GeckoLib
 * model. Detection is per-item at render time, so a definition removed by a pack reload instantly
 * restores vanilla rendering (there is no stale registration to fall back from).
 *
 * <p>Client side only. All the work happens at resource reload ({@link ArmorEngine}) and render time
 * (the two render mixins plus {@link PackGeoArmorRenderer}). See {@link ArmorSettings} for the pack
 * format.
 */
public final class GeoArmor implements ClientModInitializer {
	/** The mod's namespace: configs live under {@code assets/geoarmor/}. */
	public static final String MOD_ID = "geoarmor";

	public static final Logger LOGGER = LoggerFactory.getLogger("GeoArmor");

	/** Debug flag ({@code -Dgeoarmor.debug=true}): per-item resolution and capture logging. */
	public static final boolean DEBUG = Boolean.getBoolean("geoarmor.debug");

	@Override
	public void onInitializeClient() {
		// Nothing to wire up at init: ArmorEngine picks up resource packs through its tick/reload
		// mixins, and the first apply happens on the first game tick, before anything renders.
		LOGGER.info("GeoArmor engine active (debug={})", DEBUG);
	}
}
