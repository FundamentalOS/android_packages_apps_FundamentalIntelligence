/*
 * FundamentalOS — FundamentalIntelligence
 *
 * SmartspaceService that publishes live SmartspaceTargets built from on-device sources,
 * replacing ASI as the smartspace provider:
 *   - weather (FEATURE_WEATHER): a snapshot Bundle pushed by the FundamentalOS weather app
 *     (bound via WeatherRepository / IWeatherProvider), double-written per the verified contract
 *       - headerAction: title = temperature text, icon = condition icon, tap = PendingIntent
 *       - baseAction.extras: description / state / use_celsius / temperature(String)
 *   - calendar (FEATURE_CALENDAR): the next upcoming event read from CalendarContract.
 *   - date-row indicators: the next alarm (FEATURE_UPCOMING_ALARM) and Do Not Disturb, rendered
 *     as chips in SystemUI's DateSmartspaceView.
 */
package org.fundamentalos.intelligence.smartspace;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceConfig;
import android.app.smartspace.SmartspaceSessionId;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceTargetEvent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.service.smartspace.SmartspaceService;
import android.text.format.DateUtils;
import android.util.Log;

import org.fundamentalos.intelligence.R;
import org.fundamentalos.intelligence.calendar.CalendarRepository;
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
    private static final String K_TOMORROW_TEMP_MAX = "tomorrowTempMax";
    private static final String K_TOMORROW_TEMP_MIN = "tomorrowTempMin";
    private static final String K_TOMORROW_DESCRIPTION = "tomorrowDescription";
    private static final String K_TOMORROW_ICON = "tomorrowConditionIcon";

    // The Pixel weather clock hides the date row and shows the current weather itself,
    // so its at-a-glance carries tomorrow's forecast instead of the calendar.
    private static final String WEATHER_CLOCK_ID = "DIGITAL_CLOCK_WEATHER";
    private static final String CLOCK_FACE_SETTING = "lock_screen_custom_clock_face";

    // Date-row indicator contract, shared verbatim with SystemUI's DateSmartspaceView and
    // LockscreenSmartspaceController: a header-extra marks the Do-Not-Disturb target (the alarm
    // target is identified by FEATURE_UPCOMING_ALARM instead).
    static final String INDICATOR_EXTRA = "org.fundamentalos.smartspace.indicator";
    static final String INDICATOR_DND = "dnd";

    // Only surface the next alarm while it is coming up within this window.
    private static final long ALARM_WINDOW_MS = 12 * 60 * 60 * 1000L;

    // Track live sessions so we can (re)push proactively as well as on request.
    private final List<SmartspaceSessionId> mSessions = new ArrayList<>();

    // Re-push when the user switches clocks, so the weather-clock at-a-glance appears/clears.
    private final ContentObserver mClockObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    for (SmartspaceSessionId id : mSessions) {
                        pushTargets(id);
                    }
                }
            };

    private WeatherRepository mWeather;
    private CalendarRepository mCalendar;
    private AlarmManager mAlarmManager;
    private NotificationManager mNotificationManager;

    // Re-push when the next alarm or the Do-Not-Disturb state changes.
    private final BroadcastReceiver mIndicatorReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            for (SmartspaceSessionId id : new ArrayList<>(mSessions)) {
                pushTargets(id);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mWeather = new WeatherRepository(this, this);
        mWeather.start();
        mCalendar = new CalendarRepository(this);
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(CLOCK_FACE_SETTING), false, mClockObserver);
        mAlarmManager = getSystemService(AlarmManager.class);
        mNotificationManager = getSystemService(NotificationManager.class);
        IntentFilter indicatorFilter = new IntentFilter();
        indicatorFilter.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        indicatorFilter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
        registerReceiver(mIndicatorReceiver, indicatorFilter, Context.RECEIVER_NOT_EXPORTED);
        Log.i(TAG, "service created; weather repository started");
    }

    @Override
    public void onDestroy() {
        getContentResolver().unregisterContentObserver(mClockObserver);
        try {
            unregisterReceiver(mIndicatorReceiver);
        } catch (IllegalArgumentException ignored) {
            // never registered
        }
        if (mWeather != null) {
            mWeather.stop();
            mWeather = null;
        }
        mCalendar = null;
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
        pushTargets(sessionId);
    }

    @Override
    public void onRequestSmartspaceUpdate(SmartspaceSessionId sessionId) {
        Log.i(TAG, "onRequestSmartspaceUpdate id=" + sessionId);
        pushTargets(sessionId);
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
            pushTargets(sessionId);
        }
    }

    /** Build and publish every available target (weather, calendar, ...) to one session. */
    private void pushTargets(SmartspaceSessionId sessionId) {
        try {
            final List<SmartspaceTarget> targets = new ArrayList<>();
            final boolean weatherClock = isWeatherClockActive();
            final SmartspaceTarget weather = buildWeatherTarget();
            if (weatherClock) {
                // The at-a-glance card carries tomorrow's forecast; the weather target is
                // still pushed so the clock face can read the current conditions from it.
                final SmartspaceTarget tomorrow = buildTomorrowWeatherTarget();
                if (tomorrow != null) {
                    targets.add(tomorrow);
                }
                if (weather != null) {
                    targets.add(weather);
                }
            } else {
                if (weather != null) {
                    targets.add(weather);
                }
                final SmartspaceTarget calendar = buildCalendarTarget();
                if (calendar != null) {
                    targets.add(calendar);
                }
            }
            // Date-row indicators ride alongside the cards; SystemUI routes them to the date view.
            final SmartspaceTarget alarm = buildUpcomingAlarmTarget();
            if (alarm != null) {
                targets.add(alarm);
            }
            final SmartspaceTarget dnd = buildDndTarget();
            if (dnd != null) {
                targets.add(dnd);
            }
            updateSmartspaceTargets(sessionId, targets);
            Log.i(TAG, "pushed " + targets.size() + " target(s) to session " + sessionId
                    + " (weatherClock=" + weatherClock + ")");
        } catch (Throwable t) {
            Log.e(TAG, "failed to build/push targets", t);
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

    /** Builds tomorrow's forecast at-a-glance card for the weather clock, or null if none. */
    private SmartspaceTarget buildTomorrowWeatherTarget() {
        final Bundle wx = mWeather != null ? mWeather.getCurrent() : null;
        if (wx == null || !wx.containsKey(K_TOMORROW_TEMP_MAX)) {
            return null;
        }
        final int tempMax = wx.getInt(K_TOMORROW_TEMP_MAX);
        final int tempMin = wx.getInt(K_TOMORROW_TEMP_MIN);
        final String description = wx.getString(K_TOMORROW_DESCRIPTION, "");
        final long now = System.currentTimeMillis();

        Icon icon = wx.getParcelable(K_TOMORROW_ICON, Icon.class);
        if (icon == null) {
            icon = Icon.createWithResource(getPackageName(), R.drawable.ic_weather_test);
        }
        final PendingIntent tapIntent = wx.getParcelable(K_TAP_INTENT, PendingIntent.class);

        final String title = getString(R.string.smartspace_tomorrow_forecast, tempMax, tempMin);
        SmartspaceAction.Builder headerBuilder =
                new SmartspaceAction.Builder("wx-tomorrow-header", title)
                        .setIcon(icon)
                        .setContentDescription(
                                description.isEmpty() ? title : (title + ", " + description));
        if (!description.isEmpty()) {
            headerBuilder.setSubtitle(description);
        }
        if (tapIntent != null) {
            headerBuilder.setPendingIntent(tapIntent);
        } else {
            headerBuilder.setIntent(new Intent(Intent.ACTION_MAIN));
        }
        SmartspaceAction header = headerBuilder.build();

        ComponentName cn = new ComponentName(this, FundamentalSmartspaceService.class);
        return new SmartspaceTarget.Builder("wx-tomorrow-v0", cn, Process.myUserHandle())
                .setFeatureType(SmartspaceTarget.FEATURE_UNDEFINED)
                .setHeaderAction(header)
                .setCreationTimeMillis(now)
                .setExpiryTimeMillis(now + TWO_HOURS_MS)
                .build();
    }

    /**
     * Builds the next-alarm chip for the date row when an alarm is coming up within
     * {@link #ALARM_WINDOW_MS}, else null.
     */
    private SmartspaceTarget buildUpcomingAlarmTarget() {
        if (mAlarmManager == null) {
            return null;
        }
        final AlarmManager.AlarmClockInfo info = mAlarmManager.getNextAlarmClock();
        if (info == null) {
            return null;
        }
        final long now = System.currentTimeMillis();
        final long trigger = info.getTriggerTime();
        if (trigger <= now || trigger - now > ALARM_WINDOW_MS) {
            return null;
        }
        int flags = DateUtils.FORMAT_SHOW_TIME;
        if (!DateUtils.isToday(trigger)) {
            flags |= DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY;
        }
        final String timeText = DateUtils.formatDateTime(this, trigger, flags);

        final Icon icon = Icon.createWithResource(getPackageName(), R.drawable.ic_smartspace_alarm);
        SmartspaceAction header =
                new SmartspaceAction.Builder("alarm-header", timeText)
                        .setIcon(icon)
                        .setContentDescription(timeText)
                        .build();

        ComponentName cn = new ComponentName(this, FundamentalSmartspaceService.class);
        return new SmartspaceTarget.Builder("alarm-v0", cn, Process.myUserHandle())
                .setFeatureType(SmartspaceTarget.FEATURE_UPCOMING_ALARM)
                .setHeaderAction(header)
                .setCreationTimeMillis(now)
                .setExpiryTimeMillis(trigger)
                .build();
    }

    /** Builds the Do-Not-Disturb indicator for the date row while zen is on, else null. */
    private SmartspaceTarget buildDndTarget() {
        if (mNotificationManager == null) {
            return null;
        }
        final int filter = mNotificationManager.getCurrentInterruptionFilter();
        if (filter == NotificationManager.INTERRUPTION_FILTER_ALL
                || filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
            return null;
        }
        final long now = System.currentTimeMillis();
        final String desc = getString(R.string.smartspace_dnd_description);
        final Icon icon = Icon.createWithResource(getPackageName(), R.drawable.ic_smartspace_dnd);
        final Bundle extras = new Bundle();
        extras.putString(INDICATOR_EXTRA, INDICATOR_DND);
        SmartspaceAction header =
                new SmartspaceAction.Builder("dnd-header", desc)
                        .setIcon(icon)
                        .setContentDescription(desc)
                        .setExtras(extras)
                        .build();

        ComponentName cn = new ComponentName(this, FundamentalSmartspaceService.class);
        return new SmartspaceTarget.Builder("dnd-v0", cn, Process.myUserHandle())
                .setFeatureType(SmartspaceTarget.FEATURE_UNDEFINED)
                .setHeaderAction(header)
                .setCreationTimeMillis(now)
                .setExpiryTimeMillis(now + TWO_HOURS_MS)
                .build();
    }

    /** True when the lock screen is showing the Pixel weather clock. */
    private boolean isWeatherClockActive() {
        final String face = Settings.Secure.getString(getContentResolver(), CLOCK_FACE_SETTING);
        return face != null && face.contains(WEATHER_CLOCK_ID);
    }

    /** Builds the calendar target from the next upcoming event, or null if none / no permission. */
    private SmartspaceTarget buildCalendarTarget() {
        final CalendarRepository.Event event = mCalendar != null ? mCalendar.nextEvent() : null;
        if (event == null) {
            return null;
        }

        final long now = System.currentTimeMillis();
        final String timeText = formatEventTime(event);

        final Icon icon = Icon.createWithResource(getPackageName(), R.drawable.ic_smartspace_calendar);

        SmartspaceAction.Builder headerBuilder =
                new SmartspaceAction.Builder("cal-header", event.title)
                        .setIcon(icon)
                        .setContentDescription(
                                timeText.isEmpty() ? event.title : (event.title + ", " + timeText));
        if (!timeText.isEmpty()) {
            headerBuilder.setSubtitle(timeText);
        }
        headerBuilder.setPendingIntent(calendarTapIntent(event));
        SmartspaceAction header = headerBuilder.build();

        ComponentName cn = new ComponentName(this, FundamentalSmartspaceService.class);
        // Keep it around until a couple of hours after it starts, then let it drop.
        final long expiry = Math.max(event.beginMs, now) + TWO_HOURS_MS;

        return new SmartspaceTarget.Builder("cal-v0", cn, Process.myUserHandle())
                .setFeatureType(SmartspaceTarget.FEATURE_CALENDAR)
                .setHeaderAction(header)
                .setCreationTimeMillis(now)
                .setExpiryTimeMillis(expiry)
                .build();
    }

    /** A short, locale-formatted time for the event: a time today, a weekday+time otherwise. */
    private String formatEventTime(CalendarRepository.Event event) {
        if (event.allDay) {
            return DateUtils.formatDateTime(this, event.beginMs,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
        }
        int flags = DateUtils.FORMAT_SHOW_TIME;
        if (!DateUtils.isToday(event.beginMs)) {
            flags |= DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY;
        }
        return DateUtils.formatDateTime(this, event.beginMs, flags);
    }

    /** Tapping the calendar card opens the calendar app at the event's day/time. */
    private PendingIntent calendarTapIntent(CalendarRepository.Event event) {
        final Uri uri = Uri.parse("content://com.android.calendar/time/" + event.beginMs);
        final Intent intent = new Intent(Intent.ACTION_VIEW)
                .setData(uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
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
