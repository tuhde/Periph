#pragma once
#ifdef __linux__
#include <stdint.h>
#include <stddef.h>
#include "Transport.h"

/** @brief SPI transport for Linux (wraps spidev, uses /dev/spidevBUS.DEVICE).
 *
 * CS is managed by the kernel spidev driver.
 *
 * @param bus_num      SPI bus number.
 * @param device_num   Chip-select line on the bus.
 * @param mode         SPI mode 0–3 (CPOL/CPHA); default 0.
 * @param max_speed_hz Clock frequency in Hz; default 1 000 000.
 */
class SPITransportLinux : public Transport {
public:
    SPITransportLinux(int bus_num, int device_num,
                      uint8_t mode = 0, uint32_t max_speed_hz = 1000000);
    ~SPITransportLinux();

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

    /** @brief Write then read in a single SPI_IOC_MESSAGE(2) ioctl (CS held).
     *
     *  Two transfers submitted in one ioctl call; CS stays asserted between them.
     *
     *  @param data     Command bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override;

private:
    int      _fd;
    uint32_t _speed_hz;
};
#endif // __linux__
