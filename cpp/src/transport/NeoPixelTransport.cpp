#include "NeoPixelTransport.h"

void NeoPixelTransport::write(const uint8_t* data, size_t len) {
    _bus.beginTransaction(SPISettings(2400000, MSBFIRST, SPI_MODE0));
    _encode_write(data, len);
    _bus.endTransaction();
}

void NeoPixelTransport::_encode_write(const uint8_t* data, size_t len) {
    for (size_t i = 0; i < len; i++) {
        uint8_t byte = data[i];
        for (int bit = 7; bit >= 0; bit--) {
            uint8_t pattern = ((byte >> bit) & 1) ? 0b110 : 0b100;
            _bus.transfer(pattern);
        }
    }
    for (size_t i = 0; i < 16; i++) {
        _bus.transfer(0x00);
    }
}