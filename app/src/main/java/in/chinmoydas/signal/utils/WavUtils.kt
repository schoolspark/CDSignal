package `in`.chinmoydas.signal.utils

import java.io.File
import java.io.FileOutputStream

object WavUtils {

    // [Golden Build] 16kHz Pager Header
    private const val SAMPLE_RATE = 16000
    private const val BITS_PER_SAMPLE = 16
    private const val CHANNELS = 1

    fun saveWavFile(file: File, pcmData: ByteArray) {
        val totalDataLen = pcmData.size.toLong()
        val totalAudioLen = totalDataLen + 36
        val longSampleRate = SAMPLE_RATE.toLong()
        val byteRate = (SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8).toLong()

        try {
            // [UPGRADE] .use { } guarantees the stream closes even if writing fails mid-way
            FileOutputStream(file).use { fos ->
                val header = ByteArray(44)

                header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
                header[4] = (totalAudioLen and 0xff).toByte()
                header[5] = ((totalAudioLen shr 8) and 0xff).toByte()
                header[6] = ((totalAudioLen shr 16) and 0xff).toByte()
                header[7] = ((totalAudioLen shr 24) and 0xff).toByte()
                header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
                header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
                header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
                header[20] = 1; header[21] = 0
                header[22] = CHANNELS.toByte(); header[23] = 0
                header[24] = (longSampleRate and 0xff).toByte()
                header[25] = ((longSampleRate shr 8) and 0xff).toByte()
                header[26] = ((longSampleRate shr 16) and 0xff).toByte()
                header[27] = ((longSampleRate shr 24) and 0xff).toByte()
                header[28] = (byteRate and 0xff).toByte()
                header[29] = ((byteRate shr 8) and 0xff).toByte()
                header[30] = ((byteRate shr 16) and 0xff).toByte()
                header[31] = ((byteRate shr 24) and 0xff).toByte()
                header[32] = (CHANNELS * BITS_PER_SAMPLE / 8).toByte(); header[33] = 0
                header[34] = BITS_PER_SAMPLE.toByte(); header[35] = 0
                header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
                header[40] = (totalDataLen and 0xff).toByte()
                header[41] = ((totalDataLen shr 8) and 0xff).toByte()
                header[42] = ((totalDataLen shr 16) and 0xff).toByte()
                header[43] = ((totalDataLen shr 24) and 0xff).toByte()

                fos.write(header, 0, 44)
                fos.write(pcmData)
            } // Auto-closes here!
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}