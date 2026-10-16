package home.components;

import java.util.List;

import ng.appserver.NGActionResults;
import ng.appserver.NGContext;

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

	public ScenesPage( NGContext context ) {
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

	public NGActionResults captureScene() {
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

	public NGActionResults applyCurrentScene() {
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

	/**
	 * Re-captures the current state of the lights into this scene, keeping its name —
	 * the way to "edit" a scene: arrange the lights, then update.
	 */
	public NGActionResults updateCurrentScene() {
		try {
			Scenes.captureCurrent( currentScene.name() );
			flashError( "\"%s\" updated from the current state of the lights".formatted( currentScene.name() ) );
		}
		catch( final Exception e ) {
			flashError( e.getMessage() );
		}

		return redirectTo( "/scenes" );
	}

	public String currentSceneRenameJS() {
		final String escaped = currentScene.name().replace( "\\", "\\\\" ).replace( "'", "\\'" );
		return "hc.renameScene('%s', '%s'); return false".formatted( currentScene.slug(), escaped );
	}

	public NGActionResults deleteCurrentScene() {
		Scenes.delete( currentScene.slug() );
		return redirectTo( "/scenes" );
	}
}
