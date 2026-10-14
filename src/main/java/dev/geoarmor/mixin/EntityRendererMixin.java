package dev.geoarmor.mixin;

import com.geckolib.renderer.GeoArmorRenderer;
import com.geckolib.renderer.base.GeoRenderState;
import dev.geoarmor.ArmorEngine;
import dev.geoarmor.ArmorRenderTarget;
import dev.geoarmor.ArmorRenderTickets;
import dev.geoarmor.GeoArmor;
import dev.geoarmor.RegisteredArmor;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumMap;

/**
 * Captures one fully-extracted render state per pack armor slot at {@code createRenderState}, where the
 * entity is still in hand, and stashes them on the base render state for the armor layer.
 *
 * <p>The vanilla armor layer runs at render time with only a render state; the geo pass, however, wants
 * real player position/animation data. Capturing here gives it exactly that: each state below is a
 * second full extraction of the same entity (the same {@code createRenderState()} /
 * {@code extractRenderState} pipeline vanilla uses), so the geo bones pose against a correct biped
 * skeleton. The {@code HEAD} slot reuses the base state (it is rendered last, and nothing downstream
 * needs a pristine copy of it).
 *
 * <p>A separate state per non-head slot is required: GeckoLib writes per-slot data into the shared base
 * state, and CHEST/LEGS/FEET run before HEAD, so reusing one object for all slots would clobber it.
 *
 * <p>Slots without a live pack definition are left untouched and render vanilla.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	/** The armor slots in vanilla's render order; HEAD last, matching {@code HumanoidArmorLayer.submit}. */
	private static final EquipmentSlot[] GEOARMOR$SLOTS = {
			EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HEAD
	};

	@Inject(
			method = "createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
			at = @At("RETURN")
	)
	@SuppressWarnings({"rawtypes", "unchecked"})
	private void geoarmor$captureArmorRenderStates(Entity entity, float partialTick, CallbackInfoReturnable<EntityRenderState> cir) {
		EntityRenderState base = cir.getReturnValue();

		if (!(base instanceof HumanoidRenderState humanoid) || !(entity instanceof LivingEntity living)) {
			return;
		}

		Object self = this;

		if (!(self instanceof LivingEntityRenderer<?, ?, ?> livingRenderer)) {
			return;
		}

		HumanoidArmorLayer<?, ?, ?> armorLayer = null;

		for (Object layer : livingRenderer.layers) {
			if (layer instanceof HumanoidArmorLayer<?, ?, ?>) {
				armorLayer = (HumanoidArmorLayer<?, ?, ?>) layer;
				break;
			}
		}

		if (armorLayer == null) {
			return;
		}

		EnumMap<EquipmentSlot, ArmorRenderTarget> targets = new EnumMap<>(EquipmentSlot.class);

		for (EquipmentSlot slot : GEOARMOR$SLOTS) {
			ItemStack stack = living.getItemBySlot(slot);

			if (!HumanoidArmorLayer.shouldRender(stack, slot)) {
				continue;
			}

			RegisteredArmor registered = ArmorEngine.get(stack.getItem());

			if (registered == null) {
				continue;
			}

			HumanoidModel<?> baseModel = ((HumanoidArmorLayer) armorLayer).getArmorModel(humanoid, slot);
			HumanoidRenderState slotState = slot == EquipmentSlot.HEAD
					? humanoid
					: (HumanoidRenderState) this.geoarmor$freshState(entity, partialTick);

			registered.renderer().fillRenderState(
					registered.proxy(),
					new GeoArmorRenderer.RenderData(stack, slot, living, baseModel),
					slotState,
					partialTick
			);
			targets.put(slot, new ArmorRenderTarget(registered.renderer(), slotState));
		}

		if (!targets.isEmpty()) {
			((GeoRenderState) (Object) humanoid).addGeckolibData(ArmorRenderTickets.PER_SLOT, targets);
		}

		if (GeoArmor.DEBUG) {
			GeoArmor.LOGGER.info("captured armor slots for {}: {}", entity.getName().getString(), targets.keySet());
		}
	}

	/** A pristine, fully-extracted render state: the same body as vanilla's final {@code createRenderState}. */
	@Unique
	private EntityRenderState geoarmor$freshState(Entity entity, float partialTick) {
		EntityRenderer self = (EntityRenderer) (Object) this;
		EntityRenderState state = self.createRenderState();
		self.extractRenderState(entity, state, partialTick);
		return state;
	}
}
