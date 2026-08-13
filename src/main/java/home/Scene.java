package home;

import java.util.List;
import java.util.Map;

/**
 * A saved lighting scene: for each light, the state it should be put in.
 * Scenes live in our own JSON store rather than on the hub, so we're free
 * to shape them however we like. The hubs list records which hubs the scene's
 * lights lived on at capture time (older scenes have null — unknown), letting
 * the UI mark scenes whose location is currently out of reach.
 */

public record Scene(
		String name,
		String slug,
		List<String> hubs,
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
