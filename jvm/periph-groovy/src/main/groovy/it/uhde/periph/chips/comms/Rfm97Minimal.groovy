package it.uhde.periph.chips.comms

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/** RFM97W minimal driver — 868/915 MHz HF band, max SF=9. */
@CompileStatic
class Rfm97Minimal extends _Rfm9xBase {
    Rfm97Minimal(Connection connection, long frequencyHz) { super(connection, frequencyHz) }
    @Override protected long freqMinHz() { return 862_000_000L }
    @Override protected long freqMaxHz() { return 1_020_000_000L }
    @Override protected int  maxSf()     { return 9 }
    @Override protected boolean lfBand() { return false }
}
