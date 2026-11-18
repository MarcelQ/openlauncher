package com.openlauncher.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Client for the SPD ROM's privileged settings service (`com.spd.setting`,
 * `sharedUserId=android.uid.system`, exported `SettingService` with an AIDL
 * binder) — docs/float-window-research.md.
 *
 * Why this exists: the ROM refuses THIRD-PARTY writes to its custom
 * Settings.System keys even with WRITE_SETTINGS granted (unit-verified
 * 2026-11-18: IllegalArgumentException "You cannot keep your settings in the
 * secure settings" for SETTINGS_SHORTCUT_FLOAT et al.). The vendor's own UI
 * reaches those keys because it runs as system uid; this exported service is
 * the ROM's designed cross-process door: `putInt(code, value)` runs the write
 * server-side (system uid) and is NOT permission-gated in the manifest.
 *
 * The action string, interface descriptor, transaction codes (putInt=26,
 * getInt=27) and setting codes (40033/40040 → the two float-sidebar keys,
 * GeneralControllerImpl packed-switch base 0x9c41) come from behavior-only
 * analysis of the pulled vendor prebuilt. No vendor code is copied — this is
 * an independently written interop client, the standard way to talk to an
 * AIDL service. Harmless by design: we only touch the two documented
 * float-sidebar keys, and the Diagnostics button re-reads the raw keys
 * afterwards so a wrong mapping can never silently "succeed".
 */
object VendorSettingService {

    const val PKG         = "com.spd.setting"
    const val SERVICE     = "com.spd.setting.service.SettingService"
    private const val DESCRIPTOR = "com.spd.setting.aidl.ISettingService"
    private const val TX_PUT_INT = 26
    private const val TX_GET_INT = 27

    /** Vendor SettingUtils.General codes for the SystemUI float sidebar keys. */
    const val CODE_SHORTCUT_FLOAT   = 40033  // → Settings.System SETTINGS_SHORTCUT_FLOAT
    const val CODE_SIDEBAR_APPS     = 40040  // → Settings.System SIDEBAR_APPS_FLOAT_BUTTON

    /** True when this ROM ships the vendor settings service (any non-SPD device: false). */
    fun isAvailable(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PKG, 0) != null
    } catch (_: Throwable) { false }

    class Proxy internal constructor(private val binder: IBinder) {

        /** Returns null on any transport/protocol failure (never throws). */
        fun getInt(code: Int, def: Int): Int? = parcelTx { data, reply ->
            data.writeInt(code)
            data.writeInt(def)
            if (!binder.transact(TX_GET_INT, data, reply, 0)) return@parcelTx null
            try {
                reply.readException()   // rethrows any server-side exception
                reply.readInt()
            } catch (_: Throwable) { null }
        }

        /** True only when the server accepted the write without an exception. */
        fun putInt(code: Int, value: Int): Boolean = parcelTx { data, reply ->
            data.writeInt(code)
            data.writeInt(value)
            if (!binder.transact(TX_PUT_INT, data, reply, 0)) return@parcelTx false
            try {
                reply.readException()
                true
            } catch (_: Throwable) { false }
        } ?: false
    }

    /**
     * Binds the vendor service, runs [op] against it, unbinds. Blocking — call
     * from an IO thread. Returns false when the service is absent, refuses to
     * bind, or never connects within the timeout.
     */
    fun withProxy(context: Context, op: (Proxy) -> Unit): Boolean {
        val latch  = CountDownLatch(1)
        var proxy: Proxy? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                proxy = Proxy(binder); latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { latch.countDown() }
            override fun onBindingDied(name: ComponentName)           { latch.countDown() }
            override fun onNullBinding(name: ComponentName)           { latch.countDown() }
        }
        val bound = try {
            context.bindService(Intent(SERVICE).setPackage(PKG), conn, Context.BIND_AUTO_CREATE)
        } catch (_: Throwable) { false }
        if (!bound) return false
        val connected = try { latch.await(4, TimeUnit.SECONDS) } catch (_: Throwable) { false }
        val ok = try {
            val p = proxy
            if (connected && p != null) { op(p); true } else false
        } catch (_: Throwable) { false }
        runCatching { context.unbindService(conn) }
        return ok
    }

    /** Parcel boilerplate shared by both transactions. */
    private inline fun <R> parcelTx(body: (Parcel, Parcel) -> R?): R? {
        val data  = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            body(data, reply)
        } catch (_: Throwable) { null }
        finally { reply.recycle(); data.recycle() }
    }
}
