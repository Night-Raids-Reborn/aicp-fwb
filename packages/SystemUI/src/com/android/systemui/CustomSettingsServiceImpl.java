/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package com.android.systemui;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.util.ArrayUtils;
import com.android.systemui.DemoMode;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.util.leak.LeakDetector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;


/**
 */
@Singleton
public class CustomSettingsServiceImpl extends CustomSettingsService {

    private final Observer mObserver = new Observer();
    // Map of Uris we listen on to their settings keys.
    private final ArrayMap<Uri, String> mListeningUris = new ArrayMap<>();
    // Map of settings keys to the listener.
    private final HashMap<String, Set<CustomSettingsObserver>> mObserverLookup = new HashMap<>();
    private final HashSet<String> mStringSettings = new HashSet<>();
    private final HashSet<String> mIntSettings = new HashSet<>();
    private final Context mContext;
    private ContentResolver mContentResolver;
    private int mCurrentUser;
    private CurrentUserTracker mUserTracker;

    /**
     */
    @Inject
    public CustomSettingsServiceImpl(Context context, @Background Handler bgHandler) {
        mContext = context;
        mContentResolver = mContext.getContentResolver();

        mCurrentUser = ActivityManager.getCurrentUser();
        BroadcastDispatcher broadcastDispatcher = Dependency.get(BroadcastDispatcher.class);
        mUserTracker = new CurrentUserTracker(broadcastDispatcher) {
            @Override
            public void onUserSwitched(int newUserId) {
                mCurrentUser = newUserId;
                reloadAll();
                reregisterAll();
            }
        };
        mUserTracker.startTracking();
    }

    @Override
    public void destroy() {
        mUserTracker.stopTracking();
    }

    @Override
    public void addStringObserver(CustomSettingsObserver observer, String... keys) {
        for (String key : keys) {
            addStringObserver(observer, key);
        }
    }

    @Override
    public void addIntObserver(CustomSettingsObserver observer, String... keys) {
        for (String key : keys) {
            addIntObserver(observer, key);
        }
    }

    private void addObserver(CustomSettingsObserver observer, String key) {
        if (!mObserverLookup.containsKey(key)) {
            mObserverLookup.put(key, new ArraySet<CustomSettingsObserver>());
        }
        if (!mObserverLookup.get(key).contains(observer)) {
            mObserverLookup.get(key).add(observer);
        }

        Uri uri = Settings.System.getUriFor(key);
        if (!mListeningUris.containsKey(uri)) {
            mListeningUris.put(uri, key);
            mContentResolver.registerContentObserver(uri, false, mObserver, mCurrentUser);
        }
    }

    private void addStringObserver(CustomSettingsObserver observer, String key) {
        mStringSettings.add(key);
        addObserver(observer, key);
        // Send the first state.
        String value = Settings.System.getStringForUser(mContentResolver, key, mCurrentUser);
        observer.onStringSettingChanged(key, value);
    }

    private void addIntObserver(CustomSettingsObserver observer, String key) {
        mIntSettings.add(key);
        addObserver(observer, key);
        // Send the first state.
        try {
            Integer value = Settings.System.getIntForUser(mContentResolver, key, mCurrentUser);
            observer.onIntSettingChanged(key, value);
        } catch(Settings.SettingNotFoundException e) {
        }
    }

    @Override
    public void removeObserver(CustomSettingsObserver observer) {
        for (Set<CustomSettingsObserver> list : mObserverLookup.values()) {
            list.remove(observer);
        }
    }

    protected void reregisterAll() {
        if (mListeningUris.size() == 0) {
            return;
        }
        mContentResolver.unregisterContentObserver(mObserver);
        for (Uri uri : mListeningUris.keySet()) {
            mContentResolver.registerContentObserver(uri, false, mObserver, mCurrentUser);
        }
    }

    private void reloadSetting(Uri uri) {
        String key = mListeningUris.get(uri);
        Set<CustomSettingsObserver> observers = mObserverLookup.get(key);
        if (observers == null) {
            return;
        }
        if (mStringSettings.contains(key)) {
            String value = Settings.System.getStringForUser(mContentResolver, key, mCurrentUser);
            for (CustomSettingsObserver observer : observers) {
                observer.onStringSettingChanged(key, value);
            }
        }
        if (mIntSettings.contains(key)) {
            try {
                Integer value = Settings.System.getIntForUser(mContentResolver, key, mCurrentUser);
                for (CustomSettingsObserver observer : observers) {
                    observer.onIntSettingChanged(key, value);
                }
            } catch(Settings.SettingNotFoundException e) {
            }
        }
    }

    private void reloadAll() {
        for (String key : mObserverLookup.keySet()) {
            if (mStringSettings.contains(key)) {
                String value = Settings.System.getStringForUser(mContentResolver, key, mCurrentUser);
                for (CustomSettingsObserver observer : mObserverLookup.get(key)) {
                    observer.onStringSettingChanged(key, value);
                }
            }
            if (mIntSettings.contains(key)) {
                try {
                    Integer value = Settings.System.getIntForUser(mContentResolver, key, mCurrentUser);
                    for (CustomSettingsObserver observer : mObserverLookup.get(key)) {
                        observer.onIntSettingChanged(key, value);
                    }
                } catch(Settings.SettingNotFoundException e) {
                }
            }
        }
    }

    private class Observer extends ContentObserver {
        public Observer() {
            super(new Handler(Looper.getMainLooper()));
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (userId == ActivityManager.getCurrentUser()) {
                reloadSetting(uri);
            }
        }
    }
}
