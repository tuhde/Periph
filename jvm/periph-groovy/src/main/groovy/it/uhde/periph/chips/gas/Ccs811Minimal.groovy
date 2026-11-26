package it.uhde.periph.chips.gas

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

import java.io.IOException

/**
 * CCS811 — indoor air quality gas sensor (ScioSense / ams), minimal driver.
 *
 * <p>One call returns the equivalent CO₂ (eCO₂) and total VOC (TVOC) values of the chip's
 * internal algorithm. The constructor does no bus access; {@link #init()} verifies HW_ID
 * ({@code 0x81}), writes APP_START if the chip is still in boot mode and selects drive mode 1
 * (MEAS_MODE = 0x10: one sample per second, interrupts off). No environment data is written (the
 * chip assumes 50 %RH and 25 °C). The chip stretches the I²C clock; tie nWAKE low. New sensors
 * need a 48 h burn-in and values settle about 20 min after each power-up. I²C address
 * {@code 0x5A} (ADDR low) or {@code 0x5B} (ADDR high).
 */
@CompileStatic
public class Ccs811Minimal {

    protected static final int REG_STATUS          = 0x00
    protected static final int REG_MEAS_MODE       = 0x01
    protected static final int REG_ALG_RESULT_DATA = 0x02
    protected static final int REG_RAW_DATA        = 0x03
    protected static final int REG_ENV_DATA        = 0x05
    protected static final int REG_NTC             = 0x06
    protected static final int REG_THRESHOLDS      = 0x10
    protected static final int REG_BASELINE        = 0x11
    protected static final int REG_HW_ID           = 0x20
    protected static final int REG_HW_VERSION      = 0x21
    protected static final int REG_FW_BOOT_VERSION = 0x23
    protected static final int REG_FW_APP_VERSION  = 0x24
    protected static final int REG_ERROR_ID        = 0xE0
    protected static final int REG_APP_START       = 0xF4
    protected static final int REG_SW_RESET        = 0xFF

    protected static final int HW_ID  = 0x81
    protected static final int MODE_1 = 0x10
    /** Sample interval in ms per drive mode (0 = idle). */
    protected static final int[] INTERVAL_MS = [0, 1000, 10000, 60000, 250] as int[]

    /** STATUS.FW_MODE: application mode. */
    public static final int STATUS_FW_MODE    = 0x80
    /** STATUS.APP_VALID: valid application firmware loaded. */
    public static final int STATUS_APP_VALID  = 0x10
    /** STATUS.DATA_READY: new sample in ALG_RESULT_DATA. */
    public static final int STATUS_DATA_READY = 0x08
    /** STATUS.ERROR: see ERROR_ID. */
    public static final int STATUS_ERROR      = 0x01

    /**
     * One algorithm sample.
     *
     * @param eco2Ppm equivalent CO₂ in ppm (400–8192)
     * @param tvocPpb total VOC in ppb (0–1187)
     */
    static record Measurement(int eco2Ppm, int tvocPpb) {}

    protected final RegisterConnection connection
    protected int measMode = MODE_1

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C register connection bound to address 0x5A or 0x5B
     */
    public Ccs811Minimal(RegisterConnection connection) {
        this.connection = connection
    }

    protected int readByte(int reg) throws IOException {
        return connection.read(reg, 1)[0] & 0xFF
    }

    protected int read16(int reg) throws IOException {
        byte[] b = connection.read(reg, 2)
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF)
    }

    protected void write(int reg, int... data) throws IOException {
        byte[] b = new byte[data.length]
        for (int i = 0; i < data.length; i++) b[i] = (byte) data[i]
        connection.write(reg, b)
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Verify the chip, start the application firmware and select drive mode 1.
     *
     * @throws IOException on I²C error, a wrong HW_ID, missing application firmware, or a chip
     *                     that does not enter application mode
     */
    public void init() throws IOException {
        int id = readByte(REG_HW_ID)
        if (id != HW_ID) throw new IOException(String.format("Unexpected HW_ID 0x%02X, expected 0x81", (id) as Object))
        startApp()
        measMode = MODE_1
        write(REG_MEAS_MODE, measMode)
    }

    private void startApp() throws IOException {
        int status = readByte(REG_STATUS)
        if ((status & STATUS_APP_VALID) == 0) throw new IOException("CCS811 has no valid application firmware")
        if ((status & STATUS_FW_MODE) != 0) return
        write(REG_APP_START)
        sleepMs(2)
        if ((readByte(REG_STATUS) & STATUS_FW_MODE) == 0) throw new IOException("CCS811 did not enter application mode")
    }

    private void waitDataReady() throws IOException {
        int mode = (measMode >> 4) & 0x07
        long limit = Math.max(1500, mode < INTERVAL_MS.length ? (INTERVAL_MS[mode] * 3L).intdiv(2) as long : 0)
        long deadline = System.currentTimeMillis() + limit
        while ((readByte(REG_STATUS) & STATUS_DATA_READY) == 0) {
            if (System.currentTimeMillis() > deadline) throw new IOException("CCS811 data ready timeout")
            sleepMs(20)
        }
    }

    private Measurement readResult() throws IOException {
        byte[] b = connection.read(REG_ALG_RESULT_DATA, 5)
        if ((b[4] & STATUS_ERROR) != 0) {
            throw new IOException(String.format("CCS811 error, ERROR_ID 0x%02X", (readByte(REG_ERROR_ID)) as Object))
        }
        return new Measurement(((b[0] & 0xFF) << 8) | (b[1] & 0xFF), ((b[2] & 0xFF) << 8) | (b[3] & 0xFF))
    }

    /**
     * Wait for the next sample (DATA_READY) and read eCO₂ and TVOC.
     *
     * @return eCO₂ in ppm and TVOC in ppb
     * @throws IOException on I²C error, a timeout, or a chip-reported error (the message carries
     *                     ERROR_ID)
     */
    public Measurement read() throws IOException {
        waitDataReady()
        return readResult()
    }
}
