package dev.geoarmor.mixin;

import dev.geoarmor.ArmorEngine;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Drives {@link ArmorEngine} from the game loop without touching any Fabric API:
 *
 * <ul>
 *   <li>{@code tick} HEAD — the engine applies the pack state on the first tick (before anything
 *       renders) and on the tick after each completed reload. The read is a single volatile test, so
 *       the hook is free once applied.</li>
 *   <li>{@code reloadResourcePacks} RETURN — the returned future completes once the new resources are
 *       in place and live; the engine is marked dirty then, not at the moment the reload was
 *       requested.</li>
 * </ul>
 *
 * <p>Dev harness, inert without the system properties: {@code -Dgeoarmor.camera=<CameraType ordinal>}
 * forces the camera ({@code 1} = third person back, {@code 2} = third person front) and
 * {@code -Dgeoarmor.shot=<ticks>} grabs a screenshot once the camera has settled, so a run can be
 * measured without touching the keyboard.
 */
@Mixin(Minecraft.class)
public class ArmorMinecraftMixin {

	private static final CameraType[] GEOARMOR_CAMERAS = CameraType.values();
	private static final int GEOARMOR_CAMERA = Integer.getInteger("geoarmor.camera", -1);

	private static int geoarmor$shotTicks = -1;

	@Inject(method = "tick", at = @At("HEAD"))
	private void geoarmor$ensureFresh(CallbackInfo ci) {
		ArmorEngine.ensureFresh();

		Minecraft mc = Minecraft.getInstance();
		CameraType forced = GEOARMOR_CAMERA >= 0 && GEOARMOR_CAMERA < GEOARMOR_CAMERAS.length
				? GEOARMOR_CAMERAS[GEOARMOR_CAMERA]
				: null;

		if (forced != null && mc.options.getCameraType() != forced) {
			mc.options.setCameraType(forced);
		}

		String shot = System.getProperty("geoarmor.shot");

		if (shot != null && mc.level != null && mc.player != null && (forced == null || mc.options.getCameraType() == forced)) {
			if (geoarmor$shotTicks < 0) {
				geoarmor$shotTicks = Integer.parseInt(shot);
			}

			if (geoarmor$shotTicks-- == 0) {
				geoarmor$shotTicks = -1;
				System.clearProperty("geoarmor.shot");
				net.minecraft.client.Screenshot.grab(mc, false);
			}
		}
	}

	@Inject(method = "reloadResourcePacks", at = @At("RETURN"))
	private void geoarmor$markDirtyOnReload(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
		cir.getReturnValue().thenRun(ArmorEngine::markDirty);
	}
}
