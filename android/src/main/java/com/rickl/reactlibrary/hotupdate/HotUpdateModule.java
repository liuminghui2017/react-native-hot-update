package com.rickl.reactlibrary.hotupdate;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;

import com.facebook.react.ReactApplication;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.JSBundleLoader;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.ChoreographerCompat;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.modules.core.ReactChoreographer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HotUpdateModule extends ReactContextBaseJavaModule {

    private final ReactApplicationContext reactContext;

    private LifecycleEventListener mLifecycleEventListener = null;
    private int mMinimumBackgroundDuration = 0;

    private HotUpdate mHotUpdate;
    private SettingsManager mSettingsManager;
    private HotUpdateUpdateManager mUpdateManager;

    public HotUpdateModule(ReactApplicationContext reactContext, HotUpdate hotUpdate, HotUpdateUpdateManager hotUpdateUpdateManager, SettingsManager settingsManager) {
        super(reactContext);
        this.reactContext = reactContext;

        mHotUpdate = hotUpdate;
        mSettingsManager = settingsManager;
        mUpdateManager = hotUpdateUpdateManager;
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();

        constants.put("hotUpdateInstallModeImmediate", HotUpdateInstallMode.IMMEDIATE.getValue());
        constants.put("hotUpdateInstallModeOnNextRestart", HotUpdateInstallMode.ON_NEXT_RESTART.getValue());
        constants.put("hotUpdateInstallModeOnNextResume", HotUpdateInstallMode.ON_NEXT_RESUME.getValue());
        constants.put("hotUpdateInstallModeOnNextSuspend", HotUpdateInstallMode.ON_NEXT_SUSPEND.getValue());

        return constants;
    }

    @Override
    public String getName() {
        return "HotUpdate";
    }

    private void loadBundleLegacy() {
        final Activity currentActivity = getCurrentActivity();
        if (currentActivity == null) {
            // The currentActivity can be null if it is backgrounded / destroyed, so we simply
            // no-op to prevent any null pointer exceptions.
            return;
        }
        mHotUpdate.invalidateCurrentInstance();

        currentActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                currentActivity.recreate();
            }
        });
    }

    // Use reflection to find and set the appropriate fields on ReactInstanceManager. See #556 for a proposal for a less brittle way
    // to approach this.
    private void setJSBundle(ReactInstanceManager instanceManager, String latestJSBundleFile) throws IllegalAccessException {
        try {
            JSBundleLoader latestJSBundleLoader;
            if (latestJSBundleFile.toLowerCase().startsWith("assets://")) {
                latestJSBundleLoader = JSBundleLoader.createAssetLoader(getReactApplicationContext(), latestJSBundleFile, false);
            } else {
                latestJSBundleLoader = JSBundleLoader.createFileLoader(latestJSBundleFile);
            }

            Field bundleLoaderField = instanceManager.getClass().getDeclaredField("mBundleLoader");
            bundleLoaderField.setAccessible(true);
            bundleLoaderField.set(instanceManager, latestJSBundleLoader);
        } catch (Exception e) {
            HotUpdateUtils.log("Unable to set JSBundle - HotUpdate may not support this version of React Native");
            throw new IllegalAccessException("Could not setJSBundle");
        }
    }

    private void loadBundle() {
        clearLifecycleEventListener();
        // mHotUpdate.clearDebugCacheIfNeeded();
        try {
            // #1) Get the ReactInstanceManager instance, which is what includes the
            //     logic to reload the current React context.
            final ReactInstanceManager instanceManager = resolveInstanceManager();
            if (instanceManager == null) {
                return;
            }

            String latestJSBundleFile = mHotUpdate.getJSBundleFileInternal(mHotUpdate.getAssetsBundleFileName());

            // #2) Update the locally stored JS bundle file path
            setJSBundle(instanceManager, latestJSBundleFile);

            // #3) Get the context creation method and fire it on the UI thread (which RN enforces)
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        // We don't need to resetReactRootViews anymore 
                        // due the issue https://github.com/facebook/react-native/issues/14533
                        // has been fixed in RN 0.46.0
                        //resetReactRootViews(instanceManager);

                        instanceManager.recreateReactContextInBackground();
                        mHotUpdate.initializeUpdateAfterRestart();
                    } catch (Exception e) {
                        // The recreation method threw an unknown exception
                        // so just simply fallback to restarting the Activity (if it exists)
                        loadBundleLegacy();
                    }
                }
            });

        } catch (Exception e) {
            // Our reflection logic failed somewhere
            // so fall back to restarting the Activity (if it exists)
            loadBundleLegacy();
        }
    }

    private void clearLifecycleEventListener() {
        // Remove LifecycleEventListener to prevent infinite restart loop
        if (mLifecycleEventListener != null) {
            getReactApplicationContext().removeLifecycleEventListener(mLifecycleEventListener);
            mLifecycleEventListener = null;
        }
    }

    // Use reflection to find the ReactInstanceManager. See #556 for a proposal for a less brittle way to approach this.
    private ReactInstanceManager resolveInstanceManager() throws NoSuchFieldException, IllegalAccessException {
        ReactInstanceManager instanceManager = HotUpdate.getReactInstanceManager();
        if (instanceManager != null) {
            return instanceManager;
        }

        final Activity currentActivity = getCurrentActivity();
        if (currentActivity == null) {
            return null;
        }

        ReactApplication reactApplication = (ReactApplication) currentActivity.getApplication();
        instanceManager = reactApplication.getReactNativeHost().getReactInstanceManager();

        return instanceManager;
    }

    @ReactMethod
    public void downloadUpdate(final ReadableMap updatePackage, final boolean notifyProgress, final Promise promise) {
        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    JSONObject mutableUpdatePackage = HotUpdateUtils.convertReadableToJsonObject(updatePackage);
                    mUpdateManager.downloadPackage(mutableUpdatePackage, mHotUpdate.getAssetsBundleFileName(), new DownloadProgressCallback() {
                        private boolean hasScheduledNextFrame = false;
                        private DownloadProgress latestDownloadProgress = null;

                        @Override
                        public void call(DownloadProgress downloadProgress) {
                            if (!notifyProgress) {
                                return;
                            }

                            latestDownloadProgress = downloadProgress;
                            // If the download is completed, synchronously send the last event.
                            if (latestDownloadProgress.isCompleted()) {
                                dispatchDownloadProgressEvent();
                                return;
                            }

                            if (hasScheduledNextFrame) {
                                return;
                            }

                            hasScheduledNextFrame = true;
                            getReactApplicationContext().runOnUiQueueThread(new Runnable() {
                                @Override
                                public void run() {
                                    ReactChoreographer.getInstance().postFrameCallback(ReactChoreographer.CallbackType.TIMERS_EVENTS, new ChoreographerCompat.FrameCallback() {
                                        @Override
                                        public void doFrame(long frameTimeNanos) {
                                            if (!latestDownloadProgress.isCompleted()) {
                                                dispatchDownloadProgressEvent();
                                            }

                                            hasScheduledNextFrame = false;
                                        }
                                    });
                                }
                            });
                        }

                        public void dispatchDownloadProgressEvent() {
                            getReactApplicationContext()
                                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                                    .emit(HotUpdateConstants.DOWNLOAD_PROGRESS_EVENT_NAME, latestDownloadProgress.createWritableMap());
                        }
                    });

                    // JSONObject newPackage = mUpdateManager.getPackage(HotUpdateUtils.tryGetString(updatePackage, HotUpdateConstants.PACKAGE_HASH_KEY));
                    promise.resolve(HotUpdateUtils.convertJsonObjectToWritable(mutableUpdatePackage));
                } catch (IOException e) {
                    e.printStackTrace();
                    promise.reject(e);
                } catch (HotUpdateInvalidUpdateException e) {
                    e.printStackTrace();
                    mSettingsManager.saveFailedUpdate(HotUpdateUtils.convertReadableToJsonObject(updatePackage));
                    promise.reject(e);
                }

                return null;
            }
        };

        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @ReactMethod
    public void installUpdate(final ReadableMap updatePackage, final int installMode, final int minimumBackgroundDuration, final Promise promise) {
        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                mUpdateManager.installPackage(HotUpdateUtils.convertReadableToJsonObject(updatePackage), mSettingsManager.isPendingUpdate(null));

                String pendingHash = HotUpdateUtils.tryGetString(updatePackage, HotUpdateConstants.PACKAGE_HASH_KEY);
                if (pendingHash == null) {
                    throw new HotUpdateUnknownException("Update package to be installed has no hash.");
                } else {
                    mSettingsManager.savePendingUpdate(pendingHash, /* isLoading */false);
                }

                if (installMode == HotUpdateInstallMode.ON_NEXT_RESUME.getValue() ||
                    // We also add the resume listener if the installMode is IMMEDIATE, because
                    // if the current activity is backgrounded, we want to reload the bundle when
                    // it comes back into the foreground.
                    installMode == HotUpdateInstallMode.IMMEDIATE.getValue() ||
                    installMode == HotUpdateInstallMode.ON_NEXT_SUSPEND.getValue()) {


                    // Store the minimum duration on the native module as an instance
                    // variable instead of relying on a closure below, so that any
                    // subsequent resume-based installs could override it.
                    HotUpdateModule.this.mMinimumBackgroundDuration = minimumBackgroundDuration;

                    if (mLifecycleEventListener == null) {
                        // Ensure we do not add the listener twice.
                        mLifecycleEventListener = new LifecycleEventListener() {
                            private Date lastPausedDate = null;
                            private Handler appSuspendHandler = new Handler(Looper.getMainLooper());
                            private Runnable loadBundleRunnable = new Runnable() {
                                @Override
                                public void run() {
                                    HotUpdateUtils.log("Loading bundle on suspend");
                                    loadBundle();
                                }
                            };

                            @Override
                            public void onHostResume() {
                                appSuspendHandler.removeCallbacks(loadBundleRunnable);
                                // As of RN 36, the resume handler fires immediately if the app is in
                                // the foreground, so explicitly wait for it to be backgrounded first
                                if (lastPausedDate != null) {
                                    long durationInBackground = (new Date().getTime() - lastPausedDate.getTime()) / 1000;
                                    if (installMode == HotUpdateInstallMode.IMMEDIATE.getValue()
                                            || durationInBackground >= HotUpdateModule.this.mMinimumBackgroundDuration) {
                                        HotUpdateUtils.log("Loading bundle on resume");
                                        loadBundle();
                                    }
                                }
                            }

                            @Override
                            public void onHostPause() {
                                // Save the current time so that when the app is later
                                // resumed, we can detect how long it was in the background.
                                lastPausedDate = new Date();

                                if (installMode == HotUpdateInstallMode.ON_NEXT_SUSPEND.getValue() && mSettingsManager.isPendingUpdate(null)) {
                                    appSuspendHandler.postDelayed(loadBundleRunnable, minimumBackgroundDuration * 1000);
                                }
                            }

                            @Override
                            public void onHostDestroy() {
                            }
                        };

                        getReactApplicationContext().addLifecycleEventListener(mLifecycleEventListener);
                    }
                }

                promise.resolve("");

                return null;
            }
        };

        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }


    @ReactMethod
    public void getStat(Promise promise) {
        JSONObject info = mUpdateManager.getCurrentPackageInfo();
        promise.resolve(HotUpdateUtils.convertJsonObjectToWritable(info));
    }

    @ReactMethod
    public void getCurrentVersionInfo(Promise promise) {
        JSONObject currentPackageInfo = mUpdateManager.getCurrentPackage();
        WritableMap configMap =  Arguments.createMap();
        configMap.putString("appVersion", mHotUpdate.getAppVersion());
        if (currentPackageInfo != null) {
            int patch = currentPackageInfo.optInt(HotUpdateConstants.PACKAGE_PATCH_CODE_KEY, -1);
            String hash = currentPackageInfo.optString(HotUpdateConstants.PACKAGE_HASH_KEY, null);
            if (patch != -1) {
                configMap.putInt("appPatch", patch);    
            }
            if (hash != null) {
                configMap.putString("appHash", hash);    
            }
        }
        promise.resolve(configMap);
    }

    @ReactMethod
    public void notifyApplicationReady(Promise promise) {
        mSettingsManager.removePendingUpdate();
        promise.resolve("");
    }

    @ReactMethod
    public void restartApp(boolean onlyIfUpdateIsPending, Promise promise) {
        // If this is an unconditional restart request, or there
        // is current pending update, then reload the app.
        if (!onlyIfUpdateIsPending || mSettingsManager.isPendingUpdate(null)) {
            loadBundle();
            promise.resolve(true);
            return;
        }

        promise.resolve(false);
    }
}
