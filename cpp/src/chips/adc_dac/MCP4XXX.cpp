#include "MCP4XXX.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

#ifdef __linux__
#include <unistd.h>
static void periph_delay_ms(unsigned ms) { usleep(ms * 1000); }
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static void periph_delay_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static void periph_delay_ms(unsigned ms) { vTaskDelay(pdMS_TO_TICKS(ms) ? pdMS_TO_TICKS(ms) : 1); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static void periph_delay_ms(unsigned ms) { HAL_Delay(ms); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static void periph_delay_ms(unsigned ms) { sleep_ms(ms); }
#else
#include <Arduino.h>
static void periph_delay_ms(unsigned ms) { delay(ms); }
#endif

static const MCP4XXXPartInfo PARTS[16] = {
    {"MCP4531", 1, 128, true,  false, false, 0x2E, 0x2F},
    {"MCP4532", 1, 128, false, false, false, 0x58, 0x5B},
    {"MCP4551", 1, 256, true,  false, false, 0x2E, 0x2F},
    {"MCP4552", 1, 256, false, false, false, 0x58, 0x5B},
    {"MCP4631", 2, 128, true,  false, false, 0x28, 0x2F},
    {"MCP4632", 2, 128, false, false, false, 0x58, 0x5B},
    {"MCP4651", 2, 256, true,  false, false, 0x28, 0x2F},
    {"MCP4652", 2, 256, false, false, false, 0x58, 0x5B},
    {"MCP4541", 1, 128, true,  true,  false, 0x2E, 0x2F},
    {"MCP4542", 1, 128, false, true,  false, 0x58, 0x5B},
    {"MCP4561", 1, 256, true,  true,  false, 0x2E, 0x2F},
    {"MCP4562", 1, 256, false, true,  false, 0x58, 0x5B},
    {"MCP4641", 2, 128, true,  true,  true,  0x28, 0x2F},
    {"MCP4642", 2, 128, false, true,  false, 0x58, 0x5B},
    {"MCP4661", 2, 256, true,  true,  true,  0x28, 0x2F},
    {"MCP4662", 2, 256, false, true,  false, 0x58, 0x5B},
};

/** Per-channel TCON bit positions: HW, A, W, B. */
static const uint8_t TCON_BITS[2][4] = {{3, 2, 1, 0}, {7, 6, 5, 4}};
static const uint16_t STATUS_WL[2] = {0x02, 0x04};
static const uint16_t STATUS_EEWA = 0x08;
static const uint16_t STATUS_WP = 0x01;
static const uint16_t TCON_DEFAULT = 0x1FF;
static const uint8_t TCON_GCEN = 8;
/** Longest run of increment / decrement commands per transaction (RegisterConnection payload limit). */
static const unsigned MAX_RUN = 32;

const MCP4XXXPartInfo& MCP4XXXMinimal::info(MCP4XXXPart part) {
    return PARTS[static_cast<uint8_t>(part)];
}

MCP4XXXMinimal::MCP4XXXMinimal(RegisterConnection& connection, MCP4XXXPart part, float r_ab, float r_wiper)
    : _connection(connection), _desc(info(part)), _r_ab(r_ab), _r_wiper(r_wiper) {}

void MCP4XXXMinimal::_check_channel(uint8_t channel) const {
    if (channel >= _desc.channels) abort();
}

uint16_t MCP4XXXMinimal::_read9(uint8_t address) {
    uint8_t buf[2];
    _connection.read((uint32_t)((address << 4) | OP_READ), buf, 2);
    return (uint16_t)(((buf[0] & 0x01) << 8) | buf[1]);
}

void MCP4XXXMinimal::_write9(uint8_t address, uint16_t value) {
    uint8_t data = (uint8_t)(value & 0xFF);
    _connection.write((uint32_t)((address << 4) | OP_WRITE | ((value >> 8) & 0x01)), &data, 1);
}

uint16_t MCP4XXXMinimal::_read_wiper(uint8_t channel) {
    uint16_t v = _read9(ADDR_WIPER0 + channel);
    return v > _desc.max_code ? _desc.max_code : v;
}

int MCP4XXXMinimal::_clamp(long code) const {
    if (code < 0) return 0;
    if (code > _desc.max_code) return _desc.max_code;
    return (int)code;
}

void MCP4XXXMinimal::init() {
    for (uint8_t c = 0; c < _desc.channels; c++) _read_wiper(c);
    _read9(ADDR_TCON);
    if (_desc.nonvolatile) _read9(ADDR_STATUS);
}

void MCP4XXXMinimal::set_wiper(int code, uint8_t channel) {
    _check_channel(channel);
    _write9(ADDR_WIPER0 + channel, (uint16_t)_clamp(code));
}

uint16_t MCP4XXXMinimal::get_wiper(uint8_t channel) {
    _check_channel(channel);
    return _read_wiper(channel);
}

void MCP4XXXMinimal::set_resistance(float ohms, uint8_t channel) {
    _check_channel(channel);
    float code = roundf((ohms - _r_wiper) / _r_ab * (float)_desc.max_code);
    if (code < 0.0f) code = 0.0f;
    if (code > (float)_desc.max_code) code = (float)_desc.max_code;
    set_wiper((int)code, channel);
}

float MCP4XXXMinimal::get_resistance(uint8_t channel) {
    return (float)get_wiper(channel) / (float)_desc.max_code * _r_ab + _r_wiper;
}

MCP4XXXFull::MCP4XXXFull(RegisterConnection& connection, MCP4XXXPart part, float r_ab, float r_wiper,
                         OutputPin* hvc, unsigned hvc_settle_ms)
    : MCP4XXXMinimal(connection, part, r_ab, r_wiper),
      _hvc(info(part).nonvolatile ? hvc : nullptr), _hvc_settle_ms(hvc_settle_ms) {}

void MCP4XXXFull::set_ratio(float ratio, uint8_t channel) {
    if (ratio < 0.0f) ratio = 0.0f;
    if (ratio > 1.0f) ratio = 1.0f;
    set_wiper((int)roundf(ratio * (float)_desc.max_code), channel);
}

float MCP4XXXFull::get_ratio(uint8_t channel) {
    return (float)get_wiper(channel) / (float)_desc.max_code;
}

float MCP4XXXFull::get_resistance_wa(uint8_t channel) {
    if (!_desc.potentiometer) return -1.0f;
    float max = (float)_desc.max_code;
    return (max - (float)get_wiper(channel)) / max * _r_ab + _r_wiper;
}

bool MCP4XXXFull::set_resistance_wa(float ohms, uint8_t channel) {
    if (!_desc.potentiometer) return false;
    _check_channel(channel);
    int n = (int)roundf((ohms - _r_wiper) / _r_ab * (float)_desc.max_code);
    set_wiper(_desc.max_code - n, channel);
    return true;
}

bool MCP4XXXFull::set_both(int code0, int code1) {
    if (_desc.channels < 2) return false;
    set_wiper(code0, 0);
    set_wiper(code1, 1);
    return true;
}

uint16_t MCP4XXXFull::_step(uint8_t op, unsigned steps, uint8_t channel) {
    _check_channel(channel);
    uint8_t command = (uint8_t)(((ADDR_WIPER0 + channel) << 4) | op);
    uint8_t run[MAX_RUN];
    memset(run, command, sizeof(run));
    while (steps > 0) {
        // Continuous mode: one command byte per step, no data bytes; the first byte is the "register".
        unsigned n = steps > MAX_RUN ? MAX_RUN : steps;
        _connection.write((uint32_t)command, run, n - 1);
        steps -= n;
    }
    return _read_wiper(channel);
}

uint16_t MCP4XXXFull::increment(unsigned steps, uint8_t channel) { return _step(OP_INC, steps, channel); }
uint16_t MCP4XXXFull::decrement(unsigned steps, uint8_t channel) { return _step(OP_DEC, steps, channel); }

void MCP4XXXFull::_modify_tcon(const uint8_t* bits, const bool* on, uint8_t n) {
    uint16_t value = _read9(ADDR_TCON);
    for (uint8_t i = 0; i < n; i++) {
        if (on[i]) value |= (uint16_t)(1u << bits[i]);
        else value &= (uint16_t)~(1u << bits[i]);
    }
    _write9(ADDR_TCON, value & 0x1FF);
}

void MCP4XXXFull::shutdown(uint8_t channel) {
    _check_channel(channel);
    bool on = false;
    _modify_tcon(&TCON_BITS[channel][0], &on, 1);
}

void MCP4XXXFull::resume(uint8_t channel) {
    _check_channel(channel);
    bool on = true;
    _modify_tcon(&TCON_BITS[channel][0], &on, 1);
}

bool MCP4XXXFull::is_shutdown(uint8_t channel) {
    _check_channel(channel);
    return ((_read9(ADDR_TCON) >> TCON_BITS[channel][0]) & 1) == 0;
}

void MCP4XXXFull::set_terminals(bool a, bool w, bool b, uint8_t channel) {
    _check_channel(channel);
    uint8_t bits[3] = {TCON_BITS[channel][1], TCON_BITS[channel][2], TCON_BITS[channel][3]};
    bool on[3] = {a, w, b};
    _modify_tcon(bits, on, 3);
}

void MCP4XXXFull::get_terminals(uint8_t channel, bool& a, bool& w, bool& b) {
    _check_channel(channel);
    uint16_t tcon = _read9(ADDR_TCON);
    a = ((tcon >> TCON_BITS[channel][1]) & 1) == 1;
    w = ((tcon >> TCON_BITS[channel][2]) & 1) == 1;
    b = ((tcon >> TCON_BITS[channel][3]) & 1) == 1;
}

uint16_t MCP4XXXFull::get_tcon() { return _read9(ADDR_TCON); }

void MCP4XXXFull::set_general_call(bool enabled) {
    uint8_t bit = TCON_GCEN;
    _modify_tcon(&bit, &enabled, 1);
}

void MCP4XXXFull::reset() {
    MCP4XXXStatus st = {false, {false, false}, false};
    if (_desc.nonvolatile) read_status(st);
    bool any_locked = false;
    for (uint8_t c = 0; c < _desc.channels; c++) {
        if (st.wiper_lock[c]) {
            any_locked = true;
            continue;
        }
        set_wiper(_desc.nonvolatile ? get_nv_wiper(c) : _desc.max_code / 2, c);
    }
    if (!any_locked) _write9(ADDR_TCON, TCON_DEFAULT);
}

bool MCP4XXXFull::_address_ok(uint8_t address, bool writable) const {
    if (address > 0x0F) return false;
    bool ok = address == ADDR_TCON || address < _desc.channels;
    if (_desc.nonvolatile) {
        ok = ok || ((address == 2 || address == 3) && (address - 2) < _desc.channels) || address >= ADDR_DATA0;
        if (address == ADDR_STATUS) ok = !writable;
    }
    return ok;
}

int MCP4XXXFull::read_register(uint8_t address) {
    if (!_address_ok(address, false)) return -1;
    return _read9(address);
}

bool MCP4XXXFull::write_register(uint8_t address, int value) {
    if (!_address_ok(address, true)) return false;
    int limit = address < 2 ? 0x3FF : 0x1FF;
    if (value < 0) value = 0;
    if (value > limit) value = limit;
    _write9(address, (uint16_t)value);
    return true;
}

void MCP4XXXFull::set_rab(float r_ab, float r_wiper) {
    _r_ab = r_ab;
    if (r_wiper >= 0.0f) _r_wiper = r_wiper;
}

bool MCP4XXXFull::read_status(MCP4XXXStatus& out) {
    if (!_desc.nonvolatile) return false;
    uint16_t v = _read9(ADDR_STATUS);
    out.eeprom_busy = (v & STATUS_EEWA) != 0;
    out.wiper_lock[0] = (v & STATUS_WL[0]) != 0;
    out.wiper_lock[1] = _desc.channels > 1 && (v & STATUS_WL[1]) != 0;
    out.write_protect = (v & STATUS_WP) != 0;
    return true;
}

bool MCP4XXXFull::wait_ready(unsigned timeout_ms) {
    if (!_desc.nonvolatile) return false;
    for (unsigned i = 0; i <= timeout_ms; i++) {
        if ((_read9(ADDR_STATUS) & STATUS_EEWA) == 0) return true;
        periph_delay_ms(1);
    }
    return false;
}

int MCP4XXXFull::get_nv_wiper(uint8_t channel) {
    if (!_desc.nonvolatile) return -1;
    _check_channel(channel);
    uint16_t v = _read9(ADDR_NVWIPER0 + channel);
    return v > _desc.max_code ? _desc.max_code : v;
}

bool MCP4XXXFull::set_nv_wiper(int code, uint8_t channel) {
    if (!_desc.nonvolatile) return false;
    _check_channel(channel);
    _write9(ADDR_NVWIPER0 + channel, (uint16_t)_clamp(code));
    return wait_ready();
}

int MCP4XXXFull::store_wiper(uint8_t channel) {
    if (!_desc.nonvolatile) return -1;
    int code = get_wiper(channel);
    return set_nv_wiper(code, channel) ? code : -1;
}

int MCP4XXXFull::read_data(uint8_t index) {
    if (!_desc.nonvolatile || index > 9) return -1;
    return _read9(ADDR_DATA0 + index);
}

bool MCP4XXXFull::write_data(uint8_t index, int value) {
    if (!_desc.nonvolatile || index > 9) return false;
    if (value < 0) value = 0;
    if (value > 0x1FF) value = 0x1FF;
    _write9(ADDR_DATA0 + index, (uint16_t)value);
    return wait_ready();
}

bool MCP4XXXFull::is_wiper_locked(uint8_t channel) {
    _check_channel(channel);
    MCP4XXXStatus st;
    return read_status(st) && st.wiper_lock[channel];
}

bool MCP4XXXFull::is_write_protected() {
    MCP4XXXStatus st;
    return read_status(st) && st.write_protect;
}

bool MCP4XXXFull::_hv_command(uint8_t address, bool decrement) {
    if (_hvc == nullptr) return false;
    uint8_t command = (uint8_t)((address << 4) | (decrement ? OP_DEC : OP_INC));
    _hvc->set(true);
    periph_delay_ms(_hvc_settle_ms);
    _connection.write((uint32_t)command, nullptr, 0);
    _hvc->set(false);
    return wait_ready();
}

bool MCP4XXXFull::set_wiper_lock(bool locked, uint8_t channel) {
    if (!_desc.nonvolatile) return false;
    _check_channel(channel);
    return _hv_command(ADDR_NVWIPER0 + channel, locked);
}

bool MCP4XXXFull::set_write_protect(bool enabled) {
    if (!_desc.nonvolatile) return false;
    return _hv_command(ADDR_WP, enabled);
}
