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
 * The default implementation below (write_read/write of a leading, big-endian
 * register-address of @c regBytes bytes) matches I2C/SMBus behavior as-is;
 * SPIConnection overrides both to build its single-byte command byte instead
 * (see specs/feature_register_access_design.md §11.2 for why SPI stays
 * single-byte).
 */
class RegisterConnection : public Connection {
public:
    /** @brief Construct with optional INT/EN pins and register address width.
     *  @param intPin    Optional InputPin for INT-line delivery.
     *  @param enPin     Optional OutputPin for hardware enable/power control.
     *  @param regBytes  Register address width in bytes, big-endian (default 1, max 4).
     */
    explicit RegisterConnection(InputPin* intPin = nullptr, OutputPin* enPin = nullptr,
                                 uint8_t regBytes = 1)
        : Connection(intPin, enPin), _regBytes(regBytes) {}

    /** @brief Read @p len bytes starting at register @p reg.
     *  @param reg Register address.
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    virtual void read(uint32_t reg, uint8_t* buf, size_t len) {
        uint8_t addr[4];
        for (uint8_t i = 0; i < _regBytes; i++)
            addr[i] = (reg >> (8 * (_regBytes - 1 - i))) & 0xFF;
        write_read(addr, _regBytes, buf, len);
    }

    /** @brief Write @p len bytes of @p data to register @p reg.
     *  @param reg  Register address.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes in @p data.
     */
    virtual void write(uint32_t reg, const uint8_t* data, size_t len) {
        uint8_t payload[20];  // up to 4 addr bytes + up to 16 data bytes
        for (uint8_t i = 0; i < _regBytes; i++)
            payload[i] = (reg >> (8 * (_regBytes - 1 - i))) & 0xFF;
        memcpy(payload + _regBytes, data, len);
        Connection::write(payload, _regBytes + len);
    }

private:
    uint8_t _regBytes;
};
