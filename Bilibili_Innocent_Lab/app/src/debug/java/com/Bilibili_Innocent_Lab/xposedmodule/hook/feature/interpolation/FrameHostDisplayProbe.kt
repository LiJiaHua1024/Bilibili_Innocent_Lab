package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.app.Activity
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.util.Log
import java.io.File

/** Explicit debug experiment. Only token APIs with a zero release and server-side death cleanup. */
object FrameHostDisplayProbe {
    @JvmStatic fun acquire(activity: Activity, target: Int): AutoCloseable? {
        val option = File(activity.getExternalFilesDir(null), "innocent-frame-verify/display-probe")
            .takeIf { it.length() in 1..8 }?.readText()?.trim() ?: return null
        if (option == "window") {
            val window = activity.window.decorView.windowToken ?: return null
            val api = Class.forName("com.oplus.screenmode.OplusDisplayModeManager")
            val manager = api.getMethod("getInstance").invoke(null)
            val method = api.getMethod("overrideWindowRefreshRate", IBinder::class.java, Int::class.javaPrimitiveType)
            // Firmware constants: AUTO=0, 60=2, 120=3. WMS owns this request with the window.
            method.invoke(manager, window, if (target == 120) 3 else 2)
            Log.i("InnocentFrame", "display.window requested=$target")
            return AutoCloseable {
                method.invoke(manager, window, 0)
                Log.i("InnocentFrame", "display.window.release")
            }
        }
        if (option !in setOf("vrr", "frtc")) return null
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "oplus_vrr_service") as IBinder
        val service = Class.forName("com.oplus.vrr.IOPlusRefreshRate\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val api = Class.forName("com.oplus.vrr.IOPlusRefreshRate")
        val token = Binder()
        val title = activity.window.attributes.title.toString()
        val request: (Float) -> Any? = if (option == "vrr") {
            val method = api.getMethod("setFrameRateWithToken", Float::class.javaPrimitiveType,
                String::class.java, String::class.java, Int::class.javaPrimitiveType, IBinder::class.java);
            { fps: Float -> method.invoke(service, fps, title, activity.packageName, Process.myPid(), token) }
        } else {
            val method = api.getMethod("setFrameRateTargetControlWithToken", Float::class.javaPrimitiveType,
                String::class.java, Boolean::class.javaPrimitiveType, String::class.java, IBinder::class.java);
            { fps: Float -> method.invoke(service, fps, activity.packageName, true, "frame-host-validation", token) }
        }
        val result = try { request(target.toFloat()) } catch (error: Throwable) {
            runCatching { request(0f) }
            throw error
        }
        Log.i("InnocentFrame", "display.token $option result=$result")
        return AutoCloseable {
            val released = request(0f)
            Log.i("InnocentFrame", "display.token.release $option result=$released")
        }
    }
}
