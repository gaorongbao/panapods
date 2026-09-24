package com.panapods.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RacePacketTest {

    @Test
    fun `toBytes and fromBytes round trip without payload`() {
        val packet = RacePacket(type = RaceType.CMD_NEED_RESP, raceId = 0x1234)
        val bytes = packet.toBytes()

        assertEquals(6, bytes.size)
        val parsed = RacePacket.fromBytes(bytes)
        assertTrue(parsed != null)
        assertEquals(packet, parsed)
        assertEquals(RacePacket.HEADER_MMI, parsed!!.cmdHeader)
        assertEquals(RaceType.CMD_NEED_RESP, parsed.type)
        assertEquals(0x1234, parsed.raceId)
        assertNull(parsed.payload)
    }

    @Test
    fun `toBytes and fromBytes round trip with payload`() {
        val payload = byteArrayOf(0x01, 0x02, 0x7F, 0xFF.toByte())
        val packet = RacePacket(
            cmdHeader = RacePacket.HEADER_MMI,
            type = RaceType.RESPONSE,
            raceId = 0x0045,
            payload = payload
        )
        val parsed = RacePacket.fromBytes(packet.toBytes())
        assertEquals(RacePacket.HEADER_MMI, parsed?.cmdHeader)
        assertEquals(RaceType.RESPONSE, parsed?.type)
        assertEquals(0x45, parsed?.raceId)
        assertArrayEquals(payload, parsed?.payload)
    }

    @Test
    fun `fromBytes rejects truncated packets`() {
        assertNull(RacePacket.fromBytes(byteArrayOf(0x05)))
        assertNull(RacePacket.fromBytes(byteArrayOf(0x05, 0x5A, 0x02)))
        assertNull(RacePacket.fromBytes(byteArrayOf(0x05, 0x5A, 0x02, 0x00, 0x34)))
    }

    @Test
    fun `channel header nibble is extracted`() {
        val packet = RacePacket(
            cmdHeader = RacePacket.HEADER_FOTA,
            type = RaceType.INDICATION,
            raceId = 0x0A0B,
            payload = byteArrayOf(0x05, 0x5D)
        )
        val parsed = RacePacket.fromBytes(packet.toBytes())
        assertEquals(RacePacket.HEADER_FOTA, parsed?.cmdHeader)
        assertEquals(RaceType.INDICATION, parsed?.type)
    }

    @Test
    fun `type predicates work`() {
        assertTrue(RacePacket(type = RaceType.RESPONSE, raceId = 1).isResponse())
        assertFalse(RacePacket(type = RaceType.RESPONSE, raceId = 1).isIndication())
        assertTrue(RacePacket(type = RaceType.INDICATION, raceId = 1).isIndication())
        assertTrue(RacePacket(type = RaceType.CMD_NEED_RESP, raceId = 1).isCommand())
        assertTrue(RacePacket(type = RaceType.CMD_NO_RESP, raceId = 1).isCommand())
        assertFalse(RacePacket(type = RaceType.RESPONSE, raceId = 1).isCommand())
    }

    @Test
    fun `payloadToHex formats bytes`() {
        val packet = RacePacket(
            type = RaceType.INDICATION,
            raceId = 0x5D,
            payload = byteArrayOf(0x05, 0x00, 0x64.toByte())
        )
        assertEquals("05 00 64", packet.payloadToHex())
    }

    @Test
    fun `equals and hashCode honor payload contents`() {
        val a = RacePacket(type = RaceType.RESPONSE, raceId = 7, payload = byteArrayOf(1, 2))
        val b = RacePacket(type = RaceType.RESPONSE, raceId = 7, payload = byteArrayOf(1, 2))
        val c = RacePacket(type = RaceType.RESPONSE, raceId = 7, payload = byteArrayOf(1, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
    }
}
