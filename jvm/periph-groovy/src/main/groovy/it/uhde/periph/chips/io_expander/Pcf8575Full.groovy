package it.uhde.periph.chips.io_expander;

import it.uhde.periph.transport.Transport;

class Pcf8575Full extends Pcf8575Minimal {
    def prev = [0xFF, 0xFF]
    def callback = null
    def pollThread = null
    def pollStop = false

    Pcf8575Full(transport) {
        super(transport)
        def buf = transport.read(2)
        prev[0] = buf[0] & 0xFF
        prev[1] = buf[1] & 0xFF
    }

    def configureInterrupt(cb) {
        callback = cb
        pollStop = false
        pollThread = Thread.startDaemon {
            while (!pollStop) {
                try {
                    def current = transport.read(2)
                    def ch0 = (current[0] ^ prev[0]) & 0xFF
                    def ch1 = (current[1] ^ prev[1]) & 0xFF
                    def changed = ch0 | (ch1 << 8)
                    if (changed != 0 && callback) {
                        prev[0] = current[0] & 0xFF
                        prev[1] = current[1] & 0xFF
                        callback(changed)
                    }
                    sleep(5)
                } catch (Exception e) { }
            }
        }
    }

    def clearInterrupt() {
        def current = transport.read(2)
        def ch0 = (current[0] ^ prev[0]) & 0xFF
        def ch1 = (current[1] ^ prev[1]) & 0xFF
        prev[0] = current[0] & 0xFF
        prev[1] = current[1] & 0xFF
        return ch0 | (ch1 << 8)
    }

    def stopInterrupt() {
        pollStop = true
        pollThread?.interrupt()
    }
}