#include "AD7706.h"

// Out-of-class definitions for ODR-used static constexpr members; required
// before C++17 (AVR Arduino builds as C++11), redundant but valid after.
constexpr uint8_t _AD7706Base::_GAIN_BITS[8];
constexpr uint16_t _AD7706Base::_FS_RATES_1MHZ[4];
constexpr uint16_t _AD7706Base::_FS_RATES_2_4MHZ[4];

#ifdef __linux__
#include <time.h>
static void _delay_ns_linux(unsigned ns) {
    struct timespec ts = { 0, (long)(ns) };
    nanosleep(&ts, nullptr);
}
#define _delay_ns(ns) _delay_ns_linux(ns)
#elif defined(ARDUINO)
#include <Arduino.h>
static void _delay_ns_arduino(unsigned ns) {
    delayMicroseconds((ns + 999) / 1000);
}
#define _delay_ns(ns) _delay_ns_arduino(ns)
#elif defined(ESP_PLATFORM)
#include "freertos/FreeRTOS.h"
#include "rom/ets_sys.h"
#define _delay_ns(ns) ets_delay_us((ns + 999) / 1000)
#elif defined(PICO_SDK_VERSION_MAJOR)
#include "pico/stdlib.h"
#include "hardware/timer.h"
static void _delay_ns_pico(unsigned ns) {
    busy_wait_us((ns + 999) / 1000);
}
#define _delay_ns(ns) _delay_ns_pico(ns)
#elif defined(__ZEPHYR_SUPERVISOR__)
#include <zephyr/kernel.h>
#define _delay_ns(ns) k_busy_wait(ns)
#else
// Covers minimal-libc embedded configs that lack <chrono>/<ctime> — a raw
// nop busy-wait, matching the pattern in AD7705.cpp.
static void _delay_ns_host(unsigned ns) {
    volatile unsigned count = (ns / 10u) + 1u;
    while (count--) { __asm__ volatile("nop"); }
}
#define _delay_ns(ns) _delay_ns_host(ns)
#endif

_AD7706Base::_AD7706Base(Connection& connection, float vref, uint32_t mclk_hz, OutputPin* reset_pin)
    : _connection(connection), _reset_pin(reset_pin), _vref(vref), _mclk_hz(mclk_hz),
      _gain(1), _bipolar(true), _buffered(false) {
    if (mclk_hz != MCLK_1MHZ && mclk_hz != MCLK_2MHZ && mclk_hz != MCLK_2_4576MHZ && mclk_hz != MCLK_4_9152MHZ) {
        return;
    }
    if (_reset_pin) {
        _hardware_reset();
    }

    uint16_t default_rate = (mclk_hz >= MCLK_2_4576MHZ) ? _FS_RATES_2_4MHZ[0] : _FS_RATES_1MHZ[0];
    _configure_clock(default_rate);

    uint8_t setup = _MODE_SELF_CAL | _GAIN_BITS[0] | _BIPOLAR | _UNBUFFERED | _FSYNC_RUN;
    _write_reg_channel(_REG_SETUP, setup, _CH1, 1);
    _wait_drdy();
}

uint8_t _AD7706Base::_comm_byte(uint8_t reg, bool read, uint8_t channel) {
    return (uint8_t)(reg | (read ? _RW_READ : _RW_WRITE) | (channel & 0x03));
}

void _AD7706Base::_wait_drdy() {
    while (true) {
        uint8_t comm = _comm_byte(_REG_COMM, true, _CH1);
        uint8_t val = 0;
        _connection.write_read(&comm, 1, &val, 1);
        if (!(val & _DRDY_MASK)) {
            return;
        }
    }
}

void _AD7706Base::_hardware_reset() {
    _reset_pin->set(false);
    _delay_ns(200);
    _reset_pin->set(true);
}

void _AD7706Base::_configure_clock(uint16_t output_rate_hz) {
    uint8_t clk_bit = (_mclk_hz >= MCLK_2_4576MHZ) ? 0x04 : 0x00;
    uint8_t clkdiv_bit = (_mclk_hz == MCLK_2MHZ || _mclk_hz == MCLK_4_9152MHZ) ? 0x08 : 0x00;
    const uint16_t* rates = (_mclk_hz >= MCLK_2_4576MHZ) ? _FS_RATES_2_4MHZ : _FS_RATES_1MHZ;
    uint8_t fs_bits = 0xFF;
    for (uint8_t i = 0; i < 4; i++) {
        if (rates[i] == output_rate_hz) { fs_bits = i; break; }
    }
    _write_reg_channel(_REG_CLOCK, clkdiv_bit | clk_bit | fs_bits, _CH1, 1);
}

void _AD7706Base::_write_reg_channel(uint8_t reg, uint32_t value, uint8_t channel, uint8_t n_bytes) {
    uint8_t buf[4];
    buf[0] = _comm_byte(reg, false, channel);
    for (int8_t i = (int8_t)n_bytes - 1; i >= 0; i--) {
        buf[1 + (n_bytes - 1 - i)] = (uint8_t)((value >> (8 * i)) & 0xFF);
    }
    _connection.write(buf, 1 + n_bytes);
}

uint32_t _AD7706Base::_read_reg_channel(uint8_t reg, uint8_t channel, uint8_t n_bytes) {
    uint8_t comm = _comm_byte(reg, true, channel);
    uint8_t buf[3] = {0, 0, 0};
    _connection.write_read(&comm, 1, buf, n_bytes);
    uint32_t value = 0;
    for (uint8_t i = 0; i < n_bytes; i++) {
        value = (value << 8) | buf[i];
    }
    return value;
}

float _AD7706Base::_code_to_voltage(uint16_t code, uint8_t gain, bool bipolar) const {
    if (bipolar) {
        return (((float)(int32_t)((int16_t)code - 32768)) / 32768.0f) * (_vref / (float)gain);
    }
    return ((float)code / 65536.0f) * (_vref / (float)gain);
}

uint16_t AD7706Minimal::read_raw() {
    _wait_drdy();
    return (uint16_t)_read_reg_channel(_REG_DATA, _CH1, 2);
}

float AD7706Minimal::read_voltage() {
    uint16_t code = read_raw();
    return _code_to_voltage(code, _gain, _bipolar);
}

void AD7706Full::configure(uint8_t channel, uint8_t gain, bool bipolar, bool buffered, uint16_t output_rate_hz) {
    if (channel < 1 || channel > 3) return;
    if (gain < 1 || gain > 128) return;
    uint8_t ch = (channel == 1) ? _CH1 : (channel == 2) ? _CH2 : _CH3;

    const uint16_t* rates = (_mclk_hz >= MCLK_2_4576MHZ) ? _FS_RATES_2_4MHZ : _FS_RATES_1MHZ;
    bool rate_ok = false;
    for (uint8_t i = 0; i < 4; i++) {
        if (rates[i] == output_rate_hz) { rate_ok = true; break; }
    }
    if (!rate_ok) return;

    _configure_clock(output_rate_hz);

    uint8_t bu_bit = bipolar ? _BIPOLAR : _UNIPOLAR;
    uint8_t buf_bit = buffered ? _BUFFERED : _UNBUFFERED;
    uint8_t gain_idx = 0;
    switch (gain) {
        case 1:   gain_idx = 0; break;
        case 2:   gain_idx = 1; break;
        case 4:   gain_idx = 2; break;
        case 8:   gain_idx = 3; break;
        case 16:  gain_idx = 4; break;
        case 32:  gain_idx = 5; break;
        case 64:  gain_idx = 6; break;
        case 128: gain_idx = 7; break;
        default: return;
    }
    uint8_t setup = _MODE_NORMAL | _GAIN_BITS[gain_idx] | bu_bit | buf_bit | _FSYNC_RUN;
    _write_reg_channel(_REG_SETUP, setup, ch, 1);

    if (channel == 1) {
        _gain = gain;
        _bipolar = bipolar;
        _buffered = buffered;
    }
}

uint16_t AD7706Full::read_raw(uint8_t channel) {
    if (channel < 1 || channel > 3) return 0;
    uint8_t ch = (channel == 1) ? _CH1 : (channel == 2) ? _CH2 : _CH3;
    _wait_drdy();
    return (uint16_t)_read_reg_channel(_REG_DATA, ch, 2);
}

float AD7706Full::read_voltage(uint8_t channel) {
    uint16_t code = read_raw(channel);
    return _code_to_voltage(code, _gain, _bipolar);
}

void AD7706Full::self_calibrate(uint8_t channel) {
    if (channel < 1 || channel > 3) return;
    uint8_t ch = (channel == 1) ? _CH1 : (channel == 2) ? _CH2 : _CH3;
    uint8_t bu_bit = _bipolar ? _BIPOLAR : _UNIPOLAR;
    uint8_t buf_bit = _buffered ? _BUFFERED : _UNBUFFERED;
    uint8_t gain_idx = 0;
    switch (_gain) {
        case 1:   gain_idx = 0; break;
        case 2:   gain_idx = 1; break;
        case 4:   gain_idx = 2; break;
        case 8:   gain_idx = 3; break;
        case 16:  gain_idx = 4; break;
        case 32:  gain_idx = 5; break;
        case 64:  gain_idx = 6; break;
        case 128: gain_idx = 7; break;
        default: return;
    }
    uint8_t setup = _MODE_SELF_CAL | _GAIN_BITS[gain_idx] | bu_bit | buf_bit | _FSYNC_RUN;
    _write_reg_channel(_REG_SETUP, setup, ch, 1);
    _wait_drdy();
}

void AD7706Full::system_calibrate_zero(uint8_t channel) {
    if (channel < 1 || channel > 3) return;
    uint8_t ch = (channel == 1) ? _CH1 : (channel == 2) ? _CH2 : _CH3;
    uint8_t bu_bit = _bipolar ? _BIPOLAR : _UNIPOLAR;
    uint8_t buf_bit = _buffered ? _BUFFERED : _UNBUFFERED;
    uint8_t gain_idx = 0;
    switch (_gain) {
        case 1:   gain_idx = 0; break;
        case 2:   gain_idx = 1; break;
        case 4:   gain_idx = 2; break;
        case 8:   gain_idx = 3; break;
        case 16:  gain_idx = 4; break;
        case 32:  gain_idx = 5; break;
        case 64:  gain_idx = 6; break;
        case 128: gain_idx = 7; break;
        default: return;
    }
    uint8_t setup = _MODE_ZERO_SYS | _GAIN_BITS[gain_idx] | bu_bit | buf_bit | _FSYNC_RUN;
    _write_reg_channel(_REG_SETUP, setup, ch, 1);
    _wait_drdy();
}

void AD7706Full::system_calibrate_full(uint8_t channel) {
    if (channel < 1 || channel > 3) return;
    uint8_t ch = (channel == 1) ? _CH1 : (channel == 2) ? _CH2 : _CH3;
    uint8_t bu_bit = _bipolar ? _BIPOLAR : _UNIPOLAR;
    uint8_t buf_bit = _buffered ? _BUFFERED : _UNBUFFERED;
    uint8_t gain_idx = 0;
    switch (_gain) {
        case 1:   gain_idx = 0; break;
        case 2:   gain_idx = 1; break;
        case 4:   gain_idx = 2; break;
        case 8:   gain_idx = 3; break;
        case 16:  gain_idx = 4; break;
        case 32:  gain_idx = 5; break;
        case 64:  gain_idx = 6; break;
        case 128: gain_idx = 7; break;
        default: return;
    }
    uint8_t setup = _MODE_FULL_SYS | _GAIN_BITS[gain_idx] | bu_bit | buf_bit | _FSYNC_RUN;
    _write_reg_channel(_REG_SETUP, setup, ch, 1);
    _wait_drdy();
}

uint32_t AD7706Full::get_offset_calibration(uint8_t channel) {
    if (channel < 1 || channel > 3) return 0;
    uint8_t ch = (channel == 1) ? _CH1 : (channel == 2) ? _CH2 : _CH3;
    return _read_reg_channel(_REG_OFFSET, ch, 3);
}

void AD7706Full::set_offset_calibration(uint32_t value, uint8_t channel) {
    if (channel < 1 || channel > 3) return;
    uint8_t ch = (channel == 1) ? _CH1 : (channel == 2) ? _CH2 : _CH3;
    _write_reg_channel(_REG_OFFSET, value & 0xFFFFFF, ch, 3);
}

uint32_t AD7706Full::get_gain_calibration(uint8_t channel) {
    if (channel < 1 || channel > 3) return 0;
    uint8_t ch = (channel == 1) ? _CH1 : (channel == 2) ? _CH2 : _CH3;
    return _read_reg_channel(_REG_GAIN, ch, 3);
}

void AD7706Full::set_gain_calibration(uint32_t value, uint8_t channel) {
    if (channel < 1 || channel > 3) return;
    uint8_t ch = (channel == 1) ? _CH1 : (channel == 2) ? _CH2 : _CH3;
    _write_reg_channel(_REG_GAIN, value & 0xFFFFFF, ch, 3);
}

void AD7706Full::standby() {
    uint8_t comm = _comm_byte(_REG_COMM, false, _CH1) | _STBY_SLEEP;
    _connection.write(&comm, 1);
}

void AD7706Full::wakeup() {
    uint8_t comm = _comm_byte(_REG_COMM, false, _CH1) | _STBY_RUN;
    _connection.write(&comm, 1);
    _wait_drdy();
}

void AD7706Full::reset() {
    if (!_reset_pin) return;
    _hardware_reset();
}
