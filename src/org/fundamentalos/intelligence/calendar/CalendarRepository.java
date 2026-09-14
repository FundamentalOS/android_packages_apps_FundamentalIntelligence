/*
 * FundamentalOS — FundamentalIntelligence
 *
 * Reads the next upcoming (or ongoing) calendar event through CalendarContract, for the
 * smartspace calendar card. No Google Play Services; the platform calendar provider only.
 * Requires READ_CALENDAR (pre-granted via default-permissions, since this service has no UI
 * to request a runtime permission).
 */
package org.fundamentalos.intelligence.calendar;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.util.Log;

public class CalendarRepository {

    private static final String TAG = "FundamentalCalendarRepo";

    /** How far ahead to look for the next event. */
    private static final long WINDOW_MS = 24L * 60 * 60 * 1000;

    /** A single resolved calendar instance. */
    public static final class Event {
        public final long eventId;
        public final String title;
        public final long beginMs;
        public final boolean allDay;

        Event(long eventId, String title, long beginMs, boolean allDay) {
            this.eventId = eventId;
            this.title = title;
            this.beginMs = beginMs;
            this.allDay = allDay;
        }
    }

    private final Context context;

    public CalendarRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean hasPermission() {
        return context.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * The soonest event whose end is still in the future, beginning within the look-ahead window.
     * Recurrences are expanded by querying CalendarContract.Instances over [now, now + window].
     * Returns null when there is no such event (or no permission).
     */
    public Event nextEvent() {
        if (!hasPermission()) {
            return null;
        }
        final long now = System.currentTimeMillis();

        final Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(builder, now);
        ContentUris.appendId(builder, now + WINDOW_MS);

        final String[] projection = {
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
        };
        // Not yet ended and shown on a visible calendar; soonest first.
        final String selection = CalendarContract.Instances.END + " >= ? AND "
                + CalendarContract.Instances.VISIBLE + " = 1";
        final String[] args = { String.valueOf(now) };
        final String sortOrder = CalendarContract.Instances.BEGIN + " ASC";

        try (Cursor c = context.getContentResolver()
                .query(builder.build(), projection, selection, args, sortOrder)) {
            if (c != null && c.moveToFirst()) {
                final long eventId = c.getLong(0);
                String title = c.getString(1);
                final long begin = c.getLong(2);
                final boolean allDay = c.getInt(4) != 0;
                if (title == null || title.trim().isEmpty()) {
                    title = "(No title)";
                }
                return new Event(eventId, title, begin, allDay);
            }
        } catch (Exception e) {
            Log.w(TAG, "calendar query failed", e);
        }
        return null;
    }
}
