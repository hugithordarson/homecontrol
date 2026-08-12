package home.dirigera;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.gson.Gson;

import home.dirigera.DirigeraClient.DirigeraException;

/**
 * The hub's one-time pairing dance (OAuth2 with PKCE):
 *
 * 1. start() asks the hub for an authorization code.
 * 2. The user presses the action button on the bottom of the hub.
 * 3. finish() exchanges the code for a long-lived access token.
 */

public class DirigeraPairing {

	private static final String VERIFIER_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789_-~.";
	private static final int VERIFIER_LENGTH = 128;

	private static final Gson gson = new Gson();

	private final String _hubAddress;
	private final String _codeVerifier;
	private final HttpClient _http;

	private String _code;

	public DirigeraPairing( final String hubAddress ) {
		_hubAddress = hubAddress;
		_codeVerifier = generateCodeVerifier();
		_http = DirigeraClient.insecureHttpClient();
	}

	public String hubAddress() {
		return _hubAddress;
	}

	/**
	 * Requests an authorization code from the hub. After this, the user has about
	 * 90 seconds to press the hub's action button before finish() is called.
	 */
	public void start() {
		final String url = DirigeraClient.baseURL( _hubAddress ) + "/oauth/authorize"
				+ "?audience=homesmart.local"
				+ "&response_type=code"
				+ "&code_challenge=" + codeChallenge()
				+ "&code_challenge_method=S256";

		final HttpRequest request = HttpRequest.newBuilder()
				.uri( URI.create( url ) )
				.timeout( Duration.ofSeconds( 10 ) )
				.GET()
				.build();

		final Map<?, ?> response = sendForJSON( request );
		_code = (String)response.get( "code" );

		if( _code == null ) {
			throw new DirigeraException( "The hub did not return an authorization code: " + response );
		}
	}

	/**
	 * @return The access token. Invoke after the user has pressed the hub's action button.
	 */
	public String finish() {
		if( _code == null ) {
			throw new DirigeraException( "Pairing has not been started" );
		}

		final String form = formEncoded( Map.of(
				"code", _code,
				"name", clientName(),
				"grant_type", "authorization_code",
				"code_verifier", _codeVerifier ) );

		final HttpRequest request = HttpRequest.newBuilder()
				.uri( URI.create( DirigeraClient.baseURL( _hubAddress ) + "/oauth/token" ) )
				.header( "content-type", "application/x-www-form-urlencoded" )
				.timeout( Duration.ofSeconds( 10 ) )
				.POST( BodyPublishers.ofString( form ) )
				.build();

		final Map<?, ?> response = sendForJSON( request );
		final String token = (String)response.get( "access_token" );

		if( token == null ) {
			final Object error = response.get( "error" );
			throw new DirigeraException( error != null ? error.toString() : "The hub did not return a token: " + response );
		}

		return token;
	}

	private Map<?, ?> sendForJSON( final HttpRequest request ) {
		try {
			final HttpResponse<String> response = _http.send( request, BodyHandlers.ofString() );
			final Map<?, ?> parsed = gson.fromJson( response.body(), Map.class );

			if( response.statusCode() >= 300 ) {
				final Object error = parsed != null ? parsed.get( "error" ) : null;
				throw new DirigeraException( error != null ? error.toString() : "Hub returned %s: %s".formatted( response.statusCode(), response.body() ) );
			}

			return parsed;
		}
		catch( final IOException e ) {
			throw new DirigeraException( "Could not reach the hub at " + _hubAddress + ": " + e.getMessage(), e );
		}
		catch( final InterruptedException e ) {
			Thread.currentThread().interrupt();
			throw new DirigeraException( "Interrupted while talking to the hub", e );
		}
	}

	private String codeChallenge() {
		try {
			final byte[] digest = MessageDigest.getInstance( "SHA-256" ).digest( _codeVerifier.getBytes( StandardCharsets.UTF_8 ) );
			return Base64.getUrlEncoder().withoutPadding().encodeToString( digest );
		}
		catch( final NoSuchAlgorithmException e ) {
			throw new IllegalStateException( e );
		}
	}

	private static String generateCodeVerifier() {
		final SecureRandom random = new SecureRandom();
		final StringBuilder sb = new StringBuilder( VERIFIER_LENGTH );

		for( int i = 0; i < VERIFIER_LENGTH; i++ ) {
			sb.append( VERIFIER_ALPHABET.charAt( random.nextInt( VERIFIER_ALPHABET.length() ) ) );
		}

		return sb.toString();
	}

	private static String clientName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		}
		catch( final IOException e ) {
			return "homecontrol";
		}
	}

	private static String formEncoded( final Map<String, String> values ) {
		return values.entrySet()
				.stream()
				.map( entry -> entry.getKey() + "=" + URLEncoder.encode( entry.getValue(), StandardCharsets.UTF_8 ) )
				.collect( Collectors.joining( "&" ) );
	}
}
