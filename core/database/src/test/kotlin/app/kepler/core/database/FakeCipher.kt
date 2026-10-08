package app.kepler.core.database

import app.kepler.core.database.settings.CipherBlob
import app.kepler.core.database.settings.SecretCipher

/** Reversible but obviously not encryption; proves the store never writes plaintext. */
class FakeCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = CipherBlob(byteArrayOf(7, 7, 7), plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray())
    override fun decrypt(blob: CipherBlob): ByteArray {
        require(blob.iv.contentEquals(byteArrayOf(7, 7, 7))) { "bad iv" }
        return blob.data.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}
