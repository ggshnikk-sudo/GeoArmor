package dev.geoarmor;

/**
 * A live pack item: the shared {@link ProxyItem} (animatable + definition holder) and the single
 * {@link PackGeoArmorRenderer} that draws it. Both are created once per item and reused across reloads
 * so GeckoLib's per-item caches and per-stack animation managers survive a resource reload.
 */
public record RegisteredArmor(ProxyItem proxy, PackGeoArmorRenderer renderer) {
}
