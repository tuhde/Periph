#pragma once
#ifdef __linux__
#include <stdint.h>
#include <stddef.h>
#include "RegisterConnection.h"

/** @brief SPI connection for Linux (wraps spidev, uses /dev/spidevBUS.DEVICE).
 *
 * CS is managed by the kernel spidev driver.
 *
 * @param bus_num      SPI bus number.
 * @param device_num   Chip-select line on the bus.
 * @param mode         SPI mode 0–3 (CPOL/CPHA); default 0.
 * @param max_speed_hz Clock frequency in Hz; default 1 000 000.
 * @param readBit      Bit ORed into the command byte for a read; 0 if the
 *                     chip has no such bit. Default 0x80.
 * @param multiByteBit Bit ORed in for multi-byte (burst) transfers when
 *                     len > 1; 0 if the chip has no such bit and always
 *                     auto-increments. Default 0.
 * @param intPin       Optional InputPin for INT-line delivery.
 * @param enPin        Optional OutputPin for hardware enable/power control.
 * @param enActiveHigh True if the EN pin is active-high (default); false for active-low.
 */
class SPIConnectionLinux : public RegisterConnection {
public:
    // See RegisterConnection.h's using declaration for why this is
    // needed: overriding read()/write() below would otherwise hide
    // RegisterConnection's (and, transitively, Connection's) other
    // read()/write() overloads.
    using RegisterConnection::read;
    using RegisterConnection::write;

    SPIConnectionLinux(int bus_num, int device_num,
                       uint8_t mode = 0, uint32_t max_speed_hz = 1000000,
                       uint8_t readBit = 0x80, uint8_t multiByteBit = 0,
                       InputPin* intPin = nullptr, OutputPin* enPin = nullptr, bool enActiveHigh = true);
    ~SPIConnectionLinux();

    /** @brief Read @p len bytes starting at register @p reg, building the SPI command byte. */
    void read(uint32_t reg, uint8_t* buf, size_t len) override;

    /** @brief Write @p len bytes of @p data to register @p reg, building the SPI command byte. */
    void write(uint32_t reg, const uint8_t* data, size_t len) override;

protected:
    /** @brief Send bytes to the device.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void _write(const uint8_t* data, size_t len) override;

    /** @brief Read bytes from the device.
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void _read(uint8_t* buf, size_t len) override;

    /** @brief Write then read in a single SPI_IOC_MESSAGE(2) ioctl (CS held).
     *
     *  Two transfers submitted in one ioctl call; CS stays asserted between them.
     *
     *  @param data     Command bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    void _write_read(const uint8_t* data, size_t data_len,
                     uint8_t* buf, size_t buf_len) override;

private:
    int      _fd;
    uint32_t _speed_hz;
    uint8_t  _readBit;
    uint8_t  _multiByteBit;   // 0 = chip has no such bit
};
#endif // __linux__
