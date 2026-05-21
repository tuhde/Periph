package it.uhde.periph.chips.io_expander

import it.uhde.periph.transport.Transport

class Pcf8575Minimal(transport: Transport) {

    var shadow = intArrayOf(0xFF, 0xFF)

    init {
        writeBoth()
    }

    private fun writeBoth() {
        transport.write(byteArrayOf(shadow[0].toByte(), shadow[1].toByte()))
    }

    fun readPort(port: Int): Int {
        val buf = transport.read(2)
        return buf[port].toInt() and 0xFF
    }

    fun writePort(port: Int, mask: Int) {
        shadow[port] = mask and 0xFF
        writeBoth()
    }

    fun pin(n: Int) = Pin(this, n)

    fun setPin(n: Int, high: Boolean) {
        val portIdx = n / 8
        val bit = n % 8
        if (high) shadow[portIdx] = shadow[portIdx] or (1 shl bit)
        else shadow[portIdx] = shadow[portIdx] and (1 shl bit).inv()
        writeBoth()
    }

    class Pin(val chip: Pcf8575Minimal, val n: Int) {
        fun setInput() { chip.setPin(n, true) }
        fun setOutput() { chip.setPin(n, false) }
        fun setHigh() { chip.setPin(n, true) }
        fun setLow() { chip.setPin(n, false) }
        fun read(): Boolean {
            val port = n / 8
            val bit = n % 8
            val buf = chip.transport.read(2)
            return ((buf[port].toInt() shr bit) and 1) == 1
        }
        fun toggle() {
            val portIdx = n / 8
            val bit = n % 8
            chip.setPin(n, ((chip.shadow[portIdx] shr bit) and 1) == 0)
        }
    }
}