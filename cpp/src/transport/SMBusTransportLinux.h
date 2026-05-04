#pragma once
#ifdef __linux__
#include <stdint.h>
#include <stddef.h>
#include "Transport.h"

// SMBus transport for Linux (/dev/i2c-N).
// Implements PEC (CRC-8) in software, matching SMBusTransport (Arduino).
class SMBusTransportLinux : public Transport {
public:
    SMBusTransportLinux(int bus, uint8_t addr, bool pec = false);
    ~SMBusTransportLinux();

    void write(const uint8_t* data, size_t len) override;
    void read(uint8_t* buf, size_t len) override;
    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override;

    // Returns false if the last operation produced a PEC mismatch.
    bool valid() const { return _valid; }

private:
    int     _fd;
    uint8_t _addr;
    bool    _pec;
    bool    _valid = true;

    static uint8_t _crc8(const uint8_t* data, size_t len, uint8_t crc = 0);
};
#endif // __linux__
