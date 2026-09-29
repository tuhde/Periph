package it.uhde.periph.connection;

import java.io.IOException;

/**
 * Connection with register-addressed read/write, for I2C/SMBus/SPI-style buses.
 *
 * <p>Sits between {@link Connection} and the register-capable concrete classes
 * ({@link I2CConnection}, {@link SMBusConnection}, {@link SPIConnection});
 * every other concrete connection ({@link UARTConnection}, {@link DHTxxConnection},
 * {@link NeoPixelConnection}, {@link SiPoConnection}) is unaffected and continues
 * to implement {@link Connection} only.
 *
 * <p>Declared as an interface with default methods — rather than an abstract
 * base class — so it applies uniformly to concrete classes that extend
 * {@link AbstractConnection} ({@link I2CConnection}, {@link SMBusConnection})
 * and to {@link SPIConnection}, which implements {@link Connection} directly.
 * The default implementation below ({@link #read(int, int)} /
 * {@link #write(int, byte[])} via {@code writeRead}/{@code write}) matches
 * I2C/SMBus behavior as-is; {@link SPIConnection} overrides both to build its
 * command byte first.
 */
public interface RegisterConnection extends Connection {

    /**
     * Read {@code length} bytes starting at register {@code reg}.
     *
     * @param reg    register address
     * @param length number of bytes to read
     * @return bytes received from the device
     * @throws IOException on bus error or no ACK
     */
    default byte[] read(int reg, int length) throws IOException {
        return writeRead(new byte[]{(byte) reg}, length);
    }

    /**
     * Write {@code data} to register {@code reg}.
     *
     * @param reg  register address
     * @param data bytes to write
     * @throws IOException on bus error or no ACK
     */
    default void write(int reg, byte[] data) throws IOException {
        byte[] payload = new byte[data.length + 1];
        payload[0] = (byte) reg;
        System.arraycopy(data, 0, payload, 1, data.length);
        write(payload);
    }
}
