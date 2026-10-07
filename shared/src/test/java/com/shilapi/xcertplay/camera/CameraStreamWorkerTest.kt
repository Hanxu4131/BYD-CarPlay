package com.shilapi.xcertplay.camera

import org.junit.Assert.*
import org.junit.Test

class CameraStreamWorkerTest {
    private val args=arrayOf("--port=32123","--nonce="+"1".repeat(32),
        "--source-id=2","--callback-index=5","--layout=2560,1920,2560,2560","--format=NV21",
        "--policy=LEGACY_CONSTRUCTOR","--proof=USER_CONFIGURATION","--tail=7")
    @Test fun ownerVerifiedContractIsExplicitAndNotImageFormatEnum() {
        val options=CameraStreamWorker.parse(args)
        assertEquals(32123,options.port)
        assertTrue(options.source.userVerifiedSource)
        assertEquals(21,options.source.callbackContract?.rawFormat)
        assertEquals(BmmCameraCapture.PixelFormat.NV21,options.source.layoutExplicit?.format)
    }
    @Test fun errorDiagnosticsOnlyKeepExceptionType() {
        assertEquals("IllegalStateException",CameraStreamWorker.errorType("IllegalStateException: private configuration"))
        assertEquals("java.io.IOException",CameraStreamWorker.errorType("java.io.IOException: private address"))
        assertEquals("none",CameraStreamWorker.errorType("Unknown configuration value"))
        assertEquals("none",CameraStreamWorker.errorType(null))
    }
    @Test fun portsAndRemoteAddressArgumentsReject() {
        listOf("0","-1","65536","abc").forEach { port ->
            try { CameraStreamWorker.parse(args.map { if(it.startsWith("--port=")) "--port=$port" else it }.toTypedArray());fail() } catch (_:IllegalArgumentException) {}
        }
        listOf("--remotehost=127.0.0.1","--socket=legacy").forEach { extra ->
            try { CameraStreamWorker.parse(args+extra);fail() } catch (_:IllegalArgumentException) {}
        }
    }
    @Test fun unverifiedTailWrongSourceAndUnknownArgumentsReject() {
        listOf(args.map { if(it=="--tail=7") "--tail=8" else it }.toTypedArray(),
            args.map { if(it=="--source-id=2") "--source-id=3" else it }.toTypedArray(),
            args+"--unknown=1").forEach {
            try { CameraStreamWorker.parse(it);fail() } catch (_:IllegalArgumentException) {}
        }
    }
}
