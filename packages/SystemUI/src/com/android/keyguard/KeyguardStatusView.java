/*
 * Copyright (C) 2012 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.keyguard;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.omni.CurrentWeatherView;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.tuner.TunerService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.TimeZone;

public class KeyguardStatusView extends GridLayout implements
        ConfigurationController.ConfigurationListener, TunerService.Tunable {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardStatusView";
    private static final int MARQUEE_DELAY_MS = 2000;

    private final LockPatternUtils mLockPatternUtils;
    private final IActivityManager mIActivityManager;

    private LinearLayout mStatusViewContainer;
    private TextView mLogoutView;
    private KeyguardClockSwitch mClockView;
    private TextView mOwnerInfo;
    private KeyguardSliceView mKeyguardSlice;
    private View mNotificationIcons;
    private Runnable mPendingMarqueeStart;
    private Handler mHandler;

    private boolean mPulsing;
    private float mDarkAmount = 0;
    private int mTextColor;
    private CurrentWeatherView mWeatherView;
    private boolean mShowWeather;
    private boolean mOmniStyle;

    private static final String LOCKSCREEN_WEATHER_ENABLED =
            "system:" + Settings.System.OMNI_LOCKSCREEN_WEATHER_ENABLED;
    private static final String LOCKSCREEN_WEATHER_STYLE =
            "system:" + Settings.System.AICP_LOCKSCREEN_WEATHER_STYLE;
    private static final String LOCKSCREEN_CUSTOM_CLOCK_FACE =
            "secure:" + Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE;

    /**
     * Bottom margin that defines the margin between bottom of smart space and top of notification
     * icons on AOD.
     */
    private int mIconTopMargin;
    private int mIconTopMarginWithHeader;
    private boolean mShowingHeader;

    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onTimeChanged() {
            refreshTime();
        }

        @Override
        public void onTimeZoneChanged(TimeZone timeZone) {
            updateTimeZone(timeZone);
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (showing) {
                if (DEBUG) Slog.v(TAG, "refresh statusview showing:" + showing);
                refreshTime();
                updateOwnerInfo();
                updateLogoutView();
                updateWeatherView();
            }
        }

        @Override
        public void onStartedWakingUp() {
            setEnableMarquee(true);
        }

        @Override
        public void onFinishedGoingToSleep(int why) {
            setEnableMarquee(false);
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            refreshFormat();
            updateOwnerInfo();
            updateLogoutView();
            updateWeatherView();
        }

        @Override
        public void onLogoutEnabledChanged() {
            updateLogoutView();
        }
    };

    public KeyguardStatusView(Context context) {
        this(context, null, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mIActivityManager = ActivityManager.getService();
        mLockPatternUtils = new LockPatternUtils(getContext());
        mHandler = new Handler();
        final TunerService tunerService = Dependency.get(TunerService.class);
        tunerService.addTunable(this, LOCKSCREEN_WEATHER_ENABLED,
                                      LOCKSCREEN_WEATHER_STYLE,
                                      LOCKSCREEN_CUSTOM_CLOCK_FACE);
        onDensityOrFontScaleChanged();
    }

    /**
     * If we're presenting a custom clock of just the default one.
     */
    public boolean hasCustomClock() {
        return mClockView.hasCustomClock();
    }

    /**
     * Set whether or not the lock screen is showing notifications.
     */
    public void setHasVisibleNotifications(boolean hasVisibleNotifications) {
        mClockView.setHasVisibleNotifications(hasVisibleNotifications);
    }

    private void setEnableMarquee(boolean enabled) {
        if (DEBUG) Log.v(TAG, "Schedule setEnableMarquee: " + (enabled ? "Enable" : "Disable"));
        if (enabled) {
            if (mPendingMarqueeStart == null) {
                mPendingMarqueeStart = () -> {
                    setEnableMarqueeImpl(true);
                    mPendingMarqueeStart = null;
                };
                mHandler.postDelayed(mPendingMarqueeStart, MARQUEE_DELAY_MS);
            }
        } else {
            if (mPendingMarqueeStart != null) {
                mHandler.removeCallbacks(mPendingMarqueeStart);
                mPendingMarqueeStart = null;
            }
            setEnableMarqueeImpl(false);
        }
    }

    private void setEnableMarqueeImpl(boolean enabled) {
        if (DEBUG) Log.v(TAG, (enabled ? "Enable" : "Disable") + " transport text marquee");
        if (mOwnerInfo != null) mOwnerInfo.setSelected(enabled);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mStatusViewContainer = findViewById(R.id.status_view_container);
        mLogoutView = findViewById(R.id.logout);
        mNotificationIcons = findViewById(R.id.clock_notification_icon_container);
        if (mLogoutView != null) {
            mLogoutView.setOnClickListener(this::onLogoutClicked);
        }

        mClockView = findViewById(R.id.keyguard_clock_container);
        mClockView.setShowCurrentUserTime(true);
        mOwnerInfo = findViewById(R.id.owner_info);
        mKeyguardSlice = findViewById(R.id.keyguard_status_area);

        mWeatherView = (CurrentWeatherView) findViewById(R.id.weather_container);
        mTextColor = mClockView.getCurrentTextColor();

        boolean shouldMarquee = Dependency.get(KeyguardUpdateMonitor.class).isDeviceInteractive();
        setEnableMarquee(shouldMarquee);
        refreshFormat();
        updateOwnerInfo();
        updateLogoutView();
        updateDark();
        updateWeatherView();

        mKeyguardSlice.setContentChangeListener(this::onSliceContentChanged);
        onSliceContentChanged();
    }

    /**
     * Moves clock, adjusting margins when slice content changes.
     */
    private void onSliceContentChanged() {
        final boolean hasHeader = mKeyguardSlice.hasHeader();
        mClockView.setKeyguardShowingHeader(hasHeader);
        if (mShowingHeader == hasHeader) {
            return;
        }
        mShowingHeader = hasHeader;
        if (mNotificationIcons != null) {
            // Update top margin since header has appeared/disappeared.
            MarginLayoutParams params = (MarginLayoutParams) mNotificationIcons.getLayoutParams();
            params.setMargins(params.leftMargin,
                    hasHeader ? mIconTopMarginWithHeader : mIconTopMargin,
                    params.rightMargin,
                    params.bottomMargin);
            mNotificationIcons.setLayoutParams(params);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        layoutOwnerInfo();
        layoutWeatherView();
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        if (mClockView != null) {
            mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimensionPixelSize(R.dimen.widget_big_font_size));
        }
        if (mOwnerInfo != null) {
            mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimensionPixelSize(R.dimen.widget_label_font_size));
        }
        if (mWeatherView != null) {
            mWeatherView.onDensityOrFontScaleChanged();
        }
        loadBottomMargin();
    }

    public void dozeTimeTick() {
        refreshTime();
        mKeyguardSlice.refresh();
    }

    private void refreshTime() {
        mClockView.refresh();
    }

    private void updateTimeZone(TimeZone timeZone) {
        mClockView.onTimeZoneChanged(timeZone);
    }

    private void refreshFormat() {
        Patterns.update(mContext);
        mClockView.setFormat12Hour(Patterns.clockView12);
        mClockView.setFormat24Hour(Patterns.clockView24);
    }

    public int getLogoutButtonHeight() {
        if (mLogoutView == null) {
            return 0;
        }
        return mLogoutView.getVisibility() == VISIBLE ? mLogoutView.getHeight() : 0;
    }

    public float getClockTextSize() {
        return mClockView.getTextSize();
    }

    /**
     * Returns the preferred Y position of the clock.
     *
     * @param totalHeight The height available to position the clock.
     * @return Y position of clock.
     */
    public int getClockPreferredY(int totalHeight) {
        return mClockView.getPreferredY(totalHeight);
    }

    private void updateLogoutView() {
        if (mLogoutView == null) {
            return;
        }
        mLogoutView.setVisibility(shouldShowLogout() ? VISIBLE : GONE);
        // Logout button will stay in language of user 0 if we don't set that manually.
        mLogoutView.setText(mContext.getResources().getString(
                com.android.internal.R.string.global_action_logout));
    }

    private String getCurrentClockFace() {
        if (DEBUG) Log.v(TAG, "getCurrentClockFace: " + SeamlessClockSwitch.getCurrentClockFace(mContext));
        return SeamlessClockSwitch.getCurrentClockFace(mContext);
    }

    private boolean isClockLeftAligned() {
        String currentClock = getCurrentClockFace();
        return currentClock == null
                ? false
                : currentClock.contains("TypeClockController")
                || currentClock.contains("IDEClockController")
                || currentClock.contains("ShapeShiftClockController")
                || currentClock.contains("AndroidSDP3ClockController");
    }

    private boolean isWeatherProvidedByClockPlugin() {
        String currentClock = getCurrentClockFace();
        return currentClock == null
                ? false
                : (currentClock.contains("DividedLinesClockController")
                  || currentClock.contains("AndroidSClockController"));
    }

    private int getLeftPadding() {
        int leftPadding = 0;
        String currentClock = getCurrentClockFace();
        if (currentClock == null) return leftPadding;
        if (currentClock.contains("IDEClockController")) {
            leftPadding = (int) mContext.getResources()
                .getDimension(R.dimen.ide_clock_margin_start);
        } else if(currentClock.contains("TypeClockController")) {
            leftPadding = ((int) mContext.getResources()
                .getDimension(R.dimen.custom_clock_left_padding)) + 8;
        } else if(currentClock.contains("AndroidSDP3ClockController")) {
            leftPadding = ((int) mContext.getResources()
                .getDimension(R.dimen.sdp3_clock_padding_start));
        } else {
            //ssos clockFace
            leftPadding = ((int) mContext.getResources()
                .getDimension(R.dimen.ssos_clock_padding_start));
        }
        return leftPadding;
    }

    private void updateOwnerInfo() {
        if (mOwnerInfo == null) return;
        String info = mLockPatternUtils.getDeviceOwnerInfo();
        if (info == null) {
            // Use the current user owner information if enabled.
            final boolean ownerInfoEnabled = mLockPatternUtils.isOwnerInfoEnabled(
                    KeyguardUpdateMonitor.getCurrentUser());
            if (ownerInfoEnabled) {
                info = mLockPatternUtils.getOwnerInfo(KeyguardUpdateMonitor.getCurrentUser());
            }
        }
        mOwnerInfo.setText(info);
        updateDark();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Dependency.get(KeyguardUpdateMonitor.class).registerCallback(mInfoCallback);
        Dependency.get(ConfigurationController.class).addCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(KeyguardUpdateMonitor.class).removeCallback(mInfoCallback);
        Dependency.get(ConfigurationController.class).removeCallback(this);
    }

    @Override
    public void onLocaleListChanged() {
        refreshFormat();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("KeyguardStatusView:");
        pw.println("  mOwnerInfo: " + (mOwnerInfo == null
                ? "null" : mOwnerInfo.getVisibility() == VISIBLE));
        pw.println("  mPulsing: " + mPulsing);
        pw.println("  mDarkAmount: " + mDarkAmount);
        pw.println("  mTextColor: " + Integer.toHexString(mTextColor));
        if (mLogoutView != null) {
            pw.println("  logout visible: " + (mLogoutView.getVisibility() == VISIBLE));
        }
        if (mClockView != null) {
            mClockView.dump(fd, pw, args);
        }
        if (mKeyguardSlice != null) {
            mKeyguardSlice.dump(fd, pw, args);
        }
    }

    private void loadBottomMargin() {
        mIconTopMargin = getResources().getDimensionPixelSize(R.dimen.widget_vertical_padding);
        mIconTopMarginWithHeader = getResources().getDimensionPixelSize(
                R.dimen.widget_vertical_padding_with_header);
    }

    // DateFormat.getBestDateTimePattern is extremely expensive, and refresh is called often.
    // This is an optimization to ensure we only recompute the patterns when the inputs change.
    private static final class Patterns {
        static String clockView12;
        static String clockView24;
        static String cacheKey;

        static void update(Context context) {
            final Locale locale = Locale.getDefault();
            final Resources res = context.getResources();
            final String clockView12Skel = res.getString(R.string.clock_12hr_format);
            final String clockView24Skel = res.getString(R.string.clock_24hr_format);
            final String key = locale.toString() + clockView12Skel + clockView24Skel;
            if (key.equals(cacheKey)) return;

            clockView12 = DateFormat.getBestDateTimePattern(locale, clockView12Skel);
            // CLDR insists on adding an AM/PM indicator even though it wasn't in the skeleton
            // format.  The following code removes the AM/PM indicator if we didn't want it.
            if (!clockView12Skel.contains("a")) {
                clockView12 = clockView12.replaceAll("a", "").trim();
            }

            clockView24 = DateFormat.getBestDateTimePattern(locale, clockView24Skel);

            cacheKey = key;
        }
    }

    public void setDarkAmount(float darkAmount) {
        if (mDarkAmount == darkAmount) {
            return;
        }
        mDarkAmount = darkAmount;
        mClockView.setDarkAmount(darkAmount);
        updateDark();
    }

    private void updateDark() {
        boolean dark = mDarkAmount == 1;
        if (mLogoutView != null) {
            mLogoutView.setAlpha(dark ? 0 : 1);
        }

        if (mOwnerInfo != null) {
            boolean hasText = !TextUtils.isEmpty(mOwnerInfo.getText());
            mOwnerInfo.setVisibility(hasText ? VISIBLE : GONE);
            layoutOwnerInfo();
        }

        final int blendedTextColor = ColorUtils.blendARGB(mTextColor, Color.WHITE, mDarkAmount);
        mKeyguardSlice.setDarkAmount(mDarkAmount);
        mClockView.setTextColor(blendedTextColor);
        if (mWeatherView != null) {
            mWeatherView.blendARGB(mDarkAmount);
        }
    }

    private void layoutOwnerInfo() {
        if (mOwnerInfo != null && mOwnerInfo.getVisibility() != GONE) {
            // Animate owner info during wake-up transition
            mOwnerInfo.setAlpha(1f - mDarkAmount);

            float ratio = mDarkAmount;
            // Calculate how much of it we should crop in order to have a smooth transition
            int collapsed = mOwnerInfo.getTop() - mOwnerInfo.getPaddingTop();
            int expanded = mOwnerInfo.getBottom() + mOwnerInfo.getPaddingBottom();
            int toRemove = (int) ((expanded - collapsed) * ratio);
            setBottom(getMeasuredHeight() - toRemove);
            // If left aligned style clock, align the textView to start else keep it center.
            if (isClockLeftAligned()) {
                mOwnerInfo.setPaddingRelative(getLeftPadding(), 0, 0, 0);
                mOwnerInfo.setGravity(Gravity.START);
            } else {
                mOwnerInfo.setPaddingRelative(0, 0, 0, 0);
                mOwnerInfo.setGravity(Gravity.CENTER);
            }
            if (mNotificationIcons != null) {
                // We're using scrolling in order not to overload the translation which is used
                // when appearing the icons
                mNotificationIcons.setScrollY(toRemove);
            }
        } else if (mNotificationIcons != null){
            mNotificationIcons.setScrollY(0);
        }
    }

    public void setPulsing(boolean pulsing) {
        if (mPulsing == pulsing) {
            return;
        }
        mPulsing = pulsing;
    }

    private boolean shouldShowLogout() {
        return Dependency.get(KeyguardUpdateMonitor.class).isLogoutEnabled()
                && KeyguardUpdateMonitor.getCurrentUser() != UserHandle.USER_SYSTEM;
    }

    private void onLogoutClicked(View view) {
        int currentUserId = KeyguardUpdateMonitor.getCurrentUser();
        try {
            mIActivityManager.switchUser(UserHandle.USER_SYSTEM);
            mIActivityManager.stopUser(currentUserId, true /*force*/, null);
        } catch (RemoteException re) {
            Log.e(TAG, "Failed to logout user", re);
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case LOCKSCREEN_WEATHER_ENABLED:
                mShowWeather =
                        TunerService.parseIntegerSwitch(newValue, false);
                updateWeatherView();
                break;
            case LOCKSCREEN_WEATHER_STYLE:
                mOmniStyle =
                        !TunerService.parseIntegerSwitch(newValue, false);
                updateWeatherView();
                break;
            case LOCKSCREEN_CUSTOM_CLOCK_FACE:
                updateWeatherView();
                updateOwnerInfo();
                invalidate();
                break;
            default:
                break;
        }
    }

    public void updateWeatherView() {
        if (mWeatherView == null) return;
        if (mShowWeather && mOmniStyle) {
            layoutWeatherView();
            mWeatherView.setVisibility(isWeatherProvidedByClockPlugin() ? View.GONE : View.VISIBLE);
            mWeatherView.enableUpdates();
        } else if (!mShowWeather || !mOmniStyle) {
            mWeatherView.setVisibility(View.GONE);
            mWeatherView.disableUpdates();
        }
    }

    protected void layoutWeatherView() {
        // If left aligned style clock, align the weatherView to start else keep it center.
        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams)mWeatherView.getLayoutParams();
        if (isClockLeftAligned()) {
            mWeatherView.setPaddingRelative(getLeftPadding(), 0, 0, 0);
            lp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        } else {
            mWeatherView.setPaddingRelative(0, 0, 0, 0);
            lp.removeRule(RelativeLayout.ALIGN_PARENT_LEFT);
        }
    }
}
