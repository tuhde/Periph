package it.uhde.periph.chips.gas

import groovy.transform.CompileStatic
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection

import java.io.IOException
import java.util.function.IntConsumer

/**
 * CCS811 full driver — extends {@link Ccs811Minimal} with drive modes, raw sensor data,
 * environment compensation, the NTC input, thresholds, baseline save/restore, versions, soft
 * reset and the nINT interrupt (active low, open-drain, released when ALG_RESULT_DATA is read).
 */
@CompileStatic
public class Ccs811Full extends Ccs811Minimal {

    /** Interrupt source: new sample (MEAS_MODE.INT_DATARDY). */
    public static final int SOURCE_DATA_READY = 0x01
    /** Interrupt source: eCO₂ threshold crossing (INT_DATARDY + INT_THRESH). */
    public static final int SOURCE_THRESHOLD  = 0x02

    private static final int INT_DATARDY = 0x08
    private static final int INT_THRESH  = 0x04

    /**
     * Firmware version (major.minor.trivial).
     *
     * @param major   major version
     * @param minor   minor version
     * @param trivial trivial (patch) version
     */
    static record Version(int major, int minor, int trivial) {}

    /**
     * Bootloader and application firmware versions.
     *
     * @param boot bootloader version
     * @param app  application version
     */
    static record FirmwareVersion(Version boot, Version app) {}

    private volatile IntConsumer callback
    private InputPin subscribedPin
    private volatile boolean polling
    private Thread pollThread
    private final EdgeHandler edgeHandler = this::handleEdge

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C register connection bound to address 0x5A or 0x5B
     */
    public Ccs811Full(RegisterConnection connection) {
        super(connection)
    }

    private static int intBits(int source) {
        int bits = 0
        if ((source & SOURCE_DATA_READY) != 0) bits |= INT_DATARDY
        if ((source & SOURCE_THRESHOLD) != 0) bits |= INT_DATARDY | INT_THRESH
        return bits
    }

    private void writeMeasMode() throws IOException {
        write(REG_MEAS_MODE, measMode)
    }

    /**
     * Wait for the next sample and return the equivalent CO₂.
     *
     * @return eCO₂ in ppm
     * @throws IOException on I²C error, a timeout or a chip-reported error
     */
    public int readEco2() throws IOException {
        return read().eco2Ppm()
    }

    /**
     * Wait for the next sample and return the total VOC.
     *
     * @return TVOC in ppb
     * @throws IOException on I²C error, a timeout or a chip-reported error
     */
    public int readTvoc() throws IOException {
        return read().tvocPpb()
    }

    /**
     * Read RAW_DATA.
     *
     * @return {@code {current_ua, voltage_v}} — sense current 0–63 µA and the voltage across the
     *         sensor (ADC × 1.65d / 1023)
     * @throws IOException on I²C error
     */
    public double[] readRaw() throws IOException {
        int raw = read16(REG_RAW_DATA)
        return [raw >> 10, (raw & 0x3FF) * 1.65d / 1023] as double[]
    }

    /**
     * Whether a new sample is waiting (STATUS.DATA_READY).
     *
     * @return true if DATA_READY is set
     * @throws IOException on I²C error
     */
    public boolean dataReady() throws IOException {
        return (status() & STATUS_DATA_READY) != 0
    }

    /**
     * Select the drive mode, keeping the interrupt bits: 0 idle, 1 every second, 2 every 10 s,
     * 3 every 60 s, 4 every 250 ms (raw data only). When moving to a slower mode, idle (mode 0)
     * first; the first sample at the new rate may be stale.
     *
     * @param mode drive mode 0–4
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if mode is not 0–4
     */
    public void setMode(int mode) throws IOException {
        if (mode < 0 || mode > 4) throw new IllegalArgumentException("mode must be 0..4")
        measMode = (measMode & 0x0C) | (mode << 4)
        writeMeasMode()
    }

    /**
     * Write ambient humidity and temperature (ENV_DATA) for the compensation.
     *
     * @param humidityRh   relative humidity in % (0–100)
     * @param temperatureC temperature in °C (-25 to 102)
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if a value is out of range
     */
    public void setEnvironment(double humidityRh, double temperatureC) throws IOException {
        if (!(humidityRh >= 0 && humidityRh <= 100)) throw new IllegalArgumentException("humidityRh must be 0..100")
        if (!(temperatureC >= -25 && temperatureC <= 102)) throw new IllegalArgumentException("temperatureC must be -25..102")
        int h = (int) Math.round(humidityRh * 512)
        int t = (int) Math.round((temperatureC + 25) * 512)
        write(REG_ENV_DATA, h >> 8, h & 0xFF, t >> 8, t & 0xFF)
    }

    /**
     * Read the NTC circuit voltages.
     *
     * @return {@code {v_ref_mv, v_ntc_mv}} — voltage across R_REF and R_NTC in mV
     * @throws IOException on I²C error
     */
    public int[] readNtc() throws IOException {
        byte[] b = connection.read(REG_NTC, 4)
        return [((b[0] & 0xFF) << 8) | (b[1] & 0xFF), ((b[2] & 0xFF) << 8) | (b[3] & 0xFF)] as int[]
    }

    /**
     * NTC resistance V_NTC × R_REF / V_REF.
     *
     * @param rRefOhm reference resistor in ohms
     * @return NTC resistance in ohms
     * @throws IOException on I²C error or when V_REF reads 0 (no NTC circuit)
     */
    public double ntcResistance(double rRefOhm) throws IOException {
        int[] v = readNtc()
        if (v[0] == 0) throw new IOException("CCS811 NTC reference voltage is 0")
        return (v[1] * rRefOhm / v[0]) as double
    }

    /**
     * Set the eCO₂ boundaries for the threshold interrupt.
     *
     * @param lowMediumPpm  low-to-medium boundary in ppm
     * @param mediumHighPpm medium-to-high boundary in ppm
     * @param hysteresisPpm hysteresis in ppm (0–255)
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if a value is out of range or low ≥ high
     */
    public void setThresholds(int lowMediumPpm, int mediumHighPpm, int hysteresisPpm) throws IOException {
        if (!(lowMediumPpm >= 0 && lowMediumPpm < mediumHighPpm && mediumHighPpm <= 0xFFFF)) {
            throw new IllegalArgumentException("thresholds must satisfy 0 <= lowMedium < mediumHigh <= 65535")
        }
        if (hysteresisPpm < 0 || hysteresisPpm > 0xFF) throw new IllegalArgumentException("hysteresisPpm must be 0..255")
        write(REG_THRESHOLDS, lowMediumPpm >> 8, lowMediumPpm & 0xFF, mediumHighPpm >> 8, mediumHighPpm & 0xFF,
              hysteresisPpm)
    }

    /**
     * Set the default eCO₂ boundaries (1500 / 2500 ppm, hysteresis 50 ppm).
     *
     * @throws IOException on I²C error
     */
    public void setThresholds() throws IOException {
        setThresholds(1500, 2500, 50)
    }

    /**
     * Read the encoded algorithm baseline (save it to non-volatile storage).
     *
     * @return 16-bit baseline
     * @throws IOException on I²C error
     */
    public int getBaseline() throws IOException {
        return read16(REG_BASELINE)
    }

    /**
     * Restore a saved baseline (only after about 20 min of operation).
     *
     * @param baseline value from {@link #getBaseline()}
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if baseline is not 0–65535
     */
    public void setBaseline(int baseline) throws IOException {
        if (baseline < 0 || baseline > 0xFFFF) throw new IllegalArgumentException("baseline must be 0..65535")
        write(REG_BASELINE, baseline >> 8, baseline & 0xFF)
    }

    /**
     * Read STATUS.
     *
     * @return STATUS byte (STATUS_* bits)
     * @throws IOException on I²C error
     */
    public int status() throws IOException {
        return readByte(REG_STATUS)
    }

    /**
     * Read ERROR_ID (bit 0 write-invalid … bit 5 heater supply).
     *
     * @return ERROR_ID byte
     * @throws IOException on I²C error
     */
    public int error() throws IOException {
        return readByte(REG_ERROR_ID)
    }

    /**
     * Read the hardware ID and version.
     *
     * @return {@code {hw_id, hw_version}} — 0x81 and 0x1X
     * @throws IOException on I²C error
     */
    public int[] hardwareVersion() throws IOException {
        return [readByte(REG_HW_ID), readByte(REG_HW_VERSION)] as int[]
    }

    private Version version(int reg) throws IOException {
        int v = read16(reg)
        return new Version(v >> 12, (v >> 8) & 0x0F, v & 0xFF)
    }

    /**
     * Read the bootloader and application firmware versions.
     *
     * @return boot and app versions
     * @throws IOException on I²C error
     */
    public FirmwareVersion firmwareVersion() throws IOException {
        return new FirmwareVersion(version(REG_FW_BOOT_VERSION), version(REG_FW_APP_VERSION))
    }

    /**
     * SW_RESET into boot mode, then start the application and mode 1 again ({@link #init()}).
     * Drive mode, interrupt bits, thresholds and environment data return to their defaults.
     *
     * @throws IOException on I²C error or if the application does not restart
     */
    public void softReset() throws IOException {
        try {
            write(REG_SW_RESET, 0x11, 0xE5, 0x72, 0x8A)
        } catch (IOException e) {
            // the chip may not acknowledge while it resets
        }
        sleepMs(20)
        init()
    }

    /**
     * Enable nINT for a source, keeping the drive mode.
     *
     * @param source SOURCE_DATA_READY and/or SOURCE_THRESHOLD (which also sets INT_DATARDY)
     * @throws IOException on I²C error
     */
    public void enableInterrupt(int source) throws IOException {
        measMode |= intBits(source)
        writeMeasMode()
    }

    /**
     * Disable nINT for a source, keeping the drive mode.
     *
     * @param source SOURCE_DATA_READY and/or SOURCE_THRESHOLD
     * @throws IOException on I²C error
     */
    public void disableInterrupt(int source) throws IOException {
        measMode &= ~intBits(source) & 0xFF
        writeMeasMode()
    }

    /**
     * Read STATUS. Does not release nINT (reading ALG_RESULT_DATA does).
     *
     * @return STATUS byte; STATUS_DATA_READY marks a new sample
     * @throws IOException on I²C error
     */
    public int pollInterrupt() throws IOException {
        return status()
    }

    /**
     * Subscribe to the interrupt using {@code connection.intPin()} (falling edge), or a 20 ms
     * polling thread when the connection has no INT pin.
     *
     * @param callback receives the STATUS byte when DATA_READY is set; it should read the sample
     *                 to release nINT
     */
    public void onInterrupt(IntConsumer callback) {
        onInterrupt(callback, connection.intPin())
    }

    /**
     * Subscribe to the interrupt on an explicit pin.
     *
     * @param callback receives the STATUS byte when DATA_READY is set
     * @param intPin   pin wired to nINT, or {@code null} for the polling fallback
     */
    public void onInterrupt(IntConsumer callback, InputPin intPin) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = new Thread(() -> {
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(20)
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            }, "ccs811-poll")
            pollThread.setDaemon(true)
            pollThread.start()
        }
    }

    /** Unsubscribe and stop delivery. */
    public void offInterrupt() {
        callback = null
        if (subscribedPin != null) {
            subscribedPin.offEdge(edgeHandler)
            subscribedPin = null
        }
        polling = false
        if (pollThread != null) {
            pollThread.interrupt()
            pollThread = null
        }
    }

    private void handleEdge() {
        IntConsumer cb = callback
        if (cb == null) return
        try {
            int status = pollInterrupt()
            if ((status & STATUS_DATA_READY) != 0) cb.accept(status)
        } catch (IOException e) {
            // a failed poll is retried on the next edge / tick
        }
    }
}
