package com.shilapi.xcertplay.camera

import android.annotation.SuppressLint
import android.content.Context
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Small token-bound protocol. It exposes only the six allow-listed read fields below. */
internal object CameraVehicleSignalProtocol {
    private const val MAX_LINE_BYTES = 256
    private val tokenPattern = Regex("[0-9a-f]{32}")
    fun validToken(token: String) = tokenPattern.matches(token)
    fun parse(line: String, token: String): String? {
        if (!validToken(token) || line.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES) return null
        val parts = line.split(' ')
        if (parts.size != 2 || parts[0] != token || parts[1] !in setOf("ping", "read", "close")) return null
        return parts[1]
    }
    fun readLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        repeat(MAX_LINE_BYTES + 1) {
            val next = input.read()
            if (next < 0) return null
            if (next == 10) return bytes.toString("UTF-8")
            bytes.write(next)
        }
        return null
    }
}

/** app_process entry point. It reads vehicle metadata only and owns no setter/listener API. */
object CameraVehicleSignalWorker {
    @JvmStatic fun main(args: Array<String>) {
        if (args.size != 2 || args[0] != "--stdin") return
        val token = args[1].takeIf(CameraVehicleSignalProtocol::validToken) ?: return
        val lastRequest = AtomicLong(System.nanoTime())
        val started = System.nanoTime()
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "camera-signal-watchdog").apply { isDaemon = true } }
        watchdog.scheduleAtFixedRate({
            val now = System.nanoTime()
            if ((ready.get() && now - lastRequest.get() > TimeUnit.SECONDS.toNanos(5)) ||
                (!ready.get() && now - started > TimeUnit.SECONDS.toNanos(12))) Runtime.getRuntime().halt(124)
        }, 1, 1, TimeUnit.SECONDS)
        try {
            val device = SignalDevice()
            lastRequest.set(System.nanoTime())
            ready.set(true)
            while (true) {
                val op = CameraVehicleSignalProtocol.parse(CameraVehicleSignalProtocol.readLine(System.`in`) ?: break, token) ?: break
                lastRequest.set(System.nanoTime())
                val response = when (op) {
                    "ping" -> "ready"
                    "read" -> "raw=${device.read().joinToString(",") { it?.toString() ?: "~" }}"
                    "close" -> "closed"
                    else -> break
                }
                println(response); println("done"); System.out.flush()
                if (op == "close") break
            }
        } catch (_: Throwable) {
            // Errors fail closed: the client will publish unknowns and retry later.
        } finally {
            watchdog.shutdownNow()
            Runtime.getRuntime().halt(0)
        }
    }
}

/** Uses the public generic BYDAutoDeviceManager read API under the normal shell UID. */
@SuppressLint("PrivateApi")
private class SignalDevice {
    private val manager: Any
    private val getInt: java.lang.reflect.Method
    private val getDouble: java.lang.reflect.Method
    private val gearboxType: Int
    private val speedType: Int
    private val lightType: Int
    private val bodyworkType: Int
    private val gearAuto: Int
    private val brakeDepth: Int
    private val turnGlobal: Int
    private val turnLeft: Int
    private val turnRight: Int
    private val steering: Int

    init {
        runCatching { android.os.Looper.prepareMainLooper() }
        val thread = Class.forName("android.app.ActivityThread")
        val main = thread.getMethod("systemMain").invoke(null)
        val system = thread.getMethod("getSystemContext").invoke(main) as Context
        val shell = system.createPackageContext("com.android.shell", 0)
        val managerClass = Class.forName("android.hardware.bydauto.BYDAutoDeviceManager")
        manager = requireNotNull(managerClass.getMethod("getInstance", Context::class.java).invoke(null, shell))
        getInt = managerClass.getMethod("getInt", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        getDouble = managerClass.getMethod("getDouble", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val types = Class.forName("android.hardware.bydauto.BYDAutoConstants")
        val ids = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds")
        gearboxType = field(types, "BYDAUTO_DEVICE_GEARBOX")
        speedType = field(types, "BYDAUTO_DEVICE_SPEED")
        lightType = field(types, "BYDAUTO_DEVICE_LIGHT")
        bodyworkType = field(types, "BYDAUTO_DEVICE_BODYWORK")
        gearAuto = field(ids, "GEARBOX_AUTO_MODE_TYPE")
        brakeDepth = field(ids, "SPEED_BRAKE_S")
        turnGlobal = field(ids, "LIGHT_TURN_SIGNAL_LIGHT_SWITCH_STATE")
        turnLeft = field(ids, "LIGHT_LEFT_TURN_SIGNAL_LIGHT")
        turnRight = field(ids, "LIGHT_RIGHT_TURN_SIGNAL_LIGHT")
        steering = field(ids, "BODYWORK_STEERING_WHEEL_ANGEL")
    }

    /** autoGear, brakePercent, globalTurn, leftTurn, rightTurn, steeringNative. */
    fun read(): List<Number?> = listOf(
        readInt(gearboxType, gearAuto)?.takeIf { it in 1..6 },
        readInt(speedType, brakeDepth)?.takeIf { it in 0..100 },
        readInt(lightType, turnGlobal)?.takeIf { it in 0..2 },
        readInt(lightType, turnLeft)?.takeIf { it in 0..2 },
        readInt(lightType, turnRight)?.takeIf { it in 0..2 },
        readDouble(bodyworkType, steering)?.takeIf { it.isFinite() && it in -780.0..780.0 },
    )

    private fun readInt(type: Int, id: Int): Int? = try {
        (getInt.invoke(manager, type, id) as Number).toInt().takeUnless { it == -10011 || it == Int.MIN_VALUE }
    } catch (_: Throwable) { null }

    private fun readDouble(type: Int, id: Int): Double? = try {
        (getDouble.invoke(manager, type, id) as Number).toDouble().takeUnless { !it.isFinite() || it == -10011.0 }
    } catch (_: Throwable) { null }

    private fun field(owner: Class<*>, name: String): Int = owner.getField(name).getInt(null)
}
