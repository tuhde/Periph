#include "HX711Transport.h"

HX711Transport::HX711Transport(int dout_pin, int pd_sck_pin)
    : _dout(dout_pin), _sck(pd_sck_pin)
{
    pinMode(_dout, INPUT);
    pinMode(_sck,  OUTPUT);
    digitalWrite(_sck, LOW);
}

bool HX711Transport::is_ready() {
    return digitalRead(_dout) == LOW;
}

int32_t HX711Transport::read_raw(uint8_t num_pulses) {
    if (num_pulses != 25 && num_pulses != 26 && num_pulses != 27)
        return INT32_MIN;
    while (digitalRead(_dout) != LOW) {}
    uint32_t raw = 0;
    for (uint8_t i = 0; i < num_pulses; i++) {
        digitalWrite(_sck, HIGH);
        raw = (raw << 1) | (uint32_t)digitalRead(_dout);
        digitalWrite(_sck, LOW);
    }
    raw >>= num_pulses - 24;
    if (raw & 0x800000u)
        return static_cast<int32_t>(raw) - 0x1000000;
    return static_cast<int32_t>(raw);
}

void HX711Transport::power_down() {
    digitalWrite(_sck, HIGH);
    delayMicroseconds(65);
}

void HX711Transport::power_up() {
    digitalWrite(_sck, LOW);
}
