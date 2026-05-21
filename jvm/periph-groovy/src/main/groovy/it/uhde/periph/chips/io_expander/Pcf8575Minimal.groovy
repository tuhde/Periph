package it.uhde.periph.chips.io_expander;

import it.uhde.periph.transport.Transport;

class Pcf8575Minimal {
    def transport
    def shadow = [0xFF, 0xFF]

    Pcf8575Minimal(transport) {
        this.transport = transport
        writeBoth()
    }

    def writeBoth() {
        transport.write([(byte)shadow[0], (byte)shadow[1]] as byte[])
    }

    def readPort(port) {
        def buf = transport.read(2)
        return (buf[port] & 0xFF) as int
    }

    def writePort(port, mask) {
        shadow[port] = mask & 0xFF
        writeBoth()
    }

    def pin(n) { new Pin(this, n) }

    def setPin(n, high) {
        def portIdx = n / 8
        def bit = n % 8
        if (high) shadow[portIdx] |= (1 << bit)
        else shadow[portIdx] &= ~(1 << bit)
        writeBoth()
    }

    static class Pin {
        def chip, n
        Pin(chip, n) { this.chip = chip; this.n = n }
        def setInput() { chip.setPin(n, true) }
        def setOutput() { chip.setPin(n, false) }
        def setHigh() { chip.setPin(n, true) }
        def setLow() { chip.setPin(n, false) }
        def read() {
            def port = n / 8
            def bit = n % 8
            def buf = chip.transport.read(2)
            return ((buf[port] >> bit) & 1) == 1
        }
        def toggle() {
            def portIdx = n / 8
            def bit = n % 8
            chip.setPin(n, ((chip.shadow[portIdx] >> bit) & 1) == 0)
        }
    }
}