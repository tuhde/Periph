#include "Si1132.h"
#include <math.h>
#include <string.h>

#ifdef __linux__
#include <unistd.h>
static void si1132_delay_ms(unsigned ms) { usleep(ms * 1000); }
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static void si1132_delay_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static void si1132_delay_ms(unsigned ms) { vTaskDelay(pdMS_TO_TICKS(ms) > 0 ? pdMS_TO_TICKS(ms) : 1); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static void si1132_delay_ms(unsigned ms) { HAL_Delay(ms); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static void si1132_delay_ms(unsigned ms) { sleep_ms(ms); }
#else
#include <Arduino.h>
static void si1132_delay_ms(unsigned ms) { delay(ms); }
#endif

namespace {
const unsigned TIMEOUT_MS = 500;
const uint8_t UCOEF_DEFAULT[4] = {0x7B, 0x6B, 0x01, 0x00};
}  // namespace


// --- Minimal ---

Si1132Minimal::Si1132Minimal(RegisterConnection& connection)
    : _connection(connection) {}

bool Si1132Minimal::_write_reg(uint8_t reg, uint8_t value) {
    _connection.write((uint32_t)reg, &value, 1);
    return _connection.ok();
}

bool Si1132Minimal::_write_regs(uint8_t reg, const uint8_t* data, size_t len) {
    _connection.write((uint32_t)reg, data, len);
    return _connection.ok();
}

bool Si1132Minimal::_read_reg(uint8_t reg, uint8_t& value) {
    _connection.read((uint32_t)reg, &value, 1);
    return _connection.ok();
}

bool Si1132Minimal::_wait_response(uint8_t before) {
    for (int i = 0; i < 20; i++) {
        uint8_t resp;
        if (!_read_reg(REG_RESPONSE, resp)) return false;
        if (resp & RESP_ERROR) {
            _write_reg(REG_COMMAND, CMD_NOP);
            return false;
        }
        if ((resp & 0x0F) != before) return true;
        si1132_delay_ms(1);
    }
    return false;
}

bool Si1132Minimal::_command(uint8_t cmd) {
    uint8_t before;
    if (!_read_reg(REG_RESPONSE, before)) return false;
    before &= 0x0F;
    if (!_write_reg(REG_COMMAND, cmd)) return false;
    return _wait_response(before);
}

bool Si1132Minimal::_param_set(uint8_t offset, uint8_t value) {
    uint8_t before;
    if (!_read_reg(REG_RESPONSE, before)) return false;
    before &= 0x0F;
    if (!_write_reg(REG_PARAM_WR, value)) return false;
    if (!_write_reg(REG_COMMAND, (uint8_t)(CMD_PARAM_SET | (offset & 0x1F)))) return false;
    return _wait_response(before);
}

bool Si1132Minimal::_start_measurement() {
    if (!_write_reg(REG_IRQ_STATUS, IRQ_CLEAR)) return false;
    return _command(CMD_ALS_FORCE);
}

bool Si1132Minimal::_wait_ready() {
    unsigned waited = 0;
    for (;;) {
        uint8_t st;
        if (!_read_reg(REG_IRQ_STATUS, st)) return false;
        if (st & IRQ_ALS_INT) break;
        if (waited >= TIMEOUT_MS) return false;
        si1132_delay_ms(1);
        waited++;
    }
    return _write_reg(REG_IRQ_STATUS, IRQ_CLEAR);
}

bool Si1132Minimal::_read_data(uint16_t& visible, uint16_t& infrared, uint16_t& aux) {
    uint8_t d[12];
    _connection.read((uint32_t)REG_DATA, d, sizeof(d));
    if (!_connection.ok()) return false;
    visible = (uint16_t)(d[0] | (d[1] << 8));
    infrared = (uint16_t)(d[2] | (d[3] << 8));
    aux = (uint16_t)(d[10] | (d[11] << 8));
    return true;
}

bool Si1132Minimal::_measure(uint16_t& visible, uint16_t& infrared, uint16_t& aux) {
    if (!_start_measurement()) return false;
    if (!_wait_ready()) return false;
    return _read_data(visible, infrared, aux);
}

bool Si1132Minimal::init() {
    si1132_delay_ms(25);
    uint8_t id;
    if (!_read_reg(REG_PART_ID, id) || id != PART_ID_VALUE) return false;
    if (!_read_reg(REG_SEQ_ID, _seq_id)) return false;
    if (!_write_reg(REG_HW_KEY, HW_KEY_VALUE)) return false;
    if (!_write_regs(REG_UCOEF, UCOEF_DEFAULT, sizeof(UCOEF_DEFAULT))) return false;
    // ALS_IE lets IRQ_STATUS.ALS_INT flag completed measurements; INT stays released (INT_OE = 0).
    if (!_write_reg(REG_IRQ_ENABLE, IRQ_ALS_INT)) return false;
    _chlist = CHLIST_ALL;
    return _param_set(PARAM_CHLIST, _chlist);
}

bool Si1132Minimal::read(float& uv_index, uint16_t& visible, uint16_t& infrared) {
    uint16_t aux;
    if (!_measure(visible, infrared, aux)) return false;
    uv_index = aux / 100.0f;
    return true;
}

// --- Full ---

Si1132Full* Si1132Full::_active_instance = nullptr;

Si1132Full::Si1132Full(RegisterConnection& connection)
    : Si1132Minimal(connection) {}

float Si1132Full::read_uv_index() {
    uint16_t v, i, a;
    if (!_measure(v, i, a)) return NAN;
    return a / 100.0f;
}

bool Si1132Full::read_visible(uint16_t& visible) {
    uint16_t i, a;
    return _measure(visible, i, a);
}

bool Si1132Full::read_infrared(uint16_t& infrared) {
    uint16_t v, a;
    return _measure(v, infrared, a);
}

bool Si1132Full::read_raw(uint16_t& visible, uint16_t& infrared, uint16_t& aux) {
    return _read_data(visible, infrared, aux);
}

bool Si1132Full::_read_aux(uint8_t mux, uint16_t& raw) {
    if (!_param_set(PARAM_AUX_ADCMUX, mux)) return false;
    if (!_param_set(PARAM_CHLIST, CHLIST_AUX)) return false;
    uint16_t v, i;
    bool ok = _measure(v, i, raw);
    bool restored = _param_set(PARAM_CHLIST, _chlist);
    return ok && restored;
}

bool Si1132Full::read_temperature_raw(uint16_t& raw) { return _read_aux(0x65, raw); }

bool Si1132Full::read_vdd_raw(uint16_t& raw) { return _read_aux(0x75, raw); }

bool Si1132Full::set_channels(bool uv, bool visible, bool infrared, bool aux) {
    if (!(uv || visible || infrared || aux) || (uv && aux)) return false;
    uint8_t chlist = (uint8_t)((uv ? 0x80 : 0) | (aux ? 0x40 : 0) | (infrared ? 0x20 : 0) | (visible ? 0x10 : 0));
    if (!_param_set(PARAM_CHLIST, chlist)) return false;
    _chlist = chlist;
    return true;
}

bool Si1132Full::set_gain(uint8_t visible, uint8_t infrared) {
    if (visible > 7 || infrared > 7) return false;
    return _param_set(PARAM_VIS_GAIN, visible)
        && _param_set(PARAM_VIS_COUNTER, (uint8_t)((~visible & 0x07) << 4))
        && _param_set(PARAM_IR_GAIN, infrared)
        && _param_set(PARAM_IR_COUNTER, (uint8_t)((~infrared & 0x07) << 4));
}

bool Si1132Full::set_high_range(bool visible, bool infrared) {
    return _param_set(PARAM_VIS_MISC, visible ? 0x20 : 0x00)
        && _param_set(PARAM_IR_MISC, infrared ? 0x20 : 0x00);
}

bool Si1132Full::set_alignment(bool visible_low, bool infrared_low) {
    return _param_set(PARAM_ALS_ENCODING, (uint8_t)((visible_low ? 0x10 : 0) | (infrared_low ? 0x20 : 0)));
}

bool Si1132Full::set_uv_coefficients(const uint8_t ucoef[4]) {
    return _write_regs(REG_UCOEF, ucoef, 4);
}

bool Si1132Full::read_calibration(uint8_t out[12]) {
    if (!_command(CMD_GET_CAL)) return false;
    _connection.read((uint32_t)REG_DATA, out, 12);
    return _connection.ok();
}

bool Si1132Full::start_autonomous(float interval_s) {
    if (interval_s < 0.0f) return false;
    long counts = lroundf(interval_s / 31.25e-6f);
    if (counts > 0xFFFF) return false;
    bool ok;
    if (_seq_id == 0x01) {   // erratum: MEAS_RATE bytes swapped
        ok = _write_reg(REG_MEAS_RATE_SWAPPED, (uint8_t)(counts & 0xFF))
          && _write_reg(REG_MEAS_RATE0, (uint8_t)(counts >> 8));
    } else {
        ok = _write_reg(REG_MEAS_RATE0, (uint8_t)(counts & 0xFF))
          && _write_reg(REG_MEAS_RATE1, (uint8_t)(counts >> 8));
    }
    if (!ok) return false;
    return _command(counts ? CMD_ALS_AUTO : CMD_ALS_PAUSE);
}

bool Si1132Full::pause() { return _command(CMD_ALS_PAUSE); }

bool Si1132Full::force() { return _start_measurement(); }

bool Si1132Full::measurement_ready() {
    uint8_t st;
    return _read_reg(REG_IRQ_STATUS, st) && (st & IRQ_ALS_INT);
}

bool Si1132Full::read_response(uint8_t& counter, uint8_t& error) {
    uint8_t resp;
    if (!_read_reg(REG_RESPONSE, resp)) return false;
    if (resp & RESP_ERROR) { counter = 0; error = resp; }
    else { counter = (uint8_t)(resp & 0x0F); error = 0; }
    return true;
}

bool Si1132Full::set_i2c_address(uint8_t address) {
    return _param_set(PARAM_I2C_ADDR, address) && _command(CMD_BUSADDR);
}

bool Si1132Full::reset() {
    if (!_write_reg(REG_COMMAND, CMD_RESET)) return false;
    si1132_delay_ms(2);
    return _write_reg(REG_HW_KEY, HW_KEY_VALUE);
}

bool Si1132Full::enable_interrupt() {
    return _write_reg(REG_IRQ_ENABLE, IRQ_ALS_INT) && _write_reg(REG_INT_CFG, 0x01);
}

bool Si1132Full::disable_interrupt() {
    return _write_reg(REG_INT_CFG, 0x00);
}

uint8_t Si1132Full::poll_interrupt() {
    uint8_t st;
    if (!_read_reg(REG_IRQ_STATUS, st)) return 0;
    if (st) _write_reg(REG_IRQ_STATUS, st);
    return st;
}

void Si1132Full::_edge_trampoline() {
    Si1132Full* self = _active_instance;
    if (self && self->_callback) {
        uint8_t st = self->poll_interrupt();
        if (st) self->_callback(st);
    }
}

bool Si1132Full::on_interrupt(void (*callback)(uint8_t status), InputPin* intPin) {
    InputPin* pin = intPin ? intPin : _connection.intPin();
    if (!pin) return false;
    off_interrupt();
    _callback = callback;
    _int_pin = pin;
    _active_instance = this;
    enable_interrupt();
    return pin->onEdge(&Si1132Full::_edge_trampoline, InputPin::kFalling);
}

void Si1132Full::off_interrupt() {
    if (_int_pin) _int_pin->offEdge(&Si1132Full::_edge_trampoline);
    bool had = _callback != nullptr;
    _int_pin = nullptr;
    _callback = nullptr;
    if (had) disable_interrupt();
}
