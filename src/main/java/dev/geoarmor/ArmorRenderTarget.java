package dev.geoarmor;

import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

/**
 * One captured armor pass: the renderer to run plus the fully-extracted render state it was filled
 * with. Captured during {@code createRenderState} (where the entity is still in hand) and consumed at
 * the armor layer, so the geo pass sees real player position/animation data.
 */
public record ArmorRenderTarget(PackGeoArmorRenderer renderer, HumanoidRenderState state) {
}
