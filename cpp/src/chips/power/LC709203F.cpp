#include "LC709203F.h"
#include <math.h>

// Out-of-class definitions for ODR-used static constexpr members (AVR builds as C++11).
constexpr uint8_t  LC709203FMinimal::REG_VOLTAGE;
constexpr uint8_t  LC709203FMinimal::REG_ITE;
constexpr uint8_t  LC709203FMinimal::REG_TEMPERATURE;
constexpr uint16_t LC709203FFull::DIRECTION_AUTO;
constexpr uint16_t LC709203FFull::DIRECTION_CHARGE;
constexpr uint16_t LC709203FFull::DIRECTION_DISCHARGE;
constexpr uint8_t  LC709203FFull::ALARM_RSOC;
constexpr uint8_t  LC709203FFull::ALARM_VOLTAGE;

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

uint8_t lc709203f_crc8(const uint8_t* data, size_t length) {
    uint8_t crc = 0;
    for (size_t i = 0; i < length; i++) {
        crc ^= data[i];
        for (int b = 0; b < 8; b++) crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x07) : (uint8_t)(crc << 1);
    }
    return crc;
}

LC709203FMinimal::LC709203FMinimal(RegisterConnection& connection, uint16_t capacityMah, uint8_t profile)
    : _connection(connection) {
    _wake_and_operational();
    _write_word(REG_APA, apaFor(capacityMah));
    _write_word(REG_PROFILE, profile & 1);
    periph_delay_ms(100);  // TINIT: re-initialization like a battery insertion
    _wake_and_operational();
    _write_word(REG_STATUS, 0x0000);
    set_temperature(25.0f);
}

uint8_t LC709203FMinimal::apaFor(uint16_t capacityMah) {
    static const uint16_t cap[6] = {100, 200, 500, 1000, 2000, 3000};
    static const uint8_t  apa[6] = {0x08, 0x0B, 0x10, 0x19, 0x2D, 0x36};
    if (capacityMah <= cap[0]) return apa[0];
    if (capacityMah >= cap[5]) return apa[5];
    for (int i = 1; i < 6; i++) {
        if (capacityMah <= cap[i]) {
            float v = apa[i - 1] + (float)(apa[i] - apa[i - 1]) * (capacityMah - cap[i - 1]) / (float)(cap[i] - cap[i - 1]);
            return (uint8_t)lroundf(v);
        }
    }
    return apa[5];
}

void LC709203FMinimal::_wake_and_operational() {
    // Written twice: the first transfer may be lost while the chip wakes up.
    _write_word(REG_POWER_MODE, 0x0001);
    _write_word(REG_POWER_MODE, 0x0001);
    periph_delay_us(1000);
}

uint16_t LC709203FMinimal::_read_word(uint8_t cmd) {
    for (int attempt = 0; attempt < 2; attempt++) {
        uint8_t buf[3];
        _connection.read(cmd, buf, 3);
        if (!_connection.ok()) continue;
        uint8_t frame[5] = {0x16, cmd, 0x17, buf[0], buf[1]};
        if (lc709203f_crc8(frame, 5) == buf[2]) {
            _valid = true;
            return (uint16_t)(buf[0] | (buf[1] << 8));
        }
    }
    _valid = false;
    return 0;
}

void LC709203FMinimal::_write_word(uint8_t cmd, uint16_t value) {
    uint8_t low = value & 0xFF, high = value >> 8;
    uint8_t frame[4] = {0x16, cmd, low, high};
    uint8_t data[3] = {low, high, lc709203f_crc8(frame, 4)};
    _connection.write(cmd, data, 3);
    if (!_connection.ok()) _connection.write(cmd, data, 3);  // one retry on a bus error
}

float LC709203FMinimal::voltage() { return _read_word(REG_VOLTAGE) / 1000.0f; }

float LC709203FMinimal::percent() { return _read_word(REG_ITE) / 10.0f; }

float LC709203FMinimal::temperature() { return _read_word(REG_TEMPERATURE) / 10.0f - 273.15f; }

void LC709203FMinimal::set_temperature(float celsius) {
    if (celsius < -20.0f) celsius = -20.0f;
    if (celsius > 60.0f) celsius = 60.0f;
    _write_word(REG_TEMPERATURE, (uint16_t)lroundf((celsius + 273.15f) * 10.0f));
}

LC709203FFull* LC709203FFull::_activeInstance = nullptr;

LC709203FFull::LC709203FFull(RegisterConnection& connection, uint16_t capacityMah, uint8_t profile)
    : LC709203FMinimal(connection, capacityMah, profile) {
    _alarmRsoc = _read_word(REG_ALARM_RSOC);
    _alarmVoltage = _read_word(REG_ALARM_VOLTAGE);
}

uint8_t LC709203FFull::rsoc() { return (uint8_t)_read_word(REG_RSOC); }

void LC709203FFull::set_rsoc(uint8_t percent) { _write_word(REG_RSOC, percent > 100 ? 100 : percent); }

void LC709203FFull::init_rsoc() { _write_word(REG_INITIAL_RSOC, 0xAA55); periph_delay_ms(2); }

void LC709203FFull::before_rsoc() { _write_word(REG_BEFORE_RSOC, 0xAA55); periph_delay_ms(2); }

void LC709203FFull::use_thermistor(uint16_t bConstant) {
    _write_word(REG_THERMISTOR_B, bConstant);
    _write_word(REG_STATUS, 0x0001);
}

void LC709203FFull::use_i2c_temperature(float celsius) {
    _write_word(REG_STATUS, 0x0000);
    set_temperature(celsius);
}

bool LC709203FFull::is_thermistor_mode() { return _read_word(REG_STATUS) == 0x0001; }

uint16_t LC709203FFull::get_b_constant() { return _read_word(REG_THERMISTOR_B); }

void LC709203FFull::set_apa(uint8_t value) { _write_word(REG_APA, value); }

uint8_t LC709203FFull::get_apa() { return (uint8_t)_read_word(REG_APA); }

void LC709203FFull::set_apt(uint16_t value) { _write_word(REG_APT, value); }

uint16_t LC709203FFull::get_apt() { return _read_word(REG_APT); }

bool LC709203FFull::set_current_direction(uint16_t mode) {
    if (mode != DIRECTION_AUTO && mode != DIRECTION_CHARGE && mode != DIRECTION_DISCHARGE) return false;
    _write_word(REG_DIRECTION, mode);
    return true;
}

uint16_t LC709203FFull::get_current_direction() { return _read_word(REG_DIRECTION); }

void LC709203FFull::set_profile(uint8_t profile) {
    _write_word(REG_PROFILE, profile & 1);
    periph_delay_ms(100);
    _wake_and_operational();
}

uint8_t LC709203FFull::get_profile() { return (uint8_t)_read_word(REG_PROFILE); }

uint16_t LC709203FFull::profile_id() { return _read_word(REG_PROFILE_ID); }

uint16_t LC709203FFull::ic_version() { return _read_word(REG_IC_VERSION); }

void LC709203FFull::set_alarm_rsoc(uint8_t percent) {
    if (percent > 100) percent = 100;
    _write_word(REG_ALARM_RSOC, percent);
    _alarmRsoc = percent;
}

uint8_t LC709203FFull::get_alarm_rsoc() { return (uint8_t)_read_word(REG_ALARM_RSOC); }

void LC709203FFull::set_alarm_voltage(float volts) {
    long raw = lroundf(volts * 1000.0f);
    if (raw < 0) raw = 0;
    if (raw > 0xFFFF) raw = 0xFFFF;
    _write_word(REG_ALARM_VOLTAGE, (uint16_t)raw);
    _alarmVoltage = (uint16_t)raw;
}

float LC709203FFull::get_alarm_voltage() { return _read_word(REG_ALARM_VOLTAGE) / 1000.0f; }

void LC709203FFull::sleep() { _write_word(REG_POWER_MODE, 0x0002); }

void LC709203FFull::wake() { _wake_and_operational(); }

bool LC709203FFull::is_sleeping() {
    uint8_t dummy[1] = {0};
    _connection.write(REG_POWER_MODE, dummy, 0);  // command code only: wakes the bus, chip ignores it
    periph_delay_us(1000);
    return _read_word(REG_POWER_MODE) == 0x0002;
}

void LC709203FFull::onInterrupt(void (*callback)(uint8_t), InputPin* intPin) {
    _callback = callback;
    _intPinUsed = intPin ? intPin : _connection.intPin();
    if (!_intPinUsed) return;
    _activeInstance = this;
    _intPinUsed->onEdge(&LC709203FFull::_edgeTrampoline, InputPin::kFalling);
}

void LC709203FFull::offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&LC709203FFull::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

uint8_t LC709203FFull::pollInterrupt() {
    uint8_t status = 0;
    if (_alarmRsoc && rsoc() < _alarmRsoc) status |= ALARM_RSOC;
    if (_alarmVoltage && _read_word(REG_VOLTAGE) < _alarmVoltage) status |= ALARM_VOLTAGE;
    return status;
}

void LC709203FFull::_edgeTrampoline() {
    if (!_activeInstance) return;
    uint8_t status = _activeInstance->pollInterrupt();
    if (status && _activeInstance->_callback) _activeInstance->_callback(status);
}
