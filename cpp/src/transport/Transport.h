#pragma once
#include <stdint.h>
#include <stddef.h>

/** @brief Abstract transport interface shared by all chip drivers.
 *
 * A transport instance represents one device on the bus. Implementations
 * wrap a platform-specific bus (Wire, SPIClass, /dev/i2c-N, etc.) and a
 * device address or CS pin.
 */
class Transport {
public:
    virtual ~Transport() = default;

    /** @brief Send bytes to the device.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    virtual void write(const uint8_t* data, size_t len) = 0;

    /** @brief Read bytes from the device.
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    virtual void read(uint8_t* buf, size_t len) = 0;

    /** @brief Write then read without releasing the bus between phases.
     *
     *  Sends @p data_len bytes, then reads @p buf_len bytes within one
     *  continuous bus transaction — the bus is not released between the
     *  write and read phases.
     *
     *  @param data     Command/address bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    virtual void write_read(const uint8_t* data, size_t data_len,
                            uint8_t* buf, size_t buf_len) = 0;
};
