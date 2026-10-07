package com.shilapi.xcertplay.camera

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CameraVerifiedBmmStreamTest {
    private class Backend(private val malformed: Boolean=false):BmmCameraCapture.Backend {
        var consumer:((ByteArray,List<Int>,Long)->Unit)?=null
        val calls=mutableListOf<String>()
        override fun publicOpen(id:Int):Any?=this
        override fun mappingMissing()=false
        override fun constructLegacy(id:Int):Any=error("not used")
        override fun legacyOpen(camera:Any)=false
        override fun callback(camera:Any,consumer:(ByteArray,List<Int>,Long)->Unit) { this.consumer=consumer }
        override fun clearCallback(camera:Any) { calls.add("clear");consumer=null }
        override fun enable(camera:Any,index:Int)=true
        override fun start(camera:Any):Boolean {
            consumer?.invoke(ByteArray(if(malformed) 7372808 else 7372807),listOf(2560,1920,21,7372800,5),1)
            return true
        }
        override fun disable(camera:Any,index:Int) { calls.add("disable") }
        override fun stop(camera:Any) { calls.add("stop") }
        override fun close(camera:Any) { calls.add("close") }
    }
    @Test fun tailedCallbackCarriesExplicitPrefixWithoutExtraBmmCopy() {
        val backend=Backend();val latch=CountDownLatch(1)
        var metadata:BmmCameraCapture.Metadata?=null
        val session=BmmCameraCapture(backend).stream(CameraCaptureProfiles.confirmedBydAvm().source,
            BmmCameraCapture.FrameConsumer { bytes,m -> assertEquals(7372807,bytes.size);metadata=m;latch.countDown() })
        assertTrue(latch.await(2,TimeUnit.SECONDS));assertEquals(7372800,metadata?.payloadBytes)
        session.close();session.result.get(2,TimeUnit.SECONDS)
        assertEquals(listOf("clear","disable","stop","close"),backend.calls)
    }
    @Test fun unknownTailIsNeverDelivered() {
        val backend=Backend(true);val latch=CountDownLatch(1)
        val session=BmmCameraCapture(backend).stream(CameraCaptureProfiles.confirmedBydAvm().source,
            BmmCameraCapture.FrameConsumer { _,_ -> latch.countDown() })
        assertTrue(session.ready.get(2,TimeUnit.SECONDS));assertEquals(1L,latch.count)
        session.close();session.result.get(2,TimeUnit.SECONDS)
    }
}
