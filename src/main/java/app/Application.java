package app;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOResponse;

import er.extensions.appserver.ERXApplication;
import er.extensions.routes.RouteInvocation;
import er.extensions.routes.RouteTable;
import home.Hub;
import home.Scenes;
import home.components.FrontPage;
import home.components.ScenesPage;
import home.components.SetupPage;

public class Application extends ERXApplication {

	public Application() {
		setupRoutes();
	}

	private void setupRoutes() {
		RouteTable.defaultRouteTable().map( "/", FrontPage.class );
		RouteTable.defaultRouteTable().map( "/scenes", ScenesPage.class );
		RouteTable.defaultRouteTable().map( "/floor", home.components.FloorPage.class );
		RouteTable.defaultRouteTable().map( "/setup", SetupPage.class );

		// The control endpoints the dashboard's javascript talks to
		RouteTable.defaultRouteTable().map( "/api/all/*", this::allLights );
		RouteTable.defaultRouteTable().map( "/api/room/*", this::room );
		RouteTable.defaultRouteTable().map( "/api/toggle/*", this::toggleDevice );
		RouteTable.defaultRouteTable().map( "/api/level/*", this::deviceLevel );
		RouteTable.defaultRouteTable().map( "/api/color/*", this::deviceColor );
		RouteTable.defaultRouteTable().map( "/api/temperature/*", this::deviceTemperature );
		RouteTable.defaultRouteTable().map( "/api/scene/*", this::applyScene );
		RouteTable.defaultRouteTable().map( "/api/rename-room/*", this::renameRoom );
		RouteTable.defaultRouteTable().map( "/api/rename-scene/*", this::renameScene );
		RouteTable.defaultRouteTable().map( "/api/room-color/*", this::roomColor );
		RouteTable.defaultRouteTable().map( "/api/room-level/*", this::roomLevel );
		RouteTable.defaultRouteTable().map( "/api/batch", this::batch );
		RouteTable.defaultRouteTable().map( "/api/palette/add", this::paletteAdd );
		RouteTable.defaultRouteTable().map( "/api/palette/remove", this::paletteRemove );
		RouteTable.defaultRouteTable().map( "/api/effect/*", this::effect );
		RouteTable.defaultRouteTable().map( "/api/cycle/*", this::toggleCycle );
	}

	/**
	 * /api/all/on or /api/all/off
	 */
	private WOActionResults allLights( RouteInvocation invocation ) {
		Hub.turnAll( "on".equals( lastPathComponent( invocation ) ) );
		return ok();
	}

	/**
	 * /api/room/[roomId]/on or /api/room/[roomId]/off
	 */
	private WOActionResults room( RouteInvocation invocation ) {
		final String[] components = pathComponents( invocation, "/api/room/" );

		if( components.length != 2 ) {
			return badRequest( "Expected /api/room/[roomId]/[on|off]" );
		}

		Hub.turnRoom( components[0], "on".equals( components[1] ) );
		return ok();
	}

	/**
	 * /api/toggle/[deviceId]
	 */
	private WOActionResults toggleDevice( RouteInvocation invocation ) {
		Hub.toggle( lastPathComponent( invocation ) );
		return ok();
	}

	/**
	 * /api/level/[deviceId]/[1-100]
	 */
	private WOActionResults deviceLevel( RouteInvocation invocation ) {
		final String[] components = pathComponents( invocation, "/api/level/" );

		if( components.length != 2 ) {
			return badRequest( "Expected /api/level/[deviceId]/[1-100]" );
		}

		try {
			Hub.setLevel( components[0], Integer.parseInt( components[1] ) );
		}
		catch( final NumberFormatException e ) {
			return badRequest( "Not a light level: " + components[1] );
		}

		return ok();
	}

	/**
	 * /api/temperature/[deviceId]/[kelvin]
	 */
	private WOActionResults deviceTemperature( RouteInvocation invocation ) {
		final String[] components = pathComponents( invocation, "/api/temperature/" );

		if( components.length != 2 ) {
			return badRequest( "Expected /api/temperature/[deviceId]/[kelvin]" );
		}

		try {
			Hub.setTemperature( components[0], Integer.parseInt( components[1] ) );
		}
		catch( final NumberFormatException e ) {
			return badRequest( "Not a color temperature: " + components[1] );
		}

		return ok();
	}

	/**
	 * /api/color/[deviceId]/[hue 0-360]/[saturation 0-1]
	 */
	private WOActionResults deviceColor( RouteInvocation invocation ) {
		final String[] components = pathComponents( invocation, "/api/color/" );

		if( components.length != 3 ) {
			return badRequest( "Expected /api/color/[deviceId]/[hue]/[saturation]" );
		}

		try {
			Hub.setColor( components[0], Double.parseDouble( components[1] ), Double.parseDouble( components[2] ) );
		}
		catch( final NumberFormatException e ) {
			return badRequest( "Not a color: %s/%s".formatted( components[1], components[2] ) );
		}

		return ok();
	}

	/**
	 * /api/scene/[slug]
	 */
	private WOActionResults applyScene( RouteInvocation invocation ) {
		Scenes.apply( lastPathComponent( invocation ) );
		return ok();
	}

	/**
	 * /api/rename-room/[roomId]?name=[new name]
	 */
	private WOActionResults renameRoom( RouteInvocation invocation ) {
		final String name = invocation.request().stringFormValueForKey( "name" );

		if( name == null || name.isBlank() ) {
			return badRequest( "Expected /api/rename-room/[roomId]?name=[new name]" );
		}

		Hub.renameRoom( lastPathComponent( invocation ), name.trim() );
		return ok();
	}

	/**
	 * /api/rename-scene/[slug]?name=[new name]
	 */
	private WOActionResults renameScene( RouteInvocation invocation ) {
		final String name = invocation.request().stringFormValueForKey( "name" );

		if( name == null || name.isBlank() ) {
			return badRequest( "Expected /api/rename-scene/[slug]?name=[new name]" );
		}

		try {
			Scenes.rename( lastPathComponent( invocation ), name.trim() );
		}
		catch( final IllegalArgumentException e ) {
			final WOResponse response = new WOResponse();
			response.setStatus( 409 );
			response.setContent( e.getMessage() );
			return response;
		}

		return ok();
	}

	/**
	 * /api/room-color/[roomId]/[hue 0-360]/[saturation 0-1]
	 */
	private WOActionResults roomColor( RouteInvocation invocation ) {
		final String[] components = pathComponents( invocation, "/api/room-color/" );

		if( components.length != 3 ) {
			return badRequest( "Expected /api/room-color/[roomId]/[hue]/[saturation]" );
		}

		try {
			Hub.setRoomColor( components[0], Double.parseDouble( components[1] ), Double.parseDouble( components[2] ) );
		}
		catch( final NumberFormatException e ) {
			return badRequest( "Not a color: %s/%s".formatted( components[1], components[2] ) );
		}

		return ok();
	}

	/**
	 * /api/room-level/[roomId]/[1-100]
	 */
	private WOActionResults roomLevel( RouteInvocation invocation ) {
		final String[] components = pathComponents( invocation, "/api/room-level/" );

		if( components.length != 2 ) {
			return badRequest( "Expected /api/room-level/[roomId]/[1-100]" );
		}

		try {
			Hub.setRoomLevel( components[0], Integer.parseInt( components[1] ) );
		}
		catch( final NumberFormatException e ) {
			return badRequest( "Not a light level: " + components[1] );
		}

		return ok();
	}

	/**
	 * /api/effect/[name] — currently only "lightning". An optional ?room=[roomId]
	 * contains the effect to one room.
	 */
	private WOActionResults effect( RouteInvocation invocation ) {
		final String name = lastPathComponent( invocation );

		if( !"lightning".equals( name ) ) {
			return badRequest( "Unknown effect: " + name );
		}

		final String deviceId = invocation.request().stringFormValueForKey( "device" );
		final String devices = invocation.request().stringFormValueForKey( "devices" );

		if( deviceId != null ) {
			home.Effects.lightningBolt( deviceId );
		}
		else if( devices != null && !devices.isBlank() ) {
			home.Effects.lightningDevices( java.util.Arrays.asList( devices.split( "," ) ) );
		}
		else {
			home.Effects.lightning( invocation.request().stringFormValueForKey( "room" ) );
		}

		return ok();
	}

	/**
	 * /api/cycle/[roomId] — starts or stops the room's color carousel
	 */
	private WOActionResults toggleCycle( RouteInvocation invocation ) {
		final boolean cycling = home.Effects.toggleCycle( lastPathComponent( invocation ) );

		final WOResponse response = new WOResponse();
		response.setHeader( "application/json", "content-type" );
		response.setContent( "{\"ok\":true,\"cycling\":%s}".formatted( cycling ) );
		return response;
	}

	/**
	 * POST /api/batch with a JSON body: {"lights":[ids], "isOn":bool, "lightLevel":n, "colorTemperature":k, "colorHue":h, "colorSaturation":s}
	 * — any attribute may be omitted; the selection panel's one-request-for-everything.
	 */
	private WOActionResults batch( RouteInvocation invocation ) {
		final Hub.Batch batch = new com.google.gson.Gson().fromJson( invocation.request().contentString(), Hub.Batch.class );

		if( batch == null || batch.lights() == null || batch.lights().isEmpty() ) {
			return badRequest( "Expected a JSON body with a non-empty lights array" );
		}

		Hub.applyBatch( batch );
		return ok();
	}

	record PaletteRequest( String name, java.util.List<String> hexes ) {}

	/**
	 * POST /api/palette/add with {"name": "...", "hexes": ["#..", ...]} — saves a user chip
	 */
	private WOActionResults paletteAdd( RouteInvocation invocation ) {
		final PaletteRequest request = new com.google.gson.Gson().fromJson( invocation.request().contentString(), PaletteRequest.class );

		if( request == null || request.name() == null || request.name().isBlank() || request.hexes() == null || request.hexes().isEmpty() ) {
			return badRequest( "Expected {name, hexes[]}" );
		}

		home.Palette.add( request.name().trim(), request.hexes() );
		return ok();
	}

	/**
	 * POST /api/palette/remove with {"name": "..."}
	 */
	private WOActionResults paletteRemove( RouteInvocation invocation ) {
		final PaletteRequest request = new com.google.gson.Gson().fromJson( invocation.request().contentString(), PaletteRequest.class );

		if( request == null || request.name() == null ) {
			return badRequest( "Expected {name}" );
		}

		home.Palette.remove( request.name() );
		return ok();
	}

	private static String[] pathComponents( final RouteInvocation invocation, final String prefix ) {
		String path = stripQueryString( invocation.url() ).substring( prefix.length() );

		if( path.endsWith( "/" ) ) {
			path = path.substring( 0, path.length() - 1 );
		}

		return path.split( "/" );
	}

	private static String lastPathComponent( final RouteInvocation invocation ) {
		String url = stripQueryString( invocation.url() );

		if( url.endsWith( "/" ) ) {
			url = url.substring( 0, url.length() - 1 );
		}

		return url.substring( url.lastIndexOf( '/' ) + 1 );
	}

	private static String stripQueryString( final String url ) {
		final int questionMark = url.indexOf( '?' );
		return questionMark == -1 ? url : url.substring( 0, questionMark );
	}

	private static WOResponse ok() {
		final WOResponse response = new WOResponse();
		response.setHeader( "application/json", "content-type" );
		response.setContent( "{\"ok\":true}" );
		return response;
	}

	private static WOResponse badRequest( final String message ) {
		final WOResponse response = new WOResponse();
		response.setStatus( 400 );
		response.setContent( message );
		return response;
	}
}
