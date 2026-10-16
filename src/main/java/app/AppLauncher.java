package app;

import ng.appserver.NGApplication;

public class AppLauncher {

	public static void main( String[] argv ) {
		// The hub's certificate is self-signed for its own name — the JDK HttpClient
		// only lets us skip hostname verification globally, before first use
		System.setProperty( "jdk.internal.httpclient.disableHostnameVerification", "true" );

		// Keep pooled hub connections for 10 minutes instead of the ~30s default —
		// the hub's TLS handshake is slow enough to audibly drag a lightning strike
		System.setProperty( "jdk.httpclient.keepalive.timeout", "600" );

		NGApplication.run( argv, Application.class );
	}
}
