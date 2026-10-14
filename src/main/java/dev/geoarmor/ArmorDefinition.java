package dev.geoarmor;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

/**
 * A resolved, validated binding of one pack entry to one armor item: the item, its armor slot, the
 * geometry/texture/optional-animation ids that passed resource validation, the idle clip name, the
 * {@link ArmorSettings.Display} placement of the model on the piece, and the two render modes the pack
 * may ask for — alpha-blended {@link #translucent} and the {@link #glow} pass ({@link ArmorSettings.Glow},
 * whose mask is already resolved and validated to exist). Built by {@link ArmorEngine} at every reload
 * and pushed onto the item's {@link ProxyItem}, which the {@link PackGeoModel} and
 * {@link PackGeoArmorRenderer} read.
 */
public record ArmorDefinition(
		Item item,
		EquipmentSlot slot,
		Identifier geo,
		Identifier texture,
		@Nullable Identifier animation,
		String idle,
		ArmorSettings.Display display,
		boolean translucent,
		@Nullable ArmorSettings.Glow glow
) {
}
