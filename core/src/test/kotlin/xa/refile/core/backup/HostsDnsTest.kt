package xa.refile.core.backup

import com.google.common.truth.Truth.assertThat
import okhttp3.Dns
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class HostsDnsTest {

    /** 假系统 DNS：对任意 hostname 返回固定 IP，便于断言「未命中走 systemDns」。 */
    private fun fakeSystemDns(ip: String): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName(ip))
    }

    @Test
    fun `命中 hosts 返回配置的 IP`() {
        val config = HostsConfig(
            enabled = true,
            entries = listOf(
                HostEntry(HostPresets.TMDB_API, listOf("13.224.0.1")),
            ),
        )
        val dns = HostsDns(config, systemDns = fakeSystemDns("9.9.9.9"))

        val result = dns.lookup(HostPresets.TMDB_API)

        assertThat(result).isNotEmpty()
        assertThat(result.map { it.hostAddress }).contains("13.224.0.1")
    }

    @Test
    fun `命中 hosts 多条 IP 全部返回`() {
        val config = HostsConfig(
            enabled = true,
            entries = listOf(
                HostEntry(HostPresets.TMDB_API, listOf("13.224.0.1", "13.224.0.2", "13.224.0.3")),
            ),
        )
        val dns = HostsDns(config, systemDns = fakeSystemDns("9.9.9.9"))

        val result = dns.lookup(HostPresets.TMDB_API)

        assertThat(result).hasSize(3)
        assertThat(result.map { it.hostAddress })
            .containsExactly("13.224.0.1", "13.224.0.2", "13.224.0.3")
    }

    @Test
    fun `未命中走 systemDns 返回其结果`() {
        val config = HostsConfig(
            enabled = true,
            entries = listOf(
                HostEntry(HostPresets.TMDB_API, listOf("13.224.0.1")),
            ),
        )
        val dns = HostsDns(config, systemDns = fakeSystemDns("9.9.9.9"))

        val result = dns.lookup("not-in-hosts.example.com")

        assertThat(result.map { it.hostAddress }).containsExactly("9.9.9.9")
    }

    @Test
    fun `disabled 时全部走 systemDns 即使命中 hosts`() {
        val config = HostsConfig(
            enabled = false,
            entries = listOf(
                HostEntry(HostPresets.TMDB_API, listOf("13.224.0.1")),
            ),
        )
        val dns = HostsDns(config, systemDns = fakeSystemDns("9.9.9.9"))

        val result = dns.lookup(HostPresets.TMDB_API)

        assertThat(result.map { it.hostAddress }).containsExactly("9.9.9.9")
    }

    @Test
    fun `多条 IP 轮询 连续 lookup 首元素轮换`() {
        val ips = listOf("13.224.0.1", "13.224.0.2", "13.224.0.3")
        val config = HostsConfig(
            enabled = true,
            entries = listOf(HostEntry(HostPresets.TMDB_API, ips)),
        )
        val dns = HostsDns(config, systemDns = fakeSystemDns("9.9.9.9"))

        val first = dns.lookup(HostPresets.TMDB_API)
        val second = dns.lookup(HostPresets.TMDB_API)

        assertThat(first).isNotEmpty()
        assertThat(second).isNotEmpty()
        // size>1 时两次首元素必须不同（轮换生效）。
        assertThat(first[0].hostAddress).isNotEqualTo(second[0].hostAddress)
    }

    @Test
    fun `多条 IP 轮询 覆盖全部起始 IP`() {
        val ips = listOf("13.224.0.1", "13.224.0.2", "13.224.0.3")
        val config = HostsConfig(
            enabled = true,
            entries = listOf(HostEntry(HostPresets.TMDB_API, ips)),
        )
        val dns = HostsDns(config, systemDns = fakeSystemDns("9.9.9.9"))

        val heads = (1..ips.size).map { dns.lookup(HostPresets.TMDB_API).first().hostAddress }

        // 轮询 size 次后，每个 IP 都应作为首元素出现过。
        assertThat(heads.toSet()).containsExactly("13.224.0.1", "13.224.0.2", "13.224.0.3")
    }

    @Test
    fun `空 ips 列表回退 systemDns`() {
        val config = HostsConfig(
            enabled = true,
            entries = listOf(HostEntry(HostPresets.TMDB_API, emptyList())),
        )
        val dns = HostsDns(config, systemDns = fakeSystemDns("9.9.9.9"))

        val result = dns.lookup(HostPresets.TMDB_API)

        assertThat(result.map { it.hostAddress }).containsExactly("9.9.9.9")
    }

    @Test
    fun `大小写不敏感匹配 API THEMOVIEDB ORG 命中`() {
        val config = HostsConfig(
            enabled = true,
            entries = listOf(
                HostEntry(HostPresets.TMDB_API, listOf("13.224.0.1")),
            ),
        )
        val dns = HostsDns(config, systemDns = fakeSystemDns("9.9.9.9"))

        val result = dns.lookup("API.THEMOVIEDB.ORG")

        assertThat(result.map { it.hostAddress }).contains("13.224.0.1")
        assertThat(result.map { it.hostAddress }).doesNotContain("9.9.9.9")
    }

    @Test
    fun `image tmdb org 域名命中返回配置 IP`() {
        val config = HostsConfig(
            enabled = true,
            entries = listOf(
                HostEntry(HostPresets.TMDB_IMAGE, listOf("13.224.1.1", "13.224.1.2")),
            ),
        )
        val dns = HostsDns(config, systemDns = fakeSystemDns("9.9.9.9"))

        val result = dns.lookup(HostPresets.TMDB_IMAGE)

        assertThat(result).hasSize(2)
        assertThat(result.map { it.hostAddress })
            .containsExactly("13.224.1.1", "13.224.1.2")
    }

    @Test
    fun `HostPresets 默认候选列表包含 api 与 image 域名`() {
        assertThat(HostPresets.DEFAULT_CANDIDATES)
            .containsExactly(HostPresets.TMDB_API, HostPresets.TMDB_IMAGE)
    }

    @Test(expected = UnknownHostException::class)
    fun `系统 DNS 抛 UnknownHostException 时未命中原样抛出`() {
        val throwingDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                throw UnknownHostException("simulated")
        }
        val config = HostsConfig(enabled = true, entries = emptyList())
        val dns = HostsDns(config, systemDns = throwingDns)

        dns.lookup("anything.example.com")
    }
}
