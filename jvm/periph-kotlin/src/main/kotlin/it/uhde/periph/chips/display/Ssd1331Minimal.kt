package it.uhde.periph.chips.display

import it.uhde.periph.connection.DisplayConn
import java.io.IOException

/**
 * SSD1331 96 x 64 RGB OLED controller — minimal driver.
 *
 * Drives the Waveshare 0.95inch RGB OLED over 4-wire SPI. The picture is kept in
 * a 96 x 64 RGB565 framebuffer (12 288 bytes, high byte first) and sent to the
 * display RAM by [show].
 *
 * The connection is a [DisplayConn] with no BUSY pin (see
 * `specs/transport_display.md`); SIMPLEX wiring is enough. The SSD1331 takes
 * command parameters with D/C# low, so the driver sends every byte of a command
 * as its own `sendCommand(byte, empty)`; only pixel data uses `sendData`.
 *
 * The constructor runs the vendor initialisation sequence: hardware reset
 * (100 ms settle), display off, contrast `FF FF FF`, master current 7/16, 65k
 * colours with column remap and reversed COM scan, VCC external, power save off,
 * 100 ms VCC wait, scroll off, display RAM cleared, display on. The framebuffer
 * is cleared to black.
 *
 * @param connection display connection (no BUSY pin)
 * @throws IOException if the bus fails
 */
open class Ssd1331Minimal(protected val connection: DisplayConn) {

    companion object {
        const val WIDTH = 96
        const val HEIGHT = 64
        const val BUFFER_SIZE = WIDTH * HEIGHT * 2

        const val BLACK = 0x0000
        const val WHITE = 0xFFFF
        const val RED = 0xF800
        const val GREEN = 0x07E0
        const val BLUE = 0x001F
        const val YELLOW = 0xFFE0
        const val CYAN = 0x07FF
        const val MAGENTA = 0xF81F

        const val CMD_COLUMN = 0x15
        const val CMD_ROW = 0x75
        const val CMD_CONTRAST_A = 0x81
        const val CMD_CONTRAST_B = 0x82
        const val CMD_CONTRAST_C = 0x83
        const val CMD_MASTER_CURRENT = 0x87
        const val CMD_PRECHARGE_A = 0x8A
        const val CMD_PRECHARGE_B = 0x8B
        const val CMD_PRECHARGE_C = 0x8C
        const val CMD_REMAP = 0xA0
        const val CMD_START_LINE = 0xA1
        const val CMD_OFFSET = 0xA2
        const val CMD_MODE_NORMAL = 0xA4
        const val CMD_MUX = 0xA8
        const val CMD_DIM_SETTING = 0xAB
        const val CMD_MASTER_CONFIG = 0xAD
        const val CMD_DISPLAY_DIM = 0xAC
        const val CMD_DISPLAY_OFF = 0xAE
        const val CMD_DISPLAY_ON = 0xAF
        const val CMD_POWER_SAVE = 0xB0
        const val CMD_PHASE = 0xB1
        const val CMD_CLOCK = 0xB3
        const val CMD_GRAY_LINEAR = 0xB9
        const val CMD_PRECHARGE_LEVEL = 0xBB
        const val CMD_VCOMH = 0xBE
        const val CMD_LINE = 0x21
        const val CMD_RECT = 0x22
        const val CMD_COPY = 0x23
        const val CMD_DIM = 0x24
        const val CMD_CLEAR = 0x25
        const val CMD_FILL = 0x26
        const val CMD_SCROLL_SETUP = 0x27
        const val CMD_SCROLL_OFF = 0x2E
        const val CMD_SCROLL_ON = 0x2F

        /** Pack 8-bit R, G, B into RGB565. */
        @JvmStatic
        fun rgb(r: Int, g: Int, b: Int): Int =
            ((r and 0xF8) shl 8) or ((g and 0xFC) shl 3) or ((b and 0xFF) shr 3)
    }

    protected val fb = ByteArray(BUFFER_SIZE)
    protected var remapFlags = 0x72

    init {
        initSequence()
    }

    /** Panel width in pixels (96). */
    fun width(): Int = WIDTH

    /** Panel height in pixels (64). */
    fun height(): Int = HEIGHT

    /** Fill the framebuffer with one RGB565 colour. The display is unchanged until [show]. */
    fun clear(color: Int) {
        val hi = (color shr 8).toByte()
        val lo = color.toByte()
        var i = 0
        while (i < BUFFER_SIZE) {
            fb[i] = hi
            fb[i + 1] = lo
            i += 2
        }
    }

    /** Set one framebuffer pixel. Out-of-range coordinates are ignored. */
    fun setPixel(x: Int, y: Int, color: Int) {
        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) return
        val i = (y * WIDTH + x) * 2
        fb[i] = (color shr 8).toByte()
        fb[i + 1] = color.toByte()
    }

    /**
     * Send the whole framebuffer to the display RAM: window 0..95 x 0..63 and
     * 12 288 bytes in 192-byte (one row) transfers.
     */
    @Throws(IOException::class)
    fun show() {
        window(0, 0, WIDTH - 1, HEIGHT - 1)
        var off = 0
        while (off < BUFFER_SIZE) {
            connection.sendData(fb.copyOfRange(off, off + 2 * WIDTH))
            off += 2 * WIDTH
        }
    }

    /** Switch the display on (`0xAF`); the RAM content is shown. */
    @Throws(IOException::class)
    fun displayOn() = cmd(CMD_DISPLAY_ON)

    /** Switch the display off (`0xAE`); the RAM content is kept. */
    @Throws(IOException::class)
    fun displayOff() = cmd(CMD_DISPLAY_OFF)

    /** Send every value as its own command byte (all with D/C# low). */
    @Throws(IOException::class)
    protected fun cmd(vararg bytes: Int) {
        for (b in bytes) connection.sendCommand(b and 0xFF, ByteArray(0))
    }

    @Throws(IOException::class)
    protected fun window(x0: Int, y0: Int, x1: Int, y1: Int) {
        cmd(CMD_COLUMN, x0, x1, CMD_ROW, y0, y1)
    }

    private fun initSequence() {
        connection.reset()
        connection.delay(100)
        cmd(CMD_DISPLAY_OFF)
        cmd(CMD_CONTRAST_A, 0xFF, CMD_CONTRAST_B, 0xFF, CMD_CONTRAST_C, 0xFF)
        cmd(CMD_MASTER_CURRENT, 0x06)
        cmd(CMD_PRECHARGE_A, 0x64, CMD_PRECHARGE_B, 0x78, CMD_PRECHARGE_C, 0x64)
        cmd(CMD_REMAP, remapFlags)
        cmd(CMD_START_LINE, 0x00, CMD_OFFSET, 0x00)
        cmd(CMD_MODE_NORMAL)
        cmd(CMD_MUX, 0x3F, CMD_MASTER_CONFIG, 0x8E)
        cmd(CMD_POWER_SAVE, 0x0B, CMD_PHASE, 0x31, CMD_CLOCK, 0xF0)
        cmd(CMD_PRECHARGE_LEVEL, 0x3A, CMD_VCOMH, 0x3E)
        cmd(CMD_SCROLL_OFF)
        cmd(CMD_CLEAR, 0x00, 0x00, 0x5F, 0x3F)
        connection.delay(100)
        cmd(CMD_DISPLAY_ON)
    }
}
