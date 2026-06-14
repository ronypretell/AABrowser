package com.kododake.aavideo.net

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import org.json.JSONObject
import kotlin.concurrent.thread

object LocalDnsProxy {
    private var serverSocket: ServerSocket? = null
    private var proxyPort: Int = 0
    private var isRunning = false

    fun start(): Int {
        if (isRunning) return proxyPort
        try {
            serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            proxyPort = serverSocket!!.localPort
            isRunning = true
            thread(start = true, name = "DnsProxyServer") {
                runServer()
            }
            Log.d("AABrowser", "Local DNS Proxy started on port $proxyPort")
            return proxyPort
        } catch (e: Exception) {
            Log.e("AABrowser", "Failed to start Local DNS Proxy", e)
            return 0
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    private fun runServer() {
        val ss = serverSocket ?: return
        while (isRunning) {
            try {
                val clientSocket = ss.accept()
                thread {
                    handleClient(clientSocket)
                }
            } catch (e: Exception) {
                if (!isRunning) break
            }
        }
    }

    private fun handleClient(clientSocket: Socket) {
        try {
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            val headerLine = readLine(clientIn) ?: return
            if (headerLine.isBlank()) {
                clientSocket.close()
                return
            }

            val parts = headerLine.split(" ")
            if (parts.size < 2) {
                clientSocket.close()
                return
            }

            val method = parts[0].uppercase()
            val target = parts[1]

            if (method == "CONNECT") {
                var line: String?
                while (true) {
                    line = readLine(clientIn)
                    if (line.isNullOrBlank()) break
                }

                val hostPort = target.split(":")
                val host = hostPort[0]
                val port = if (hostPort.size > 1) hostPort[1].toInt() else 443

                val ip = resolveDnsWithGoogleDoH(host) ?: host

                val destSocket = try {
                    Socket(ip, port)
                } catch (e: Exception) {
                    clientSocket.close()
                    return
                }

                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                clientOut.flush()

                val t1 = thread {
                    bridge(clientIn, destSocket.getOutputStream())
                }
                val t2 = thread {
                    bridge(destSocket.getInputStream(), clientOut)
                }
                t1.join()
                t2.join()
                destSocket.close()
            } else {
                var host = ""
                var port = 80
                var path = target
                if (target.startsWith("http://")) {
                    val uriStr = target.substring(7)
                    val slashIdx = uriStr.indexOf("/")
                    val hostPart = if (slashIdx != -1) uriStr.substring(0, slashIdx) else uriStr
                    path = if (slashIdx != -1) uriStr.substring(slashIdx) else "/"
                    
                    val hostPort = hostPart.split(":")
                    host = hostPort[0]
                    if (hostPort.size > 1) port = hostPort[1].toInt()
                }

                if (host.isBlank()) {
                    clientSocket.close()
                    return
                }

                val ip = resolveDnsWithGoogleDoH(host) ?: host

                val destSocket = try {
                    Socket(ip, port)
                } catch (e: Exception) {
                    clientSocket.close()
                    return
                }

                val destOut = destSocket.getOutputStream()
                val destIn = destSocket.getInputStream()

                val newRequestLine = "$method $path ${parts.getOrNull(2) ?: "HTTP/1.1"}\r\n"
                destOut.write(newRequestLine.toByteArray())

                var line: String?
                while (true) {
                    line = readLine(clientIn)
                    if (line == null || line.isBlank()) {
                        destOut.write("\r\n".toByteArray())
                        break
                    }
                    destOut.write("$line\r\n".toByteArray())
                }
                destOut.flush()

                val t1 = thread {
                    bridge(clientIn, destOut)
                }
                val t2 = thread {
                    bridge(destIn, clientOut)
                }
                t1.join()
                t2.join()
                destSocket.close()
            }
        } catch (_: Exception) {
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun bridge(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        try {
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                output.flush()
            }
        } catch (_: Exception) {}
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var c: Int
        try {
            while (input.read().also { c = it } != -1) {
                if (c == '\n'.code) break
                if (c == '\r'.code) continue
                sb.append(c.toChar())
            }
        } catch (_: Exception) {
            return null
        }
        return sb.toString()
    }

    private fun resolveDnsWithGoogleDoH(host: String): String? {
        if (host == "localhost" || host == "127.0.0.1" || host.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))) {
            return host
        }
        try {
            val url = URL("https://dns.google/resolve?name=${host}&type=A")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            val answer = json.optJSONArray("Answer")
            if (answer != null && answer.length() > 0) {
                for (i in 0 until answer.length()) {
                    val entry = answer.getJSONObject(i)
                    val type = entry.optInt("type")
                    if (type == 1) {
                        val ip = entry.optString("data")
                        if (ip.isNotBlank()) {
                            Log.d("AABrowser", "DoH Resolved: $host -> $ip")
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AABrowser", "Failed to resolve $host via Google DoH: ${e.message}")
        }
        return try {
            val ip = InetAddress.getByName(host).hostAddress
            Log.d("AABrowser", "System Resolved: $host -> $ip")
            ip
        } catch (e: Exception) {
            null
        }
    }
}
