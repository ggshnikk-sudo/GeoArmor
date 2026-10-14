package dev.geoarmor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-armor-item render settings, read from {@code assets/<ns>/config/armor/*.json} in resource packs.
 *
 * <p>The engine binds one GeckoLib {@code geo} model + {@code texture} (and optionally an animation
 * file) to one armor item (the registry id, e.g. {@code minecraft:leather_helmet}). The piece is
 * rendered by GeckoLib's armor renderer instead of vanilla's fixed layer, so the geo may use any
 * GeckoLib bone names and the texture may be RGBA.
 *
 * <p>Format:
 * <pre>{@code
 * {
 *   "armor": {
 *     "minecraft:leather_helmet": {
 *       "geo": "mypack:diamond_set",
 *       "texture": "mypack:textures/armor/diamond_set.png",
 *       "animation": "mypack:diamond_set",
 *       "idle": "misc.idle",
 *       "translucent": false,
 *       "glow": true
 *     }
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code geo} (required) — the geometry asset id. GeckoLib bakes every file under
 *       {@code assets/<ns>/geckolib/models/**} and keys it by the path with that root and the file
 *       extension removed, so {@code mypack:diamond_set} is
 *       {@code assets/mypack/geckolib/models/diamond_set.geo.json}. A path spelling such as
 *       {@code mypack:geo/diamond_set.geo.json} or {@code mypack:geckolib/models/diamond_set.geo.json}
 *       is accepted and reduced to that key by {@link #geckoAssetId}.</li>
 *   <li>{@code texture} — the texture id as GeckoLib hands it to the render type, i.e. the full
 *       resource path {@code textures/.../name.png}; defaults to the geometry's own name beside it
 *       ({@code mypack:diamond_set} → {@code textures/armor/diamond_set.png}).</li>
 *   <li>{@code animation} — optional animation asset id, same id rules as {@code geo}, from
 *       {@code assets/<ns>/geckolib/animations/<name>.animation.json}; when present the item idles.</li>
 *   <li>{@code idle} — optional clip name to loop for the idle controller (default {@code misc.idle}).</li>
 *   <li>{@code display} — optional placement of the model on the armor piece, in the same shape
 *       Blockbench writes into a Java item model (Display → Head):
 *       <pre>{@code {"translate": [x, y, z], "rotate": [x, y, z], "scale": 1.6}}</pre>
 *       {@code translate} is in model units (16 = one block, +y is up), {@code rotate} in degrees and
 *       {@code scale} either one number or {@code [x, y, z]}. Values are applied inside the armor
 *       segment, so the piece keeps following the head/limb it is drawn on. See {@link Display}.</li>
 *   <li>{@code translucent} — optional, default {@code false}. GeckoLib draws armor with vanilla's
 *       <em>cutout</em> render type, which throws away every pixel below the cutout threshold and
 *       renders the rest fully opaque, so a semi-transparent texture (glass, visor, cape, ghost armor)
 *       comes out solid. {@code true} switches the piece to vanilla's armor translucent render type:
 *       alpha is blended and the texture's alpha channel is honoured. Costs correct draw sorting, so
 *       it is per entry and off unless asked for.</li>
 *   <li>{@code glow} — optional glow pass, default off; same key and meaning as Armor Model API's
 *       {@code glow}, so a PseudoArmor-style pack works unchanged. {@code true} (or {@code false}) for a
 *       plain full-bright pass, {@code "radiant"} for a much brighter fill-plus-burn pass, or an object
 *       with a {@code mask} giving an explicit mask id, e.g.
 *       <pre>{@code
 *       "glow": "radiant"
 *       "glow": {"mask": "mypack:textures/armor/set_runes.png"}
 *       }</pre>
 *       The mask is the same UV layout as the base texture; only its opaque pixels glow, so a blade,
 *       runes or eyes light up while the rest of the piece stays shaded. Under a mask the glow keeps the
 *       <em>base texture's colour</em> and takes only the mask's shape (the two are baked together), and
 *       the default mask is the base texture with {@code _glowmask} appended
 *       ({@code .../set.png} → {@code .../set_glowmask.png}). When that file is absent the whole piece
 *       glows (the base texture is used as its own mask) and one warning is logged.</li>
 * </ul>
 *
 * <p>Unknown keys (e.g. {@code trim} from other armor engines) are ignored, so packs authored for a
 * different engine still load their geometry here.
 *
 * <p>All parsing lives here as pure functions over Gson so they can be tested without a running game;
 * file discovery, pack-priority merging and logging belong to {@link ArmorSettingsIndex}.
 */
public record ArmorSettings(
		Map<String, ArmorSettings.Entry> items,
		List<String> problems,
		boolean declared
) {
	/** The idle clip played when a pack does not name one (matches GeckoLib's {@code DefaultAnimations.IDLE}). */
	public static final String DEFAULT_IDLE = "misc.idle";

	/**
	 * One item's render settings.
	 *
	 * @param itemId      the armor item's registry id
	 * @param geo         the geometry asset id (required), already reduced to GeckoLib's cache key
	 * @param texture     the base texture resource id ({@code textures/.../name.png})
	 * @param animation   the animation asset id (GeckoLib's cache key), or {@code null} when static
	 * @param idle        the clip name the idle controller loops
	 * @param display     where the model sits on the armor piece ({@link Display#DEFAULT} when unwritten)
	 * @param translucent whether the piece blends its texture's alpha instead of cutout-rendering it
	 * @param glow        the glow pass to draw over the piece, or {@code null} for none ({@link Glow})
	 */
	public record Entry(
			String itemId,
			Identifier geo,
			Identifier texture,
			@Nullable Identifier animation,
			String idle,
			Display display,
			boolean translucent,
			@Nullable Glow glow
	) {
	}

	/** How bright a {@code glow} pass is drawn. */
	public enum GlowKind {
		/** One full-bright pass: the mask's shape tinted by the base texture. */
		GLOW,
		/**
		 * The {@link #GLOW} fill plus additive burn passes, for a piece that reads as genuinely bright
		 * rather than merely unshaded. Same two numbers as Armor Model API uses: a 2.5 gain is one fill
		 * plus two burns.
		 */
		RADIANT
	}

	/**
	 * One item's glow request.
	 *
	 * @param kind how bright the pass is
	 * @param mask an explicit mask texture id, or {@code null} for the derived
	 *             {@code <base texture>_glowmask.png} ({@link #glowMask})
	 */
	public record Glow(GlowKind kind, @Nullable Identifier mask) {
	}

	/**
	 * Where the model is placed on the armor piece, in the same units Blockbench writes into an item
	 * model's {@code display.head}: {@code translate} in 1/16-block units (16 = 1 block, +y up, the
	 * space the geo is authored in), {@code rotate} in degrees, {@code scale} as a factor.
	 *
	 * <p>Applied inside the armor segment's own space, so a helmet's offsets follow the head as it
	 * turns instead of staying fixed to the body.
	 */
	public record Display(float translateX, float translateY, float translateZ,
			float rotateX, float rotateY, float rotateZ,
			float scaleX, float scaleY, float scaleZ) {

		public static final Display DEFAULT = new Display(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);

		public boolean isDefault() {
			return equals(DEFAULT);
		}
	}

	/** The key the settings live under. */
	public static final String KEY = "armor";

	/**
	 * What one settings file parsed to.
	 *
	 * @param items    settings by item id, empty when the file declares nothing usable
	 * @param problems human-readable authoring errors, logged by the index
	 * @param declared whether the file had an {@code "armor"} object at all
	 */
	public record Result(Map<String, Entry> items, List<String> problems, boolean declared) {
	}

	/**
	 * Parses one settings file.
	 *
	 * <p>Never throws on bad authoring: every problem is collected into {@link Result#problems()} and
	 * the remaining entries still load. Only a structurally broken file (not an object, or no
	 * {@code "armor"} key) reports {@code declared = false}.
	 */
	public static Result parse(Identifier fileId, JsonElement root) {
		List<String> problems = new ArrayList<>();
		Map<String, Entry> items = new LinkedHashMap<>();

		if (!root.isJsonObject()) {
			problems.add(fileId + " is not a JSON object");
			return new Result(items, problems, false);
		}

		JsonElement declared = root.getAsJsonObject().get(KEY);

		if (declared == null || !declared.isJsonObject()) {
			problems.add(fileId + " has no '" + KEY + "' object");
			return new Result(items, problems, false);
		}

		for (Map.Entry<String, JsonElement> entry : declared.getAsJsonObject().entrySet()) {
			String itemId = entry.getKey();

			if (!entry.getValue().isJsonObject()) {
				problems.add(itemId + ": entry must be an object, entry skipped");
				continue;
			}

			Entry parsed = parseEntry(itemId, entry.getValue().getAsJsonObject(), problems);

			if (parsed != null) {
				items.put(itemId, parsed);
			}
		}

		return new Result(items, problems, true);
	}

	@Nullable
	private static Entry parseEntry(String itemId, JsonObject object, List<String> problems) {
		// Geo is the only required field: without it there is nothing to render on the armor piece.
		String rawGeo = readString(object, "geo");

		if (rawGeo == null) {
			problems.add(itemId + ": no 'geo', entry skipped");
			return null;
		}

		Identifier geo = Identifier.tryParse(rawGeo);

		if (geo == null) {
			problems.add(itemId + ": 'geo' value '" + rawGeo + "' is not a usable id, entry skipped");
			return null;
		}

		geo = geckoAssetId(geo);

		Identifier texture = readTexture(object, geo, itemId, problems);
		Identifier animation = readOptionalId(object, "animation", itemId, problems);

		if (animation != null) {
			animation = geckoAssetId(animation);
		}

		String idle = readStringOrDefault(object, "idle", DEFAULT_IDLE, itemId, problems);
		Display display = readDisplay(object, itemId, problems);
		boolean translucent = readBoolean(object, "translucent", itemId, problems);
		Glow glow = readGlow(object, itemId, problems);

		return new Entry(itemId, geo, texture, animation, idle, display, translucent, glow);
	}

	/**
	 * Reads the optional {@code glow} pass: {@code true}/{@code "glow"} (derived mask beside the base
	 * texture), {@code "radiant"} (the same, drawn brighter), or {@code {"mask": "<texture id>"}} for an
	 * explicit mask, with an optional {@code "mode"} of {@code "glow"} or {@code "radiant"} alongside it.
	 * {@code false}/absent is no glow.
	 */
	@Nullable
	private static Glow readGlow(JsonObject object, String itemId, List<String> problems) {
		JsonElement element = object.get("glow");

		if (element == null) {
			return null;
		}

		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
			return element.getAsBoolean() ? new Glow(GlowKind.GLOW, null) : null;
		}

		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
			return glowKind(element.getAsString(), itemId, problems).map(kind -> new Glow(kind, null)).orElse(null);
		}

		if (element.isJsonObject()) {
			JsonObject glow = element.getAsJsonObject();

			if (!glow.has("mask")) {
				problems.add(itemId + ": 'glow' object has no 'mask', ignored");
				return null;
			}

			Identifier mask = readOptionalId(glow, "mask", itemId, problems);

			if (mask == null) {
				return null;
			}

			String mode = readString(glow, "mode");

			if (mode == null) {
				return new Glow(GlowKind.GLOW, mask);
			}

			return glowKind(mode, itemId, problems).map(kind -> new Glow(kind, mask)).orElse(null);
		}

		problems.add(itemId + ": 'glow' must be true, false, a mode string or a {\"mask\": ...} object, ignored");
		return null;
	}

	/** Maps a glow mode name to its kind: {@code "glow"} or {@code "radiant"}, else reported and empty. */
	private static Optional<GlowKind> glowKind(String mode, String itemId, List<String> problems) {
		return switch (mode) {
			case "glow" -> Optional.of(GlowKind.GLOW);
			case "radiant" -> Optional.of(GlowKind.RADIANT);
			default -> {
				problems.add(itemId + ": 'glow' mode '" + mode + "' is not \"glow\" or \"radiant\", ignored");
				yield Optional.empty();
			}
		};
	}

	/** Reads a boolean property, defaulting to false and reporting a wrong-typed value. */
	private static boolean readBoolean(JsonObject object, String key, String itemId, List<String> problems) {
		JsonElement element = object.get(key);

		if (element == null) {
			return false;
		}

		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
			problems.add(itemId + ": '" + key + "' must be true or false, ignored");
			return false;
		}

		return element.getAsBoolean();
	}

	/** Reads the optional {@code display} object; a malformed value is reported and treated as absent. */
	private static Display readDisplay(JsonObject object, String itemId, List<String> problems) {
		JsonElement element = object.get("display");

		if (element == null) {
			return Display.DEFAULT;
		}

		if (!element.isJsonObject()) {
			problems.add(itemId + ": 'display' must be an object, ignored");
			return Display.DEFAULT;
		}

		JsonObject display = element.getAsJsonObject();
		float[] translate = readVec3(display, "translate", itemId, problems);
		float[] rotate = readVec3(display, "rotate", itemId, problems);
		float[] scale = readScale(display, itemId, problems);

		return new Display(
				translate[0], translate[1], translate[2],
				rotate[0], rotate[1], rotate[2],
				scale[0], scale[1], scale[2]
		);
	}

	/** {@code display.scale} accepts a single number or an array of three; defaults to one. */
	private static float[] readScale(JsonObject display, String itemId, List<String> problems) {
		JsonElement element = display.get("scale");

		if (element == null) {
			return new float[]{1.0F, 1.0F, 1.0F};
		}

		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
			float scale = element.getAsFloat();
			return new float[]{scale, scale, scale};
		}

		return readVec3(display, "scale", itemId, problems, 1.0F);
	}

	/** Reads an optional {@code [x, y, z]} number array, defaulting to zeroes. */
	private static float[] readVec3(JsonObject object, String key, String itemId, List<String> problems) {
		return readVec3(object, key, itemId, problems, 0.0F);
	}

	/** Reads an optional {@code [x, y, z]} number array; a malformed value is reported and defaulted. */
	private static float[] readVec3(JsonObject object, String key, String itemId, List<String> problems, float fallback) {
		JsonElement element = object.get(key);

		if (element == null) {
			return new float[]{fallback, fallback, fallback};
		}

		if (!element.isJsonArray() || element.getAsJsonArray().size() != 3) {
			problems.add(itemId + ": 'display." + key + "' must be an array of 3 numbers, ignored");
			return new float[]{fallback, fallback, fallback};
		}

		float[] out = new float[3];

		for (int i = 0; i < 3; i++) {
			JsonElement value = element.getAsJsonArray().get(i);

			if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
				problems.add(itemId + ": 'display." + key + "' must be an array of 3 numbers, ignored");
				return new float[]{fallback, fallback, fallback};
			}

			out[i] = value.getAsFloat();
		}

		return out;
	}

	/** Reads a string property, recording a problem when it is present but not a string. */
	@Nullable
	private static String readString(JsonObject object, String key) {
		JsonElement element = object.get(key);
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
				? element.getAsString()
				: null;
	}

	/** Reads an optional id property; a present-but-unparseable value is reported and treated as absent. */
	@Nullable
	private static Identifier readOptionalId(JsonObject object, String key, String itemId, List<String> problems) {
		JsonElement element = object.get(key);

		if (element == null) {
			return null;
		}

		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			problems.add(itemId + ": '" + key + "' must be a string id, ignored");
			return null;
		}

		Identifier id = Identifier.tryParse(element.getAsString());

		if (id == null) {
			problems.add(itemId + ": '" + key + "' value '" + element.getAsString() + "' is not a usable id, ignored");
		}

		return id;
	}

	/** Reads a string property with a fallback, reporting a present-but-wrong-typed value. */
	private static String readStringOrDefault(JsonObject object, String key, String fallback, String itemId, List<String> problems) {
		JsonElement element = object.get(key);

		if (element == null) {
			return fallback;
		}

		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			problems.add(itemId + ": '" + key + "' must be a string, using " + fallback);
			return fallback;
		}

		return element.getAsString();
	}

	/** Texture defaults to beside the geometry's own name: {@code mypack:hood} becomes {@code textures/armor/hood.png}. */
	public static Identifier textureBeside(Identifier geo) {
		String path = geckoAssetId(geo).getPath();
		int slash = path.lastIndexOf('/');

		if (slash >= 0) {
			path = path.substring(slash + 1);
		}

		return Identifier.fromNamespaceAndPath(geo.getNamespace(), "textures/armor/" + path + ".png");
	}

	/**
	 * The default glow mask beside a texture: {@code textures/armor/set.png} →
	 * {@code textures/armor/set_glowmask.png}. The same convention as GeckoLib's own emissive layer and
	 * Armor Model API's derived mask, so a pack written for either keeps working when it is pointed at an
	 * armor item here.
	 */
	public static Identifier glowMask(Identifier texture) {
		String path = texture.getPath();
		int dot = path.lastIndexOf('.');
		return texture.withPath((dot < 0 ? path : path.substring(0, dot)) + "_glowmask.png");
	}

	/**
	 * Reduces a written geometry/animation id to the key GeckoLib bakes that asset under.
	 *
	 * <p>GeckoLib lists every file below {@code assets/<ns>/geckolib/models} and
	 * {@code assets/<ns>/geckolib/animations} and keys the result by the path with that root and the
	 * file extension stripped: {@code assets/mypack/geckolib/models/hood.geo.json} is cached as
	 * {@code mypack:hood}, and its animation as {@code mypack:hood} from
	 * {@code .../geckolib/animations/hood.animation.json}. Handing GeckoLib a path spelled any other
	 * way makes it log a "superfluous prefix or suffix" and fail to find the model every frame, so the
	 * roots ({@code geckolib/}, {@code models/}, {@code animations/}, and the older {@code geo/}) and
	 * the extensions ({@code .geo.json}, {@code .animation.json}, {@code .json}, {@code .geo},
	 * {@code .animation}) are stripped once here.
	 *
	 * <p>Idempotent: a value that is already a bare key is returned unchanged.
	 */
	public static Identifier geckoAssetId(Identifier id) {
		String path = id.getPath();

		// At most two roots in a row ("geckolib/models/"), each stripped once.
		for (int pass = 0; pass < 2; pass++) {
			String stripped = stripAnyPrefix(path);

			if (stripped.equals(path)) {
				break;
			}

			path = stripped;
		}

		for (String suffix : SUFFIXES) {
			if (path.endsWith(suffix)) {
				path = path.substring(0, path.length() - suffix.length());
				break;
			}
		}

		return path.equals(id.getPath()) ? id : id.withPath(path);
	}

	/** The model file GeckoLib bakes {@code geo} from, for validation and tooling. */
	public static Identifier modelFile(Identifier geo) {
		return file(geo, "geckolib/models/", ".geo.json");
	}

	/** The animation file GeckoLib bakes {@code animation} from, for validation and tooling. */
	public static Identifier animationFile(Identifier animation) {
		return file(animation, "geckolib/animations/", ".animation.json");
	}

	private static Identifier file(Identifier asset, String root, String extension) {
		return Identifier.fromNamespaceAndPath(asset.getNamespace(), root + asset.getPath() + extension);
	}

	private static String stripAnyPrefix(String path) {
		for (String root : ROOTS) {
			if (path.startsWith(root)) {
				return path.substring(root.length());
			}
		}

		return path;
	}

	/** Roots a written asset id may carry, including GeckoLib 4's {@code geo/}. */
	private static final List<String> ROOTS = List.of("geckolib/", "models/", "animations/", "geo/");

	/** Longest first, so {@code x.geo.json} loses the whole {@code .geo.json} rather than just {@code .json}. */
	private static final List<String> SUFFIXES = List.of(".geo.json", ".animation.json", ".json", ".geo", ".animation");

	/** Texture defaults to beside the geometry, complaining only when the file asked for a bad one. */
	private static Identifier readTexture(JsonObject object, Identifier geo, String itemId, List<String> problems) {
		String raw = readString(object, "texture");

		if (raw == null) {
			return textureBeside(geo);
		}

		Identifier texture = Identifier.tryParse(raw);

		if (texture == null) {
			problems.add(itemId + ": 'texture' value '" + raw + "' is not a usable id, using " + textureBeside(geo));
			return textureBeside(geo);
		}

		return texture;
	}
}
