package it.uhde.periph.chips.comms;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/** RFM97W full driver. */
public class Rfm97Full extends _Rfm9xFull {

    public Rfm97Full(Connection connection, long frequencyHz) throws IOException {
        super(connection, frequencyHz);
    }

    @Override protected long freqMinHz() { return 862_000_000L; }
    @Override protected long freqMaxHz() { return 1_020_000_000L; }
    @Override protected int  maxSf()     { return 9; }
    @Override protected boolean lfBand() { return false; }
}
