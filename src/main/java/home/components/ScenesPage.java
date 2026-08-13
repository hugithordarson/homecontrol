package home.components;

import java.util.List;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOContext;

import app.HomeComponent;
import home.Scene;
import home.Scenes;

/**
 * Managing scenes: apply or delete existing ones, and capture the current
 * state of all lights as a new scene. Every action redirects back here
 * (post/redirect/get), with errors passed along as a session flash message.
 */

public class ScenesPage extends HomeComponent {

	public Scene currentScene;
	public String newSceneName;

	public ScenesPage( WOContext context ) {
		super( context );
	}

	public List<Scene> scenes() {
		return Scenes.all();
	}

	public boolean hasScenes() {
		return !scenes().isEmpty();
	}

	public boolean hasNoScenes() {
		return scenes().isEmpty();
	}

	public WOActionResults captureScene() {
		if( newSceneName == null || newSceneName.isBlank() ) {
			flashError( "Give the scene a name first" );
			return redirectTo( "/scenes" );
		}

		try {
			Scenes.captureCurrent( newSceneName.trim() );
		}
		catch( final Exception e ) {
			flashError( e.getMessage() );
		}

		return redirectTo( "/scenes" );
	}

	public WOActionResults applyCurrentScene() {
		try {
			final String note = Scenes.apply( currentScene.slug() );

			if( note != null ) {
				flashError( note );
			}
		}
		catch( final Exception e ) {
			flashError( e.getMessage() );
		}

		return redirectTo( "/scenes" );
	}

	public WOActionResults deleteCurrentScene() {
		Scenes.delete( currentScene.slug() );
		return redirectTo( "/scenes" );
	}
}
