package it.uhde.periph.chips.comms

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/** RFM98W full driver. */
@CompileStatic
class Rfm98Full extends _Rfm9xFull {
    Rfm98Full(Connection connection, long frequencyHz) { super(connection, frequencyHz) }
    @Override protected long freqMinHz() { return 410_000_000L }
    @Override protected long freqMaxHz() { return  525_000_000L }
    @Override protected int  maxSf()     { return 12 }
    @Override protected boolean lfBand() { return true }
}
