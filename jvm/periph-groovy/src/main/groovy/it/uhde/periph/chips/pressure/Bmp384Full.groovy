package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.RegisterConnection
import groovy.transform.CompileStatic

/**
 * BMP384 — full driver. Extends {@link Bmp384Minimal} with oversampling/IIR/ODR
 * configuration, power-mode switching, data-ready polling, soft reset, and
 * FIFO support.
 *
 * Mode constants: {@link #MODE_SLEEP}, {@link #MODE_FORCED}, {@link #MODE_NORMAL}
 */
@CompileStatic
class Bmp384Full extends Bmp384Minimal {

    /** One parsed FIFO frame. */
    static final class FifoFrame {
        final String type
        final double value
        FifoFrame(String type, double value) { this.type = type; this.value = value }
    }

    Bmp384Full(RegisterConnection conn) { super(conn) }
    Bmp384Full(RegisterConnection conn, int addr) { super(conn, addr) }

    void configure(int osrP, int osrT, int iirFilter, int odrSel) {
        this.osrP = osrP
        this.osrT = osrT
        this.iir  = iirFilter
        this.odr  = odrSel
        connection.write(REG_OSR, [(byte) (   (osrT << 3) | (osrP << 0))] as byte[])
        connection.write(REG_CONFIG, [(byte) ((iirFilter << 1))] as byte[])
        connection.write(REG_ODR, [(byte) (   odrSel)] as byte[])
    }

    double[] read() {
        if (mode == MODE_FORCED) {
            triggerForced()
            int tConvMs = computeTConvMs(osrP, osrT)
            try { Thread.sleep(tConvMs) } catch (InterruptedException e) { Thread.currentThread().interrupt() }
        }
        int[] burst = readBurst()
        double t = compensateTemperature(burst[1])
        double p = compensatePressure(burst[0]) / 100.0d
        return new double[]{p, t}
    }

    double[] readForced() {
        int prevMode = this.mode
        try {
            setMode(MODE_FORCED)
            triggerForced()
            int tConvMs = computeTConvMs(osrP, osrT)
            try { Thread.sleep(tConvMs) } catch (InterruptedException e) { Thread.currentThread().interrupt() }
            int[] burst = readBurst()
            double t = compensateTemperature(burst[1])
            double p = compensatePressure(burst[0]) / 100.0d
            return new double[]{p, t}
        } finally {
            this.mode = prevMode
            applyPwr()
        }
    }

    void setMode(int mode) {
        this.@mode = mode
        applyPwr()
    }

    boolean isDataReady() {
        return (readReg(REG_STATUS) & (1 << 5)) != 0
    }

    void softreset() {
        connection.write(REG_CMD, [(byte) (SOFT_RESET_CMD)] as byte[])
        try { Thread.sleep(3) } catch (InterruptedException e) { Thread.currentThread().interrupt() }
        readCalibration()
        applyConfig()
    }

    void fifoConfigure(boolean pressEn, boolean tempEn, int wtm, boolean stopOnFull) {
        int cfg1 = (1 << 4) |
            ((stopOnFull ? 1 : 0) << 3) |
            ((tempEn ? 1 : 0) << 1) |
            (pressEn ? 1 : 0)
        connection.write(0x17, [(byte) (cfg1)] as byte[])
        connection.write(0x15, [(byte) (wtm & 0xFF)] as byte[])
        connection.write(0x16, [(byte) ((wtm >> 8) & 0x01)] as byte[])
    }

    FifoFrame[] fifoRead() {
        int lenLo = readReg(0x12)
        int lenHi = readReg(0x13)
        int length = ((lenHi & 0xFF) << 8) | (lenLo & 0xFF)
        if (length == 0) return new FifoFrame[0]
        byte[] buf = connection.read(0x14, length)

        List<FifoFrame> frames = []
        int i = 0
        while (i < buf.length) {
            int hdr = buf[i] & 0xFF
            if (hdr == 0x84) {  // Pressure
                if (i + 3 >= buf.length) break
                int uncomp = ((buf[i + 3] & 0xFF) << 16) | ((buf[i + 2] & 0xFF) << 8) | (buf[i + 1] & 0xFF)
                double vPa = compensatePressureWithTLin(uncomp, tLin)
                frames.add(new FifoFrame("pressure", vPa / 100.0d))
                i += 4
            } else if (hdr == 0x90) {  // Temperature
                if (i + 3 >= buf.length) break
                int uncomp = ((buf[i + 3] & 0xFF) << 16) | ((buf[i + 2] & 0xFF) << 8) | (buf[i + 1] & 0xFF)
                double t = compensateTemperature(uncomp)
                frames.add(new FifoFrame("temperature", t))
                i += 4
            } else if (hdr == 0xA0) {  // Sensortime
                if (i + 3 >= buf.length) break
                int uncomp = ((buf[i + 3] & 0xFF) << 16) | ((buf[i + 2] & 0xFF) << 8) | (buf[i + 1] & 0xFF)
                frames.add(new FifoFrame("sensortime", (double) uncomp))
                i += 4
            } else if (hdr == 0x44 || hdr == 0x80) {  // Error / Empty
                frames.add(new FifoFrame(hdr == 0x44 ? "error" : "empty", 0.0d))
                i += 1
            } else {
                frames.add(new FifoFrame("unknown", 0.0d))
                i += 1
            }
        }
        return frames as FifoFrame[]
    }

    void fifoFlush() {
        connection.write(REG_CMD, [(byte) (FIFO_FLUSH_CMD)] as byte[])
    }

    double altitude(double seaLevelHpa) {
        double p = pressure()
        if (p <= 0.0d) return 0.0d
        return 44330.0d * (1.0d - Math.pow(p / seaLevelHpa, 1.0d / 5.255d))
    }

    double altitude() { altitude(1013.25d) }

    private void triggerForced() {
        connection.write(REG_PWR_CTRL, [(byte) ((MODE_FORCED << 4) | PWR_TEMP_EN | PWR_PRESS_EN)] as byte[])
    }

    private void applyPwr() {
        connection.write(REG_PWR_CTRL, [(byte) ((mode << 4) | PWR_TEMP_EN | PWR_PRESS_EN)] as byte[])
    }

    private double compensatePressureWithTLin(int uncompPress, double tLin) {
        this.tLin = tLin
        return compensatePressure(uncompPress)
    }

    private static int computeTConvMs(int osrP, int osrT) {
        long tConvUs = 234L
            + 392L + (1L << osrP) * 2000L
            + 313L + (1L << osrT) * 2000L
        return (int) ((tConvUs + 999) / 1000)
    }
}
