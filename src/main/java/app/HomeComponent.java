package app;

import ng.appserver.NGContext;
import ng.appserver.http.NGResponse;
import ng.appserver.http.NGResponses;
import ng.appserver.templating.NGComponent;

public abstract class HomeComponent extends NGComponent {

	private String _error;
	private boolean _errorPopped;

	public HomeComponent( NGContext context ) {
		super( context );
	}

	/**
	 * Component actions return this instead of a page, so the browser lands on a
	 * clean GET URL and a reload doesn't offer to resend the form (post/redirect/get).
	 */
	protected NGResponse redirectTo( final String path ) {
		final NGResponse response = NGResponses.of( 302, "" );
		response.setHeader( "location", path );
		return response;
	}

	/**
	 * Stores an error message to be shown on the page the current action redirects to
	 */
	protected void flashError( final String message ) {
		session().setFlashError( message );
	}

	/**
	 * @return An error message flashed by the previous action, if any
	 */
	public String error() {
		if( !_errorPopped ) {
			_errorPopped = true;
			_error = session().popFlashError();
		}

		return _error;
	}

	public boolean hasError() {
		return error() != null;
	}

	@Override
	public Application application() {
		return (Application)super.application();
	}

	@Override
	public Session session() {
		return (Session)super.session();
	}
}
