#pragma once
#ifdef __linux__
#include <stdint.h>
#include <stddef.h>
#include "Transport.h"

/** @brief I²C transport for Linux (opens /dev/i2c-N via ioctl).
 *
 * @param bus  Bus number; opens /dev/i2c-{bus}.
 * @param addr 7-bit device address (set via I2C_SLAVE ioctl).
 */
class I2CTransportLinux : public Transport {
public:
    I2CTransportLinux(int bus, uint8_t addr);
    ~I2CTransportLinux();

    /** @brief Send bytes to the device.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void write(const uint8_t* data, size_t len) override;

    /** @brief Read bytes from the device.
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void read(uint8_t* buf, size_t len) override;

    /** @brief Write then read in a single I2C_RDWR ioctl (repeated start).
     *
     *  Both messages are submitted together; the kernel issues a repeated START
     *  between them without releasing the bus.
     *
     *  @param data     Register/command bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override;

private:
    int     _fd;
    uint8_t _addr;
};
#endif // __linux__
