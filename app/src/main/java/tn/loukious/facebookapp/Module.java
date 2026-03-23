package tn.loukious.facebookapp;

import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class Module implements IXposedHookLoadPackage {

    private static final String TAG = "FacebookAppAdsRemover";
    private static volatile boolean sDexKitLoaded = false;

    private static void debugLogInfo(String message) {
        if (BuildConfig.DEBUG) {
            Log.i(TAG, message);
        }
    }

    private static void debugLogError(String message, Throwable throwable) {
        if (BuildConfig.DEBUG) {
            Log.e(TAG, message, throwable);
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.facebook.katana".equals(lpparam.packageName)) {
            return;
        }

        debugLogInfo("Loading hooks for package=" + lpparam.packageName + " process=" + lpparam.processName);
        ClassLoader cl = lpparam.classLoader;
        ensureDexKitLoaded();
        try (DexKitBridge bridge = DexKitBridge.create(cl, true)) {
            PatchesKt.installFacebookAdRemover(cl, bridge);
        } catch (Exception e) {
            debugLogError("Failed to install Facebook ad remover", e);
        }
    }

    private static void ensureDexKitLoaded() {
        if (sDexKitLoaded) {
            return;
        }
        synchronized (Module.class) {
            if (!sDexKitLoaded) {
                System.loadLibrary("dexkit");
                sDexKitLoaded = true;
            }
        }
    }
}
