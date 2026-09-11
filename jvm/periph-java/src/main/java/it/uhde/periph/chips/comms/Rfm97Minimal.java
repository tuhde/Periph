package it.uhde.periph.chips.comms;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * RFM97W minimal driver — 868/915 MHz HF band, max SF=9.
 */
public class Rfm97Minimal extends _Rfm9xBase {

    /**
     * Construct an RFM97W driver at the given carrier frequency.
     *
     * @param transport   SPI transport bound to the device.
     * @param frequencyHz Carrier frequency in Hz (862 000 000 – 1 020 000 000).
     * @throws IOException on SPI error
     */
    public Rfm97Minimal(Connection connection, long frequencyHz) throws IOException {
        super(connection, frequencyHz);
    }

    @Override protected long freqMinHz() { return 862_000_000L; }
    @Override protected long freqMaxHz() { return 1_020_000_000L; }
    @Override protected int  maxSf()     { return 9; }
    @Override protected boolean lfBand() { return false; }
}
