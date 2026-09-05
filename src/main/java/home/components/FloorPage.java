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

	public record Region( String id, String name, String hubRoom, List<List<Double>> polygon, List<Double> label, Integer labelSize ) {}

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
	 * @return The region's fill: dark when off/unmapped, otherwise the color the room's
	 * lit bulbs are actually showing — real hue for color mode, the blackbody color of
	 * the Kelvin value for white mode — dimmed with the bulb's brightness
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
			if( !light.on() || light.attributes() == null ) {
				continue;
			}

			final Device.Attributes a = light.attributes();
			final Integer level = a.lightLevel();
			final float brightness = 0.45f + 0.4f * ( level != null ? level : 100 ) / 100f;

			if( light.inColorMode() && a.colorHue() != null && a.colorSaturation() != null ) {
				return hex( a.colorHue(), Math.clamp( a.colorSaturation(), 0d, 1d ), brightness );
			}

			if( a.colorTemperature() != null ) {
				final double[] hs = home.dirigera.ColorMath.kelvinToHueSat( a.colorTemperature() );
				return hex( hs[0], hs[1], brightness );
			}
		}

		return "#6b5327";
	}

	private static String hex( final double hue, final double saturation, final float brightness ) {
		final int rgb = java.awt.Color.HSBtoRGB( (float)(hue / 360d), (float)saturation, brightness );
		return "#%06x".formatted( rgb & 0xffffff );
	}

	public String currentLabelX() {
		return String.valueOf( currentRegion.label().get( 0 ).intValue() );
	}

	public String currentLabelY() {
		return String.valueOf( currentRegion.label().get( 1 ).intValue() );
	}

	/**
	 * @return An inline font-size for regions too narrow for the default label size
	 */
	public String currentLabelStyle() {
		return currentRegion.labelSize() != null ? "font-size: " + currentRegion.labelSize() + "px" : "";
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
		return "event.stopPropagation(); hc.toggle('%s')".formatted( currentLight.id() );
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

	public String currentRoomCyclable() {
		final Hub.RoomGroup group = currentGroup();
		return group != null && group.lights().stream().filter( Device::supportsColor ).count() >= 2 ? "true" : "false";
	}

	public String currentRoomRenamable() {
		final Hub.RoomGroup group = currentGroup();
		return group != null && !"none".equals( group.id() ) ? "true" : "false";
	}

	public int currentLightLevel() {
		final Integer level = currentLight.attributes() != null ? currentLight.attributes().lightLevel() : null;
		return level != null ? level : 100;
	}

	public int currentLightTemperature() {
		final Integer kelvin = currentLight.attributes() != null ? currentLight.attributes().colorTemperature() : null;
		return kelvin != null ? kelvin : 2700;
	}

	public int currentLightWarmest() {
		return currentLight.warmestTemperature();
	}

	public int currentLightColdest() {
		return currentLight.coldestTemperature();
	}

	public String currentLightColorHex() {
		final Device.Attributes attributes = currentLight.attributes();
		final double hue = attributes != null && attributes.colorHue() != null ? attributes.colorHue() : 0;
		final double saturation = attributes != null && attributes.colorSaturation() != null ? Math.clamp( attributes.colorSaturation(), 0d, 1d ) : 0;
		final int rgb = java.awt.Color.HSBtoRGB( (float)(hue / 360d), (float)saturation, 1f );
		return "#%06x".formatted( rgb & 0xffffff );
	}

	public String currentHasColor() {
		final Hub.RoomGroup group = currentGroup();
		return group != null && group.lights().stream().anyMatch( Device::supportsColor ) ? "true" : "false";
	}
}
