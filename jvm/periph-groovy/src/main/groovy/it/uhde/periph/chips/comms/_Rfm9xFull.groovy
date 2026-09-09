package it.uhde.periph.chips.comms

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/** RFM9x full driver (Groovy). */
@CompileStatic
abstract class _Rfm9xFull extends _Rfm9xBase {
    _Rfm9xFull(Connection connection, long frequencyHz) { super(connection, frequencyHz) }

    void reset() {
        Thread.sleep(5)
        writeReg(REG_OP_MODE, 0x00)
        Thread.sleep(1)
        writeReg(REG_OP_MODE, MODE_LONG_RANGE | MODE_SLEEP)
        Thread.sleep(1)
        writeReg(REG_LNA, lfBand() ? (readReg(REG_LNA) & 0x3F) : 0x23)
        writeReg(REG_MODEM_CONFIG_3, readReg(REG_MODEM_CONFIG_3) | 0x04)
        writeReg(REG_FIFO_TX_BASE, 0x80)
        writeReg(REG_FIFO_RX_BASE, 0x00)
        setFrequency(frequencyHz)
        writeReg(REG_MODEM_CONFIG_1, (0x07 << 4) | (0x01 << 1) | 0x00)
        writeReg(REG_MODEM_CONFIG_2, (0x07 << 4) | (0x01 << 2) | 0x03)
        writeReg(REG_PREAMBLE_LSB, 0x08)
        setTxPower(17, true)
        standby()
    }
}
