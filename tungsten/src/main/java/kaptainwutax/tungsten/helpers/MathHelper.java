package kaptainwutax.tungsten.helpers;

/**
 * Helper class to easily do some math.
 */
public class MathHelper {
	
	/**
     * Rounds a value to a certain precision.
     * 
     * @param value to be rounded
     * @param precision
     * @return value rounded to a certain precision.
     */
	public static double roundToPrecision(double value, int precision) {
		    double scale = Math.pow(10, precision);
		    return Math.round(value * scale);
	}
}
