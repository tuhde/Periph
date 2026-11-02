#include "HY3131.h"
#include <math.h>

// Out-of-class definitions for ODR-used static constexpr members; required
// before C++17 (AVR Arduino builds as C++11), redundant but valid after.
constexpr uint8_t _HY3131Base::_REG_INTF;
constexpr uint8_t _HY3131Base::_REG_AD1;
constexpr int32_t HY3131Minimal::TIMEOUT;

#ifdef __linux__
#include <unistd.h>
static void periph_delay_ms(unsigned ms) { usleep(ms * 1000); }
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static void periph_delay_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static void periph_delay_ms(unsigned ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }
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

static const float AD1_GAINS[4] = {0.9f, 1.8f, 2.7f, 3.6f};
static const float DCOS_TABLE[8] = {0.0f, 0.25f, 0.5f, 0.75f, 0.0f, -0.25f, -0.5f, -0.75f};
static const unsigned TIMEOUT_MS = 2000;

void _HY3131Base::read_register(uint8_t reg, uint8_t* buf, uint8_t n) {
    uint8_t cmd = static_cast<uint8_t>(((reg & 0x7F) << 1) | 1);
    uint8_t rx[9] = {0};
    _connection.write_read(&cmd, 1, rx, static_cast<size_t>(n) + 1);
    for (uint8_t i = 0; i < n; i++) {
        buf[i] = static_cast<uint8_t>((rx[i] << 1) | (rx[i + 1] >> 7));
    }
}

void _HY3131Base::write_register(uint8_t reg, const uint8_t* data, uint8_t n) {
    uint8_t buf[9];
    buf[0] = static_cast<uint8_t>((reg & 0x7F) << 1);
    for (uint8_t i = 0; i < n; i++) buf[1 + i] = data[i];
    _connection.write(buf, static_cast<size_t>(n) + 1);
}

uint8_t _HY3131Base::_read1(uint8_t reg) {
    uint8_t b = 0;
    read_register(reg, &b, 1);
    return b;
}

void _HY3131Base::_init() {
    write_register(_REG_R37, 0x60);
    periph_delay_ms(5);
    write_register(_REG_R33, 0x28);
    periph_delay_ms(10);
    write_register(_REG_R31, 0xC0);
    write_register(_REG_R30, 0x00);
    periph_delay_ms(5);
    write_register(_REG_R2F, 0x80);
    const uint8_t zeros[5] = {0, 0, 0, 0, 0};
    write_register(_REG_R2A, zeros, 5);
    const uint8_t clear_regs[10] = {0x32, _REG_R20, 0x21, _REG_R34, _REG_R35, 0x36,
                                    _REG_R29, _REG_R26, _REG_R27, _REG_INTE};
    for (uint8_t i = 0; i < 10; i++) write_register(clear_regs[i], 0x00);
    write_register(_REG_R24, 0xA3);
    write_register(_REG_R28, 0x11);
    _ig = 0;
    _rg = 0;
    _dcos = 0.0f;
    write_register(_REG_R25, 0x00);
    write_register(_REG_R22, 0x07);
    write_register(_REG_R23, 0x80);
    _read1(_REG_INTF);
    _flags = 0;
}

bool _HY3131Base::_wait_flag(uint8_t mask) {
    for (unsigned waited = 0; waited <= TIMEOUT_MS; waited++) {
        _flags |= _read1(_REG_INTF);
        if (_flags & mask) {
            _flags &= static_cast<uint8_t>(~mask);
            return true;
        }
        periph_delay_ms(1);
    }
    return false;
}

int64_t _HY3131Base::_read24(uint8_t reg) {
    uint8_t b[3];
    read_register(reg, b, 3);
    return static_cast<int64_t>(b[0]) | (static_cast<int64_t>(b[1]) << 8) | (static_cast<int64_t>(b[2]) << 16);
}

int64_t _HY3131Base::_sext(int64_t value, int bits) {
    int64_t sign = static_cast<int64_t>(1) << (bits - 1);
    return (value & sign) ? value - (sign << 1) : value;
}

bool HY3131Minimal::data_ready() {
    _flags |= _read1(_REG_INTF);
    return (_flags & FLAG_AD1F) != 0;
}

int32_t HY3131Minimal::read_raw() {
    if (!_wait_flag(FLAG_AD1F)) return TIMEOUT;
    return static_cast<int32_t>(_sext(_read24(_REG_AD1), 24));
}

float HY3131Minimal::read_voltage() {
    int32_t code = read_raw();
    if (code == TIMEOUT) return NAN;
    double rg = _rg == 0 ? 1.0 : 0.3333333333;
    return static_cast<float>((static_cast<double>(code) / 8388608.0 * rg - _dcos) * _vref / AD1_GAINS[_ig]);
}

void HY3131Full::reset() { _init(); }

void HY3131Full::configure_ad1(uint8_t osr, uint8_t chop, uint8_t input_gain, uint8_t ref_gain,
                               uint8_t offset, uint8_t buffers) {
    uint8_t r25 = _read1(_REG_R25);
    write_register(_REG_R25, static_cast<uint8_t>((r25 & 0xCF) | ((input_gain & 3) << 4)));
    write_register(_REG_R22, static_cast<uint8_t>(((offset & 7) << 5) | ((chop & 3) << 3) | (osr & 7)));
    write_register(_REG_R23, static_cast<uint8_t>(0x80 | ((ref_gain & 1) << 4) | (buffers & 0x0F)));
    _ig = input_gain & 3;
    _rg = ref_gain & 1;
    _dcos = DCOS_TABLE[offset & 7];
    _read1(_REG_INTF);
    _flags = 0;
}

void HY3131Full::set_ad1_input(uint8_t pos, uint8_t neg, uint8_t filter_r) {
    write_register(_REG_R24, static_cast<uint8_t>(((pos & 15) << 4) | (neg & 7)));
    uint8_t r33 = _read1(_REG_R33);
    write_register(_REG_R33, static_cast<uint8_t>((r33 & 0xF3) | ((filter_r & 3) << 2)));
}

void HY3131Full::set_ad1_reference(uint8_t high, uint8_t low) {
    write_register(_REG_R28, static_cast<uint8_t>(((high & 7) << 4) | (low & 7)));
}

int32_t HY3131Full::read_temperature_code() {
    uint8_t r33 = _read1(_REG_R33);
    int32_t codes[2] = {0, 0};
    for (uint8_t i = 0; i < 2; i++) {
        write_register(_REG_R33, static_cast<uint8_t>((r33 & 0xFC) | (i + 2)));
        _read1(_REG_INTF);
        _flags = 0;
        _wait_flag(FLAG_AD1F);  // discard settling sample
        codes[i] = read_raw();
    }
    write_register(_REG_R33, r33);
    _flags = 0;
    return (codes[1] - codes[0]) / 2;
}

float HY3131Full::read_temperature(int32_t tcode_25) {
    const double t_os = -3.15;
    double g = static_cast<double>(tcode_25) / (25.0 + 273.15 + t_os);
    return static_cast<float>(static_cast<double>(read_temperature_code()) / g - 273.15 - t_os);
}

void HY3131Full::enable_ad2(uint8_t osr, uint8_t input_gain, uint8_t ref_gain, bool chop,
                            bool clk_div4, uint8_t source, bool use_ad3) {
    uint8_t r25 = _read1(_REG_R25);
    write_register(_REG_R25, static_cast<uint8_t>((r25 & 0x3F) | ((input_gain & 3) << 6)));
    write_register(_REG_R27, static_cast<uint8_t>(((source & 3) << 6) | 0x15));
    uint8_t chop_bit = chop ? 0x20 : 0;
    write_register(_REG_R26, static_cast<uint8_t>(0x80 | chop_bit | ((ref_gain & 1) << 4) |
                                                   (clk_div4 ? 0x08 : 0) | (osr & 7)));
    if (use_ad3) {
        write_register(_REG_R35, static_cast<uint8_t>(((source & 3) << 6) | (1 << 4) | ((input_gain & 3) << 2)));
        write_register(_REG_R34, static_cast<uint8_t>(0x80 | chop_bit | ((ref_gain & 1) << 4) | 0x04));
    } else {
        write_register(_REG_R34, 0x00);
    }
    _read1(_REG_INTF);
    _flags = 0;
}

int32_t HY3131Full::read_ad2_raw() {
    if (!_wait_flag(FLAG_AD2F)) return TIMEOUT;
    return static_cast<int32_t>(_sext(_read24(_REG_AD2), 24));
}

void HY3131Full::enable_lpf(uint8_t bw) {
    uint8_t r29 = _read1(_REG_R29);
    write_register(_REG_R29, static_cast<uint8_t>((r29 & 0x87) | 0x40 | ((bw & 7) << 3)));
}

int32_t HY3131Full::read_lpf_raw() {
    if (!_wait_flag(FLAG_LPFF)) return TIMEOUT;
    return static_cast<int32_t>(_sext(_read24(_REG_LPF), 24));
}

void HY3131Full::enable_rms() {
    write_register(_REG_R29, static_cast<uint8_t>(_read1(_REG_R29) | 0x80));
}

float HY3131Full::read_rms() {
    if (!_wait_flag(FLAG_RMSF)) return NAN;
    uint8_t b[5];
    read_register(_REG_RMS, b, 5);
    int64_t raw = 0;
    for (int i = 0; i < 5; i++) raw |= static_cast<int64_t>(b[i]) << (8 * i);
    int64_t v = _sext(raw, 40);
    if (v < 0) v = 0;
    return static_cast<float>(sqrt(static_cast<double>(v)));
}

void HY3131Full::enable_peak_hold(uint8_t source) {
    uint8_t r29 = _read1(_REG_R29);
    write_register(_REG_R29, static_cast<uint8_t>(r29 & ~0x07));
    write_register(_REG_R29, static_cast<uint8_t>((r29 & ~0x07) | 0x04 | (source & 3)));
}

void HY3131Full::read_peak(int32_t& min_code, int32_t& max_code) {
    min_code = static_cast<int32_t>(_sext(_read24(_REG_PKHMIN), 24));
    max_code = static_cast<int32_t>(_sext(_read24(_REG_PKHMAX), 24));
}

void HY3131Full::start_counter(uint16_t gate_preload) {
    uint8_t r20 = _read1(_REG_R20);
    write_register(_REG_R20, static_cast<uint8_t>(r20 & ~0x02));
    const uint8_t preload[2] = {static_cast<uint8_t>(gate_preload & 0xFF), static_cast<uint8_t>(gate_preload >> 8)};
    write_register(_REG_CTA + 1, preload, 2);
    _read1(_REG_INTF);
    _flags = 0;
    write_register(_REG_R20, static_cast<uint8_t>(r20 | 0x0A));
}

bool HY3131Full::read_counter(uint32_t& cta, uint32_t& ctb, uint32_t& ctc, bool& overflow) {
    if (!_wait_flag(FLAG_CTF)) return false;
    overflow = (_read1(_REG_CTSTA) & 0x01) != 0;
    cta = static_cast<uint32_t>(_read24(_REG_CTA));
    ctb = static_cast<uint32_t>(_read24(_REG_CTB));
    ctc = static_cast<uint32_t>(_read24(_REG_CTC));
    return true;
}

uint8_t HY3131Full::read_flags() {
    uint8_t f = static_cast<uint8_t>(_flags | _read1(_REG_INTF));
    _flags = 0;
    return f;
}

void HY3131Full::enable_interrupts(uint8_t mask) {
    write_register(_REG_INTE, static_cast<uint8_t>(mask & 0x1F));
}
