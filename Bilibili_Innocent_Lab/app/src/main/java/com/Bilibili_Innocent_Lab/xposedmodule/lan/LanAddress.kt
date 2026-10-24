package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI

internal object LanAddress {
    fun privateV4(value: String): Inet4Address? {
        if (!value.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) return null
        val octets = value.split('.').map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
        if (octets.map(Int::toString).joinToString(".") != value) return null
        val a = octets[0]; val b = octets[1]
        if (!(a == 10 || a == 172 && b in 16..31 || a == 192 && b == 168 || a == 169 && b == 254)) return null
        return InetAddress.getByAddress(octets.map(Int::toByte).toByteArray()) as Inet4Address
    }
    fun url(value: String, expectedHost: String? = null): URI {
        val uri = URI(value)
        require(uri.scheme == "http" && uri.rawUserInfo == null && uri.rawFragment == null)
        require(privateV4(uri.host.orEmpty()) != null && (expectedHost == null || uri.host == expectedHost))
        require(uri.port == -1 || uri.port in 1..65535)
        return uri
    }
    fun sameOrigin(base: URI, path: String): URI = base.resolve(path).also {
        url(it.toString(), base.host)
        require(it.port == base.port && it.scheme == base.scheme)
    }
}
