package home.components;

import java.util.Objects;

import ng.appserver.NGContext;

import app.HomeComponent;

public class PageWrapper extends HomeComponent {

	public PageWrapper( NGContext context ) {
		super( context );
	}

	@Override
	public boolean synchronizesVariablesWithBindings() {
		return false;
	}

	public String title() {
		return Objects.requireNonNullElse( (String)valueForBinding( "title" ), "HomeControl" );
	}

	public String mainStyle() {
		return "max-width: %s; margin: 0 auto; padding: 24px 16px".formatted( Objects.requireNonNullElse( (String)valueForBinding( "width" ), "980px" ) );
	}
}
