#pragma once
#include <SPI.h>
#include "Transport.h"

class SPITransport : public Transport {
public:
    SPITransport(SPIClass& bus, uint8_t cs_pin, SPISettings settings)
        : _bus(bus), _cs_pin(cs_pin), _settings(settings) {
        pinMode(_cs_pin, OUTPUT);
        digitalWrite(_cs_pin, HIGH);
    }

    void write(const uint8_t* data, size_t len) override;
    void read(uint8_t* buf, size_t len) override;
    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override;

private:
    SPIClass&   _bus;
    uint8_t     _cs_pin;
    SPISettings _settings;
};
