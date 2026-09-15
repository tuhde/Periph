package it.uhde.periph.chips.comms;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * RFM96W minimal driver — 433/470 MHz LF band, max SF=12.
 */
public class Rfm96Minimal extends _Rfm9xBase {

    /**
     * Construct an RFM96W driver at the given carrier frequency.
     *
     * @param transport   SPI transport bound to the device.
     * @param frequencyHz Carrier frequency in Hz (410 000 000 – 525 000 000).
     * @throws IOException on SPI error
     */
    public Rfm96Minimal(Connection connection, long frequencyHz) throws IOException {
        super(connection, frequencyHz);
    }

    @Override protected long freqMinHz() { return 410_000_000L; }
    @Override protected long freqMaxHz() { return  525_000_000L; }
    @Override protected int  maxSf()     { return 12; }
    @Override protected boolean lfBand() { return true; }
}
