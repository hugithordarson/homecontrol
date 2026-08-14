package home;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import home.dirigera.Device;

/**
 * Fun light effects. Each effect snapshots the lights it touches and puts them
 * back afterwards, so pressing the button doesn't wreck the current mood.
 *
 * All strikes run on the same engine: a randomized flash schedule is built up
 * front and every command fires at its intended wall-clock time on its own
 * virtual thread, without waiting for the previous response — the hub's
 * ~100-300ms REST round-trip would otherwise stretch the rhythm. The final
 * scheduled event flashes straight back into the original state; a trailing
 * restore would fade, an awkward outro.
 */

public class Effects {

	private static final AtomicBoolean _effectRunning = new AtomicBoolean();

	/**
	 * The strike waiting its turn. Clicks during a running storm land here instead of
	 * being dropped — the latest click wins, and it fires as soon as the current storm
	 * (including its cleanup tail) finishes. Storms never overlap: two storms reading
	 * and restoring the same bulbs would trample each other's snapshots.
	 */
	private static final AtomicReference<Runnable> _queuedStrike = new AtomicReference<>();

	/**
	 * A lightning storm across many lights. Pass a roomId to contain it to one room,
	 * null strikes everything.
	 */
	public static void lightning( final String roomId ) {
		runOrQueue( "lightning", () -> {
			final List<Device> lights = Hub.lightsSnapshot( 3000 )
					.stream()
					.filter( Device::reachable )
					.filter( light -> roomId == null || roomId.equals( light.roomId() ) )
					.toList();

			strike( lights );
		} );
	}

	/**
	 * A single-bulb strobe in the spirit of the old Shelly/TRADFRI-CoAP effect.
	 */
	public static void lightningBolt( final String deviceId ) {
		runOrQueue( "lightning-bolt", () -> {
			final List<Device> light = Hub.lightsSnapshot( 3000 )
					.stream()
					.filter( l -> l.id().equals( deviceId ) )
					.filter( Device::reachable )
					.toList();

			strike( light );
		} );
	}

	private static void runOrQueue( final String name, final Runnable strike ) {
		_queuedStrike.set( strike );
		maybeStartWorker( name );
	}

	private static void maybeStartWorker( final String name ) {
		if( _effectRunning.compareAndSet( false, true ) ) {
			Thread.ofVirtual().name( name ).start( () -> {
				try {
					Runnable next;

					while( ( next = _queuedStrike.getAndSet( null ) ) != null ) {
						try {
							next.run();
						}
						catch( final Exception e ) {
							// A failed storm shouldn't block the queued one
						}
					}
				}
				finally {
					_effectRunning.set( false );

					// A click may have landed between the last queue check and the release
					if( _queuedStrike.get() != null ) {
						maybeStartWorker( name );
					}
				}
			} );
		}
	}

	private static void strike( final List<Device> lights ) {
		if( lights.isEmpty() ) {
			return;
		}

		final Random random = new Random();

		// The rhythm scales with group size: the hub's Zigbee radio transmits serially,
		// so each wave needs roughly 30ms of airtime per bulb before the next can land.
		// One bulb strobes at Shelly-ish tempo; a whole house gets honest pacing.
		final int step = Math.max( 40, lights.size() * 30 );
		final int flashes = lights.size() <= 6 ? 6 : 3;

		final boolean anyOn = lights.stream().anyMatch( Device::on );

		// Open connections now so the first flashes don't pay TLS handshakes;
		// the schedule's head start below gives the warmup time to win the race
		Hub.warmHubsFor( lights );

		try {
			try( final var executor = Executors.newVirtualThreadPerTaskExecutor() ) {
				final long start = System.currentTimeMillis();

				// Off bulbs wake at t=0, in parallel with the storm: switch on, then snap
				// to minimum the moment the on-command confirms — truncating the power-on
				// fade at its dimmest point, i.e. invisibly. (Pre-staging the level doesn't
				// work: bulbs ignore level patches while off.) Meanwhile previously-on
				// bulbs flash immediately; the woken ones join once safely dark.
				for( final Device light : lights ) {
					if( !light.on() ) {
						executor.submit( () -> {
							try {
								Hub.setAttributes( light.id(), Map.of( "isOn", true ) );

								if( light.dimmable() ) {
									Hub.setAttributes( light.id(), Map.of( "lightLevel", 1 ), 0 );
								}
							}
							catch( final Exception e ) {
								// It sits the storm out
							}
						} );
					}
				}

				// If everything was off, the whole storm just starts at the join point
				long at = anyOn ? WARMUP_LEAD_MS : OFF_BULB_JOIN_MS;

				for( int i = 0; i < flashes; i++ ) {
					final int intensity = 40 + random.nextInt( 61 );

					scheduleWave( executor, start, at, lights, intensity, true );
					at += step + random.nextInt( 60 );
					scheduleWave( executor, start, at, lights, 1, false );
					at += step + random.nextInt( 160 );
				}

				// The storm's final event flashes previously-on lights straight back into
				// their original state. Previously-off lights stay at minimum, and their
				// switch-off is a scheduled event too — off from level 1 is as invisible
				// as the entry was, and nobody waits for serial round-trips afterwards.
				for( final Device light : lights ) {
					if( light.on() ) {
						if( light.dimmable() && light.attributes() != null && light.attributes().lightLevel() != null ) {
							scheduleLevel( executor, start, at, light.id(), light.attributes().lightLevel() );
						}
						else if( !light.dimmable() ) {
							scheduleSwitch( executor, start, at, light.id(), true );
						}
					}
					else {
						scheduleSwitch( executor, start, at + OFF_SETTLE_MS, light.id(), false );
					}
				}
			}
		}
		finally {
			final List<Device> woken = lights.stream().filter( light -> !light.on() ).toList();

			if( !woken.isEmpty() ) {
				// A switch-off riding right behind the storm's command backlog can get
				// dropped by a busy hub — verify against fresh state, re-send stragglers
				try {
					Thread.sleep( 800 );
					final List<Device> current = Hub.lights();

					for( final Device light : woken ) {
						if( current.stream().anyMatch( d -> d.id().equals( light.id() ) && d.on() ) ) {
							Hub.setAttributes( light.id(), Map.of( "isOn", false ) );
						}
					}
				}
				catch( final Exception e ) {
					// The verification is best-effort
				}

				// The woken bulbs now remember level 1 and ignore level corrections while
				// off. Two-pronged repair: the Hub repays the brightness on the next
				// app-side turn-on, and startUpCurrentLevel (writable while off, unlike
				// lightLevel) stages it for wall-switch power cycles.
				for( final Device light : woken ) {
					if( light.dimmable() && light.attributes() != null && light.attributes().lightLevel() != null ) {
						final int level = light.attributes().lightLevel();
						Hub.registerPendingLevelRestore( light.id(), level );

						try {
							Hub.setAttributes( light.id(), Map.of( "startUpCurrentLevel", level ) );
						}
						catch( final Exception e ) {
							// Best effort
						}
					}
				}
			}
		}
	}

	/** Scheduling margin between the storm's last event and the woken bulbs' switch-off */
	private static final long OFF_SETTLE_MS = 400;

	/** How long a woken bulb's on + snap-to-minimum needs before it can safely join the waves */
	private static final long OFF_BULB_JOIN_MS = 600;

	/** Head start for the connection warmup before the first flash */
	private static final long WARMUP_LEAD_MS = 300;

	/**
	 * One flash wave across the group. Bulbs that were off skip wave events during
	 * their wake-up window, so a wave command can't race their entry sequence.
	 */
	private static void scheduleWave( final ExecutorService executor, final long start, final long at, final List<Device> lights, final int intensity, final boolean brightPhase ) {
		for( final Device light : lights ) {
			if( !light.on() && at < OFF_BULB_JOIN_MS ) {
				continue;
			}

			if( light.dimmable() ) {
				scheduleLevel( executor, start, at, light.id(), brightPhase ? intensity : 1 );
			}
			else {
				scheduleSwitch( executor, start, at, light.id(), brightPhase );
			}
		}
	}

	private static void scheduleLevel( final ExecutorService executor, final long startMillis, final long atMillis, final String deviceId, final int level ) {
		executor.submit( () -> {
			try {
				sleepUntil( startMillis + atMillis );
				Hub.setAttributes( deviceId, Map.of( "lightLevel", level ), 0 );
			}
			catch( final Exception e ) {
				// A dropped flash is just how lightning is
			}
		} );
	}

	private static void scheduleSwitch( final ExecutorService executor, final long startMillis, final long atMillis, final String deviceId, final boolean on ) {
		executor.submit( () -> {
			try {
				sleepUntil( startMillis + atMillis );
				Hub.setAttributes( deviceId, Map.of( "isOn", on ) );
			}
			catch( final Exception e ) {
				// A dropped flash is just how lightning is
			}
		} );
	}

	private static void sleepUntil( final long timeMillis ) throws InterruptedException {
		final long delay = timeMillis - System.currentTimeMillis();

		if( delay > 0 ) {
			Thread.sleep( delay );
		}
	}

	/** The color carousels currently running, by roomId */
	private static final Map<String, ColorCycler> _cyclers = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Starts or stops the color carousel for a room: each color-capable bulb's current
	 * color glides to the next bulb every few seconds, round and round. Intended for
	 * multi-bulb fixtures set up as rooms — the colors travel through the fixture.
	 *
	 * @return true if the carousel is now running, false if it was stopped (or the room lacks two lit color bulbs)
	 */
	public static boolean toggleCycle( final String roomId ) {
		final ColorCycler existing = _cyclers.remove( roomId );

		if( existing != null ) {
			existing.stop();
			return false;
		}

		final List<ColorCycler.BulbColor> colors = Hub.lightsSnapshot( 3000 )
				.stream()
				.filter( light -> roomId.equals( light.roomId() ) )
				.filter( Device::supportsColor )
				.filter( Device::reachable )
				.filter( Device::on )
				.filter( light -> light.attributes() != null && light.attributes().colorHue() != null && light.attributes().colorSaturation() != null )
				// Clamped at capture: the hub REPORTS saturations slightly above 1.0 but
				// rejects them on write — echoing raw values back kills the patch
				.map( light -> new ColorCycler.BulbColor(
						light.id(),
						Math.clamp( light.attributes().colorHue(), 0d, 360d ),
						Math.clamp( light.attributes().colorSaturation(), 0d, 1d ) ) )
				.toList();

		if( colors.size() < 2 ) {
			return false;
		}

		final ColorCycler cycler = new ColorCycler( roomId, colors );
		_cyclers.put( roomId, cycler );
		cycler.start();
		return true;
	}

	public static boolean cycling( final String roomId ) {
		return _cyclers.containsKey( roomId );
	}

	static class ColorCycler {

		/** A step every this many ms... */
		private static final long INTERVAL_MS = 4000;

		/**
		 * ...with the color gliding for this much of it. The generous margin matters:
		 * a glide command arriving while the previous glide is still finishing makes
		 * the bulb snap-complete it — a visible blink at every hand-off.
		 */
		private static final int TRANSITION_MS = 2400;

		record BulbColor( String deviceId, double hue, double saturation ) {}

		private final String _roomId;
		private final List<BulbColor> _colors;
		private volatile boolean _stopped;
		private Thread _thread;

		ColorCycler( final String roomId, final List<BulbColor> colors ) {
			_roomId = roomId;
			_colors = colors;
		}

		void start() {
			_thread = Thread.ofVirtual().name( "color-cycle-" + _roomId ).start( this::run );
		}

		void stop() {
			_stopped = true;

			if( _thread != null ) {
				_thread.interrupt();
			}
		}

		private void run() {
			int step = 0;

			while( !_stopped ) {
				step++;

				for( int i = 0; i < _colors.size(); i++ ) {
					final BulbColor target = _colors.get( ( i + step ) % _colors.size() );

					try {
						// Hue and saturation must travel together — the hub rejects a
						// patch containing only one of them (400, "allowed types")
						Hub.setAttributes( _colors.get( i ).deviceId(),
								Map.of( "colorHue", target.hue(), "colorSaturation", target.saturation() ), TRANSITION_MS );
					}
					catch( final Exception e ) {
						// A missed glide is no tragedy — the next step catches up
					}
				}

				try {
					Thread.sleep( INTERVAL_MS );
				}
				catch( final InterruptedException e ) {
					Thread.currentThread().interrupt();
					break;
				}
			}

			// Settle each bulb back on its own original color
			for( final BulbColor color : _colors ) {
				try {
					Hub.setAttributes( color.deviceId(), Map.of( "colorHue", color.hue(), "colorSaturation", color.saturation() ), 1000 );
				}
				catch( final Exception e ) {
					// Best effort
				}
			}
		}
	}
}
