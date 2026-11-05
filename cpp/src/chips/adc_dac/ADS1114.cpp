#include "ADS1114.h"

namespace {
const uint16_t RATES[8] = { 8, 16, 32, 64, 128, 250, 475, 860 };
}  // namespace

int16_t ADS1114Minimal::readRaw() {
    return _convert(0);
}

float ADS1114Minimal::readVoltage() {
    return readRaw() * 0.0000625f;
}

float ADS1114Full::readVoltage() {
    return readRaw() * _lsb();
}

uint16_t ADS1114Full::dataRateSps() const {
    return RATES[_dr];
}
