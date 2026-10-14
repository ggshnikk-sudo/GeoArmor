package dev.geoarmor.mixin;

import com.geckolib.renderer.base.GeoRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.geoarmor.ArmorRenderTarget;
import dev.geoarmor.ArmorRenderTickets;
import dev.geoarmor.GeoArmor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.EnumMap;

/**
 * Replaces vanilla's armor piece render for the slots GeoArmor owns with the captured GeckoLib pass.
 *
 * <p>Runs at the head of {@code HumanoidArmorLayer.renderArmorPiece} — the method vanilla calls once per
 * armor slot (CHEST, LEGS, FEET, HEAD). The per-slot captures live on the base render state under
 * {@link ArmorRenderTickets#PER_SLOT} (see {@code EntityRendererMixin}); when this slot has one, the geo
 * pass is submitted and the vanilla pass is cancelled, otherwise the vanilla pass runs unchanged.
 *
 * <p>The camera state for {@code performRenderPass} is the live level render state, which is populated
 * by the time entities are submitted.
 */
@Mixin(HumanoidArmorLayer.class)
public abstract class HumanoidArmorLayerMixin {

	@Inject(
			method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
			at = @At("HEAD"),
			cancellable = true
	)
	private void geoarmor$renderPackArmor(
			PoseStack poseStack,
			SubmitNodeCollector collector,
			ItemStack stack,
			EquipmentSlot slot,
			int light,
			HumanoidRenderState state,
			CallbackInfo ci
	) {
		EnumMap<EquipmentSlot, ArmorRenderTarget> targets =
				((GeoRenderState) (Object) state).getGeckolibData(ArmorRenderTickets.PER_SLOT);

		if (targets == null) {
			return;
		}

		ArmorRenderTarget target = targets.get(slot);

		if (target == null) {
			return;
		}

		if (GeoArmor.DEBUG) {
			GeoArmor.LOGGER.info("geo armor pass: slot={} stack={}", slot, stack);
		}

		target.renderer().performRenderPass(
				target.state(),
				poseStack,
				collector,
				Minecraft.getInstance().levelRenderer.levelRenderState.cameraRenderState
		);
		ci.cancel();
	}
}
