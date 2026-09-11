package it.uhde.periph.chips.comms

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.OutputPin

/**
 * RFM9x (RFM95/96/97/98W) LoRa transceiver — minimal driver (Groovy).
 *
 * All four modules share identical pins, register maps, SPI protocol, and
 * LoRa modem logic. They differ only in supported frequency bands and the
 * maximum spreading factor for RFM97W.
 *
 * Only the Minimal-stage public API (send, receive) is public here;
 * Full-stage functionality is protected and re-exposed publicly by
 * {@link _Rfm9xFull}, so {@code RfmXXMinimal} instances never see it.
 *
 * An optional NRESET {@link OutputPin} may be supplied (Full only, via
 * {@link _Rfm9xFull}); when present it drives a real hardware reset both at
 * construction and on {@code _Rfm9xFull#reset()}. Without it, construction
 * falls back to waiting out the POR delay.
 */
@CompileStatic
abstract class _Rfm9xBase {

    static final int REG_FIFO            = 0x00
    static final int REG_OP_MODE         = 0x01
    static final int REG_FRF_MSB         = 0x06
    static final int REG_FRF_MID         = 0x07
    static final int REG_FRF_LSB         = 0x08
    static final int REG_PA_CONFIG       = 0x09
    static final int REG_OCP             = 0x0B
    static final int REG_LNA             = 0x0C
    static final int REG_FIFO_ADDR_PTR   = 0x0D
    static final int REG_FIFO_TX_BASE    = 0x0E
    static final int REG_FIFO_RX_BASE    = 0x0F
    static final int REG_FIFO_RX_CURRENT = 0x10
    static final int REG_IRQ_FLAGS       = 0x12
    static final int REG_RX_NB_BYTES     = 0x13
    static final int REG_PKT_SNR         = 0x19
    static final int REG_PKT_RSSI        = 0x1A
    static final int REG_RSSI            = 0x1B
    static final int REG_MODEM_CONFIG_1  = 0x1D
    static final int REG_MODEM_CONFIG_2  = 0x1E
    static final int REG_PREAMBLE_LSB    = 0x21
    static final int REG_PAYLOAD_LENGTH  = 0x22
    static final int REG_MODEM_CONFIG_3  = 0x26
    static final int REG_DETECTION_OPT   = 0x31
    static final int REG_DETECTION_THR   = 0x37
    static final int REG_DIO_MAPPING_1   = 0x40
    static final int REG_VERSION         = 0x42
    static final int REG_PA_DAC          = 0x4D

    static final int MODE_LONG_RANGE = 0x80
    static final int MODE_SLEEP      = 0x00
    static final int MODE_STANDBY    = 0x01
    static final int MODE_TX         = 0x03
    static final int MODE_RX_CONT    = 0x05
    static final int MODE_RX_SINGLE  = 0x06

    static final int IRQ_TX_DONE    = 0x08
    static final int IRQ_RX_DONE    = 0x40
    static final int IRQ_RX_TIMEOUT = 0x80

    static final int PA_BOOST          = 0x80
    static final int PA_DAC_HIGH_POWER = 0x87
    static final int PA_DAC_DEFAULT    = 0x84
    static final int OCP_240MA         = 0x3B
    static final int OCP_DEFAULT       = 0x2B

    static final int DIO0_RX_DONE = 0x00
    static final int DIO0_TX_DONE = 0x40

    static final long FXOSC             = 32_000_000L
    static final int  EXPECTED_VERSION  = 0x12

    protected final Connection connection
    protected final OutputPin resetPin
    protected long frequencyHz

    protected abstract long freqMinHz()
    protected abstract long freqMaxHz()
    protected abstract int  maxSf()
    protected abstract boolean lfBand()

    _Rfm9xBase(Connection connection, long frequencyHz) {
        this(connection, frequencyHz, (OutputPin) null)
    }

    /** Package-private constructor used by {@link _Rfm9xFull} to wire an optional NRESET pin. */
    _Rfm9xBase(Connection connection, long frequencyHz, OutputPin resetPin) {
        this.connection  = connection
        this.frequencyHz = frequencyHz
        this.resetPin    = resetPin
        if (frequencyHz < freqMinHz() || frequencyHz > freqMaxHz()) {
            throw new IllegalArgumentException(
                "frequencyHz ${frequencyHz} out of range [${freqMinHz()}, ${freqMaxHz()}]")
        }

        if (resetPin != null) {
            resetViaPin()
        } else {
            sleepMs(10)
        }
        initRegisters()
    }

    /**
     * Run the register-level init sequence: FSK-sleep -> LoRa-sleep, LNA/AGC
     * setup, FIFO split, carrier frequency, default modem parameters, TX
     * power, then STDBY. Shared by the constructor and {@code _Rfm9xFull#reset()}.
     */
    protected void initRegisters() {
        writeReg(REG_OP_MODE, 0x00)
        sleepMs(1)
        writeReg(REG_OP_MODE, MODE_LONG_RANGE | MODE_SLEEP)
        sleepMs(1)

        if (lfBand()) {
            int lna = readReg(REG_LNA)
            writeReg(REG_LNA, lna & 0x3F)
        } else {
            writeReg(REG_LNA, 0x23)
        }
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

    /** Assert NRESET low, then release it (active-low hardware reset). */
    protected void resetViaPin() {
        resetPin.set(false)
        sleepMs(1)
        resetPin.set(true)
        sleepMs(5)
    }

    /** Sleep, swallowing InterruptedException by re-asserting the interrupt flag. */
    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }
    }

    protected void writeReg(int reg, int value) {
        connection.write(new byte[] { (byte)(reg | 0x80), (byte)(value & 0xFF) } as byte[])
    }

    protected int readReg(int reg) {
        return (connection.writeRead(new byte[] { (byte)(reg & 0x7F) } as byte[], 1)[0] as int) & 0xFF
    }

    protected void burstWrite(int reg, byte[] data) {
        byte[] buf = new byte[data.length + 1]
        buf[0] = (byte)(reg | 0x80)
        System.arraycopy(data, 0, buf, 1, data.length)
        connection.write(buf)
    }

    protected byte[] burstRead(int reg, int n) {
        return connection.writeRead(new byte[] { (byte)(reg & 0x7F) } as byte[], n)
    }

    protected void setFrequency(long frequencyHz) {
        if (frequencyHz < freqMinHz() || frequencyHz > freqMaxHz()) {
            throw new IllegalArgumentException(
                "frequencyHz ${frequencyHz} out of range [${freqMinHz()}, ${freqMaxHz()}]")
        }
        long frf = (frequencyHz << 19).intdiv(FXOSC)
        writeReg(REG_FRF_MSB, (int)((frf >> 16) & 0xFF))
        writeReg(REG_FRF_MID, (int)((frf >>  8) & 0xFF))
        writeReg(REG_FRF_LSB, (int)( frf        & 0xFF))
        this.frequencyHz = frequencyHz
    }

    protected void setTxPower(int powerDbm, boolean usePaBoost) {
        int p = powerDbm
        if (usePaBoost) {
            if (p > 17) {
                if (p > 20) p = 20
                writeReg(REG_PA_DAC, PA_DAC_HIGH_POWER)
                writeReg(REG_OCP, OCP_240MA)
                writeReg(REG_PA_CONFIG, PA_BOOST | 0x0F)
            } else {
                if (p < 2) p = 2
                writeReg(REG_PA_DAC, PA_DAC_DEFAULT)
                writeReg(REG_OCP, OCP_DEFAULT)
                writeReg(REG_PA_CONFIG, PA_BOOST | (p - 2))
            }
        } else {
            writeReg(REG_PA_DAC, PA_DAC_DEFAULT)
            writeReg(REG_OCP, OCP_DEFAULT)
            int maxPower = 7
            double pmax = 10.8 + 0.6 * maxPower
            int op = (int)(p - pmax + 15)
            if (op < 0) op = 0
            if (op > 15) op = 15
            writeReg(REG_PA_CONFIG, (maxPower << 4) | op)
        }
    }

    protected void configure(int sf, float bandwidthKhz, int codingRate, boolean crc) {
        float[] bwTable = [7.8f, 10.4f, 15.6f, 20.8f, 31.25f, 41.7f, 62.5f, 125.0f, 250.0f, 500.0f]
        int bwCode = 0x07
        for (int i = 0; i < 10; i++) {
            if (bandwidthKhz == bwTable[i]) { bwCode = i; break }
        }
        int s = sf
        if (s < 6 || s > maxSf()) s = Math.min(Math.max(s, 6), maxSf())

        if (s == 6) {
            writeReg(REG_DETECTION_OPT, 0x05)
            writeReg(REG_DETECTION_THR, 0x0C)
        } else {
            writeReg(REG_DETECTION_OPT, 0x03)
            writeReg(REG_DETECTION_THR, 0x0A)
        }

        boolean implicitHeader = (s == 6)
        int cr = (codingRate >= 5 && codingRate <= 8) ? (codingRate - 4) : 0x01
        writeReg(REG_MODEM_CONFIG_1, (bwCode << 4) | (cr << 1) | (implicitHeader ? 1 : 0))
        writeReg(REG_MODEM_CONFIG_2, (s << 4) | ((crc ? 1 : 0) << 2) | 0x03)
    }

    protected void standby() { writeReg(REG_OP_MODE, MODE_LONG_RANGE | bandFlag() | MODE_STANDBY) }
    protected void sleep()   { writeReg(REG_OP_MODE, MODE_LONG_RANGE | bandFlag() | MODE_SLEEP) }

    protected int version() { return readReg(REG_VERSION) }

    void send(byte[] data) {
        int len = Math.min(data.length, 255)
        standby()
        writeReg(REG_FIFO_ADDR_PTR, 0x80)
        byte[] payload = new byte[len]
        System.arraycopy(data, 0, payload, 0, len)
        burstWrite(REG_FIFO, payload)
        writeReg(REG_PAYLOAD_LENGTH, len)
        writeReg(REG_DIO_MAPPING_1, DIO0_TX_DONE)
        writeReg(REG_OP_MODE, MODE_LONG_RANGE | bandFlag() | MODE_TX)
        while (true) {
            int irq = readReg(REG_IRQ_FLAGS)
            if ((irq & IRQ_TX_DONE) != 0) break
            sleepMs(2)
        }
        writeReg(REG_IRQ_FLAGS, IRQ_TX_DONE)
        standby()
    }

    byte[] receive(int timeoutMs) {
        int t = (timeoutMs <= 0) ? 2000 : timeoutMs
        standby()
        writeReg(REG_DIO_MAPPING_1, DIO0_RX_DONE)
        writeReg(REG_OP_MODE, MODE_LONG_RANGE | bandFlag() | MODE_RX_SINGLE)
        int elapsed = 0
        while (elapsed < t) {
            int irq = readReg(REG_IRQ_FLAGS)
            if ((irq & IRQ_RX_DONE) != 0) {
                writeReg(REG_IRQ_FLAGS, IRQ_RX_DONE)
                return readPayload()
            }
            if ((irq & IRQ_RX_TIMEOUT) != 0) {
                writeReg(REG_IRQ_FLAGS, IRQ_RX_TIMEOUT)
                return null
            }
            sleepMs(5)
            elapsed += 5
        }
        writeReg(REG_OP_MODE, MODE_LONG_RANGE | bandFlag() | MODE_STANDBY)
        return null
    }

    protected byte[] readPayload() {
        int current = readReg(REG_FIFO_RX_CURRENT)
        writeReg(REG_FIFO_ADDR_PTR, current)
        int n = readReg(REG_RX_NB_BYTES) & 0xFF
        return burstRead(REG_FIFO, n)
    }

    protected void receiveContinuous() {
        standby()
        writeReg(REG_DIO_MAPPING_1, DIO0_RX_DONE)
        writeReg(REG_OP_MODE, MODE_LONG_RANGE | bandFlag() | MODE_RX_CONT)
    }

    protected byte[] readPacket() {
        int irq = readReg(REG_IRQ_FLAGS)
        if ((irq & IRQ_RX_DONE) == 0) return null
        writeReg(REG_IRQ_FLAGS, IRQ_RX_DONE)
        return readPayload()
    }

    protected void stopReceive() { standby() }

    protected float rssi() { return -137.0f + readReg(REG_RSSI) }
    protected float lastPacketRssi() { return -137.0f + readReg(REG_PKT_RSSI) }
    protected float lastPacketSnr() {
        int raw = readReg(REG_PKT_SNR)
        if ((raw & 0x80) != 0) raw -= 0x100
        return raw / 4.0f
    }

    protected int bandFlag() { return lfBand() ? 0x08 : 0x00 }
}
