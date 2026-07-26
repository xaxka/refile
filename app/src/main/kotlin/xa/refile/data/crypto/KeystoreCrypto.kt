package xa.refile.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 基于 Android Keystore 的对称加解密工具（计划 §M1 SubTask 1.3.2）。
 *
 * 用途：在服务器配置持久化前对明文密码加密、读取时解密，确保明文密码绝不落盘（红线）。
 *
 * - 算法：AES/GCM/NoPadding，密钥长度 256。
 * - 密钥别名 `refile_master_key`，存于 `AndroidKeyStore` provider；首次使用时生成。
 * - 每次加密随机生成 12 字节 IV，与密文一同以 base64 形式存储：`base64(iv || cipherText)`。
 * - 不调用 `setUserAuthenticationRequired`：应用无锁屏也可使用。
 * - 构造接受 [Context]：Keystore 本身不需要 Context，保留以便未来扩展（如迁移至 Tink）。
 *
 * 安全约束：本类不打印任何日志——明文密码绝不进入日志（红线）。
 */
class KeystoreCrypto(private val context: Context) {

    /**
     * 加密明文，返回 `base64(iv || cipherText)`。
     *
     * @param plainText 待加密的明文密码。
     * @return base64 编码的 iv+密文组合串。
     */
    fun encrypt(plainText: String): String {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ensureKeyExists(keyStore)
        val secretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey

        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Android Keystore 在 GCM 加密模式下不允许调用方传入 IV（默认
        // setRandomizedEncryptionRequired=true），须由 provider 自动生成。
        // 加密后从 cipher.iv 取回随机 IV，与密文一同存储以供解密使用。
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv

        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherText.size).apply {
            System.arraycopy(iv, 0, this, 0, iv.size)
            System.arraycopy(cipherText, 0, this, iv.size, cipherText.size)
        }
        return Base64.getEncoder().encodeToString(combined)
    }

    /**
     * 解密 [encrypt] 产出的密文，返回明文。
     *
     * @param encrypted `base64(iv || cipherText)` 形式的密文。
     * @return 明文密码。
     */
    fun decrypt(encrypted: String): String {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(KEY_ALIAS, null)
            ?: throw NoSuchElementException("加密密钥不存在，可能因设备恢复或数据清除导致")
        val secretKey = key as SecretKey

        val combined = Base64.getDecoder().decode(encrypted)
        require(combined.size > IV_SIZE_BYTES) { "invalid encrypted payload" }
        val iv = combined.copyOfRange(0, IV_SIZE_BYTES)
        val cipherText = combined.copyOfRange(IV_SIZE_BYTES, combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    /** 若 Keystore 中尚无主密钥别名，则在 AndroidKeyStore 中生成 AES-256 GCM 密钥。 */
    private fun ensureKeyExists(keyStore: KeyStore) {
        if (keyStore.containsAlias(KEY_ALIAS)) return
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "refile_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
