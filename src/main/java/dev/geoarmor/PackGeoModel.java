package dev.geoarmor;

import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderState;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

/**
 * The geometry/texture/animation source for one resource-pack armor item, reading the live
 * {@link ArmorDefinition} off the {@link ProxyItem} that owns it.
 *
 * <p>The model is created once per item and never replaced; only the definition it reads changes at
 * reload, so GeckoLib's per-item caches are not disturbed by a pack reload.
 */
public final class PackGeoModel extends GeoModel<ProxyItem> {
	/**
	 * Placeholder animation asset id used when a piece is static. The idle controller stops instead of
	 * playing, so GeckoLib never resolves it; it is a bare id, not a file path, because that is what
	 * {@link ArmorSettings#geckoAssetId} hands GeckoLib for real animations too.
	 */
	private static final Identifier NO_ANIMATION = Identifier.fromNamespaceAndPath(GeoArmor.MOD_ID, "empty");

	private final ProxyItem proxy;

	public PackGeoModel(ProxyItem proxy) {
		this.proxy = proxy;
	}

	@Override
	public Identifier getModelResource(GeoRenderState renderState) {
		return this.proxy.definition().geo();
	}

	/** The live definition backing this model, or {@code null} before the first apply. */
	@Nullable
	public ArmorDefinition definition() {
		return this.proxy.definition();
	}

	@Override
	public Identifier getTextureResource(GeoRenderState renderState) {
		return this.proxy.definition().texture();
	}

	@Override
	public Identifier getAnimationResource(ProxyItem animatable) {
		ArmorDefinition def = animatable.definition();
		return def != null && def.animation() != null ? def.animation() : NO_ANIMATION;
	}
}
