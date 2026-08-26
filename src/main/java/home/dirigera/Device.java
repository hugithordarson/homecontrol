package home.dirigera;

import java.util.List;

/**
 * A device as returned by the hub's GET /v1/devices. Only the fields we actually
 * use are declared — Gson ignores the rest of the JSON.
 */

public record Device(
		String id,
		String type,
		String deviceType,
		Boolean isReachable,
		Boolean isHidden,
		Attributes attributes,
		Room room,
		Capabilities capabilities ) {

	public record Attributes(
			String customName,
			String model,
			Boolean isOn,
			Integer lightLevel,
			String colorMode,
			Integer colorTemperature,
			Integer colorTemperatureMin,
			Integer colorTemperatureMax,
			Double colorHue,
			Double colorSaturation ) {}

	public record Room(
			String id,
			String name,
			String color,
			String icon ) {}

	public record Capabilities(
			List<String> canSend,
			List<String> canReceive ) {}

	public boolean isLight() {
		return "light".equals( type );
	}

	public boolean on() {
		return attributes != null && Boolean.TRUE.equals( attributes.isOn() );
	}

	public boolean reachable() {
		return Boolean.TRUE.equals( isReachable );
	}

	public boolean hidden() {
		return Boolean.TRUE.equals( isHidden );
	}

	/**
	 * @return The user-assigned name if present, otherwise the model name
	 */
	public String name() {
		if( attributes != null && attributes.customName() != null && !attributes.customName().isBlank() ) {
			return attributes.customName();
		}

		if( attributes != null && attributes.model() != null ) {
			return attributes.model();
		}

		return deviceType;
	}

	public boolean canReceive( final String attributeName ) {
		return capabilities != null && capabilities.canReceive() != null && capabilities.canReceive().contains( attributeName );
	}

	public boolean dimmable() {
		return canReceive( "lightLevel" );
	}

	public boolean supportsColorTemperature() {
		return canReceive( "colorTemperature" );
	}

	public boolean supportsColor() {
		return canReceive( "colorHue" );
	}

	/**
	 * The hub names the temperature range in mired-think: colorTemperatureMax is the
	 * WARMEST Kelvin value (lowest number) and colorTemperatureMin the coldest.
	 * These accessors translate to what a human means.
	 */
	public int warmestTemperature() {
		return attributes != null && attributes.colorTemperatureMax() != null ? attributes.colorTemperatureMax() : 2202;
	}

	public int coldestTemperature() {
		return attributes != null && attributes.colorTemperatureMin() != null ? attributes.colorTemperatureMin() : 4000;
	}

	/**
	 * @return true if this is a color bulb currently showing a color (rather than a white temperature)
	 */
	public boolean inColorMode() {
		return supportsColor() && attributes != null && "color".equals( attributes.colorMode() );
	}

	public String roomId() {
		return room != null ? room.id() : "none";
	}

	public String roomName() {
		return room != null ? room.name() : "No room";
	}
}
