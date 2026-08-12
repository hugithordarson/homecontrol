package home.dirigera;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.google.gson.Gson;

/**
 * Client for the DIRIGERA hub's local REST API (https on port 8443).
 *
 * The hub serves a self-signed certificate, so we trust everything — combined with
 * jdk.internal.httpclient.disableHostnameVerification=true (set in AppLauncher) since
 * the JDK's HttpClient offers no per-client way to skip hostname verification.
 */

public class DirigeraClient {

	private static final Gson gson = new Gson();

	private final String _hubAddress;
	private final String _token;
	private final HttpClient _http;

	public DirigeraClient( final String hubAddress, final String token ) {
		_hubAddress = hubAddress;
		_token = token;
		_http = insecureHttpClient();
	}

	public List<Device> devices() {
		final HttpRequest request = requestBuilder( "/devices" ).GET().build();
		final String body = send( request, 200 );
		return Arrays.asList( gson.fromJson( body, Device[].class ) );
	}

	/**
	 * Sets attributes on a device, e.g. Map.of( "isOn", true ) or Map.of( "lightLevel", 42 ).
	 * The hub answers 202 Accepted (the command is forwarded to the device asynchronously).
	 */
	public void setAttributes( final String deviceId, final Map<String, Object> attributes ) {
		setAttributes( deviceId, attributes, null );
	}

	/**
	 * @param transitionTimeMs How long the device takes to move to the new state, in ms —
	 * 0 snaps instantly (sidestepping the bulbs' soft fade on level moves), null uses the hub's default
	 */
	public void setAttributes( final String deviceId, final Map<String, Object> attributes, final Integer transitionTimeMs ) {
		final Map<String, Object> item = transitionTimeMs == null
				? Map.of( "attributes", attributes )
				: Map.of( "attributes", attributes, "transitionTime", transitionTimeMs );

		final String body = gson.toJson( List.of( item ) );

		final HttpRequest request = requestBuilder( "/devices/" + deviceId )
				.header( "content-type", "application/json" )
				.method( "PATCH", BodyPublishers.ofString( body ) )
				.build();

		send( request, 202, 200 );
	}

	/**
	 * Renames a room. The hub answers 202 — the rooms endpoint takes PUT (PATCH 404s).
	 */
	public void renameRoom( final String roomId, final String name ) {
		final HttpRequest request = requestBuilder( "/rooms/" + roomId )
				.header( "content-type", "application/json" )
				.PUT( BodyPublishers.ofString( gson.toJson( Map.of( "name", name ) ) ) )
				.build();

		send( request, 202, 200 );
	}

	private HttpRequest.Builder requestBuilder( final String path ) {
		return HttpRequest.newBuilder()
				.uri( URI.create( baseURL( _hubAddress ) + path ) )
				.header( "authorization", "Bearer " + _token )
				.timeout( Duration.ofSeconds( 10 ) );
	}

	private String send( final HttpRequest request, final int... acceptedStatuses ) {
		try {
			final HttpResponse<String> response = _http.send( request, BodyHandlers.ofString() );

			for( final int status : acceptedStatuses ) {
				if( response.statusCode() == status ) {
					return response.body();
				}
			}

			throw new DirigeraException( "Hub returned %s: %s".formatted( response.statusCode(), response.body() ) );
		}
		catch( final IOException e ) {
			throw new DirigeraException( "Could not reach the hub at " + _hubAddress + ": " + e.getMessage(), e );
		}
		catch( final InterruptedException e ) {
			Thread.currentThread().interrupt();
			throw new DirigeraException( "Interrupted while talking to the hub", e );
		}
	}

	static String baseURL( final String hubAddress ) {
		return "https://%s:8443/v1".formatted( hubAddress );
	}

	static HttpClient insecureHttpClient() {
		try {
			final TrustManager trustEverything = new X509TrustManager() {

				@Override
				public void checkClientTrusted( X509Certificate[] chain, String authType ) {}

				@Override
				public void checkServerTrusted( X509Certificate[] chain, String authType ) {}

				@Override
				public X509Certificate[] getAcceptedIssuers() {
					return new X509Certificate[0];
				}
			};

			final SSLContext sslContext = SSLContext.getInstance( "TLS" );
			sslContext.init( null, new TrustManager[] { trustEverything }, new SecureRandom() );

			return HttpClient.newBuilder()
					.sslContext( sslContext )
					// LAN-appropriate: a healthy hub connects in milliseconds, and an
					// absent one shouldn't hold anybody up longer than this
					.connectTimeout( Duration.ofSeconds( 2 ) )
					.build();
		}
		catch( final GeneralSecurityException e ) {
			throw new IllegalStateException( e );
		}
	}

	public static class DirigeraException extends RuntimeException {

		public DirigeraException( String message ) {
			super( message );
		}

		public DirigeraException( String message, Throwable cause ) {
			super( message, cause );
		}
	}
}
