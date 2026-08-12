package app;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WORequest;

import er.extensions.appserver.ERXDirectAction;
import home.components.FrontPage;

public class DirectAction extends ERXDirectAction {

	public DirectAction( WORequest request ) {
		super( request );
	}

	@Override
	public WOActionResults defaultAction() {
		return pageWithName( FrontPage.class );
	}
}
