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

		for( final Device light : Hub.lights() ) {
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

		final Scene scene = new Scene( name, slugify( name ), states );

		final List<Scene> scenes = all();
		scenes.removeIf( existing -> existing.slug().equals( scene.slug() ) );
		scenes.add( scene );
		save();

		return scene;
	}

	public static void delete( final String slug ) {
		all().removeIf( scene -> scene.slug().equals( slug ) );
		save();
	}

	/**
	 * Applies a scene: each light is sent its stored state. isOn and lightLevel are
	 * sent as separate patches — the hub is picky about combined attribute updates.
	 */
	public static void apply( final String slug ) {
		final Scene scene = bySlug( slug );

		if( scene == null ) {
			throw new IllegalArgumentException( "No scene with slug " + slug );
		}

		for( final Map.Entry<String, Scene.LightState> entry : scene.states().entrySet() ) {
			final String deviceId = entry.getKey();
			final Scene.LightState state = entry.getValue();

			if( Boolean.FALSE.equals( state.isOn() ) ) {
				Hub.setAttributes( deviceId, Map.of( "isOn", false ) );
				continue;
			}

			Hub.setAttributes( deviceId, Map.of( "isOn", true ) );

			if( state.lightLevel() != null ) {
				Hub.setAttributes( deviceId, Map.of( "lightLevel", state.lightLevel() ) );
			}

			if( state.colorTemperature() != null ) {
				Hub.setAttributes( deviceId, Map.of( "colorTemperature", state.colorTemperature() ) );
			}

			if( state.colorHue() != null && state.colorSaturation() != null ) {
				Hub.setAttributes( deviceId, Map.of( "colorHue", state.colorHue(), "colorSaturation", state.colorSaturation() ) );
			}
		}
	}

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
