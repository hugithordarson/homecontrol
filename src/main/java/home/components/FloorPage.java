package home.components;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.webobjects.appserver.WOContext;

import app.HomeComponent;
import home.Hub;
import home.dirigera.Device;

/**
 * The apartment floor plan as a control surface: a schematic SVG traced from the
 * architect's drawing (regions defined in woresources/floorplan.json, mm
 * coordinates), each region filled with its hub room's live light state.
 * Clicking a mapped region opens a control panel wired to the /api routes.
 */

public class FloorPage extends HomeComponent {

	private static final Gson gson = new Gson();

	public record Region( String id, String name, String hubRoom, List<List<Double>> polygon, List<Double> label ) {}

	record FloorPlan( List<Region> regions ) {}

	public Region currentRegion;
	public Device currentLight;

	private FloorPlan _plan;
	private Map<String, Hub.RoomGroup> _groupsByName;

	public FloorPage( WOContext context ) {
		super( context );
	}

	public List<Region> regions() {
		if( _plan == null ) {
			final byte[] bytes = application().resourceManager().bytesForResourceNamed( "floorplan.json", null, null );
			_plan = gson.fromJson( new String( bytes, StandardCharsets.UTF_8 ), FloorPlan.class );
		}

		return _plan.regions();
	}

	private Map<String, Hub.RoomGroup> groupsByName() {
		if( _groupsByName == null ) {
			_groupsByName = new HashMap<>();

			if( Hub.configured() ) {
				for( final Hub.RoomGroup group : Hub.roomGroups() ) {
					_groupsByName.put( group.name(), group );
				}
			}
		}

		return _groupsByName;
	}

	private Hub.RoomGroup currentGroup() {
		return currentRegion.hubRoom() != null ? groupsByName().get( currentRegion.hubRoom() ) : null;
	}

	public String currentPoints() {
		final StringBuilder sb = new StringBuilder();

		for( final List<Double> point : currentRegion.polygon() ) {
			if( sb.length() > 0 ) {
				sb.append( ' ' );
			}

			sb.append( point.get( 0 ).intValue() ).append( ',' ).append( point.get( 1 ).intValue() );
		}

		return sb.toString();
	}

	public String currentCss() {
		final Hub.RoomGroup group = currentGroup();

		if( group == null ) {
			return "region unmapped";
		}

		return group.anyOn() ? "region mapped on" : "region mapped";
	}

	/**
	 * @return The region's fill: dark when off/unmapped, the actual bulb color when
	 * a color bulb is lit, a warm glow for lit white-spectrum rooms
	 */
	public String currentFill() {
		final Hub.RoomGroup group = currentGroup();

		if( group == null ) {
			return "#171a1f";
		}

		if( !group.anyOn() ) {
			return "#20242c";
		}

		for( final Device light : group.lights() ) {
			if( light.on() && light.inColorMode() && light.attributes().colorHue() != null && light.attributes().colorSaturation() != null ) {
				final int rgb = java.awt.Color.HSBtoRGB(
						(float)(light.attributes().colorHue() / 360d),
						(float)Math.clamp( light.attributes().colorSaturation(), 0d, 1d ),
						0.75f );
				return "#%06x".formatted( rgb & 0xffffff );
			}
		}

		return "#6b5327";
	}

	public String currentLabelX() {
		return String.valueOf( currentRegion.label().get( 0 ).intValue() );
	}

	public String currentLabelY() {
		return String.valueOf( currentRegion.label().get( 1 ).intValue() );
	}

	public String currentRoomId() {
		final Hub.RoomGroup group = currentGroup();
		return group != null ? group.id() : "";
	}

	/**
	 * @return The selected region's lights, for the panel's list (empty for unmapped regions)
	 */
	public List<Device> currentRegionLights() {
		final Hub.RoomGroup group = currentGroup();
		return group != null ? group.lights() : List.of();
	}

	public String currentLightRowClass() {
		return "light" + ( currentLight.on() ? " on" : "" ) + ( currentLight.reachable() ? "" : " unreachable" );
	}

	public String currentLightBulbStyle() {
		if( !currentLight.on() || !currentLight.inColorMode() || currentLight.attributes().colorHue() == null ) {
			return "";
		}

		final int rgb = java.awt.Color.HSBtoRGB(
				(float)(currentLight.attributes().colorHue() / 360d),
				(float)Math.clamp( currentLight.attributes().colorSaturation() == null ? 1d : currentLight.attributes().colorSaturation(), 0d, 1d ),
				1f );
		return "color: #%06x".formatted( rgb & 0xffffff );
	}

	public String currentLightToggleJS() {
		return "hc.toggle('%s')".formatted( currentLight.id() );
	}

	public String currentLightMeta() {
		if( !currentLight.reachable() ) {
			return "unreachable";
		}

		if( !currentLight.on() ) {
			return "off";
		}

		final StringBuilder sb = new StringBuilder();
		final Integer level = currentLight.attributes() != null ? currentLight.attributes().lightLevel() : null;

		if( currentLight.dimmable() && level != null ) {
			sb.append( level ).append( '%' );
		}

		if( currentLight.inColorMode() ) {
			sb.append( " · color" );
		}
		else if( currentLight.supportsColorTemperature() && currentLight.attributes().colorTemperature() != null ) {
			sb.append( " · " ).append( currentLight.attributes().colorTemperature() ).append( " K" );
		}

		return sb.toString();
	}

	public String currentHasColor() {
		final Hub.RoomGroup group = currentGroup();
		return group != null && group.lights().stream().anyMatch( Device::supportsColor ) ? "true" : "false";
	}
}
