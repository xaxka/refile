package xa.refile.core.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 通过 DNS-over-HTTPS (DoH) 解析域名候选 IP（测试反馈 Item 13）。
 *
 * 问题背景：应用预设后 hostname 的 ips 列表为空，导致「测试」「自动选优」按钮均不可用。
 * 本类通过 DoH 服务自动解析域名获取候选 IP，无需用户手动查找填入。
 *
 * 解析数据源：DoH 服务（[dohProviders]）：AliDNS + DNSPod（国内可直连）。
 *
 * 注意：Cloudflare（1.1.1.1）与 Google（8.8.8.8）等境外 DoH/DoT 在中国大陆普遍不可达，
 * 不再作为候选；如需自定义可构造时传入 [dohProviders]。
 *
 * 解析结果去重，仅返回 A 记录（IPv4）；IPv6 暂不支持（TMDB 主要走 IPv4）。
 *
 * @param baseClient 可注入的 OkHttpClient（测试用）；生产环境默认新建，超时 8s。
 * @param dohProviders DoH JSON API 服务商 URL 列表（按优先级）；默认 AliDNS + DNSPod。
 */
class HostsIpResolver(
    baseClient: OkHttpClient = OkHttpClient(),
    private val dohProviders: List<String> = DEFAULT_DOH_PROVIDERS,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * 解析 [hostname] 的 IPv4 地址列表。
     *
     * 依次尝试 [dohProviders] 中的 DoH 服务，首个成功返回非空结果即停止。
     * 所有 DoH 服务都失败或返回空时返回空列表。
     *
     * @param hostname 待解析域名（如 `api.themoviedb.org`）。
     * @return 去重后的 IPv4 字符串列表；失败返回空列表。
     */
    suspend fun resolve(hostname: String): List<String> = withContext(Dispatchers.IO) {
        for (provider in dohProviders) {
            val ips = runCatching { resolveViaDoH(provider, hostname) }.getOrDefault(emptyList())
            if (ips.isNotEmpty()) return@withContext ips
        }
        emptyList()
    }

    /**
     * 通过单个 DoH 服务解析域名。
     *
     * DoH JSON API 返回格式（RFC 8484 的 JSON 变体）：
     * ```json
     * {"Status":0,"Answer":[{"name":"...","type":1,"TTL":300,"data":"1.2.3.4"}, ...]}
     * ```
     * type=1 为 A 记录，data 字段即 IPv4 地址。
     */
    private fun resolveViaDoH(providerUrl: String, hostname: String): List<String> {
        val encodedHostname = URLEncoder.encode(hostname, "UTF-8")
        val request = Request.Builder()
            .url("$providerUrl?name=$encodedHostname&type=A")
            .header("Accept", "application/dns-json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val root = json.parseToJsonElement(body).jsonObject
            // Status 0 = NOERROR
            if (root["Status"]?.jsonPrimitive?.contentOrNull != "0") return emptyList()
            val answer = root["Answer"]?.jsonArray ?: return emptyList()
            return answer
                .filterIsInstance<JsonObject>()
                .filter { it["type"]?.jsonPrimitive?.contentOrNull == "1" }
                .mapNotNull { it["data"]?.jsonPrimitive?.contentOrNull }
                .filter { isValidIPv4(it) }
                .distinct()
        }
    }

    /** 简单 IPv4 格式校验（4 段数字，每段 0-255）。 */
    private fun isValidIPv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val n = part.toIntOrNull() ?: return false
            n in 0..255
        }
    }

    private companion object {
        const val TIMEOUT_MS = 8_000L

        /** 默认 DoH JSON API 服务商列表（按优先级：AliDNS → DNSPod，均为国内可直连）。 */
        val DEFAULT_DOH_PROVIDERS = listOf(
            "https://dns.alidns.com/resolve",
            "https://doh.pub/dns-query",
        )
    }
}
