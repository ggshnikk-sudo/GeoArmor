# GeoArmor — working notes

Fabric client mod for **Minecraft 26.2** that renders **GeckoLib 5** 3D armor models for any armor item
(vanilla or modded) chosen by a resource pack. GeckoLib replacement for the ArmorModelAPI-based
`~/Projects/pseudoarmor` (read-only reference; do not modify).

## Toolchain (all pinned in `gradle.properties` / `build.gradle`)

- 26.1+ ships **unobfuscated**, so there are no Yarn mappings: no `mappings` line and
  `fabric.loom.disableObfuscation=true`. Names are Mojang names; `net.minecraft.resources.Identifier`
  (not `ResourceLocation`).
- Loom `1.18.2`, loader `0.19.3`, Gradle wrapper `9.8.0`, Java toolchain 25, fabric-api `0.161.0+26.2`
  as `localRuntime` (dev client only).
- GeckoLib Fabric for 26.2 = **5.5.5**, `maven.modrinth:geckolib:7gaQHok7`, hard dependency
  `geckolib>=5.5.5`. Packages are `com.geckolib.*`.
- Loom needs the **Gradle JVM ≥ 25** (run `gradle` on the system JDK; `JAVA_HOME`/toolchain only feeds
  `options.release`).
- `./gradlew build` and `./gradlew test` are the gates. Tests live in `src/test/java/dev/geoarmor`
  and cover the parser/merge purely over Gson — no game singletons.

## GeckoLib 5.5.5 asset layout (source of the biggest bug so far)

GeckoLib 5 does **not** load from `assets/<ns>/geo/` and `assets/<ns>/animations/`. It scans, across all
namespaces:

| kind | file | cache key |
| --- | --- | --- |
| geometry | `assets/<ns>/geckolib/models/<name>.geo.json` | `<ns>:<name>` |
| animation | `assets/<ns>/geckolib/animations/<name>.animation.json` | `<ns>:<name>` |

- `GeckoLibResources.MODELS_PATH`/`ANIMATIONS_PATH` = `geckolib:geckolib/models` / `geckolib:geckolib/animations`;
  the key is the listed path with that root and the extension (`Regex "((\.geo)|((\.animation)s?))?(\.json)$"`)
  removed.
- `getModelResource`/`getAnimationResource` must return that bare key. Returning a file-path spelling makes
  GeckoLib log `Superfluous prefix or suffix found in model resource path: '<x>'. Should be '<y>'` **every
  frame** and fail to find the asset.
- `stripLegacyPath` in `BakedModelCache`/`BakedAnimationCache` is a deprecated fallback and only strips
  `geckolib/`, `models/`, `animations/` and the extensions — never `geo/`.
- **Textures are different**: the id returned by `getTextureResource` goes straight into
  `RenderTypes.armorCutoutNoCull(id)` → `RenderSetup.withTexture("Sampler0", id)`, i.e. it is a full
  resource path. It must be `textures/.../name.png` (vanilla RenderType convention), *not* a bare name.
- Geometry/animation format versions seen working: `1.12.0` and Bedrock `1.21.110`.

### Armor geometry is *only* the segment bones (this is why the helmet looked empty)

`GeoArmorRenderer.submitRenderTasks` does **not** draw the whole model — it draws one bone per segment,
named by `getBoneNameForSegment` (`GeoArmorRenderer.java:135`): `armorHead` (HEAD), `armorBody`,
`armorLeftArm`, `armorRightArm` (CHEST), `armorLeftLeg`, `armorRightLeg` (LEGS), `armorLeftBoot`,
`armorRightBoot` (FEET). A model whose top-level bones are `наушники` / `нота` (no `armorHead`) renders
**nothing** for HEAD, and since our inject cancels vanilla, the head ends up bare. Nothing in the pipeline
reports it, so `PackGeoArmorRenderer.warnMissingBones` (called from `adjustModelBonesForRender`, which runs
per render pass via `RenderPassInfo.create`'s bone updater while the piece is worn) logs
`<item>: geo '<geo>' has no '<bone>' bone, the segment will not be drawn` once per model and segment.
Verified live: a helmet geo on `minecraft:golden_leggings` produced exactly the two `armorLeftLeg` /
`armorRightLeg` lines and no per-frame spam.
`Bone.positionAndRender` *does* recurse into children (`GeoBone.java:118`), so nesting the real geometry
under a single `armorHead` bone works and keeps per-bone animation clips reachable.

Authoring space (derived from `LivingEntityRenderer.submit` in 26.2: `scale(-1,-1,1)` **then**
`translate(0,-1.501,0)`, plus `GeoArmorRenderer.adjustRenderPose` = `translate(0,1.5,0); scale(-1,-1,1)`):
geometry is authored in **Bedrock humanoid space — y-up, feet at 0, 16 units = 1 block, head 24..32**,
x mirrored relative to vanilla. That is why the original `hat.geo.json` (cubes at y 0..8, i.e. at the feet)
was invisible even once it had the right bone: the wrapper had to shift the cubes +24 in Y. Bone pivots are
absolute in Bedrock space — parenting does not offset children, it only stacks transforms (rotate-around-
pivot), so shifting means editing cube `origin`/bone `pivot` values directly.

`ArmorSettings.geckoAssetId` normalizes author-written ids (`ns:geo/x.geo.json`,
`ns:geckolib/models/x.geo.json`, `ns:x`) down to the key; `modelFile`/`animationFile` map back for
existence validation (`ArmorEngine.validate`, which checks both `.geo.json` and plain `.json` since
GeckoLib's loader accepts either).

### Segment bone pivots are the joint (the "gloves are glued to the body" bug)

A segment bone rotates **around its own `pivot`** (`RenderUtil.translateToPivotPoint` → rotate by
`baseRot + snapshotRot` → `translateAwayFromPivotPoint`; `adjustModelBonesForRender` only replaces the
*rotation*, never the pivot), so a pivot sitting on the geometry makes that piece spin in place while the
limb walks away = reads as "glued to the body". Correct contract, in Bedrock space:
`armorHead`/`armorBody` `[0, 24, 0]`, `armorRightArm`/`armorLeftArm` `[-5, 22, 0]`/`[5, 22, 0]` (shoulder),
`armorRightLeg`/`armorLeftLeg` `[-2, 12, 0]`/`[2, 12, 0]` (hip), and the boots `[-2, 12, 0]`/`[2, 12, 0]`
— boots are driven by the *leg* part (`ArmorSegment.LEFT_FOOT`->`model.leftLeg`), so a boot bone's pivot
must be the leg's hip. Reference values: `pseudoarmor-sample/*.geo.json` (`armorRightArm pivot [-5,22,0]`
with a cube spanning y 12..24) and this mod's `iron_set.geo.json`. Two shipped-sample typos of exactly
this class were fixed (`armorLeftBoot` `[7,12,0]`->`[2,12,0]`, `armorRightLeg` `[2,12,0]`->`[-2,12,0]`).
Confirmed against a real pack: a pack's `kosha.geo.json` had both arm pivots at the glove
(`[-6,12,0]`/`[7,12,0]`), which is the whole symptom — **not a mod bug**, and `warnMissingBones` cannot
see it (the bones exist), so `PackGeoArmorRenderer.logBonePivots` prints every segment bone's pivot under
`-Dgeoarmor.debug=true` once per model. `ArmorSegment` does not expose the pivot, so the expected
numbers stay documented (README "Пивот кости"), not duplicated as a check in code: a long limb cube
*contains* its own joint, so any "pivot is inside the geometry" heuristic false-positives on the
reference samples.

## Pack format

`assets/<ns>/config/armor/*.json`, direct children only:

```json
{"armor": {"minecraft:diamond_helmet": {
  "geo": "mypack:diamond_set",
  "texture": "mypack:textures/armor/diamond_set.png",
  "animation": "mypack:diamond_set",
  "idle": "misc.idle",
  "display": {"translate": [1.6, -2.75, 0], "rotate": [0, 0, 0], "scale": 1.6},
  "translucent": false,
  "glow": "radiant"
}}}
```

`geo` required; `texture` defaults to `textures/armor/<last path segment of geo>.png`; `animation`
optional (no animation ⇒ static); `idle` defaults to `misc.idle`; `display` optional (identity when
absent). Keys are **item** ids, one per piece: `minecraft:iron_chestplate` needs its own entry, mapping
`iron_helmet` does not cover it. Unknown keys are ignored. Every file in every namespace merges; the same
item id in two files is reported (last sorted file wins) — see `ArmorSettingsIndex`.

### Render modes: `translucent` and `glow`

Both came out of the "transparent textures + glow" request and are wired in
`ArmorSettings.Entry` → `ArmorDefinition` → `PackGeoArmorRenderer`/`PackGlowLayer`.

- `translucent` (bool, default `false`). GeckoLib's own type is `RenderTypes.armorCutoutNoCull` —
  `ARMOR_CUTOUT_NO_CULL` has **no blend function**, so texture alpha is binary (a pixel is either kept
  at full opacity or discarded below `ALPHA_CUTOUT 0.1`); semi-transparent art renders solid. `true`
  switches the piece to vanilla `RenderTypes.armorTranslucent(texture)` (`ARMOR_TRANSLUCENT` +
  `LayeringTransform.VIEW_OFFSET_Z_LAYERING`), which blends. Override:
  `PackGeoArmorRenderer.getRenderType(HumanoidRenderState, Identifier)`.
- `glow` — same key and meaning as Armor Model API's, so a PseudoArmor pack works unchanged. `true`
  (or `false`), `"glow"`, `"radiant"`, or an object `{"mask": id, "mode": "glow"|"radiant"}` (the key is
  `mode`, not `kind`). The mask defaults to the base texture with `_glowmask` appended before `.png`
  (`ArmorSettings.glowMask`), GeckoLib's own naming. Only the mask's opaque pixels are drawn by the glow
  pass, on top of the base texture, so a mask carved out of the base art's highlights ("shiny armor") or
  a few UV islands (visor, runes) is what a pack wants. **Mask missing ⇒ the whole piece glows** (the
  base texture is used as its own mask) and `ArmorEngine.validate` warns once. The two are baked
  together by `GlowMaskBaker` under `geoarmor:glow/<base>_<mask>`, so the glow keeps the base
  texture's colours and takes only the mask's shape.
- `translucent` and `glow` are independent: the *base* pass is `PackGeoArmorRenderer.getRenderType`
  (`cutout`/`translucent`), the glow is a separate pass on top.
- `PackGlowLayer` is attached to the renderer **always** (a reload can add/drop `glow`, and GeckoLib's
  layer list is built in the renderer's constructor); a definition without a glow submits nothing.
- `PackRenderTypes.glow(baked)` / `.burn(baked)` build the two render types **once per baked texture**
  (`ConcurrentHashMap`, mirrors GeckoLib's own cache), both with `VIEW_OFFSET_Z_LAYERING` so they follow
  armor z-layering.
- **Both pipelines are built by the mod, not taken from vanilla**, and neither is
  `ENTITY_TRANSLUCENT_EMISSIVE`: on this Mesa/OpenGL stack that vanilla type draws **nothing** at all
  (verified by pixels: `RenderPipelines.EYES` and the mod's own fill are identical, the vanilla emissive
  type is invisible). `RENDER_PIPELINES` are registered from `MATRICES_FOG_LIGHT_DIR_SNIPPET` +
  `core/entity` with `EMISSIVE`, `NO_OVERLAY`, `NO_CARDINAL_LIGHTING`, `ALPHA_CUTOUT 0.1`, a bare
  `SAMPLER0` (a `SAMPLER1`/overlay or `APPLY_TEXTURE_MATRIX` emitter also draws nothing here),
  `BlendFunction.TRANSLUCENT` (fill) or `.ADDITIVE` (burn), and a read-only `GEQUAL` depth test — the
  base piece already wrote the same depth, and the default `LESS` would reject the glow outright.
  `EMISSIVE` drops the lightmap multiply, so the fill reaches the framebuffer at the texture's own
  colour whatever the world light is (measured: texture (60,0,0) → 62 px).
- **Every glow submit carries its own draw order** (`ORDER = 100`, Armor Model API's `preferredOrder`,
  then 101, 102, … for the burns). The submit dispatcher draws the submits of one order grouped by
  render type in `HashMap` order, i.e. unspecified — a fill and its burns sharing an order means the
  fill can land last and wipe them, which reads as "radiant is just a plain glow". `PackGeoArmorRenderer.submitGlow`
  exists for exactly this: GeckoLib's own `submitRenderTasks` takes no order/light/colour, so it copies
  its ~15-line body (per-segment `getBoneNameForSegment` bones, `model.getBone(name).positionAndRender`)
  with `packedLight`, `packedOverlay`, `renderColor` and `order` as parameters.
- `glow: "radiant"` = fill + the Armor Model API gain-2.5 loop: two full additive burns at
  `0xF000F0`/`-1`, then a partial burn tinted `ARGB.color(255, round(0.5*255) x3)` = 128 grey. Verified
  in one frame: fill-only piece 62, fill+burn piece 225 (texture 60).
- **GeckoLib's own `AutoGlowingGeoLayer` is not usable here**: its
  `EmissiveRenderType.buildNewInstance` bytecode is `if (!SystemUtils.IS_OS_WINDOWS) return
  RenderTypes.eyes(texture);` — on Linux it drops the armor render type entirely (the `eyes` type has no
  depth/outline/layering setup for armor). That is why the mod builds its own glow type.


`display` mirrors Blockbench's *Display → Head* numbers onto the worn piece: `translate` in 1/16-block
units (+y up, the space the geo is authored in), `rotate` in degrees, `scale` a number or `[x,y,z]`.
`PackGeoArmorRenderer.adjustModelBonesForRender` adds them onto the segment bone's `BoneSnapshot`, so the
offset follows the head/limb instead of the body. It exists for **item** models worn as armor
(a helmet-shaped item has its own Blockbench display); a model authored against the armor bone contract
does not need it. Implemented and parser-tested; not yet verified visually in game.


## Render wiring (why it looks like this)

- `EntityRendererMixin` — `@Inject` at `RETURN` of `createRenderState(Entity,F)`: the only place the
  entity is still in hand while a render state exists. Requires `base instanceof HumanoidRenderState`,
  `entity instanceof LivingEntity`, and `Object self = this; self instanceof LivingEntityRenderer`
  (a direct `this instanceof` is a javac pattern-match error). Finds the `HumanoidArmorLayer` in
  `livingRenderer.layers`; for each slot `HumanoidArmorLayer.shouldRender(stack, slot)` +
  `ArmorEngine.get(stack.getItem())`; HEAD reuses the base state, the other slots get a fresh
  `createRenderState()` + `extractRenderState(...)` (GeckoLib writes per-slot data into the shared base
  state and CHEST/LEGS/FEET run before HEAD); stores an `EnumMap` under our own `DataTicket`
  (`ArmorRenderTickets.PER_SLOT`) — never GeckoLib's `PER_SLOT_RENDER_DATA`, which casts the stack's item
  to its own type.
- Render: `@Inject` at `HEAD`, cancellable, on `HumanoidArmorLayer.renderArmorPiece(...)`; read the
  ticket, `performRenderPass(target.state(), poseStack, collector, levelRenderer.levelRenderState.cameraRenderState)`,
  `ci.cancel()`. GeckoLib's own `@WrapWithCondition` on `submit` means GeckoLib-rendered pieces never reach
  this inject.
- `ProxyItem` is a never-registered item implementing `GeoItem` (GeckoLib's `GeoArmorRenderer<T>` needs
  `T extends Item & GeoItem`, so a vanilla armor item cannot be rendered directly). One proxy per pack item,
  holding a `volatile ArmorDefinition` that the model reads live, so an F3+T reload never disturbs
  GeckoLib's caches. Its `AnimatableInstanceCache` is `SingletonAnimatableInstanceCache` keyed by stack.
- `ArmorEngine.ensureFresh` (volatile-dirty check from the `Minecraft.tick` mixin) applies settings on the
  main thread. A definition that disappears on reload simply drops from the registry → vanilla rendering.

## MC 26.2 gotchas already paid for

- **Constructing an item after startup is blocked**: `Item.<init>` calls
  `BuiltInRegistries.ITEM.createIntrusiveHolder(this)`, which throws `IllegalStateException: This registry
  can't create intrusive holders` once the registry is frozen (`MappedRegistry.freeze()` nulls the field,
  and `validateWrite()` throws on reassignment). Solved with `mixin/ItemConstructorMixin`: `@Redirect` on
  that call; the handler signature must be the erased `(DefaultedRegistry<Item>, Object) ->
  Holder.Reference<Item>` or mixin validation fails; fall back to `Holder.Reference.createIntrusive(owner, item)`.
- **An unregistered item id poisons the data-component initializer map, and the next world load or
  `/reload` dies with it**: `Item.<init>` also runs
  `BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.add(properties.itemIdOrThrow(), initializer)`.
  `ReloadableServerResources.loadResources` (every world load, every `/reload`) and the client's
  `RegistryDataCollector` then resolve **every** entry with `registry.getOrThrow(key)`, so a proxy key
  throws `IllegalStateException: Missing element ResourceKey[minecraft:item / geoarmor:proxy_0]`. In
  singleplayer that aborts the load with "Failed to load level data or datapacks, can't proceed with
  server load"; inside `/reload` it surfaces as a logged `Failed to execute reload`. The map is
  **in-memory**, so the failure only appears on the *second* resource load in one JVM — a fresh game
  launch always looks fine, which is what made this look intermittent. Fixed by the second
  `@Redirect` in `mixin/ItemConstructorMixin`, which skips `Item`'s `DATA_COMPONENT_INITIALIZERS.add`
  call for `ProxyItem.isProxyId(id)` (a proxy has no components to bind).
- Items need an id: `Item$Properties.setId(ResourceKey<Item>)`, else `itemIdOrThrow` NPEs (`Item id not set`).
- GeckoLib's `geckolib.classtweaker` entries are **not transitive**: only its `transitive-inject-interface`
  lines reach dependents. Hence this mod ships its own `geoarmor.accesswidener`
  (`accessWidener v2 official`), wired via `loom { accessWidenerPath = file(...) }`. Needed: `LivingEntityRenderer.layers`,
  `HumanoidArmorLayer.getArmorModel(HumanoidRenderState, EquipmentSlot)`, `LevelRenderer.levelRenderState`.
  `HumanoidArmorLayer.shouldRender(ItemStack, EquipmentSlot)` is public (no AW). `HumanoidRenderState` does
  not implement `GeoRenderState` at compile time → cast via `(GeoRenderState) (Object) state`.
- It is `net.minecraft.resources.Identifier`, and `EntityRenderer.createRenderState()` is final.

## Dev-run verification recipe (works headless of the GUI, on this box)

1. Packs are canonical in `examples/`: `examples/geoarmor-sample` (the full iron+diamond example) and
   `examples/geoarmor-template` (the minimal one-cube-helmet starting point, maps `golden_helmet`); both are
   copied into `run/resourcepacks/` by the `syncExamplePacks` task before every `runClient` (MC's pack
   scanner ignores symlinked pack dirs; `run/` is gitignored, `examples/` is committed). `run/options.txt` lists
   `resourcePacks:["vanilla","file/geoarmor-sample","file/geoarmor-template"]`.
   The `glowtest` pixel-probe pack stays on disk (unpublished) but is disabled — swap it in for recipe 5.
2. World `run/saves/TEST` (copied from pseudoarmor). The `pseudoarmor-check` datapack is **wired**: its
   `data/minecraft/tags/function/tick.json` runs `pseudoarmor-check:tick` → `function/equip.mcfunction`, so
   the world re-equips the sample iron set every tick (note the 1.21+ singular `function/` folder; the old
   `functions/` spelling silently does nothing). Point `equip.mcfunction` at whatever set a run needs.
   (`minecraft:iron_helmet` … `iron_boots`). The pack must map **every** piece that is worn: a slot whose
   item has no entry renders vanilla, which is easy to misread as "the mod only does helmets" (that is the
   `ArmorEngine: K item(s) active` vs `captured armor slots …` discrepancy — the item mapping is per
   *item*, not per slot family).
3. `./gradlew runClient --args="--quickPlaySingleplayer TEST" --console=plain` (run in background).
   Expect, in order: `GeoArmor engine active (debug=true)` → `(GeckoLib) Loaded N models and M animations
   from resources` → `Loaded K armor setting(s) …` → per-item mapping → `ArmorEngine: K item(s) active`.
   With `debug=true` also: `captured armor slots for <player>: [FEET, LEGS, CHEST, HEAD]` (the mixin side)
   and `geo armor pass: slot=… stack=…` per rendered piece (the layer side). Both are the fastest way to
   tell a *capture* problem from a *config* problem: empty capture list = no packed piece worn/mapped.
4. The player is **not** rendered in first person, so the capture/render path only runs in third person. The
   mod's own dev hook does both, no keyboard and no window focus needed:
   `-Dgeoarmor.camera=<CameraType ordinal>` forces the camera (1 = third person back, 2 = third person
   front) and `-Dgeoarmor.shot=<ticks>` grabs a screenshot through `Screenshot.grab` once the camera has
   settled — front view is how a face/visor on the model gets measured. Both live in `ArmorMinecraftMixin`
   and are inert without the properties. `/tmp/opencode/cap.sh <name> 60 ["-Dgeoarmor.camera=2"]` wraps
   it; use 60 ticks (a larger count has come back `NO SHOT`, the dev client quits itself first), and the
   grab is a framebuffer read, so the window does not need focus.
5. Reading pixels when the model is small in frame: pick a colour the world cannot produce and count it.
   **Never compare two runs** for a colour/luminance question: the save's time of day drifts between
   runs, so the same config comes back day-lit and pitch black (a session's screenshots range from
   `sky=(211,191,191)` to `sky=(1,1,1)`). Put both variants **in one frame** instead:
   - the `glowtest` dev pack maps `iron_helmet` → `dim_red.png` + `"glow": "glow"`,
     `iron_chestplate` → `dim_green.png` + `"glow": "radiant"`, `iron_leggings` → `dim_blue.png` with no
     glow, all on `gt.geo.json` (a clean visible head/body/legs box model — the shipped
     `iron_set.geo.json` is a *sketch*: no arm cubes, a 1-px chest plate, 1-px boot soles, so a chest or
     leg glow is invisible there and reads as "only the helmet glows").
   - dim solid colours (60) keep the additive passes far from the 255 clamp. One run then gives:
     fill = 62 ≈ the texture colour (proves the fill is unlit), fill + gain-2.5 = 225 (proves the burns
     land *after* the fill), and the no-glow piece reads the scene light in the same frame.
   For `translucent` the discriminating metric is **pure red** (`r>20, g==0, b==0`) with a
   `(255,0,0,128)` texture: cutout writes the literal texture colour (g=b=0 exactly, alpha ignored), a
   blended pass mixes the body underneath in, so g/b become non-zero. The metric scores 0 on every
   screenshot taken so far (no false positives) — but it only means anything with the *same* camera and a
   semi-transparent texture, see the open items.
   The harness (`/tmp/opencode/cap.sh`, not in the repo) shoots at `-Dgeoarmor.shot=60`; a larger tick
   count has come back `NO SHOT` because the dev client quits itself first.
6. **Always check the capture against the log's `Stopping!` time.** A grim of a dead window's screen region
   silently returns the desktop/another app — one reading ("48 red pixels") looked like a pass and was
   garbage. So: shoot immediately after `joined the game` (the game quits itself seconds to ~20 s later,
   cleanly, no exception), and sanity-check the frame (a real frame has >10k distinct colours; verify the
   window geometry from `hyprctl -j clients` rather than assuming 640,0). Focus by address and re-read
   `-j activewindow` before sending keys — the active window can be a different app at the same geometry.
7. F3+T works in the dev client and re-reads pack configs (`ArmorEngine.ensureFresh`), existing files and
   new ones: hold F3 while pressing T — `wtype -P F3 -s 600 -k t -p F3` (needs wtype's `-P`/`-p`; a plain
   `-k F3` then `-k t` opens **chat** instead). A reload does not reliably re-log
   `Loaded K armor setting(s)`; use the per-definition lines or the pixels as evidence.
8. `PackGeoArmorRenderer.getRenderType` logs `render mode for <item>: cutout|translucent (+ glow <mask>)`
   once per change — the cheapest proof that a pack key reached the render type.
9. Benign noise in the log: auth 401 / Realms (offline dev account), `Pack declares support for version
   newer than 81, but is missing mandatory fields min_format and max_format`, X11 cursor-shape warning,
   `Loaded 1688 advancements`, UBO resize spam, and a clean `Stopping!` a few seconds after login
   (the dev client quits itself on this box; restart it, do not chase it).
10. Hard-won: **`pkill -f runClient` (and any `pkill -f devlaunchinjector`) kills the calling shell** — the
   pattern matches the wrapper's own command line. `pkill -f 'devlaunchinjecto[r]'` does not save you
   either: this box's zsh strips the quotes and glob-expands the brackets (`no matches found`). Kill by
   PID looked up from `ps -eo pid,args`, or set the pattern through a variable.
11. **Repro harness for the `DATA_COMPONENT_INITIALIZERS` crash** (the one above). `ItemConstructorMixin`
    poisoning the map needs a *second* resource load, so arm the world datapack with a delayed `/reload`:
    `function/tick.mcfunction` = original equip line, then `scoreboard objectives add geoarmor_check
    dummy`, `execute if entity @a run scoreboard players add #pt geoarmor_check 1`, and
    `execute if score #pt geoarmor_check matches 40.. unless score #reloaded geoarmor_check matches 1..
    run function pseudoarmor-check:reload_once`; `reload_once.mcfunction` = `scoreboard players set
    #reloaded geoarmor_check 1` + `reload`. 40 player ticks ≈ 2 s after join, i.e. after the proxies were
    built. On the broken build the log shows `Failed to execute reload` +
    `Missing element ResourceKey[minecraft:item / geoarmor:proxy_N]` at
    `ReloadableServerResources.lambda$loadResources$1`.
    **How to read success**: a function's `/say` is *not* logged, so verify offline instead. Add a
    `data/minecraft/tags/function/load.json` → `pseudoarmor-check:on_load`
    (`scoreboard players add #reloads reload_check 1`) — the load tag fires on world load *and* on
    `/reload`, so `#reloads == 2` is positive proof the reload completed. Let the client exit **on its
    own** (it does, cleanly, ~10-20 s after login; do not `kill` it or the world is not saved) and read
    `run/saves/TEST/data/minecraft/scoreboard.dat` (`nbtlib`; note 26.2 puts it under `data/minecraft/`,
    not `data/`): `#reloaded == 1` + `#reloads == 2` + no exception = fixed. **`cap.sh` must delete that
    file before each run** — the guard score persists in the world, so without the delete a later run
    silently skips the reload and proves nothing (that false negative cost one run).

## Docs

- `README.md` (Russian) is the pack-author/user doc: quick start, `config/armor` format + key table,
  GeckoLib asset paths + the `geoarmor-template` starter pack, bone contract (bone names, the
  missing-bone warning, and the "Пивот кости" table + the glued-limb symptom row), geo units, `display`,
  `translucent`, `glow`, F3+T reload, log lines (incl. the debug segment-pivot line), a symptom→cause
  table, build from source. `LICENSE` (MIT, held by "GeoArmor")
  is now present so the `jar { from("LICENSE") }` block emits `LICENSE_geoarmor`.
- Keep the README's log snippets in sync with the real format strings (`ArmorEngine`, `ArmorSettingsIndex`,
  `PackGeoArmorRenderer`, `PackGlowLayer`) — it quotes them verbatim.
- The README states F3+T re-reads packs (the engine's intended behavior); the live reload itself is still
  not confirmed in the log (see Open items).

## Open items

- **Missing segment bone is now reported** (`PackGeoArmorRenderer.warnMissingBones`, once per model +
  segment, verified live with `golden_leggings` → the helmet geo). Still open: the alternative *fallback* —
  returning the model's first root bone when the expected segment bone is missing, which would make
  arbitrary one-root Blockbench models render as-is instead of warning.
- `diamond_set.geo.json` and the sample's `iron_set.geo.json` (both exported from one Blockbench project)
  put their `armorHead` cubes at y 31.5..43, i.e. above the head band (24..32). That is the asset author's
  geometry, not the mod: the mod renders the cubes exactly where the geo puts them. Do not "fix" placement
  in the mod.
- **Segment bone pivots: documented + logged, debug line verified live.** `logBonePivots` was seen in a
  run (`-Dgeoarmor.debug=true`, one line per slot: `armorLeftArm=[-5.0, 22.0, 0.0]`,
  `armorRightArm=[5.0, 22.0, 0.0]`, `armorBody=[0.0, 24.0, 0.0]`); X in that line is already mirrored, so
  the sign is the opposite of the `.geo.json`. The pivot contract is read off GeckoLib's code plus
  `pseudoarmor-sample`, and the shipped samples have **no arm cubes**, so "the piece follows the limb"
  still has no pixel-level proof. A real pack showed the failure mode (a pack whose `kosha.geo.json` had
  both arm pivots on the glove), so re-check this class against that model if a follow-along bug is
  reported.
- Not yet exercised: a definition dropped by F3+T (should fall back to vanilla), modded armor items, and the
  `display` key in game. Multiple pieces at once **is** now exercised (full iron set, all four slots).
- **`translucent` has no pixel-level proof yet.** Live evidence is the code path only: definition carries
  `translucent true`, `render mode for minecraft:iron_chestplate: translucent` appears, the pass still
  draws, no exception. The blend itself was not captured with a semi-transparent texture — three attempts
  ended with the game window gone/another window in the captured region (see recipe 6), and it is not yet
  established whether the last frames were third person or first person (`F5` delivery was not confirmed
  then; log `Minecraft.getInstance().options.getCameraType()` if this needs re-checking).
- `glow` is verified end-to-end **by pixels**: fill = the texture's own colour at whatever light level
  (62 for a (60,0,0) texture), `"radiant"` = 225 in the same frame, and a full iron set submits all four
  slots (`glow pass:` per piece). The *fallback* path is verified live too: with no `_glowmask.png`
  present the log shows `glow mask '...' does not exist, the whole piece will glow` for the helmet and
  the chestplate, and both glow.
- The sample pack ships `"glow": true` on `minecraft:iron_helmet`, `"glow": "radiant"` on
  `minecraft:iron_chestplate` and `"glow": {"mask": "...iron_set_runes.png"}` on
  `minecraft:iron_leggings`, with a generated `iron_set_glowmask.png` (white where the base texture's
  luminance ≥ 225, i.e. the armour highlights) as the helmet's default mask.

- `EntityRendererMixin` runs once per non-head slot whose item is pack-owned, and GeckoLib's `@WrapMethod`
  on `createRenderState` re-enters `extractRenderState` per slot — bounded, but it is the main per-frame
  cost to watch.
