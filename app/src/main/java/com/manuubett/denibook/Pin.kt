package com.manuubett.denibook

import android.content.SharedPreferences
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The owner PIN and the waiter PIN ("owner" and "waiter" are the keys).
 * Only a salted hash is stored. The hashing is slow on purpose, so call
 * set() and check() from a background thread.
 */
object Pin {

    private const val ITERATIONS = 10000

    fun isSet(prefs: SharedPreferences, key: String): Boolean =
        !prefs.getString("${key}_hash", "").isNullOrEmpty()

    private fun hash(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")

        return Base64.encodeToString(factory.generateSecret(spec).encoded, Base64.NO_WRAP)
    }

    fun set(prefs: SharedPreferences, key: String, pin: String) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)

        prefs.edit()
            .putString("${key}_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("${key}_hash", hash(pin, salt))
            .apply()
    }

    fun check(prefs: SharedPreferences, key: String, pin: String): Boolean {
        val saltText = prefs.getString("${key}_salt", "") ?: ""
        val saved = prefs.getString("${key}_hash", "") ?: ""

        if (saltText.isEmpty() || saved.isEmpty()) return false

        val salt = Base64.decode(saltText, Base64.NO_WRAP)

        return hash(pin, salt) == saved
    }

    fun clear(prefs: SharedPreferences, key: String) {
        prefs.edit().remove("${key}_salt").remove("${key}_hash").apply()
    }
}
