package tn.loukious.facebookappadsremover;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class Module implements IXposedHookLoadPackage {

    private static final String TAG = "FacebookAppAdsRemover";
    private static final long[] FAST_SOURCE_DELAYS_MS = {100L, 250L, 750L, 1_500L, 2_500L};
    private static final long[] FAST_COMPONENT_DELAYS_MS = {3_500L, 5_000L, 7_500L};
    private static final long[] INSTALL_DELAYS_MS = {3_000L, 10_000L, 25_000L};
    private static volatile boolean sDexKitLoaded = false;
    private static final AtomicBoolean sAttachHookInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean sDexReadyHookInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean sFastInstallInProgress = new AtomicBoolean(false);
    private static final AtomicBoolean sFastSourceHooksInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean sComponentGuardInstallInProgress = new AtomicBoolean(false);
    private static final AtomicBoolean sFeedComponentGuardInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean sInstallInProgress = new AtomicBoolean(false);
    private static final AtomicBoolean sHooksInstalled = new AtomicBoolean(false);
    private static volatile XC_MethodHook.Unhook sClassLoadNotifierUnhook;

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
        installFacebookDexReadyHook(lpparam.classLoader);
        ensureDexKitLoaded();
        if (!sAttachHookInstalled.compareAndSet(false, true)) {
            return;
        }

        Method attach = Application.class.getDeclaredMethod("attach", Context.class);
        attach.setAccessible(true);
        XposedBridge.hookMethod(attach, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Application application = (Application) param.thisObject;
                scheduleHookInstallAttempts(application.getClassLoader());
            }
        });
        debugLogInfo("Waiting for Facebook Application.attach before scanning secondary dex");
    }

    private static void installFacebookDexReadyHook(ClassLoader classLoader) {
        if (!sDexReadyHookInstalled.compareAndSet(false, true)) {
            return;
        }
        try {
            installFacebookClassLoadNotifierHook(classLoader);
            Class<?> multiDexClassLoader = Class.forName(
                    "com.facebook.common.dextricks.MultiDexClassLoaderJava",
                    false,
                    classLoader
            );
            Method configure = null;
            for (Method method : multiDexClassLoader.getDeclaredMethods()) {
                if ("configure".equals(method.getName()) && method.getParameterCount() == 1) {
                    configure = method;
                    break;
                }
            }
            if (configure == null) {
                debugLogInfo("Facebook MultiDex configure method not found; using timed feed hook fallback");
                return;
            }
            configure.setAccessible(true);
            XposedBridge.hookMethod(configure, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ClassLoader configuredLoader = param.thisObject instanceof ClassLoader
                            ? (ClassLoader) param.thisObject
                            : classLoader;
                    tryInstallFastFeedHooksAtDexReady(configuredLoader, "MultiDex configure");
                }
            });
            int fallbackHooks = 0;
            Class<?> multiDexBase = Class.forName(
                    "com.facebook.common.dextricks.MultiDexClassLoader",
                    false,
                    classLoader
            );
            for (Method method : multiDexBase.getDeclaredMethods()) {
                if (
                        "maybeFallbackLoadDexes".equals(method.getName()) &&
                        method.getParameterCount() >= 1 &&
                        method.getParameterTypes()[0] == String.class
                ) {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(param.getResult())) {
                                tryInstallFastFeedHooksAtDexReady(classLoader, "long-tail dex load");
                            }
                        }
                    });
                    fallbackHooks++;
                }
            }
            debugLogInfo(
                    "Waiting for Facebook MultiDex configure/long-tail load before installing decoded response hooks; " +
                            "fallbackHooks=" + fallbackHooks
            );
        } catch (Throwable throwable) {
            debugLogError("Failed to hook Facebook MultiDex readiness; using timed fallback", throwable);
        }
    }

    private static void installFacebookClassLoadNotifierHook(ClassLoader classLoader) throws Exception {
        Class<?> notifierClass = Class.forName(
                "com.facebook.common.dextricks.ClassLoadsNotifier",
                false,
                classLoader
        );
        Method notifyClassLoaded = notifierClass.getDeclaredMethod("notifyClassLoaded", Class.class);
        notifyClassLoaded.setAccessible(true);
        sClassLoadNotifierUnhook = XposedBridge.hookMethod(notifyClassLoaded, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Class<?> loadedClass = param.args[0] instanceof Class ? (Class<?>) param.args[0] : null;
                if (loadedClass == null || !isFastFeedTargetClass(loadedClass.getName())) {
                    return;
                }

                ClassLoader targetLoader = loadedClass.getClassLoader();
                if (targetLoader == null) {
                    targetLoader = classLoader;
                }
                debugLogInfo(
                        "Observed FB 571 feed source class load=" + loadedClass.getName() +
                                " loader=" + targetLoader.getClass().getName()
                );
                tryInstallFastFeedHooksAtDexReady(targetLoader, "class-load notification");
            }
        });
        debugLogInfo("Waiting for FB 571 feed source class load before installing decoded response hooks");
    }

    private static boolean isFastFeedTargetClass(String className) {
        return "X.1fM".equals(className)
                || "X.21p".equals(className)
                || "X.baJ".equals(className)
                || "X.baK".equals(className)
                || "X.21O".equals(className)
                || "X.3YX".equals(className)
                || "X.2OT".equals(className)
                || "X.2Oc".equals(className);
    }

    private static void tryInstallFastFeedHooksAtDexReady(
            ClassLoader classLoader,
            String readinessSource
    ) {
        if (!sFastSourceHooksInstalled.get() && sFastInstallInProgress.compareAndSet(false, true)) {
            try {
                if (PatchesKt.installFacebook571FeedSourceFastPath(classLoader)) {
                    sFastSourceHooksInstalled.set(true);
                    debugLogInfo(
                            "FB 571 decoded response hooks installed synchronously at " + readinessSource
                    );
                }
            } catch (Throwable throwable) {
                debugLogError(
                        "Failed FB 571 decoded response install at " + readinessSource,
                        throwable
                );
            } finally {
                sFastInstallInProgress.set(false);
            }
        }
        tryInstallFeedComponentGuard(classLoader, readinessSource);
        removeClassLoadNotifierHook();
    }

    private static void removeClassLoadNotifierHook() {
        if (!sFastSourceHooksInstalled.get() || !sFeedComponentGuardInstalled.get()) {
            return;
        }
        XC_MethodHook.Unhook unhook = sClassLoadNotifierUnhook;
        if (unhook == null) {
            return;
        }
        sClassLoadNotifierUnhook = null;
        unhook.unhook();
        debugLogInfo("Removed FB 571 class-load notifier after decoded hooks became active");
    }

    private static void scheduleHookInstallAttempts(ClassLoader classLoader) {
        Handler handler = new Handler(Looper.getMainLooper());
        tryInstallFastFeedSourceHooks(classLoader, 0);
        tryInstallFeedComponentGuard(classLoader, "Application.attach");
        for (int attempt = 0; attempt < FAST_SOURCE_DELAYS_MS.length; attempt++) {
            final int attemptNumber = attempt + 1;
            handler.postDelayed(
                    () -> new Thread(
                            () -> tryInstallFastFeedSourceHooks(classLoader, attemptNumber),
                            "FacebookFeedFastInit-" + attemptNumber
                    ).start(),
                    FAST_SOURCE_DELAYS_MS[attempt]
            );
        }
        for (int attempt = 0; attempt < FAST_COMPONENT_DELAYS_MS.length; attempt++) {
            final int attemptNumber = attempt + 1;
            handler.postDelayed(
                    () -> new Thread(
                            () -> tryInstallFeedComponentGuard(
                                    classLoader,
                                    "component attempt=" + attemptNumber
                            ),
                            "FacebookFeedComponentInit-" + attemptNumber
                    ).start(),
                    FAST_COMPONENT_DELAYS_MS[attempt]
            );
        }
        for (int attempt = 0; attempt < INSTALL_DELAYS_MS.length; attempt++) {
            final int attemptNumber = attempt + 1;
            handler.postDelayed(
                    () -> new Thread(
                            () -> tryInstallHooks(classLoader, attemptNumber),
                            "FacebookAdsHookInit-" + attemptNumber
                    ).start(),
                    INSTALL_DELAYS_MS[attempt]
            );
        }
    }

    private static void tryInstallFastFeedSourceHooks(ClassLoader classLoader, int attemptNumber) {
        if (!sFastSourceHooksInstalled.get() && sFastInstallInProgress.compareAndSet(false, true)) {
            try {
                if (PatchesKt.installFacebook571FeedSourceFastPath(classLoader)) {
                    sFastSourceHooksInstalled.set(true);
                    debugLogInfo("FB 571 decoded response hooks installed on attempt=" + attemptNumber);
                }
            } catch (Throwable throwable) {
                debugLogError("Failed FB 571 fast decoded response install on attempt=" + attemptNumber, throwable);
            } finally {
                sFastInstallInProgress.set(false);
            }
        }
        tryInstallFeedComponentGuard(classLoader, "source attempt=" + attemptNumber);
        removeClassLoadNotifierHook();
    }

    private static void tryInstallFeedComponentGuard(
            ClassLoader classLoader,
            String readinessSource
    ) {
        if (
                sFeedComponentGuardInstalled.get() ||
                !sComponentGuardInstallInProgress.compareAndSet(false, true)
        ) {
            return;
        }
        try {
            if (PatchesKt.installFacebook571FeedComponentGuard(classLoader)) {
                sFeedComponentGuardInstalled.set(true);
                debugLogInfo(
                        "FB 571 sponsored feed component guard installed at " + readinessSource
                );
            }
        } catch (Throwable throwable) {
            debugLogError(
                    "Failed FB 571 sponsored feed component guard at " + readinessSource,
                    throwable
            );
        } finally {
            sComponentGuardInstallInProgress.set(false);
        }
    }

    private static void tryInstallHooks(ClassLoader classLoader, int attemptNumber) {
        if (sHooksInstalled.get() || !sInstallInProgress.compareAndSet(false, true)) {
            return;
        }

        try (DexKitBridge bridge = DexKitBridge.create(classLoader, true)) {
            debugLogInfo("Scanning Facebook secondary dex, attempt=" + attemptNumber);
            if (PatchesKt.installFacebookAdRemover(classLoader, bridge)) {
                sHooksInstalled.set(true);
                tryInstallFeedComponentGuard(classLoader, "full DexKit readiness");
                removeClassLoadNotifierHook();
                debugLogInfo("Facebook ad remover hooks installed on attempt=" + attemptNumber);
            }
        } catch (Throwable throwable) {
            debugLogError("Failed to install Facebook ad remover on attempt=" + attemptNumber, throwable);
        } finally {
            sInstallInProgress.set(false);
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
