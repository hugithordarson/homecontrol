package home.components;

import java.util.List;

import ng.appserver.NGContext;

import app.HomeComponent;
import home.Palette;

/**
 * The selection control panel shared by the dashboard and the floor plan: acts on
 * whatever lights are selected (see the hc.sel machinery in PageWrapper) through
 * /api/batch requests, and carries the palette of preset chips — tap to apply to
 * the selection, drag onto any light or room. Multi-color chips deal their colors
 * out across the target lights; the Mix row composes and saves new ones.
 */

public class SelectionPanel extends HomeComponent {

	public Palette.Preset currentPreset;

	public SelectionPanel( NGContext context ) {
		super( context );
	}

	@Override
	public boolean synchronizesVariablesWithBindings() {
		return false;
	}

	public List<Palette.Preset> presets() {
		return Palette.all();
	}

	public String currentPresetClass() {
		return "chip" + ( currentPreset.multi() ? " multi" : "" );
	}

	public String currentPresetStyle() {
		if( currentPreset.multi() ) {
			final StringBuilder stops = new StringBuilder();
			final int n = currentPreset.hexes().size();

			for( int i = 0; i < n; i++ ) {
				if( i > 0 ) {
					stops.append( ", " );
				}

				stops.append( currentPreset.hexes().get( i ) ).append( ' ' ).append( 100 * i / n ).append( "% " ).append( 100 * ( i + 1 ) / n ).append( '%' );
			}

			return "background: conic-gradient(" + stops + ")";
		}

		return "background: " + ( currentPreset.hex() != null ? currentPreset.hex() : kelvinHex( currentPreset.kelvin() ) );
	}

	/**
	 * @return What the chip carries: a hex color, "K:2700" for a white temperature, or "M:#a,#b,#c" for a multi-color set
	 */
	public String currentPresetData() {
		if( currentPreset.multi() ) {
			return "M:" + String.join( ",", currentPreset.hexes() );
		}

		return currentPreset.hex() != null ? currentPreset.hex() : "K:" + currentPreset.kelvin();
	}

	public boolean currentPresetCustom() {
		return currentPreset.custom();
	}

	public String currentPresetDeleteJS() {
		return "event.stopPropagation(); hc.deletePreset('%s')".formatted( currentPreset.name().replace( "'", "\\'" ) );
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
