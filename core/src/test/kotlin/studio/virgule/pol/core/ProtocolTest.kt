package studio.virgule.pol.core

import studio.virgule.pol.core.billing.Entitlement
import studio.virgule.pol.core.billing.OfferTerms
import studio.virgule.pol.core.billing.Plan
import studio.virgule.pol.core.billing.TransferReference
import studio.virgule.pol.core.billing.UnconfiguredBillingProvider
import studio.virgule.pol.core.client.ClientModel
import studio.virgule.pol.core.client.ClientSnapshot
import studio.virgule.pol.core.client.LinkState
import studio.virgule.pol.core.crypto.Hkdf
import studio.virgule.pol.core.crypto.P256
import studio.virgule.pol.core.demo.DemoGateway
import studio.virgule.pol.core.domain.DeliveryState
import studio.virgule.pol.core.domain.Direction
import studio.virgule.pol.core.domain.Sms
import studio.virgule.pol.core.link.Handshake
import studio.virgule.pol.core.link.Limits
import studio.virgule.pol.core.link.ProtocolException
import studio.virgule.pol.core.link.RecordReader
import studio.virgule.pol.core.link.encodeRecord
import studio.virgule.pol.core.proto.RecentIds
import studio.virgule.pol.core.proto.SmsPage
import studio.virgule.pol.core.proto.T
import studio.virgule.pol.core.proto.Validate
import studio.virgule.pol.core.proto.Wire
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun hkdfMatchesRfc5869Case1() {
        val okm = Hkdf.derive(hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.hex())
    }

    @Test fun vectorsMatchIndependentImplementation() {
        // Values produced by tools/vectors.py (Python hashlib/hmac), so Kotlin and Swift can be checked against a
        // third implementation. See protocol/vectors.json.
        val transcript = ByteArray(32) { it.toByte() }
        assertEquals(VECTOR_SAS, Handshake.sas(transcript))
        val keys = Handshake.keys(ByteArray(32) { 7 }, transcript)
        assertEquals(VECTOR_K_C2G, keys.clientToGateway.hex())
        assertEquals(VECTOR_K_G2C, keys.gatewayToClient.hex())
        val pub = byteArrayOf(4) + ByteArray(64) { it.toByte() }
        assertEquals("6bcda99d58e5962b91d70f9c5d248e07", Handshake.deviceId(pub).hex())
        assertEquals("000310aabb", encodeRecord(0x10, hex("aabb")).hex())
    }

    @Test fun publicKeyRoundTripAndRejection() {
        val kp = P256.generateKeyPair()
        val enc = P256.encode(kp.public)
        assertEquals(65, enc.size)
        assertTrue(P256.encode(P256.decode(enc)).contentEquals(enc))
        val bad = enc.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
        assertFailsWith<Exception> { P256.decode(bad) }
        assertFailsWith<Exception> { P256.decode(ByteArray(65)) }
    }

    @Test fun recordReaderBoundsAndReassembly() {
        val rec = encodeRecord(0x10, ByteArray(500) { 1 })
        val r = RecordReader()
        assertTrue(r.feed(rec.copyOfRange(0, 1)).isEmpty())
        assertTrue(r.feed(rec.copyOfRange(1, 300)).isEmpty())
        assertEquals(1, r.feed(rec.copyOfRange(300, rec.size)).size)
        assertFailsWith<ProtocolException> { RecordReader().feed(byteArrayOf(0x7F, 0x7F, 0x10)) }
        assertFailsWith<ProtocolException> { RecordReader().feed(byteArrayOf(0, 1, 0x55)) }
        assertFailsWith<ProtocolException> { RecordReader().feed(byteArrayOf(0, 0, 0x10)) }
    }

    @Test fun validation() {
        assertEquals("+989121234567", Validate.dialNumber("+98 (912) 123-4567"))
        assertEquals("09121234567", Validate.dialNumber("۰۹۱۲۱۲۳۴۵۶۷"))
        assertNull(Validate.dialNumber("*140#"), "carrier codes are not dialled as calls")
        assertNull(Validate.dialNumber("12+3"))
        assertNull(Validate.dialNumber("tel:123"))
        assertNull(Validate.dialNumber(""))
        assertEquals("*140#", Validate.ussdCode("*140#"))
        assertEquals("**21*09121234567#", Validate.ussdCode("**21*0912 123 4567#"))
        assertNull(Validate.ussdCode("*140"))
        assertNull(Validate.ussdCode("140#"))
        assertNull(Validate.smsBody("   "))
        assertNull(Validate.smsBody("x".repeat(1601)))
        assertEquals("ab", Validate.smsBody("a\u0000b"))
        assertTrue(Validate.clientMessageId("123e4567-e89b-12d3-a456-426614174000"))
        assertFalse(Validate.clientMessageId("../../etc"))
    }

    @Test fun duplicateRequestsAreExecutedOnce() {
        val ids = RecentIds(capacity = 3, ttlMs = 1000)
        assertTrue(ids.firstSeen("a", 0)); assertFalse(ids.firstSeen("a", 10))
        assertTrue(ids.firstSeen("b", 20)); assertTrue(ids.firstSeen("c", 30)); assertTrue(ids.firstSeen("d", 40))
        assertTrue(ids.firstSeen("a", 50), "evicted by capacity")
        assertTrue(ids.firstSeen("b", 5000), "expired by ttl")
    }

    @Test fun pagesStayBelowLimit() {
        val items = (1..400L).map { Sms(it, 1, "+1555", "x".repeat(300), it, Direction.IN) }
        val pages = Wire.pages(items, 100) { list, more -> Wire.encode(T.SMS_PAGE, SmsPage.serializer(), SmsPage(list, more)) }
        assertTrue(pages.all { it.toByteArray().size <= Limits.MAX_MESSAGE })
        val back = pages.flatMap { Wire.body(Wire.decode(it)!!, SmsPage.serializer())!!.items }
        assertEquals(400, back.size)
        assertFalse(Wire.body(Wire.decode(pages.last())!!, SmsPage.serializer())!!.more)
        val empty = Wire.pages(emptyList<Sms>(), 10) { list, more -> Wire.encode(T.SMS_PAGE, SmsPage.serializer(), SmsPage(list, more)) }
        assertEquals(1, empty.size)
    }

    @Test fun malformedMessagesAreIgnored() {
        assertNull(Wire.decode("not json"))
        assertNull(Wire.decode("""{"id":1}"""))
        assertNull(Wire.decode("x".repeat(Limits.MAX_MESSAGE + 1)))
        val env = Wire.decode("""{"t":"sms.send","b":{"cmid":5},"extra":true}""")!!
        assertNull(Wire.body(env, studio.virgule.pol.core.proto.SmsSend.serializer()))
    }

    @Test fun demoModeFlowsEndToEndWithoutTelephony() {
        var snap = ClientSnapshot()
        lateinit var model: ClientModel
        val demo = DemoGateway({ json -> model.onMessage(json) })
        model = ClientModel(send = { demo.receive(it); true }, onChange = { snap = it }, clientName = "Test")
        model.setLink(LinkState.CONNECTED, demo = true)
        model.onSessionUp()
        assertNotNull(snap.status)
        assertTrue(snap.conversations.isNotEmpty())
        assertTrue(snap.calls.isNotEmpty())
        assertEquals("Sara Ahmadi", snap.nameFor("555-555-0101"))
        assertEquals(ClientModel.ActionResult.Ok, model.sendSms("+15555550104", "hello", 1))
        val sent = snap.messages.values.single { it.body == "hello" }
        assertEquals(DeliveryState.DELIVERED, sent.state)
        assertTrue(sent.id > 0, "local pending bubble replaced by gateway copy")
        assertEquals(ClientModel.ActionResult.Invalid, model.dial("*140#", 1))
        assertEquals(ClientModel.ActionResult.Ok, model.dial("+15555550101", 2))
        assertEquals(1, snap.liveCalls.size)
        model.callAction(snap.liveCalls[0].callId, "end")
        assertTrue(snap.liveCalls.isEmpty())
        assertEquals(ClientModel.ActionResult.Ok, model.ussd("*140#", 1))
        assertTrue(snap.ussd!!.text!!.contains("Simulated"))
        val unread = snap.conversations.first { it.unread > 0 }
        model.markRead(unread.thread)
        assertEquals(0, snap.conversations.first { it.thread == unread.thread }.unread)
    }

    @Test fun pendingSendFailsThenRetries() {
        var clock = 0L
        var snap = ClientSnapshot()
        val sent = mutableListOf<String>()
        val model = ClientModel(send = { sent += it; true }, onChange = { snap = it }, now = { clock })
        assertEquals(ClientModel.ActionResult.NotConnected, model.sendSms("+15555550101", "hi", null))
        assertTrue(sent.isEmpty())
        clock += 100_000
        model.expirePending()
        val failed = snap.messages.values.single()
        assertEquals(DeliveryState.FAILED, failed.state)
        model.setLink(LinkState.CONNECTED)
        assertEquals(ClientModel.ActionResult.Ok, model.retry(failed.cmid!!))
        assertTrue(sent.single().contains(failed.cmid!!))
    }

    @Test fun subscriptionTermsAndBoundary() {
        val tehran = ZoneId.of("Asia/Tehran")
        val start = Instant.parse("2027-01-31T08:00:00Z")
        val terms = OfferTerms.startingAt(start, tehran)
        assertEquals(Instant.parse("2027-02-28T08:00:00Z"), terms.trialEnd)
        assertEquals(500_000L, terms.priceTomans)
        assertEquals(5_000_000L, Plan.PRICE_RIALS)
        val provider = UnconfiguredBillingProvider()
        assertFalse(provider.configured)
        runBlocking {
            assertEquals(Entitlement.NotConfigured, provider.refresh("x"))
            assertTrue(provider.startCheckout(studio.virgule.pol.core.billing.BillingRequest(Plan.ID, "x", Plan.PRICE_RIALS)).isFailure)
        }
        assertFalse(Entitlement.NotConfigured.unlocksPaidFeatures)
        assertFalse(Entitlement.PendingVerification("123456", start).unlocksPaidFeatures, "an unverified transfer grants nothing")
        assertEquals("1234567890", TransferReference.normalize("۱۲۳۴ ۵۶۷۸۹۰"))
        assertNull(TransferReference.normalize("12ab"))
    }

    companion object {
        const val VECTOR_SAS = "974705"
        const val VECTOR_K_C2G = "0fcf73cf646ed2deff5378cb5a41d6a4b013e33a4ac8e415ea37826f63ca0c0d"
        const val VECTOR_K_G2C = "b8ac50668abbd8c08f791923ab9ffa0c2508b9a75ee40194bd918f5621bbf45f"
    }
}
