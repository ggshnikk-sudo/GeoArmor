package dev.geoarmor.mixin;

import dev.geoarmor.ProxyItem;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes a never-registered {@link ProxyItem} constructible, and keeps it out of the two places where
 * {@code Item}'s constructor touches the registries.
 *
 * <p>{@code Item.<init>} does two registry-dependent things, and both break for an item that is not
 * registered:
 *
 * <ol>
 *   <li>{@code BuiltInRegistries.ITEM.createIntrusiveHolder(this)} — throws once the registry is frozen
 *       ({@code MappedRegistry.freeze()} nulls the field and {@code validateWrite()} rejects the
 *       reassignment). The constructor still needs a holder, so one is bound directly.</li>
 *   <li>{@code BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.add(itemId, initializer)} — keyed by the
 *       item's id. {@code ReloadableServerResources.loadResources} (every world load and every
 *       {@code /reload}) and the client's {@code RegistryDataCollector} later walk that map and resolve
 *       <em>every</em> key with {@code registry.getOrThrow(key)}. For an unregistered item that throws
 *       {@code Missing element ResourceKey[minecraft:item / geoarmor:proxy_0]}, which aborts the load
 *       with "Failed to load level data or datapacks, can't proceed with server load". A proxy has no
 *       components to bind, so its key is simply not added.</li>
 * </ol>
 *
 * <p>Both matter only for {@link ProxyItem} ids; during bootstrap, and for every other item, the vanilla
 * call path is used untouched. A {@code ProxyItem} is never placed in the registry, in a creative tab or
 * in a world — it exists solely to satisfy GeckoLib's {@code T extends Item & GeoItem} bound and to carry
 * one item's render definition.
 */
@Mixin(Item.class)
public class ItemConstructorMixin {

	@Redirect(
			method = "<init>",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/core/DefaultedRegistry;createIntrusiveHolder(Ljava/lang/Object;)Lnet/minecraft/core/Holder$Reference;"
			)
	)
	private Holder.Reference<Item> geoarmor$holderForUnregisteredItem(DefaultedRegistry<Item> registry, Object value) {
		// The redirected call's descriptor is erased: createIntrusiveHolder(Ljava/lang/Object;).
		Item item = (Item) value;

		try {
			return registry.createIntrusiveHolder(item);
		} catch (IllegalStateException frozen) {
			@SuppressWarnings("unchecked")
			HolderOwner<Item> owner = (HolderOwner<Item>) registry;
			return Holder.Reference.createIntrusive(owner, item);
		}
	}

	@Redirect(
			method = "<init>",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/core/component/DataComponentInitializers;add(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/component/DataComponentInitializers$Initializer;)V"
			)
	)
	private void geoarmor$skipInitializerForUnregisteredItem(DataComponentInitializers initializers, ResourceKey<Item> id, DataComponentInitializers.Initializer<Item> initializer) {
		if (!ProxyItem.isProxyId(id)) {
			initializers.add(id, initializer);
		}
	}
}
