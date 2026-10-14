package dev.geoarmor;

import com.geckolib.animation.state.BoneSnapshot;
import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.renderer.GeoArmorRenderer;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.RenderPassInfo;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The GeckoLib armor renderer for one pack item: {@link GeoArmorRenderer} plus the pack's optional
 * {@link ArmorSettings.Display} placement, its {@code translucent} render type and its {@code glow}
 * pass ({@link PackGlowLayer}).
 *
 * <p>GeckoLib draws armor as one bone per segment ({@code armorHead}, {@code armorBody}, …) and drives
 * that bone from the vanilla humanoid model part ({@link GeoArmorRenderer#adjustModelBonesForRender}),
 * which is what makes an armor model follow the head as it turns. The display transform is folded into
 * that same bone snapshot — translation before the part rotation, scale around the bone pivot — so it is
 * applied *inside* the segment and a helmet's offset keeps following the head instead of the body.
 */
public class PackGeoArmorRenderer extends GeoArmorRenderer<ProxyItem, HumanoidRenderState> {
	/** Debug only: the render mode last logged, so a change is logged once instead of every frame. */
	private @Nullable String loggedMode;

	/** The baked model the missing-bone warnings were last computed for; a new one resets {@link #warnedBones}. */
	private @Nullable BakedGeoModel warnedModel;

	/** {@code <slot>/<bone>} pairs already reported missing, so it is logged once and not every frame. */
	private final Set<String> warnedBones = new HashSet<>();

	/** Debug only: the model whose segment bone pivots were last printed, so the line appears once per model. */
	private @Nullable BakedGeoModel loggedPivots;

	public PackGeoArmorRenderer(PackGeoModel model) {
		super(model);

		// Always attached: the layer decides per render whether the entry asked for a glow, so nothing
		// has to be re-registered when a reload adds or drops `glow`.
		withRenderLayer(new PackGlowLayer(this, model));
	}

	@Override
	public RenderType getRenderType(HumanoidRenderState renderState, Identifier texture) {
		ArmorDefinition definition = definition();

		if (definition == null) {
			return super.getRenderType(renderState, texture);
		}

		if (GeoArmor.DEBUG) {
			String mode = (definition.translucent() ? "translucent" : "cutout")
					+ (definition.glow() != null ? " + glow " + definition.glow().kind() : "")
					+ " @ " + Minecraft.getInstance().options.getCameraType();

			if (!mode.equals(this.loggedMode)) {
				this.loggedMode = mode;
				GeoArmor.LOGGER.info("render mode for {}: {}", definition.item(), mode);
			}
		}

		return definition.translucent() ? RenderTypes.armorTranslucent(texture) : super.getRenderType(renderState, texture);
	}

	/**
	 * Submits one glow-shaped pass: the same per-segment bones GeckoLib's {@code submitRenderTasks} draws,
	 * but with an explicit light, colour and draw order. The order is what makes the pass ordering real —
	 * the dispatcher draws the submits of one order in an unspecified order, so a fill and its burns must
	 * not share one — and the colour is what tints a partial burn.
	 */
	public void submitGlow(RenderPassInfo<HumanoidRenderState> renderPassInfo, SubmitNodeCollector renderTasks,
			RenderType renderType, int packedLight, int packedOverlay, int renderColor, int order) {
		HumanoidRenderState renderState = renderPassInfo.renderState();
		EquipmentSlot slot = renderState.getGeckolibData(CURRENT_SLOT);

		if (slot == null) {
			return;
		}

		BakedGeoModel model = renderPassInfo.model();

		if (model.isMissingno()) {
			return;
		}

		List<ArmorSegment> segments = getSegmentsForSlot(renderState, slot);

		renderTasks.order(order).submitCustomGeometry(renderPassInfo.poseStack(), renderType, (pose, vertexConsumer) -> {
			PoseStack poseStack = renderPassInfo.poseStack();

			poseStack.pushPose();
			poseStack.last().set(pose);

			renderPassInfo.renderPosed(() -> {
				for (ArmorSegment segment : segments) {
					model.getBone(getBoneNameForSegment(renderState, segment)).ifPresent(bone -> bone.positionAndRender(
							renderPassInfo, vertexConsumer, packedLight, packedOverlay, renderColor));
				}
			});

			poseStack.popPose();
		});
	}

	@Override
	public void adjustModelBonesForRender(RenderPassInfo<HumanoidRenderState> renderPassInfo, BoneSnapshots snapshots) {
		super.adjustModelBonesForRender(renderPassInfo, snapshots);

		EquipmentSlot slot = renderPassInfo.renderState().getGeckolibData(CURRENT_SLOT);

		if (slot == null) {
			return;
		}

		List<ArmorSegment> segments = getSegmentsForSlot(renderPassInfo.renderState(), slot);

		warnMissingBones(renderPassInfo.model(), renderPassInfo.renderState(), slot, segments);

		if (GeoArmor.DEBUG) {
			logBonePivots(renderPassInfo.model(), renderPassInfo.renderState(), segments);
		}

		ArmorDefinition definition = definition();

		if (definition == null) {
			return;
		}

		ArmorSettings.Display display = definition.display();

		if (display == null || display.isDefault()) {
			return;
		}

		for (ArmorSegment segment : segments) {
			snapshots.get(getBoneNameForSegment(renderPassInfo.renderState(), segment)).ifPresent(snapshot -> apply(snapshot, display));
		}
	}

	/**
	 * Reports a bone the model does not have. GeckoLib draws armor one bone per segment
	 * ({@code armorHead}, {@code armorBody}, {@code armorLeftArm}, …) and the segment is driven by that
	 * bone's name, so a model without it is silently drawn without that segment — nothing else in the
	 * pipeline notices. A missing model is skipped, GeckoLib already reports that one.
	 *
	 * <p>Runs every frame, so each pair is logged once per model; a reload that swaps the model resets
	 * the set through {@link #warnedModel}.
	 */
	private void warnMissingBones(BakedGeoModel model, HumanoidRenderState renderState, EquipmentSlot slot, List<ArmorSegment> segments) {
		ArmorDefinition definition = definition();

		if (definition == null || model.isMissingno()) {
			return;
		}

		if (model != this.warnedModel) {
			this.warnedModel = model;
			this.warnedBones.clear();
		}

		for (ArmorSegment segment : segments) {
			String bone = getBoneNameForSegment(renderState, segment);

			if (model.getBone(bone).isEmpty() && this.warnedBones.add(slot.getName() + "/" + bone)) {
				GeoArmor.LOGGER.warn("{}: geo '{}' has no '{}' bone, the segment will not be drawn",
						definition.item(), definition.geo(), bone);
			}
		}
	}

	/**
	 * Debug only: prints the pivot of every segment bone the model has, once per model. A segment rotates
	 * around its own bone's pivot, so a pivot sitting on the geometry instead of the joint makes the piece
	 * rotate in place instead of following the limb. The expected pivots are {@code [-5, 22, 0]} /
	 * {@code [5, 22, 0]} for the arms, {@code [-2, 12, 0]} / {@code [2, 12, 0]} for the legs and boots and
	 * {@code [0, 24, 0]} for the head and the body (README, "Пивот кости") — these numbers are what tells
	 * that apart from a bone that is simply absent, which {@link #warnMissingBones} already covers.
	 */
	private void logBonePivots(BakedGeoModel model, HumanoidRenderState renderState, List<ArmorSegment> segments) {
		ArmorDefinition definition = definition();

		if (definition == null || model.isMissingno() || model == this.loggedPivots) {
			return;
		}

		this.loggedPivots = model;

		StringBuilder pivots = new StringBuilder();

		for (ArmorSegment segment : segments) {
			String name = getBoneNameForSegment(renderState, segment);

			model.getBone(name).ifPresent(bone -> {
				if (!pivots.isEmpty()) {
					pivots.append(' ');
				}

				pivots.append(formatPivot(name, bone.pivotX(), bone.pivotY(), bone.pivotZ()));
			});
		}

		if (!pivots.isEmpty()) {
			GeoArmor.LOGGER.info("{}: geo '{}' segment bone pivots: {}", definition.item(), definition.geo(), pivots);
		}
	}

	/** The live pack definition behind this renderer, or {@code null} before the first apply. */
	@Nullable
	private ArmorDefinition definition() {
		return ((PackGeoModel) getGeoModel()).definition();
	}

	/**
	 * One {@code <bone>=[x, y, z]} entry of the debug pivot line, the exact shape the README quotes.
	 * {@code + 0.0f} collapses the {@code -0.0} the x mirroring leaves on a zero pivot, so the line reads
	 * {@code [0.0, 24.0, 0.0]} rather than {@code [-0.0, 24.0, 0.0]}.
	 */
	static String formatPivot(String bone, float x, float y, float z) {
		return bone + "=[" + (x + 0.0f) + ", " + (y + 0.0f) + ", " + (z + 0.0f) + "]";
	}

	private static void apply(BoneSnapshot snapshot, ArmorSettings.Display display) {
		snapshot.setTranslateX(snapshot.getTranslateX() + display.translateX());
		snapshot.setTranslateY(snapshot.getTranslateY() + display.translateY());
		snapshot.setTranslateZ(snapshot.getTranslateZ() + display.translateZ());

		snapshot.setRotX(snapshot.getRotX() + radians(display.rotateX()));
		snapshot.setRotY(snapshot.getRotY() + radians(display.rotateY()));
		snapshot.setRotZ(snapshot.getRotZ() + radians(display.rotateZ()));

		snapshot.setScaleX(snapshot.getScaleX() * display.scaleX());
		snapshot.setScaleY(snapshot.getScaleY() * display.scaleY());
		snapshot.setScaleZ(snapshot.getScaleZ() * display.scaleZ());
	}

	private static float radians(float degrees) {
		return degrees * ((float) Math.PI / 180.0F);
	}
}
