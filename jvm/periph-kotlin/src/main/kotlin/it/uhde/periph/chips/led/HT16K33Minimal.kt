package it.uhde.periph.chips.led

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * HT16K33 — 16 x 8 LED matrix driver (minimal driver).
 *
 * Drives up to 16 ROW x 8 COM = 128 LEDs through a 16-byte display RAM that
 * is shadowed in the driver. I2C address 0x70 (A0-A2 select 0x70-0x77).
 *
 * Defaults baked in at construction: oscillator on, ROW15 pin as a ROW
 * output, brightness 16/16, blink off, RAM cleared, display on.
 */
open class HT16K33Minimal @Throws(IOException::class) constructor(protected val connection: Connection) {

    companion object {
        const val COMMONS = 8
        const val ROWS    = 16

        const val CMD_SYSTEM  = 0x20
        const val CMD_DISPLAY = 0x80
        const val CMD_DIMMING = 0xE0
        const val CMD_KEY_PTR = 0x40
        const val CMD_INT_PTR = 0x60
    }

    protected val ram = ByteArray(16)
    protected var brightnessLevel: Int = 15
    protected var blinkCode: Int = 0
    protected var displayEnabled: Boolean = true
    protected var rowInt: Int = 0xA0

    init {
        command(CMD_SYSTEM or 0x01)
        command(rowInt)
        command(CMD_DIMMING or brightnessLevel)
        show()
        applyDisplay()
    }

    @Throws(IOException::class)
    protected fun command(b: Int) {
        connection.write(byteArrayOf(b.toByte()))
    }

    @Throws(IOException::class)
    protected fun applyDisplay() {
        command(CMD_DISPLAY or (blinkCode shl 1) or (if (displayEnabled) 1 else 0))
    }

    /** Zero the shadow RAM and write it to the chip. */
    @Throws(IOException::class)
    fun clear() {
        ram.fill(0)
        show()
    }

    /**
     * Set or clear one LED in the shadow RAM (does not write to the chip).
     *
     * @param com COM line, 0-7
     * @param row ROW line, 0-15
     * @param on true to light the LED
     * @throws IllegalArgumentException if com or row is out of range
     */
    fun setPixel(com: Int, row: Int, on: Boolean = true) {
        require(com in 0..7) { "com must be in 0..7" }
        require(row in 0..15) { "row must be in 0..15" }
        val idx = 2 * com + (row shr 3)
        val mask = 1 shl (row and 7)
        ram[idx] = if (on) (ram[idx].toInt() or mask).toByte() else (ram[idx].toInt() and mask.inv()).toByte()
    }

    /**
     * Write raw RAM bytes at [address] and update the shadow RAM.
     *
     * @param data 1-16 bytes of display data
     * @param address start RAM address, 0-15
     * @throws IllegalArgumentException if data is empty or address + length exceeds 16
     */
    @Throws(IOException::class)
    fun writeRaw(data: ByteArray, address: Int = 0) {
        require(data.isNotEmpty() && address >= 0 && address + data.size <= 16) {
            "address + data.size must be within 0..16"
        }
        data.copyInto(ram, address)
        connection.write(byteArrayOf(address.toByte()) + data)
    }

    /** Write the whole 16-byte shadow RAM to the chip. */
    @Throws(IOException::class)
    fun show() {
        connection.write(byteArrayOf(0) + ram)
    }

    /**
     * Set the dimming level.
     *
     * @param level 0-15, giving (level + 1)/16 duty
     * @throws IllegalArgumentException if level is out of range
     */
    @Throws(IOException::class)
    fun setBrightness(level: Int) {
        require(level in 0..15) { "level must be in 0..15" }
        brightnessLevel = level
        command(CMD_DIMMING or level)
    }
}
