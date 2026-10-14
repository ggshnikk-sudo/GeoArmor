package dev.geoarmor;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bakes a glow mask with the base texture into one drawable texture: the mask's alpha decides
 * <em>where</em> the glow lands and the base texture's colour decides <em>what colour</em> it is.
 *
 * <p>This is what lets a mask be a plain white cutout: a mask is a shape, not a palette, so the glow
 * keeps the armour's own colours instead of a flat white. It is the same composite Armor Model API
 * builds ({@code mask.alpha | base.rgb}).
 *
 * <p>One bake per (base, mask) pair, kept in a static cache and dropped by {@link #invalidate()} — which
 * {@link ArmorEngine} calls on every reload, since a reload may have replaced either file. The baked
 * texture is registered in the texture manager under a stable id, so re-baking replaces (and closes) the
 * previous texture instead of leaking one per reload.
 */
public final class GlowMaskBaker {
	/** The two source textures of one bake. */
	private record Key(Identifier base, Identifier mask) {
	}

	/** Baked results by source pair; an empty value remembers a failure instead of retrying per frame. */
	private static final Map<Key, Optional<Identifier>> BAKED = new ConcurrentHashMap<>();

	/** Bumped by {@link #invalidate()}; the cache is dropped on the next bake that sees a new value. */
	private static final AtomicLong GENERATION = new AtomicLong();

	private static volatile long bakedGeneration = -1L;

	private GlowMaskBaker() {
	}

	/** Drops every baked texture; called when a resource reload may have changed the sources. */
	static void invalidate() {
		GENERATION.incrementAndGet();
	}

	/** The baked texture for {@code (base, mask)}, or {@code null} when either file cannot be read. */
	@Nullable
	public static Identifier bake(Identifier base, Identifier mask) {
		if (bakedGeneration != GENERATION.get()) {
			synchronized (GlowMaskBaker.class) {
				if (bakedGeneration != GENERATION.get()) {
					BAKED.clear();
					bakedGeneration = GENERATION.get();
				}
			}
		}

		return BAKED.computeIfAbsent(new Key(base, mask), GlowMaskBaker::create).orElse(null);
	}

	@Nullable

	/** The one pixel of the composite: the mask's alpha over the base texture's colour. */
	static int compositePixel(int baseArgb, int maskArgb) {
		return (maskArgb & 0xFF000000) | (baseArgb & 0x00FFFFFF);
	}

	private static Optional<Identifier> create(Key key) {
		ResourceManager resources = Minecraft.getInstance().getResourceManager();

		try (NativeImage base = read(resources, key.base()); NativeImage mask = read(resources, key.mask())) {
			if (base == null || mask == null) {
				// ArmorEngine validates the mask at reload time, so this is a file that vanished (or a
				// pack that never had one) between the reload and the first frame that asked for it.
				GeoArmor.LOGGER.warn("glow textures '{}' / '{}' not readable, skipping the glow pass", key.base(), key.mask());
				return Optional.empty();
			}

			if (base.getWidth() != mask.getWidth() || base.getHeight() != mask.getHeight()) {
				GeoArmor.LOGGER.warn(
						"glow mask '{}' is {}x{} but base texture '{}' is {}x{}, skipping the glow pass",
						key.mask(), mask.getWidth(), mask.getHeight(), key.base(), base.getWidth(), base.getHeight()
				);
				return Optional.empty();
			}

			NativeImage composite = new NativeImage(base.getWidth(), base.getHeight(), false);

			for (int y = 0; y < base.getHeight(); y++) {
				for (int x = 0; x < base.getWidth(); x++) {
					composite.setPixel(x, y, compositePixel(base.getPixel(x, y), mask.getPixel(x, y)));
				}
			}

			Identifier id = bakedId(key);
			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, composite));

			if (GeoArmor.DEBUG) {
				GeoArmor.LOGGER.info("baked glow texture {} from {} + {}", id, key.base(), key.mask());
			}

			return Optional.of(id);
		} catch (IOException e) {
			// Failures are cached, so log the cause once per (base, mask) instead of every frame.
			GeoArmor.LOGGER.warn("failed to bake the glow texture from '{}' + '{}', skipping the glow pass", key.base(), key.mask(), e);
			return Optional.empty();
		}
	}

	/** A stable id for the baked pair: same two sources in, same id out, across reloads. */
	private static Identifier bakedId(Key key) {
		return Identifier.fromNamespaceAndPath(
				GeoArmor.MOD_ID,
				"glow/" + key.base().getNamespace() + "/" + key.base().getPath() + "_" + key.mask().getNamespace() + "/" + key.mask().getPath()
		);
	}

	@Nullable
	private static NativeImage read(ResourceManager resources, Identifier id) throws IOException {
		Optional<Resource> resource = resources.getResource(id);

		if (resource.isEmpty()) {
			return null;
		}

		try (InputStream in = resource.get().open()) {
			return NativeImage.read(in);
		}
	}
}
