package home;

import java.util.Map;

/**
 * A saved lighting scene: for each light, the state it should be put in.
 * Scenes live in our own JSON store rather than on the hub, so we're free
 * to shape them however we like.
 */

public record Scene(
		String name,
		String slug,
		Map<String, LightState> states ) {

	public record LightState(
			Boolean isOn,
			Integer lightLevel,
			Integer colorTemperature,
			Double colorHue,
			Double colorSaturation ) {}

	public int lightCount() {
		return states != null ? states.size() : 0;
	}
}
