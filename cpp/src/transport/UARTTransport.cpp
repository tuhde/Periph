#include "UARTTransport.h"

void UARTTransport::write(const uint8_t* data, size_t len) {
    if (_de_pin >= 0)
        digitalWrite(_de_pin, HIGH);
    _serial.write(data, len);
    _serial.flush();
    if (_de_pin >= 0)
        digitalWrite(_de_pin, LOW);
}

void UARTTransport::read(uint8_t* buf, size_t len) {
    _serial.readBytes(buf, len);
}

void UARTTransport::write_read(const uint8_t* data, size_t data_len,
                                uint8_t* buf, size_t buf_len) {
    write(data, data_len);
    read(buf, buf_len);
}
