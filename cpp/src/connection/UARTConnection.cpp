#include <string.h>
#include "UARTConnection.h"

void UARTConnection::_write(const uint8_t* data, size_t len) {
    if (_de_pin >= 0)
        digitalWrite(_de_pin, HIGH);
    size_t sent = _serial.write(data, len);
    _serial.flush();
    if (_de_pin >= 0)
        digitalWrite(_de_pin, LOW);
    _setOk(sent == len);
}

void UARTConnection::_read(uint8_t* buf, size_t len) {
    size_t got = _serial.readBytes(buf, len);   // stops at Stream::setTimeout() (default 1 s)
    if (got < len) memset(buf + got, 0, len - got);   // never leave stale bytes behind
    _setOk(got == len);
}

void UARTConnection::_write_read(const uint8_t* data, size_t data_len,
                                  uint8_t* buf, size_t buf_len) {
    _write(data, data_len);
    bool wrote = ok();
    _read(buf, buf_len);
    if (!wrote) _setOk(false);
}
