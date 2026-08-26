package home;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import home.dirigera.Device;
import home.dirigera.DirigeraClient;

/**
 * The app's connection to the DIRIGERA hubs (yes, plural — e.g. home and office).
 * A static facade so the rest of the app doesn't care how many hubs there are:
 * device and room operations are routed to the owning hub via maps maintained on
 * every full device fetch. Pairings persist in ~/.homecontrol/config.json.
 */

public class Hub {

	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger( Hub.class );

	private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	private static List<HubConfig> _hubs;
	private static final Map<String, DirigeraClient> _clients = new ConcurrentHashMap<>();

	/** deviceId/roomId → owning hub's address, refreshed on every full fetch */
	private static final Map<String, String> _deviceHub = new ConcurrentHashMap<>();
	private static final Map<String, String> _roomHub = new ConcurrentHashMap<>();

	/** Human-readable failures from the last lights() fetch — an unreachable hub is a warning, not a dead dashboard */
	private static final List<String> _fetchErrors = new CopyOnWriteArrayList<>();

	/**
	 * Circuit breaker: hubs that failed a fetch land here (address → earliest re-probe
	 * time) and are skipped during renders, so an absent hub (e.g. the home hub, seen
	 * from the office) costs its connect-timeout once — not on every request. A
	 * background probe readmits the hub when it answers again.
	 */
	private static final Map<String, Long> _downHubs = new ConcurrentHashMap<>();
	private static final java.util.Set<String> _probing = ConcurrentHashMap.newKeySet();
	private static final long HUB_RETRY_MS = 30_000;

	public record HubConfig( String name, String address, String token ) {}

	/** The on-disk shape. Old single-hub config files ({hubAddress, token}) migrate on read. */
	private record ConfigFile( List<HubConfig> hubs ) {}

	private record LegacyConfig( String hubAddress, String token ) {}

	public record RoomGroup( String id, String name, String hubName, List<Device> lights ) {

		public boolean anyOn() {
			return lights.stream().anyMatch( Device::on );
		}
	}

	public static boolean configured() {
		return !hubs().isEmpty();
	}

	public static List<HubConfig> hubs() {
		if( _hubs == null ) {
			_hubs = loadHubs();
		}

		return _hubs;
	}

	/**
	 * Adds a pairing (or replaces an existing pairing with the same address)
	 */
	public static void addHub( final String name, final String address, final String token ) {
		final List<HubConfig> updated = new ArrayList<>( hubs() );
		updated.removeIf( hub -> hub.address().equals( address ) );
		updated.add( new HubConfig( name == null || name.isBlank() ? address : name.trim(), address, token ) );
		writeConfig( updated );
	}

	public static void removeHub( final String address ) {
		final List<HubConfig> updated = new ArrayList<>( hubs() );
		updated.removeIf( hub -> hub.address().equals( address ) );
		writeConfig( updated );
	}

	/**
	 * @return All non-hidden lights across every hub, ordered by room name, then light
	 * name. Also refreshes the device/room → hub routing maps. An unreachable hub
	 * contributes a fetchErrors() entry instead of an exception.
	 */
	public static List<Device> lights() {
		final java.util.Queue<Device> collected = new java.util.concurrent.ConcurrentLinkedQueue<>();
		_fetchErrors.clear();

		try( final var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor() ) {
			for( final HubConfig hub : hubs() ) {
				if( _downHubs.containsKey( hub.address() ) ) {
					_fetchErrors.add( "%s: unreachable, retrying in the background".formatted( hub.name() ) );
					probeDownHub( hub );
					continue;
				}

				executor.submit( () -> {
					try {
						for( final Device device : client( hub ).devices() ) {
							_deviceHub.put( device.id(), hub.address() );

							if( device.room() != null ) {
								_roomHub.put( device.room().id(), hub.address() );
							}

							if( device.isLight() && !device.hidden() ) {
								collected.add( device );
							}
						}
					}
					catch( final Exception e ) {
						logger.warn( "Fetch from hub '{}' ({}) failed: {}", hub.name(), hub.address(), e.toString() );
						_downHubs.put( hub.address(), System.currentTimeMillis() + HUB_RETRY_MS );
						_fetchErrors.add( "%s: %s".formatted( hub.name(), e.getMessage() ) );
					}
				} );
			}
		}

		final List<Device> all = new ArrayList<>( collected );
		all.sort( Comparator.comparing( Device::roomName ).thenComparing( Device::name ) );
		_lightsSnapshot = all;
		_lightsSnapshotTime = System.currentTimeMillis();
		return all;
	}

	/**
	 * Kicks off a background reachability check for a down hub, at most once per
	 * cooldown period — renders never block on it
	 */
	private static void probeDownHub( final HubConfig hub ) {
		final Long retryAt = _downHubs.get( hub.address() );

		if( retryAt == null || System.currentTimeMillis() < retryAt || !_probing.add( hub.address() ) ) {
			return;
		}

		Thread.ofVirtual().name( "hub-probe" ).start( () -> {
			try {
				client( hub ).devices();
				logger.info( "Hub '{}' is reachable again", hub.name() );
				_downHubs.remove( hub.address() );
			}
			catch( final Exception e ) {
				logger.warn( "Probe of hub '{}' ({}) failed: {}", hub.name(), hub.address(), e.toString() );
				_downHubs.put( hub.address(), System.currentTimeMillis() + HUB_RETRY_MS );
			}
			finally {
				_probing.remove( hub.address() );
			}
		} );
	}

	public static List<String> fetchErrors() {
		return List.copyOf( _fetchErrors );
	}

	private static List<Device> _lightsSnapshot;
	private static long _lightsSnapshotTime;

	/**
	 * @return A recent snapshot of lights() — effects use this so a strike doesn't pay
	 * for a full device fetch when the dashboard fetched everything moments earlier
	 */
	public static List<Device> lightsSnapshot( final long maxAgeMs ) {
		if( _lightsSnapshot == null || System.currentTimeMillis() - _lightsSnapshotTime > maxAgeMs ) {
			lights();
		}

		return _lightsSnapshot;
	}

	/**
	 * @return The lights grouped by room, in room name order
	 */
	public static List<RoomGroup> roomGroups() {
		final Map<String, RoomGroup> groups = new LinkedHashMap<>();

		for( final Device light : lights() ) {
			groups.computeIfAbsent( light.roomId(), _ -> new RoomGroup( light.roomId(), light.roomName(), hubNameForDevice( light.id() ), new ArrayList<>() ) )
					.lights()
					.add( light );
		}

		return List.copyOf( groups.values() );
	}

	/**
	 * Brightness owed to lights an effect left off at minimum level: bulbs ignore level
	 * patches while off, so the remembered level is repaired next time we turn them on.
	 */
	private static final Map<String, Integer> _pendingLevelRestore = new ConcurrentHashMap<>();

	public static void registerPendingLevelRestore( final String deviceId, final int level ) {
		_pendingLevelRestore.put( deviceId, level );
	}

	public static void setAttributes( final String deviceId, final Map<String, Object> attributes ) {
		clientForDevice( deviceId ).setAttributes( deviceId, attributes );
		repairLevelIfPending( deviceId, attributes );
	}

	public static void setAttributes( final String deviceId, final Map<String, Object> attributes, final Integer transitionTimeMs ) {
		clientForDevice( deviceId ).setAttributes( deviceId, attributes, transitionTimeMs );
		repairLevelIfPending( deviceId, attributes );
	}

	private static void repairLevelIfPending( final String deviceId, final Map<String, Object> attributes ) {
		if( Boolean.TRUE.equals( attributes.get( "isOn" ) ) ) {
			final Integer level = _pendingLevelRestore.remove( deviceId );

			if( level != null ) {
				try {
					// startUpCurrentLevel keeps the staged value: the hub rejects writing
					// the "previous level" sentinel (-1/255 both 400), so deterministic
					// power-cycle brightness is the best available end state
					clientForDevice( deviceId ).setAttributes( deviceId, Map.of( "lightLevel", level ) );
				}
				catch( final Exception e ) {
					// The repair is best-effort; don't break the turn-on it piggybacks on
				}
			}
		}
	}

	public static void turnAll( final boolean on ) {
		for( final Device light : lights() ) {
			if( light.reachable() && light.on() != on ) {
				setAttributes( light.id(), Map.of( "isOn", on ) );
			}
		}
	}

	public static void turnRoom( final String roomId, final boolean on ) {
		for( final Device light : lights() ) {
			if( light.roomId().equals( roomId ) && light.reachable() && light.on() != on ) {
				setAttributes( light.id(), Map.of( "isOn", on ) );
			}
		}
	}

	public static void toggle( final String deviceId ) {
		final Device device = lights()
				.stream()
				.filter( d -> d.id().equals( deviceId ) )
				.findFirst()
				.orElseThrow( () -> new IllegalArgumentException( "No device with id " + deviceId ) );

		setAttributes( deviceId, Map.of( "isOn", !device.on() ) );
	}

	public static void setLevel( final String deviceId, final int level ) {
		setAttributes( deviceId, Map.of( "lightLevel", clamp( level, 1, 100 ) ) );
	}

	public static void setTemperature( final String deviceId, final int kelvin ) {
		setAttributes( deviceId, Map.of( "colorTemperature", kelvin ) );
	}

	public static void setColor( final String deviceId, final double hue, final double saturation ) {
		setAttributes( deviceId, Map.of(
				"colorHue", clamp( hue, 0, 360 ),
				"colorSaturation", clamp( saturation, 0, 1 ) ) );
	}

	public static void renameRoom( final String roomId, final String name ) {
		String address = _roomHub.get( roomId );

		if( address == null ) {
			lights();
			address = _roomHub.get( roomId );
		}

		if( address == null ) {
			throw new IllegalArgumentException( "No hub knows room " + roomId );
		}

		clientByAddress( address ).renameRoom( roomId, name );
	}

	/**
	 * @return The address of the hub this device belongs to, if we've seen it in a fetch
	 */
	public static String hubAddressForDevice( final String deviceId ) {
		return _deviceHub.get( deviceId );
	}

	/**
	 * Opens a few connections to each hub the given lights live on, fire-and-forget.
	 * An effect calls this just before its schedule starts so the flash commands find
	 * warm TLS connections instead of paying the hub's slow handshake mid-storm.
	 */
	public static void warmHubsFor( final List<Device> lights ) {
		lights.stream()
				.map( light -> _deviceHub.get( light.id() ) )
				.filter( address -> address != null )
				.distinct()
				.forEach( address -> {
					for( int i = 0; i < 3; i++ ) {
						Thread.ofVirtual().name( "hub-warmup" ).start( () -> {
							try {
								clientByAddress( address ).ping();
							}
							catch( final Exception e ) {
								// Warming is best-effort
							}
						} );
					}
				} );
	}

	public static boolean hubDown( final String address ) {
		return _downHubs.containsKey( address );
	}

	private static DirigeraClient clientForDevice( final String deviceId ) {
		String address = _deviceHub.get( deviceId );

		if( address == null ) {
			lights();
			address = _deviceHub.get( deviceId );
		}

		if( address == null ) {
			throw new IllegalArgumentException( "No hub knows device " + deviceId );
		}

		return clientByAddress( address );
	}

	private static DirigeraClient client( final HubConfig hub ) {
		return _clients.computeIfAbsent( hub.address(), _ -> new DirigeraClient( hub.address(), hub.token() ) );
	}

	private static DirigeraClient clientByAddress( final String address ) {
		return hubs()
				.stream()
				.filter( hub -> hub.address().equals( address ) )
				.findFirst()
				.map( Hub::client )
				.orElseThrow( () -> new IllegalStateException( "No configured hub with address " + address ) );
	}

	private static String hubNameForDevice( final String deviceId ) {
		final String address = _deviceHub.get( deviceId );

		return hubs()
				.stream()
				.filter( hub -> hub.address().equals( address ) )
				.findFirst()
				.map( HubConfig::name )
				.orElse( "?" );
	}

	private static int clamp( final int value, final int min, final int max ) {
		return Math.max( min, Math.min( max, value ) );
	}

	private static double clamp( final double value, final double min, final double max ) {
		return Math.max( min, Math.min( max, value ) );
	}

	private static List<HubConfig> loadHubs() {
		if( !Files.exists( configFile() ) ) {
			return List.of();
		}

		try {
			final String json = Files.readString( configFile() );
			final ConfigFile parsed = gson.fromJson( json, ConfigFile.class );

			if( parsed != null && parsed.hubs() != null ) {
				return parsed.hubs();
			}

			// Old single-hub format — migrate in memory; the file converts on the next write
			final LegacyConfig legacy = gson.fromJson( json, LegacyConfig.class );

			if( legacy != null && legacy.hubAddress() != null ) {
				return List.of( new HubConfig( "Home", legacy.hubAddress(), legacy.token() ) );
			}

			return List.of();
		}
		catch( final IOException e ) {
			throw new UncheckedIOException( "Could not read " + configFile(), e );
		}
	}

	private static void writeConfig( final List<HubConfig> hubs ) {
		try {
			Files.createDirectories( configDirectory() );
			Files.writeString( configFile(), gson.toJson( new ConfigFile( hubs ) ) );
		}
		catch( final IOException e ) {
			throw new UncheckedIOException( "Could not write " + configFile(), e );
		}

		_hubs = null;
		_clients.clear();
		_downHubs.clear();
	}

	static Path configDirectory() {
		return Path.of( System.getProperty( "user.home" ), ".homecontrol" );
	}

	private static Path configFile() {
		return configDirectory().resolve( "config.json" );
	}
}
