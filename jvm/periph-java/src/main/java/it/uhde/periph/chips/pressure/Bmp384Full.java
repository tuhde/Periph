package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * BMP384 — full driver. Extends {@link Bmp384Minimal} with oversampling/IIR/ODR
 * configuration, power-mode switching, data-ready polling, soft reset, and
 * FIFO support (watermark, stop-on-full, frame parsing).
 *
 * <h2>Mode constants</h2>
 * {@link #MODE_SLEEP}, {@link #MODE_FORCED}, {@link #MODE_NORMAL}
 */
public class Bmp384Full extends Bmp384Minimal {

    /** Sleep mode: no measurements performed. */
    public static final int MODE_SLEEP  = 0;
    /** Forced mode: single measurement then sleep. */
    public static final int MODE_FORCED = 1;
    /** Normal mode: continuous measurements at the configured ODR. */
    public static final int MODE_NORMAL = 3;

    /** FIFO frame header byte for a pressure frame (3 payload bytes). */
    public static final int FIFO_HEADER_PRESS   = 0x84;
    /** FIFO frame header byte for a temperature frame (3 payload bytes). */
    public static final int FIFO_HEADER_TEMP    = 0x90;
    /** FIFO frame header byte for a sensortime frame (3 payload bytes). */
    public static final int FIFO_HEADER_SENSORT = 0xA0;
    /** FIFO frame header byte for an error frame (no payload). */
    public static final int FIFO_HEADER_ERROR   = 0x44;
    /** FIFO frame header byte for an empty frame (no payload). */
    public static final int FIFO_HEADER_EMPTY   = 0x80;

    /** Discriminator for {@link FifoFrame#type} on parsed pressure frames. */
    public static final String FIFO_TYPE_PRESS    = "pressure";
    /** Discriminator for {@link FifoFrame#type} on parsed temperature frames. */
    public static final String FIFO_TYPE_TEMP     = "temperature";
    /** Discriminator for {@link FifoFrame#type} on parsed sensortime frames. */
    public static final String FIFO_TYPE_SENSORT  = "sensortime";
    /** Discriminator for {@link FifoFrame#type} on error frames. */
    public static final String FIFO_TYPE_ERROR    = "error";
    /** Discriminator for {@link FifoFrame#type} on empty frames. */
    public static final String FIFO_TYPE_EMPTY    = "empty";
    /** Discriminator for {@link FifoFrame#type} on unknown-header frames. */
    public static final String FIFO_TYPE_UNKNOWN  = "unknown";

    /** One parsed FIFO frame. */
    public static final class FifoFrame {
        /** Frame type — one of {@link #FIFO_TYPE_PRESS} etc. */
        public final String type;
        /** Pressure (hPa), temperature (°C), sensortime (raw ticks), or 0 for error/empty/unknown. */
        public final double value;

        public FifoFrame(String type, double value) {
            this.type  = type;
            this.value = value;
        }
    }

    public Bmp384Full(Connection conn) throws IOException { super(conn); }
    public Bmp384Full(Connection conn, int addr) throws IOException { super(conn, addr); }

    /**
     * Write OSR, CONFIG, and ODR registers.
     *
     * @param osrP      pressure oversampling (0–5, where 0=×1, 1=×2, … 5=×32)
     * @param osrT      temperature oversampling (0–5)
     * @param iirFilter IIR filter coefficient index (0=bypass, 1=1, 2=3, … 7=127)
     * @param odrSel    output data rate selector (0x00–0x11)
     * @throws IOException on I²C error
     */
    public void configure(int osrP, int osrT, int iirFilter, int odrSel) throws IOException {
        this.osrP = osrP;
        this.osrT = osrT;
        this.iir   = iirFilter;
        this.odr   = odrSel;
        writeReg(REG_OSR,    (osrT << 3) | (osrP << 0));
        writeReg(REG_CONFIG, (iirFilter << 1));
        writeReg(REG_ODR,    odrSel);
    }

    /**
     * Read both pressure and temperature in a single burst.
     *
     * @return two-element array {@code [pressure_hPa, temperature_C]}
     * @throws IOException on I²C error
     */
    public double[] read() throws IOException {
        if (mode == MODE_FORCED) {
            triggerForced();
        }
        int[] burst = readBurst();
        double t = compensateTemperature(burst[1]);
        double p = compensatePressure(burst[0]) / 100.0;
        return new double[]{p, t};
    }

    /**
     * Trigger a forced measurement, wait T_conv, then return both values.
     *
     * @return two-element array {@code [pressure_hPa, temperature_C]}
     * @throws IOException on I²C error
     */
    public double[] readForced() throws IOException {
        int prevMode = this.mode;
        try {
            setMode(MODE_FORCED);
            triggerForced();
            int tConvMs = computeTConvMs(osrP, osrT);
            try { Thread.sleep(tConvMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            int[] burst = readBurst();
            double t = compensateTemperature(burst[1]);
            double p = compensatePressure(burst[0]) / 100.0;
            return new double[]{p, t};
        } finally {
            this.mode = prevMode;
            applyPwr();
        }
    }

    /**
     * Set the power mode.
     *
     * @param mode one of {@link #MODE_SLEEP}, {@link #MODE_FORCED}, {@link #MODE_NORMAL}.
     */
    public void setMode(int mode) throws IOException {
        this.mode = mode;
        applyPwr();
    }

    /**
     * @return {@code true} if STATUS.drdy_press is set.
     */
    public boolean isDataReady() throws IOException {
        return (readReg(REG_STATUS) & (1 << 5)) != 0;
    }

    /**
     * Soft-reset, re-read calibration, re-apply configuration.
     */
    public void softreset() throws IOException {
        writeReg(REG_CMD, SOFT_RESET_CMD);
        try { Thread.sleep(3); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        readCalibration();
        applyConfig();
    }

    /**
     * Configure FIFO source, watermark, and stop-on-full behaviour.
     *
     * @param pressEn     store pressure frames in FIFO
     * @param tempEn      store temperature frames in FIFO
     * @param wtm         FIFO watermark in bytes (0–511)
     * @param stopOnFull  if true, stop writing at full (no overwrite)
     */
    public void fifoConfigure(boolean pressEn, boolean tempEn, int wtm, boolean stopOnFull) throws IOException {
        int cfg1 = (1 << 4)
            | ((stopOnFull ? 1 : 0) << 3)
            | ((tempEn ? 1 : 0) << 1)
            | (pressEn ? 1 : 0);
        writeReg(0x17, cfg1);
        writeReg(0x15, wtm & 0xFF);
        writeReg(0x16, (wtm >> 8) & 0x01);
    }

    /**
     * Read and parse every available FIFO frame.
     */
    public FifoFrame[] fifoRead() throws IOException {
        int lenLo = readReg(0x12);
        int lenHi = readReg(0x13);
        int length = ((lenHi & 0xFF) << 8) | (lenLo & 0xFF);
        if (length == 0) return new FifoFrame[0];
        byte[] buf = connection.writeRead(new byte[]{(byte) 0x14}, length);

        java.util.List<FifoFrame> frames = new java.util.ArrayList<>();
        int i = 0;
        while (i < buf.length) {
            int hdr = buf[i] & 0xFF;
            if (hdr == FIFO_HEADER_PRESS) {
                if (i + 3 >= buf.length) break;
                int uncomp = ((buf[i + 3] & 0xFF) << 16) | ((buf[i + 2] & 0xFF) << 8) | (buf[i + 1] & 0xFF);
                double vPa = compensatePressureWithTLin(uncomp, tLin);
                frames.add(new FifoFrame(FIFO_TYPE_PRESS, vPa / 100.0));
                i += 4;
            } else if (hdr == FIFO_HEADER_TEMP) {
                if (i + 3 >= buf.length) break;
                int uncomp = ((buf[i + 3] & 0xFF) << 16) | ((buf[i + 2] & 0xFF) << 8) | (buf[i + 1] & 0xFF);
                double t = compensateTemperature(uncomp);
                frames.add(new FifoFrame(FIFO_TYPE_TEMP, t));
                i += 4;
            } else if (hdr == FIFO_HEADER_SENSORT) {
                if (i + 3 >= buf.length) break;
                int uncomp = ((buf[i + 3] & 0xFF) << 16) | ((buf[i + 2] & 0xFF) << 8) | (buf[i + 1] & 0xFF);
                frames.add(new FifoFrame(FIFO_TYPE_SENSORT, uncomp));
                i += 4;
            } else if (hdr == FIFO_HEADER_ERROR || hdr == FIFO_HEADER_EMPTY) {
                frames.add(new FifoFrame(hdr == FIFO_HEADER_ERROR ? FIFO_TYPE_ERROR : FIFO_TYPE_EMPTY, 0.0));
                i += 1;
            } else {
                frames.add(new FifoFrame(FIFO_TYPE_UNKNOWN, 0.0));
                i += 1;
            }
        }
        return frames.toArray(new FifoFrame[0]);
    }

    /**
     * Flush the FIFO contents.
     */
    public void fifoFlush() throws IOException {
        writeReg(REG_CMD, FIFO_FLUSH_CMD);
    }

    /**
     * Compute altitude above sea level from the current pressure.
     *
     * @param seaLevelHpa reference sea-level pressure in hPa (default 1013.25)
     * @return altitude in metres
     */
    public double altitude(double seaLevelHpa) throws IOException {
        double p = pressure();
        if (p <= 0.0) return 0.0;
        return 44330.0 * (1.0 - Math.pow(p / seaLevelHpa, 1.0 / 5.255));
    }

    /** Convenience overload using the standard sea-level pressure of 1013.25 hPa. */
    public double altitude() throws IOException { return altitude(1013.25); }

    private void triggerForced() throws IOException {
        writeReg(REG_PWR_CTRL, (MODE_FORCED << 4) | PWR_TEMP_EN | PWR_PRESS_EN);
    }

    private void applyPwr() throws IOException {
        writeReg(REG_PWR_CTRL, (mode << 4) | PWR_TEMP_EN | PWR_PRESS_EN);
    }

    private double compensatePressureWithTLin(int uncompPress, double tLin) {
        this.tLin = tLin;
        return compensatePressure(uncompPress);
    }

    private static int computeTConvMs(int osrP, int osrT) {
        // T_conv per spec: 234 + 392 + 2^osr_p*2000 + 313 + 2^osr_t*2000 µs.
        long tConvUs = 234L
            + 392L + (1L << osrP) * 2000L
            + 313L + (1L << osrT) * 2000L;
        return (int) ((tConvUs + 999) / 1000);
    }
}
