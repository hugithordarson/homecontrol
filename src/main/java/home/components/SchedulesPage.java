package home.components;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOContext;

import app.HomeComponent;
import home.Hub;
import home.Scene;
import home.Scenes;
import home.Schedules;
import home.Schedules.Schedule;

/**
 * Scheduled transitions: list, enable/disable, run now, delete — and a form for
 * new ones. Every action redirects back here (post/redirect/get).
 */

public class SchedulesPage extends HomeComponent {

	public Schedule currentSchedule;
	public DayOfWeek currentDay;
	public Hub.RoomGroup currentRoom;
	public Scene currentScene;

	// The form ("name" would collide with WOComponent.name())
	public String scheduleName;
	public String trigger = "sunset";
	public Scene selectedScene;
	public String durationMinutes = "10";
	public Set<DayOfWeek> selectedDays = new LinkedHashSet<>( List.of( DayOfWeek.values() ) );
	public Set<String> selectedRooms = new LinkedHashSet<>();

	private List<Hub.RoomGroup> _rooms;

	public SchedulesPage( WOContext context ) {
		super( context );
	}

	public int runningFades() {
		return Scenes.runningFades();
	}

	public boolean hasRunningFades() {
		return runningFades() > 0;
	}

	public WOActionResults cancelFades() {
		final int count = Scenes.cancelAllFades();
		flashError( "Stopped the fade on %s lights — they stay as they are now".formatted( count ) );
		return redirectTo( "/schedules" );
	}

	public List<Schedule> schedules() {
		return Schedules.all();
	}

	public boolean hasSchedules() {
		return !schedules().isEmpty();
	}

	public List<Scene> scenes() {
		return Scenes.all();
	}

	public List<DayOfWeek> weekdays() {
		return Schedules.weekdays();
	}

	public List<Hub.RoomGroup> rooms() {
		if( _rooms == null ) {
			_rooms = Hub.configured() ? Hub.roomGroups() : List.of();
		}

		return _rooms;
	}

	public String sunToday() {
		final LocalDate today = LocalDate.now();
		final String rise = home.SunCalc.sunrise( today ).map( t -> "%02d:%02d".formatted( t.getHour(), t.getMinute() ) ).orElse( "—" );
		final String set = home.SunCalc.sunset( today ).map( t -> "%02d:%02d".formatted( t.getHour(), t.getMinute() ) ).orElse( "—" );
		return "Today the sun rises at %s and sets at %s.".formatted( rise, set );
	}

	// ---- Form bindings ----

	public String currentDayLabel() {
		return currentDay.name().substring( 0, 1 ) + currentDay.name().substring( 1, 3 ).toLowerCase();
	}

	public boolean currentDayChecked() {
		return selectedDays.contains( currentDay );
	}

	public void setCurrentDayChecked( final boolean checked ) {
		if( checked ) {
			selectedDays.add( currentDay );
		}
		else {
			selectedDays.remove( currentDay );
		}
	}

	public boolean currentRoomChecked() {
		return selectedRooms.contains( currentRoom.id() );
	}

	public void setCurrentRoomChecked( final boolean checked ) {
		if( checked ) {
			selectedRooms.add( currentRoom.id() );
		}
		else {
			selectedRooms.remove( currentRoom.id() );
		}
	}

	public WOActionResults addSchedule() {
		try {
			if( scheduleName == null || scheduleName.isBlank() ) {
				throw new IllegalArgumentException( "Give the schedule a name" );
			}

			if( selectedScene == null ) {
				throw new IllegalArgumentException( "Pick a scene" );
			}

			Schedules.resolveTrigger( trigger, LocalDate.now() );
			final int minutes = Integer.parseInt( durationMinutes.trim() );

			final List<String> days = new ArrayList<>();
			selectedDays.stream().sorted().forEach( d -> days.add( d.name().substring( 0, 3 ) ) );

			// "All rooms" is the empty list; a full set of rooms means the same thing
			final List<String> rooms = selectedRooms.size() == rooms().size() ? List.of() : new ArrayList<>( selectedRooms );

			Schedules.add( new Schedule( Schedules.newId(), scheduleName.trim(), trigger.trim(), days, selectedScene.slug(), Math.max( 0, minutes ), rooms, true ) );
		}
		catch( final NumberFormatException e ) {
			flashError( "Duration must be a number of minutes" );
		}
		catch( final IllegalArgumentException e ) {
			flashError( e.getMessage() );
		}

		return redirectTo( "/schedules" );
	}

	// ---- List bindings ----

	public String currentScheduleWhen() {
		return Schedules.describeTrigger( currentSchedule );
	}

	public String currentScheduleDays() {
		final List<String> days = currentSchedule.days();

		if( days == null || days.isEmpty() || days.size() == 7 ) {
			return "every day";
		}

		return String.join( ", ", days ).toLowerCase();
	}

	public String currentScheduleScene() {
		final Scene scene = Scenes.bySlug( currentSchedule.sceneSlug() );
		return scene != null ? scene.name() : currentSchedule.sceneSlug() + " (missing)";
	}

	public String currentScheduleDuration() {
		return currentSchedule.durationMinutes() == 0 ? "instantly" : "over " + currentSchedule.durationMinutes() + " min";
	}

	public String currentScheduleRooms() {
		if( currentSchedule.allRooms() ) {
			return "all rooms";
		}

		final List<String> names = new ArrayList<>();

		for( final Hub.RoomGroup room : rooms() ) {
			if( currentSchedule.rooms().contains( room.id() ) ) {
				names.add( room.name() );
			}
		}

		return names.isEmpty() ? "no reachable rooms" : String.join( ", ", names );
	}

	public String currentScheduleClass() {
		return currentSchedule.enabled() ? "schedule" : "schedule disabled";
	}

	public String currentScheduleToggleLabel() {
		return currentSchedule.enabled() ? "Disable" : "Enable";
	}

	public WOActionResults toggleCurrentSchedule() {
		Schedules.setEnabled( currentSchedule.id(), !currentSchedule.enabled() );
		return redirectTo( "/schedules" );
	}

	public WOActionResults runCurrentSchedule() {
		final Schedule schedule = currentSchedule;
		Thread.ofVirtual().start( () -> Schedules.run( schedule ) );
		flashError( "Running \"%s\" now%s".formatted( schedule.name(), schedule.durationMinutes() > 0 ? " — the fade takes " + schedule.durationMinutes() + " minutes" : "" ) );
		return redirectTo( "/schedules" );
	}

	public WOActionResults deleteCurrentSchedule() {
		Schedules.delete( currentSchedule.id() );
		return redirectTo( "/schedules" );
	}
}
