/*
 * FundamentalOS — FundamentalIntelligence
 *
 * SmartspaceService that publishes a live weather SmartspaceTarget built from a
 * snapshot Bundle pushed by the FundamentalOS weather app (bound via
 * WeatherRepository / IWeatherProvider). Replaces ASI as the smartspace provider.
 *
 * The weather target is double-written per the verified contract:
 *   - headerAction: title = temperature text, icon = condition icon, tap = PendingIntent
 *     (read by the lockscreen smartspace weather row)
 *   - baseAction.extras: description / state / use_celsius / temperature(String)
 *     (read by clock-face weather via WeatherData.fromBundle)
 */
package org.fundamentalos.intelligence.smartspace;

import android.app.PendingIntent;
import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceConfig;
import android.app.smartspace.SmartspaceSessionId;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceTargetEvent;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Process;
import android.service.smartspace.SmartspaceService;
import android.util.Log;

import org.fundamentalos.intelligence.R;
import org.fundamentalos.intelligence.weather.WeatherRepository;

import java.util.ArrayList;
import java.util.List;

public class FundamentalSmartspaceService extends SmartspaceService
        implements WeatherRepository.Listener {

    private static final String TAG = "FundamentalSmartspace";

    private static final long TWO_HOURS_MS = 2 * 60 * 60 * 1000L;

    // Snapshot Bundle keys (see IWeatherProvider). Must stay in sync with the weather app.
    private static final String K_TEMPERATURE = "temperature";
    private static final String K_USE_CELSIUS = "useCelsius";
    private static final String K_WMO_CODE = "wmoCode";
    private static final String K_IS_DAY = "isDay";
    private static final String K_DESCRIPTION = "description";
    private static final String K_CONDITION_ICON = "conditionIcon";
    private static final String K_VALID_UNTIL = "validUntilMillis";
    private static final String K_TAP_INTENT = "tapIntent";

    // Track live sessions so we can (re)push proactively as well as on request.
    private final List<SmartspaceSessionId> mSessions = new ArrayList<>();

    private WeatherRepository mWeather;

    @Override
    public void onCreate() {
        super.onCreate();
        mWeather = new WeatherRepository(this, this);
        mWeather.start();
        Log.i(TAG, "service created; weather repository started");
    }

    @Override
    public void onDestroy() {
        if (mWeather != null) {
            mWeather.stop();
            mWeather = null;
        }
        Log.i(TAG, "service destroyed; weather repository stopped");
        super.onDestroy();
    }

    @Override
    public void onCreateSmartspaceSession(SmartspaceConfig config, SmartspaceSessionId sessionId) {
        Log.i(TAG, "onCreateSmartspaceSession id=" + sessionId
                + " uiSurface=" + (config != null ? config.getUiSurface() : "null"));
        if (!mSessions.contains(sessionId)) {
            mSessions.add(sessionId);
        }
        pushWeather(sessionId);
    }

    @Override
    public void onRequestSmartspaceUpdate(SmartspaceSessionId sessionId) {
        Log.i(TAG, "onRequestSmartspaceUpdate id=" + sessionId);
        pushWeather(sessionId);
    }

    @Override
    public void notifySmartspaceEvent(SmartspaceSessionId sessionId, SmartspaceTargetEvent event) {
        Log.i(TAG, "notifySmartspaceEvent id=" + sessionId + " event=" + event);
    }

    @Override
    public void onDestroySmartspaceSession(SmartspaceSessionId sessionId) {
        Log.i(TAG, "onDestroySmartspaceSession id=" + sessionId);
        mSessions.remove(sessionId);
    }

    @Override
    public void onDestroy(SmartspaceSessionId sessionId) {
        Log.i(TAG, "onDestroy(session) id=" + sessionId);
        mSessions.remove(sessionId);
    }

    /** Weather snapshot changed → re-publish to every live session. */
    @Override
    public void onWeatherChanged() {
        Log.i(TAG, "onWeatherChanged; re-pushing to " + mSessions.size() + " session(s)");
        for (SmartspaceSessionId sessionId : new ArrayList<>(mSessions)) {
            pushWeather(sessionId);
        }
    }

    private void pushWeather(SmartspaceSessionId sessionId) {
        try {
            SmartspaceTarget target = buildWeatherTarget();
            if (target == null) {
                // No data yet (unbound / null cache): publish an empty list rather
                // than a half-baked or stale target.
                updateSmartspaceTargets(sessionId, new ArrayList<>());
                Log.i(TAG, "no weather snapshot; pushed empty target list to " + sessionId);
                return;
            }
            updateSmartspaceTargets(sessionId, List.of(target));
            Log.i(TAG, "pushed weather target to session " + sessionId);
        } catch (Throwable t) {
            Log.e(TAG, "failed to build/push weather target", t);
        }
    }

    /** Builds the weather target from the cached snapshot, or null if none. */
    private SmartspaceTarget buildWeatherTarget() {
        final Bundle wx = mWeather != null ? mWeather.getCurrent() : null;
        if (wx == null) {
            return null;
        }

        final long now = System.currentTimeMillis();

        final double temperature = wx.getDouble(K_TEMPERATURE);
        final boolean useCelsius = wx.getBoolean(K_USE_CELSIUS, true);
        final int wmoCode = wx.getInt(K_WMO_CODE, -1);
        final boolean isDay = wx.getBoolean(K_IS_DAY, true);
        final String description = wx.getString(K_DESCRIPTION, "");
        final long validUntil = wx.getLong(K_VALID_UNTIL, 0L);

        final long tempRounded = Math.round(temperature);
        final String tempText = tempRounded + "°";

        // Condition icon from the snapshot, else fall back to the bundled test icon.
        Icon icon = wx.getParcelable(K_CONDITION_ICON, Icon.class);
        if (icon == null) {
            icon = Icon.createWithResource(getPackageName(), R.drawable.ic_weather_test);
        }

        final PendingIntent tapIntent = wx.getParcelable(K_TAP_INTENT, PendingIntent.class);

        // --- headerAction (smartspace weather row) ---
        SmartspaceAction.Builder headerBuilder =
                new SmartspaceAction.Builder("wx-header", tempText)
                        .setIcon(icon)
                        .setContentDescription(description);
        if (tapIntent != null) {
            headerBuilder.setPendingIntent(tapIntent);
        } else {
            headerBuilder.setIntent(new Intent(Intent.ACTION_MAIN)); // placeholder, never tapped
        }
        SmartspaceAction header = headerBuilder.build();

        // --- baseAction.extras (clock-face weather via WeatherData.fromBundle) ---
        // Contract: description(String), state(Int 0..31), use_celsius(Boolean),
        // temperature(String). temperature MUST be a String or the whole thing is dropped.
        Bundle extras = new Bundle();
        extras.putString("description", description);
        extras.putInt("state", wmoToState(wmoCode, isDay));
        extras.putBoolean("use_celsius", useCelsius);
        extras.putString("temperature", String.valueOf(tempRounded));

        SmartspaceAction base = new SmartspaceAction.Builder("wx-base", tempText)
                .setExtras(extras)
                .build();

        ComponentName cn = new ComponentName(this, FundamentalSmartspaceService.class);

        final long expiry = (validUntil > now) ? validUntil : (now + TWO_HOURS_MS);

        return new SmartspaceTarget.Builder("wx-v0b", cn, Process.myUserHandle())
                .setFeatureType(SmartspaceTarget.FEATURE_WEATHER)
                .setHeaderAction(header)
                .setBaseAction(base)
                .setCreationTimeMillis(now - 60_000L)
                .setExpiryTimeMillis(expiry)
                .build();
    }

    /**
     * Maps a WMO weather interpretation code (open-meteo style, 0..99) to a
     * WeatherData.WeatherStateIcon id (0..31), selecting the day/night variant
     * from isDay where one exists. Unmapped codes fall back to CLOUDY(9).
     *
     * WeatherStateIcon ids used here:
     *   1 SUNNY / 2 CLEAR_NIGHT / 3 MOSTLY_SUNNY / 4 MOSTLY_CLEAR_NIGHT
     *   5 PARTLY_CLOUDY / 6 PARTLY_CLOUDY_NIGHT / 9 CLOUDY / 10 HAZE_FOG_DUST_SMOKE
     *   11 DRIZZLE / 12 HEAVY_RAIN / 13 SHOWERS_RAIN
     *   14 SCATTERED_SHOWERS_DAY / 15 SCATTERED_SHOWERS_NIGHT
     *   16 ISOLATED_SCATTERED_TSTORMS_DAY / 17 ISOLATED_SCATTERED_TSTORMS_NIGHT
     *   18 STRONG_TSTORMS / 21 FLURRIES / 22 HEAVY_SNOW / 25 SNOW_SHOWERS_SNOW
     *   23 SCATTERED_SNOW_SHOWERS_DAY / 24 SCATTERED_SNOW_SHOWERS_NIGHT
     *   31 WINTRY_MIX_RAIN_SNOW
     */
    private static int wmoToState(int wmo, boolean isDay) {
        switch (wmo) {
            case 0:  return isDay ? 1 : 2;    // clear sky
            case 1:  return isDay ? 3 : 4;    // mainly clear
            case 2:  return isDay ? 5 : 6;    // partly cloudy
            case 3:  return 9;                // overcast -> CLOUDY
            case 45:
            case 48: return 10;               // fog / rime fog -> HAZE_FOG_DUST_SMOKE
            case 51:
            case 53:
            case 55: return 11;               // drizzle -> DRIZZLE
            case 56:
            case 57: return 31;               // freezing drizzle -> WINTRY_MIX_RAIN_SNOW
            case 61:
            case 63: return 13;               // rain slight/moderate -> SHOWERS_RAIN
            case 65: return 12;               // rain heavy -> HEAVY_RAIN
            case 66:
            case 67: return 31;               // freezing rain -> WINTRY_MIX_RAIN_SNOW
            case 71:
            case 73: return 25;               // snow slight/moderate -> SNOW_SHOWERS_SNOW
            case 75: return 22;               // snow heavy -> HEAVY_SNOW
            case 77: return 21;               // snow grains -> FLURRIES
            case 80:
            case 81: return isDay ? 14 : 15;  // rain showers slight/moderate
            case 82: return 12;               // rain showers violent -> HEAVY_RAIN
            case 85: return isDay ? 23 : 24;  // snow showers slight
            case 86: return 22;               // snow showers heavy -> HEAVY_SNOW
            case 95: return isDay ? 16 : 17;  // thunderstorm
            case 96:
            case 99: return 18;               // thunderstorm with hail -> STRONG_TSTORMS
            default: return 9;                // safe default -> CLOUDY
        }
    }
}
