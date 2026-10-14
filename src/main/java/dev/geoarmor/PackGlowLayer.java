package dev.geoarmor;

import com.geckolib.renderer.GeoArmorRenderer;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.renderer.layer.GeoRenderLayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code glow} pass: draws the baked mask texture — the pack's mask shape carrying the base
 * texture's colours — over the armor, full brightness, then the additive burns of the
 * {@link ArmorSettings.GlowKind#RADIANT} gain on top of it.
 *
 * <p>The fill and every burn are submitted at their own draw order, never a shared one. The submit
 * dispatcher draws the submits of one order grouped by render type in an unspecified order, so a fill
 * and its burns sharing an order means the fill can land last and wipe them — and the burn then only
 * looks broken.
 *
 * <p>Attached to the renderer unconditionally (a reload can add or drop {@code glow}, and GeckoLib's
 * layer list is built in the renderer's constructor); a definition without a glow simply submits
 * nothing. {@link GlowMaskBaker} caches one baked texture per (base, mask) pair.
 */
public class PackGlowLayer extends GeoRenderLayer<ProxyItem, GeoArmorRenderer.RenderData, HumanoidRenderState> {
	/** The radiant gain Armor Model API uses: a fill, two full burns and a half burn. */
	private static final float GAIN = 2.5F;

	/**
	 * Draw order of the glow passes. Armor Model API submits at {@code preferredOrder()} 100; the burns
	 * follow it, one order each, so they always draw after the fill they brighten.
	 */
	private static final int ORDER = 100;

	private final PackGeoArmorRenderer renderer;
	private final PackGeoModel model;

	private @Nullable Identifier loggedGlow;

	public PackGlowLayer(PackGeoArmorRenderer renderer, PackGeoModel model) {
		super(renderer);
		this.renderer = renderer;
		this.model = model;
	}

	@Override
	public void submitRenderTask(RenderPassInfo<HumanoidRenderState> renderPassInfo, SubmitNodeCollector renderTasks) {
		if (!renderPassInfo.willRender()) {
			return;
		}

		ArmorDefinition definition = this.model.definition();

		if (definition == null || definition.glow() == null) {
			return;
		}

		// A definition without a mask (the mask file was missing) glows as a whole piece: the base
		// texture is already registered, so it can be drawn as its own mask.
		Identifier mask = definition.glow().mask();
		Identifier baked = mask == null ? definition.texture() : GlowMaskBaker.bake(definition.texture(), mask);

		if (baked == null) {
			return;
		}

		if (GeoArmor.DEBUG && !baked.equals(this.loggedGlow)) {
			this.loggedGlow = baked;
			GeoArmor.LOGGER.info("glow pass: {} ({})", baked, definition.glow().kind());
		}

		this.renderer.submitGlow(renderPassInfo, renderTasks, PackRenderTypes.glow(baked),
				LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, -1, ORDER);

		if (definition.glow().kind() == ArmorSettings.GlowKind.RADIANT) {
			RenderType burn = PackRenderTypes.burn(baked);
			int order = ORDER + 1;
			float remaining = GAIN;

			while (remaining >= 1.0F) {
				this.renderer.submitGlow(renderPassInfo, renderTasks, burn,
						LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, -1, order++);
				remaining -= 1.0F;
			}

			if (remaining > 0.01F) {
				int channel = Math.round(remaining * 255.0F);
				this.renderer.submitGlow(renderPassInfo, renderTasks, burn,
						LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ARGB.color(255, channel, channel, channel), order);
			}
		}
	}
}
