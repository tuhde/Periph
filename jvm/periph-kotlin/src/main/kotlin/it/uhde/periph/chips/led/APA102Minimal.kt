package it.uhde.periph.chips.led

import it.uhde.periph.connection.Connection

/**
 * APA102 addressable RGB LED strip — minimal interface.
 *
 * Drives a chain of [n] APA102 pixels over an SPI connection
 * (Mode 0, MSB first). Maintains an internal BGR+brightness buffer;
 * [fill] writes all pixels and transmits the full frame
 * (start + pixels + end) immediately. No per-pixel addressing or
 * brightness control.
 *
 * The APA102 frame format is:
 * - start frame: 4 zero-bytes (0x00 × 4)
 * - pixel data:  n × 4 bytes [0xE0|brightness, B, G, R] (BGR wire order)
 * - end frame:   max(4, (n+15)//16) bytes of 0xFF
 *
 * Use [APA102Full] for per-pixel addressing with per-pixel
 * hardware brightness, explicit frame control, global software brightness
 * scaling, buffer rotation, and HSV fill.
 *
 * @param connection configured SPI connection (Mode 0, MSB first)
 * @param n number of pixels in the strip (≥1)
 */
open class APA102Minimal(
    protected val connection: Connection,
    protected val n: Int
) {
    /** Internal pixel buffer in BGR+brightness wire order ([0xE0|brightness, B, G, R] per pixel). */
    protected val buf: ByteArray = ByteArray(n * 4).also {
        for (i in 0 until n) {
            it[i * 4]     = (0xE0 or 31).toByte() // brightness byte (3 high bits = 1)
            it[i * 4 + 1] = 0.toByte()            // blue
            it[i * 4 + 2] = 0.toByte()            // green
            it[i * 4 + 3] = 0.toByte()            // red
        }
    }

    /**
     * Fill every pixel with one colour and transmit the full APA102 frame
     * immediately.
     *
     * Each channel is clamped to [0, 255]. Stores brightness/B/G/R in
     * the internal buffer (BGR wire order with hardware brightness byte
     * first), then sends start frame + pixel data + end frame.
     *
     * @param r red channel (0–255)
     * @param g green channel (0–255)
     * @param b blue channel (0–255)
     */
    fun fill(r: Int, g: Int, b: Int) {
        val rc = r.coerceIn(0, 255)
        val gc = g.coerceIn(0, 255)
        val bc = b.coerceIn(0, 255)
        for (i in 0 until n) {
            buf[i * 4]     = (0xE0 or 31).toByte() // hardware brightness = 31 (max)
            buf[i * 4 + 1] = bc.toByte()           // blue
            buf[i * 4 + 2] = gc.toByte()           // green
            buf[i * 4 + 3] = rc.toByte()           // red
        }
        sendFrame()
    }

    /**
     * Turn off all pixels (equivalent to [fill](0, 0, 0)).
     */
    fun off() = fill(0, 0, 0)

    /**
     * Send the full APA102 frame (start + pixel buffer + end).
     */
    protected fun sendFrame() {
        val endBytes = maxOf(4, (n + 15) / 16)
        val frame = ByteArray(4 + n * 4 + endBytes)
        frame[0] = 0x00.toByte()
        frame[1] = 0x00.toByte()
        frame[2] = 0x00.toByte()
        frame[3] = 0x00.toByte()
        buf.copyInto(frame, 4, 0, n * 4)
        for (i in 0 until endBytes) {
            frame[4 + n * 4 + i] = 0xFF.toByte()
        }
        connection.write(frame)
    }
}