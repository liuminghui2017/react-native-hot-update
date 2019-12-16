package com.rickl.reactlibrary.hotupdate;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
// import android.content.res.Resources;
// import android.support.annotation.NonNull;


import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.facebook.react.ReactInstanceManager;
import com.facebook.react.ReactPackage;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.uimanager.ViewManager;
import com.facebook.react.bridge.JavaScriptModule;


public class HotUpdate implements ReactPackage {
    private static boolean sIsRunningBinaryVersion = false; // 是否运行的assets://下版本
    private static String sAppVersion = null; // app版本

    private String mAssetsBundleFileName;

    // Helper classes.
    private HotUpdateUpdateManager mUpdateManager;
    private SettingsManager mSettingsManager;

    private Context mContext;
    private final boolean mIsDebugMode; // 当前是否为开发环境

    private static ReactInstanceHolder mReactInstanceHolder;
    private static HotUpdate mCurrentInstance;
    
    // 构造函数
    public HotUpdate(Context context, boolean isDebugMode) {
        mContext = context.getApplicationContext();

        mUpdateManager = new HotUpdateUpdateManager(context.getFilesDir().getAbsolutePath());
        
        mIsDebugMode = isDebugMode;
        mSettingsManager = new SettingsManager(mContext);

        if (sAppVersion == null) {
            try {
                PackageInfo pInfo = mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0);
                sAppVersion = pInfo.versionName;
            } catch (PackageManager.NameNotFoundException e) {
                throw new HotUpdateUnknownException("Unable to get package info for " + mContext.getPackageName(), e);
            }
        }

        mCurrentInstance = this;

        // clearDebugCacheIfNeeded();
        initializeUpdateAfterRestart();
    }

    // 获取bundle路径
    public static String getJSBundleFile() {
        return HotUpdate.getJSBundleFile(HotUpdateConstants.DEFAULT_JS_BUNDLE_NAME);
    }

    // 获取bundle路径 - 重载
    public static String getJSBundleFile(String assetsBundleFileName) {
        if (mCurrentInstance == null) {
            throw new HotUpdateNotInitializedException("A HotUpdate instance has not been created yet. Have you added it to your app's list of ReactPackages?");
        }

        return mCurrentInstance.getJSBundleFileInternal(assetsBundleFileName);
    }

    public String getJSBundleFileInternal(String assetsBundleFileName) {
        this.mAssetsBundleFileName = assetsBundleFileName;
        String binaryJsBundleUrl = HotUpdateConstants.ASSETS_BUNDLE_PREFIX + assetsBundleFileName;

        String packageFilePath = mUpdateManager.getCurrentPackageBundlePath(this.mAssetsBundleFileName);
        
        if (packageFilePath == null) {
            // There has not been any downloaded updates.
            HotUpdateUtils.log("getJSBundleFileInternal: pachageFilePath is null");
            HotUpdateUtils.logBundleUrl(binaryJsBundleUrl);
            sIsRunningBinaryVersion = true;
            return binaryJsBundleUrl;
        }

        JSONObject packageMetadata = this.mUpdateManager.getCurrentPackage();
        if (!hasBinaryVersionChanged(packageMetadata)) {
            HotUpdateUtils.logBundleUrl(packageFilePath);
            sIsRunningBinaryVersion = false;
            return packageFilePath;
        } else {
            // 更新包记录的appVersion与app原生版本不一致时
            if (!this.mIsDebugMode) {
                this.clearUpdates();
            }
            HotUpdateUtils.log("getJSBundleFileInternal: appVersion is not match");
            HotUpdateUtils.logBundleUrl(binaryJsBundleUrl);
            sIsRunningBinaryVersion = true;
            return binaryJsBundleUrl;
        }
    }

    public String getAppVersion() {
        return sAppVersion;
    }

    public String getAssetsBundleFileName() {
        return mAssetsBundleFileName;
    }

    public void clearDebugCacheIfNeeded() {
        if (mIsDebugMode && mSettingsManager.isPendingUpdate(null)) {
            // This needs to be kept in sync with https://github.com/facebook/react-native/blob/master/ReactAndroid/src/main/java/com/facebook/react/devsupport/DevSupportManager.java#L78
            File cachedDevBundle = new File(mContext.getFilesDir(), "ReactNativeDevBundle.js");
            if (cachedDevBundle.exists()) {
                cachedDevBundle.delete();
            }
        }
    }

    void initializeUpdateAfterRestart() {
        JSONObject pendingUpdate = mSettingsManager.getPendingUpdate();
        if (pendingUpdate != null) {
            JSONObject packageMetadata = this.mUpdateManager.getCurrentPackage();
            if (hasBinaryVersionChanged(packageMetadata)) {
                HotUpdateUtils.log("Skipping initializeUpdateAfterRestart(), binary version mismatch");
                return;
            }

            try {
                boolean updateIsLoading = pendingUpdate.getBoolean(HotUpdateConstants.PENDING_UPDATE_IS_LOADING_KEY);
                if (updateIsLoading) {
                    // Pending update was initialized, but notifyApplicationReady was not called.
                    // Therefore, deduce that it is a broken update and rollback.
                    HotUpdateUtils.log("Update did not finish loading the last time, rolling back to a previous version.");                    
                    rollbackPackage();
                } else {
                    // Mark that we tried to initialize the new update, so that if it crashes,
                    // we will know that we need to rollback when the app next starts.
                    mSettingsManager.savePendingUpdate(pendingUpdate.getString(HotUpdateConstants.PENDING_UPDATE_HASH_KEY),
                            /* isLoading */true);
                }
            } catch (JSONException e) {
                // Should not happen.
                throw new HotUpdateUnknownException("Unable to read pending update metadata stored in SharedPreferences", e);
            }
        }
    }

    void invalidateCurrentInstance() {
        mCurrentInstance = null;
    }

    public static void setReactInstanceHolder(ReactInstanceHolder reactInstanceHolder) {
        mReactInstanceHolder = reactInstanceHolder;
    }

    public void clearUpdates() {
        mUpdateManager.clearUpdates();
        mSettingsManager.removePendingUpdate();
        mSettingsManager.removeFailedUpdates();
    }

    static ReactInstanceManager getReactInstanceManager() {
        if (mReactInstanceHolder == null) {
            return null;
        }
        return mReactInstanceHolder.getReactInstanceManager();
    }

    // 判断更新包与原生包的版本是否不一致
    private boolean hasBinaryVersionChanged(JSONObject packageMetadata) {
        String packageAppVersion = packageMetadata.optString("appVersion", null);
        return !sAppVersion.equals(packageAppVersion);
    }

    private void rollbackPackage() {
        JSONObject failedPackage = mUpdateManager.getCurrentPackage();
        mSettingsManager.saveFailedUpdate(failedPackage);
        mUpdateManager.rollbackPackage();
        mSettingsManager.removePendingUpdate();
    }

    @Override
    public List<NativeModule> createNativeModules(ReactApplicationContext reactContext) {
        return Arrays.<NativeModule>asList(new HotUpdateModule(reactContext, this, mUpdateManager, mSettingsManager));
    }

    @Override
    public List<ViewManager> createViewManagers(ReactApplicationContext reactContext) {
        return Collections.emptyList();
    }
}
