#include "AHT21.h"

#ifdef __linux__
#include <unistd.h>
static void _delay_ms(unsigned ms) { usleep(ms * 1000); }
#elif defined(CONFIG_ZEPHYR)
#include <zephyr/kernel.h>
static void _delay_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }
#else
#include <Arduino.h>
static void _delay_ms(unsigned ms) { delay(ms); }
#endif

AHT21Minimal::AHT21Minimal(Transport& transport)
    : _transport(transport) {
    _delay_ms(100);
    uint8_t status = _read_status();
    if ((status & 0x18) != 0x18) {
        _transport.write(&CMD_SOFT_RESET, 1);
        _delay_ms(20);
        status = _read_status();
        if ((status & 0x18) != 0x18) {
            _transport.write(CMD_CAL_INIT_1, 3);
            _delay_ms(10);
            _transport.write(CMD_CAL_INIT_2, 3);
            _delay_ms(10);
            _transport.write(CMD_CAL_INIT_3, 3);
            _delay_ms(10);
        }
    }
}

uint8_t AHT21Minimal::_read_status() {
    uint8_t buf;
    _transport.read(&buf, 1);
    return buf;
}

void AHT21Minimal::_read_raw(uint8_t* buf, uint8_t len) {
    _transport.read(buf, len);
}

void AHT21Minimal::_decode(const uint8_t* buf, float& temperature_c, float& humidity_pct) {
    uint32_t raw_rh = ((uint32_t)buf[1] << 12) | ((uint32_t)buf[2] << 4) | (buf[3] >> 4);
    uint32_t raw_t  = ((uint32_t)(buf[3] & 0x0F) << 16) | ((uint32_t)buf[4] << 8) | buf[5];
    humidity_pct   = (raw_rh / 1048576.0f) * 100.0f;
    temperature_c  = (raw_t  / 1048576.0f) * 200.0f - 50.0f;
}

float AHT21Minimal::temperature() {
    float t, h;
    read(t, h);
    return t;
}

float AHT21Minimal::humidity() {
    float t, h;
    read(t, h);
    return h;
}

void AHT21Minimal::read(float& temperature_c, float& humidity_pct) {
    _transport.write(CMD_TRIGGER, 3);
    _delay_ms(80);
    uint8_t buf[6];
    _read_raw(buf, 6);
    _decode(buf, temperature_c, humidity_pct);
}

// AHT21Full

AHT21Full::AHT21Full(Transport& transport)
    : AHT21Minimal(transport) {}

bool AHT21Full::read_with_crc(float& temperature_c, float& humidity_pct) {
    _transport.write(CMD_TRIGGER, 3);
    _delay_ms(80);
    uint8_t buf[7];
    _read_raw(buf, 7);
    _decode(buf, temperature_c, humidity_pct);
    return _crc8(buf, 6) == buf[6];
}

void AHT21Full::soft_reset() {
    _transport.write(&CMD_SOFT_RESET, 1);
    _delay_ms(20);
}

bool AHT21Full::is_calibrated() {
    return (_read_status() & STATUS_CAL) != 0;
}

bool AHT21Full::is_busy() {
    return (_read_status() & STATUS_BUSY) != 0;
}

uint8_t AHT21Full::_crc8(const uint8_t* data, uint8_t len) {
    uint8_t crc = 0xFF;
    for (uint8_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (uint8_t j = 0; j < 8; j++) {
            if (crc & 0x80)
                crc = (crc << 1) ^ 0x31;
            else
                crc = crc << 1;
        }
    }
    return crc;
}
