#include "Max17048.h"
#include <math.h>

// Out-of-class definitions for ODR-used static constexpr members (AVR builds as C++11).
constexpr uint8_t  Max17048Minimal::REG_VCELL;
constexpr uint8_t  Max17048Minimal::REG_SOC;
constexpr uint8_t  Max17048Minimal::REG_MODE;
constexpr uint8_t  Max17048Minimal::REG_VERSION;
constexpr uint8_t  Max17048Minimal::REG_HIBRT;
constexpr uint8_t  Max17048Minimal::REG_CONFIG;
constexpr uint8_t  Max17048Minimal::REG_VALRT;
constexpr uint8_t  Max17048Minimal::REG_CRATE;
constexpr uint8_t  Max17048Minimal::REG_VRESET;
constexpr uint8_t  Max17048Minimal::REG_STATUS;
constexpr uint8_t  Max17048Minimal::REG_CMD;
constexpr uint16_t Max17048Full::SOURCE_RESET;
constexpr uint16_t Max17048Full::SOURCE_OVERVOLTAGE;
constexpr uint16_t Max17048Full::SOURCE_UNDERVOLTAGE;
constexpr uint16_t Max17048Full::SOURCE_VRESET;
constexpr uint16_t Max17048Full::SOURCE_LOW_SOC;
constexpr uint16_t Max17048Full::SOURCE_SOC_CHANGE;

#ifdef __linux__
#include <unistd.h>
static void periph_delay_us(unsigned us) { usleep(us); }
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static void periph_delay_us(unsigned us) { k_usleep(us); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <esp_rom_sys.h>
static void periph_delay_us(unsigned us) {
    if (us >= 10000) vTaskDelay(pdMS_TO_TICKS(us / 1000)); else esp_rom_delay_us(us);
}
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static void periph_delay_us(unsigned us) { HAL_Delay((us + 999) / 1000); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static void periph_delay_us(unsigned us) { sleep_us(us); }
#else
#include <Arduino.h>
static void periph_delay_us(unsigned us) { delayMicroseconds(us); }
#endif

static void periph_delay_ms(unsigned ms) {
    while (ms > 0) { unsigned s = ms > 10 ? 10 : ms; periph_delay_us(s * 1000); ms -= s; }
}

static uint8_t clamp_byte(long v) { return v < 0 ? 0 : (v > 255 ? 255 : (uint8_t)v); }

Max17048Minimal::Max17048Minimal(RegisterConnection& connection, uint8_t cells)
    : _connection(connection), _cells(cells == 2 ? 2 : 1) {}

uint16_t Max17048Minimal::_read_word(uint8_t reg) {
    uint8_t buf[2] = {0, 0};
    _connection.read(reg, buf, 2);
    return (uint16_t)((buf[0] << 8) | buf[1]);
}

void Max17048Minimal::_write_word(uint8_t reg, uint16_t value) {
    uint8_t data[2] = {(uint8_t)(value >> 8), (uint8_t)(value & 0xFF)};
    _connection.write(reg, data, 2);
}

float Max17048Minimal::voltage() {
    return (float)(_read_word(REG_VCELL) >> 4) * 1.25e-3f * (float)_cells;
}

float Max17048Minimal::percent() {
    float p = (float)_read_word(REG_SOC) / 256.0f;
    return p < 0.0f ? 0.0f : (p > 100.0f ? 100.0f : p);
}

Max17048Full* Max17048Full::_activeInstance = nullptr;

Max17048Full::Max17048Full(RegisterConnection& connection, uint8_t cells) : Max17048Minimal(connection, cells) {}

void Max17048Full::_modify(uint8_t reg, uint16_t clearBits, uint16_t setBits) {
    _write_word(reg, (uint16_t)((_read_word(reg) & ~clearBits) | setBits));
}

uint16_t Max17048Full::raw_voltage() { return _read_word(REG_VCELL); }
uint16_t Max17048Full::raw_soc() { return _read_word(REG_SOC); }

float Max17048Full::charge_rate() { return (float)(int16_t)_read_word(REG_CRATE) * 0.208f; }

uint16_t Max17048Full::version() { return _read_word(REG_VERSION); }
uint8_t Max17048Full::chip_id() { return (uint8_t)(_read_word(REG_VRESET) & 0xFF); }

void Max17048Full::quick_start() { _write_word(REG_MODE, (uint16_t)(0x4000 | (_sleepEnabled ? 0x2000 : 0))); }

void Max17048Full::reset() {
    _write_word(REG_CMD, 0x5400);   // the chip does not ACK the second byte; the connection error is ignored
    periph_delay_ms(1000);
}

void Max17048Full::set_rcomp(uint8_t value) { _modify(REG_CONFIG, 0xFF00, (uint16_t)value << 8); }
uint8_t Max17048Full::get_rcomp() { return (uint8_t)(_read_word(REG_CONFIG) >> 8); }

void Max17048Full::compensate_temperature(float celsius) {
    float coefficient = celsius >= 20.0f ? -0.5f : -5.0f;
    set_rcomp(clamp_byte(lroundf(151.0f + (celsius - 20.0f) * coefficient)));
}

void Max17048Full::set_alert_soc(uint8_t percent) {
    if (percent < 1) percent = 1;
    if (percent > 32) percent = 32;
    _modify(REG_CONFIG, 0x001F, (uint16_t)(32 - percent));
}

uint8_t Max17048Full::get_alert_soc() { return (uint8_t)(32 - (_read_word(REG_CONFIG) & 0x1F)); }

void Max17048Full::set_soc_change_alert(bool enabled) { _modify(REG_CONFIG, 0x0040, enabled ? 0x0040 : 0); }

bool Max17048Full::set_voltage_alert(float min_v, float max_v) {
    if (min_v > max_v) return false;
    uint8_t low = clamp_byte(lroundf(min_v / 0.020f));
    uint8_t high = clamp_byte(lroundf(max_v / 0.020f));
    _write_word(REG_VALRT, (uint16_t)((low << 8) | high));
    return true;
}

void Max17048Full::get_voltage_alert(float* min_v, float* max_v) {
    uint16_t raw = _read_word(REG_VALRT);
    if (min_v) *min_v = (float)(raw >> 8) * 0.020f;
    if (max_v) *max_v = (float)(raw & 0xFF) * 0.020f;
}

void Max17048Full::set_reset_voltage(float volts) {
    long steps = lroundf(volts / 0.040f);
    if (steps < 0) steps = 0;
    if (steps > 127) steps = 127;
    _modify(REG_VRESET, 0xFE00, (uint16_t)(steps << 9));
}

float Max17048Full::get_reset_voltage() { return (float)(_read_word(REG_VRESET) >> 9) * 0.040f; }

void Max17048Full::enable_voltage_reset_alert(bool enabled) { _modify(REG_STATUS, 0x4000, enabled ? 0x4000 : 0); }

void Max17048Full::set_comparator_disabled(bool disabled) { _modify(REG_VRESET, 0x0100, disabled ? 0x0100 : 0); }

void Max17048Full::set_hibernate_thresholds(float hib_thr_pct_per_h, float act_thr_v) {
    uint8_t hib = clamp_byte(lroundf(hib_thr_pct_per_h / 0.208f));
    uint8_t act = clamp_byte(lroundf(act_thr_v / 0.00125f));
    _write_word(REG_HIBRT, (uint16_t)((hib << 8) | act));
}

void Max17048Full::get_hibernate_thresholds(float* hib_thr_pct_per_h, float* act_thr_v) {
    uint16_t raw = _read_word(REG_HIBRT);
    if (hib_thr_pct_per_h) *hib_thr_pct_per_h = (float)(raw >> 8) * 0.208f;
    if (act_thr_v) *act_thr_v = (float)(raw & 0xFF) * 0.00125f;
}

void Max17048Full::disable_hibernate() { _write_word(REG_HIBRT, 0x0000); }
void Max17048Full::force_hibernate() { _write_word(REG_HIBRT, 0xFFFF); }

bool Max17048Full::is_hibernating() {
    uint16_t mode = _read_word(REG_MODE);
    return _connection.ok() && (mode & 0x1000) != 0;   // MODE is write-only: an unreadable value counts as false
}

void Max17048Full::enable_sleep(bool enabled) {
    _sleepEnabled = enabled;
    _write_word(REG_MODE, enabled ? 0x2000 : 0x0000);
}

void Max17048Full::sleep() { _modify(REG_CONFIG, 0, 0x0080); }
void Max17048Full::wake() { _modify(REG_CONFIG, 0x0080, 0); }

uint16_t Max17048Full::read_status() { return _read_word(REG_STATUS); }

void Max17048Full::clear_status(uint16_t mask) {
    _modify(REG_STATUS, mask, 0);
    _modify(REG_CONFIG, 0x0020, 0);
}

uint16_t Max17048Full::read_config() { return _read_word(REG_CONFIG); }
void Max17048Full::write_config(uint16_t value) { _write_word(REG_CONFIG, value); }

void Max17048Full::enableInterrupt(uint16_t source) {
    if (source == SOURCE_SOC_CHANGE) set_soc_change_alert(true);
    else if (source == SOURCE_VRESET) enable_voltage_reset_alert(true);
}

void Max17048Full::disableInterrupt(uint16_t source) {
    if (source == SOURCE_SOC_CHANGE) set_soc_change_alert(false);
    else if (source == SOURCE_VRESET) enable_voltage_reset_alert(false);
    else if (source == SOURCE_UNDERVOLTAGE) _modify(REG_VALRT, 0xFF00, 0);
    else if (source == SOURCE_OVERVOLTAGE) _modify(REG_VALRT, 0x00FF, 0x00FF);
}

void Max17048Full::onInterrupt(void (*callback)(uint16_t), InputPin* intPin) {
    _callback = callback;
    _intPinUsed = intPin ? intPin : _connection.intPin();
    if (!_intPinUsed) return;
    _activeInstance = this;
    _intPinUsed->onEdge(&Max17048Full::_edgeTrampoline, InputPin::kFalling);
}

void Max17048Full::offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&Max17048Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

uint16_t Max17048Full::pollInterrupt() {
    uint16_t flags = _read_word(REG_STATUS) & 0x3F00;
    if (flags) clear_status(flags);
    return flags;
}

void Max17048Full::_edgeTrampoline() {
    if (!_activeInstance) return;
    uint16_t status = _activeInstance->pollInterrupt();
    if (status && _activeInstance->_callback) _activeInstance->_callback(status);
}
