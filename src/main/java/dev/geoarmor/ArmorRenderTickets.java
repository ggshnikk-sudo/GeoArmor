package dev.geoarmor;

import com.geckolib.constant.dataticket.DataTicket;
import com.google.common.reflect.TypeToken;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.EnumMap;

/**
 * GeoArmor's own GeckoLib data ticket, attached to the base {@code HumanoidRenderState} during
 * {@code createRenderState} and read back at the armor layer.
 *
 * <p>A dedicated ticket (rather than GeckoLib's internal {@code PER_SLOT_RENDER_DATA}) keeps our
 * captures fully isolated from GeckoLib's own item-based flow, which never sees plain armor items.
 */
public final class ArmorRenderTickets {
	/** Per-slot captured armor passes, keyed by the slot the piece is equipped in. */
	public static final DataTicket<EnumMap<EquipmentSlot, ArmorRenderTarget>> PER_SLOT =
			DataTicket.create("geoarmor:per_slot", new TypeToken<EnumMap<EquipmentSlot, ArmorRenderTarget>>() {});

	private ArmorRenderTickets() {
	}
}
