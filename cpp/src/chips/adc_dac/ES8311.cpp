#include "ES8311.h"

#include <stdlib.h>
#include <math.h>

#if defined(__linux__)
#include <unistd.h>
#define DELAY_MS(ms) usleep((ms) * 1000)
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
#define DELAY_MS(ms) k_sleep(K_MSEC(ms))
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#define DELAY_MS(ms) vTaskDelay(pdMS_TO_TICKS(ms))
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
#define DELAY_MS(ms) HAL_Delay(ms)
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
#define DELAY_MS(ms) sleep_ms(ms)
#else
#include <Arduino.h>
#define DELAY_MS(ms) delay(ms)
#endif

namespace {

struct ClockPreset { uint32_t mclk; uint32_t fs; uint8_t r02, r03, r04, r05; };

// Guide clock table (first set for the duplicated 12 MHz rows).
const ClockPreset PRESETS[] = {
    {24576000, 32000, 0x40, 0x10, 0x10, 0x00},
    {24576000, 48000, 0x20, 0x10, 0x10, 0x00},
    {12288000, 32000, 0x48, 0x10, 0x10, 0x00},
    {12288000, 48000, 0x00, 0x10, 0x10, 0x00},
    {18432000, 32000, 0x20, 0x12, 0x12, 0x00},
    {18432000, 48000, 0x48, 0x10, 0x10, 0x00},
    {11289600, 44100, 0x00, 0x10, 0x10, 0x00},
    {12000000, 32000, 0x00, 0x17, 0x17, 0x00},
    {12000000, 44100, 0x00, 0x11, 0x11, 0x00},
    {12000000, 48000, 0x98, 0x19, 0x19, 0x00},
    {26000000, 32000, 0x40, 0x10, 0x10, 0x00},
    {3000000,  32000, 0x18, 0x52, 0x17, 0x41},
    {25000000, 32000, 0x48, 0x55, 0x10, 0x21},
    {25000000, 48000, 0x28, 0x55, 0x10, 0x21},
};

const uint16_t PREFERRED_RATIOS[] = {256, 384, 512, 768, 1024, 1536};
const uint8_t BCLK_DIVIDERS[] = {22, 24, 25, 30, 32, 33, 34, 36, 44, 48, 66, 72};  // DIV_BCLK codes 20..31

const float ALC_LEVELS_DB[16] = {-30.1f, -24.1f, -20.6f, -18.1f, -16.1f, -14.5f, -13.2f, -12.0f,
                                 -11.0f, -10.1f, -9.3f, -8.5f, -7.8f, -7.2f, -6.6f, -6.0f};
const float NOISE_GATE_DB[16] = {-96, -90, -84, -78, -72, -66, -60, -54, -51, -48, -45, -42, -39, -36, -33, -30};

uint8_t nearest(const float* table, float value) {
    uint8_t best = 0;
    for (uint8_t i = 1; i < 16; i++) {
        if (fabsf(table[i] - value) < fabsf(table[best] - value)) best = i;
    }
    return best;
}

uint8_t clamp_u8(int v, int lo, int hi) {
    return static_cast<uint8_t>(v < lo ? lo : (v > hi ? hi : v));
}

}  // namespace

ES8311Minimal::ES8311Minimal(RegisterConnection& connection, uint32_t mclk_hz,
                             uint32_t sample_rate_hz, float dvdd_volts)
    : _connection(connection), _mclk_hz(mclk_hz), _fs(sample_rate_hz), _dvdd(dvdd_volts) {
    uint8_t clk[4];
    if (!_solve_clock(mclk_hz, sample_rate_hz, clk)) {
        // Repo convention for a failed construction (see MPU6050Minimal): the
        // Python port raises ValueError for an MCLK / fs pair without dividers.
        abort();
    }
    _soft_reset();
    _write_reg(REG_CLK1, 0x3F);
    _write_clock(clk);
    _write_reg(REG_CLK6, 0x03);
    _write_reg(REG_CLK7, 0x00);
    _write_reg(REG_CLK8, 0xFF);
    _write_reg(REG_SDP_IN, 0x0C);
    _write_reg(REG_SDP_OUT, 0x0C);
    _power_up_analog();
    _write_reg(REG_ADC_SCALE, 0x04);
    _write_reg(REG_SYS_MIC, 0x18);
    _write_reg(REG_ADC_VOLUME, 0xBF);
    _write_reg(REG_DAC_VOLUME, 0x00);
}

uint8_t ES8311Minimal::_read_reg(uint8_t reg) {
    uint8_t v = 0;
    _connection.read(reg, &v, 1);
    return v;
}

void ES8311Minimal::_write_reg(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
}

void ES8311Minimal::_update(uint8_t reg, uint8_t mask, uint8_t value) {
    _write_reg(reg, static_cast<uint8_t>((_read_reg(reg) & ~mask) | (value & mask)));
}

void ES8311Minimal::_soft_reset() {
    _write_reg(REG_RESET, 0x1F);
    DELAY_MS(20);
    _write_reg(REG_RESET, 0x00);
    _write_reg(REG_RESET, 0x80);
}

void ES8311Minimal::_write_clock(const uint8_t clk[4]) {
    _write_reg(REG_CLK2, clk[0]);
    _write_reg(REG_CLK3, clk[1]);
    _write_reg(REG_CLK4, clk[2]);
    _write_reg(REG_CLK5, clk[3]);
}

void ES8311Minimal::_power_up_analog() {
    _write_reg(REG_SYS_0B, 0x00);
    _write_reg(REG_SYS_0C, 0x00);
    _write_reg(REG_SYS_10, 0x1F);
    _write_reg(REG_SYS_11, 0x7F);
    _write_reg(REG_SYS_PWR, 0x01);
    _write_reg(REG_SYS_ADC, 0x02);
    _write_reg(REG_SYS_DAC, 0x00);
    _write_reg(REG_SYS_HP, 0x10);
    _write_reg(REG_ADC_HPF2_EQ, 0x6A);
    _write_reg(REG_DAC_RAMP_EQ, 0x08);
}

uint8_t ES8311Minimal::_volume_to_reg(float db) {
    return clamp_u8(static_cast<int>(lroundf(db / 0.5f)) + 191, 0, 255);
}

bool ES8311Minimal::_solve_clock(uint32_t mclk_hz, uint32_t fs, uint8_t out[4]) const {
    if (fs == 0) return false;
    const bool low = _dvdd < 2.5f;
    if (!low) {
        for (const ClockPreset& p : PRESETS) {
            if (p.mclk == mclk_hz && p.fs == fs) {
                out[0] = p.r02; out[1] = p.r03; out[2] = p.r04; out[3] = p.r05;
                return true;
            }
        }
    }
    const double pre_limit = low ? 4.5e6 : 10e6;
    const double clk_limit = low ? 13e6 : 35e6;
    bool found = false;
    int best_rank = 0, best_dp = 0, best_mult = 0, best_div = 0, best_ratio = 0, best_mi = 0;
    static const uint8_t MULTS[4] = {1, 2, 4, 8};
    for (int dp = 1; dp <= 8; dp++) {
        if (static_cast<double>(mclk_hz) / dp >= pre_limit) continue;
        for (int mi = 0; mi < 4; mi++) {
            const double imclk = static_cast<double>(mclk_hz) / dp * MULTS[mi];
            for (int div = 0; div < 16; div++) {
                const double clk = imclk / (div + 1);
                if (clk >= clk_limit) continue;
                const double ratio_f = clk / fs;
                const int ratio = static_cast<int>(ratio_f);
                if (ratio_f != ratio) continue;
                if (ratio % 16 != 0 || ratio < 256 || ratio > 1008) continue;
                int rank = 99;
                for (int i = 0; i < 6; i++) if (PREFERRED_RATIOS[i] == ratio) rank = i;
                bool better = !found || rank < best_rank ||
                    (rank == best_rank && (dp < best_dp || (dp == best_dp &&
                        (MULTS[mi] < best_mult || (MULTS[mi] == best_mult && div < best_div)))));
                if (better) {
                    found = true; best_rank = rank; best_dp = dp; best_mult = MULTS[mi];
                    best_div = div; best_ratio = ratio; best_mi = mi;
                }
            }
        }
    }
    if (!found) return false;
    out[0] = static_cast<uint8_t>(((best_dp - 1) << 5) | (best_mi << 3));
    out[1] = static_cast<uint8_t>(best_ratio / 16);
    out[2] = out[1];
    out[3] = static_cast<uint8_t>((best_div << 4) | best_div);
    return true;
}

void ES8311Minimal::set_volume(float db) {
    _write_reg(REG_DAC_VOLUME, _volume_to_reg(db));
}

void ES8311Minimal::set_mute(bool muted) {
    _update(REG_DAC_MUTE, 0x60, muted ? 0x60 : 0x00);
}

void ES8311Minimal::set_mic_gain(float db) {
    _update(REG_SYS_MIC, 0x0F, clamp_u8(static_cast<int>(lroundf(db / 3.0f)), 0, 10));
}

// ---------------------------------------------------------------------------

ES8311Full::ES8311Full(RegisterConnection& connection, uint32_t mclk_hz, uint32_t sample_rate_hz,
                       float dvdd_volts)
    : ES8311Minimal(connection, mclk_hz, sample_rate_hz, dvdd_volts) {}

float ES8311Full::get_volume() {
    return (static_cast<int>(_read_reg(REG_DAC_VOLUME)) - 191) * 0.5f;
}

float ES8311Full::get_mic_gain() {
    return (_read_reg(REG_SYS_MIC) & 0x0F) * 3.0f;
}

void ES8311Full::reset() {
    _soft_reset();
}

void ES8311Full::power_up() {
    _update(REG_RESET, 0x9F, 0x00);
    _update(REG_RESET, 0x80, 0x80);
    _write_reg(REG_CLK1, 0x3F);
    _power_up_analog();
}

void ES8311Full::power_down() {
    set_mute(true);
    _update(REG_SYS_PWR, 0xFC, 0xFC);
    _update(REG_SYS_ADC, 0x70, 0x70);
    _update(REG_SYS_PWR, 0x03, 0x00);
    _write_reg(REG_RESET, 0x1F);
}

void ES8311Full::set_low_power(bool dac, bool pga, bool pga_out, bool adc, bool adc_ref,
                               bool dac_ref, bool flash, bool integrator) {
    const bool flags[8] = {dac, pga, pga_out, adc, adc_ref, dac_ref, flash, integrator};
    uint8_t value = 0;
    for (uint8_t i = 0; i < 8; i++) if (flags[i]) value |= static_cast<uint8_t>(0x80 >> i);
    _write_reg(REG_SYS_LP, value);
}

bool ES8311Full::configure_clock(uint32_t mclk_hz, uint32_t sample_rate_hz, bool mclk_from_bclk,
                                 bool mclk_invert) {
    uint8_t clk[4];
    if (!_solve_clock(mclk_hz, sample_rate_hz, clk)) return false;
    _mclk_hz = mclk_hz;
    _fs = sample_rate_hz;
    _write_reg(REG_CLK1, static_cast<uint8_t>(0x3F | (mclk_from_bclk ? 0x80 : 0) | (mclk_invert ? 0x40 : 0)));
    _write_clock(clk);
    return true;
}

uint32_t ES8311Full::_internal_clock() {
    const uint8_t r02 = _read_reg(REG_CLK2);
    static const uint8_t MULTS[4] = {1, 2, 4, 8};
    return static_cast<uint32_t>(static_cast<double>(_mclk_hz) / (((r02 >> 5) & 7) + 1) * MULTS[(r02 >> 3) & 3]);
}

bool ES8311Full::configure_serial(uint8_t format, uint8_t word_length, bool master, bool bclk_invert,
                                  bool lrck_invert, bool dac_right, uint16_t bclk_div,
                                  uint16_t lrck_div, bool continuous_bclk) {
    if (format > FORMAT_DSP_B) return false;
    uint8_t wl;
    switch (word_length) {
        case 24: wl = 0; break;
        case 20: wl = 1; break;
        case 18: wl = 2; break;
        case 16: wl = 3; break;
        case 32: wl = 4; break;
        default: return false;
    }
    const uint8_t fmt = (format == FORMAT_I2S) ? 0 : (format == FORMAT_LEFT_JUSTIFIED ? 1 : 3);
    const uint8_t lrp = (lrck_invert ? 1 : 0) ^ (format == FORMAT_DSP_B ? 1 : 0);
    const uint8_t base = static_cast<uint8_t>((wl << 2) | fmt | (lrp << 5));
    uint8_t code = 0;
    if (master) {
        const uint32_t imclk = _internal_clock();
        if (bclk_div == 0) bclk_div = static_cast<uint16_t>(imclk / (_fs * 2u * word_length));
        if (lrck_div == 0) lrck_div = static_cast<uint16_t>(imclk / _fs);
        if (bclk_div == 0 || lrck_div == 0 || lrck_div > 4096) return false;
        if (bclk_div <= 20) {
            code = static_cast<uint8_t>(bclk_div - 1);
        } else {
            bool ok = false;
            for (uint8_t i = 0; i < 12; i++) {
                if (BCLK_DIVIDERS[i] == bclk_div) { code = static_cast<uint8_t>(20 + i); ok = true; }
            }
            if (!ok) return false;
        }
    }
    _write_reg(REG_SDP_IN, static_cast<uint8_t>(base | (dac_right ? 0x80 : 0x00) | (_read_reg(REG_SDP_IN) & 0x40)));
    _write_reg(REG_SDP_OUT, static_cast<uint8_t>(base | (_read_reg(REG_SDP_OUT) & 0x40)));
    if (master) {
        _write_reg(REG_CLK6, static_cast<uint8_t>((continuous_bclk ? 0x00 : 0x40) | (bclk_invert ? 0x20 : 0) | code));
        _update(REG_CLK7, 0x0F, static_cast<uint8_t>(((lrck_div - 1) >> 8) & 0x0F));
        _write_reg(REG_CLK8, static_cast<uint8_t>((lrck_div - 1) & 0xFF));
        _update(REG_RESET, 0x44, 0x40);
    } else {
        _update(REG_CLK6, 0x20, bclk_invert ? 0x20 : 0x00);
        _update(REG_RESET, 0x44, 0x04);
    }
    return true;
}

void ES8311Full::set_tristate(bool bclk_lrck, bool adcdat) {
    _update(REG_CLK7, 0x30, static_cast<uint8_t>((bclk_lrck ? 0x20 : 0) | (adcdat ? 0x10 : 0)));
}

bool ES8311Full::set_input(uint8_t source) {
    switch (source) {
        case INPUT_ANALOG:
            _update(REG_SYS_MIC, 0x70, 0x10);
            _update(REG_SYS_ADC, 0x40, 0x00);
            return true;
        case INPUT_DIGITAL:
            _update(REG_SYS_MIC, 0x70, 0x40);
            _update(REG_SYS_ADC, 0x40, 0x40);
            return true;
        case INPUT_NONE:
            _update(REG_SYS_MIC, 0x70, 0x00);
            _update(REG_SYS_ADC, 0x40, 0x40);
            return true;
        default:
            return false;
    }
}

void ES8311Full::configure_dmic(bool latch_falling_edge) {
    _update(REG_ADC_RAMP, 0x01, latch_falling_edge ? 0x01 : 0x00);
}

void ES8311Full::set_adc_volume(float db) {
    _write_reg(REG_ADC_VOLUME, _volume_to_reg(db));
}

bool ES8311Full::configure_adc(uint8_t scale_db, uint16_t osr, bool double_speed, bool hpf,
                               uint8_t hpf_coeff1, uint8_t hpf_coeff2) {
    const int code = double_speed ? osr / 2 : osr / 4;
    if (code < 15 || code > 63 || (double_speed && code < 16)) return false;
    _update(REG_ADC_SCALE, 0x07, clamp_u8(static_cast<int>(lroundf(scale_db / 6.0f)), 0, 7));
    _write_reg(REG_CLK3, static_cast<uint8_t>((double_speed ? 0x40 : 0x00) | code));
    _update(REG_ADC_HPF2_EQ, 0x3F, static_cast<uint8_t>((hpf ? 0x20 : 0x00) | (hpf_coeff2 & 0x1F)));
    _update(REG_ADC_HPF1, 0x1F, static_cast<uint8_t>(hpf_coeff1 & 0x1F));
    return true;
}

void ES8311Full::set_adc_ramp(uint8_t rate) {
    _update(REG_ADC_RAMP, 0xF0, static_cast<uint8_t>(clamp_u8(rate, 0, 15) << 4));
}

void ES8311Full::configure_alc(bool enabled, float max_level_db, float min_level_db, uint8_t window,
                               bool automute, uint8_t automute_window, float noise_gate_db) {
    _write_reg(REG_ADC_ALC1, static_cast<uint8_t>((enabled ? 0x80 : 0) | (automute ? 0x40 : 0) | (window & 0x0F)));
    _write_reg(REG_ADC_ALC2, static_cast<uint8_t>((nearest(ALC_LEVELS_DB, max_level_db) << 4) |
                                                   nearest(ALC_LEVELS_DB, min_level_db)));
    _write_reg(REG_ADC_AUTOMUTE, static_cast<uint8_t>(((automute_window & 0x0F) << 4) |
                                                       nearest(NOISE_GATE_DB, noise_gate_db)));
}

bool ES8311Full::_write_eq(uint8_t base, const uint32_t* coefficients, uint8_t count) {
    uint8_t data[20];
    for (uint8_t i = 0; i < count; i++) {
        const uint32_t c = coefficients[i];
        if (c >= (1UL << 30)) return false;
        data[4 * i] = static_cast<uint8_t>((c >> 24) & 0x3F);
        data[4 * i + 1] = static_cast<uint8_t>((c >> 16) & 0xFF);
        data[4 * i + 2] = static_cast<uint8_t>((c >> 8) & 0xFF);
        data[4 * i + 3] = static_cast<uint8_t>(c & 0xFF);
    }
    _connection.write(base, data, 4u * count);
    return true;
}

void ES8311Full::bypass_adc_eq() {
    _update(REG_ADC_HPF2_EQ, 0x40, 0x40);
}

bool ES8311Full::set_adc_eq(const uint32_t coefficients[5]) {
    if (!_write_eq(REG_ADCEQ, coefficients, 5)) return false;
    _update(REG_ADC_HPF2_EQ, 0x40, 0x00);
    return true;
}

void ES8311Full::set_dac_ramp(uint8_t rate) {
    _update(REG_DAC_RAMP_EQ, 0xF0, static_cast<uint8_t>(clamp_u8(rate, 0, 15) << 4));
}

void ES8311Full::configure_drc(bool enabled, float max_level_db, float min_level_db, uint8_t window) {
    _write_reg(REG_DAC_DRC1, static_cast<uint8_t>((enabled ? 0x80 : 0) | (window & 0x0F)));
    _write_reg(REG_DAC_DRC2, static_cast<uint8_t>((nearest(ALC_LEVELS_DB, max_level_db) << 4) |
                                                   nearest(ALC_LEVELS_DB, min_level_db)));
}

void ES8311Full::bypass_dac_eq() {
    _update(REG_DAC_RAMP_EQ, 0x08, 0x08);
}

bool ES8311Full::set_dac_eq(const uint32_t coefficients[3]) {
    if (!_write_eq(REG_DACEQ, coefficients, 3)) return false;
    _update(REG_DAC_RAMP_EQ, 0x08, 0x00);
    return true;
}

void ES8311Full::set_dac_mute_options(bool dsm, bool dem, bool dsm_target) {
    _update(REG_DAC_MUTE, 0xE0, static_cast<uint8_t>((dsm_target ? 0x80 : 0) | (dsm ? 0x40 : 0) | (dem ? 0x20 : 0)));
}

void ES8311Full::set_loopback(bool enabled) {
    _update(REG_GPIO, 0x80, enabled ? 0x80 : 0x00);
}

bool ES8311Full::set_adcdat_mode(uint8_t mode) {
    if (mode > 6 || mode == 3) return false;
    _update(REG_GPIO, 0x70, static_cast<uint8_t>(mode << 4));
    return true;
}

void ES8311Full::set_sdp_mute(bool input, bool output) {
    _update(REG_SDP_IN, 0x40, input ? 0x40 : 0x00);
    _update(REG_SDP_OUT, 0x40, output ? 0x40 : 0x00);
}

uint8_t ES8311Full::read_register(uint8_t reg) {
    return _read_reg(reg);
}

void ES8311Full::write_register(uint8_t reg, uint8_t value) {
    _write_reg(reg, value);
}
