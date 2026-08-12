package app;

import er.extensions.appserver.ERXSession;
import home.dirigera.DirigeraPairing;

public class Session extends ERXSession {

	/**
	 * An in-flight hub pairing (started on SetupPage), kept here so it survives
	 * the redirects between the pairing steps — along with the name the new hub
	 * should get once the pairing completes.
	 */
	public DirigeraPairing pairing;
	public String pairingName;

	private String _flashError;

	public Session() {
		setStoresIDsInCookies( true );
		setStoresIDsInURLs( false );
	}

	public void setFlashError( final String message ) {
		_flashError = message;
	}

	/**
	 * @return The stored error message, clearing it — it's displayed exactly once, on the page an action redirected to
	 */
	public String popFlashError() {
		final String error = _flashError;
		_flashError = null;
		return error;
	}

	@Override
	public String domainForIDCookies() {
		return "/";
	}
}
