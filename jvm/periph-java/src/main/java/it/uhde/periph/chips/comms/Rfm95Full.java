package it.uhde.periph.chips.comms;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * RFM95W full driver — extends Rfm95Minimal with full configuration API.
 */
public class Rfm95Full extends _Rfm9xFull {

    /**
     * Construct an RFM95W full driver at the given carrier frequency.
     *
     * @param transport   SPI transport bound to the device.
     * @param frequencyHz Carrier frequency in Hz (862 000 000 – 1 020 000 000).
     * @throws IOException on SPI error
     */
    public Rfm95Full(Connection connection, long frequencyHz) throws IOException {
        super(connection, frequencyHz);
    }

    @Override protected long freqMinHz() { return 862_000_000L; }
    @Override protected long freqMaxHz() { return 1_020_000_000L; }
    @Override protected int  maxSf()     { return 12; }
    @Override protected boolean lfBand() { return false; }
}
