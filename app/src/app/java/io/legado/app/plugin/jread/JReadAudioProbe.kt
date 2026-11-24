package io.legado.app.plugin.jread

/**
 * 音频格式探测：WAV 头直读采样率，MP3/AAC 给常用默认值。
 * 只做播放侧需要的判断，不做转码。
 */
object JReadAudioProbe {

    private const val DEFAULT_RATE = 24000

    fun sampleRate(bytes: ByteArray): Int {
        val wav = wavSampleRate(bytes)
        if (wav > 0) return wav
        return if (isMp3(bytes)) 24000 else DEFAULT_RATE
    }

    fun contentType(bytes: ByteArray): String = when {
        isWav(bytes) -> "audio/wav"
        isMp3(bytes) -> "audio/mpeg"
        isAdts(bytes) -> "audio/aac"
        isOgg(bytes) -> "audio/ogg"
        bytes.size > 4 && bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() -> "audio/flac"
        else -> "audio/wav"
    }

    fun isWav(bytes: ByteArray): Boolean =
        bytes.size > 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte()

    fun isMp3(bytes: ByteArray): Boolean =
        bytes.size > 2 && (
            (bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) ||
                ((bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xE0) == 0xE0)
            )

    private fun isAdts(bytes: ByteArray): Boolean =
        bytes.size > 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xF6) == 0xF0

    private fun isOgg(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte() &&
            bytes[2] == 'g'.code.toByte() && bytes[3] == 'S'.code.toByte()

    private fun wavSampleRate(bytes: ByteArray): Int {
        if (!isWav(bytes) || bytes.size < 28) return 0
        return (bytes[24].toInt() and 0xFF) or
            ((bytes[25].toInt() and 0xFF) shl 8) or
            ((bytes[26].toInt() and 0xFF) shl 16) or
            ((bytes[27].toInt() and 0xFF) shl 24)
    }
}
