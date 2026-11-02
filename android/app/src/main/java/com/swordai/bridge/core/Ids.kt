package com.swordai.bridge.core

import java.security.SecureRandom

object Ids {
    private val random = SecureRandom()
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    fun random(length: Int): String {
        val chars = CharArray(length) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return String(chars)
    }

    fun snapshot(): String = "s_" + random(10)

    fun file(): String = "f_" + random(16)

    val COMMAND_ID = Regex("^cmd_[A-Za-z0-9]{8,40}$")
    val DEVICE_ID = Regex("^dev_[A-Za-z0-9]{8,40}$")
    val ELEMENT_ID = Regex("^e[0-9]{1,4}$")
    val SNAPSHOT_ID = Regex("^s_[A-Za-z0-9]{4,32}$")
    val FILE_ID = Regex("^f_[A-Za-z0-9]{8,40}$")
    val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    val MIME_PREFIX = Regex("^[a-z]+(/[a-z0-9.+\\-]*)?$")
}
