package it.uhde.periph.chips.gas;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * ENS160 digital multi-gas sensor — minimal interface.
 *
 * <p>Provides calibrated air quality readings (AQI, TVOC, eCO2) with no
 * configuration required beyond the transport. The sensor performs automatic
 * baseline correction and on-chip signal processing.
 *
 * <p>Default: STANDARD mode (gas sensing active), polling only, no external
 * T/RH compensation.
 */
public class Ens160Minimal {

    protected static final int REG_PART_ID       = 0x00;
    protected static final int REG_OPMODE        = 0x10;
    protected static final int REG_CONFIG        = 0x11;
    protected static final int REG_COMMAND       = 0x12;
    protected static final int REG_TEMP_IN       = 0x13;
    protected static final int REG_RH_IN         = 0x15;
    protected static final int REG_DEVICE_STATUS = 0x20;
    protected static final int REG_DATA_AQI      = 0x21;
    protected static final int REG_DATA_TVOC     = 0x22;
    protected static final int REG_DATA_ECO2     = 0x24;
    protected static final int REG_DATA_T        = 0x30;
    protected static final int REG_DATA_RH       = 0x32;
    protected static final int REG_GPR_READ      = 0x48;

    protected static final int OPMODE_DEEP_SLEEP = 0x00;
    protected static final int OPMODE_IDLE       = 0x01;
    protected static final int OPMODE_STANDARD   = 0x02;

    protected static final int PART_ID_EXPECTED  = 0x0160;

    protected final Transport transport;

    /**
     * Construct the driver, verify PART_ID, and start STANDARD mode.
     *
     * @param transport I²C or SPI transport bound to the device.
     * @throws IOException on I²C error or wrong PART_ID.
     */
    public Ens160Minimal(Transport transport) throws IOException {
        this.transport = transport;
        writeReg(REG_OPMODE, OPMODE_IDLE);
        try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        int partId = readRegLE16(REG_PART_ID);
        if (partId != PART_ID_EXPECTED) {
            throw new IOException("ENS160 not found: expected PART_ID 0x0160, got 0x" + Integer.toHexString(partId));
        }
        writeReg(REG_OPMODE, OPMODE_STANDARD);
    }

    protected void writeReg(int reg, int value) throws IOException {
        transport.write(new byte[]{(byte) reg, (byte) value});
    }

    protected void writeRegLE16(int reg, int value) throws IOException {
        transport.write(new byte[]{(byte) reg, (byte) (value & 0xFF), (byte) ((value >> 8) & 0xFF)});
    }

    protected byte[] readReg(int reg, int n) throws IOException {
        return transport.writeRead(new byte[]{(byte) reg}, n);
    }

    protected int readRegLE16(int reg) throws IOException {
        byte[] data = readReg(reg, 2);
        return (data[0] & 0xFF) | ((data[1] & 0xFF) << 8);
    }

    protected int readDeviceStatus() throws IOException {
        byte[] data = readReg(REG_DEVICE_STATUS, 1);
        return data[0] & 0xFF;
    }

    protected int waitForNewData(int timeoutMs) throws IOException {
        long start = System.currentTimeMillis();
        while (true) {
            int status = readDeviceStatus();
            if ((status & 0x02) != 0) {
                return status;
            }
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw new IOException("ENS160: NEWDAT not set within " + timeoutMs + " ms");
            }
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /**
     * Read the VALIDITY_FLAG from DEVICE_STATUS.
     *
     * @return Validity flag (0=OK, 1=Warm-up, 2=Initial Start-up, 3=No valid output).
     * @throws IOException on I²C error.
     */
    public int status() throws IOException {
        int status = readDeviceStatus();
        return (status >> 2) & 0x03;
    }

    /**
     * Read calibrated air quality values.
     *
     * <p>Polls until NEWDAT is set, then checks VALIDITY_FLAG. Only returns
     * data when validity is 0 (OK). Reads AQI, TVOC, and eCO2 in a single
     * burst to ensure consistency.
     *
     * @return array: [aqi (1–5), tvocPpb, eco2Ppm].
     * @throws IOException on I²C error or invalid data.
     */
    public double[] readAirQuality() throws IOException {
        int status = waitForNewData(5000);
        int validity = (status >> 2) & 0x03;
        if (validity != 0) {
            throw new IOException("ENS160: data not valid (VALIDITY_FLAG=" + validity + ")");
        }
        byte[] data = readReg(REG_DATA_AQI, 5);
        int aqi = data[0] & 0x07;
        int tvocPpb = (data[1] & 0xFF) | ((data[2] & 0xFF) << 8);
        int eco2Ppm = (data[3] & 0xFF) | ((data[4] & 0xFF) << 8);
        return new double[]{aqi, tvocPpb, eco2Ppm};
    }
}
