package com.promenar.infinity.data.linux

import android.content.SharedPreferences
import com.promenar.infinity.data.backup.BackupPreferencePolicy
import com.promenar.infinity.data.tailnet.DisabledTailnetRouting
import com.promenar.infinity.domain.linux.*
import com.promenar.infinity.domain.network.AgentNetworkPolicy
import com.promenar.infinity.domain.network.NetworkAccessDenied
import java.lang.reflect.Proxy
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class LinuxMirrorAndroidTest {
    @Test fun environmentMirrorPreferencesAreDeviceLocal() {
        // 镜像和测速绑定本机 Linux 环境；与独立 Tailscale 偏好使用相同的备份边界。
        for (key in listOf(LinuxMirrorSharedPreferences.KEY_MIRROR, LinuxMirrorSharedPreferences.KEY_CUSTOM,
            LinuxMirrorSharedPreferences.KEY_LATENCIES)) {
            assertFalse(BackupPreferencePolicy.isAllowed(LinuxMirrorSharedPreferences.PREFERENCES_NAME, key))
        }
    }

    @Test fun preferencesPersistChoiceAndNullableLatencies() {
        val backing = mutableMapOf<String, String?>()
        val preferences = LinuxMirrorSharedPreferences(preferences(backing))
        assertEquals(LinuxMirrorId.TUNA, preferences.readChoice().id)
        val choice = LinuxMirrorChoice(LinuxMirrorId.CUSTOM, "https://mirror.example/alpine")
        preferences.saveChoice(choice)
        preferences.saveLatencies(mapOf(LinuxMirrorId.TUNA to 42, LinuxMirrorId.USTC to null))
        val restored = LinuxMirrorSharedPreferences(preferences(backing))
        assertEquals(choice, restored.readChoice())
        assertEquals(mapOf(LinuxMirrorId.TUNA to 42L, LinuxMirrorId.USTC to null), restored.readLatencies())
        backing["mirror"] = "UNKNOWN"
        backing["latencies"] = "not json"
        assertEquals(LinuxMirrorId.TUNA, restored.readChoice().id)
        assertTrue(restored.readLatencies().isEmpty())
    }

    @Test fun redirectsAllowLanAndRevalidateMulticast(): Unit = runBlocking {
        val seen = mutableListOf<String>()
        val client = client { request ->
            seen += request.url.toString()
            if (seen.size == 1) response(request, 302, "http://lan.example/index") else response(request, 200)
        }
        val policy = AgentNetworkPolicy(resolver = { host -> arrayOf(address(if (host == "lan.example") byteArrayOf(192.toByte(), 168.toByte(), 1, 4) else byteArrayOf(93, 184.toByte(), 1, 1))) })
        assertTrue(LinuxMirrorHttpClient(client, policy, DisabledTailnetRouting).head("https://public.example/index"))
        assertEquals(listOf("https://public.example/index", "http://lan.example/index"), seen)
        val multicast = client { request -> response(request, 302, "http://224.0.0.1/index") }
        val literalPolicy = AgentNetworkPolicy(resolver = { arrayOf(InetAddress.getByName(it)) })
        try { LinuxMirrorHttpClient(multicast, literalPolicy, DisabledTailnetRouting).head("http://127.0.0.1/index"); fail() }
        catch (failure: NetworkAccessDenied) { assertEquals("ADDRESS_DENIED", failure.code) }
    }

    @Test fun headAcceptsLoopbackHttp(): Unit = runBlocking {
        val methods = mutableListOf<String>()
        val client = client { request -> methods += request.method; response(request, 200) }
        val policy = AgentNetworkPolicy(resolver = { arrayOf(address(byteArrayOf(127, 0, 0, 1))) })
        assertTrue(LinuxMirrorHttpClient(client, policy, DisabledTailnetRouting).head("http://lan.example/index"))
        assertEquals(listOf("HEAD"), methods)
    }

    @Test fun nonSuccessHttpStatusIsNotALatencySuccess(): Unit = runBlocking {
        val client = client { request -> response(request, 404) }
        val policy = AgentNetworkPolicy(resolver = { arrayOf(address(byteArrayOf(93, 184.toByte(), 1, 1))) })
        assertFalse(LinuxMirrorHttpClient(client, policy, DisabledTailnetRouting).head("https://public.example/index"))
    }

    private fun client(handle: (Request) -> Response) = OkHttpClient.Builder().addInterceptor { handle(it.request()) }.build()
    private fun response(request: Request, code: Int, location: String? = null): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test").body("".toResponseBody())
        .apply { location?.let { header("Location", it) } }.build()
    private fun address(bytes: ByteArray) = InetAddress.getByAddress(bytes)

    /** 仅替换 Android 持久化边界；序列化、键名与失败处理使用真实实现。 */
    private fun preferences(backing: MutableMap<String, String?>): SharedPreferences {
        val changes = mutableMapOf<String, String?>()
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { changes[args!![0] as String] = args[1] as String?; proxy }
                "commit" -> { backing.putAll(changes); changes.clear(); true }
                else -> error("未预期的编辑操作：${method.name}")
            }
        }
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> backing.getOrDefault(args!![0] as String, args[1] as String?)
                "edit" -> editor
                else -> error("未预期的偏好操作：${method.name}")
            }
        } as SharedPreferences
    }
}
