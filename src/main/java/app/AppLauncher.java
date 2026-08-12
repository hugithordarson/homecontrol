package app;

import er.extensions.appserver.ERXApplication;

public class AppLauncher {

	public static void main( String[] argv ) {
		// The hub's certificate is self-signed for its own name — the JDK HttpClient
		// only lets us skip hostname verification globally, before first use
		System.setProperty( "jdk.internal.httpclient.disableHostnameVerification", "true" );

		ERXApplication.main( argv, Application.class );
	}
}
