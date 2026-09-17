package home;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Sunrise and sunset times (the classic Almanac for Computers algorithm, good to
 * a minute or two). At 64° north a clock time means little for lighting — the
 * sun sets at 15:30 in December and 23:50 in June — so schedules can say
 * "sunset+30" instead.
 */

public class SunCalc {

	/** Reykjavík unless overridden with -Dhomecontrol.latitude / -Dhomecontrol.longitude */
	public static final double LATITUDE = Double.parseDouble( System.getProperty( "homecontrol.latitude", "64.1466" ) );
	public static final double LONGITUDE = Double.parseDouble( System.getProperty( "homecontrol.longitude", "-21.9426" ) );

	private static final double ZENITH = 90.833;

	public static Optional<LocalTime> sunrise( final LocalDate date ) {
		return event( date, true );
	}

	public static Optional<LocalTime> sunset( final LocalDate date ) {
		return event( date, false );
	}

	private static Optional<LocalTime> event( final LocalDate date, final boolean rising ) {
		final double lngHour = LONGITUDE / 15;
		final double t = date.getDayOfYear() + ( ( rising ? 6 : 18 ) - lngHour ) / 24;

		final double m = 0.9856 * t - 3.289;
		double l = m + 1.916 * sin( m ) + 0.020 * sin( 2 * m ) + 282.634;
		l = mod( l, 360 );

		double ra = Math.toDegrees( Math.atan( 0.91764 * Math.tan( Math.toRadians( l ) ) ) );
		ra = mod( ra, 360 );
		ra += Math.floor( l / 90 ) * 90 - Math.floor( ra / 90 ) * 90;
		ra /= 15;

		final double sinDec = 0.39782 * sin( l );
		final double cosDec = Math.cos( Math.asin( sinDec ) );

		final double cosH = ( Math.cos( Math.toRadians( ZENITH ) ) - sinDec * Math.sin( Math.toRadians( LATITUDE ) ) ) / ( cosDec * Math.cos( Math.toRadians( LATITUDE ) ) );

		if( cosH > 1 || cosH < -1 ) {
			return Optional.empty(); // polar day or night — no such event today
		}

		double h = Math.toDegrees( Math.acos( cosH ) );
		h = rising ? 360 - h : h;
		h /= 15;

		final double localMeanTime = h + ra - 0.06571 * t - 6.622;
		final double ut = mod( localMeanTime - lngHour, 24 );

		final ZonedDateTime utc = date.atStartOfDay( ZoneId.of( "UTC" ) ).plusSeconds( Math.round( ut * 3600 ) );
		return Optional.of( utc.withZoneSameInstant( ZoneId.systemDefault() ).toLocalTime() );
	}

	private static double sin( final double degrees ) {
		return Math.sin( Math.toRadians( degrees ) );
	}

	private static double mod( final double value, final double m ) {
		return ( ( value % m ) + m ) % m;
	}
}
