package home.components;

import java.util.List;

import ng.appserver.NGContext;

import app.HomeComponent;
import home.Hub;
import home.Scene;
import home.Scenes;
import home.dirigera.Device;

/**
 * The dashboard: every light grouped by room, as a selection surface. Tapping a
 * light (or a room name, for all its lights) selects it; the panel at the bottom
 * controls the whole selection through one /api/batch request. Palette chips can
 * be tapped (apply to selection) or dragged onto any light or room.
 */

public class FrontPage extends HomeComponent {

	public Hub.RoomGroup currentRoom;
	public Device currentLight;
	public Scene currentScene;
	public String currentWarning;

	private List<Hub.RoomGroup> _rooms;
	private String _hubError;
	private boolean _loaded;

	public FrontPage( NGContext context ) {
		super( context );
	}

	public boolean configured() {
		return Hub.configured();
	}

	public boolean notConfigured() {
		return !configured();
	}

	public List<Hub.RoomGroup> rooms() {
		if( !_loaded ) {
			_loaded = true;

			try {
				_rooms = Hub.roomGroups();
			}
			catch( final Exception e ) {
				_hubError = e.getMessage();
				_rooms = List.of();
			}
		}

		return _rooms;
	}

	public String hubError() {
		rooms();
		return _hubError;
	}

	public boolean hasHubError() {
		return configured() && hubError() != null;
	}

	public List<String> fetchWarnings() {
		rooms();
		return Hub.fetchErrors();
	}

	public boolean hasFetchWarnings() {
		return configured() && !fetchWarnings().isEmpty();
	}

	public boolean multipleHubs() {
		return Hub.hubs().size() > 1;
	}

	// Scenes

	public List<Scene> scenes() {
		return Scenes.all();
	}

	public boolean hasScenes() {
		return !scenes().isEmpty();
	}

	public String currentSceneApplyJS() {
		return "hc.scene('%s')".formatted( currentScene.slug() );
	}

	public boolean currentSceneAway() {
		final List<String> hubs = currentScene.hubs();

		if( hubs == null || hubs.isEmpty() ) {
			return false;
		}

		return hubs.stream().allMatch( Hub::hubDown );
	}

	public String currentSceneClass() {
		return currentSceneAway() ? "scene away" : "scene";
	}

	public String currentSceneTitle() {
		return currentSceneAway() ? "This scene's lights are at the other location" : "";
	}

	// Rooms

	public String currentRoomOnJS() {
		return "hc.room('%s', 'on')".formatted( currentRoom.id() );
	}

	public String currentRoomOffJS() {
		return "hc.room('%s', 'off')".formatted( currentRoom.id() );
	}

	public String currentRoomRenamable() {
		return "none".equals( currentRoom.id() ) ? "false" : "true";
	}

	public String currentRoomCyclable() {
		return currentRoom.lights().stream().filter( Device::supportsColor ).count() >= 2 ? "true" : "false";
	}

	// Lights

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

	public String currentLightClass() {
		final StringBuilder sb = new StringBuilder( "light" );

		if( currentLight.on() ) {
			sb.append( " on" );
		}

		if( !currentLight.reachable() ) {
			sb.append( " unreachable" );
		}

		return sb.toString();
	}

	/**
	 * @return The bulb's color as hex at full brightness (color bulbs), for the swatch and the bulb icon
	 */
	public String currentLightColorHex() {
		final Device.Attributes attributes = currentLight.attributes();
		final double hue = attributes != null && attributes.colorHue() != null ? attributes.colorHue() : 0;
		final double saturation = attributes != null && attributes.colorSaturation() != null ? Math.clamp( attributes.colorSaturation(), 0d, 1d ) : 0;
		final int rgb = java.awt.Color.HSBtoRGB( (float)(hue / 360d), (float)saturation, 1f );
		return "#%06x".formatted( rgb & 0xffffff );
	}

	/**
	 * @return The bulb icon tinted with the bulb's actual color when it's lit in color mode
	 */
	public String currentLightBulbStyle() {
		return currentLight.on() && currentLight.inColorMode() ? "color: " + currentLightColorHex() : "";
	}

	public String currentLightToggleJS() {
		return "event.stopPropagation(); hc.toggle('%s')".formatted( currentLight.id() );
	}

	/**
	 * @return A one-line state summary: brightness plus color or temperature
	 */
	public String currentLightMeta() {
		if( !currentLight.reachable() ) {
			return "unreachable";
		}

		if( !currentLight.on() ) {
			return "off";
		}

		final StringBuilder sb = new StringBuilder();

		if( currentLight.dimmable() ) {
			sb.append( currentLightLevel() ).append( '%' );
		}

		if( currentLight.inColorMode() ) {
			sb.append( " · color" );
		}
		else if( currentLight.supportsColorTemperature() ) {
			sb.append( " · " ).append( currentLightTemperature() ).append( " K" );
		}

		return sb.toString();
	}

}
