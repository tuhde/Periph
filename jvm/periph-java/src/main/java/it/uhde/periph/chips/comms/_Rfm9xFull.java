package it.uhde.periph.chips.comms;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * RFM9x (RFM95/96/97/98W) LoRa transceiver — full driver.
 *
 * <p>Adds hardware reset (POR wait fallback; pin-driven reset wired in examples)
 * and exposes the full configuration API: {@link #configure}, {@link #setFrequency},
 * {@link #setTxPower}, {@link #receiveContinuous}, {@link #rssi}, etc.
 */
abstract class _Rfm9xFull extends _Rfm9xBase {

    _Rfm9xFull(Connection connection, long frequencyHz) throws IOException {
        super(connection, frequencyHz);
    }

    /** Re-run the full init sequence (POR wait fallback; pin-driven reset wired in examples). */
    public void reset() throws IOException {
        Thread.sleep(5);
        writeReg(REG_OP_MODE, 0x00);
        Thread.sleep(1);
        writeReg(REG_OP_MODE, MODE_LONG_RANGE | MODE_SLEEP);
        Thread.sleep(1);
        writeReg(REG_LNA, lfBand() ? (readReg(REG_LNA) & 0x3F) : 0x23);
        writeReg(REG_MODEM_CONFIG_3, readReg(REG_MODEM_CONFIG_3) | 0x04);
        writeReg(REG_FIFO_TX_BASE, 0x80);
        writeReg(REG_FIFO_RX_BASE, 0x00);
        setFrequency(frequencyHz);
        writeReg(REG_MODEM_CONFIG_1, (0x07 << 4) | (0x01 << 1) | 0x00);
        writeReg(REG_MODEM_CONFIG_2, (0x07 << 4) | (0x01 << 2) | 0x03);
        writeReg(REG_PREAMBLE_LSB, 0x08);
        setTxPower(17, true);
        standby();
    }
}
