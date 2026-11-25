#include "MMA8451Q.h"
#include <stdlib.h>
#include <math.h>
#include <string.h>

#ifdef ARDUINO
#include <Arduino.h>
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static inline void delay(unsigned long ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static inline void delay(unsigned long ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static inline void delay(unsigned long ms) { HAL_Delay(ms); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static inline void delay(unsigned long ms) { sleep_ms(ms); }
#else
#include <unistd.h>
static inline void delay(unsigned long ms) { usleep(ms * 1000UL); }
#endif

// Debounce step in ms, indexed by [ODR index][oversampling mode].
// ODR order: 800, 400, 200, 100, 50, 12.5, 6.25, 1.56 Hz.
// Mode order: normal, low-noise low-power, high resolution, low power.
// Datasheet tables 27, 38 and 43.
namespace {
struct StepTable { float odr_hz; float step[4]; };
const StepTable DEBOUNCE_TABLE[] = {
    { 800.0f,  { 1.25f,  1.25f,  1.25f,   1.25f } },
    { 400.0f,  { 2.5f,   2.5f,   2.5f,    2.5f  } },
    { 200.0f,  { 5.0f,   5.0f,   2.5f,    5.0f  } },
    { 100.0f,  { 10.0f,  10.0f,  2.5f,    10.0f } },
    { 50.0f,   { 20.0f,  20.0f,  2.5f,    20.0f } },
    { 12.5f,  { 20.0f,  80.0f,  2.5f,    80.0f } },
    { 6.25f,  { 20.0f,  80.0f,  2.5f,    160.0f } },
    { 1.56f,  { 20.0f,  80.0f,  2.5f,    160.0f } },
};

// Pulse time-limit step in ms (PULSE_TMLT), lpf enabled.
// Datasheet tables 50 / 51.
const StepTable PULSE_TMLT_LPF[] = {
    { 800.0f,  { 1.25f,  1.25f,  1.25f,   1.25f } },
    { 400.0f,  { 2.5f,   2.5f,   2.5f,    2.5f  } },
    { 200.0f,  { 5.0f,   5.0f,   2.5f,    5.0f  } },
    { 100.0f,  { 10.0f,  10.0f,  2.5f,    10.0f } },
    { 50.0f,   { 20.0f,  20.0f,  2.5f,    20.0f } },
    { 12.5f,  { 20.0f,  80.0f,  2.5f,    80.0f } },
    { 6.25f,  { 20.0f,  80.0f,  2.5f,    160.0f } },
    { 1.56f,  { 20.0f,  80.0f,  2.5f,    160.0f } },
};

// Pulse time-limit step in ms (PULSE_TMLT), lpf disabled.
const StepTable PULSE_TMLT_NO_LPF[] = {
    { 800.0f,  { 0.625f, 0.625f, 0.625f,  0.625f } },
    { 400.0f,  { 0.625f, 0.625f, 0.625f,  1.25f  } },
    { 200.0f,  { 1.25f,  1.25f,  0.625f,  2.5f   } },
    { 100.0f,  { 2.5f,   2.5f,   0.625f,  5.0f   } },
    { 50.0f,   { 5.0f,   5.0f,   0.625f,  10.0f  } },
    { 12.5f,  { 5.0f,   20.0f,  0.625f,  40.0f  } },
    { 6.25f,  { 5.0f,   20.0f,  0.625f,  40.0f  } },
    { 1.56f,  { 5.0f,   20.0f,  0.625f,  40.0f  } },
};

// Pulse latency / window step in ms (PULSE_LTCY, PULSE_WIND), lpf enabled.
// Datasheet tables 53 / 54 (equal to 56 / 57).
const StepTable PULSE_LTCY_LPF[] = {
    { 800.0f,  { 2.5f,   2.5f,   2.5f,    2.5f  } },
    { 400.0f,  { 5.0f,   5.0f,   5.0f,    5.0f  } },
    { 200.0f,  { 10.0f,  10.0f,  5.0f,    10.0f } },
    { 100.0f,  { 20.0f,  20.0f,  5.0f,    20.0f } },
    { 50.0f,   { 40.0f,  40.0f,  5.0f,    40.0f } },
    { 12.5f,  { 40.0f,  160.0f, 5.0f,    160.0f } },
    { 6.25f,  { 40.0f,  160.0f, 5.0f,    320.0f } },
    { 1.56f,  { 40.0f,  160.0f, 5.0f,    320.0f } },
};

// Pulse latency / window step in ms (PULSE_LTCY, PULSE_WIND), lpf disabled.
const StepTable PULSE_LTCY_NO_LPF[] = {
    { 800.0f,  { 1.25f,  1.25f,  1.25f,   1.25f } },
    { 400.0f,  { 1.25f,  1.25f,  1.25f,   2.5f  } },
    { 200.0f,  { 2.5f,   2.5f,   1.25f,   5.0f  } },
    { 100.0f,  { 5.0f,   5.0f,   1.25f,   10.0f } },
    { 50.0f,   { 10.0f,  10.0f,  1.25f,   20.0f } },
    { 12.5f,  { 10.0f,  40.0f,  1.25f,   80.0f } },
    { 6.25f,  { 10.0f,  40.0f,  1.25f,   80.0f } },
    { 1.56f,  { 10.0f,  40.0f,  1.25f,   80.0f } },
};

// Output data rates, in the CTRL_REG1.DR code order.
const float ODR_RATES[8] = { 800.0f, 400.0f, 200.0f, 100.0f, 50.0f, 12.5f, 6.25f, 1.56f };

// ASLP_RATE codes (CTRL_REG1 bits 7:6) and their rates.
const float ASLP_RATES[4] = { 50.0f, 12.5f, 6.25f, 1.56f };

// Orientation trip angles (datasheet table 32) and their P_L_THS codes.
struct ThsEntry { uint8_t degrees; uint8_t code; };
const ThsEntry ORIENTATION_THS[] = {
    { 15, 0x07 }, { 20, 0x09 }, { 30, 0x0C }, { 35, 0x0D }, { 40, 0x0F },
    { 45, 0x10 }, { 55, 0x13 }, { 60, 0x14 }, { 70, 0x17 }, { 75, 0x19 },
};

// Find the table row matching the current ODR.
int stepTableIndex(float odr_hz) {
    int best = 0;
    float best_diff = 1e9f;
    for (int i = 0; i < 8; i++) {
        float diff = ODR_RATES[i] - odr_hz;
        if (diff < 0) diff = -diff;
        if (diff < best_diff) {
            best_diff = diff;
            best = i;
        }
    }
    return best;
}

uint8_t clampU8(int value) {
    if (value < 0) return 0;
    if (value > 255) return 255;
    return (uint8_t)value;
}

int16_t clampI8(int value) {
    if (value < -128) return -128;
    if (value > 127) return 127;
    return (int16_t)value;
}
}  // namespace

// MMA8451QMinimal

MMA8451QMinimal::MMA8451QMinimal(RegisterConnection& connection)
    : _connection(connection) {
    // 1. Wait for the power-up: I²C is ready ~350 us (500 us max) after VDDIO.
    _delay_ms(1);

    // 2. Verify WHO_AM_I.
    if (_read_reg(REG_WHO_AM_I) != WHO_AM_I_VALUE) {
        // Identity check failed — wrong chip / wrong address / wiring problem.
        // abort() rather than throwing: exceptions are disabled per platform
        // convention in this repo.
        abort();
    }

    // 3. Software reset. The I²C interface is reset by it, so the write may
    //    complete without ACK; wait >= 1 ms and re-read until CTRL_REG2
    //    returns 0 (retry up to 10 ms).
    _write_reg(REG_CTRL_REG2, CTRL2_RST);
    _delay_ms(1);
    for (int i = 0; i < 10; i++) {
        if (_read_reg(REG_CTRL_REG2) == 0) break;
        _delay_ms(1);
    }

    // 4.–6. The chip is in STANDBY with all registers at their reset values.
    _write_reg(REG_XYZ_DATA_CFG, 0x00);  // ±2 g, no high-pass output
    _write_reg(REG_CTRL_REG2, 0x00);     // normal oversampling, Auto-SLEEP off
    _write_reg(REG_CTRL_REG1, 0x19);     // DR = 011 (100 Hz), ACTIVE = 1

    // 7. Wait for the STANDBY → ACTIVE turn-on: 2/ODR + 2 ms.
    _delay_ms(_turn_on_ms());

    // Cached defaults after init.
    _range_g = 2;
    _odr_hz = 100.0f;
    _oversample = 0;
    _sleep_oversample = 0;
    _low_noise = false;
    _fast_read = false;
    _fifo_mode = FIFO_DISABLED;
}

void MMA8451QMinimal::_write_reg(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
}

uint8_t MMA8451QMinimal::_read_reg(uint8_t reg) {
    uint8_t v = 0;
    _connection.read(reg, &v, 1);
    return v;
}

void MMA8451QMinimal::_read_burst(uint8_t reg, uint8_t* buf, size_t len) {
    _connection.read(reg, buf, len);
}

void MMA8451QMinimal::_delay_ms(uint32_t ms) {
    delay(ms);
}

float MMA8451QMinimal::_scale() const {
    if (_fast_read) {
        switch (_range_g) {
            case 4: return SCALE_4G_8BIT;
            case 8: return SCALE_8G_8BIT;
            case 2:
            default: return SCALE_2G_8BIT;
        }
    }
    switch (_range_g) {
        case 4: return SCALE_4G_14BIT;
        case 8: return SCALE_8G_14BIT;
        case 2:
        default: return SCALE_2G_14BIT;
    }
}

uint32_t MMA8451QMinimal::_turn_on_ms() const {
    // 2/ODR + 2 ms.
    float turn_on = 2000.0f / _odr_hz + 2.0f;
    uint32_t ms = (uint32_t)(turn_on + 0.999f);
    return ms < 1 ? 1 : ms;
}

int16_t MMA8451QMinimal::_decode_14bit(uint8_t msb, uint8_t lsb) {
    // The 14-bit value is left-justified in the MSB:LSB pair; bits 1:0 of
    // the LSB register are always zero. Sign-extend the 16-bit pair first,
    // then shift right by two (an arithmetic shift).
    uint16_t raw = (uint16_t)(((uint16_t)msb << 8) | lsb);
    return (int16_t)(toSigned(raw, 16) >> 2);
}

int8_t MMA8451QMinimal::_decode_8bit(uint8_t msb) {
    return (int8_t)msb;
}

void MMA8451QMinimal::_read_counts(int16_t& x, int16_t& y, int16_t& z) {
    if (_fast_read) {
        uint8_t raw[3];
        _read_burst(REG_OUT_X_MSB, raw, 3);
        x = _decode_8bit(raw[0]);
        y = _decode_8bit(raw[1]);
        z = _decode_8bit(raw[2]);
    } else {
        uint8_t raw[6];
        _read_burst(REG_OUT_X_MSB, raw, 6);
        x = _decode_14bit(raw[0], raw[1]);
        y = _decode_14bit(raw[2], raw[3]);
        z = _decode_14bit(raw[4], raw[5]);
    }
}

void MMA8451QMinimal::read(float& x, float& y, float& z) {
    int16_t rx, ry, rz;
    _read_counts(rx, ry, rz);
    float scale = _scale();
    x = (float)rx / scale;
    y = (float)ry / scale;
    z = (float)rz / scale;
}

// MMA8451QFull

MMA8451QFull* MMA8451QFull::_activeInstance = nullptr;

MMA8451QFull::MMA8451QFull(RegisterConnection& connection)
    : MMA8451QMinimal(connection) {}

float MMA8451QFull::_debounce_step_ms() const {
    int idx = stepTableIndex(_odr_hz);
    uint8_t mode = _oversample & 0x03;
    return DEBOUNCE_TABLE[idx].step[mode];
}

float MMA8451QFull::_pulse_ttmlt_step_ms() const {
    int idx = stepTableIndex(_odr_hz);
    uint8_t mode = _oversample & 0x03;
    return PULSE_TMLT_LPF[idx].step[mode];
}

float MMA8451QFull::_pulse_ltcy_step_ms() const {
    int idx = stepTableIndex(_odr_hz);
    uint8_t mode = _oversample & 0x03;
    return PULSE_LTCY_LPF[idx].step[mode];
}

uint8_t MMA8451QFull::_threshold_code(float threshold_g) {
    if (threshold_g < 0.0f) threshold_g = 0.0f;
    int code = (int)lroundf(threshold_g / THRESHOLD_STEP_G);
    if (code < 0) code = 0;
    if (code > 127) code = 127;
    return (uint8_t)code;
}

uint8_t MMA8451QFull::_offset_code(float offset_g) {
    int code = (int)lroundf(offset_g / OFFSET_STEP_G);
    code = (int)clampI8(code);
    return (uint8_t)(code & 0xFF);
}

uint8_t MMA8451QFull::_debounce_count(float debounce_ms) const {
    float step = _debounce_step_ms();
    if (step <= 0.0f) return 0;
    int count = (int)lroundf(debounce_ms / step);
    return clampU8(count);
}

uint8_t MMA8451QFull::_pulse_count(float time_ms, float step_ms) const {
    if (step_ms <= 0.0f) return 0;
    int count = (int)lroundf(time_ms / step_ms);
    return clampU8(count);
}

uint8_t MMA8451QFull::_orientation_ths_code(uint8_t degrees) {
    for (unsigned i = 0; i < sizeof(ORIENTATION_THS) / sizeof(ORIENTATION_THS[0]); i++) {
        if (ORIENTATION_THS[i].degrees == degrees) return ORIENTATION_THS[i].code;
    }
    return 0xFF;
}

void MMA8451QFull::_write_standby_reg(uint8_t reg, uint8_t value) {
    _write_standby_regs(&reg, &value, 1);
}

void MMA8451QFull::_write_standby_regs(const uint8_t* regs, const uint8_t* values, uint8_t count) {
    uint8_t ctrl1 = _read_reg(REG_CTRL_REG1);
    bool was_active = (ctrl1 & CTRL1_ACTIVE) != 0;
    if (was_active) {
        // Clear ACTIVE, then write the target register(s) in STANDBY.
        _write_reg(REG_CTRL_REG1, (uint8_t)(ctrl1 & (uint8_t)~CTRL1_ACTIVE));
    }
    bool touches_ctrl1 = false;
    for (uint8_t i = 0; i < count; i++) {
        _write_reg(regs[i], values[i]);
        if (regs[i] == REG_CTRL_REG1) touches_ctrl1 = true;
    }
    if (was_active) {
        // Restore ACTIVE. When CTRL_REG1 was one of the targets, the value
        // written above already carries the new configuration — read it back
        // instead of the pre-write snapshot, which would undo the change.
        uint8_t restore = touches_ctrl1 ? _read_reg(REG_CTRL_REG1) : ctrl1;
        _write_reg(REG_CTRL_REG1, (uint8_t)(restore | CTRL1_ACTIVE));
        // Wait for the STANDBY → ACTIVE turn-on.
        _delay_ms(_turn_on_ms());
    }
}

void MMA8451QFull::set_range(uint8_t range_g) {
    uint8_t fs;
    switch (range_g) {
        case 4: fs = 0x01; break;
        case 8: fs = 0x02; break;
        case 2:
        default: fs = 0x00; range_g = 2; break;
    }
    if (range_g == 8 && _low_noise) {
        // Low-noise mode limits the dynamic range to ±4 g — refuse the change.
        return;
    }
    uint8_t cfg = _read_reg(REG_XYZ_DATA_CFG);
    cfg = (uint8_t)((cfg & (uint8_t)~0x03) | fs);
    _write_standby_reg(REG_XYZ_DATA_CFG, cfg);
    _range_g = range_g;
}

void MMA8451QFull::set_data_rate(float rate_hz) {
    uint8_t code = 0xFF;
    for (uint8_t i = 0; i < 8; i++) {
        float diff = ODR_RATES[i] - rate_hz;
        if (diff < 0) diff = -diff;
        if (diff < 0.001f) {
            code = i;
            break;
        }
    }
    if (code == 0xFF) return;  // unsupported rate — ignore

    uint8_t ctrl1 = _read_reg(REG_CTRL_REG1);
    ctrl1 = (uint8_t)((ctrl1 & (uint8_t)~CTRL1_DR_MASK) | (uint8_t)(code << CTRL1_DR_SHIFT));
    _write_standby_reg(REG_CTRL_REG1, ctrl1);
    _odr_hz = ODR_RATES[code];
}

void MMA8451QFull::set_oversampling(uint8_t mode, int sleep_mode) {
    if (mode > 3) return;
    uint8_t ctrl2 = _read_reg(REG_CTRL_REG2);
    ctrl2 = (uint8_t)((ctrl2 & (uint8_t)~CTRL2_MODS_MASK) | (uint8_t)(mode & 0x03));
    if (sleep_mode >= 0 && sleep_mode <= 3) {
        ctrl2 = (uint8_t)((ctrl2 & (uint8_t)~CTRL2_SMODS_MASK) |
                          (uint8_t)(((uint8_t)sleep_mode & 0x03) << CTRL2_SMODS_SHIFT));
        _sleep_oversample = (uint8_t)sleep_mode;
    }
    _write_standby_reg(REG_CTRL_REG2, ctrl2);
    _oversample = mode & 0x03;
}

void MMA8451QFull::set_low_noise(bool enabled) {
    if (enabled && _range_g == 8) {
        // Low-noise mode limits the dynamic range to ±4 g — refuse.
        return;
    }
    uint8_t ctrl1 = _read_reg(REG_CTRL_REG1);
    if (enabled) ctrl1 |= CTRL1_LNOISE;
    else        ctrl1 = (uint8_t)(ctrl1 & (uint8_t)~CTRL1_LNOISE);
    _write_standby_reg(REG_CTRL_REG1, ctrl1);
    _low_noise = enabled;
}

void MMA8451QFull::set_resolution(uint8_t bits) {
    if (bits != 8 && bits != 14) return;
    if (_fifo_mode != FIFO_DISABLED) {
        // F_READ may only change while F_MODE = 0.
        return;
    }
    uint8_t ctrl1 = _read_reg(REG_CTRL_REG1);
    if (bits == 8) ctrl1 |= CTRL1_F_READ;
    else           ctrl1 = (uint8_t)(ctrl1 & (uint8_t)~CTRL1_F_READ);
    _write_standby_reg(REG_CTRL_REG1, ctrl1);
    _fast_read = (bits == 8);
}

void MMA8451QFull::standby() {
    uint8_t ctrl1 = _read_reg(REG_CTRL_REG1);
    if ((ctrl1 & CTRL1_ACTIVE) == 0) return;
    _write_reg(REG_CTRL_REG1, (uint8_t)(ctrl1 & (uint8_t)~CTRL1_ACTIVE));
}

void MMA8451QFull::activate() {
    uint8_t ctrl1 = _read_reg(REG_CTRL_REG1);
    if (ctrl1 & CTRL1_ACTIVE) return;
    _write_reg(REG_CTRL_REG1, (uint8_t)(ctrl1 | CTRL1_ACTIVE));
    _delay_ms(_turn_on_ms());
}

void MMA8451QFull::reset() {
    _write_reg(REG_CTRL_REG2, CTRL2_RST);
    _delay_ms(1);
    for (int i = 0; i < 10; i++) {
        if (_read_reg(REG_CTRL_REG2) == 0) break;
        _delay_ms(1);
    }
    // The chip is now in STANDBY with all registers at their reset values.
    _range_g = 2;
    _odr_hz = 100.0f;
    _oversample = 0;
    _sleep_oversample = 0;
    _low_noise = false;
    _fast_read = false;
    _fifo_mode = FIFO_DISABLED;
}

void MMA8451QFull::read_raw(int16_t& x, int16_t& y, int16_t& z) {
    _read_counts(x, y, z);
}

bool MMA8451QFull::new_data_available() {
    return (_read_reg(REG_STATUS) & STATUS_ZYXDR) != 0;
}

uint8_t MMA8451QFull::read_status() {
    return _read_reg(REG_STATUS);
}

uint8_t MMA8451QFull::read_system_mode() {
    return (uint8_t)(_read_reg(REG_SYSMOD) & 0x03);
}

void MMA8451QFull::set_high_pass_output(bool enabled, uint8_t cutoff) {
    uint8_t cfg = _read_reg(REG_XYZ_DATA_CFG);
    if (enabled) cfg |= 0x10;
    else        cfg = (uint8_t)(cfg & (uint8_t)~0x10);
    _write_standby_reg(REG_XYZ_DATA_CFG, cfg);

    uint8_t hpf = _read_reg(REG_HP_FILTER);
    hpf = (uint8_t)((hpf & (uint8_t)~0x03) | (cutoff & 0x03));
    _write_standby_reg(REG_HP_FILTER, hpf);
}

void MMA8451QFull::set_offset(float x_g, float y_g, float z_g) {
    uint8_t regs[3]  = { REG_OFF_X, REG_OFF_Y, REG_OFF_Z };
    uint8_t values[3] = { _offset_code(x_g), _offset_code(y_g), _offset_code(z_g) };
    _write_standby_regs(regs, values, 3);
}

void MMA8451QFull::get_offset(float& x, float& y, float& z) {
    int16_t ox = (int16_t)toSigned(_read_reg(REG_OFF_X), 8);
    int16_t oy = (int16_t)toSigned(_read_reg(REG_OFF_Y), 8);
    int16_t oz = (int16_t)toSigned(_read_reg(REG_OFF_Z), 8);
    x = (float)ox * OFFSET_STEP_G;
    y = (float)oy * OFFSET_STEP_G;
    z = (float)oz * OFFSET_STEP_G;
}

void MMA8451QFull::set_fifo(uint8_t mode, uint8_t watermark) {
    if (mode > 3) return;
    if (_fifo_mode != FIFO_DISABLED && mode != FIFO_DISABLED && _fifo_mode != mode) {
        // Changing between two enabled modes must go through the disabled
        // mode (the datasheet requires F_MODE to pass through 00).
        _write_standby_reg(REG_F_SETUP, 0x00);
        _fifo_mode = FIFO_DISABLED;
    }
    uint8_t setup = (uint8_t)(((mode & 0x03) << 6) | (watermark & 0x3F));
    _write_standby_reg(REG_F_SETUP, setup);
    _fifo_mode = mode & 0x03;
}

void MMA8451QFull::set_fifo_trigger(bool transient, bool orientation, bool pulse, bool motion) {
    uint8_t trig = 0;
    if (transient)  trig |= 0x20;
    if (orientation) trig |= 0x10;
    if (pulse)      trig |= 0x08;
    if (motion)     trig |= 0x04;
    _write_standby_reg(REG_TRIG_CFG, trig);
}

uint8_t MMA8451QFull::fifo_level() {
    return (uint8_t)(_read_reg(REG_STATUS) & 0x3F);
}

void MMA8451QFull::read_fifo_status(bool& overflow, bool& watermark, uint8_t& count) {
    uint8_t status = _read_reg(REG_STATUS);
    overflow  = (status & 0x80) != 0;
    watermark = (status & 0x40) != 0;
    count     = (uint8_t)(status & 0x3F);
}

uint8_t MMA8451QFull::read_fifo(float* out, uint8_t max_in) {
    if (!out || max_in == 0) return 0;
    uint8_t status = _read_reg(REG_STATUS);
    uint8_t available = (uint8_t)(status & 0x3F);
    if (available > FIFO_CAPACITY) available = FIFO_CAPACITY;
    uint8_t count = available < max_in ? available : max_in;
    float scale = _scale();
    for (uint8_t i = 0; i < count; i++) {
        // One sample per transaction starting at OUT_X_MSB: the driver never
        // relies on the auto-increment pointer across samples.
        uint8_t raw[6];
        if (_fast_read) {
            _read_burst(REG_OUT_X_MSB, raw, 3);
            out[i * 3 + 0] = (float)_decode_8bit(raw[0]) / scale;
            out[i * 3 + 1] = (float)_decode_8bit(raw[1]) / scale;
            out[i * 3 + 2] = (float)_decode_8bit(raw[2]) / scale;
        } else {
            _read_burst(REG_OUT_X_MSB, raw, 6);
            out[i * 3 + 0] = (float)_decode_14bit(raw[0], raw[1]) / scale;
            out[i * 3 + 1] = (float)_decode_14bit(raw[2], raw[3]) / scale;
            out[i * 3 + 2] = (float)_decode_14bit(raw[4], raw[5]) / scale;
        }
    }
    return count;
}

void MMA8451QFull::set_freefall_motion(bool motion, uint8_t axes, float threshold_g,
                                       float debounce_ms, bool latch, bool debounce_clear) {
    uint8_t cfg = 0;
    if (latch) cfg |= 0x80;                       // ELE
    if (motion) cfg |= 0x40;                      // OAE
    if (axes & 0x04) cfg |= 0x20;                 // ZEFE
    if (axes & 0x02) cfg |= 0x10;                 // YEFE
    if (axes & 0x01) cfg |= 0x08;                 // XEFE
    _write_standby_reg(REG_FF_MT_CFG, cfg);

    uint8_t ths = _threshold_code(threshold_g);
    if (debounce_clear) ths |= 0x80;              // DBCNTM
    // FF_MT_THS and FF_MT_COUNT are writable in ACTIVE mode (marked "A").
    _write_reg(REG_FF_MT_THS, ths);
    _write_reg(REG_FF_MT_COUNT, _debounce_count(debounce_ms));
}

uint8_t MMA8451QFull::read_motion_source() {
    return _read_reg(REG_FF_MT_SRC);
}

void MMA8451QFull::set_transient(uint8_t axes, float threshold_g, float debounce_ms,
                                 bool bypass_hpf, bool latch) {
    uint8_t cfg = 0;
    if (latch) cfg |= 0x10;                       // ELE
    if (axes & 0x04) cfg |= 0x08;                 // ZTEFE
    if (axes & 0x02) cfg |= 0x04;                 // YTEFE
    if (axes & 0x01) cfg |= 0x02;                 // XTEFE
    if (bypass_hpf) cfg |= 0x01;                  // HPF_BYP
    _write_standby_reg(REG_TRANSIENT_CFG, cfg);

    uint8_t ths = _threshold_code(threshold_g);
    _write_reg(REG_TRANSIENT_THS, ths);
    _write_reg(REG_TRANSIENT_COUNT, _debounce_count(debounce_ms));
}

uint8_t MMA8451QFull::read_transient_source() {
    return _read_reg(REG_TRANSIENT_SRC);
}

void MMA8451QFull::set_pulse(uint8_t single, uint8_t double_tap,
                             float threshold_x_g, float threshold_y_g, float threshold_z_g,
                             float time_limit_ms, float latency_ms, float window_ms,
                             bool latch, bool double_abort, bool lpf, bool hpf) {
    uint8_t cfg = 0;
    if (double_abort) cfg |= 0x80;                // DPA
    if (latch) cfg |= 0x40;                       // ELE
    if (double_tap & 0x04) cfg |= 0x20;           // ZDPEFE
    if (single    & 0x04) cfg |= 0x10;           // ZSPEFE
    if (double_tap & 0x02) cfg |= 0x08;           // YDPEFE
    if (single    & 0x02) cfg |= 0x04;           // YSPEFE
    if (double_tap & 0x01) cfg |= 0x02;           // XDPEFE
    if (single    & 0x01) cfg |= 0x01;           // XSPEFE
    _write_standby_reg(REG_PULSE_CFG, cfg);

    // PULSE_THSX / PULSE_THSY are writable in ACTIVE mode; PULSE_THSZ is
    // STANDBY-only, so write all three in one mode switch.
    uint8_t regs[3]  = { REG_PULSE_THSX, REG_PULSE_THSY, REG_PULSE_THSZ };
    uint8_t values[3] = { _threshold_code(threshold_x_g),
                          _threshold_code(threshold_y_g),
                          _threshold_code(threshold_z_g) };
    _write_standby_regs(regs, values, 3);

    // The pulse time steps depend on the low-pass filter setting, so pick
    // the matching table row.
    int idx = stepTableIndex(_odr_hz);
    uint8_t mode = _oversample & 0x03;
    float tmlt_step = lpf ? PULSE_TMLT_LPF[idx].step[mode] : PULSE_TMLT_NO_LPF[idx].step[mode];
    float ltcy_step = lpf ? PULSE_LTCY_LPF[idx].step[mode] : PULSE_LTCY_NO_LPF[idx].step[mode];

    uint8_t timing[3];
    timing[0] = _pulse_count(time_limit_ms, tmlt_step);   // PULSE_TMLT
    timing[1] = _pulse_count(latency_ms, ltcy_step);       // PULSE_LTCY
    timing[2] = _pulse_count(window_ms, ltcy_step);        // PULSE_WIND
    uint8_t timing_regs[3] = { REG_PULSE_TMLT, REG_PULSE_LTCY, REG_PULSE_WIND };
    _write_standby_regs(timing_regs, timing, 3);

    // HP_FILTER_CUTOFF: Pulse_HPF_BYP (bit 5) and Pulse_LPF_EN (bit 4).
    uint8_t hpf_cfg = _read_reg(REG_HP_FILTER);
    if (hpf) hpf_cfg |= 0x20;
    else     hpf_cfg = (uint8_t)(hpf_cfg & (uint8_t)~0x20);
    if (lpf) hpf_cfg |= 0x10;
    else     hpf_cfg = (uint8_t)(hpf_cfg & (uint8_t)~0x10);
    _write_standby_reg(REG_HP_FILTER, hpf_cfg);
}

uint8_t MMA8451QFull::read_pulse_source() {
    return _read_reg(REG_PULSE_SRC);
}

void MMA8451QFull::set_orientation(bool enabled, float debounce_ms, uint8_t threshold_deg,
                                   uint8_t hysteresis, uint8_t z_lock, uint8_t back_front) {
    uint8_t ths_code = _orientation_ths_code(threshold_deg);
    if (ths_code == 0xFF) return;  // unsupported trip angle — ignore
    if (hysteresis > 7) return;
    if (z_lock > 7) return;
    if (back_front > 3) return;
    // THS + HYS (register values) must lie in 1..31.
    if ((ths_code + hysteresis) < 1 || (ths_code + hysteresis) > 31) return;

    // PL_CFG: DBCNTM (bit 7) and PL_EN (bit 6).
    uint8_t pl_cfg = 0;
    if (enabled) pl_cfg |= 0x40;
    _write_standby_reg(REG_PL_CFG, pl_cfg);

    // PL_COUNT is writable in ACTIVE mode.
    _write_reg(REG_PL_COUNT, _debounce_count(debounce_ms));

    // PL_BF_ZCOMP: BKFR<1:0> (bits 7:6) and ZLOCK<2:0> (bits 2:0).
    uint8_t zcomp = (uint8_t)(((back_front & 0x03) << 6) | (z_lock & 0x07));
    _write_standby_reg(REG_PL_BF_ZCOMP, zcomp);

    // P_L_THS_REG: P_L_THS<4:0> (bits 7:3) and HYS<2:0> (bits 2:0).
    uint8_t pl_ths = (uint8_t)(((ths_code & 0x1F) << 3) | (hysteresis & 0x07));
    _write_standby_reg(REG_P_L_THS, pl_ths);
}

void MMA8451QFull::read_orientation(bool& changed, bool& lockout,
                                    uint8_t& portrait_landscape, bool& back) {
    uint8_t status = _read_reg(REG_PL_STATUS);
    changed  = (status & PL_STATUS_NEWLP) != 0;
    lockout  = (status & PL_STATUS_LO) != 0;
    portrait_landscape = (uint8_t)((status & PL_STATUS_LAPO_MASK) >> PL_STATUS_LAPO_SHIFT);
    back     = (status & PL_STATUS_BAFRO) != 0;
}

void MMA8451QFull::set_auto_sleep(bool enabled, float sleep_rate_hz, float timeout_s,
                                  uint8_t wake_sources) {
    // CTRL_REG2.SLPE (bit 2).
    uint8_t ctrl2 = _read_reg(REG_CTRL_REG2);
    if (enabled) ctrl2 |= CTRL2_SLPE;
    else         ctrl2 = (uint8_t)(ctrl2 & (uint8_t)~CTRL2_SLPE);
    _write_standby_reg(REG_CTRL_REG2, ctrl2);

    // CTRL_REG1.ASLP_RATE<1:0> (bits 7:6).
    uint8_t aslp_code = 0xFF;
    for (uint8_t i = 0; i < 4; i++) {
        float diff = ASLP_RATES[i] - sleep_rate_hz;
        if (diff < 0) diff = -diff;
        if (diff < 0.001f) {
            aslp_code = i;
            break;
        }
    }
    if (aslp_code != 0xFF) {
        uint8_t ctrl1 = _read_reg(REG_CTRL_REG1);
        ctrl1 = (uint8_t)((ctrl1 & (uint8_t)~CTRL1_ASLP_RATE_MASK) |
                          (uint8_t)(aslp_code << CTRL1_ASLP_RATE_SHIFT));
        _write_standby_reg(REG_CTRL_REG1, ctrl1);
    }

    // ASLP_COUNT: step is 0.32 s for ODR >= 6.25 Hz, 0.64 s at 1.56 Hz.
    float step_s = (_odr_hz <= 1.56f) ? 0.64f : 0.32f;
    int count = (int)lroundf(timeout_s / step_s);
    _write_standby_reg(REG_ASLP_COUNT, clampU8(count));

    // CTRL_REG3.WAKE_* (bits 6:3).
    uint8_t ctrl3 = _read_reg(REG_CTRL_REG3);
    ctrl3 = (uint8_t)(ctrl3 & (uint8_t)~(CTRL3_WAKE_TRANS | CTRL3_WAKE_LNDPRT |
                                       CTRL3_WAKE_PULSE | CTRL3_WAKE_FF_MT));
    if (wake_sources & SOURCE_TRANSIENT)   ctrl3 |= CTRL3_WAKE_TRANS;
    if (wake_sources & SOURCE_ORIENTATION) ctrl3 |= CTRL3_WAKE_LNDPRT;
    if (wake_sources & SOURCE_PULSE)       ctrl3 |= CTRL3_WAKE_PULSE;
    if (wake_sources & SOURCE_FF_MT)       ctrl3 |= CTRL3_WAKE_FF_MT;
    _write_standby_reg(REG_CTRL_REG3, ctrl3);
}

void MMA8451QFull::set_fifo_gate(bool enabled) {
    uint8_t ctrl3 = _read_reg(REG_CTRL_REG3);
    if (enabled) ctrl3 |= CTRL3_FIFO_GATE;
    else         ctrl3 = (uint8_t)(ctrl3 & (uint8_t)~CTRL3_FIFO_GATE);
    _write_standby_reg(REG_CTRL_REG3, ctrl3);
}

void MMA8451QFull::set_interrupt_pins(bool active_high, bool open_drain) {
    uint8_t ctrl3 = _read_reg(REG_CTRL_REG3);
    if (active_high) ctrl3 |= CTRL3_IPOL;
    else             ctrl3 = (uint8_t)(ctrl3 & (uint8_t)~CTRL3_IPOL);
    if (open_drain)  ctrl3 |= CTRL3_PP_OD;
    else             ctrl3 = (uint8_t)(ctrl3 & (uint8_t)~CTRL3_PP_OD);
    _write_standby_reg(REG_CTRL_REG3, ctrl3);
}

void MMA8451QFull::enable_interrupt(uint8_t source, uint8_t pin) {
    uint8_t ctrl4 = _read_reg(REG_CTRL_REG4);
    ctrl4 |= source;
    uint8_t ctrl5 = _read_reg(REG_CTRL_REG5);
    // INT_CFG_*: 1 = routed to INT1, 0 = routed to INT2.
    if (pin == 1) ctrl5 |= source;
    else          ctrl5 = (uint8_t)(ctrl5 & (uint8_t)~source);
    uint8_t regs[2]  = { REG_CTRL_REG4, REG_CTRL_REG5 };
    uint8_t values[2] = { ctrl4, ctrl5 };
    _write_standby_regs(regs, values, 2);
    _enabled_sources |= source;
}

void MMA8451QFull::disable_interrupt(uint8_t source) {
    uint8_t ctrl4 = _read_reg(REG_CTRL_REG4);
    ctrl4 = (uint8_t)(ctrl4 & (uint8_t)~source);
    _write_standby_reg(REG_CTRL_REG4, ctrl4);
    _enabled_sources = (uint8_t)(_enabled_sources & (uint8_t)~source);
}

void MMA8451QFull::onInterrupt(void (*callback)(uint8_t status), uint8_t pin,
                               InputPin* intPin) {
    InputPin* pin_used = intPin ? intPin : _connection.intPin();
    if (!pin_used) {
        // No INT line wired — the caller has to poll pollInterrupt() instead.
        return;
    }
    _callback = callback;
    if (pin == 1) {
        _intPinUsed = pin_used;
        _intPinUsed2 = nullptr;
    } else {
        _intPinUsed2 = pin_used;
        _intPinUsed = nullptr;
    }
    _activeInstance = this;
    bool active_high = (_read_reg(REG_CTRL_REG3) & CTRL3_IPOL) != 0;
    pin_used->onEdge(&MMA8451QFull::_edgeTrampoline,
                     active_high ? InputPin::kRising : InputPin::kFalling);
}

void MMA8451QFull::offInterrupt(uint8_t pin) {
    InputPin* pin_used = (pin == 1) ? _intPinUsed : _intPinUsed2;
    if (pin_used) {
        pin_used->offEdge(&MMA8451QFull::_edgeTrampoline);
        if (pin == 1) _intPinUsed = nullptr;
        else          _intPinUsed2 = nullptr;
    }
    if (pin == 1) _callback = nullptr;
}

void MMA8451QFull::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void MMA8451QFull::_handleEdge() {
    uint8_t status = pollInterrupt();
    if (_callback) _callback(status);
}

uint8_t MMA8451QFull::pollInterrupt() {
    uint8_t status = _read_reg(REG_INT_SOURCE);
    // Every set bit except SOURCE_DATA_READY is cleared by reading the
    // matching source register.
    if (status & SOURCE_FIFO)        _read_reg(REG_STATUS);
    if (status & SOURCE_FF_MT)       _read_reg(REG_FF_MT_SRC);
    if (status & SOURCE_PULSE)       _read_reg(REG_PULSE_SRC);
    if (status & SOURCE_ORIENTATION) _read_reg(REG_PL_STATUS);
    if (status & SOURCE_TRANSIENT)   _read_reg(REG_TRANSIENT_SRC);
    if (status & SOURCE_AUTO_SLEEP)  _read_reg(REG_SYSMOD);
    return status;
}

void MMA8451QFull::_average_counts(int16_t& x, int16_t& y, int16_t& z, uint8_t samples) {
    if (samples == 0) samples = 1;
    int32_t sx = 0, sy = 0, sz = 0;
    for (uint8_t i = 0; i < samples; i++) {
        int16_t rx, ry, rz;
        _read_counts(rx, ry, rz);
        sx += rx;
        sy += ry;
        sz += rz;
    }
    x = (int16_t)(sx / samples);
    y = (int16_t)(sy / samples);
    z = (int16_t)(sz / samples);
}

void MMA8451QFull::self_test(bool& ok, int16_t& dx, int16_t& dy, int16_t& dz) {
    ok = false;
    dx = 0;
    dy = 0;
    dz = 0;

    // Remember the current configuration so it can be restored.
    uint8_t ctrl1_saved = _read_reg(REG_CTRL_REG1);
    uint8_t xyz_saved  = _read_reg(REG_XYZ_DATA_CFG);
    uint8_t ctrl2_saved = _read_reg(REG_CTRL_REG2);

    bool fast_read_saved = _fast_read;
    uint8_t range_saved = _range_g;
    float odr_saved = _odr_hz;

    // ±4 g, 100 Hz, normal oversampling, ACTIVE.
    _fast_read = false;
    _range_g = 4;
    _odr_hz = 100.0f;

    uint8_t xyz = (uint8_t)((xyz_saved & (uint8_t)~0x03) | 0x01);  // ±4 g
    _write_standby_reg(REG_XYZ_DATA_CFG, xyz);

    uint8_t ctrl1 = (uint8_t)((ctrl1_saved & (uint8_t)~(CTRL1_F_READ | CTRL1_DR_MASK |
                                                    CTRL1_LNOISE)) |
                              (0x03 << CTRL1_DR_SHIFT) | CTRL1_ACTIVE);
    _write_standby_reg(REG_CTRL_REG1, ctrl1);

    uint8_t ctrl2 = (uint8_t)(ctrl2_saved & (uint8_t)~(CTRL2_ST | CTRL2_MODS_MASK |
                                                    CTRL2_SMODS_MASK));
    _write_standby_reg(REG_CTRL_REG2, ctrl2);

    // Average a few samples before enabling the self-test force.
    int16_t bx, by, bz;
    _average_counts(bx, by, bz, 4);

    // Enable the self-test force.
    ctrl2 = (uint8_t)(_read_reg(REG_CTRL_REG2) | CTRL2_ST);
    _write_standby_reg(REG_CTRL_REG2, ctrl2);
    // Wait >= 2/ODR.
    _delay_ms((uint32_t)(2000.0f / _odr_hz) + 1);

    int16_t ax, ay, az;
    _average_counts(ax, ay, az, 4);

    // Clear the self-test force.
    ctrl2 = (uint8_t)(_read_reg(REG_CTRL_REG2) & (uint8_t)~CTRL2_ST);
    _write_standby_reg(REG_CTRL_REG2, ctrl2);

    dx = (int16_t)(ax - bx);
    dy = (int16_t)(ay - by);
    dz = (int16_t)(az - bz);

    // Datasheet typical self-test deltas in 14-bit counts: X +181,
    // Y +255, Z +1680. The datasheet lists no limits, so the test passes
    // when every axis moved by more than half of the typical value.
    ok = (dx > 90) && (dy > 127) && (dz > 840);

    // Restore the previous configuration.
    _fast_read = fast_read_saved;
    _range_g = range_saved;
    _odr_hz = odr_saved;
    _write_standby_reg(REG_XYZ_DATA_CFG, xyz_saved);
    _write_standby_reg(REG_CTRL_REG2, ctrl2_saved);
    _write_standby_reg(REG_CTRL_REG1, ctrl1_saved);
}
