package xa.refile.core.backup

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 自定义 Hosts 的 OkHttp [Dns] 实现（spec §5.3.1 / Task 5.3.1）。
 *
 * 用于让 TMDB 域名在中国大陆可直连（规避 DNS 污染）：
 * - 命中 [HostsConfig.entries] 的域名：返回配置的候选 IP 列表，OkHttp 按顺序逐个尝试
 *   连接，前一个失败自动切下一个（这就是「多条轮询 + 失败切换」的实现）。
 * - 每次命中 lookup 会基于内部计数器轮换起始 IP，实现负载轮询（不同请求起始 IP 不同）。
 * - 未命中或 [HostsConfig.enabled] 为 false：回退到 [systemDns]。
 *
 * TLS SNI/证书校验：OkHttp 默认用请求 URL 的 hostname 做 SNI 和证书验证，[Dns] 只负责
 * 返回 IP，故原域名的 SNI/证书校验自动保留——本类不需要额外处理。
 *
 * @param config hosts 配置（开关 + 条目）。
 * @param systemDns 回退用的系统 DNS，默认 [Dns.SYSTEM]；测试可注入假实现。
 */
class HostsDns(
    private val config: HostsConfig,
    private val systemDns: Dns = Dns.SYSTEM,
) : Dns {

    /** 轮询计数器，每次命中自增，用于轮换起始 IP。 */
    private val counter = AtomicInteger(0)

    override fun lookup(hostname: String): List<InetAddress> {
        // 总开关关闭 → 全部走系统 DNS。
        if (!config.enabled) return systemDns.lookup(hostname)

        // 命中 hosts（忽略大小写匹配）。
        val entry = config.entries.firstOrNull { it.hostname.equals(hostname, ignoreCase = true) }
            ?: return systemDns.lookup(hostname)

        val ips = entry.ips
        // ips 为空 → 回退系统 DNS（避免返回空列表触发 OkHttp 的 UnknownHostException）。
        if (ips.isEmpty()) return systemDns.lookup(hostname)

        // 轮换起始 IP：每次 lookup 取 counter 自增后对 size 取模，把列表前 n 项挪到尾部。
        val n = counter.getAndIncrement().let { Math.floorMod(it, ips.size) }
        val rotated = ips.drop(n) + ips.take(n)

        // 把 IP 字面量转成 InetAddress。getByName 对纯 IP 字面量直接构造、不发 DNS 查询。
        // 任一转换失败抛 UnknownHostException（OkHttp 要求返回非空列表）。
        return rotated.map { ip ->
            try {
                InetAddress.getByName(ip)
            } catch (e: UnknownHostException) {
                throw e
            } catch (e: Throwable) {
                throw UnknownHostException("无法解析 hosts IP: $ip (${e.message})")
            }
        }
    }
}
