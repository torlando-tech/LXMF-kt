package network.reticulum.lxmf

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * Minimal QR encoding facade for PAPER message representation.
 *
 * Wraps zxing's QR encoder to provide the same semantics python LXMF gets
 * from the `qrcode` module in `LXMessage.as_qr()` (LXMessage.py:718-736):
 * ERROR_CORRECT_L, quiet-zone border of 1 module, data encoded as bytes.
 *
 * The result is exposed as a plain Boolean matrix (true = dark module) so
 * lxmf-core carries no image/graphics dependency; callers render it onto
 * whatever canvas their platform provides.
 */
object QrEncoder {
    /** Python qrcode's ERROR_CORRECT_L equivalent. */
    val ERROR_CORRECT_L = ErrorCorrectionLevel.L

    /**
     * Encode [data] into a QR matrix including a quiet zone of [border]
     * modules on every side.
     *
     * @return Matrix indexed [y][x]; true = dark module. Null if encoding fails.
     */
    fun encode(
        data: String,
        errorCorrectionLevel: ErrorCorrectionLevel = ERROR_CORRECT_L,
        border: Int = 1,
    ): Array<BooleanArray>? {
        return try {
            val hints =
                mapOf(
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                    EncodeHintType.ERROR_CORRECTION to errorCorrectionLevel,
                    EncodeHintType.MARGIN to border,
                )
            val qr = Encoder.encode(data, errorCorrectionLevel, hints)
            val size = qr.matrix.width
            Array(qr.matrix.height) { y ->
                BooleanArray(size) { x -> qr.matrix.get(x, y) != 0.toByte() }
            }
        } catch (e: Exception) {
            println("QR encoding failed for ${data.length}-char payload: ${e.message}")
            null
        }
    }
}
