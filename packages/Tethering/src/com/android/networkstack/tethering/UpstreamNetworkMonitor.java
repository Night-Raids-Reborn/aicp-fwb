/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.networkstack.tethering;

import static android.net.ConnectivityManager.TYPE_BLUETOOTH;
import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_MOBILE_DUN;
import static android.net.ConnectivityManager.TYPE_MOBILE_HIPRI;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.NetworkCapabilities.NET_CAPABILITY_DUN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.util.PrefixUtils;
import android.net.util.SharedLog;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.StateMachine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;


/**
 * A class to centralize all the network and link properties information
 * pertaining to the current and any potential upstream network.
 *
 * The owner of UNM gets it to register network callbacks by calling the
 * following methods :
 * Calling #startTrackDefaultNetwork() to track the system default network.
 * Calling #startObserveAllNetworks() to observe all networks. Listening all
 * networks is necessary while the expression of preferred upstreams remains
 * a list of legacy connectivity types.  In future, this can be revisited.
 * Calling #registerMobileNetworkRequest() to bring up mobile DUN/HIPRI network.
 *
 * The methods and data members of this class are only to be accessed and
 * modified from the tethering master state machine thread. Any other
 * access semantics would necessitate the addition of locking.
 *
 * TODO: Move upstream selection logic here.
 *
 * All callback methods are run on the same thread as the specified target
 * state machine.  This class does not require locking when accessed from this
 * thread.  Access from other threads is not advised.
 *
 * @hide
 */
public class UpstreamNetworkMonitor {
    private static final String TAG = UpstreamNetworkMonitor.class.getSimpleName();
    private static final boolean DBG = false;
    private static final boolean VDBG = false;

    // Copied from core/java/android/provider/Settings.java
    private static final String TETHERING_ALLOW_VPN_UPSTREAMS = "tethering_allow_vpn_upstreams";

    public static final int EVENT_ON_CAPABILITIES   = 1;
    public static final int EVENT_ON_LINKPROPERTIES = 2;
    public static final int EVENT_ON_LOST           = 3;
    public static final int NOTIFY_LOCAL_PREFIXES   = 10;
    // This value is used by deprecated preferredUpstreamIfaceTypes selection which is default
    // disabled.
    @VisibleForTesting
    public static final int TYPE_NONE = -1;

    private static final int CALLBACK_LISTEN_ALL = 1;
    private static final int CALLBACK_DEFAULT_INTERNET = 2;
    private static final int CALLBACK_MOBILE_REQUEST = 3;

    private static final SparseIntArray sLegacyTypeToTransport = new SparseIntArray();
    static {
        sLegacyTypeToTransport.put(TYPE_MOBILE,       NetworkCapabilities.TRANSPORT_CELLULAR);
        sLegacyTypeToTransport.put(TYPE_MOBILE_DUN,   NetworkCapabilities.TRANSPORT_CELLULAR);
        sLegacyTypeToTransport.put(TYPE_MOBILE_HIPRI, NetworkCapabilities.TRANSPORT_CELLULAR);
        sLegacyTypeToTransport.put(TYPE_WIFI,         NetworkCapabilities.TRANSPORT_WIFI);
        sLegacyTypeToTransport.put(TYPE_BLUETOOTH,    NetworkCapabilities.TRANSPORT_BLUETOOTH);
        sLegacyTypeToTransport.put(TYPE_ETHERNET,     NetworkCapabilities.TRANSPORT_ETHERNET);
    }

    private final Context mContext;
    private final SharedLog mLog;
    private final StateMachine mTarget;
    private final Handler mHandler;
    private final int mWhat;
    private final HashMap<Network, UpstreamNetworkState> mNetworkMap = new HashMap<>();
    private HashSet<IpPrefix> mLocalPrefixes;
    private ConnectivityManager mCM;
    private EntitlementManager mEntitlementMgr;
    private NetworkCallback mListenAllCallback;
    private NetworkCallback mDefaultNetworkCallback;
    private NetworkCallback mMobileNetworkCallback;
    private boolean mDunRequired;
    // Whether the current default upstream is mobile or not.
    private boolean mIsDefaultCellularUpstream;
    // The current system default network (not really used yet).
    private Network mDefaultInternetNetwork;
    // The current upstream network used for tethering.
    private Network mTetheringUpstreamNetwork;

    // Set if the Internet is considered reachable via a VPN network
    private Network mVpnInternetNetwork;

    public UpstreamNetworkMonitor(Context ctx, StateMachine tgt, SharedLog log, int what) {
        mContext = ctx;
        mTarget = tgt;
        mHandler = mTarget.getHandler();
        mLog = log.forSubComponent(TAG);
        mWhat = what;
        mLocalPrefixes = new HashSet<>();
        mIsDefaultCellularUpstream = false;
    }

    @VisibleForTesting
    public UpstreamNetworkMonitor(
            ConnectivityManager cm, StateMachine tgt, SharedLog log, int what) {
        this((Context) null, tgt, log, what);
        mCM = cm;
    }

    /**
     * Tracking the system default network. This method should be called when system is ready.
     *
     * @param defaultNetworkRequest should be the same as ConnectivityService default request
     * @param entitle a EntitlementManager object to communicate between EntitlementManager and
     * UpstreamNetworkMonitor
     */
    public void startTrackDefaultNetwork(NetworkRequest defaultNetworkRequest,
            EntitlementManager entitle) {

        // defaultNetworkRequest is not really a "request", just a way of tracking the system
        // default network. It's guaranteed not to actually bring up any networks because it's
        // the should be the same request as the ConnectivityService default request, and thus
        // shares fate with it. We can't use registerDefaultNetworkCallback because it will not
        // track the system default network if there is a VPN that applies to our UID.
        if (mDefaultNetworkCallback == null) {
            mDefaultNetworkCallback = new UpstreamNetworkCallback(CALLBACK_DEFAULT_INTERNET);
            cm().requestNetwork(defaultNetworkRequest, mDefaultNetworkCallback, mHandler);
        }
        if (mEntitlementMgr == null) {
            mEntitlementMgr = entitle;
        }
    }

    /** Listen all networks. */
    public void startObserveAllNetworks() {
        stop();

        final NetworkRequest listenAllRequest = new NetworkRequest.Builder()
                .clearCapabilities().build();
        mListenAllCallback = new UpstreamNetworkCallback(CALLBACK_LISTEN_ALL);
        cm().registerNetworkCallback(listenAllRequest, mListenAllCallback, mHandler);
    }

    /**
     * Stop tracking candidate tethering upstreams and release mobile network request.
     * Note: this function is used when tethering is stopped because tethering do not need to
     * choose upstream anymore. But it would not stop default network tracking because
     * EntitlementManager may need to know default network to decide whether to request entitlement
     * check even tethering is not active yet.
     */
    public void stop() {
        releaseMobileNetworkRequest();

        mVpnInternetNetwork = null;

        releaseCallback(mListenAllCallback);
        mListenAllCallback = null;

        mTetheringUpstreamNetwork = null;
        mNetworkMap.clear();
    }

    /** Setup or teardown DUN connection according to |dunRequired|. */
    public void updateMobileRequiresDun(boolean dunRequired) {
        final boolean valueChanged = (mDunRequired != dunRequired);
        mDunRequired = dunRequired;
        if (valueChanged && mobileNetworkRequested()) {
            releaseMobileNetworkRequest();
            registerMobileNetworkRequest();
        }
    }

    /** Whether mobile network is requested. */
    public boolean mobileNetworkRequested() {
        return (mMobileNetworkCallback != null);
    }

    /** Request mobile network if mobile upstream is permitted. */
    public void registerMobileNetworkRequest() {
        if (!isCellularUpstreamPermitted()) {
            mLog.i("registerMobileNetworkRequest() is not permitted");
            releaseMobileNetworkRequest();
            return;
        }
        if (mMobileNetworkCallback != null) {
            mLog.e("registerMobileNetworkRequest() already registered");
            return;
        }

        final NetworkRequest mobileUpstreamRequest;
        if (mDunRequired) {
            mobileUpstreamRequest = new NetworkRequest.Builder()
                    .addCapability(NET_CAPABILITY_DUN)
                    .removeCapability(NET_CAPABILITY_NOT_RESTRICTED)
                    .addTransportType(TRANSPORT_CELLULAR).build();
        } else {
            mobileUpstreamRequest = new NetworkRequest.Builder()
                    .addCapability(NET_CAPABILITY_INTERNET)
                    .addTransportType(TRANSPORT_CELLULAR).build();
        }

        // The existing default network and DUN callbacks will be notified.
        // Therefore, to avoid duplicate notifications, we only register a no-op.
        mMobileNetworkCallback = new UpstreamNetworkCallback(CALLBACK_MOBILE_REQUEST);

        // The following use of the legacy type system cannot be removed until
        // upstream selection no longer finds networks by legacy type.
        // See also http://b/34364553 .
        final int legacyType = mDunRequired ? TYPE_MOBILE_DUN : TYPE_MOBILE_HIPRI;

        // TODO: Change the timeout from 0 (no onUnavailable callback) to some
        // moderate callback timeout. This might be useful for updating some UI.
        // Additionally, we log a message to aid in any subsequent debugging.
        mLog.i("requesting mobile upstream network: " + mobileUpstreamRequest);

        cm().requestNetwork(mobileUpstreamRequest, 0, legacyType, mHandler,
                mMobileNetworkCallback);
    }

    /** Release mobile network request. */
    public void releaseMobileNetworkRequest() {
        if (mMobileNetworkCallback == null) return;

        cm().unregisterNetworkCallback(mMobileNetworkCallback);
        mMobileNetworkCallback = null;
    }

    // So many TODOs here, but chief among them is: make this functionality an
    // integral part of this class such that whenever a higher priority network
    // becomes available and useful we (a) file a request to keep it up as
    // necessary and (b) change all upstream tracking state accordingly (by
    // passing LinkProperties up to Tethering).
    /**
     * Select the first available network from |perferredTypes|.
     */
    public UpstreamNetworkState selectPreferredUpstreamType(Iterable<Integer> preferredTypes) {
        final TypeStatePair typeStatePair = findFirstAvailableUpstreamByType(
                mNetworkMap.values(), preferredTypes, isCellularUpstreamPermitted());

        mLog.log("preferred upstream type: " + typeStatePair.type);

        switch (typeStatePair.type) {
            case TYPE_MOBILE_DUN:
            case TYPE_MOBILE_HIPRI:
                // Tethering just selected mobile upstream in spite of the default network being
                // not mobile. This can happen because of the priority list.
                // Notify EntitlementManager to check permission for using mobile upstream.
                if (!mIsDefaultCellularUpstream) {
                    mEntitlementMgr.maybeRunProvisioning();
                }
                // If we're on DUN, put our own grab on it.
                registerMobileNetworkRequest();
                break;
            case TYPE_NONE:
                // If we found NONE and mobile upstream is permitted we don't want to do this
                // as we want any previous requests to keep trying to bring up something we can use.
                if (!isCellularUpstreamPermitted()) releaseMobileNetworkRequest();
                break;
            default:
                // If we've found an active upstream connection that's not DUN/HIPRI
                // we should stop any outstanding DUN/HIPRI requests.
                releaseMobileNetworkRequest();
                break;
        }

        return typeStatePair.ns;
    }

    /**
     * Get current preferred upstream network. If default network is cellular and DUN is required,
     * preferred upstream would be DUN otherwise preferred upstream is the same as default network.
     * Returns null if no current upstream is available.
     */
    public UpstreamNetworkState getCurrentPreferredUpstream() {
        // Use VPN upstreams if hotspot settings allow.
        if (mVpnInternetNetwork != null &&
                Settings.Secure.getInt(mContext.getContentResolver(),
                        TETHERING_ALLOW_VPN_UPSTREAMS, 0) == 1) {
            return mNetworkMap.get(mVpnInternetNetwork);
        }

        final UpstreamNetworkState dfltState = (mDefaultInternetNetwork != null)
                ? mNetworkMap.get(mDefaultInternetNetwork)
                : null;
        if (isNetworkUsableAndNotCellular(dfltState)) return dfltState;

        if (!isCellularUpstreamPermitted()) return null;

        if (!mDunRequired) return dfltState;

        // Find a DUN network. Note that code in Tethering causes a DUN request
        // to be filed, but this might be moved into this class in future.
        return findFirstDunNetwork(mNetworkMap.values());
    }

    /** Tell UpstreamNetworkMonitor which network is the current upstream of tethering. */
    public void setCurrentUpstream(Network upstream) {
        mTetheringUpstreamNetwork = upstream;
    }

    /** Return local prefixes. */
    public Set<IpPrefix> getLocalPrefixes() {
        return (Set<IpPrefix>) mLocalPrefixes.clone();
    }

    private boolean isCellularUpstreamPermitted() {
        if (mEntitlementMgr != null) {
            return mEntitlementMgr.isCellularUpstreamPermitted();
        } else {
            // This flow should only happens in testing.
            return true;
        }
    }

    private void handleAvailable(Network network) {
        if (mNetworkMap.containsKey(network)) return;

        if (VDBG) Log.d(TAG, "onAvailable for " + network);
        mNetworkMap.put(network, new UpstreamNetworkState(null, null, network));
    }

    private void handleNetCap(Network network, NetworkCapabilities newNc) {
        if (isVpnInternetNetwork(newNc)) mVpnInternetNetwork = network;

        final UpstreamNetworkState prev = mNetworkMap.get(network);
        if (prev == null || newNc.equals(prev.networkCapabilities)) {
            // Ignore notifications about networks for which we have not yet
            // received onAvailable() (should never happen) and any duplicate
            // notifications (e.g. matching more than one of our callbacks).
            return;
        }

        if (VDBG) {
            Log.d(TAG, String.format("EVENT_ON_CAPABILITIES for %s: %s",
                    network, newNc));
        }

        mNetworkMap.put(network, new UpstreamNetworkState(
                prev.linkProperties, newNc, network));
        // TODO: If sufficient information is available to select a more
        // preferable upstream, do so now and notify the target.
        notifyTarget(EVENT_ON_CAPABILITIES, network);
    }

    private void handleLinkProp(Network network, LinkProperties newLp) {
        final UpstreamNetworkState prev = mNetworkMap.get(network);
        if (prev == null || newLp.equals(prev.linkProperties)) {
            // Ignore notifications about networks for which we have not yet
            // received onAvailable() (should never happen) and any duplicate
            // notifications (e.g. matching more than one of our callbacks).
            return;
        }

        if (VDBG) {
            Log.d(TAG, String.format("EVENT_ON_LINKPROPERTIES for %s: %s",
                    network, newLp));
        }

        mNetworkMap.put(network, new UpstreamNetworkState(
                newLp, prev.networkCapabilities, network));
        // TODO: If sufficient information is available to select a more
        // preferable upstream, do so now and notify the target.
        notifyTarget(EVENT_ON_LINKPROPERTIES, network);
    }

    private void handleLost(Network network) {
        // There are few TODOs within ConnectivityService's rematching code
        // pertaining to spurious onLost() notifications.
        //
        // TODO: simplify this, probably if favor of code that:
        //     - selects a new upstream if mTetheringUpstreamNetwork has
        //       been lost (by any callback)
        //     - deletes the entry from the map only when the LISTEN_ALL
        //       callback gets notified.

        if (network.equals(mVpnInternetNetwork)) {
            mVpnInternetNetwork = null;
        }

        if (!mNetworkMap.containsKey(network)) {
            // Ignore loss of networks about which we had not previously
            // learned any information or for which we have already processed
            // an onLost() notification.
            return;
        }

        if (VDBG) Log.d(TAG, "EVENT_ON_LOST for " + network);

        // TODO: If sufficient information is available to select a more
        // preferable upstream, do so now and notify the target.  Likewise,
        // if the current upstream network is gone, notify the target of the
        // fact that we now have no upstream at all.
        notifyTarget(EVENT_ON_LOST, mNetworkMap.remove(network));
    }

    private void recomputeLocalPrefixes() {
        final HashSet<IpPrefix> localPrefixes = allLocalPrefixes(mNetworkMap.values());
        if (!mLocalPrefixes.equals(localPrefixes)) {
            mLocalPrefixes = localPrefixes;
            notifyTarget(NOTIFY_LOCAL_PREFIXES, localPrefixes.clone());
        }
    }

    // Fetch (and cache) a ConnectivityManager only if and when we need one.
    private ConnectivityManager cm() {
        if (mCM == null) {
            // MUST call the String variant to be able to write unittests.
            mCM = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        }
        return mCM;
    }

    /**
     * A NetworkCallback class that handles information of interest directly
     * in the thread on which it is invoked. To avoid locking, this MUST be
     * run on the same thread as the target state machine's handler.
     */
    private class UpstreamNetworkCallback extends NetworkCallback {
        private final int mCallbackType;

        UpstreamNetworkCallback(int callbackType) {
            mCallbackType = callbackType;
        }

        @Override
        public void onAvailable(Network network) {
            handleAvailable(network);
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities newNc) {
            if (mCallbackType == CALLBACK_DEFAULT_INTERNET) {
                mDefaultInternetNetwork = network;
                final boolean newIsCellular = isCellular(newNc);
                if (mIsDefaultCellularUpstream != newIsCellular) {
                    mIsDefaultCellularUpstream = newIsCellular;
                    mEntitlementMgr.notifyUpstream(newIsCellular);
                }
                return;
            }

            handleNetCap(network, newNc);
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties newLp) {
            if (mCallbackType == CALLBACK_DEFAULT_INTERNET) return;

            handleLinkProp(network, newLp);
            // Any non-LISTEN_ALL callback will necessarily concern a network that will
            // also match the LISTEN_ALL callback by construction of the LISTEN_ALL callback.
            // So it's not useful to do this work for non-LISTEN_ALL callbacks.
            if (mCallbackType == CALLBACK_LISTEN_ALL) {
                recomputeLocalPrefixes();
            }
        }

        @Override
        public void onLost(Network network) {
            if (mCallbackType == CALLBACK_DEFAULT_INTERNET) {
                mDefaultInternetNetwork = null;
                mIsDefaultCellularUpstream = false;
                mEntitlementMgr.notifyUpstream(false);
                return;
            }

            handleLost(network);
            // Any non-LISTEN_ALL callback will necessarily concern a network that will
            // also match the LISTEN_ALL callback by construction of the LISTEN_ALL callback.
            // So it's not useful to do this work for non-LISTEN_ALL callbacks.
            if (mCallbackType == CALLBACK_LISTEN_ALL) {
                recomputeLocalPrefixes();
            }
        }
    }

    private void releaseCallback(NetworkCallback cb) {
        if (cb != null) cm().unregisterNetworkCallback(cb);
    }

    private void notifyTarget(int which, Network network) {
        notifyTarget(which, mNetworkMap.get(network));
    }

    private void notifyTarget(int which, Object obj) {
        mTarget.sendMessage(mWhat, which, 0, obj);
    }

    private static class TypeStatePair {
        public int type = TYPE_NONE;
        public UpstreamNetworkState ns = null;
    }

    private static TypeStatePair findFirstAvailableUpstreamByType(
            Iterable<UpstreamNetworkState> netStates, Iterable<Integer> preferredTypes,
            boolean isCellularUpstreamPermitted) {
        final TypeStatePair result = new TypeStatePair();

        for (int type : preferredTypes) {
            NetworkCapabilities nc;
            try {
                nc = networkCapabilitiesForType(type);
            } catch (IllegalArgumentException iae) {
                Log.e(TAG, "No NetworkCapabilities mapping for legacy type: " + type);
                continue;
            }
            if (!isCellularUpstreamPermitted && isCellular(nc)) {
                continue;
            }

            for (UpstreamNetworkState value : netStates) {
                if (!nc.satisfiedByNetworkCapabilities(value.networkCapabilities)) {
                    continue;
                }

                result.type = type;
                result.ns = value;
                return result;
            }
        }

        return result;
    }

    private static HashSet<IpPrefix> allLocalPrefixes(Iterable<UpstreamNetworkState> netStates) {
        final HashSet<IpPrefix> prefixSet = new HashSet<>();

        for (UpstreamNetworkState ns : netStates) {
            final LinkProperties lp = ns.linkProperties;
            if (lp == null) continue;
            prefixSet.addAll(PrefixUtils.localPrefixesFrom(lp));
        }

        return prefixSet;
    }

    private static boolean isCellular(UpstreamNetworkState ns) {
        return (ns != null) && isCellular(ns.networkCapabilities);
    }

    private static boolean isCellular(NetworkCapabilities nc) {
        return (nc != null) && nc.hasTransport(TRANSPORT_CELLULAR)
               && nc.hasCapability(NET_CAPABILITY_NOT_VPN);
    }

    private static boolean hasCapability(UpstreamNetworkState ns, int netCap) {
        return (ns != null) && (ns.networkCapabilities != null)
               && ns.networkCapabilities.hasCapability(netCap);
    }

    private static boolean isNetworkUsableAndNotCellular(UpstreamNetworkState ns) {
        return (ns != null) && (ns.networkCapabilities != null) && (ns.linkProperties != null)
               && !isCellular(ns.networkCapabilities);
    }

    private static boolean isVpnInternetNetwork(NetworkCapabilities nc) {
        return (nc != null) && !nc.hasCapability(NET_CAPABILITY_NOT_VPN) &&
               nc.hasCapability(NET_CAPABILITY_INTERNET);
    }

    private static UpstreamNetworkState findFirstDunNetwork(
            Iterable<UpstreamNetworkState> netStates) {
        for (UpstreamNetworkState ns : netStates) {
            if (isCellular(ns) && hasCapability(ns, NET_CAPABILITY_DUN)) return ns;
        }

        return null;
    }

    /**
     * Given a legacy type (TYPE_WIFI, ...) returns the corresponding NetworkCapabilities instance.
     * This function is used for deprecated legacy type and be disabled by default.
     */
    @VisibleForTesting
    public static NetworkCapabilities networkCapabilitiesForType(int type) {
        final NetworkCapabilities.Builder builder = new NetworkCapabilities.Builder();

        // Map from type to transports.
        final int notFound = -1;
        final int transport = sLegacyTypeToTransport.get(type, notFound);
        if (transport == notFound) {
            throw new IllegalArgumentException("unknown legacy type: " + type);
        }
        builder.addTransportType(transport);

        if (type == TYPE_MOBILE_DUN) {
            builder.addCapability(NetworkCapabilities.NET_CAPABILITY_DUN);
            // DUN is restricted network, see NetworkCapabilities#FORCE_RESTRICTED_CAPABILITIES.
            builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        } else {
            builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        return builder.build();
    }
}
