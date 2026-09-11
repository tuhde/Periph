package it.uhde.periph.chips.comms;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.OutputPin;

import java.io.IOException;

/** RFM98W full driver. */
public class Rfm98Full extends _Rfm9xFull {

    public Rfm98Full(Connection connection, long frequencyHz) throws IOException {
        super(connection, frequencyHz);
    }

    /** Construct with optional NRESET/DIO0 pins. */
    public Rfm98Full(Connection connection, long frequencyHz, OutputPin resetPin, InputPin dio0Pin) throws IOException {
        super(connection, frequencyHz, resetPin, dio0Pin);
    }

    @Override protected long freqMinHz() { return 410_000_000L; }
    @Override protected long freqMaxHz() { return  525_000_000L; }
    @Override protected int  maxSf()     { return 12; }
    @Override protected boolean lfBand() { return true; }
}
