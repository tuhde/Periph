#pragma once
#ifdef __linux__
#include <stdint.h>
#include <stddef.h>
#include "Connection.h"

/** @brief SMBus connection for Linux (/dev/i2c-N with software PEC).
 *
 * Mirrors SMBusConnection (Arduino): validates the address range and
 * implements CRC-8 in software for write, read, and write_read operations.
 *
 * @param bus    Bus number; opens /dev/i2c-{bus}.
 * @param addr   7-bit device address (0x08–0x77).
 * @param pec    Enable Packet Error Code (CRC-8) checking (default false).
 * @param intPin Optional InputPin for INT-line delivery.
 * @param enPin  Optional OutputPin for hardware enable/power control.
 */
class SMBusConnectionLinux : public Connection {
public:
    SMBusConnectionLinux(int bus, uint8_t addr, bool pec = false,
                         InputPin* intPin = nullptr, OutputPin* enPin = nullptr);
    ~SMBusConnectionLinux();

    /** @brief Returns false if the last read or write_read produced a PEC mismatch. */
    bool valid() const { return _valid; }

protected:
    /** @brief Send bytes to the device, appending a PEC byte if enabled. */
    void _write(const uint8_t* data, size_t len) override;

    /** @brief Read bytes from the device, verifying the PEC byte if enabled.
     *
     *  Reads len+1 bytes when PEC is enabled; the trailing byte is the CRC.
     *  Call valid() after to check whether PEC matched.
     */
    void _read(uint8_t* buf, size_t len) override;

    /** @brief Write then read via I2C_RDWR ioctl, with PEC on the read phase.
     *
     *  PEC covers the full transaction (write address + data + read address + data).
     *  Call valid() after to check whether PEC matched.
     */
    void _write_read(const uint8_t* data, size_t data_len,
                     uint8_t* buf, size_t buf_len) override;

private:
    int     _fd;
    uint8_t _addr;
    bool    _pec;
    bool    _valid = true;

    static uint8_t _crc8(const uint8_t* data, size_t len, uint8_t crc = 0);
};
#endif // __linux__
