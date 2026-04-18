package app.seamlessupdate.client

import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class ModernTLSSocketFactory : SSLSocketFactory() {

    private val wrapped: SSLSocketFactory = try {
        SSLContext.getInstance("TLS").also { it.init(null, null, null) }.socketFactory
    } catch (e: Exception) {
        throw RuntimeException(e)
    }

    override fun getDefaultCipherSuites(): Array<String> = wrapped.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = wrapped.supportedCipherSuites

    override fun createSocket(): Socket =
        configureSocket(wrapped.createSocket())

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        configureSocket(wrapped.createSocket(s, host, port, autoClose))

    override fun createSocket(host: String, port: Int): Socket =
        configureSocket(wrapped.createSocket(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        configureSocket(wrapped.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        configureSocket(wrapped.createSocket(host, port))

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        configureSocket(wrapped.createSocket(address, port, localAddress, localPort))

    private fun configureSocket(socket: Socket): Socket {
        (socket as SSLSocket).enabledProtocols = arrayOf("TLSv1.3")
        return socket
    }
}