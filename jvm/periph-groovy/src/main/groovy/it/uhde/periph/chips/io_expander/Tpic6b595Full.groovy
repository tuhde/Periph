package it.uhde.periph.chips.io_expander

import groovy.transform.CompileStatic
import it.uhde.periph.connection.SiPoConnection

@CompileStatic
class Tpic6b595Full extends Tpic6b595Minimal {

    Tpic6b595Full(SiPoConnection connection) {
        super(connection, 1)
    }

    Tpic6b595Full(SiPoConnection connection, int numDevices) {
        super(connection, numDevices)
    }

    @Override
    Pin pin(int n) {
        return new Pin(this, n)
    }

    void clear() { connection.clear() }

    void setOutputEnable(boolean enabled) { connection.setOutputEnable(enabled) }

    void writeAll(int[] values) {
        for (int i = 0; i < numDevices; i++) {
            int v = (i < values.length) ? values[i] : 0
            shadow[i] = v & 0xFF
        }
        flush()
    }

    @CompileStatic
    static class Pin extends Tpic6b595Minimal.Pin {
        protected Pin(Tpic6b595Full chip, int n) {
            super(chip, n)
        }
    }
}
