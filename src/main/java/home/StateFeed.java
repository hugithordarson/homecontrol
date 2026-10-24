package home;

import java.util.List;

import com.google.gson.Gson;

import home.dirigera.Device;

/**
 * The whole house as JSON, for GET /api/state — what the iOS app (and anything
 * else that isn't a rendered page) reads. Lights are flattened into what a client
 * needs rather than the hub's raw device records, and the hub's stale values for a
 * bulb's inactive mode are left out: hue/saturation only in color mode, kelvin only
 * in temperature mode.
 */

public class StateFeed {

	/**
	 * Served from a lights snapshot this young at most, so a phone and a few tablets
	 * polling don't each cost a full fetch from every hub
	 */
	private static final long MAX_SNAPSHOT_AGE_MS = 1000;

	private static final Gson gson = new Gson();

	public record State( List<Room> rooms, List<SceneInfo> scenes, List<Palette.Preset> palette, List<String> warnings, int runningFades ) {}

	public record Room( String id, String name, String hub, List<Light> lights ) {}

	public record Light(
			String id,
			String name,
			boolean on,
			boolean reachable,
			boolean dimmable,
			Integer level,
			boolean supportsTemperature,
			boolean supportsColor,
			boolean colorMode,
			Integer kelvin,
			int warmest,
			int coldest,
			Double hue,
			Double saturation ) {}

	public record SceneInfo( String name, String slug, int lightCount, boolean away ) {}

	public static String json() {
		return gson.toJson( state() );
	}

	public static State state() {
		final List<Room> rooms = Hub.roomGroups( Hub.lightsSnapshot( MAX_SNAPSHOT_AGE_MS ) )
				.stream()
				.map( group -> new Room( group.id(), group.name(), group.hubName(), group.lights().stream().map( StateFeed::light ).toList() ) )
				.toList();

		final List<SceneInfo> scenes = Scenes.all()
				.stream()
				.map( scene -> new SceneInfo( scene.name(), scene.slug(), scene.lightCount(), away( scene ) ) )
				.toList();

		return new State( rooms, scenes, Palette.all(), Hub.fetchErrors(), Scenes.runningFades() );
	}

	private static Light light( final Device device ) {
		final Device.Attributes attributes = device.attributes();
		final boolean colorMode = device.inColorMode();

		return new Light(
				device.id(),
				device.name(),
				device.on(),
				device.reachable(),
				device.dimmable(),
				attributes != null ? attributes.lightLevel() : null,
				device.supportsColorTemperature(),
				device.supportsColor(),
				colorMode,
				attributes != null && !colorMode ? attributes.colorTemperature() : null,
				device.warmestTemperature(),
				device.coldestTemperature(),
				attributes != null && colorMode ? attributes.colorHue() : null,
				attributes != null && colorMode && attributes.colorSaturation() != null ? Math.clamp( attributes.colorSaturation(), 0d, 1d ) : null );
	}

	/**
	 * @return true if every hub the scene's lights live on is currently down — the scene belongs to the other location
	 */
	private static boolean away( final Scene scene ) {
		return scene.hubs() != null && !scene.hubs().isEmpty() && scene.hubs().stream().allMatch( Hub::hubDown );
	}
}
