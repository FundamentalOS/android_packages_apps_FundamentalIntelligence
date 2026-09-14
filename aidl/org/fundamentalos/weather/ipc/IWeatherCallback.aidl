package org.fundamentalos.weather.ipc;

/**
 * Pushed by the weather app whenever its current snapshot changes.
 * The Bundle contract (fixed keys) is documented on IWeatherProvider.
 */
oneway interface IWeatherCallback {
    void onWeatherChanged(in android.os.Bundle snapshot);
}
