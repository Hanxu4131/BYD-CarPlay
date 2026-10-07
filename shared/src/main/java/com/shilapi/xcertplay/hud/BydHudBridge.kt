package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Publishes CarPlay route arrows, distance and street to BYD's native windshield HUD. */
internal object BydHudBridge {
    private const val TAG = "DiPlay-BYD-HUD"
    private const val SOMEIP_PACKAGE = "com.ts.car.someip.service"
    private const val SOMEIP_CLASS = "com.ts.car.someip.service.manager.SomeIpServerService"
    private const val SOMEIP_ACTION = "com.ts.car.someip.SomeIpServerService"
    private const val SOMEIP_TOKEN = "ts.car.someip.sdk.ISomeIpServerInterface"
    private const val CALLBACK_TOKEN = "ts.car.someip.sdk.ISomeIpCallback"
    private const val SERVICE_ID = BydHudProtocol.SERVICE_TOPIC
    private const val HUD_TOPIC = BydHudProtocol.NAVIGATION_TOPIC
    private const val TX_REGISTER_CALLBACK = 1
    private const val TX_START_SERVICE = 4
    private const val TX_FIRE_EVENT = 6
    private const val ICON_ASSET_DIR = "byd-hud-icons"

    private val callbacks = Executors.newSingleThreadExecutor { task ->
        Thread(task, "diplay-hud-callback").apply { isDaemon = true }
    }
    private val lock = Any()
    private val route = BydHudRouteState()
    private var context: Context? = null
    private var binder: IBinder? = null
    private var binding = false
    private var started = false
    private var senderStarted = false
    private var guidanceSentLogged = false
    private var showing = false
    private var lastSendResult: Int? = null
    private var icons: Map<Int, ByteArray>? = null
    private val bindRetry = BydHudBindRetryPolicy()
    private var active = false

    // The gateway pings registered callbacks and drops registrations that do not answer like an AIDL stub.
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
            INTERFACE_TRANSACTION -> { reply?.writeString(CALLBACK_TOKEN); true }
            FIRST_CALL_TRANSACTION -> {
                data.enforceInterface(CALLBACK_TOKEN)
                reply?.writeNoException()
                reply?.writeInt(0)
                true
            }
            FIRST_CALL_TRANSACTION + 1 -> {
                data.enforceInterface(CALLBACK_TOKEN)
                val ready = data.readInt() != 0
                Log.i(TAG, "SOME/IP HAL ready=$ready")
                reply?.writeNoException()
                true
            }
            FIRST_CALL_TRANSACTION + 2 -> {
                data.enforceInterface(CALLBACK_TOKEN)
                reply?.writeNoException()
                reply?.writeInt(0) // No response to an unsupported request.
                reply?.writeInt(0) // No modified input parcel.
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }

    fun initialize(appContext: Context) = synchronized(lock) {
        if (context == null) context = appContext.applicationContext
        active = true
        bindRetry.reset()
        bindLocked()
        if (!senderStarted) {
            senderStarted = true
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "diplay-byd-hud").apply { isDaemon = true }
            }.scheduleWithFixedDelay(::tick, BydHudProtocol.REPEAT_MILLIS, BydHudProtocol.REPEAT_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    fun onFrame(frame: Iap2Frame) = synchronized(lock) {
        val change = route.accept(frame.messageId, frame.payload)
        if (frame.messageId == BydHudRouteState.ROUTE_GUIDANCE_UPDATE ||
            frame.messageId == BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE
        ) {
            Log.d(TAG, "route frame=0x${frame.messageId.toString(16)} change=$change guidance=${route.current()}")
        }
        when (change) {
            BydHudRouteChange.GUIDANCE -> sendCurrentLocked()
            BydHudRouteChange.CLEAR -> clearHudLocked()
            BydHudRouteChange.NONE -> Unit
        }
    }

    /** Clears the HUD immediately; called when DiPlay is about to be killed. */
    fun clearNow() = clear()

    fun clear() = synchronized(lock) {
        active = false
        bindRetry.reset()
        route.clear()
        clearHudLocked()
    }

    private fun tick() = synchronized(lock) {
        if (!active) return@synchronized
        if (binder == null && !binding) bindLocked()
        sendCurrentLocked()
    }

    private fun enabled(): Boolean = context?.let(BydOutputSettings::hudEnabled) ?: false

    private fun sendCurrentLocked() {
        if (binder == null || !started) return
        if (!enabled()) {
            clearHudLocked()
            return
        }
        val guidance = route.current()
        if (guidance == null) {
            clearHudLocked()
            return
        }
        val payload = BydHudPayload.guidance(
            distanceMeters = guidance.distanceMeters,
            maneuver = guidance.maneuver,
            icon = iconFor(guidance.gaode),
            road = guidance.road,
        )
        val accepted = sendLocked(payload)
        if (accepted && !guidanceSentLogged) {
            guidanceSentLogged = true
            Log.i(TAG, "HUD profile (300ms repeat, fixed field2=2): gateway accepted guidance; rendering unconfirmed")
        }
        if (accepted) showing = true
    }

    private fun clearHudLocked() {
        if (!showing) return
        if (!sendLocked(BydHudPayload.clear())) return
        showing = false
        guidanceSentLogged = false
    }

    // Arrow-less maneuvers (roundabouts, destination) are only visible through the field-8 icon.
    private fun iconFor(gaode: Int): ByteArray? {
        if (gaode <= 0) return null
        val loaded = icons ?: loadIcons().also { icons = it }
        return loaded[gaode]
    }

    private fun loadIcons(): Map<Int, ByteArray> {
        val assets = context?.assets ?: return emptyMap()
        return runCatching {
            assets.list(ICON_ASSET_DIR).orEmpty().mapNotNull { name ->
                val code = name.removePrefix("0x").removeSuffix(".png").toIntOrNull(16) ?: return@mapNotNull null
                code to assets.open("$ICON_ASSET_DIR/$name").use { it.readBytes() }
            }.toMap()
        }.onFailure { Log.w(TAG, "HUD icons unavailable", it) }.getOrDefault(emptyMap())
    }

    private fun bindLocked() {
        val appContext = context ?: return
        if (!active || binder != null || binding) return
        val now = SystemClock.elapsedRealtime()
        if (!bindRetry.ready(now)) return
        try {
            // The merged manifest queries this package. Check the exact service, not a route handler.
            // Leave permission/export checks to bindService, which knows the caller's privileges.
            appContext.packageManager.getServiceInfo(ComponentName(SOMEIP_PACKAGE, SOMEIP_CLASS), 0)
        } catch (_: PackageManager.NameNotFoundException) {
            bindRetry.missing(now)
            Log.i(TAG, "SOME/IP service absent; checking again in 60s")
            return
        } catch (error: Exception) {
            // A failed package query does not prove absence; still try the explicit binding.
            Log.w(TAG, "cannot check SOME/IP service", error)
        }
        try {
            // The gateway's onUnbind requires a MIME type; a typeless bind crashes the whole SOME/IP process.
            val intent = Intent(SOMEIP_ACTION).apply {
                setClassName(SOMEIP_PACKAGE, SOMEIP_CLASS)
                type = appContext.packageName
            }
            binding = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!binding) bindRetry.failed(SystemClock.elapsedRealtime())
            Log.i(TAG, "bindService=$binding")
        } catch (error: Throwable) {
            binding = false
            bindRetry.failed(SystemClock.elapsedRealtime())
            Log.w(TAG, "cannot bind SOME/IP service", error)
        }
    }

    private fun onGatewayCallback(action: () -> Unit) {
        callbacks.execute {
            synchronized(lock) {
                runCatching(action).onFailure { Log.w(TAG, "gateway callback failed", it) }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) = onGatewayCallback {
            bindRetry.reset()
            binder = service
            binding = true
            val registered = transactLocked(TX_REGISTER_CALLBACK, returnsValue = false) { it.writeStrongBinder(callback) }
            val startResult = if (registered == 0) transactLocked(TX_START_SERVICE) { it.writeLong(SERVICE_ID) } else -1
            // Firmware explicitly treats 13 as an already-started service.
            started = BydHudProtocol.serviceStarted(startResult)
            Log.i(TAG, "SOME/IP connected, callback=$registered startResult=$startResult started=$started")
            showing = false
            sendCurrentLocked()
        }

        // BIND_AUTO_CREATE reconnects after a gateway restart without a second binding.
        override fun onServiceDisconnected(name: ComponentName) = onGatewayCallback {
            binder = null
            started = false
        }

        override fun onBindingDied(name: ComponentName) = onGatewayCallback { resetBindingLocked() }
        override fun onNullBinding(name: ComponentName) = onGatewayCallback { resetBindingLocked() }
    }

    private inline fun transactLocked(code: Int, returnsValue: Boolean = true, write: (Parcel) -> Unit): Int {
        val target = binder ?: return -1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SOMEIP_TOKEN)
            write(data)
            check(target.transact(code, data, reply, 0)) { "Unsupported transaction $code" }
            reply.readException()
            if (returnsValue) reply.readInt() else 0
        } catch (error: Throwable) {
            Log.w(TAG, "SOME/IP transaction $code failed", error)
            -1
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun sendLocked(payload: ByteArray): Boolean {
        if (binder == null) return false
        val result = transactLocked(TX_FIRE_EVENT) {
            it.writeInt(1)
            it.writeLong(HUD_TOPIC)
            it.writeLong(System.currentTimeMillis())
            it.writeInt(payload.size)
            it.writeByteArray(payload)
        }
        if (lastSendResult != result) {
            Log.i(TAG, "HUD fireEvent result=$result bytes=${payload.size}")
            lastSendResult = result
        }
        if (result != 0) {
            if (binder?.isBinderAlive != true) resetBindingLocked()
        }
        return BydHudProtocol.eventAccepted(result)
    }

    private fun resetBindingLocked() {
        val appContext = context
        if (appContext != null && binding) {
            runCatching { appContext.unbindService(connection) }
        }
        binder = null
        binding = false
        started = false
        showing = false
        guidanceSentLogged = false
        if (active) bindRetry.failed(SystemClock.elapsedRealtime()) else bindRetry.reset()
    }
}
