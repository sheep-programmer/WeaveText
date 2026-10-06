package com.weavetext.translate.google

import android.os.IBinder
import android.os.RemoteException
import com.weavetext.translation.contract.ITranslationCallback
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RequestBrokerTest {
    private class Job(val listener: (PluginEvent) -> Unit) : PluginCall {
        var cancellations = 0
        override fun cancel() { cancellations++ }
    }
    private class Peer : ITranslationCallback {
        val deaths = mutableListOf<IBinder.DeathRecipient>()
        val events = mutableListOf<Pair<String, JSONObject>>()
        var failEvent = false
        private val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { proxy, method, args ->
            when (method.name) {
                "linkToDeath" -> { deaths += args!![0] as IBinder.DeathRecipient; null }
                "unlinkToDeath" -> deaths.remove(args!![0] as IBinder.DeathRecipient)
                "isBinderAlive", "pingBinder" -> true
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "TestCallbackBinder"
                else -> null
            }
        } as IBinder
        override fun asBinder() = binder
        override fun onEvent(requestId: String?, json: String?) {
            if (failEvent) throw RemoteException("callback disconnected")
            events += requestId.orEmpty() to JSONObject(json!!)
        }
        fun die() { deaths.toList().forEach { it.binderDied() } }
    }
    private fun broker(jobs: MutableList<Job>) = RequestBroker { _, listener -> Job(listener).also { jobs += it } }

    @Test fun replacementCancelsOldIdAndLateEventsCannotEnterTheNewRequest() {
        val jobs = mutableListOf<Job>(); val broker = broker(jobs); val peer = Peer()
        broker.request(10, "id", "{}", peer)
        broker.request(10, "id", "{}", peer)
        assertEquals(1, jobs[0].cancellations)
        jobs[0].listener(PluginEvent.result("old", null))
        jobs[1].listener(PluginEvent.result("new", null))
        assertEquals(1, peer.events.size)
        assertEquals("new", peer.events.single().second.getString("text"))
        assertTrue(peer.deaths.isEmpty())
        broker.close()
    }

    @Test fun idsAreScopedToUidAndOneClientCannotCancelAnotherClient() {
        val jobs = mutableListOf<Job>(); val broker = broker(jobs); val first = Peer(); val second = Peer()
        broker.request(10, "same", "{}", first)
        broker.request(20, "same", "{}", second)
        broker.cancel(10, "same")
        assertEquals(1, jobs[0].cancellations)
        assertEquals(0, jobs[1].cancellations)
        jobs[0].listener(PluginEvent.result("old", null))
        jobs[1].listener(PluginEvent.result("other", null))
        assertTrue(first.events.isEmpty())
        assertEquals("other", second.events.single().second.getString("text"))
        broker.close()
    }

    @Test fun callbackDeathCancelsResourcesAndDropsLateSdkResults() {
        val jobs = mutableListOf<Job>(); val broker = broker(jobs); val peer = Peer()
        broker.request(10, "id", "{}", peer)
        peer.die()
        assertEquals(1, jobs.single().cancellations)
        jobs.single().listener(PluginEvent.result("late", null))
        assertTrue(peer.events.isEmpty())
        assertTrue(peer.deaths.isEmpty())
        broker.close()
    }

    @Test fun binderFailureCancelsTheRequestWithoutLeakingDeathRegistration() {
        val jobs = mutableListOf<Job>(); val broker = broker(jobs); val peer = Peer()
        broker.request(10, "id", "{}", peer)
        peer.failEvent = true
        jobs.single().listener(PluginEvent.progress("TRANSLATING", "working"))
        assertEquals(1, jobs.single().cancellations)
        assertTrue(peer.deaths.isEmpty())
        broker.close()
    }

    @Test fun synchronousCompletionBeforeReturningItsHandleDoesNotLeaveAnActiveJob() {
        var cancellations = 0
        val broker = RequestBroker { _, listener ->
            listener(PluginEvent.result("done", null))
            PluginCall { cancellations++ }
        }
        val peer = Peer()
        broker.request(10, "id", "{}", peer)
        assertEquals("done", peer.events.single().second.getString("text"))
        assertEquals(1, cancellations)
        assertTrue(peer.deaths.isEmpty())
        broker.cancel(10, "id")
        assertEquals(1, cancellations)
        broker.close()
    }

    @Test fun unbindingOrServiceShutdownCancelsAllClientsAndSuppressesLateEvents() {
        val jobs = mutableListOf<Job>(); val broker = broker(jobs); val first = Peer(); val second = Peer()
        broker.request(10, "one", "{}", first); broker.request(20, "two", "{}", second)
        broker.cancelAll()
        assertEquals(listOf(1, 1), jobs.map { it.cancellations })
        jobs.forEach { it.listener(PluginEvent.result("late", null)) }
        assertTrue(first.events.isEmpty()); assertTrue(second.events.isEmpty())
        broker.close()
        broker.request(10, "new", "{}", first)
        assertEquals("PLUGIN_STOPPED", first.events.single().second.getString("code"))
        assertEquals(2, jobs.size)
    }

    @Test fun invalidIdIsRejectedBeforeDispatch() {
        val jobs = mutableListOf<Job>(); val broker = broker(jobs); val peer = Peer()
        broker.request(10, "x".repeat(129), "{}", peer)
        assertTrue(jobs.isEmpty())
        assertEquals("INVALID_REQUEST", peer.events.single().second.getString("code"))
        broker.close()
    }

    @Test fun oversizedEventsFailSafelyAndRemoveTheRequest() {
        val jobs = mutableListOf<Job>(); val broker = broker(jobs); val peer = Peer()
        broker.request(10, "id", "{}", peer)
        jobs.single().listener(PluginEvent.result("x".repeat(PluginProtocol.MAX_EVENT_BYTES + 1), null))
        assertEquals("RESULT_TOO_LARGE", peer.events.single().second.getString("code"))
        assertTrue(peer.deaths.isEmpty())
        broker.close()
    }
}
