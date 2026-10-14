package dev.geoarmor;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderSetup.OutlineProperty;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The render types an armor entry can ask for beyond GeckoLib's default cutout: the pack's glow pass
 * ({@link #glow}) and, for {@code "radiant"}, the additive burn drawn on top of it ({@link #burn}).
 *
 * <p>{@link RenderType} instances are cached (building one per frame would re-create a render type and
 * its setup every frame) and built lazily on the render thread, the same way GeckoLib builds its own
 * emissive type. Both take the <em>baked</em> glow texture ({@link GlowMaskBaker}), not the raw mask.
 *
 * <p>Both pipelines are built here rather than taken from vanilla, because no vanilla pipeline has the
 * combination a glow pass needs: {@code EMISSIVE} together with {@code NO_OVERLAY} and no cardinal
 * lighting ({@code entity_translucent_emissive} has neither {@code NO_OVERLAY} nor
 * {@code NO_CARDINAL_LIGHTING} — it samples the overlay sampler and shades per face), a depth test of
 * {@code GEQUAL} without depth writes (the base piece has already written depth at the same depth, so
 * the default {@code LESS} would reject the pass outright), and for the burn additive blending. On this
 * Mesa/OpenGL stack a {@code SAMPLER1}/overlay or {@code APPLY_TEXTURE_MATRIX} emitter draws nothing at
 * all, so the passes stay on a bare {@code SAMPLER0}.
 */
final class PackRenderTypes {
	private static final Map<Identifier, RenderType> GLOW = new ConcurrentHashMap<>();
	private static final Map<Identifier, RenderType> BURN = new ConcurrentHashMap<>();

	private static final RenderPipeline FILL = build(false);
	private static final RenderPipeline ADD = build(true);

	private PackRenderTypes() {
	}

	/**
	 * The fill pass: the baked glow texture drawn full-bright over the already-drawn armor piece.
	 *
	 * <p>{@code EMISSIVE} drops the lightmap multiply and {@code NO_CARDINAL_LIGHTING} drops the per-face
	 * light, so the texture reaches the framebuffer at its own colour whatever the world light is. Set up
	 * like vanilla's armor types ({@code VIEW_OFFSET_Z_LAYERING}), otherwise the base piece would sit in
	 * front of its own glow.
	 */
	static RenderType glow(Identifier baked) {
		return GLOW.computeIfAbsent(baked, PackRenderTypes::createGlow);
	}

	/**
	 * The burn pass {@code "radiant"} adds on top of {@link #glow}: the same texture drawn again with
	 * additive blending, which pushes the lit pixels past their own colour and reads as genuinely bright
	 * rather than merely unshaded. Stacking it twice is the 2.5 gain Armor Model API uses for
	 * {@code Mode.RADIANT}.
	 */
	static RenderType burn(Identifier baked) {
		return BURN.computeIfAbsent(baked, PackRenderTypes::createBurn);
	}

	private static RenderType createGlow(Identifier baked) {
		return RenderType.create("geoarmor_armor_glow", RenderSetup.builder(FILL)
				.withTexture("Sampler0", baked)
				.setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
				.sortOnUpload()
				.setOutline(OutlineProperty.NONE)
				.createRenderSetup());
	}

	private static RenderType createBurn(Identifier baked) {
		return RenderType.create("geoarmor_armor_radiant_burn", RenderSetup.builder(ADD)
				.withTexture("Sampler0", baked)
				.setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
				.sortOnUpload()
				.setOutline(OutlineProperty.NONE)
				.createRenderSetup());
	}

	/**
	 * The shared pipeline: {@code core/entity} with {@code EMISSIVE} (no lightmap), {@code NO_OVERLAY}
	 * (never samples the overlay) and {@code NO_CARDINAL_LIGHTING} (no per-face light), a single
	 * {@code Sampler0}, the entity vertex format, no culling and a {@code GEQUAL} read-only depth test so
	 * it draws exactly on top of the base piece. {@code additive} swaps the blend for {@code ONE,ONE}.
	 */
	private static RenderPipeline build(boolean additive) {
		return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
				.withLocation(Identifier.parse("geoarmor:pipeline/armor_glow" + (additive ? "_burn" : "")))
				.withVertexShader("core/entity")
				.withFragmentShader("core/entity")
				.withShaderDefine("EMISSIVE")
				.withShaderDefine("NO_OVERLAY")
				.withShaderDefine("NO_CARDINAL_LIGHTING")
				.withShaderDefine("ALPHA_CUTOUT", 0.1f)
				.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
				.withColorTargetState(new ColorTargetState(additive ? BlendFunction.ADDITIVE : BlendFunction.TRANSLUCENT))
				.withVertexBinding(0, DefaultVertexFormat.ENTITY)
				.withPrimitiveTopology(PrimitiveTopology.QUADS)
				.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
				.withCull(false)
				.build());
	}
}
