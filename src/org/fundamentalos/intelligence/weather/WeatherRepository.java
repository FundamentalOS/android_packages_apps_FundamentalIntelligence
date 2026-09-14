/*
 * FundamentalOS — FundamentalIntelligence
 *
 * Binds the FundamentalOS weather app's IWeatherProvider, caches the latest
 * weather snapshot Bundle, and notifies a listener whenever it changes so the
 * SmartspaceService can re-publish its weather target.
 *
 * The transport is a plain android.os.Bundle (see IWeatherProvider for the key
 * contract) — no custom parcelable is shared across the process boundary.
 */
package org.fundamentalos.intelligence.weather;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import org.fundamentalos.weather.ipc.IWeatherCallback;
import org.fundamentalos.weather.ipc.IWeatherProvider;

public class WeatherRepository {

    private static final String TAG = "FundamentalWeatherRepo";

    /** Package + action of the weather app service we bind to. */
    public static final String WEATHER_PACKAGE = "org.fundamentalos.weather";
    public static final String WEATHER_ACTION = "org.fundamentalos.weather.ipc.IWeatherProvider";

    private static final long RECONNECT_DELAY_MS = 5_000L;

    /** Notified (on the main thread) whenever the cached snapshot changes. */
    public interface Listener {
        void onWeatherChanged();
    }

    private final Context mContext;
    private final Listener mListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private final Object mLock = new Object();
    private Bundle mCurrent;                 // last known snapshot, may be null
    private IWeatherProvider mProvider;      // non-null while connected
    private boolean mBound;                  // bindService() succeeded and not yet unbound
    private boolean mStarted;                // start() called, stop() not yet

    public WeatherRepository(Context context, Listener listener) {
        mContext = context.getApplicationContext();
        mListener = listener;
    }

    /** Latest snapshot Bundle, or null if nothing has arrived yet. */
    public Bundle getCurrent() {
        synchronized (mLock) {
            return mCurrent;
        }
    }

    public void start() {
        synchronized (mLock) {
            if (mStarted) return;
            mStarted = true;
        }
        bind();
    }

    public void stop() {
        synchronized (mLock) {
            if (!mStarted) return;
            mStarted = false;
        }
        mMainHandler.removeCallbacks(mReconnect);
        unbindInternal();
    }

    // --- binding -----------------------------------------------------------

    private void bind() {
        Intent intent = new Intent(WEATHER_ACTION).setPackage(WEATHER_PACKAGE);
        try {
            boolean ok = mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
            synchronized (mLock) {
                mBound = ok;
            }
            if (!ok) {
                Log.w(TAG, "bindService returned false for " + WEATHER_PACKAGE + "; will retry");
                // bindService still leaves the binding registered; release it before retry.
                unbindInternal();
                scheduleReconnect();
            } else {
                Log.i(TAG, "bindService requested for " + WEATHER_PACKAGE);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "bindService security exception (missing permission?)", e);
            scheduleReconnect();
        }
    }

    private void unbindInternal() {
        IWeatherProvider provider;
        boolean wasBound;
        synchronized (mLock) {
            provider = mProvider;
            wasBound = mBound;
            mProvider = null;
            mBound = false;
        }
        if (provider != null) {
            try {
                provider.unregisterCallback(mCallback);
            } catch (RemoteException ignored) {
                // Service already gone; nothing to unregister.
            }
        }
        if (wasBound) {
            try {
                mContext.unbindService(mConnection);
            } catch (IllegalArgumentException ignored) {
                // Not registered; safe to ignore.
            }
        }
    }

    private void scheduleReconnect() {
        synchronized (mLock) {
            if (!mStarted) return;
        }
        mMainHandler.removeCallbacks(mReconnect);
        mMainHandler.postDelayed(mReconnect, RECONNECT_DELAY_MS);
    }

    private final Runnable mReconnect = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                if (!mStarted || mProvider != null) return;
            }
            Log.i(TAG, "attempting reconnect to weather provider");
            bind();
        }
    };

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            IWeatherProvider provider = IWeatherProvider.Stub.asInterface(service);
            synchronized (mLock) {
                mProvider = provider;
            }
            Log.i(TAG, "connected to weather provider " + name);
            try {
                // registerCallback re-pushes the current value once, but also seed
                // synchronously in case that push races the initial target build.
                Bundle current = provider.getCurrent();
                provider.registerCallback(mCallback);
                if (current != null) {
                    cacheAndNotify(current);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "failed to register/seed weather callback", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "weather provider disconnected " + name);
            synchronized (mLock) {
                mProvider = null;
            }
            scheduleReconnect();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.w(TAG, "weather provider binding died " + name);
            unbindInternal();
            scheduleReconnect();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.w(TAG, "weather provider returned null binding " + name);
            // Provider present but refused to bind; back off and retry.
            unbindInternal();
            scheduleReconnect();
        }
    };

    private final IWeatherCallback mCallback = new IWeatherCallback.Stub() {
        @Override
        public void onWeatherChanged(Bundle snapshot) {
            cacheAndNotify(snapshot);
        }
    };

    private void cacheAndNotify(Bundle snapshot) {
        if (snapshot != null) {
            // Framework parcelables (Icon, PendingIntent) live in the boot classloader;
            // set it so getParcelable() can unmarshal them on read.
            snapshot.setClassLoader(Icon.class.getClassLoader());
        }
        synchronized (mLock) {
            mCurrent = snapshot;
        }
        mMainHandler.post(() -> {
            if (mListener != null) {
                mListener.onWeatherChanged();
            }
        });
    }
}
