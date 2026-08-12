package home.components;

import com.webobjects.appserver.WOContext;

import app.HomeComponent;

public class PageWrapper extends HomeComponent {

	public PageWrapper( WOContext context ) {
		super( context );
	}

	@Override
	public boolean synchronizesVariablesWithBindings() {
		return false;
	}

	public String title() {
		return stringValueForBinding( "title", "HomeControl" );
	}

	public String mainStyle() {
		return "max-width: %s; margin: 0 auto; padding: 24px 16px".formatted( stringValueForBinding( "width", "980px" ) );
	}
}
