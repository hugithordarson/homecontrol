package home.components;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.google.gson.Gson;
import com.webobjects.appserver.WOContext;

import app.HomeComponent;

/**
 * The selection control panel shared by the dashboard and the floor plan: acts on
 * whatever lights are selected (see the hc.sel machinery in PageWrapper) through
 * one /api/batch request, and carries the palette of preset chips — tap to apply
 * to the selection, drag onto any light or room.
 */

public class SelectionPanel extends HomeComponent {

	private static final Gson gson = new Gson();

	public record Preset( String name, String hex, Integer kelvin ) {}

	record Palette( List<Preset> presets ) {}

	public Preset currentPreset;

	private List<Preset> _presets;

	public SelectionPanel( WOContext context ) {
		super( context );
	}

	@Override
	public boolean synchronizesVariablesWithBindings() {
		return false;
	}

	public List<Preset> presets() {
		if( _presets == null ) {
			final byte[] bytes = application().resourceManager().bytesForResourceNamed( "palette.json", null, null );
			_presets = gson.fromJson( new String( bytes, StandardCharsets.UTF_8 ), Palette.class ).presets();
		}

		return _presets;
	}

	public String currentPresetStyle() {
		return "background: " + ( currentPreset.hex() != null ? currentPreset.hex() : kelvinHex( currentPreset.kelvin() ) );
	}

	/**
	 * @return What the chip carries: a hex color, or "K:2700" for a white temperature
	 */
	public String currentPresetData() {
		return currentPreset.hex() != null ? currentPreset.hex() : "K:" + currentPreset.kelvin();
	}

	private static String kelvinHex( final int kelvin ) {
		if( kelvin <= 2300 ) {
			return "#ff9d45";
		}

		if( kelvin <= 2800 ) {
			return "#ffc27a";
		}

		if( kelvin <= 3400 ) {
			return "#ffd9ad";
		}

		return "#fdf3e0";
	}
}
