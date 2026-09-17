package home;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Scheduled scene transitions: at a time of day (or relative to sunrise/sunset),
 * on chosen weekdays, fade the lights into a scene over a number of minutes —
 * optionally only in some rooms, so the bedroom can wake up when its occupants
 * choose. Persisted in ~/.homecontrol/schedules.json.
 */

public class Schedules {

	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger( Schedules.class );

	private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * @param trigger "HH:mm", "sunrise", "sunset", or either with a minute offset: "sunset+30", "sunrise-15"
	 * @param days weekday names ("MON".."SUN") the schedule fires on
	 * @param rooms hub room ids the transition is limited to; empty means every room
	 */
	public record Schedule( String id, String name, String trigger, List<String> days, String sceneSlug, int durationMinutes, List<String> rooms, boolean enabled ) {

		public boolean allRooms() {
			return rooms == null || rooms.isEmpty();
		}
	}

	private static List<Schedule> _schedules;

	/** Per schedule, the date it last fired — so each fires once per day */
	private static final Map<String, LocalDate> _firedOn = new ConcurrentHashMap<>();

	public static List<Schedule> all() {
		if( _schedules == null ) {
			_schedules = new ArrayList<>();

			if( Files.exists( file() ) ) {
				try {
					final List<Schedule> loaded = gson.fromJson( Files.readString( file() ), new TypeToken<List<Schedule>>() {}.getType() );

					if( loaded != null ) {
						_schedules.addAll( loaded );
					}
				}
				catch( final IOException e ) {
					throw new UncheckedIOException( "Could not read " + file(), e );
				}
			}
		}

		return _schedules;
	}

	public static Schedule byId( final String id ) {
		return all().stream().filter( s -> s.id().equals( id ) ).findFirst().orElse( null );
	}

	public static void add( final Schedule schedule ) {
		all().add( schedule );
		save();
	}

	public static void delete( final String id ) {
		all().removeIf( s -> s.id().equals( id ) );
		save();
	}

	public static void setEnabled( final String id, final boolean enabled ) {
		final Schedule s = byId( id );

		if( s != null ) {
			all().set( all().indexOf( s ), new Schedule( s.id(), s.name(), s.trigger(), s.days(), s.sceneSlug(), s.durationMinutes(), s.rooms(), enabled ) );
			save();
		}
	}

	/**
	 * Runs the schedule's transition right now (also what the scheduler calls when it's time)
	 */
	public static String run( final Schedule schedule ) {
		logger.info( "Running schedule '{}': scene {} over {} min{}", schedule.name(), schedule.sceneSlug(), schedule.durationMinutes(), schedule.allRooms() ? "" : ", rooms " + schedule.rooms() );
		return Scenes.transitionTo( schedule.sceneSlug(), Duration.ofMinutes( schedule.durationMinutes() ), schedule.allRooms() ? null : schedule.rooms() );
	}

	/**
	 * @return When this schedule fires on the given date, if its trigger resolves (no sunset during polar day)
	 */
	public static Optional<LocalTime> fireTime( final Schedule schedule, final LocalDate date ) {
		return resolveTrigger( schedule.trigger(), date );
	}

	/**
	 * Parses a trigger and resolves it for a date. Throws IllegalArgumentException for garbage.
	 */
	public static Optional<LocalTime> resolveTrigger( final String trigger, final LocalDate date ) {
		final String t = trigger.trim().toLowerCase();
		final java.util.regex.Matcher sun = java.util.regex.Pattern.compile( "^(sunrise|sunset)\\s*(?:([+-])\\s*(\\d{1,3}))?$" ).matcher( t );

		if( sun.matches() ) {
			final Optional<LocalTime> base = "sunrise".equals( sun.group( 1 ) ) ? SunCalc.sunrise( date ) : SunCalc.sunset( date );
			final int offset = sun.group( 2 ) == null ? 0 : Integer.parseInt( sun.group( 3 ) ) * ( "-".equals( sun.group( 2 ) ) ? -1 : 1 );
			return base.map( time -> time.plusMinutes( offset ) );
		}

		if( t.matches( "^\\d{1,2}:\\d{2}$" ) ) {
			final String[] parts = t.split( ":" );
			return Optional.of( LocalTime.of( Integer.parseInt( parts[0] ), Integer.parseInt( parts[1] ) ) );
		}

		throw new IllegalArgumentException( "Trigger must be a time like 17:30, or sunrise/sunset with an optional offset like sunset+30" );
	}

	/**
	 * Describes when a schedule fires today, for the list: "17:30" or "sunset+30 (today 19:52)"
	 */
	public static String describeTrigger( final Schedule schedule ) {
		final String t = schedule.trigger().trim();

		if( t.matches( "^\\d{1,2}:\\d{2}$" ) ) {
			return t;
		}

		return fireTime( schedule, LocalDate.now() )
				.map( time -> "%s (today %02d:%02d)".formatted( t, time.getHour(), time.getMinute() ) )
				.orElse( t + " (no such event today)" );
	}

	// ---- The scheduler ----

	private static boolean _started;

	/** Fires each enabled schedule once per day, within this window after its time (so a sleepy laptop can't miss one by seconds) */
	private static final Duration FIRE_WINDOW = Duration.ofMinutes( 3 );

	public static synchronized void startScheduler() {
		if( _started ) {
			return;
		}

		_started = true;

		Thread.ofVirtual().name( "scheduler" ).start( () -> {
			while( true ) {
				try {
					tick();
					Thread.sleep( 20_000 );
				}
				catch( final InterruptedException e ) {
					return;
				}
				catch( final Exception e ) {
					logger.warn( "Scheduler tick failed: {}", e.toString() );
				}
			}
		} );
	}

	static void tick() {
		final LocalDateTime now = LocalDateTime.now();
		final LocalDate today = now.toLocalDate();
		final String weekday = today.getDayOfWeek().name().substring( 0, 3 );

		for( final Schedule schedule : List.copyOf( all() ) ) {
			if( !schedule.enabled() || ( schedule.days() != null && !schedule.days().isEmpty() && !schedule.days().contains( weekday ) ) ) {
				continue;
			}

			if( today.equals( _firedOn.get( schedule.id() ) ) ) {
				continue;
			}

			final Optional<LocalTime> at = fireTime( schedule, today );

			if( at.isEmpty() ) {
				continue;
			}

			final LocalDateTime fire = today.atTime( at.get() );

			if( !now.isBefore( fire ) && now.isBefore( fire.plus( FIRE_WINDOW ) ) ) {
				_firedOn.put( schedule.id(), today );
				Thread.ofVirtual().name( "schedule-" + schedule.id() ).start( () -> run( schedule ) );
			}
		}
	}

	private static void save() {
		try {
			Files.createDirectories( Hub.configDirectory() );
			Files.writeString( file(), gson.toJson( _schedules ) );
		}
		catch( final IOException e ) {
			throw new UncheckedIOException( "Could not write " + file(), e );
		}
	}

	private static Path file() {
		return Hub.configDirectory().resolve( "schedules.json" );
	}

	public static String newId() {
		return Long.toString( System.currentTimeMillis(), 36 );
	}

	public static List<DayOfWeek> weekdays() {
		return List.of( DayOfWeek.values() );
	}
}
