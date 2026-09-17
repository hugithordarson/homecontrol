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

	/**
	 * Fades up to this long are a single command with transitionTime. Longer fades
	 * are stepped from here: some bulb generations (the 2017 CWS opal 600lm, the CWS
	 * globe 806lm) silently ignore multi-minute transitions, while every generation
	 * honors a short one — so the server sends an interpolated target every STEP_MS,
	 * each with a short transition, and the whole fleet fades identically.
	 */
	private static final long SINGLE_COMMAND_MAX_MS = 10_000;
	private static final long STEP_MS = 15_000;
	private static final int STEP_TRANSITION_MS = 12_000;

	private static void applyToLight( final String deviceId, final Device device, final Scene.LightState state, final int transitionMs, final java.util.concurrent.atomic.AtomicInteger unreachable, final java.util.concurrent.atomic.AtomicInteger failed ) {
		final String hub = Hub.hubAddressForDevice( deviceId );

		if( hub == null || Hub.hubDown( hub ) ) {
			unreachable.incrementAndGet();
			return;
		}

		final boolean fading = transitionMs > 0;
		final boolean stepped = transitionMs > SINGLE_COMMAND_MAX_MS && device != null && device.attributes() != null;
		final boolean currentlyOn = device != null && device.on();
		final Integer transition = fading ? transitionMs : null;

		try {
			if( Boolean.FALSE.equals( state.isOn() ) ) {
				if( fading && currentlyOn && device.dimmable() ) {
					// Fade down to minimum over the duration, then switch off invisibly —
					// and remember the brightness owed for the next turn-on
					if( stepped ) {
						steppedFade( deviceId, device, new Scene.LightState( true, 1, null, null, null ), transitionMs );
					}
					else {
						Hub.setAttributes( deviceId, Map.of( "lightLevel", 1 ), transitionMs );
						Thread.sleep( transitionMs + 500 );
					}

					Hub.setAttributes( deviceId, Map.of( "isOn", false ) );

					if( device.attributes().lightLevel() != null ) {
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
			Device from = device;

			if( !currentlyOn ) {
				Hub.setAttributes( deviceId, Map.of( "isOn", true ) );

				if( fading && device != null && device.dimmable() ) {
					// Wake at minimum so the fade-in starts from darkness, not the remembered level
					Thread.sleep( PATCH_SPACING_MS );
					Hub.setAttributes( deviceId, Map.of( "lightLevel", 1 ), 0 );
					from = withLevel( device, 1 );
				}
			}

			if( stepped ) {
				steppedFade( deviceId, from, state, transitionMs );
				return;
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

	/**
	 * Walks a bulb from its current state to the target in STEP_MS increments on a
	 * wall-clock schedule, each step a short transition the bulb finishes before the
	 * next arrives (no snap-completed transitions, no blinks).
	 */
	private static void steppedFade( final String deviceId, final Device from, final Scene.LightState to, final int durationMs ) throws InterruptedException {
		final Device.Attributes a = from.attributes();
		final int steps = (int)Math.max( 1, Math.ceil( durationMs / (double)STEP_MS ) );
		final long start = System.currentTimeMillis();

		final Integer level0 = from.dimmable() && a.lightLevel() != null ? a.lightLevel() : null;
		final Integer temp0 = a.colorTemperature();
		final Double hue0 = a.colorHue();
		final Double sat0 = a.colorSaturation() != null ? Math.clamp( a.colorSaturation(), 0d, 1d ) : null;

		for( int i = 1; i <= steps; i++ ) {
			final double f = i / (double)steps;
			final long due = start + (long)( f * durationMs );
			final long wait = due - System.currentTimeMillis();

			if( wait > 0 ) {
				Thread.sleep( wait );
			}

			final int transition = i == steps ? Math.min( STEP_TRANSITION_MS, (int)Math.max( 1000, durationMs / steps - 3000 ) ) : STEP_TRANSITION_MS;

			if( to.lightLevel() != null && from.dimmable() ) {
				final int level = level0 == null ? to.lightLevel() : (int)Math.round( level0 + ( to.lightLevel() - level0 ) * f );
				Hub.setAttributes( deviceId, Map.of( "lightLevel", Math.clamp( level, 1, 100 ) ), transition );
			}

			if( to.colorTemperature() != null && from.supportsColorTemperature() ) {
				Thread.sleep( PATCH_SPACING_MS );
				final int kelvin = temp0 == null ? to.colorTemperature() : (int)Math.round( temp0 + ( to.colorTemperature() - temp0 ) * f );
				Hub.setAttributes( deviceId, Map.of( "colorTemperature", kelvin ), transition );
			}

			if( to.colorHue() != null && to.colorSaturation() != null && from.supportsColor() ) {
				Thread.sleep( PATCH_SPACING_MS );
				final double hue = hue0 == null ? to.colorHue() : hue0 + shortestArc( hue0, to.colorHue() ) * f;
				final double sat = sat0 == null ? to.colorSaturation() : sat0 + ( to.colorSaturation() - sat0 ) * f;
				Hub.setAttributes( deviceId, Map.of( "colorHue", ( ( hue % 360 ) + 360 ) % 360, "colorSaturation", Math.clamp( sat, 0d, 1d ) ), transition );
			}
		}
	}

	private static double shortestArc( final double fromHue, final double toHue ) {
		double delta = ( ( toHue - fromHue ) % 360 + 540 ) % 360 - 180;
		return delta;
	}

	private static Device withLevel( final Device device, final int level ) {
		final Device.Attributes a = device.attributes();
		return new Device( device.id(), device.type(), device.deviceType(), device.isReachable(), device.isHidden(),
				new Device.Attributes( a.customName(), a.model(), true, level, a.colorMode(), a.colorTemperature(), a.colorTemperatureMin(), a.colorTemperatureMax(), a.colorHue(), a.colorSaturation() ),
				device.room(), device.capabilities() );
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
