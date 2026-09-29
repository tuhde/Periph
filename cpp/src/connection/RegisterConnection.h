#pragma once
#include "Connection.h"

/** @brief Connection with register-addressed read/write, for I2C/SMBus/SPI-style buses.
 *
 * Sits between Connection and the register-capable concrete classes
 * (I2CConnection, SMBusConnection, SPIConnection); every other concrete
 * connection (UARTConnection, HX711Connection, NeoPixelConnection,
 * SiPoConnection, DHTxxConnection) is unaffected and continues to extend
 * Connection only.
 *
 * The default implementation below (write_read/write of a leading
 * register-address byte) matches I2C/SMBus behavior as-is; SPIConnection
 * overrides both to build its command byte first.
 */
class RegisterConnection : public Connection {
public:
    using Connection::Connection;

    /** @brief Read @p len bytes starting at register @p reg.
     *  @param reg Register address.
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    virtual void read(uint8_t reg, uint8_t* buf, size_t len) {
        uint8_t r = reg;
        write_read(&r, 1, buf, len);
    }

    /** @brief Write @p len bytes of @p data to register @p reg.
     *  @param reg  Register address.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes in @p data.
     */
    virtual void write(uint8_t reg, const uint8_t* data, size_t len) {
        uint8_t payload[17];
        payload[0] = reg;
        memcpy(payload + 1, data, len);
        Connection::write(payload, len + 1);
    }
};
