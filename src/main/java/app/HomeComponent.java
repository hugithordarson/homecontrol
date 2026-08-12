package app;

import com.webobjects.appserver.WOContext;
import com.webobjects.appserver.WOResponse;

import er.extensions.components.ERXComponent;

public abstract class HomeComponent extends ERXComponent {

	private String _error;
	private boolean _errorPopped;

	public HomeComponent( WOContext context ) {
		super( context );
	}

	/**
	 * Component actions return this instead of a page, so the browser lands on a
	 * clean GET URL and a reload doesn't offer to resend the form (post/redirect/get).
	 */
	protected WOResponse redirectTo( final String path ) {
		final WOResponse response = new WOResponse();
		response.setStatus( 302 );
		response.setHeader( path, "location" );
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
