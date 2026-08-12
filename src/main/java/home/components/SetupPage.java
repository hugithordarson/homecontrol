package home.components;

import java.util.List;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOContext;

import app.HomeComponent;
import home.Hub;
import home.dirigera.DirigeraPairing;

/**
 * Hub management: the paired hubs, and the pairing dance for adding a new one —
 * enter its IP, request an authorization code, press the action button on the
 * hub, exchange for a token.
 *
 * The pairing in progress lives in the session, since every action redirects
 * back here (post/redirect/get) and lands on a fresh page instance.
 */

public class SetupPage extends HomeComponent {

	public String hubAddress;
	public String hubName;
	public Hub.HubConfig currentHub;

	public SetupPage( WOContext context ) {
		super( context );
	}

	public List<Hub.HubConfig> hubs() {
		return Hub.hubs();
	}

	public boolean hasHubs() {
		return !hubs().isEmpty();
	}

	public WOActionResults startPairing() {
		if( hubAddress == null || hubAddress.isBlank() ) {
			flashError( "Enter the hub's IP address first" );
			return redirectTo( "/setup" );
		}

		try {
			final DirigeraPairing pairing = new DirigeraPairing( hubAddress.trim() );
			pairing.start();
			session().pairing = pairing;
			session().pairingName = hubName;
		}
		catch( final Exception e ) {
			flashError( e.getMessage() );
		}

		return redirectTo( "/setup" );
	}

	public WOActionResults completePairing() {
		try {
			final String token = session().pairing.finish();
			Hub.addHub( session().pairingName, session().pairing.hubAddress(), token );
			session().pairing = null;
			session().pairingName = null;
			return redirectTo( "/" );
		}
		catch( final Exception e ) {
			flashError( e.getMessage() );
			return redirectTo( "/setup" );
		}
	}

	public WOActionResults cancelPairing() {
		session().pairing = null;
		session().pairingName = null;
		return redirectTo( "/setup" );
	}

	public WOActionResults removeCurrentHub() {
		Hub.removeHub( currentHub.address() );
		return redirectTo( "/setup" );
	}

	public boolean pairingStarted() {
		return session().pairing != null;
	}

	public boolean pairingNotStarted() {
		return !pairingStarted();
	}
}
