package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.camera.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

/** Capture starts only through the camera master switch; imported candidates do not change the verified profile. */
internal object CameraServices {
    // This release uses the original L1Mini integration; no native capture is started.
    const val ENABLED = false
    private var runtime: CameraOverlayRuntime? = null
    private var capture: CaptureBinding? = null
    private var geometrySubscription: AutoCloseable? = null
    private var signalFactoryRegistration: AutoCloseable? = null

    @Synchronized fun ensure(context: Context) {
        if (!ENABLED) return
        if (runtime != null) return
        val app=context.applicationContext
        val owner=CameraOverlayRuntime(app)
        val binding=CaptureBinding(app,owner)
        runtime=owner; capture=binding
        CameraIntegration.bindHost(object : CameraIntegration.Host {
            override fun apply(context: Context,values: CameraSettings.Values) {
                owner.apply(context,values)
                binding.apply(values)
                CameraSessionService.update(app,values.enabled)
            }
            override fun status(): String = binding.status(owner.status())
            override fun frameKind(view: CameraView): CameraFrameKind = owner.frameKind(view)
        })
        geometrySubscription=CameraHostGeometry.subscribe(owner::updateSplit)
        signalFactoryRegistration = binding.attachSignals { ctx, callback -> CameraVehicleSignalProvider(ctx, callback) }
        CameraIntegration.configure(app,CameraSettings.load(app))
    }

    /** Root-owned read-only signal client factory. Constructed only while master is ON, closed on OFF. */
    fun attachVehicleSignals(context: Context,
        factory: (Context,(CameraVehicleSignals)->Unit)->AutoCloseable,
    ): AutoCloseable {
        ensure(context)
        val binding=synchronized(this) { requireNotNull(capture) }
        return binding.attachSignals(factory)
    }

    @Synchronized fun close() {
        geometrySubscription?.close(); geometrySubscription=null
        signalFactoryRegistration?.close(); signalFactoryRegistration=null
        capture?.close(); capture=null
        runtime?.close(); runtime=null
        CameraIntegration.bindHost(null)
    }

    private class CaptureBinding(private val app: Context,private val owner: CameraOverlayRuntime) : AutoCloseable {
        private val controller=Executors.newSingleThreadScheduledExecutor { r -> Thread(r,"camera-services").apply { isDaemon=true } }
        private val closed=AtomicBoolean(false)
        private val consecutiveFailures=AtomicInteger()
        private var retry: java.util.concurrent.ScheduledFuture<*>? = null
        private val plan=CameraCaptureProfiles.confirmedBydAvm()
        @Volatile private var settings=CameraSettings.Values()
        @Volatile private var leases: Map<CameraView,CameraOverlayRuntime.SourceLease> = emptyMap()
        @Volatile private var captureFailure: String?=null
        @Volatile private var signalFactory: ((Context,(CameraVehicleSignals)->Unit)->AutoCloseable)?=null
        private var signalSubscription: AutoCloseable?=null // controller thread only
        private var appliedSignalFactory: ((Context,(CameraVehicleSignals)->Unit)->AutoCloseable)?=null
        private val client=CameraCaptureClient(app,{ view,bytes,layout ->
            if (!closed.get() && settings.enabled) {
                val lease=leases[view]
                if (lease?.layout==layout) {
                    consecutiveFailures.set(0)
                    lease.submitFrame(bytes)
                }
            }
        },{ message ->
            captureFailure=message
            if (!closed.get()) controller.execute {
                clearLeases()
                val failures = consecutiveFailures.incrementAndGet()
                if (settings.enabled && failures <= 3) {
                    retry?.cancel(false)
                    retry = controller.schedule({
                        retry = null
                        if (!closed.get() && settings.enabled) {
                            captureFailure = null
                            android.util.Log.i("DiPlay-Camera", "retry capture after startup or frame failure attempt=$failures")
                            reconcile()
                        }
                    }, 5, TimeUnit.SECONDS)
                }
            }
        })

        fun apply(values: CameraSettings.Values) {
            if (closed.get()) return
            settings=values.sanitized()
            controller.execute { reconcile() }
        }
        fun status(overlay: String): String {
            if (!settings.enabled) return overlay
            if (!cameraPermissionGranted()) return "请在摄像头设置中允许使用摄像头"
            captureFailure?.let { return it }
            val status=client.status()
            return when(status.phase) {
                CameraCaptureClient.Phase.STARTING,CameraCaptureClient.Phase.FAILED -> status.message
                CameraCaptureClient.Phase.STREAMING -> "${status.message}；$overlay"
                else -> overlay
            }
        }
        private fun reconcile() {
            if (closed.get()) return
            if (!settings.enabled) {
                retry?.cancel(false); retry=null; consecutiveFailures.set(0)
                clearLeases() // Invalidate display/publication before stopping the helper.
                client.stop()
                CameraInstrumentHost.close()
                signalSubscription?.close(); signalSubscription=null; appliedSignalFactory=null
                captureFailure=null
                return
            }
            if (!cameraPermissionGranted()) {
                retry?.cancel(false); retry=null
                clearLeases(); client.stop()
                CameraInstrumentHost.close()
                signalSubscription?.close(); signalSubscription=null; appliedSignalFactory=null
                captureFailure=null
                return
            }
            CameraInstrumentHost.ensure(app)
            val factory=signalFactory
            if (factory!==appliedSignalFactory) {
                signalSubscription?.close(); signalSubscription=null; appliedSignalFactory=factory
                if (factory!=null) runCatching { factory(app,owner::updateVehicleSignals) }.onSuccess { signalSubscription=it }
            }
            // Slider gestures never reopen a failed helper; only the timed retry or an OFF/ON transition does.
            if (captureFailure!=null) return
            if (leases.isEmpty()) {
                plan.validate()
                leases=plan.regions.mapValues { (view,region) -> owner.attachVerifiedSource(view,region.layout(plan.layout),plan.kind) }
            }
            client.start(plan) // Same plan is idempotent; hidden front windows keep continuous acquisition.
        }
        fun attachSignals(factory: (Context,(CameraVehicleSignals)->Unit)->AutoCloseable): AutoCloseable {
            check(!closed.get())
            signalFactory=factory
            controller.execute { reconcile() }
            return AutoCloseable {
                if (signalFactory===factory) {
                    signalFactory=null
                    if (!closed.get()) controller.execute { reconcile() }
                }
            }
        }
        private fun clearLeases() {
            val previous=leases; leases=emptyMap()
            previous.values.forEach { it.close() }
        }
        private fun cameraPermissionGranted(): Boolean =
            app.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        override fun close() {
            if (!closed.compareAndSet(false,true)) return
            settings=CameraSettings.Values()
            controller.execute {
                retry?.cancel(false); retry=null
                clearLeases()
                signalSubscription?.close(); signalSubscription=null
                client.close()
                CameraInstrumentHost.close()
            }
            controller.shutdown()
        }
    }
}
