package xa.refile.core.openlist

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * OpenList DTO 契约测试：覆盖各数据类的构造、字段读取、data-class 合成方法（copy/equals/componentN）
 * 与序列化往返。响应类 DTO 在生产代码中仅由 kotlinx.serialization 反序列化产生（不经构造器），
 * 此处显式构造并读取全部字段，确保字段映射与 data-class 契约正确，并补全覆盖。
 */
class OpenListDtosTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test fun `OpenListFile constructs and exposes all fields with data-class contract`() {
        val f = OpenListFile(
            name = "a.mkv",
            size = 1024,
            isDir = false,
            modified = "2024-01-01T00:00:00Z",
            created = "2024-01-01T00:00:00Z",
            sign = "sig",
            thumb = "thumb-url",
            type = 0,
        )
        assertThat(f.name).isEqualTo("a.mkv")
        assertThat(f.size).isEqualTo(1024L)
        assertThat(f.isDir).isFalse()
        assertThat(f.modified).isEqualTo("2024-01-01T00:00:00Z")
        assertThat(f.created).isEqualTo("2024-01-01T00:00:00Z")
        assertThat(f.sign).isEqualTo("sig")
        assertThat(f.thumb).isEqualTo("thumb-url")
        assertThat(f.type).isEqualTo(0)
        // data-class 合成方法
        assertThat(f.component1()).isEqualTo("a.mkv")
        val copy = f.copy(name = "b.mkv")
        assertThat(copy).isNotEqualTo(f)
        assertThat(f.equals(f)).isTrue()
        assertThat(f.equals(null)).isFalse()
        assertThat(f.hashCode()).isEqualTo(copy.copy(name = "a.mkv").hashCode())
        assertThat(f.toString()).contains("a.mkv")
    }

    @Test fun `OpenListFile deserializes all fields from json`() {
        val f = json.decodeFromString<OpenListFile>(
            """{"name":"x","size":5,"is_dir":true,"modified":"m","created":"c","sign":"s","thumb":"t","type":1}""",
        )
        assertThat(f.name).isEqualTo("x")
        assertThat(f.isDir).isTrue()
        assertThat(f.sign).isEqualTo("s")
        assertThat(f.thumb).isEqualTo("t")
        assertThat(f.type).isEqualTo(1)
    }

    @Test fun `FsListData constructs and exposes all fields`() {
        val data = FsListData(
            content = listOf(OpenListFile(name = "a")),
            total = 1,
            readme = "readme",
            write = true,
            provider = "Local",
        )
        assertThat(data.content).hasSize(1)
        assertThat(data.total).isEqualTo(1)
        assertThat(data.readme).isEqualTo("readme")
        assertThat(data.write).isTrue()
        assertThat(data.provider).isEqualTo("Local")
        assertThat(data.copy()).isEqualTo(data)
        assertThat(data.component1()).hasSize(1)
    }

    @Test fun `FsListData tolerates null content`() {
        val data = json.decodeFromString<FsListData>("""{"content":null,"total":0}""")
        assertThat(data.content).isNull()
        assertThat(data.total).isEqualTo(0)
    }

    @Test fun `LoginData constructs and exposes token`() {
        val d = LoginData(token = "jwt")
        assertThat(d.token).isEqualTo("jwt")
        assertThat(d.copy(token = null).token).isNull()
        assertThat(d.component1()).isEqualTo("jwt")
    }

    @Test fun `OpenListResponse exposes code message data`() {
        val resp = OpenListResponse(code = 200, message = "success", data = LoginData(token = "t"))
        assertThat(resp.code).isEqualTo(200)
        assertThat(resp.message).isEqualTo("success")
        assertThat(resp.data?.token).isEqualTo("t")
        assertThat(resp.copy(code = 400).code).isEqualTo(400)
    }

    @Test fun `LoginRequest serializes username password and otp_code`() {
        val req = LoginRequest(username = "admin", password = "pw", otpCode = "123456")
        val encoded = json.encodeToString(LoginRequest.serializer(), req)
        assertThat(encoded).contains("\"username\":\"admin\"")
        assertThat(encoded).contains("\"password\":\"pw\"")
        assertThat(encoded).contains("\"otp_code\":\"123456\"")
        // 往返
        val back = json.decodeFromString(LoginRequest.serializer(), encoded)
        assertThat(back).isEqualTo(req)
        assertThat(req.component3()).isEqualTo("123456")
    }

    @Test fun `FsListRequest serializes all fields`() {
        val req = FsListRequest(path = "/Movies", password = "dir-pw", page = 2, perPage = 50, refresh = true)
        val encoded = json.encodeToString(FsListRequest.serializer(), req)
        assertThat(encoded).contains("\"path\":\"/Movies\"")
        assertThat(encoded).contains("\"password\":\"dir-pw\"")
        assertThat(encoded).contains("\"page\":2")
        assertThat(encoded).contains("\"per_page\":50")
        assertThat(encoded).contains("\"refresh\":true")
        assertThat(json.decodeFromString(FsListRequest.serializer(), encoded)).isEqualTo(req)
    }

    @Test fun `FsMkdirRequest serializes path`() {
        val req = FsMkdirRequest(path = "/New")
        val encoded = json.encodeToString(FsMkdirRequest.serializer(), req)
        assertThat(encoded).contains("\"path\":\"/New\"")
        assertThat(json.decodeFromString(FsMkdirRequest.serializer(), encoded)).isEqualTo(req)
        assertThat(req.component1()).isEqualTo("/New")
    }

    @Test fun `FsRenameRequest serializes path and name`() {
        val req = FsRenameRequest(path = "/d/a", name = "b")
        val encoded = json.encodeToString(FsRenameRequest.serializer(), req)
        assertThat(encoded).contains("\"path\":\"/d/a\"")
        assertThat(encoded).contains("\"name\":\"b\"")
        assertThat(json.decodeFromString(FsRenameRequest.serializer(), encoded)).isEqualTo(req)
    }

    @Test fun `FsMoveRequest serializes src_dir dst_dir names`() {
        val req = FsMoveRequest(srcDir = "/a", dstDir = "/b", names = listOf("x", "y"))
        val encoded = json.encodeToString(FsMoveRequest.serializer(), req)
        assertThat(encoded).contains("\"src_dir\":\"/a\"")
        assertThat(encoded).contains("\"dst_dir\":\"/b\"")
        assertThat(encoded).contains("\"names\":[\"x\",\"y\"]")
        assertThat(json.decodeFromString(FsMoveRequest.serializer(), encoded)).isEqualTo(req)
        assertThat(req.component3()).containsExactly("x", "y")
    }
}
