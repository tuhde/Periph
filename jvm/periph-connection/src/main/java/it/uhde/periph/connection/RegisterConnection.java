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
 * I2C/SMBus behavior as-is, building a big-endian address of {@link #regBytes()}
 * bytes; {@link SPIConnection} overrides both to build its own single-byte
 * command byte instead (see specs/feature_register_access_design.md §11.2 for
 * why SPI stays single-byte).
 */
public interface RegisterConnection extends Connection {

    /**
     * Register address width in bytes, big-endian. Default 1; {@link I2CConnection}
     * and {@link SMBusConnection} override this to return a per-instance value set
     * at construction.
     */
    default int regBytes() { return 1; }

    /**
     * Read {@code length} bytes starting at register {@code reg}.
     *
     * @param reg    register address
     * @param length number of bytes to read
     * @return bytes received from the device
     * @throws IOException on bus error or no ACK
     */
    default byte[] read(int reg, int length) throws IOException {
        return writeRead(regAddrBytes(reg), length);
    }

    /**
     * Write {@code data} to register {@code reg}.
     *
     * @param reg  register address
     * @param data bytes to write
     * @throws IOException on bus error or no ACK
     */
    default void write(int reg, byte[] data) throws IOException {
        byte[] addr = regAddrBytes(reg);
        byte[] payload = new byte[addr.length + data.length];
        System.arraycopy(addr, 0, payload, 0, addr.length);
        System.arraycopy(data, 0, payload, addr.length, data.length);
        write(payload);
    }

    private byte[] regAddrBytes(int reg) {
        int n = regBytes();
        byte[] addr = new byte[n];
        for (int i = 0; i < n; i++) addr[i] = (byte) (reg >>> (8 * (n - 1 - i)));
        return addr;
    }
}
