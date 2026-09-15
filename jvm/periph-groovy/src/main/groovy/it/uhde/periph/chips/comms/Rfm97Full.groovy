package it.uhde.periph.chips.comms

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin

/** RFM97W full driver. */
@CompileStatic
class Rfm97Full extends _Rfm9xFull {
    Rfm97Full(Connection connection, long frequencyHz) { super(connection, frequencyHz) }

    /** Construct with optional NRESET/DIO0 pins. */
    Rfm97Full(Connection connection, long frequencyHz, OutputPin resetPin, InputPin dio0Pin) {
        super(connection, frequencyHz, resetPin, dio0Pin)
    }
    @Override protected long freqMinHz() { return 862_000_000L }
    @Override protected long freqMaxHz() { return 1_020_000_000L }
    @Override protected int  maxSf()     { return 9 }
    @Override protected boolean lfBand() { return false }
}
