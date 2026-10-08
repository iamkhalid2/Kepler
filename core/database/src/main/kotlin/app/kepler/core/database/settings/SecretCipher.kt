package app.kepler.core.database.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CipherBlob(val iv: ByteArray, val data: ByteArray)

/** Seals small secrets (API keys). Abstracted so JVM tests can use a fake instead of the Android Keystore. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): CipherBlob
    /** Throws when the blob cannot be opened (for example the key was wiped). */
    fun decrypt(blob: CipherBlob): ByteArray
}

/** AES-256-GCM with a non-exportable key held in the Android Keystore. */
class KeystoreSecretCipher(private val alias: String = "kepler_secrets_v1") : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): CipherBlob {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return CipherBlob(c.iv, c.doFinal(plain))
    }

    override fun decrypt(blob: CipherBlob): ByteArray {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.iv)) }
        return c.doFinal(blob.data)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
