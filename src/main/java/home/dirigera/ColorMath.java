package home.dirigera;

/**
 * Color conversions for bulbs that speak only one dialect. The 2017-generation
 * TRADFRI color bulbs (CWS opal 600lm) have no colorTemperature capability —
 * their "white" is a color-engine impression — so a Kelvin request has to be
 * translated into the hue/saturation of that blackbody color.
 */

public class ColorMath {

	/**
	 * @return {hue 0-360, saturation 0-1} approximating the color of a blackbody
	 * at the given temperature (Tanner Helland's RGB approximation, then to HSV)
	 */
	public static double[] kelvinToHueSat( final int kelvin ) {
		final double t = Math.clamp( kelvin, 1000, 40000 ) / 100d;

		double r = t <= 66 ? 255 : 329.698727446 * Math.pow( t - 60, -0.1332047592 );
		double g = t <= 66 ? 99.4708025861 * Math.log( t ) - 161.1195681661 : 288.1221695283 * Math.pow( t - 60, -0.0755148492 );
		double b = t >= 66 ? 255 : ( t <= 19 ? 0 : 138.5177312231 * Math.log( t - 10 ) - 305.0447927307 );

		r = Math.clamp( r, 0, 255 ) / 255d;
		g = Math.clamp( g, 0, 255 ) / 255d;
		b = Math.clamp( b, 0, 255 ) / 255d;

		final double max = Math.max( r, Math.max( g, b ) );
		final double min = Math.min( r, Math.min( g, b ) );
		final double d = max - min;

		double hue = 0;

		if( d > 0 ) {
			if( max == r ) {
				hue = ( ( g - b ) / d ) % 6;
			}
			else if( max == g ) {
				hue = ( b - r ) / d + 2;
			}
			else {
				hue = ( r - g ) / d + 4;
			}

			hue *= 60;

			if( hue < 0 ) {
				hue += 360;
			}
		}

		final double saturation = max == 0 ? 0 : d / max;
		return new double[] { hue, Math.clamp( saturation, 0, 1 ) };
	}
}
