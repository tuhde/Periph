package it.uhde.periph.chips.io_expander

import it.uhde.periph.transport.Transport

class Pcf8575Full(transport: Transport) : Pcf8575Minimal(transport) {

    private var prev = intArrayOf(0xFF, 0xFF)
    private var callback: ((Int) -> Unit)? = null
    private var pollThread: Thread? = null
    private var pollStop = false

    init {
        val buf = transport.read(2)
        prev[0] = buf[0].toInt() and 0xFF
        prev[1] = buf[1].toInt() and 0xFF
    }

    fun configureInterrupt(callback: (Int) -> Unit) {
        this.callback = callback
        pollStop = false
        pollThread = Thread { pollLoop() }
        pollThread?.daemon = true
        pollThread?.start()
    }

    private fun pollLoop() {
        try {
            while (!pollStop) {
                val current = transport.read(2)
                val ch0 = (current[0].toInt() xor prev[0]) and 0xFF
                val ch1 = (current[1].toInt() xor prev[1]) and 0xFF
                val changed = ch0 or (ch1 shl 8)
                if (changed != 0 && callback != null) {
                    prev[0] = current[0].toInt() and 0xFF
                    prev[1] = current[1].toInt() and 0xFF
                    callback.invoke(changed)
                }
                Thread.sleep(5)
            }
        } catch (e: Exception) { }
    }

    fun clearInterrupt(): Int {
        val current = transport.read(2)
        val ch0 = (current[0].toInt() xor prev[0]) and 0xFF
        val ch1 = (current[1].toInt() xor prev[1]) and 0xFF
        prev[0] = current[0].toInt() and 0xFF
        prev[1] = current[1].toInt() and 0xFF
        return ch0 or (ch1 shl 8)
    }

    fun stopInterrupt() {
        pollStop = true
        pollThread?.interrupt()
    }
}