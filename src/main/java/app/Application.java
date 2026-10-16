package app;

import ng.appserver.NGActionResults;
import ng.appserver.NGApplication;
import ng.appserver.http.NGRequest;
import ng.appserver.http.NGResponse;
import ng.appserver.http.NGResponses;
import ng.plugins.Elements;
import ng.plugins.Routes;
import home.Hub;
import home.Scenes;
import home.components.FrontPage;
import home.components.ScenesPage;
import home.components.SetupPage;

public class Application extends NGApplication {

	public Application() {
		home.Schedules.startScheduler();
	}

	@Override
	public Elements elements() {
		return super
				.elements()
				.elementPackage( "home.components" );
	}

	@Override
	public Routes routes() {
		final Routes routes = Routes.create();
		routes.map( "/", FrontPage.class );
		routes.map( "/scenes", ScenesPage.class );
		routes.map( "/floor", home.components.FloorPage.class );
		routes.map( "/schedules", home.components.SchedulesPage.class );
		routes.map( "/setup", SetupPage.class );


		// The control endpoints the dashboard's javascript talks to
		routes.map( "/api/all/*", this::allLights );
		routes.map( "/api/room/*", this::room );
		routes.map( "/api/toggle/*", this::toggleDevice );
		routes.map( "/api/level/*", this::deviceLevel );
		routes.map( "/api/color/*", this::deviceColor );
		routes.map( "/api/temperature/*", this::deviceTemperature );
		routes.map( "/api/scene/*", this::applyScene );
		routes.map( "/api/rename-room/*", this::renameRoom );
		routes.map( "/api/rename-scene/*", this::renameScene );
		routes.map( "/api/room-color/*", this::roomColor );
		routes.map( "/api/room-level/*", this::roomLevel );
		routes.map( "/api/batch", this::batch );
		routes.map( "/api/room-order", this::roomOrder );
		routes.map( "/api/schedule/run/*", this::runSchedule );
		routes.map( "/api/schedule/cancel", this::cancelFades );
		routes.map( "/api/palette/add", this::paletteAdd );
		routes.map( "/api/palette/remove", this::paletteRemove );
		routes.map( "/api/effect/*", this::effect );
		routes.map( "/api/cycle/*", this::toggleCycle );
		return routes;
	}

	/**
	 * /api/all/on or /api/all/off
	 */
	private NGActionResults allLights( NGRequest request ) {
		Hub.turnAll( "on".equals( lastPathComponent( request ) ) );
		return ok();
	}

	/**
	 * /api/room/[roomId]/on or /api/room/[roomId]/off
	 */
	private NGActionResults room( NGRequest request ) {
		final String[] components = pathComponents( request, "/api/room/" );

		if( components.length != 2 ) {
			return badRequest( "Expected /api/room/[roomId]/[on|off]" );
		}

		Hub.turnRoom( components[0], "on".equals( components[1] ) );
		return ok();
	}

	/**
	 * /api/toggle/[deviceId]
	 */
	private NGActionResults toggleDevice( NGRequest request ) {
		Hub.toggle( lastPathComponent( request ) );
		return ok();
	}

	/**
	 * /api/level/[deviceId]/[1-100]
	 */
	private NGActionResults deviceLevel( NGRequest request ) {
		final String[] components = pathComponents( request, "/api/level/" );

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
	private NGActionResults deviceTemperature( NGRequest request ) {
		final String[] components = pathComponents( request, "/api/temperature/" );

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
	private NGActionResults deviceColor( NGRequest request ) {
		final String[] components = pathComponents( request, "/api/color/" );

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
	private NGActionResults applyScene( NGRequest request ) {
		final String slug = lastPathComponent( request );

		if( Scenes.bySlug( slug ) == null ) {
			return NGResponses.of( 404, "No scene with slug " + slug );
		}

		Scenes.apply( slug );
		return ok();
	}

	/**
	 * /api/rename-room/[roomId]?name=[new name]
	 */
	private NGActionResults renameRoom( NGRequest request ) {
		final String name = request.formValueForKey( "name" );

		if( name == null || name.isBlank() ) {
			return badRequest( "Expected /api/rename-room/[roomId]?name=[new name]" );
		}

		Hub.renameRoom( lastPathComponent( request ), name.trim() );
		return ok();
	}

	/**
	 * /api/rename-scene/[slug]?name=[new name]
	 */
	private NGActionResults renameScene( NGRequest request ) {
		final String name = request.formValueForKey( "name" );

		if( name == null || name.isBlank() ) {
			return badRequest( "Expected /api/rename-scene/[slug]?name=[new name]" );
		}

		try {
			Scenes.rename( lastPathComponent( request ), name.trim() );
		}
		catch( final IllegalArgumentException e ) {
			return NGResponses.of( 409, e.getMessage() );
		}

		return ok();
	}

	/**
	 * /api/room-color/[roomId]/[hue 0-360]/[saturation 0-1]
	 */
	private NGActionResults roomColor( NGRequest request ) {
		final String[] components = pathComponents( request, "/api/room-color/" );

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
	private NGActionResults roomLevel( NGRequest request ) {
		final String[] components = pathComponents( request, "/api/room-level/" );

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
	private NGActionResults effect( NGRequest request ) {
		final String name = lastPathComponent( request );

		if( !"lightning".equals( name ) ) {
			return badRequest( "Unknown effect: " + name );
		}

		final String deviceId = request.formValueForKey( "device" );
		final String devices = request.formValueForKey( "devices" );

		if( deviceId != null ) {
			home.Effects.lightningBolt( deviceId );
		}
		else if( devices != null && !devices.isBlank() ) {
			home.Effects.lightningDevices( java.util.Arrays.asList( devices.split( "," ) ) );
		}
		else {
			home.Effects.lightning( request.formValueForKey( "room" ) );
		}

		return ok();
	}

	/**
	 * /api/cycle/[roomId] — starts or stops the room's color carousel
	 */
	private NGActionResults toggleCycle( NGRequest request ) {
		final boolean cycling = home.Effects.toggleCycle( lastPathComponent( request ) );

		return json( "{\"ok\":true,\"cycling\":%s}".formatted( cycling ) );
	}

	/**
	 * POST /api/batch with a JSON body: {"lights":[ids], "isOn":bool, "lightLevel":n, "colorTemperature":k, "colorHue":h, "colorSaturation":s}
	 * — any attribute may be omitted; the selection panel's one-request-for-everything.
	 */
	private NGActionResults batch( NGRequest request ) {
		final Hub.Batch batch = new com.google.gson.Gson().fromJson( request.contentString(), Hub.Batch.class );

		if( batch == null || batch.lights() == null || batch.lights().isEmpty() ) {
			return badRequest( "Expected a JSON body with a non-empty lights array" );
		}

		Hub.applyBatch( batch );
		return ok();
	}

	/**
	 * POST /api/schedule/run/[id] — runs a schedule's transition now (in the background)
	 */
	private NGActionResults runSchedule( NGRequest request ) {
		final home.Schedules.Schedule schedule = home.Schedules.byId( lastPathComponent( request ) );

		if( schedule == null ) {
			return NGResponses.of( 404, "No such schedule" );
		}

		Thread.ofVirtual().start( () -> home.Schedules.run( schedule ) );
		return ok();
	}

	/**
	 * POST /api/schedule/cancel — stops every running scheduled fade, lights stay where they are
	 */
	private NGActionResults cancelFades( NGRequest request ) {
		final int count = Scenes.cancelAllFades();
		return json( "{\"ok\":true,\"cancelled\":%s}".formatted( count ) );
	}

	record RoomOrderRequest( java.util.List<String> rooms ) {}

	/**
	 * POST /api/room-order with {"rooms":[roomIds in display order]} — the dashboard's card order
	 */
	private NGActionResults roomOrder( NGRequest request ) {
		final RoomOrderRequest body = new com.google.gson.Gson().fromJson( request.contentString(), RoomOrderRequest.class );

		if( body == null || body.rooms() == null ) {
			return badRequest( "Expected {rooms: [ids]}" );
		}

		Hub.saveRoomOrder( body.rooms() );
		return ok();
	}

	record PaletteRequest( String name, java.util.List<String> hexes ) {}

	/**
	 * POST /api/palette/add with {"name": "...", "hexes": ["#..", ...]} — saves a user chip
	 */
	private NGActionResults paletteAdd( NGRequest request ) {
		final PaletteRequest body = new com.google.gson.Gson().fromJson( request.contentString(), PaletteRequest.class );

		if( body == null || body.name() == null || body.name().isBlank() || body.hexes() == null || body.hexes().isEmpty() ) {
			return badRequest( "Expected {name, hexes[]}" );
		}

		home.Palette.add( body.name().trim(), body.hexes() );
		return ok();
	}

	/**
	 * POST /api/palette/remove with {"name": "..."}
	 */
	private NGActionResults paletteRemove( NGRequest request ) {
		final PaletteRequest body = new com.google.gson.Gson().fromJson( request.contentString(), PaletteRequest.class );

		if( body == null || body.name() == null ) {
			return badRequest( "Expected {name}" );
		}

		home.Palette.remove( body.name() );
		return ok();
	}

	/**
	 * The route URL arrives still percent-encoded — invisible for UUID ids, fatal for
	 * scene slugs with Icelandic letters (n%C3%B3tt). Every component gets decoded.
	 */
	private static String[] pathComponents( final NGRequest request, final String prefix ) {
		String path = stripQueryString( request.uri() ).substring( prefix.length() );

		if( path.endsWith( "/" ) ) {
			path = path.substring( 0, path.length() - 1 );
		}

		final String[] components = path.split( "/" );

		for( int i = 0; i < components.length; i++ ) {
			components[i] = java.net.URLDecoder.decode( components[i], java.nio.charset.StandardCharsets.UTF_8 );
		}

		return components;
	}

	private static String lastPathComponent( final NGRequest request ) {
		String url = stripQueryString( request.uri() );

		if( url.endsWith( "/" ) ) {
			url = url.substring( 0, url.length() - 1 );
		}

		return java.net.URLDecoder.decode( url.substring( url.lastIndexOf( '/' ) + 1 ), java.nio.charset.StandardCharsets.UTF_8 );
	}

	private static String stripQueryString( final String url ) {
		final int questionMark = url.indexOf( '?' );
		return questionMark == -1 ? url : url.substring( 0, questionMark );
	}

	private static NGResponse ok() {
		return json( "{\"ok\":true}" );
	}

	private static NGResponse json( final String content ) {
		final NGResponse response = NGResponses.ok( content );
		response.setHeader( "content-type", "application/json" );
		return response;
	}

	private static NGResponse badRequest( final String message ) {
		return NGResponses.of( 400, message );
	}
}
