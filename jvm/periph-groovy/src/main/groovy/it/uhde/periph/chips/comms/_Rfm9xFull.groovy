package it.uhde.periph.chips.comms

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * RFM9x full driver (Groovy).
 *
 * Adds hardware reset — real NRESET-pin-driven reset when an OutputPin is
 * supplied to the constructor, otherwise a POR-wait fallback — and exposes
 * the full configuration API. Also adds DIO0-interrupt-driven receive via
 * {@link #receive(int, boolean)} when an InputPin is supplied.
 */
@CompileStatic
abstract class _Rfm9xFull extends _Rfm9xBase {

    protected final InputPin dio0Pin

    _Rfm9xFull(Connection connection, long frequencyHz) {
        super(connection, frequencyHz)
        this.dio0Pin = null
    }

    /**
     * Construct with optional NRESET/DIO0 pins.
     *
     * @param resetPin optional NRESET output pin; when present, construction
     *                 and {@link #reset()} drive a real hardware reset instead
     *                 of waiting out the POR delay
     * @param dio0Pin  optional DIO0 input pin; required for {@link #receive(int, boolean)}
     *                 with {@code useInterrupt=true}
     */
    _Rfm9xFull(Connection connection, long frequencyHz, OutputPin resetPin, InputPin dio0Pin) {
        super(connection, frequencyHz, resetPin)
        this.dio0Pin = dio0Pin
    }

    /**
     * Hardware reset. Drives NRESET low/high when a reset pin was configured,
     * otherwise waits out the POR delay; then re-runs the full init sequence.
     */
    void reset() {
        if (resetPin != null) {
            resetViaPin()
        } else {
            sleepMs(5)
        }
        initRegisters()
    }

    @Override void configure(int sf, float bandwidthKhz, int codingRate, boolean crc) { super.configure(sf, bandwidthKhz, codingRate, crc) }
    @Override void setFrequency(long frequencyHz) { super.setFrequency(frequencyHz) }
    @Override void setTxPower(int powerDbm, boolean usePaBoost) { super.setTxPower(powerDbm, usePaBoost) }
    @Override void standby() { super.standby() }
    @Override void sleep() { super.sleep() }
    @Override int version() { return super.version() }
    @Override void receiveContinuous() { super.receiveContinuous() }
    @Override byte[] readPacket() { return super.readPacket() }
    @Override void stopReceive() { super.stopReceive() }
    @Override float rssi() { return super.rssi() }
    @Override float lastPacketRssi() { return super.lastPacketRssi() }
    @Override float lastPacketSnr() { return super.lastPacketSnr() }

    /**
     * Receive a single packet, optionally waiting on the DIO0 interrupt line
     * instead of polling the IRQ status register.
     *
     * @param timeoutMs    receive timeout in milliseconds
     * @param useInterrupt true to wait on the DIO0 edge instead of polling
     *                     (requires a {@code dio0Pin} passed to the constructor)
     * @return received payload bytes, or null on timeout
     */
    byte[] receive(int timeoutMs, boolean useInterrupt) {
        if (!useInterrupt) {
            return super.receive(timeoutMs)
        }
        if (dio0Pin == null) {
            throw new IllegalStateException("useInterrupt=true requires dio0Pin")
        }
        return receiveInterrupt(timeoutMs)
    }

    private byte[] receiveInterrupt(int timeoutMs) {
        int t = (timeoutMs <= 0) ? 2000 : timeoutMs
        standby()
        writeReg(REG_DIO_MAPPING_1, DIO0_RX_DONE)

        CountDownLatch latch = new CountDownLatch(1)
        EdgeHandler handler = { -> latch.countDown() } as EdgeHandler
        dio0Pin.onEdge(handler, EdgeTrigger.RISING)
        try {
            writeReg(REG_OP_MODE, MODE_LONG_RANGE | bandFlag() | MODE_RX_SINGLE)

            boolean fired
            try {
                fired = latch.await(t, TimeUnit.MILLISECONDS)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
                fired = false
            }
            if (!fired) {
                writeReg(REG_OP_MODE, MODE_LONG_RANGE | bandFlag() | MODE_STANDBY)
                return null
            }

            int irq = readReg(REG_IRQ_FLAGS)
            writeReg(REG_IRQ_FLAGS, irq)
            if ((irq & IRQ_RX_DONE) != 0) {
                return readPayload()
            }
            return null
        } finally {
            dio0Pin.offEdge(handler)
        }
    }
}
