package home;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import home.dirigera.Device;

/**
 * Storage and application of scenes (persisted in ~/.homecontrol/scenes.json).
 */

public class Scenes {

	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger( Scenes.class );

	private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	private static List<Scene> _scenes;

	public static List<Scene> all() {
		if( _scenes == null ) {
			if( Files.exists( scenesFile() ) ) {
				try {
					_scenes = gson.fromJson( Files.readString( scenesFile() ), new TypeToken<List<Scene>>() {}.getType() );
				}
				catch( final IOException e ) {
					throw new UncheckedIOException( "Could not read " + scenesFile(), e );
				}
			}

			if( _scenes == null ) {
				_scenes = new ArrayList<>();
			}
		}

		return _scenes;
	}

	public static Scene bySlug( final String slug ) {
		return all()
				.stream()
				.filter( scene -> scene.slug().equals( slug ) )
				.findFirst()
				.orElse( null );
	}

	/**
	 * Snapshots the current state of every light into a new scene.
	 * An existing scene with the same slug is replaced.
	 */
	public static Scene captureCurrent( final String name ) {
		final Map<String, Scene.LightState> states = new LinkedHashMap<>();
		final List<String> hubs = new ArrayList<>();

		for( final Device light : Hub.lights() ) {
			final String hubAddress = Hub.hubAddressForDevice( light.id() );

			if( hubAddress != null && !hubs.contains( hubAddress ) ) {
				hubs.add( hubAddress );
			}
			// A color bulb showing a color gets its hue/saturation captured; a bulb in
			// white mode (or without color support) gets its color temperature instead
			final boolean inColorMode = light.inColorMode();

			states.put( light.id(), new Scene.LightState(
					light.on(),
					light.dimmable() ? light.attributes().lightLevel() : null,
					light.supportsColorTemperature() && !inColorMode ? light.attributes().colorTemperature() : null,
					inColorMode ? light.attributes().colorHue() : null,
					inColorMode ? light.attributes().colorSaturation() : null ) );
		}

		final Scene scene = new Scene( name, slugify( name ), hubs, states );

		final List<Scene> scenes = all();
		scenes.removeIf( existing -> existing.slug().equals( scene.slug() ) );
		scenes.add( scene );
		save();

		return scene;
	}

	/**
	 * Renames a scene; the slug follows the name. Refuses a name whose slug would
	 * collide with another scene.
	 */
	public static void rename( final String slug, final String newName ) {
		final Scene scene = bySlug( slug );

		if( scene == null ) {
			throw new IllegalArgumentException( "No scene with slug " + slug );
		}

		final String newSlug = slugify( newName );
		final Scene collision = bySlug( newSlug );

		if( collision != null && collision != scene ) {
			throw new IllegalArgumentException( "There's already a scene called \"%s\"".formatted( collision.name() ) );
		}

		final List<Scene> scenes = all();
		scenes.set( scenes.indexOf( scene ), new Scene( newName.trim(), newSlug, scene.hubs(), scene.states() ) );
		save();
	}

	public static void delete( final String slug ) {
		all().removeIf( scene -> scene.slug().equals( slug ) );
		save();
	}

	/**
	 * Applies a scene: each light is sent its stored state. isOn and lightLevel are
	 * sent as separate patches — the hub is picky about combined attribute updates.
	 * Lights are handled concurrently (one virtual thread each, throttled per hub by
	 * DirigeraClient) so the apartment changes semi-simultaneously rather than
	 * cascading light by light. Lights whose hub is unreachable (e.g. the scene is
	 * for the other location) are skipped rather than aborting the rest of the scene.
	 *
	 * @return null when everything applied, otherwise a human-readable note about the skipped lights
	 */
	public static String apply( final String slug ) {
		return transitionTo( slug, java.time.Duration.ZERO, null );
	}

	/**
	 * The hub's per-patch transitionTime tops out around 100 minutes (Zigbee counts
	 * tenths of a second in 16 bits); longer requests are clamped.
	 */
	private static final long MAX_TRANSITION_MS = 6_000_000;

	/**
	 * Fades the lights into a scene over a duration — the bulbs do the fading via
	 * transitionTime, so it costs one command per attribute regardless of length.
	 * A zero duration applies the scene at once.
	 *
	 * @param roomIds limit the transition to lights in these hub rooms (null = all)
	 * @return null when everything applied, otherwise a human-readable note
	 */
	public static String transitionTo( final String slug, final java.time.Duration duration, final List<String> roomIds ) {
		final Scene scene = bySlug( slug );

		if( scene == null ) {
			throw new IllegalArgumentException( "No scene with slug " + slug );
		}

		final int transitionMs = (int)Math.min( MAX_TRANSITION_MS, Math.max( 0, duration.toMillis() ) );
		final Map<String, Device> devices = new java.util.HashMap<>();

		for( final Device light : Hub.lightsSnapshot( 3000 ) ) {
			devices.put( light.id(), light );
		}

		final java.util.concurrent.atomic.AtomicInteger unreachable = new java.util.concurrent.atomic.AtomicInteger();
		final java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
		int included = 0;

		try( final var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor() ) {
			for( final Map.Entry<String, Scene.LightState> entry : scene.states().entrySet() ) {
				final Device device = devices.get( entry.getKey() );

				if( roomIds != null && ( device == null || !roomIds.contains( device.roomId() ) ) ) {
					continue;
				}

				included++;
				executor.submit( () -> applyToLight( entry.getKey(), device, entry.getValue(), transitionMs, unreachable, failed ) );
			}
		}

		if( unreachable.get() == 0 && failed.get() == 0 ) {
			return null;
		}

		final StringBuilder note = new StringBuilder();

		if( unreachable.get() > 0 ) {
			note.append( "%s of %s lights are at a hub that's out of reach right now".formatted( unreachable.get(), included ) );
		}

		if( failed.get() > 0 ) {
			if( note.length() > 0 ) {
				note.append( "; " );
			}

			note.append( "%s of %s lights didn't take the update (the hub didn't respond — try again)".formatted( failed.get(), included ) );
		}

		return note.toString();
	}

	private static void applyToLight( final String deviceId, final Device device, final Scene.LightState state, final int transitionMs, final java.util.concurrent.atomic.AtomicInteger unreachable, final java.util.concurrent.atomic.AtomicInteger failed ) {
		final String hub = Hub.hubAddressForDevice( deviceId );

		if( hub == null || Hub.hubDown( hub ) ) {
			unreachable.incrementAndGet();
			return;
		}

		final boolean fading = transitionMs > 0;
		final boolean currentlyOn = device != null && device.on();
		final Integer transition = fading ? transitionMs : null;

		try {
			if( Boolean.FALSE.equals( state.isOn() ) ) {
				if( fading && currentlyOn && device.dimmable() ) {
					// Fade down to minimum over the duration, then switch off invisibly —
					// and remember the brightness owed for the next turn-on
					Hub.setAttributes( deviceId, Map.of( "lightLevel", 1 ), transitionMs );
					Thread.sleep( transitionMs + 500 );
					Hub.setAttributes( deviceId, Map.of( "isOn", false ) );

					if( device.attributes() != null && device.attributes().lightLevel() != null ) {
						Hub.registerPendingLevelRestore( deviceId, device.attributes().lightLevel() );
					}
				}
				else {
					Hub.setAttributes( deviceId, Map.of( "isOn", false ) );
				}

				return;
			}

			// Successive patches to the same bulb get a little air between them —
			// the hub answers back-to-back updates to one device with transient errors
			if( !currentlyOn ) {
				Hub.setAttributes( deviceId, Map.of( "isOn", true ) );

				if( fading && device != null && device.dimmable() ) {
					// Wake at minimum so the fade-in starts from darkness, not the remembered level
					Thread.sleep( PATCH_SPACING_MS );
					Hub.setAttributes( deviceId, Map.of( "lightLevel", 1 ), 0 );
				}
			}

			if( state.lightLevel() != null ) {
				Thread.sleep( PATCH_SPACING_MS );
				Hub.setAttributes( deviceId, Map.of( "lightLevel", state.lightLevel() ), transition );
			}

			if( state.colorTemperature() != null ) {
				Thread.sleep( PATCH_SPACING_MS );
				Hub.setAttributes( deviceId, Map.of( "colorTemperature", state.colorTemperature() ), transition );
			}

			if( state.colorHue() != null && state.colorSaturation() != null ) {
				Thread.sleep( PATCH_SPACING_MS );
				Hub.setAttributes( deviceId, Map.of( "colorHue", state.colorHue(), "colorSaturation", state.colorSaturation() ), transition );
			}
		}
		catch( final InterruptedException e ) {
			Thread.currentThread().interrupt();
			failed.incrementAndGet();
		}
		catch( final Exception e ) {
			logger.warn( "Scene update of {} failed: {}", deviceId, e.toString() );
			failed.incrementAndGet();
		}
	}

	private static final long PATCH_SPACING_MS = 120;

	private static void save() {
		try {
			Files.createDirectories( Hub.configDirectory() );
			Files.writeString( scenesFile(), gson.toJson( _scenes ) );
		}
		catch( final IOException e ) {
			throw new UncheckedIOException( "Could not write " + scenesFile(), e );
		}
	}

	static String slugify( final String name ) {
		String slug = name.toLowerCase().replaceAll( "[^a-z0-9áðéíóúýþæö]+", "-" );
		slug = slug.replaceAll( "^-+|-+$", "" );
		return slug.isEmpty() ? "scene" : slug;
	}

	private static Path scenesFile() {
		return Hub.configDirectory().resolve( "scenes.json" );
	}
}
