package it.uhde.periph.chips.comms;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/** RFM96W full driver. */
public class Rfm96Full extends _Rfm9xFull {

    public Rfm96Full(Connection connection, long frequencyHz) throws IOException {
        super(connection, frequencyHz);
    }

    @Override protected long freqMinHz() { return 410_000_000L; }
    @Override protected long freqMaxHz() { return  525_000_000L; }
    @Override protected int  maxSf()     { return 12; }
    @Override protected boolean lfBand() { return true; }
}
