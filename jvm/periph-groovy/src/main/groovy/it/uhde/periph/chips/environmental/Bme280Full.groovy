package it.uhde.periph.chips.environmental

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport
import java.io.IOException

/**
 * BME280 — full driver. Extends {@link Bme280Minimal} with oversampling
 * control for all three TPH channels, IIR filter configuration, standby time
 * setting, altitude / sea-level pressure / dew-point computation, status
 * polling, chip ID read-back, and soft reset.
 *
 * <h2>Oversampling constants</h2>
 * {@link #OSRS_SKIP}, {@link #OSRS_X1}, {@link #OSRS_X2}, {@link #OSRS_X4},
 * {@link #OSRS_X8}, {@link #OSRS_X16}
 *
 * <h2>Mode constants</h2>
 * {@link #MODE_SLEEP}, {@link #MODE_FORCED}, {@link #MODE_NORMAL}
 *
 * <h2>Filter constants</h2>
 * {@link #FILTER_OFF}, {@link #FILTER_2}, {@link #FILTER_4},
 * {@link #FILTER_8}, {@link #FILTER_16}
 *
 * <h2>Standby time constants</h2>
 * {@link #T_SB_0_5_MS} … {@link #T_SB_1000_MS}, {@link #T_SB_10_MS},
 * {@link #T_SB_20_MS} — note that codes 6 and 7 mean <b>10 ms / 20 ms</b> on
 * the BME280, not 2000 ms / 4000 ms as on the BMP280.
 *
 * <h2>Status flags</h2>
 * {@link #STATUS_MEASURING}, {@link #STATUS_IM_UPDATE}
 */
@CompileStatic
class Bme280Full extends Bme280Minimal {

    static final int OSRS_SKIP = 0
    static final int OSRS_X1   = 1
    static final int OSRS_X2   = 2
    static final int OSRS_X4   = 3
    static final int OSRS_X8   = 4
    static final int OSRS_X16  = 5

    static final int MODE_SLEEP  = 0
    static final int MODE_FORCED = 1
    static final int MODE_NORMAL = 3

    static final int FILTER_OFF = 0
    static final int FILTER_2   = 1
    static final int FILTER_4   = 2
    static final int FILTER_8   = 3
    static final int FILTER_16  = 4

    static final int T_SB_0_5_MS   = 0
    static final int T_SB_62_5_MS  = 1
    static final int T_SB_125_MS   = 2
    static final int T_SB_250_MS   = 3
    static final int T_SB_500_MS   = 4
    static final int T_SB_1000_MS  = 5
    static final int T_SB_10_MS    = 6
    static final int T_SB_20_MS    = 7

    static final int STATUS_MEASURING  = 0x08
    static final int STATUS_IM_UPDATE  = 0x01

    private static final double DEFAULT_SEA_LEVEL_HPA = 1013.25
    private static final double MAGNUS_A = 17.27
    private static final double MAGNUS_B = 237.7

    Bme280Full(Transport transport) throws IOException {
        super(transport)
    }

    Bme280Full(Transport transport, int addr) throws IOException {
        super(transport, addr)
    }

    void configure(int osrsT, int osrsP, int osrsH, int mode, int filter, int tSb) throws IOException {
        ctrlHum  = osrsH & 0x07
        config   = ((tSb & 0x07) << 5) | ((filter & 0x07) << 2)
        ctrlMeas = ((osrsT & 0x07) << 5) | ((osrsP & 0x07) << 2) | (mode & 0x03)
        transport.write(new byte[]{(byte) REG_CTRL_HUM, (byte) ctrlHum})
        transport.write(new byte[]{(byte) REG_CONFIG, (byte) config})
        transport.write(new byte[]{(byte) REG_CTRL_MEAS, (byte) ctrlMeas})
    }

    void setOversampling(int osrsT, int osrsP, int osrsH) throws IOException {
        ctrlHum  = osrsH & 0x07
        ctrlMeas = ((osrsT & 0x07) << 5) | ((osrsP & 0x07) << 2) | (ctrlMeas & 0x03)
        transport.write(new byte[]{(byte) REG_CTRL_HUM, (byte) ctrlHum})
        transport.write(new byte[]{(byte) REG_CTRL_MEAS, (byte) ctrlMeas})
    }

    void setMode(int mode) throws IOException {
        ctrlMeas = (ctrlMeas & 0xFC) | (mode & 0x03)
        transport.write(new byte[]{(byte) REG_CTRL_MEAS, (byte) ctrlMeas})
    }

    void setFilter(int coeff) throws IOException {
        config = (config & 0xE3) | ((coeff & 0x07) << 2)
        transport.write(new byte[]{(byte) REG_CONFIG, (byte) config})
    }

    void setStandby(int tSb) throws IOException {
        config = (config & 0x1F) | ((tSb & 0x07) << 5)
        transport.write(new byte[]{(byte) REG_CONFIG, (byte) config})
    }

    int status() throws IOException {
        byte[] b = transport.writeRead(new byte[]{(byte) REG_STATUS}, 1)
        return b[0] & 0xFF
    }

    double altitude() throws IOException {
        return altitude(DEFAULT_SEA_LEVEL_HPA)
    }

    double altitude(double seaLevelHpa) throws IOException {
        double p = pressure()
        return 44330.0 * (1.0 - Math.pow(p / seaLevelHpa, 1.0 / 5.255))
    }

    double seaLevelPressure(double altitudeM) throws IOException {
        double p = pressure()
        return p / Math.pow(1.0 - altitudeM / 44330.0, 5.255)
    }

    double dewPoint() throws IOException {
        double t = temperature()
        double h = humidity()
        if (h <= 0.0) return Double.NEGATIVE_INFINITY
        double alpha = (MAGNUS_A * t) / (MAGNUS_B + t) + Math.log(h / 100.0)
        return (MAGNUS_B * alpha) / (MAGNUS_A - alpha)
    }

    int chipId() throws IOException {
        byte[] b = transport.writeRead(new byte[]{(byte) REG_ID}, 1)
        return b[0] & 0xFF
    }

    void reset() throws IOException {
        transport.write(new byte[]{(byte) REG_RESET, (byte) RESET_CMD})
        try { Thread.sleep(2) } catch (InterruptedException e) { Thread.currentThread().interrupt() }
        readCalibration()
        transport.write(new byte[]{(byte) REG_CTRL_HUM, (byte) ctrlHum})
        transport.write(new byte[]{(byte) REG_CONFIG, (byte) config})
        transport.write(new byte[]{(byte) REG_CTRL_MEAS, (byte) ctrlMeas})
    }
}
