#include "DHTxxTransport.h"

DHTxxTransport::DHTxxTransport(uint8_t data_pin) : _pin(data_pin) {
    pinMode(_pin, INPUT);
}

void DHTxxTransport::_drive_low() {
    pinMode(_pin, OUTPUT);
    digitalWrite(_pin, LOW);
}

void DHTxxTransport::_release_bus() {
    pinMode(_pin, INPUT);
}

int32_t DHTxxTransport::_measure_pulse(uint8_t level, uint32_t timeout_us) {
    uint32_t start = micros();
    while (digitalRead(_pin) != level) {
        if (micros() - start > timeout_us) return -1;
    }
    uint32_t pulse_start = micros();
    while (digitalRead(_pin) == level) {
        if (micros() - pulse_start > timeout_us) return -1;
    }
    return (int32_t)(micros() - pulse_start);
}

bool DHTxxTransport::read(uint8_t* out) {
    _drive_low();
    delay(_START_LOW_MS);
    _release_bus();

    int32_t elapsed = _measure_pulse(LOW, _RESPONSE_TIMEOUT_US);
    if (elapsed < 0) return false;
    elapsed = _measure_pulse(HIGH, _RESPONSE_TIMEOUT_US);
    if (elapsed < 0) return false;

    for (uint8_t byte_idx = 0; byte_idx < 5; byte_idx++) {
        uint8_t byte = 0;
        for (uint8_t bit_idx = 0; bit_idx < 8; bit_idx++) {
            elapsed = _measure_pulse(LOW, _BIT_TIMEOUT_US);
            if (elapsed < 0) return false;
            elapsed = _measure_pulse(HIGH, _BIT_TIMEOUT_US);
            if (elapsed < 0) return false;
            byte = (byte << 1) | (elapsed > (int32_t)_BIT_THRESHOLD_US ? 1 : 0);
        }
        out[byte_idx] = byte;
    }
    return true;
}

void DHTxxTransport::close() {
    pinMode(_pin, INPUT);
}
