package it.uhde.periph.chips.power;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * LM25066 — full driver. Extends {@link LM25066Minimal} with averaging, warning limits, status,
 * MOSFET control, device setup, black box and alerts.
 *
 * <p><b>WARNING:</b> {@link #operationOff()} cuts the load rail and {@link #setGateMask(int)} can
 * stop hardware faults from switching the MOSFET off (which may destroy it). Neither is ever called
 * by the constructor.
 *
 * <p>{@code SOURCE_*} constants are ALERT_MASK bit positions (1 = masked) for
 * {@link #enableInterrupt(int)} / {@link #disableInterrupt(int)}. {@link #onInterrupt(IntConsumer)}
 * uses {@code connection.intPin()} if wired (falling edge on SMBA), otherwise a 1 s polling thread.
 */
public class LM25066Full extends LM25066Minimal {

    /** VOUT under-voltage warning. */
    public static final int SOURCE_VOUT_UV_WARN = 0x8000;
    /** Input current limit warning. */
    public static final int SOURCE_IIN_WARN = 0x4000;
    /** VIN under-voltage warning. */
    public static final int SOURCE_VIN_UV_WARN = 0x2000;
    /** VIN over-voltage warning. */
    public static final int SOURCE_VIN_OV_WARN = 0x1000;
    /** Power Good negated (masked by default). */
    public static final int SOURCE_POWER_GOOD = 0x0800;
    /** Over-temperature warning. */
    public static final int SOURCE_OT_WARN = 0x0400;
    /** Over-power limit warning. */
    public static final int SOURCE_PIN_OP_WARN = 0x0100;
    /** External MOSFET shorted. */
    public static final int SOURCE_MOSFET_SHORTED = 0x0040;
    /** VIN under-voltage fault (masked by default). */
    public static final int SOURCE_VIN_UV_FAULT = 0x0020;
    /** VIN over-voltage fault. */
    public static final int SOURCE_VIN_OV_FAULT = 0x0010;
    /** IIN / PFET over-current fault. */
    public static final int SOURCE_IIN_FAULT = 0x0008;
    /** Over-temperature fault. */
    public static final int SOURCE_OT_FAULT = 0x0004;
    /** Communication fault. */
    public static final int SOURCE_CML_FAULT = 0x0002;
    /** Circuit-breaker fault. */
    public static final int SOURCE_CB_FAULT = 0x0001;

    /** Converted BLOCK_READ / AVG_BLOCK_READ / BLACK_BOX_READ telemetry. */
    public static final class Block {
        /** Diagnostic word at the time of the block. */
        public final int diagnostic;
        /** Input current in A. */
        public final double current;
        /** Output voltage in V. */
        public final double voltageOut;
        /** Input voltage in V. */
        public final double voltageIn;
        /** Input power in W. */
        public final double power;
        /** MOSFET temperature in °C. */
        public final double temperature;

        Block(int diagnostic, double current, double voltageOut, double voltageIn, double power, double temperature) {
            this.diagnostic = diagnostic;
            this.current = current;
            this.voltageOut = voltageOut;
            this.voltageIn = voltageIn;
            this.power = power;
            this.temperature = temperature;
        }
    }

    /** All warning / fault thresholds; {@code null} where disabled. */
    public static final class Limits {
        /** VIN over-voltage warning in V. */
        public final Double vinOvWarn;
        /** VIN under-voltage warning in V. */
        public final Double vinUvWarn;
        /** VOUT under-voltage warning in V. */
        public final Double voutUvWarn;
        /** Over-temperature warning in °C. */
        public final Double otWarn;
        /** Over-temperature fault in °C. */
        public final Double otFault;
        /** Input over-current warning in A. */
        public final Double iinWarn;
        /** Input over-power warning in W. */
        public final Double pinWarn;

        Limits(Double vinOvWarn, Double vinUvWarn, Double voutUvWarn, Double otWarn, Double otFault,
               Double iinWarn, Double pinWarn) {
            this.vinOvWarn = vinOvWarn;
            this.vinUvWarn = vinUvWarn;
            this.voutUvWarn = voutUvWarn;
            this.otWarn = otWarn;
            this.otFault = otFault;
            this.iinWarn = iinWarn;
            this.pinWarn = pinWarn;
        }
    }

    private volatile IntConsumer callback;
    private InputPin subscribedPin;
    private volatile boolean polling;
    private Thread pollThread;
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Construct the driver. No bus access.
     *
     * @param connection       I²C / SMBus connection bound to the chip's address
     * @param rSenseMohm       sense resistor in milliohms
     * @param currentLimitHigh false = CL pin at GND (25 mV), true = CL pin at VDD (46 mV)
     */
    public LM25066Full(RegisterConnection connection, double rSenseMohm, boolean currentLimitHigh) {
        super(connection, rSenseMohm, currentLimitHigh);
    }

    /**
     * Construct the driver with a 1 mΩ sense resistor and the CL pin at GND.
     *
     * @param connection I²C / SMBus connection bound to the chip's address
     */
    public LM25066Full(RegisterConnection connection) {
        this(connection, 1.0, false);
    }

    private Block block(int cmd) throws IOException {
        byte[] raw = connection.read(cmd, 13);
        if ((raw[0] & 0xFF) != 12) throw new IOException("LM25066 block read count " + (raw[0] & 0xFF) + " != 12");
        int[] w = new int[6];
        for (int i = 0; i < 6; i++) w[i] = (raw[1 + 2 * i] & 0xFF) | ((raw[2 + 2 * i] & 0xFF) << 8);
        return new Block(w[0], amp.fromY(w[1] & 0x0FFF), VOLT.fromY(w[2] & 0x0FFF), VOLT.fromY(w[3] & 0x0FFF),
            watt.fromY(w[4] & 0x0FFF), tempC(w[5]));
    }

    private String text(int cmd, int n) throws IOException {
        byte[] raw = connection.read(cmd, n + 1);
        if ((raw[0] & 0xFF) != n) throw new IOException("LM25066 block read count " + (raw[0] & 0xFF) + " != " + n);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) if (raw[i] != 0) sb.append((char) (raw[i] & 0xFF));
        return sb.toString();
    }

    /**
     * Read the auxiliary ADC input.
     *
     * @return voltage at the VAUX pin in V (0 … 1.16 V)
     * @throws IOException on bus error
     */
    public double voltageAux() throws IOException {
        return readConverted(CMD_READ_VAUX, VAUX);
    }

    /**
     * BLOCK_READ: time-aligned telemetry.
     *
     * @return converted block
     * @throws IOException on bus error or unexpected count byte
     */
    public Block readBlock() throws IOException {
        return block(CMD_BLOCK_READ);
    }

    /**
     * AVG_BLOCK_READ: averaged telemetry.
     *
     * @return converted block
     * @throws IOException on bus error or unexpected count byte
     */
    public Block readAverage() throws IOException {
        return block(CMD_AVG_BLOCK_READ);
    }

    /**
     * BLACK_BOX_READ: the snapshot latched at the first SMBA since {@link #clearFaults()}.
     *
     * @return converted block
     * @throws IOException on bus error or unexpected count byte
     */
    public Block blackBox() throws IOException {
        return block(CMD_BLACK_BOX);
    }

    /**
     * Averaged VIN.
     *
     * @return VIN in V
     * @throws IOException on bus error
     */
    public double voltageInAvg() throws IOException {
        return readConverted(CMD_READ_AVG_VIN, VOLT);
    }

    /**
     * Averaged VOUT.
     *
     * @return VOUT in V
     * @throws IOException on bus error
     */
    public double voltageOutAvg() throws IOException {
        return readConverted(CMD_READ_AVG_VOUT, VOLT);
    }

    /**
     * Averaged input current.
     *
     * @return current in A
     * @throws IOException on bus error
     */
    public double currentAvg() throws IOException {
        return readConverted(CMD_READ_AVG_IIN, amp);
    }

    /**
     * Averaged input power.
     *
     * @return power in W
     * @throws IOException on bus error
     */
    public double powerAvg() throws IOException {
        return readConverted(CMD_READ_AVG_PIN, watt);
    }

    /**
     * Peak input power since reset or {@link #clearPowerPeak()}.
     *
     * @return power in W
     * @throws IOException on bus error
     */
    public double powerPeak() throws IOException {
        return readConverted(CMD_READ_PIN_PEAK, watt);
    }

    /**
     * Reset the peak-power register (CLEAR_PIN_PEAK, send byte).
     *
     * @throws IOException on bus error
     */
    public void clearPowerPeak() throws IOException {
        connection.write(CMD_CLEAR_PIN_PEAK, new byte[0]);
    }

    /**
     * Average over 2^exponent samples.
     *
     * @param exponent AVGN, 0–12
     * @throws IllegalArgumentException if exponent is outside 0–12
     * @throws IOException on bus error
     */
    public void setAveraging(int exponent) throws IOException {
        if (exponent < 0 || exponent > 12) throw new IllegalArgumentException("exponent must be 0-12");
        connection.write(CMD_SAMPLES_FOR_AVG, new byte[]{(byte) exponent});
    }

    /**
     * Read SAMPLES_FOR_AVG.
     *
     * @return AVGN exponent
     * @throws IOException on bus error
     */
    public int getAveraging() throws IOException {
        return readByte(CMD_SAMPLES_FOR_AVG);
    }

    private void setLimit(int cmd, Double value, Coef coef, int disabled) throws IOException {
        writeWord(cmd, value == null ? disabled : coef.toY(value));
    }

    private Double getLimit(int cmd, Coef coef, int disabled) throws IOException {
        int y = readWord(cmd) & 0x0FFF;
        return y == disabled ? null : coef.fromY(y);
    }

    /**
     * VIN over-voltage warning.
     *
     * @param volts threshold in V, or {@code null} to disable
     * @throws IOException on bus error
     */
    public void setVinOvWarn(Double volts) throws IOException {
        setLimit(CMD_VIN_OV_WARN, volts, VOLT, 0x0FFF);
    }

    /**
     * VIN under-voltage warning.
     *
     * @param volts threshold in V, or {@code null} to disable
     * @throws IOException on bus error
     */
    public void setVinUvWarn(Double volts) throws IOException {
        setLimit(CMD_VIN_UV_WARN, volts, VOLT, 0x0000);
    }

    /**
     * VOUT under-voltage warning.
     *
     * @param volts threshold in V, or {@code null} to disable
     * @throws IOException on bus error
     */
    public void setVoutUvWarn(Double volts) throws IOException {
        setLimit(CMD_VOUT_UV_WARN, volts, VOLT, 0x0000);
    }

    /**
     * Over-temperature warning.
     *
     * @param celsius threshold in °C, or {@code null} to disable
     * @throws IOException on bus error
     */
    public void setOtWarn(Double celsius) throws IOException {
        setLimit(CMD_OT_WARN, celsius, TEMP, 0x0FFF);
    }

    /**
     * Over-temperature fault.
     *
     * @param celsius threshold in °C, or {@code null} to disable
     * @throws IOException on bus error
     */
    public void setOtFault(Double celsius) throws IOException {
        setLimit(CMD_OT_FAULT, celsius, TEMP, 0x0FFF);
    }

    /**
     * Input over-current warning.
     *
     * @param amps threshold in A, or {@code null} to disable
     * @throws IOException on bus error
     */
    public void setIinWarn(Double amps) throws IOException {
        setLimit(CMD_IIN_WARN, amps, amp, 0x0FFF);
    }

    /**
     * Input over-power warning.
     *
     * @param watts threshold in W, or {@code null} to disable
     * @throws IOException on bus error
     */
    public void setPinWarn(Double watts) throws IOException {
        setLimit(CMD_PIN_WARN, watts, watt, 0x0FFF);
    }

    /**
     * Read all warning / fault thresholds.
     *
     * @return the thresholds ({@code null} fields are disabled)
     * @throws IOException on bus error
     */
    public Limits getLimits() throws IOException {
        return new Limits(
            getLimit(CMD_VIN_OV_WARN, VOLT, 0x0FFF), getLimit(CMD_VIN_UV_WARN, VOLT, 0x0000),
            getLimit(CMD_VOUT_UV_WARN, VOLT, 0x0000), getLimit(CMD_OT_WARN, TEMP, 0x0FFF),
            getLimit(CMD_OT_FAULT, TEMP, 0x0FFF), getLimit(CMD_IIN_WARN, amp, 0x0FFF),
            getLimit(CMD_PIN_WARN, watt, 0x0FFF));
    }

    /** @return STATUS_BYTE @throws IOException on bus error */
    public int statusByte() throws IOException {
        return readByte(CMD_STATUS_BYTE);
    }

    /** @return STATUS_WORD @throws IOException on bus error */
    public int statusWord() throws IOException {
        return readWord(CMD_STATUS_WORD);
    }

    /** @return STATUS_VOUT @throws IOException on bus error */
    public int statusVout() throws IOException {
        return readByte(CMD_STATUS_VOUT);
    }

    /** @return STATUS_INPUT @throws IOException on bus error */
    public int statusInput() throws IOException {
        return readByte(CMD_STATUS_INPUT);
    }

    /** @return STATUS_TEMPERATURE @throws IOException on bus error */
    public int statusTemperature() throws IOException {
        return readByte(CMD_STATUS_TEMP);
    }

    /** @return STATUS_CML @throws IOException on bus error */
    public int statusCml() throws IOException {
        return readByte(CMD_STATUS_CML);
    }

    /** @return STATUS_MFR_SPECIFIC @throws IOException on bus error */
    public int statusMfr() throws IOException {
        return readByte(CMD_STATUS_MFR);
    }

    /** @return READ_DIAGNOSTIC_WORD (all faults and warnings) @throws IOException on bus error */
    public int diagnosticWord() throws IOException {
        return readWord(CMD_DIAGNOSTIC);
    }

    /**
     * CLEAR_FAULTS (send byte); does not switch the MOSFET on.
     *
     * @throws IOException on bus error
     */
    public void clearFaults() throws IOException {
        connection.write(CMD_CLEAR_FAULTS, new byte[0]);
    }

    /**
     * OPERATION = 0x80: switches the load on.
     *
     * @throws IOException on bus error
     */
    public void operationOn() throws IOException {
        connection.write(CMD_OPERATION, new byte[]{(byte) 0x80});
    }

    /**
     * OPERATION = 0x00: CUTS THE LOAD RAIL.
     *
     * @throws IOException on bus error
     */
    public void operationOff() throws IOException {
        connection.write(CMD_OPERATION, new byte[]{0x00});
    }

    /**
     * Whether the MOSFET is on.
     *
     * @return true if OPERATION reads 0x80 and the STATUS_BYTE OFF bit is clear
     * @throws IOException on bus error
     */
    public boolean isOn() throws IOException {
        return readByte(CMD_OPERATION) == 0x80 && (readByte(CMD_STATUS_BYTE) & 0x40) == 0;
    }

    /** @return DEVICE_SETUP @throws IOException on bus error */
    public int deviceSetup() throws IOException {
        return readByte(CMD_DEVICE_SETUP);
    }

    /**
     * Override the RETRY / CL / CB pin settings from software; only the non-null arguments are
     * overridden. Updates the driver coefficients when {@code currentLimitHigh} is given.
     *
     * @param retryCode        0–7 (bits 7:5: 0 pin, 1 none, 2 = 1 retry, 3 = 2, 4 = 4, 5 = 8, 6 = 16, 7 = unlimited), or null
     * @param currentLimitHigh true = 46 mV, false = 25 mV, or null
     * @param cbRatioHigh      true = 3.6×, false = 1.8×, or null
     * @throws IllegalArgumentException if retryCode is outside 0–7
     * @throws IOException on bus error
     */
    public void configureDevice(Integer retryCode, Boolean currentLimitHigh, Boolean cbRatioHigh) throws IOException {
        int value = 0;
        if (retryCode != null) {
            if (retryCode < 0 || retryCode > 7) throw new IllegalArgumentException("retryCode must be 0-7");
            value |= retryCode << 5;
        }
        if (currentLimitHigh != null) value |= 0x04 | (currentLimitHigh ? 0x10 : 0);
        if (cbRatioHigh != null) value |= 0x02 | (cbRatioHigh ? 0x08 : 0);
        connection.write(CMD_DEVICE_SETUP, new byte[]{(byte) value});
        if (currentLimitHigh != null) setCurrentLimit(currentLimitHigh);
    }

    /**
     * Write GATE_MASK. DANGEROUS: can disable fault shut-off of the MOSFET.
     *
     * @param mask bits 5, 4, 2 only
     * @throws IllegalArgumentException if other bits are set
     * @throws IOException on bus error
     */
    public void setGateMask(int mask) throws IOException {
        if ((mask & ~0x34) != 0) throw new IllegalArgumentException("only GATE_MASK bits 5, 4, 2 may be set");
        connection.write(CMD_GATE_MASK, new byte[]{(byte) mask});
    }

    /** @return GATE_MASK @throws IOException on bus error */
    public int getGateMask() throws IOException {
        return readByte(CMD_GATE_MASK);
    }

    /** @return CAPABILITY @throws IOException on bus error */
    public int capability() throws IOException {
        return readByte(CMD_CAPABILITY);
    }

    /** @return MFR_ID as a string @throws IOException on bus error or unexpected count byte */
    public String manufacturerId() throws IOException {
        return text(CMD_MFR_ID, 3);
    }

    /** @return MFR_MODEL as a string @throws IOException on bus error or unexpected count byte */
    public String model() throws IOException {
        return text(CMD_MFR_MODEL, 8);
    }

    /** @return MFR_REVISION as a string @throws IOException on bus error or unexpected count byte */
    public String revision() throws IOException {
        return text(CMD_MFR_REVISION, 2);
    }

    /**
     * Unmask alert source bit(s) in ALERT_MASK (read-modify-write).
     *
     * @param source one or more {@code SOURCE_*} bits
     * @throws IOException on bus error
     */
    public void enableInterrupt(int source) throws IOException {
        writeWord(CMD_ALERT_MASK, readWord(CMD_ALERT_MASK) & ~source & 0xFFFF);
    }

    /**
     * Mask alert source bit(s) in ALERT_MASK (read-modify-write).
     *
     * @param source one or more {@code SOURCE_*} bits
     * @throws IOException on bus error
     */
    public void disableInterrupt(int source) throws IOException {
        writeWord(CMD_ALERT_MASK, readWord(CMD_ALERT_MASK) | source);
    }

    /**
     * Subscribe to SMBA (falling edge) on the connection's interrupt pin, or a 1 s polling thread.
     *
     * @param callback receives READ_DIAGNOSTIC_WORD
     * @throws IOException on bus error
     */
    public void onInterrupt(IntConsumer callback) throws IOException {
        onInterrupt(callback, connection.intPin());
    }

    /**
     * Subscribe to SMBA on an explicit pin.
     *
     * @param callback receives READ_DIAGNOSTIC_WORD
     * @param intPin   pin to arm, or {@code null} to force the 1 s polling fallback
     * @throws IOException on bus error
     */
    public void onInterrupt(IntConsumer callback, InputPin intPin) throws IOException {
        offInterrupt();
        this.callback = callback;
        if (intPin != null) {
            subscribedPin = intPin;
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING);
        } else {
            polling = true;
            pollThread = new Thread(() -> {
                while (polling) {
                    handleEdge();
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "lm25066-poll");
            pollThread.setDaemon(true);
            pollThread.start();
        }
    }

    /** Unsubscribe and stop delivery. */
    public void offInterrupt() {
        callback = null;
        if (subscribedPin != null) {
            subscribedPin.offEdge(edgeHandler);
            subscribedPin = null;
        }
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    /**
     * Read READ_DIAGNOSTIC_WORD (does not clear; call {@link #clearFaults()} after servicing).
     *
     * @return diagnostic word
     * @throws IOException on bus error
     */
    public int pollInterrupt() throws IOException {
        return readWord(CMD_DIAGNOSTIC);
    }

    private void handleEdge() {
        IntConsumer cb = callback;
        if (cb == null) return;
        try {
            int status = pollInterrupt();
            if (status != 0) cb.accept(status);
        } catch (IOException e) {
            // transient bus error: the next edge or tick retries
        }
    }
}
