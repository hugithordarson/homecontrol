package home.components;

import java.util.List;

import com.webobjects.appserver.WOContext;

import app.HomeComponent;
import home.Hub;
import home.Scene;
import home.Scenes;
import home.dirigera.Device;

/**
 * The dashboard: every light in the home grouped by room, with group switches
 * and scene buttons on top. All controls go through the /api/ routes via the
 * little hc javascript object in PageWrapper.
 */

public class FrontPage extends HomeComponent {

	public Hub.RoomGroup currentRoom;
	public Device currentLight;
	public Scene currentScene;
	public String currentWarning;

	private List<Hub.RoomGroup> _rooms;
	private String _hubError;
	private boolean _loaded;

	public FrontPage( WOContext context ) {
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

	/**
	 * @return Per-hub fetch failures — e.g. the office hub being unreachable from home.
	 * The dashboard still renders whatever the reachable hubs returned.
	 */
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

	public List<Scene> scenes() {
		return Scenes.all();
	}

	public boolean hasScenes() {
		return !scenes().isEmpty();
	}

	public int currentLightLevel() {
		final Integer level = currentLight.attributes() != null ? currentLight.attributes().lightLevel() : null;
		return level != null ? level : 100;
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

	public String currentLightToggleJS() {
		return "hc.toggle('%s')".formatted( currentLight.id() );
	}

	public String currentLightLevelJS() {
		return "hc.level('%s', this.value)".formatted( currentLight.id() );
	}

	/**
	 * @return The bulb's current color as a hex value for the color input, rendered
	 * at full brightness (actual brightness is the lightLevel slider's business)
	 */
	public String currentLightColorHex() {
		final Device.Attributes attributes = currentLight.attributes();
		final double hue = attributes != null && attributes.colorHue() != null ? attributes.colorHue() : 0;
		final double saturation = attributes != null && attributes.colorSaturation() != null ? attributes.colorSaturation() : 0;
		final int rgb = java.awt.Color.HSBtoRGB( (float)(hue / 360d), (float)saturation, 1f );
		return "#%06x".formatted( rgb & 0xffffff );
	}

	public String currentLightColorJS() {
		return "hc.color('%s', this.value)".formatted( currentLight.id() );
	}

	public String currentLightBoltJS() {
		return "hc.strike('/api/effect/lightning?device=%s')".formatted( currentLight.id() );
	}

	/**
	 * @return true for real hub rooms — the synthetic "No room" group can't be renamed
	 */
	public boolean currentRoomRenamable() {
		return !"none".equals( currentRoom.id() );
	}

	public String currentRoomRenameJS() {
		final String escapedName = currentRoom.name().replace( "\\", "\\\\" ).replace( "'", "\\'" );
		return "hc.renameRoom('%s', '%s')".formatted( currentRoom.id(), escapedName );
	}

	public String currentRoomBoltJS() {
		return "hc.strike('/api/effect/lightning?room=%s')".formatted( currentRoom.id() );
	}

	public String currentRoomOnJS() {
		return "hc.room('%s', 'on')".formatted( currentRoom.id() );
	}

	public String currentRoomOffJS() {
		return "hc.room('%s', 'off')".formatted( currentRoom.id() );
	}

	public String currentSceneApplyJS() {
		return "hc.scene('%s')".formatted( currentScene.slug() );
	}
}
