#include "LSM303.h"
#include <stdlib.h>
#include <math.h>

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

namespace {
// Identity registers of the magnetometer: IRA/IRB/IRC = ASCII "H43";
// identical to the HMC5883L identity (see the spec's identity-collisions note).
const uint8_t MAG_ID[3] = { 0x48, 0x34, 0x33 };

// Sensitivity in mg/LSB, indexed [mode][range_bits]; mode 0 = low-power
// (8-bit), 1 = normal (10-bit), 2 = high-resolution (12-bit);
// range 0..3 = ±2/4/8/16 g. The ±16 g value is 12 mg/LSB, not 8.
const float ACCEL_SENSITIVITY_MG[3][4] = {
    { 16.0f, 32.0f, 64.0f, 192.0f },
    {  4.0f,  8.0f, 16.0f,  48.0f },
    {  1.0f,  2.0f,  4.0f,  12.0f },
};

// Arithmetic shift per mode: 8-bit low-power → 8, 10-bit normal → 6, 12-bit HR → 4.
const uint8_t ACCEL_SHIFT[3] = { 8, 6, 4 };

// Full-scale in g, indexed by range_bits 0..3.
const float RANGE_G[4] = { 2.0f, 4.0f, 8.0f, 16.0f };

// Magnetometer gain codes GN<2:0> 1..7: {µT full scale, X/Y gain, Z gain}
// (LSB/gauss). Z uses a different gain from X and Y at every setting.
const struct MagRange { uint16_t fs_ut; float gain_xy; float gain_z; }
MAG_RANGES[7] = {
    { 130, 1100.0f, 980.0f },
    { 190,  855.0f, 760.0f },
    { 250,  670.0f, 600.0f },
    { 400,  450.0f, 400.0f },
    { 470,  400.0f, 355.0f },
    { 560,  330.0f, 295.0f },
    { 810,  230.0f, 205.0f },
};

// Valid magnetometer output data rates in Hz (CRA_REG_M DO bits 4:2).
const float MAG_ODR[8] = { 0.75f, 1.5f, 3.0f, 7.5f, 15.0f, 30.0f, 75.0f, 220.0f };
}  // namespace

// LSM303Minimal

LSM303Minimal::LSM303Minimal(RegisterConnection& accel_connection, RegisterConnection& mag_connection)
    : _accel(accel_connection), _mag(mag_connection) {
    // Boot download of the factory trim values (≥ 5 ms, as for sibling parts).
    _delay_ms(5);

    // Magnetometer identity check: read IRA/IRB/IRC in one burst.
    uint8_t id[3];
    _mag_read_burst(REG_IRA_M, id, 3);
    if (id[0] != MAG_ID[0] || id[1] != MAG_ID[1] || id[2] != MAG_ID[2]) {
        // Identity check failed — wrong chip / wrong address / wiring problem.
        abort();
    }

    // Accelerometer: no soft reset — write the control registers to known values.
    _accel_write(REG_CTRL2_A,     0x00);
    _accel_write(REG_CTRL3_A,     0x00);
    _accel_write(REG_CTRL5_A,     0x00);
    _accel_write(REG_CTRL6_A,     0x00);
    _accel_write(REG_FIFO_CTRL_A, 0x00);
    // CTRL_REG4: BDU=1, FS=00 (±2 g), HR=1 (high-resolution).
    _accel_write(REG_CTRL4_A, CTRL4_DEFAULT);
    // CTRL_REG1: ODR=0101 (100 Hz), LPen=0, all axes enabled — leaves power-down.
    _accel_write(REG_CTRL1_A, CTRL1_DEFAULT);

    // Magnetometer: the part resets into sleep mode (MR reset value 0x03), so
    // write all three control registers explicitly (15 Hz, ±1.3 gauss, continuous).
    _mag_write(REG_CRA_M, 0x10);
    _mag_write(REG_CRB_M, 0x20);
    _mag_write(REG_MR_M,  0x00);

    // Turn-on waits: 7/ODR (70 ms at 100 Hz) for the accelerometer and one
    // output period (67 ms at 15 Hz) for the first magnetometer sample.
    _delay_ms(70);
    _delay_ms(67);
}

void LSM303Minimal::_delay_ms(uint32_t ms) {
    delay(ms);
}

void LSM303Minimal::_accel_write(uint8_t reg, uint8_t value) {
    _accel.write(reg, &value, 1);
}

uint8_t LSM303Minimal::_accel_read(uint8_t reg) {
    uint8_t v = 0;
    _accel.read(reg, &v, 1);
    return v;
}

void LSM303Minimal::_accel_read_burst(uint8_t reg, uint8_t* buf, size_t len) {
    // Multi-byte accelerometer transfers set the auto-increment bit (MSb of
    // the sub-address); single-register accesses do not.
    _accel.read(static_cast<uint8_t>(reg | 0x80), buf, len);
}

void LSM303Minimal::_mag_write(uint8_t reg, uint8_t value) {
    _mag.write(reg, &value, 1);
}

uint8_t LSM303Minimal::_mag_read(uint8_t reg) {
    uint8_t v = 0;
    _mag.read(reg, &v, 1);
    return v;
}

void LSM303Minimal::_mag_read_burst(uint8_t reg, uint8_t* buf, size_t len) {
    // The magnetometer pointer always auto-increments; no auto-inc bit.
    _mag.read(reg, buf, len);
}

float LSM303Minimal::_accel_sensitivity_g() {
    return ACCEL_SENSITIVITY_MG[_mode][_range_bits] / 1000.0f;
}

void LSM303Minimal::_convert_accel_sample(const uint8_t* raw,
                                          int16_t& cx, int16_t& cy, int16_t& cz) {
    // Sign-extend the 16-bit pair first, then arithmetic-shift right
    // (12-bit data is left-justified; LSB register first because BLE = 0).
    int32_t rx = toSigned(static_cast<uint32_t>(raw[0] | (raw[1] << 8)), 16);
    int32_t ry = toSigned(static_cast<uint32_t>(raw[2] | (raw[3] << 8)), 16);
    int32_t rz = toSigned(static_cast<uint32_t>(raw[4] | (raw[5] << 8)), 16);
    uint8_t shift = ACCEL_SHIFT[_mode];
    cx = static_cast<int16_t>(rx >> shift);
    cy = static_cast<int16_t>(ry >> shift);
    cz = static_cast<int16_t>(rz >> shift);
}

float LSM303Minimal::_mag_gain_xy(uint8_t index) {
    return MAG_RANGES[index & 0x07].gain_xy;
}

float LSM303Minimal::_mag_gain_z(uint8_t index) {
    return MAG_RANGES[index & 0x07].gain_z;
}

void LSM303Minimal::read_acceleration(float& x, float& y, float& z) {
    uint8_t raw[6];
    _accel_read_burst(REG_OUT_X_L_A, raw, 6);
    int16_t cx, cy, cz;
    _convert_accel_sample(raw, cx, cy, cz);
    float scale = _accel_sensitivity_g();
    x = static_cast<float>(cx) * scale;
    y = static_cast<float>(cy) * scale;
    z = static_cast<float>(cz) * scale;
}

void LSM303Minimal::read_magnetic(float& x, float& y, float& z) {
    uint8_t raw[6];
    // On the wire: X (high/low), Z (high/low), Y (high/low); high byte first.
    _mag_read_burst(REG_OUT_X_H_M, raw, 6);
    int32_t mx = toSigned(static_cast<uint32_t>((raw[0] << 8) | raw[1]), 16);
    int32_t mz = toSigned(static_cast<uint32_t>((raw[2] << 8) | raw[3]), 16);
    int32_t my = toSigned(static_cast<uint32_t>((raw[4] << 8) | raw[5]), 16);
    float gain_xy = _mag_gain_xy(_mag_gn);
    float gain_z  = _mag_gain_z(_mag_gn);
    // 1 gauss = 100 µT.
    x = static_cast<float>(mx) / gain_xy * 100.0f;
    y = static_cast<float>(my) / gain_xy * 100.0f;
    z = static_cast<float>(mz) / gain_z * 100.0f;
}

// LSM303Full

LSM303Full* LSM303Full::_activeInstance = nullptr;

LSM303Full::LSM303Full(RegisterConnection& accel_connection, RegisterConnection& mag_connection)
    : LSM303Minimal(accel_connection, mag_connection) {}

void LSM303Full::set_range(uint8_t range_g) {
    uint8_t code = 0xFF;
    switch (range_g) {
        case 2:  code = 0; break;
        case 4:  code = 1; break;
        case 8:  code = 2; break;
        case 16: code = 3; break;
        default: return;   // invalid range — rejected, no bus write
    }
    _range_bits = code;
    uint8_t ctrl4 = _accel_read(REG_CTRL4_A);
    ctrl4 = static_cast<uint8_t>((ctrl4 & ~FS_MASK) | (code << 4));
    _accel_write(REG_CTRL4_A, ctrl4);
}

void LSM303Full::set_data_rate(uint16_t rate_hz) {
    uint8_t code = 0xFF;
    if (rate_hz == 1)        code = ODR_1HZ;
    else if (rate_hz == 10)  code = ODR_10HZ;
    else if (rate_hz == 25)  code = ODR_25HZ;
    else if (rate_hz == 50)  code = ODR_50HZ;
    else if (rate_hz == 100) code = ODR_100HZ;
    else if (rate_hz == 200) code = ODR_200HZ;
    else if (rate_hz == 400) code = ODR_400HZ;
    else if (rate_hz == 1620) code = ODR_1620HZ;
    else if (rate_hz == 1344 || rate_hz == 5376) code = ODR_1344HZ;
    else if (rate_hz == 0)   code = 0;  // power-down
    if (code == 0xFF) return;           // invalid rate — rejected, no bus write
    // ODR 1000 (1.620 kHz) exists in low-power mode only; ODR 1001 is
    // 1.344 kHz in normal / high-resolution and 5.376 kHz in low-power only.
    if (code == ODR_1620HZ && _mode != MODE_LOW_POWER) return;
    if (code == ODR_1344HZ && rate_hz == 5376 && _mode != MODE_LOW_POWER) return;
    if (code == ODR_1344HZ && rate_hz == 1344 && _mode == MODE_LOW_POWER) return;
    uint8_t ctrl1 = _accel_read(REG_CTRL1_A);
    ctrl1 = static_cast<uint8_t>((ctrl1 & 0x0F) | (code << 4));
    _accel_write(REG_CTRL1_A, ctrl1);
    _odr_hz = static_cast<float>(rate_hz);
}

void LSM303Full::set_mode(uint8_t mode) {
    if (mode > MODE_HIGH_RES) return;  // invalid mode — rejected, no bus write
    uint8_t ctrl1 = _accel_read(REG_CTRL1_A);
    uint8_t ctrl4 = _accel_read(REG_CTRL4_A);
    if (mode == MODE_LOW_POWER) {
        ctrl1 |= LPEN_BIT;   // LPen = 1
        ctrl4 &= ~HR_BIT;    // HR = 0
    } else if (mode == MODE_NORMAL) {
        ctrl1 &= ~LPEN_BIT;
        ctrl4 &= ~HR_BIT;
    } else {
        ctrl1 &= ~LPEN_BIT;
        ctrl4 |= HR_BIT;
    }
    _accel_write(REG_CTRL1_A, ctrl1);
    _accel_write(REG_CTRL4_A, ctrl4);
    _mode = mode;
}

void LSM303Full::set_axes(bool x, bool y, bool z) {
    uint8_t ctrl1 = _accel_read(REG_CTRL1_A);
    ctrl1 = static_cast<uint8_t>((ctrl1 & 0xF8) | ((z ? 1 : 0) << 2) | ((y ? 1 : 0) << 1) | (x ? 1 : 0));
    _accel_write(REG_CTRL1_A, ctrl1);
}

void LSM303Full::power_down() {
    uint8_t ctrl1 = _accel_read(REG_CTRL1_A);
    _accel_write(REG_CTRL1_A, static_cast<uint8_t>(ctrl1 & 0x0F));
    _odr_hz = 0.0f;
}

void LSM303Full::read_acceleration_raw(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t raw[6];
    _accel_read_burst(REG_OUT_X_L_A, raw, 6);
    _convert_accel_sample(raw, x, y, z);
}

bool LSM303Full::new_data_available() {
    return (_accel_read(REG_STATUS_A) & 0x08) != 0;  // ZYXDA
}

uint8_t LSM303Full::read_status() {
    return _accel_read(REG_STATUS_A);
}

void LSM303Full::set_high_pass(uint8_t mode, uint8_t cutoff, bool filter_output,
                               bool on_ia1, bool on_ia2, bool on_click) {
    uint8_t ctrl2 = static_cast<uint8_t>(((mode & 0x03) << 6) | ((cutoff & 0x03) << 4));
    if (filter_output) ctrl2 |= 0x08;  // FDS
    if (on_click)      ctrl2 |= 0x04;  // HPCLICK
    if (on_ia2)        ctrl2 |= 0x02;  // HPIS2
    if (on_ia1)        ctrl2 |= 0x01;  // HPIS1
    _accel_write(REG_CTRL2_A, ctrl2);
}

uint8_t LSM303Full::reset_high_pass() {
    // Reading REFERENCE_A resets the filter in HPM = 00.
    return _accel_read(REG_REFERENCE_A);
}

void LSM303Full::set_reference(uint8_t value) {
    _accel_write(REG_REFERENCE_A, value);
}

void LSM303Full::set_fifo(uint8_t mode, uint8_t watermark, bool trigger_int2) {
    uint8_t fifo_ctrl = static_cast<uint8_t>(((mode & 0x03) << 6) |
                                             (trigger_int2 ? 0x20 : 0x00) |
                                             (watermark & 0x1F));
    _accel_write(REG_FIFO_CTRL_A, fifo_ctrl);
    // FIFO_EN: set for FIFO / stream / trigger, cleared for bypass —
    // only bypass mode empties the buffer.
    uint8_t ctrl5 = _accel_read(REG_CTRL5_A);
    if (mode == 1 || mode == 2 || mode == 3) ctrl5 |= FIFO_EN_BIT;
    else                                     ctrl5 &= ~FIFO_EN_BIT;
    _accel_write(REG_CTRL5_A, ctrl5);
}

uint8_t LSM303Full::fifo_level() {
    return static_cast<uint8_t>(_accel_read(REG_FIFO_SRC_A) & 0x1F);
}

uint8_t LSM303Full::read_fifo(float* x_buf, float* y_buf, float* z_buf, uint8_t max_samples) {
    uint8_t count = fifo_level();
    if (count > max_samples) count = max_samples;
    float scale = _accel_sensitivity_g();
    uint8_t popped = 0;
    for (uint8_t i = 0; i < count; i++) {
        uint8_t raw[6];
        _accel_read_burst(REG_OUT_X_L_A, raw, 6);
        int16_t cx, cy, cz;
        _convert_accel_sample(raw, cx, cy, cz);
        x_buf[i] = static_cast<float>(cx) * scale;
        y_buf[i] = static_cast<float>(cy) * scale;
        z_buf[i] = static_cast<float>(cz) * scale;
        popped++;
    }
    return popped;
}

void LSM303Full::read_fifo_status(bool& watermark, bool& overrun, bool& empty, uint8_t& level) {
    uint8_t src = _accel_read(REG_FIFO_SRC_A);
    watermark = (src & 0x80) != 0;  // WTM
    overrun   = (src & 0x40) != 0;  // OVRN_FIFO
    empty     = (src & 0x20) != 0;  // EMPTY
    level     = src & 0x1F;         // FSS
}

void LSM303Full::_int_regs(uint8_t& cfg, uint8_t& src, uint8_t& ths, uint8_t& dur,
                           uint8_t generator) {
    if (generator == 1) {
        cfg = REG_INT1_CFG_A; src = REG_INT1_SRC_A;
        ths = REG_INT1_THS_A; dur = REG_INT1_DUR_A;
    } else {
        cfg = REG_INT2_CFG_A; src = REG_INT2_SRC_A;
        ths = REG_INT2_THS_A; dur = REG_INT2_DUR_A;
    }
}

uint8_t LSM303Full::_encode_threshold(float threshold_g) {
    // 1 LSb = full-scale / 128 (stated for CLICK_THS_A, assumed for INTx_THS_A).
    int code = static_cast<int>(lroundf(threshold_g * 128.0f / RANGE_G[_range_bits & 0x03]));
    if (code > 127) code = 127;
    if (code < 0)   code = 0;
    return static_cast<uint8_t>(code);
}

uint8_t LSM303Full::_encode_duration(float duration_ms, uint8_t max_bits) {
    // 1 LSb = 1/ODR.
    int code = static_cast<int>(lroundf(duration_ms * _odr_hz / 1000.0f));
    int limit = (1 << max_bits) - 1;
    if (code > limit) code = limit;
    if (code < 0)     code = 0;
    return static_cast<uint8_t>(code);
}

void LSM303Full::set_motion_interrupt(uint8_t generator, uint8_t events, float threshold_g,
                                      float duration_ms, bool and_mode) {
    if (generator != 1 && generator != 2) return;  // rejected, no bus write
    uint8_t cfg, src, ths, dur;
    _int_regs(cfg, src, ths, dur, generator);
    _accel_write(cfg, static_cast<uint8_t>((and_mode ? 0x80 : 0x00) | (events & 0x3F)));
    _accel_write(ths, _encode_threshold(threshold_g));
    _accel_write(dur, _encode_duration(duration_ms));
}

void LSM303Full::set_free_fall(uint8_t generator, float threshold_g, float duration_ms) {
    // All three axes low, AND-combined: ZLIE|YLIE|XLIE = 0x15.
    set_motion_interrupt(generator, 0x15, threshold_g, duration_ms, true);
}

void LSM303Full::set_orientation(uint8_t generator, bool position, bool four_d) {
    if (generator != 1 && generator != 2) return;  // rejected, no bus write
    uint8_t cfg, src, ths, dur;
    _int_regs(cfg, src, ths, dur, generator);
    uint8_t value = 0x40 | 0x3F;  // 6D + all six direction zones
    if (position) value |= 0x80;
    _accel_write(cfg, value);
    // 4D via D4D_INT1 (bit 2) / D4D_INT2 (bit 0) of CTRL_REG5_A.
    uint8_t four_d_bit = (generator == 1) ? 0x04 : 0x01;
    uint8_t ctrl5 = _accel_read(REG_CTRL5_A);
    if (four_d) ctrl5 |= four_d_bit;
    else        ctrl5 &= static_cast<uint8_t>(~four_d_bit);
    _accel_write(REG_CTRL5_A, ctrl5);
}

void LSM303Full::set_latch(uint8_t generator, bool enabled) {
    if (generator != 1 && generator != 2) return;  // rejected, no bus write
    // LIR_INT1 (bit 3) / LIR_INT2 (bit 1) of CTRL_REG5_A.
    uint8_t latch_bit = (generator == 1) ? 0x08 : 0x02;
    uint8_t ctrl5 = _accel_read(REG_CTRL5_A);
    if (enabled) ctrl5 |= latch_bit;
    else         ctrl5 &= static_cast<uint8_t>(~latch_bit);
    _accel_write(REG_CTRL5_A, ctrl5);
}

uint8_t LSM303Full::read_interrupt_source(uint8_t generator) {
    if (generator != 1 && generator != 2) return 0;
    uint8_t cfg, src, ths, dur;
    _int_regs(cfg, src, ths, dur, generator);
    return _accel_read(src);  // reading clears the interrupt
}

void LSM303Full::set_click(uint8_t single, uint8_t double_click, float threshold_g,
                           float time_limit_ms, float latency_ms, float window_ms) {
    // single / double are axis masks: X=1, Y=2, Z=4 (0–7); otherwise rejected.
    if (single > 7 || double_click > 7) return;
    // Spread to CLICK_CFG_A bits: XD=0, XS=1, YD=2, YS=3, ZD=4, ZS=5.
    uint8_t single_code = static_cast<uint8_t>(((single & 1) << 1) | ((single & 2) << 2) |
                                               ((single & 4) << 3));
    uint8_t double_code = static_cast<uint8_t>(((double_click & 1) << 0) |
                                               ((double_click & 2) << 1) |
                                               ((double_click & 4) << 2));
    _accel_write(REG_CLICK_CFG_A, static_cast<uint8_t>(single_code | double_code));
    _accel_write(REG_CLICK_THS_A, _encode_threshold(threshold_g));
    _accel_write(REG_TIME_LIMIT_A, _encode_duration(time_limit_ms));
    _accel_write(REG_TIME_LAT_A,   _encode_duration(latency_ms, 8));
    _accel_write(REG_TIME_WIN_A,   _encode_duration(window_ms, 8));
}

uint8_t LSM303Full::read_click_source() {
    return _accel_read(REG_CLICK_SRC_A);  // reading clears the click interrupt
}

void LSM303Full::set_interrupt_polarity(bool active_low) {
    uint8_t ctrl6 = _accel_read(REG_CTRL6_A);
    if (active_low) ctrl6 |= 0x02;  // H_LACTIVE
    else            ctrl6 &= static_cast<uint8_t>(~0x02);
    _accel_write(REG_CTRL6_A, ctrl6);
}

void LSM303Full::set_mag_range(uint16_t range_ut) {
    uint8_t index = 0xFF;
    for (uint8_t i = 0; i < 7; i++) {
        if (MAG_RANGES[i].fs_ut == range_ut) { index = i; break; }
    }
    if (index == 0xFF) return;  // invalid range — rejected, no bus write
    _mag_gn = index;
    _mag_write(REG_CRB_M, static_cast<uint8_t>((index + 1) << 5));
}

void LSM303Full::set_mag_data_rate(float rate_hz) {
    uint8_t code = 0xFF;
    for (uint8_t i = 0; i < 8; i++) {
        if (fabsf(MAG_ODR[i] - rate_hz) < 0.01f) { code = i; break; }
    }
    if (code == 0xFF) return;  // invalid rate — rejected, no bus write
    // Preserve the TEMP_EN bit.
    uint8_t cra = _mag_read(REG_CRA_M);
    _mag_write(REG_CRA_M, static_cast<uint8_t>((cra & 0x80) | (code << 2)));
    _mag_odr_hz = rate_hz;
}

void LSM303Full::set_mag_mode(uint8_t mode) {
    if (mode > 2) return;  // invalid mode — rejected, no bus write
    _mag_write(REG_MR_M, static_cast<uint8_t>(mode & 0x03));
}

void LSM303Full::read_magnetic_raw(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t raw[6];
    _mag_read_burst(REG_OUT_X_H_M, raw, 6);
    // On the wire: X, Z, Y — reorder to X, Y, Z.
    x = static_cast<int16_t>(toSigned(static_cast<uint32_t>((raw[0] << 8) | raw[1]), 16));
    y = static_cast<int16_t>(toSigned(static_cast<uint32_t>((raw[4] << 8) | raw[5]), 16));
    z = static_cast<int16_t>(toSigned(static_cast<uint32_t>((raw[2] << 8) | raw[3]), 16));
}

bool LSM303Full::mag_data_ready() {
    return (_mag_read(REG_SR_M) & 0x01) != 0;  // DRDY
}

void LSM303Full::read_mag_status(bool& drdy, bool& lock) {
    uint8_t sr = _mag_read(REG_SR_M);
    drdy = (sr & 0x01) != 0;
    lock = (sr & 0x02) != 0;
}

void LSM303Full::read_mag_identity(uint8_t& ida, uint8_t& idb, uint8_t& idc) {
    ida = _mag_read(REG_IRA_M);
    idb = _mag_read(REG_IRB_M);
    idc = _mag_read(REG_IRC_M);
}

void LSM303Full::enable_temperature(bool enabled) {
    uint8_t cra = _mag_read(REG_CRA_M);
    if (enabled) cra |= 0x80;  // TEMP_EN
    else         cra &= static_cast<uint8_t>(~0x80);
    _mag_write(REG_CRA_M, cra);
    _temp_enabled = enabled;
}

float LSM303Full::read_temperature_delta() {
    if (!_temp_enabled) enable_temperature(true);
    uint8_t raw[2];
    _mag_read_burst(REG_TEMP_H_M, raw, 2);
    // 12-bit two's complement, left-justified; 8 LSB/°C, relative to an
    // unspecified zero-degree reference.
    int32_t value = toSigned(static_cast<uint32_t>((raw[0] << 8) | raw[1]), 16) >> 4;
    return static_cast<float>(value) / 8.0f;
}

float LSM303Full::heading(bool tilt_compensate) {
    float mx, my, mz;
    read_magnetic(mx, my, mz);
    float angle;
    if (!tilt_compensate) {
        angle = atan2f(my, mx) * 180.0f / 3.14159265f;
    } else {
        float ax, ay, az;
        read_acceleration(ax, ay, az);
        float norm = sqrtf(ax * ax + ay * ay + az * az);
        if (norm == 0.0f) norm = 1.0f;
        ax /= norm; ay /= norm; az /= norm;
        float roll  = atan2f(ay, az);
        float pitch = atan2f(-ax, ay * sinf(roll) + az * cosf(roll));
        float xh = mx * cosf(pitch) + mz * sinf(pitch);
        float yh = mx * sinf(roll) * sinf(pitch) + my * cosf(roll)
                 - mz * sinf(roll) * cosf(pitch);
        angle = atan2f(yh, xh) * 180.0f / 3.14159265f;
    }
    if (angle < 0.0f) angle += 360.0f;
    return angle;
}

void LSM303Full::enable_interrupt(uint16_t source, uint8_t pin) {
    // SOURCE_MAG_DATA_READY is on the magnetometer DRDY pin, not routable;
    // SOURCE_BOOT is INT2-only; pin must be 1 or 2. Unknown or illegal
    // combinations are rejected without a bus write.
    if (source == SOURCE_MAG_DATA_READY) return;
    if (pin != 1 && pin != 2) return;
    if (source != SOURCE_DATA_READY && source != SOURCE_WATERMARK && source != SOURCE_OVERRUN &&
        source != SOURCE_IA1 && source != SOURCE_IA2 && source != SOURCE_CLICK &&
        source != SOURCE_BOOT) return;
    if (source == SOURCE_BOOT && pin != 2) return;

    uint8_t ctrl3 = _accel_read(REG_CTRL3_A);
    uint8_t ctrl6 = _accel_read(REG_CTRL6_A);
    if (source == SOURCE_DATA_READY)     ctrl3 |= I1_DRDY1;
    else if (source == SOURCE_WATERMARK) ctrl3 |= I1_WTM;
    else if (source == SOURCE_OVERRUN)   ctrl3 |= I1_OVERRUN;
    else if (source == SOURCE_IA1) {
        if (pin == 1) ctrl3 |= I1_AOI1;
        else          ctrl6 |= I2_INT1;
    }
    else if (source == SOURCE_IA2) {
        if (pin == 1) ctrl3 |= I1_AOI2;
        else          ctrl6 |= I2_INT2;
    }
    else if (source == SOURCE_CLICK) {
        if (pin == 1) ctrl3 |= I1_CLICK;
        else          ctrl6 |= I2_CLICK;
    }
    else {  // SOURCE_BOOT — already validated as pin 2
        ctrl6 |= BOOT_I1;
    }
    _accel_write(REG_CTRL3_A, ctrl3);
    _accel_write(REG_CTRL6_A, ctrl6);
    _int_sources |= source;
}

void LSM303Full::disable_interrupt(uint16_t source, uint8_t pin) {
    if (source == SOURCE_MAG_DATA_READY) return;
    if (pin != 1 && pin != 2) return;
    if (source != SOURCE_DATA_READY && source != SOURCE_WATERMARK && source != SOURCE_OVERRUN &&
        source != SOURCE_IA1 && source != SOURCE_IA2 && source != SOURCE_CLICK &&
        source != SOURCE_BOOT) return;
    if (source == SOURCE_BOOT && pin != 2) return;

    uint8_t ctrl3 = _accel_read(REG_CTRL3_A);
    uint8_t ctrl6 = _accel_read(REG_CTRL6_A);
    if (source == SOURCE_DATA_READY)     ctrl3 &= static_cast<uint8_t>(~I1_DRDY1);
    else if (source == SOURCE_WATERMARK) ctrl3 &= static_cast<uint8_t>(~I1_WTM);
    else if (source == SOURCE_OVERRUN)   ctrl3 &= static_cast<uint8_t>(~I1_OVERRUN);
    else if (source == SOURCE_IA1) {
        if (pin == 1) ctrl3 &= static_cast<uint8_t>(~I1_AOI1);
        else          ctrl6 &= static_cast<uint8_t>(~I2_INT1);
    }
    else if (source == SOURCE_IA2) {
        if (pin == 1) ctrl3 &= static_cast<uint8_t>(~I1_AOI2);
        else          ctrl6 &= static_cast<uint8_t>(~I2_INT2);
    }
    else if (source == SOURCE_CLICK) {
        if (pin == 1) ctrl3 &= static_cast<uint8_t>(~I1_CLICK);
        else          ctrl6 &= static_cast<uint8_t>(~I2_CLICK);
    }
    else {  // SOURCE_BOOT — already validated as pin 2
        ctrl6 &= static_cast<uint8_t>(~BOOT_I1);
    }
    _accel_write(REG_CTRL3_A, ctrl3);
    _accel_write(REG_CTRL6_A, ctrl6);
    _int_sources &= static_cast<uint16_t>(~source);
}

uint16_t LSM303Full::poll_interrupt() {
    uint16_t status = 0;
    if (SOURCE_DATA_READY & _int_sources) {
        if (_accel_read(REG_STATUS_A) & 0x08) status |= SOURCE_DATA_READY;  // ZYXDA
    }
    if ((SOURCE_WATERMARK | SOURCE_OVERRUN) & _int_sources) {
        uint8_t fifo_src = _accel_read(REG_FIFO_SRC_A);
        if (fifo_src & 0x80) status |= SOURCE_WATERMARK;  // WTM
        if (fifo_src & 0x40) status |= SOURCE_OVERRUN;    // OVRN_FIFO
    }
    if (SOURCE_IA1 & _int_sources) {
        if (_accel_read(REG_INT1_SRC_A) & 0x40) status |= SOURCE_IA1;  // IA
    }
    if (SOURCE_IA2 & _int_sources) {
        if (_accel_read(REG_INT2_SRC_A) & 0x40) status |= SOURCE_IA2;  // IA
    }
    if (SOURCE_CLICK & _int_sources) {
        if (_accel_read(REG_CLICK_SRC_A) & 0x40) status |= SOURCE_CLICK;  // IA
    }
    // SOURCE_MAG_DATA_READY is always reported (DRDY is not routable but the
    // status register is still the way to sample it).
    if (_mag_read(REG_SR_M) & 0x01) status |= SOURCE_MAG_DATA_READY;
    return status;
}

void LSM303Full::on_interrupt(void (*callback)(uint16_t status), uint8_t pin) {
    if (pin != 1 && pin != 2) return;
    _int_callbacks[pin - 1] = callback;
    InputPin* pin_raw = _accel.intPin();
    if (!pin_raw) return;  // no INT pin wired — poll poll_interrupt() directly
    if (!_intPinUsed) {
        _intPinUsed = pin_raw;
        _activeInstance = this;
        // Both INT lines typically share one GPIO; the shared edge handler
        // polls the sources and dispatches to whichever callbacks are set.
        pin_raw->onEdge(&LSM303Full::_edgeTrampoline, InputPin::kFalling);
    }
}

void LSM303Full::off_interrupt(uint8_t pin) {
    if (pin != 1 && pin != 2) return;
    _int_callbacks[pin - 1] = nullptr;
    if (!_int_callbacks[0] && !_int_callbacks[1]) {
        if (_intPinUsed) {
            _intPinUsed->offEdge(&LSM303Full::_edgeTrampoline);
            _intPinUsed = nullptr;
        }
    }
}

void LSM303Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void LSM303Full::_handleEdge() {
    uint16_t status = poll_interrupt();
    if (_int_callbacks[0]) _int_callbacks[0](status);
    if (_int_callbacks[1]) _int_callbacks[1](status);
}

void LSM303Full::reboot() {
    uint8_t ctrl5 = _accel_read(REG_CTRL5_A);
    _accel_write(REG_CTRL5_A, static_cast<uint8_t>(ctrl5 | BOOT_BIT));
    _delay_ms(5);
}
