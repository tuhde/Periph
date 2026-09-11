package it.uhde.periph.chips.comms;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.OutputPin;

import java.io.IOException;

/**
 * RFM9x (RFM95/96/97/98W) LoRa transceiver — minimal driver.
 *
 * <p>All four modules share identical pins, register maps, SPI protocol, and
 * LoRa modem logic. They differ only in supported frequency bands and the
 * maximum spreading factor for RFM97W.
 *
 * <p>The driver is built around an internal {@link _Rfm9xBase} that owns all
 * register logic. Four thin variant subclasses — {@link Rfm95Minimal},
 * {@link Rfm96Minimal}, {@link Rfm97Minimal}, {@link Rfm98Minimal} —
 * supply the variant-specific frequency limits, maximum SF, and band flag.
 *
 * <p>Only the Minimal-stage public API ({@link #send}, {@link #receive})
 * lives here as {@code public}; Full-stage functionality (configuration,
 * power management, continuous receive, RSSI/SNR, ...) is implemented as
 * {@code protected} and re-exposed publicly by {@link _Rfm9xFull}, so
 * {@code RfmXXMinimal} instances never see it.
 *
 * <p>An optional NRESET {@link OutputPin} may be supplied (Full only, via
 * {@link _Rfm9xFull}); when present it drives a real hardware reset both at
 * construction and on {@link _Rfm9xFull#reset()}. Without it, construction
 * falls back to waiting out the POR delay.
 *
 * <p>Default configuration (baked in at construction):
 * <ul>
 *   <li>SF 7, BW 125 kHz, CR 4/5, explicit header, CRC on</li>
 *   <li>Preamble length 8</li>
 *   <li>TX power +17 dBm on PA_BOOST</li>
 *   <li>FIFO split: TX base 0x80, RX base 0x00</li>
 *   <li>AGC enabled (RegModemConfig3 |= 0x04)</li>
 *   <li>LNA boost enabled (HF variants only)</li>
 * </ul>
 *
 * @param transport SPI transport bound to the device (CS managed by the transport).
 * @param frequencyHz Carrier frequency in Hz; must lie in the variant's range.
 */
abstract class _Rfm9xBase {

    // --- Register addresses ---
    static final int REG_FIFO            = 0x00;
    static final int REG_OP_MODE         = 0x01;
    static final int REG_FRF_MSB         = 0x06;
    static final int REG_FRF_MID         = 0x07;
    static final int REG_FRF_LSB         = 0x08;
    static final int REG_PA_CONFIG       = 0x09;
    static final int REG_OCP             = 0x0B;
    static final int REG_LNA             = 0x0C;
    static final int REG_FIFO_ADDR_PTR   = 0x0D;
    static final int REG_FIFO_TX_BASE    = 0x0E;
    static final int REG_FIFO_RX_BASE    = 0x0F;
    static final int REG_FIFO_RX_CURRENT = 0x10;
    static final int REG_IRQ_FLAGS       = 0x12;
    static final int REG_RX_NB_BYTES     = 0x13;
    static final int REG_PKT_SNR         = 0x19;
    static final int REG_PKT_RSSI        = 0x1A;
    static final int REG_RSSI            = 0x1B;
    static final int REG_MODEM_CONFIG_1  = 0x1D;
    static final int REG_MODEM_CONFIG_2  = 0x1E;
    static final int REG_PREAMBLE_LSB    = 0x21;
    static final int REG_PAYLOAD_LENGTH  = 0x22;
    static final int REG_MODEM_CONFIG_3  = 0x26;
    static final int REG_DETECTION_OPT   = 0x31;
    static final int REG_DETECTION_THR   = 0x37;
    static final int REG_DIO_MAPPING_1   = 0x40;
    static final int REG_VERSION         = 0x42;
    static final int REG_PA_DAC          = 0x4D;

    static final int MODE_LONG_RANGE = 0x80;
    static final int MODE_SLEEP      = 0x00;
    static final int MODE_STANDBY    = 0x01;
    static final int MODE_TX         = 0x03;
    static final int MODE_RX_CONT    = 0x05;
    static final int MODE_RX_SINGLE  = 0x06;

    static final int IRQ_TX_DONE    = 0x08;
    static final int IRQ_RX_DONE    = 0x40;
    static final int IRQ_RX_TIMEOUT = 0x80;

    static final int PA_BOOST          = 0x80;
    static final int PA_DAC_HIGH_POWER = 0x87;
    static final int PA_DAC_DEFAULT    = 0x84;
    static final int OCP_240MA         = 0x3B;
    static final int OCP_DEFAULT       = 0x2B;

    static final int DIO0_RX_DONE = 0x00;
    static final int DIO0_TX_DONE = 0x40;

    static final long FXOSC             = 32_000_000L;
    static final int  EXPECTED_VERSION  = 0x12;

    protected final Connection connection;
    protected final OutputPin resetPin;
    protected long frequencyHz;

    /** Variant-specific minimum carrier frequency in Hz. */
    protected abstract long freqMinHz();
    /** Variant-specific maximum carrier frequency in Hz. */
    protected abstract long freqMaxHz();
    /** Variant-specific maximum spreading factor. */
    protected abstract int  maxSf();
    /** True for LF-band variants (RFM96W, RFM98W). */
    protected abstract boolean lfBand();

    _Rfm9xBase(Connection connection, long frequencyHz) throws IOException {
        this(connection, frequencyHz, null);
    }

    /**
     * Package-private constructor used by {@link _Rfm9xFull} to wire an
     * optional NRESET pin through to the initial reset.
     */
    _Rfm9xBase(Connection connection, long frequencyHz, OutputPin resetPin) throws IOException {
        this.connection  = connection;
        this.frequencyHz = frequencyHz;
        this.resetPin    = resetPin;
        if (frequencyHz < freqMinHz() || frequencyHz > freqMaxHz()) {
            throw new IllegalArgumentException(
                "frequencyHz " + frequencyHz + " out of range [" + freqMinHz() + ", " + freqMaxHz() + "]");
        }

        if (resetPin != null) {
            resetViaPin();
        } else {
            sleepMs(10);
        }
        initRegisters();
    }

    /**
     * Run the register-level init sequence: FSK-sleep → LoRa-sleep, LNA/AGC
     * setup, FIFO split, carrier frequency, default modem parameters, TX
     * power, then STDBY. Shared by the constructor and {@link _Rfm9xFull#reset()}.
     */
    protected void initRegisters() throws IOException {
        writeReg(REG_OP_MODE, 0x00);
        sleepMs(1);
        writeReg(REG_OP_MODE, MODE_LONG_RANGE | MODE_SLEEP);
        sleepMs(1);

        if (lfBand()) {
            int lna = readReg(REG_LNA);
            writeReg(REG_LNA, lna & 0x3F);
        } else {
            writeReg(REG_LNA, 0x23);
        }
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

    /** Assert NRESET low, then release it (active-low hardware reset). */
    protected void resetViaPin() throws IOException {
        resetPin.set(false);
        sleepMs(1);
        resetPin.set(true);
        sleepMs(5);
    }

    /** Sleep, swallowing {@link InterruptedException} by re-asserting the interrupt flag. */
    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected void writeReg(int reg, int value) throws IOException {
        connection.write(new byte[] { (byte)(reg | 0x80), (byte)(value & 0xFF) });
    }

    protected int readReg(int reg) throws IOException {
        return connection.writeRead(new byte[] { (byte)(reg & 0x7F) }, 1)[0] & 0xFF;
    }

    protected void burstWrite(int reg, byte[] data) throws IOException {
        byte[] buf = new byte[data.length + 1];
        buf[0] = (byte)(reg | 0x80);
        System.arraycopy(data, 0, buf, 1, data.length);
        connection.write(buf);
    }

    protected byte[] burstRead(int reg, int n) throws IOException {
        return connection.writeRead(new byte[] { (byte)(reg & 0x7F) }, n);
    }

    /**
     * Set the carrier frequency.
     *
     * @param frequencyHz Carrier frequency in Hz; must lie in the variant's range.
     * @throws IOException on SPI error
     */
    protected void setFrequency(long frequencyHz) throws IOException {
        if (frequencyHz < freqMinHz() || frequencyHz > freqMaxHz()) {
            throw new IllegalArgumentException(
                "frequencyHz " + frequencyHz + " out of range [" + freqMinHz() + ", " + freqMaxHz() + "]");
        }
        long frf = (frequencyHz << 19) / FXOSC;
        writeReg(REG_FRF_MSB, (int)((frf >> 16) & 0xFF));
        writeReg(REG_FRF_MID, (int)((frf >>  8) & 0xFF));
        writeReg(REG_FRF_LSB, (int)( frf        & 0xFF));
        this.frequencyHz = frequencyHz;
    }

    /**
     * Set TX output power.
     *
     * @param powerDbm    Output power in dBm. −1 to +14 (RFO) or +2 to +20 (PA_BOOST).
     * @param usePaBoost  True to use PA_BOOST pin (default), false for RFO.
     * @throws IOException on SPI error
     */
    protected void setTxPower(int powerDbm, boolean usePaBoost) throws IOException {
        if (usePaBoost) {
            if (powerDbm > 17) {
                if (powerDbm > 20) powerDbm = 20;
                writeReg(REG_PA_DAC, PA_DAC_HIGH_POWER);
                writeReg(REG_OCP, OCP_240MA);
                writeReg(REG_PA_CONFIG, PA_BOOST | 0x0F);
            } else {
                if (powerDbm < 2) powerDbm = 2;
                writeReg(REG_PA_DAC, PA_DAC_DEFAULT);
                writeReg(REG_OCP, OCP_DEFAULT);
                writeReg(REG_PA_CONFIG, PA_BOOST | (powerDbm - 2));
            }
        } else {
            writeReg(REG_PA_DAC, PA_DAC_DEFAULT);
            writeReg(REG_OCP, OCP_DEFAULT);
            int maxPower = 7;
            double pmax = 10.8 + 0.6 * maxPower;
            int op = (int)(powerDbm - pmax + 15);
            if (op < 0) op = 0;
            if (op > 15) op = 15;
            writeReg(REG_PA_CONFIG, (maxPower << 4) | op);
        }
    }

    /**
     * Configure LoRa modulation parameters.
     *
     * @param sf            Spreading factor 6–12 (variant-capped; RFM97W max 9).
     * @param bandwidthKhz  Signal bandwidth in kHz.
     * @param codingRate    Coding rate denominator 5–8 (4/5 … 4/8).
     * @param crc           True to enable CRC on RX payloads (default).
     * @throws IOException on SPI error
     */
    protected void configure(int sf, float bandwidthKhz, int codingRate, boolean crc) throws IOException {
        float[] bwTable = { 7.8f, 10.4f, 15.6f, 20.8f, 31.25f, 41.7f, 62.5f, 125.0f, 250.0f, 500.0f };
        int bwCode = 0x07;
        for (int i = 0; i < 10; i++) {
            if (bandwidthKhz == bwTable[i]) { bwCode = i; break; }
        }
        if (sf < 6 || sf > maxSf()) sf = Math.min(Math.max(sf, 6), maxSf());

        if (sf == 6) {
            writeReg(REG_DETECTION_OPT, 0x05);
            writeReg(REG_DETECTION_THR, 0x0C);
        } else {
            writeReg(REG_DETECTION_OPT, 0x03);
            writeReg(REG_DETECTION_THR, 0x0A);
        }

        boolean implicitHeader = (sf == 6);
        int cr = (codingRate >= 5 && codingRate <= 8) ? (codingRate - 4) : 0x01;
        writeReg(REG_MODEM_CONFIG_1, (bwCode << 4) | (cr << 1) | (implicitHeader ? 1 : 0));
        writeReg(REG_MODEM_CONFIG_2, (sf << 4) | ((crc ? 1 : 0) << 2) | 0x03);
    }

    /** Enter STDBY mode (crystal on, RF/PLL off, FIFO accessible). */
    protected void standby() throws IOException {
        writeReg(REG_OP_MODE, MODE_LONG_RANGE | bandFlag() | MODE_STANDBY);
    }

    /** Enter SLEEP mode (lowest power; FIFO inaccessible). */
    protected void sleep() throws IOException {
        writeReg(REG_OP_MODE, MODE_LONG_RANGE | bandFlag() | MODE_SLEEP);
    }

    /** Read RegVersion. Expect 0x12 (SX1276). */
    protected int version() throws IOException {
        return readReg(REG_VERSION);
    }

    /**
     * Send a packet.
     *
     * @param data payload; max 255 bytes
     * @throws IOException on SPI error
     */
    public void send(byte[] data) throws IOException {
        int len = Math.min(data.length, 255);
        standby();
        writeReg(REG_FIFO_ADDR_PTR, 0x80);
        byte[] payload = new byte[len];
        System.arraycopy(data, 0, payload, 0, len);
        burstWrite(REG_FIFO, payload);
        writeReg(REG_PAYLOAD_LENGTH, len);
        writeReg(REG_DIO_MAPPING_1, DIO0_TX_DONE);
        writeReg(REG_OP_MODE, MODE_LONG_RANGE | bandFlag() | MODE_TX);
        while (true) {
            int irq = readReg(REG_IRQ_FLAGS);
            if ((irq & IRQ_TX_DONE) != 0) break;
            sleepMs(2);
        }
        writeReg(REG_IRQ_FLAGS, IRQ_TX_DONE);
        standby();
    }

    /**
     * Receive a single packet.
     *
     * @param timeoutMs receive timeout in milliseconds (default 2000)
     * @return received payload bytes, or null on timeout
     * @throws IOException on SPI error
     */
    public byte[] receive(int timeoutMs) throws IOException {
        int t = (timeoutMs <= 0) ? 2000 : timeoutMs;
        standby();
        writeReg(REG_DIO_MAPPING_1, DIO0_RX_DONE);
        writeReg(REG_OP_MODE, MODE_LONG_RANGE | bandFlag() | MODE_RX_SINGLE);
        int elapsed = 0;
        while (elapsed < t) {
            int irq = readReg(REG_IRQ_FLAGS);
            if ((irq & IRQ_RX_DONE) != 0) {
                writeReg(REG_IRQ_FLAGS, IRQ_RX_DONE);
                return readPayload();
            }
            if ((irq & IRQ_RX_TIMEOUT) != 0) {
                writeReg(REG_IRQ_FLAGS, IRQ_RX_TIMEOUT);
                return null;
            }
            sleepMs(5);
            elapsed += 5;
        }
        writeReg(REG_OP_MODE, MODE_LONG_RANGE | bandFlag() | MODE_STANDBY);
        return null;
    }

    protected byte[] readPayload() throws IOException {
        int current = readReg(REG_FIFO_RX_CURRENT);
        writeReg(REG_FIFO_ADDR_PTR, current);
        int n = readReg(REG_RX_NB_BYTES) & 0xFF;
        return burstRead(REG_FIFO, n);
    }

    /** Enter continuous receive mode. */
    protected void receiveContinuous() throws IOException {
        standby();
        writeReg(REG_DIO_MAPPING_1, DIO0_RX_DONE);
        writeReg(REG_OP_MODE, MODE_LONG_RANGE | bandFlag() | MODE_RX_CONT);
    }

    /**
     * Read one packet from the FIFO in continuous receive mode.
     *
     * @return payload bytes, or null if no packet is waiting
     */
    protected byte[] readPacket() throws IOException {
        int irq = readReg(REG_IRQ_FLAGS);
        if ((irq & IRQ_RX_DONE) == 0) return null;
        writeReg(REG_IRQ_FLAGS, IRQ_RX_DONE);
        return readPayload();
    }

    /** Return to STDBY from continuous receive mode. */
    protected void stopReceive() throws IOException {
        standby();
    }

    /** Current channel RSSI in dBm (readable in continuous RX). */
    protected float rssi() throws IOException {
        return -137.0f + readReg(REG_RSSI);
    }

    /** RSSI of last received packet in dBm. */
    protected float lastPacketRssi() throws IOException {
        return -137.0f + readReg(REG_PKT_RSSI);
    }

    /** SNR of last received packet in dB. */
    protected float lastPacketSnr() throws IOException {
        int raw = readReg(REG_PKT_SNR);
        if ((raw & 0x80) != 0) raw = raw - 0x100;
        return raw / 4.0f;
    }

    protected int bandFlag() { return lfBand() ? 0x08 : 0x00; }
}
