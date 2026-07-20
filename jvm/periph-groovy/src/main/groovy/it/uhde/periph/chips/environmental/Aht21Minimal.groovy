package it.uhde.periph.chips.environmental

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport

/**
 * AHT21 — temperature and humidity sensor with I²C interface (minimal driver).
 *
 * <p>Measures temperature (-50 to 150 °C) and relative humidity (0 to 100 %RH).
 * Handles power-on initialization, calibration check, and measurement
 * triggering automatically.
 *
 * <p>Default I²C address: 0x38 (fixed — not configurable).
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>Measurement triggered on every read() call (no continuous mode)</li>
 *   <li>80 ms fixed wait after trigger (no busy-polling)</li>
 *   <li>No CRC verification (reduces complexity; CRC check is Full-only)</li>
 * </ul>
 */
@CompileStatic
class Aht21Minimal {

    protected static final byte[] CMD_TRIGGER    = [(byte) 0xAC, (byte) 0x33, (byte) 0x00] as byte[]
    protected static final byte[] CMD_SOFT_RESET = [(byte) 0xBA] as byte[]
    protected static final byte[] CMD_CAL_INIT_1 = [(byte) 0x1B, (byte) 0x00, (byte) 0x00] as byte[]
    protected static final byte[] CMD_CAL_INIT_2 = [(byte) 0x1C, (byte) 0x00, (byte) 0x00] as byte[]
    protected static final byte[] CMD_CAL_INIT_3 = [(byte) 0x1E, (byte) 0x00, (byte) 0x00] as byte[]

    protected static final int STATUS_BUSY = 0x80
    protected static final int STATUS_CAL  = 0x08

    protected final Transport transport

    /**
     * Construct the driver and perform power-on initialization.
     *
     * <p>Waits 100 ms, checks calibration status, sends soft reset if needed,
     * and writes calibration init commands if still uncalibrated.
     *
     * @param transport I²C transport bound to the AHT21 device address (0x38)
     */
    Aht21Minimal(Transport transport) {
        this.transport = transport
        Thread.sleep(100)
        int status = readStatus()
        if ((status & 0x18) != 0x18) {
            transport.write(CMD_SOFT_RESET)
            Thread.sleep(20)
            status = readStatus()
            if ((status & 0x18) != 0x18) {
                transport.write(CMD_CAL_INIT_1)
                Thread.sleep(10)
                transport.write(CMD_CAL_INIT_2)
                Thread.sleep(10)
                transport.write(CMD_CAL_INIT_3)
                Thread.sleep(10)
            }
        }
    }

    /**
     * Trigger a measurement and return temperature and humidity.
     *
     * <p>Sends the trigger command, waits 80 ms, reads 6 bytes, and decodes
     * the raw 20-bit values into physical units.
     *
     * @return double array: [0] = temperature in °C, [1] = humidity in %RH
     */
    double[] read() {
        transport.write(CMD_TRIGGER)
        Thread.sleep(80)
        byte[] data = transport.read(6)
        decode(data)
    }

    protected int readStatus() {
        byte[] buf = transport.read(1)
        buf[0] & 0xFF
    }

    protected double[] decode(byte[] buf) {
        int rawRh = ((buf[1] & 0xFF) << 12) | ((buf[2] & 0xFF) << 4) | ((buf[3] & 0xFF) >> 4)
        int rawT  = ((buf[3] & 0x0F) << 16) | ((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF)
        double humidityPct  = (rawRh / 1048576.0d) * 100.0d
        double temperatureC = (rawT  / 1048576.0d) * 200.0d - 50.0d
        [temperatureC, humidityPct] as double[]
    }
}
