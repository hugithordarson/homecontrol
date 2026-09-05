package home;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.webobjects.appserver.WOApplication;

/**
 * The palette of preset chips: the built-in set from woresources/palette.json
 * plus the user's own chips, persisted in ~/.homecontrol/palette.json so every
 * tablet sees them. A preset is a single color (hex), a white temperature
 * (kelvin), or a multi-color set (hexes) dealt out across the target lights.
 */

public class Palette {

	private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	public record Preset( String name, String hex, Integer kelvin, List<String> hexes, boolean custom ) {

		public boolean multi() {
			return hexes != null && !hexes.isEmpty();
		}
	}

	record PaletteFile( List<Preset> presets ) {}

	private static List<Preset> _builtIn;
	private static List<Preset> _custom;

	public static List<Preset> all() {
		final List<Preset> all = new ArrayList<>( builtIn() );
		all.addAll( custom() );
		return all;
	}

	public static void add( final String name, final List<String> hexes ) {
		final List<Preset> updated = new ArrayList<>( custom() );
		updated.removeIf( preset -> preset.name().equals( name ) );
		updated.add( new Preset( name, hexes.size() == 1 ? hexes.get( 0 ) : null, null, hexes.size() > 1 ? hexes : null, true ) );
		saveCustom( updated );
	}

	public static void remove( final String name ) {
		final List<Preset> updated = new ArrayList<>( custom() );
		updated.removeIf( preset -> preset.name().equals( name ) );
		saveCustom( updated );
	}

	private static List<Preset> builtIn() {
		if( _builtIn == null ) {
			final byte[] bytes = WOApplication.application().resourceManager().bytesForResourceNamed( "palette.json", null, null );
			_builtIn = gson.fromJson( new String( bytes, StandardCharsets.UTF_8 ), PaletteFile.class ).presets()
					.stream()
					.map( p -> new Preset( p.name(), p.hex(), p.kelvin(), p.hexes(), false ) )
					.toList();
		}

		return _builtIn;
	}

	private static List<Preset> custom() {
		if( _custom == null ) {
			_custom = new ArrayList<>();

			if( Files.exists( customFile() ) ) {
				try {
					final PaletteFile file = gson.fromJson( Files.readString( customFile() ), PaletteFile.class );

					if( file != null && file.presets() != null ) {
						file.presets().forEach( p -> _custom.add( new Preset( p.name(), p.hex(), p.kelvin(), p.hexes(), true ) ) );
					}
				}
				catch( final IOException e ) {
					throw new UncheckedIOException( "Could not read " + customFile(), e );
				}
			}
		}

		return _custom;
	}

	private static void saveCustom( final List<Preset> presets ) {
		try {
			Files.createDirectories( Hub.configDirectory() );
			Files.writeString( customFile(), gson.toJson( new PaletteFile( presets ) ) );
		}
		catch( final IOException e ) {
			throw new UncheckedIOException( "Could not write " + customFile(), e );
		}

		_custom = null;
	}

	private static Path customFile() {
		return Hub.configDirectory().resolve( "palette.json" );
	}
}
