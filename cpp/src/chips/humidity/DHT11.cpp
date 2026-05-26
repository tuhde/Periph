#include "DHT11.h"
#include <string.h>

DHT11Minimal::DHT11Minimal(DHTxxTransport& transport) : _transport(transport) {}

void DHT11Minimal::read(float& temperature, float& humidity) {
    uint8_t frame[5];
    if (!_transport.read(frame, 5)) {
        temperature = 0.0f;
        humidity = 0.0f;
        return;
    }

    uint8_t hum_int = frame[0];
    uint8_t hum_dec = frame[1];
    uint8_t temp_int = frame[2];
    uint8_t temp_dec = frame[3];
    uint8_t checksum = frame[4];

    if ((hum_int + hum_dec + temp_int + temp_dec) & 0xFF != checksum) {
        return;
    }

    humidity = (float)hum_int + (float)hum_dec / 10.0f;
    int8_t sign = (temp_dec & 0x80) ? -1 : 1;
    uint8_t temp_dec_value = temp_dec & 0x7F;
    temperature = sign * ((float)temp_int + (float)temp_dec_value / 10.0f);
}

DHT11Full::DHT11Full(DHTxxTransport& transport) : DHT11Minimal(transport), _transport(transport) {}

float DHT11Full::readTemperature() {
    float t, h;
    read(t, h);
    return t;
}

float DHT11Full::readHumidity() {
    float t, h;
    read(t, h);
    return h;
}

void DHT11Full::readRetry(float& temperature, float& humidity, int maxRetries) {
    for (int i = 0; i < maxRetries; i++) {
        uint8_t frame[5];
        if (_transport.read(frame, 5)) {
            uint8_t hum_int = frame[0];
            uint8_t hum_dec = frame[1];
            uint8_t temp_int = frame[2];
            uint8_t temp_dec = frame[3];
            uint8_t checksum = frame[4];

            if ((hum_int + hum_dec + temp_int + temp_dec) & 0xFF == checksum) {
                humidity = (float)hum_int + (float)hum_dec / 10.0f;
                int8_t sign = (temp_dec & 0x80) ? -1 : 1;
                uint8_t temp_dec_value = temp_dec & 0x7F;
                temperature = sign * ((float)temp_int + (float)temp_dec_value / 10.0f);
                return;
            }
        }
    }
}

void DHT11Full::readRaw(uint8_t* frame, size_t len) {
    if (len < 5) {
        return;
    }
    _transport.read(frame, 5);
}
