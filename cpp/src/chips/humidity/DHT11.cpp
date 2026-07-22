#include "DHT11.h"
#include "../../transport/DHTxxTransport.h"
#include <math.h>

DHT11Minimal::DHT11Minimal(DHTxxTransport& transport) : _transport(transport) {}

void DHT11Minimal::_decode(const uint8_t* frame, float& temperature, float& humidity) {
    uint8_t hum_int  = frame[0];
    uint8_t hum_dec  = frame[1];
    uint8_t temp_int = frame[2];
    uint8_t temp_dec = frame[3];
    uint8_t checksum = frame[4];
    uint8_t expected = (uint8_t)((hum_int + hum_dec + temp_int + temp_dec) & 0xFF);
    if (expected != checksum) {
        _valid = false;
        temperature = NAN;
        humidity    = NAN;
        return;
    }
    humidity = hum_int + hum_dec / 10.0f;
    int sign = (temp_dec & 0x80) ? -1 : 1;
    uint8_t temp_dec_value = (uint8_t)(temp_dec & 0x7F);
    temperature = sign * (temp_int + temp_dec_value / 10.0f);
    _valid = true;
}

bool DHT11Minimal::read(float& temperature, float& humidity) {
    uint8_t frame[5];
    if (!_transport.read(frame)) {
        _valid = false;
        temperature = NAN;
        humidity    = NAN;
        return false;
    }
    _decode(frame, temperature, humidity);
    return _valid;
}

DHT11Full::DHT11Full(DHTxxTransport& transport, uint8_t max_retries)
    : DHT11Minimal(transport), _max_retries(max_retries) {}

float DHT11Full::read_temperature() {
    float t, h;
    read(t, h);
    return t;
}

float DHT11Full::read_humidity() {
    float t, h;
    read(t, h);
    return h;
}

bool DHT11Full::read_retry(uint8_t max_retries, float& temperature, float& humidity) {
    if (max_retries == 0) max_retries = _max_retries;
    for (uint8_t i = 0; i < max_retries; i++) {
        uint8_t frame[5];
        if (_transport.read(frame)) {
            _decode(frame, temperature, humidity);
            if (_valid) return true;
        }
    }
    return false;
}

bool DHT11Full::read_raw(uint8_t* out) {
    return _transport.read(out);
}

bool DHT11Full::read_raw_with_retry(uint8_t* out) {
    for (uint8_t i = 0; i < _max_retries; i++) {
        if (_transport.read(out)) {
            uint8_t expected = (uint8_t)((out[0] + out[1] + out[2] + out[3]) & 0xFF);
            if (expected == out[4]) return true;
        }
    }
    return false;
}
