#include "ENS160.h"
#include <stdlib.h>
#include <cmath>

#ifndef ARDUINO
#include <unistd.h>
static inline void delay(unsigned long ms) { usleep(ms * 1000UL); }
#endif

ENS160Minimal::ENS160Minimal(Transport& transport)
    : _transport(transport) {
    _write_reg(REG_OPMODE, OPMODE_IDLE);
    delay(1);
    uint16_t part_id = _read_reg_le16(REG_PART_ID);
    if (part_id != PART_ID_EXPECTED) {
        abort();
    }
    _write_reg(REG_OPMODE, OPMODE_STANDARD);
}

void ENS160Minimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { reg, value };
    _transport.write(buf, 2);
}

void ENS160Minimal::_write_reg_le16(uint8_t reg, uint16_t value) {
    uint8_t buf[3] = { reg, (uint8_t)(value & 0xFF), (uint8_t)((value >> 8) & 0xFF) };
    _transport.write(buf, 3);
}

void ENS160Minimal::_read_reg(uint8_t reg, uint8_t* buf, size_t len) {
    _transport.write_read(&reg, 1, buf, len);
}

uint16_t ENS160Minimal::_read_reg_le16(uint8_t reg) {
    uint8_t buf[2];
    _read_reg(reg, buf, 2);
    return (uint16_t)buf[0] | ((uint16_t)buf[1] << 8);
}

uint8_t ENS160Minimal::_read_device_status() {
    uint8_t buf[1];
    _read_reg(REG_DEVICE_STATUS, buf, 1);
    return buf[0];
}

bool ENS160Minimal::_wait_for_new_data(uint32_t timeout_ms) {
    uint32_t start = 0;
    #ifdef ARDUINO
    start = millis();
    #else
    start = 0;
    #endif
    while (true) {
        uint8_t status = _read_device_status();
        if (status & 0x02) {
            return true;
        }
        #ifdef ARDUINO
        if (millis() - start > timeout_ms) {
            return false;
        }
        #else
        (void)start;
        (void)timeout_ms;
        #endif
        delay(10);
    }
}

uint8_t ENS160Minimal::status() {
    uint8_t status = _read_device_status();
    return (status >> 2) & 0x03;
}

bool ENS160Minimal::read_air_quality(uint8_t& aqi, float& tvoc_ppb, float& eco2_ppm) {
    if (!_wait_for_new_data()) {
        return false;
    }
    uint8_t status = _read_device_status();
    uint8_t validity = (status >> 2) & 0x03;
    if (validity != 0) {
        return false;
    }
    uint8_t data[5];
    _read_reg(REG_DATA_AQI, data, 5);
    aqi = data[0] & 0x07;
    uint16_t tvoc_raw = (uint16_t)data[1] | ((uint16_t)data[2] << 8);
    uint16_t eco2_raw = (uint16_t)data[3] | ((uint16_t)data[4] << 8);
    tvoc_ppb = (float)tvoc_raw;
    eco2_ppm = (float)eco2_raw;
    return true;
}

// ENS160Full

ENS160Full::ENS160Full(Transport& transport)
    : ENS160Minimal(transport) {
}

void ENS160Full::set_compensation(float temp_celsius, float rh_percent) {
    uint16_t temp_raw = (uint16_t)((temp_celsius + 273.15f) * 64.0f);
    uint16_t rh_raw = (uint16_t)(rh_percent * 512.0f);
    _write_reg_le16(REG_TEMP_IN, temp_raw);
    _write_reg_le16(REG_RH_IN, rh_raw);
}

float ENS160Full::read_tvoc() {
    _wait_for_new_data();
    return (float)_read_reg_le16(REG_DATA_TVOC);
}

float ENS160Full::read_eco2() {
    _wait_for_new_data();
    return (float)_read_reg_le16(REG_DATA_ECO2);
}

uint8_t ENS160Full::read_aqi() {
    _wait_for_new_data();
    uint8_t data[1];
    _read_reg(REG_DATA_AQI, data, 1);
    return data[0] & 0x07;
}

float ENS160Full::read_ethanol() {
    _wait_for_new_data();
    return (float)_read_reg_le16(REG_DATA_TVOC);
}

float ENS160Full::read_raw_resistance(uint8_t sensor) {
    uint8_t offset = (sensor == 1) ? 0 : (sensor == 4) ? 6 : 0;
    uint16_t raw = _read_reg_le16(REG_GPR_READ + offset);
    return powf(2.0f, (float)raw / 2048.0f);
}

void ENS160Full::read_compensation_actuals(float& temp_celsius, float& rh_percent) {
    uint8_t data[4];
    _read_reg(REG_DATA_T, data, 4);
    uint16_t temp_raw = (uint16_t)data[0] | ((uint16_t)data[1] << 8);
    uint16_t rh_raw = (uint16_t)data[2] | ((uint16_t)data[3] << 8);
    temp_celsius = ((float)temp_raw / 64.0f) - 273.15f;
    rh_percent = (float)rh_raw / 512.0f;
}

void ENS160Full::get_firmware_version(uint8_t& major, uint8_t& minor, uint8_t& release) {
    _write_reg(REG_OPMODE, OPMODE_IDLE);
    delay(1);
    _write_reg(REG_COMMAND, 0x0E);
    delay(1);
    uint8_t data[3];
    _read_reg(REG_GPR_READ + 4, data, 3);
    major = data[0];
    minor = data[1];
    release = data[2];
    _write_reg(REG_OPMODE, OPMODE_STANDARD);
}

void ENS160Full::configure_interrupt(bool enabled, bool active_high, bool push_pull, bool on_data, bool on_gpr) {
    uint8_t config = 0;
    if (enabled) config |= 0x01;
    if (on_data) config |= 0x02;
    if (on_gpr) config |= 0x08;
    if (push_pull) config |= 0x20;
    if (active_high) config |= 0x40;
    _write_reg(REG_CONFIG, config);
}

void ENS160Full::sleep() {
    _write_reg(REG_OPMODE, OPMODE_DEEP_SLEEP);
}

void ENS160Full::wake() {
    _write_reg(REG_OPMODE, OPMODE_IDLE);
    delay(1);
    _write_reg(REG_OPMODE, OPMODE_STANDARD);
}
