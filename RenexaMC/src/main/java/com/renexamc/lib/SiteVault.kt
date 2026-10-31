package com.renexamc.lib

import android.content.Context
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts the encrypted site bundle (an asset) in memory only.
 * The key/IV are stored XOR-masked so no plain secret sits in the bytecode.
 */
object SiteVault {

    private val MASK = byteArrayOf(104, 72, -126, 43, -22, 64, -56, 49, 87, 116, 99, -8, -73, 2, -122, -57, -54, 7, 112, -104, 76, -24, -18, 36, -115, -81, -91, 44, -36, -75, 37, 66)
    private val KEY_OBF = byteArrayOf(-45, 118, -101, -4, -119, 19, -125, 110, 102, -47, -10, 11, 10, 114, -21, 80, -101, -128, 123, 53, 70, 30, -44, -37, 21, 30, 53, 104, 10, 11, 121, 7)
    private val IV_OBF = byteArrayOf(-9, 99, 93, -38, 21, -61, 83, 109, -121, -14, -66, -16, 30, 35, -87, 45)

    fun decryptAsset(context: Context, assetName: String): ByteArray {
        val key = ByteArray(32) { i -> (KEY_OBF[i].toInt() xor MASK[i % MASK.size].toInt()).toByte() }
        val iv = ByteArray(16) { i -> (IV_OBF[i].toInt() xor MASK[i % MASK.size].toInt()).toByte() }

        val encrypted = context.assets.open(assetName).use { it.readBytes() }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(encrypted)
    }
}
