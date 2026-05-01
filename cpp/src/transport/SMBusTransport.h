#pragma once
#include <Wire.h>
#include "Transport.h"

class SMBusTransport : public Transport {
public:
    SMBusTransport(TwoWire& bus, uint8_t addr, bool pec = false);

    void write(const uint8_t* data, size_t len) override;
    void read(uint8_t* buf, size_t len) override;
    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override;

    // Returns false if the last operation produced a PEC mismatch.
    bool valid() const { return _valid; }

private:
    TwoWire& _bus;
    uint8_t  _addr;
    bool     _pec;
    bool     _valid = true;

    static uint8_t _crc8(const uint8_t* data, size_t len, uint8_t crc = 0);
};
