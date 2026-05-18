package it.uhde.periph.transport;

import com.pi4j.context.Context;
import com.pi4j.io.i2c.I2C;

import java.io.IOException;

/**
 * I²C transport backed by Pi4J (Linux /dev/i2c-N via the linuxfs plugin).
 *
 * <p>One instance represents one device address on one bus. Create a separate
 * instance for every distinct address, including the I²C General Call address
 * (0x00) when needed.
 */
public final class I2CTransport implements Transport {

    private final I2C device;

    /**
     * Open an I²C device.
     *
     * @param pi4j    active Pi4J context (caller retains ownership)
     * @param bus     I²C bus number (e.g. 1 for /dev/i2c-1)
     * @param address 7-bit device address
     */
    public I2CTransport(Context pi4j, int bus, int address) {
        var config = I2C.newConfigBuilder(pi4j)
                .id("i2c-" + bus + "-" + Integer.toHexString(address))
                .bus(bus)
                .device(address)
                .build();
        this.device = pi4j.create(config);
    }

    @Override
    public void write(byte[] data) throws IOException {
        try {
            device.write(data, 0, data.length);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public byte[] read(int n) throws IOException {
        try {
            byte[] buf = new byte[n];
            device.read(buf, 0, n);
            return buf;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /**
     * Writes then reads as two separate bus transactions (stop + start between them).
     * Pi4J's linuxfs plugin does not expose a true repeated-start for raw I²C;
     * for chips that require it, use a platform-specific transport instead.
     */
    @Override
    public byte[] writeRead(byte[] data, int n) throws IOException {
        write(data);
        return read(n);
    }

    @Override
    public void close() throws IOException {
        try {
            device.close();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
