package it.uhde.periph.chips.comms

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/** RFM96W minimal driver — 433/470 MHz LF band, max SF=12. */
@CompileStatic
class Rfm96Minimal extends _Rfm9xBase {
    Rfm96Minimal(Connection connection, long frequencyHz) { super(connection, frequencyHz) }
    @Override protected long freqMinHz() { return 410_000_000L }
    @Override protected long freqMaxHz() { return  525_000_000L }
    @Override protected int  maxSf()     { return 12 }
    @Override protected boolean lfBand() { return true }
}
