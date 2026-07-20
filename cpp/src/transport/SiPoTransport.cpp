#include "SiPoTransport.h"

SiPoTransport::SiPoTransport(SPIClass& spi, int rck_pin, int srclr_pin, int g_pin,
                             SPISettings settings)
    : _spi(&spi), _settings(settings), _ser_in(-1), _srck(-1),
      _rck(rck_pin), _srclr(srclr_pin), _g(g_pin)
{
    pinMode(_rck, OUTPUT);
    digitalWrite(_rck, LOW);
    if (_srclr >= 0) {
        pinMode(_srclr, OUTPUT);
        digitalWrite(_srclr, HIGH);
    }
    if (_g >= 0) {
        pinMode(_g, OUTPUT);
        digitalWrite(_g, LOW);
    }
}

SiPoTransport::SiPoTransport(int ser_in_pin, int srck_pin, int rck_pin,
                             int srclr_pin, int g_pin)
    : _spi(nullptr), _settings(1000000, MSBFIRST, SPI_MODE0),
      _ser_in(ser_in_pin), _srck(srck_pin),
      _rck(rck_pin), _srclr(srclr_pin), _g(g_pin)
{
    pinMode(_ser_in, OUTPUT);
    pinMode(_srck, OUTPUT);
    digitalWrite(_srck, LOW);
    pinMode(_rck, OUTPUT);
    digitalWrite(_rck, LOW);
    if (_srclr >= 0) {
        pinMode(_srclr, OUTPUT);
        digitalWrite(_srclr, HIGH);
    }
    if (_g >= 0) {
        pinMode(_g, OUTPUT);
        digitalWrite(_g, LOW);
    }
}

void SiPoTransport::write(const uint8_t* data, size_t len) {
    if (_spi != nullptr) {
        _spi->beginTransaction(_settings);
        for (size_t i = 0; i < len; i++)
            _spi->transfer(data[i]);
        _spi->endTransaction();
    } else {
        for (size_t i = 0; i < len; i++) {
            for (int bit = 7; bit >= 0; bit--) {
                digitalWrite(_ser_in, (data[i] >> bit) & 1);
                digitalWrite(_srck, HIGH);
                digitalWrite(_srck, LOW);
            }
        }
    }
    _latch();
}

void SiPoTransport::_latch() {
    digitalWrite(_rck, HIGH);
    digitalWrite(_rck, LOW);
}

bool SiPoTransport::clear() {
    if (_srclr < 0) return false;
    digitalWrite(_srclr, LOW);
    digitalWrite(_srclr, HIGH);
    return true;
}

bool SiPoTransport::set_output_enable(bool enabled) {
    if (_g < 0) return false;
    digitalWrite(_g, enabled ? LOW : HIGH);
    return true;
}
