package com.gbscontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HostAddressTest {
    @Test
    fun `keeps a plain hostname`() {
        assertEquals("gbscontrol.local", HostAddress.normalize("gbscontrol.local"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("gbscontrol.local", HostAddress.normalize("  gbscontrol.local \n"))
    }

    @Test
    fun `strips a scheme and any path`() {
        assertEquals("192.168.1.50", HostAddress.normalize("http://192.168.1.50/slot/set?slot=A"))
    }

    @Test
    fun `strips a path without a scheme`() {
        assertEquals("192.168.1.50", HostAddress.normalize("192.168.1.50/index.html"))
    }

    @Test
    fun `drops the trailing dot of an mDNS name`() {
        assertEquals("gbscontrol.local", HostAddress.normalize("gbscontrol.local."))
    }

    @Test
    fun `unwraps a bracketed IPv6 literal`() {
        assertEquals("fe80::1", HostAddress.normalize("[fe80::1]"))
    }

    @Test
    fun `rejects an empty address`() {
        assertThrows(IllegalArgumentException::class.java) { HostAddress.normalize("   ") }
    }

    @Test
    fun `rejects an address containing whitespace`() {
        assertThrows(IllegalArgumentException::class.java) { HostAddress.normalize("gbs control.local") }
    }

    @Test
    fun `re-brackets IPv6 in a URL authority`() {
        assertEquals("http://[fe80::1]/api/v1/state", HostAddress.httpUrl("fe80::1", "/api/v1/state"))
    }

    @Test
    fun `escapes the zone separator of a link-local address`() {
        assertEquals("http://[fe80::1%25wlan0]/", HostAddress.httpUrl("fe80::1%wlan0"))
    }

    @Test
    fun `leaves an IPv4 authority unbracketed`() {
        assertEquals("http://192.168.1.50/", HostAddress.httpUrl("192.168.1.50"))
    }

    @Test
    fun `adds a leading slash to a bare path`() {
        assertEquals("http://gbscontrol.local/uc", HostAddress.httpUrl("gbscontrol.local", "uc"))
    }

    @Test
    fun `builds the websocket url`() {
        assertEquals("ws://gbscontrol.local/ws", HostAddress.webSocketUrl("gbscontrol.local"))
    }
}
