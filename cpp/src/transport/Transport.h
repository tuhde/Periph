#pragma once
#include <stdint.h>
#include <stddef.h>

class Transport {
public:
    virtual ~Transport() = default;
    virtual void write(const uint8_t* data, size_t len) = 0;
    virtual void read(uint8_t* buf, size_t len) = 0;
    // Write then read without releasing bus (repeated start).
    virtual void write_read(const uint8_t* data, size_t data_len,
                            uint8_t* buf, size_t buf_len) = 0;
};
