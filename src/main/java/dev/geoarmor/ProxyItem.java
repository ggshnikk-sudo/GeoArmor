package dev.geoarmor;

import com.geckolib.animatable.GeoItem;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.instance.SingletonAnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.animation.object.PlayState;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

/**
 * The GeckoLib animatable behind one resource-pack armor item.
 *
 * <p>GeckoLib's {@link com.geckolib.renderer.GeoArmorRenderer} is bound to {@code T extends Item &
 * GeoItem}, so a plain vanilla armor item cannot be rendered by it. This proxy supplies that bound: it
 * is a real (never registered) item whose {@link AnimatableInstanceCache} is keyed per equipped stack
 * (GeckoLib hashes the stack), giving each stack its own animation controllers, while the live
 * {@link ArmorDefinition} it carries is what actually decides the geo/texture/animation drawn.
 *
 * <p>One proxy exists per pack item and lives as long as the item has a valid definition; a reload
 * that drops the definition simply removes the proxy from {@link ArmorEngine}'s registry and the item
 * renders vanilla again.
 */
public final class ProxyItem extends Item implements GeoItem {
	private final AnimatableInstanceCache cache = new SingletonAnimatableInstanceCache(this);

	/** Swapped wholesale at reload; read on the render thread only. */
	private volatile ArmorDefinition definition;

	public ProxyItem(ResourceKey<Item> id) {
		super(new Item.Properties().setId(id));
	}

	public void setDefinition(ArmorDefinition definition) {
		this.definition = definition;
	}

	@Nullable
	public ArmorDefinition definition() {
		return this.definition;
	}

	/**
	 * True for the ids this mod constructs for its proxies ({@code geoarmor:proxy_<n>}). They are real
	 * {@link Item}s that are deliberately never registered, so anything that later resolves an item id
	 * against the registry must not be handed one — see {@code ItemConstructorMixin}.
	 */
	public static boolean isProxyId(ResourceKey<?> id) {
		return id.isFor(Registries.ITEM)
				&& GeoArmor.MOD_ID.equals(id.identifier().getNamespace())
				&& id.identifier().getPath().startsWith("proxy_");
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		// One idle controller. Registered eagerly (the animatable manager is built on the first render),
		// and reads the definition live so a reload that changes the clip or drops the animation is
		// picked up without rebuilding the manager.
		controllers.add(new AnimationController<>("idle", 0, test -> {
			ArmorDefinition def = this.definition;

			if (def == null || def.animation() == null) {
				return PlayState.STOP;
			}

			return test.setAndContinue(RawAnimation.begin().thenLoop(def.idle()));
		}));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return this.cache;
	}
}
