package org.fundamentalos.weather.ipc;

import org.fundamentalos.weather.ipc.IWeatherCallback;

/**
 * Bound by FundamentalIntelligence to read live weather and stitch it into a
 * smartspace weather target.
 *
 * Snapshot Bundle contract (fixed keys, must stay in sync with the weather app):
 *   temperature          double      current temperature (in the unit given by useCelsius)
 *   useCelsius           boolean     true = Celsius, false = Fahrenheit
 *   wmoCode              int         WMO weather interpretation code (0..99)
 *   isDay               boolean     true = daytime (selects day/night icon variant)
 *   description          String      short human-readable condition text
 *   conditionIcon        Parcelable  android.graphics.drawable.Icon (putParcelable)
 *   locationName         String      display name of the observation location
 *   observationTimeMillis long       when the observation was taken (epoch ms)
 *   validUntilMillis     long        snapshot validity horizon (epoch ms)
 *   tapIntent            Parcelable  android.app.PendingIntent to open the weather app (putParcelable)
 */
interface IWeatherProvider {
    /** Latest snapshot, or null if none available yet. */
    android.os.Bundle getCurrent();

    /** Register for pushes. The provider re-pushes the current value once on registration. */
    void registerCallback(IWeatherCallback cb);

    void unregisterCallback(IWeatherCallback cb);
}
