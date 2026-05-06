#pragma once
#ifdef __linux__
#include <stdint.h>
#include <stddef.h>
#include "Transport.h"

class SPITransportLinux : public Transport {
public:
    SPITransportLinux(int bus_num, int device_num,
                      uint8_t mode = 0, uint32_t max_speed_hz = 1000000);
    ~SPITransportLinux();

    void write(const uint8_t* data, size_t len) override;
    void read(uint8_t* buf, size_t len) override;
    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override;

private:
    int      _fd;
    uint32_t _speed_hz;
};
#endif // __linux__
