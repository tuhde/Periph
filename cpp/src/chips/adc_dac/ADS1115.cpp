#include "ADS1115.h"

namespace {
const uint16_t RATES[8] = { 8, 16, 32, 64, 128, 250, 475, 860 };
}  // namespace

int16_t ADS1115Minimal::readRaw(uint8_t channel) {
    if (channel > 3) channel = 3;
    return _convert(4 + channel);
}

float ADS1115Minimal::readVoltage(uint8_t channel) {
    return readRaw(channel) * 0.0000625f;
}

float ADS1115Full::readVoltage(uint8_t channel) {
    return readRaw(channel) * _lsb();
}

int16_t ADS1115Full::readDifferentialRaw(uint8_t pair) {
    if (pair > 3) pair = 3;
    return _convert(pair);
}

float ADS1115Full::readDifferential(uint8_t pair) {
    return readDifferentialRaw(pair) * _lsb();
}

uint16_t ADS1115Full::dataRateSps() const {
    return RATES[_dr];
}
