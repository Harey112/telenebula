package com.telenebula.dex.tls

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Keystore is the one part of Dex no unit test can reach, and the way a key is restricted
 * decides whether a handshake can happen at all: TLS hands the key an already-hashed transcript,
 * so a key that allows only SHA-256 is refused when the connection is made, not when it is made.
 */
@RunWith(AndroidJUnit4::class)
class DexTlsTest {
    private val alias = "tn.dex.tls.test"
    private val tls = DexTls(alias)

    @After
    fun tearDown() {
        runCatching { tls.reset() }
    }

    private fun trustEverything(): SSLContext {
        val manager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
    }

    @Test
    fun aBrowserCompletesTheHandshakeAndIsServed() {
        val loopback = InetAddress.getLoopbackAddress()
        val server = tls.serverSocketFactory().createServerSocket() as SSLServerSocket
        server.bind(InetSocketAddress(loopback, 0))
        val served = StringBuilder()
        val accepting = thread {
            runCatching {
                server.accept().use { socket ->
                    val first = socket.getInputStream().read()
                    served.append(first.toChar())
                    socket.getOutputStream().apply {
                        write('B'.code)
                        flush()
                    }
                }
            }
        }
        try {
            val client = trustEverything().socketFactory.createSocket(loopback, server.localPort) as SSLSocket
            client.use {
                it.startHandshake()
                it.getOutputStream().apply {
                    write('A'.code)
                    flush()
                }
                assertEquals('B'.code, it.getInputStream().read())
            }
        } finally {
            accepting.join(5_000)
            server.close()
        }
        assertEquals("A", served.toString())
    }

    @Test
    fun theKeyIsKeptAndItsFingerprintIsStable() {
        val first = tls.fingerprintSha256
        assertTrue(first.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
        assertEquals(first, DexTls(alias).fingerprintSha256)
    }

    /** A key an older build restricted to one digest must be replaced, not served and then refused. */
    @Test
    fun aKeyThatCannotSignAHandshakeIsReplaced() {
        val before = tls.fingerprintSha256
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(store.containsAlias(alias))
        LegacyKey.create(alias)
        val after = DexTls(alias).fingerprintSha256
        assertTrue("the unusable key was served instead of replaced", after != before)
        aBrowserCompletesTheHandshakeAndIsServed()
    }
}
