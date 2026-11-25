#include "ADS1113.h"

namespace {
const uint16_t RATES[8] = { 8, 16, 32, 64, 128, 250, 475, 860 };
}  // namespace

int16_t ADS1113Minimal::readRaw() {
    return _convert(0);
}

float ADS1113Minimal::readVoltage() {
    return readRaw() * 0.0000625f;
}

float ADS1113Full::readVoltage() {
    return readRaw() * _lsb();
}

uint16_t ADS1113Full::dataRateSps() const {
    return RATES[_dr];
}
