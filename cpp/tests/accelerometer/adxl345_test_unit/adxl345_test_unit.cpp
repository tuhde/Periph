#include <stdio.h>
#include <stdint.h>
#include <cmath>
#include "I2CConnectionMock.h"
#include "ADXL345.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static const uint8_t REG_DEVID          = 0x00;
static const uint8_t REG_DATA_FORMAT    = 0x31;
static const uint8_t REG_BW_RATE        = 0x2C;
static const uint8_t REG_POWER_CTL      = 0x2D;
static const uint8_t REG_DATAX0         = 0x32;
static const uint8_t REG_OFSX           = 0x1E;
static const uint8_t REG_OFSY           = 0x1F;
static const uint8_t REG_OFSZ           = 0x20;
static const uint8_t REG_INT_ENABLE     = 0x2E;
static const uint8_t REG_INT_MAP        = 0x2F;
static const uint8_t REG_THRESH_TAP     = 0x1D;
static const uint8_t REG_THRESH_FF      = 0x28;
static const uint8_t REG_TIME_FF        = 0x29;
static const uint8_t REG_FIFO_CTL       = 0x38;
static const uint8_t REG_FIFO_STATUS    = 0x39;

int main() {
    I2CConnectionMock mock;
    mock.setRegister(REG_DEVID, {0xE5});
    // x=0x0001 (raw=1), y=0x0002 (raw=2), z=0x0003 (raw=3) little-endian.
    mock.setRegister(REG_DATAX0, {0x01, 0x00, 0x02, 0x00, 0x03, 0x00});

    ADXL345Minimal accel(mock);
    check_true(true, "construct_minimal");

    // Init writes DATA_FORMAT=0x08, BW_RATE=0x0A, POWER_CTL=0x08.
    bool df_ok = false, bw_ok = false, pwr_ok = false, devid_read = false;
    for (const auto& w : mock.writes()) {
        if (w.size() == 2 && w[0] == REG_DATA_FORMAT && w[1] == 0x08) df_ok = true;
        if (w.size() == 2 && w[0] == REG_BW_RATE      && w[1] == 0x0A) bw_ok = true;
        if (w.size() == 2 && w[0] == REG_POWER_CTL    && w[1] == 0x08) pwr_ok = true;
        if (w.size() == 1 && w[0] == REG_DEVID) devid_read = true;
    }
    check_true(df_ok, "init_writes_data_format_default");
    check_true(bw_ok, "init_writes_bw_rate_default");
    check_true(pwr_ok, "init_writes_power_ctl_default");
    check_true(devid_read, "init_reads_devid");

    // read(): scale 0.0039 g/LSB → (0.0039, 0.0078, 0.0117).
    float x, y, z;
    accel.read(x, y, z);
    check_true(std::fabs(x - 0.0039f) < 1e-9f, "read_x");
    check_true(std::fabs(y - 0.0078f) < 1e-9f, "read_y");
    check_true(std::fabs(z - 0.0117f) < 1e-9f, "read_z");

    // Full range switch to ±4 g: DATA_FORMAT bit 0 should toggle.
    I2CConnectionMock mock2;
    mock2.setRegister(REG_DEVID, {0xE5});
    mock2.setRegister(REG_DATA_FORMAT, {0x08});
    mock2.setRegister(REG_DATAX0, {0x00, 0x01, 0x00, 0x02, 0x00, 0x03});
    ADXL345Full accel_full(mock2);
    accel_full.set_range(4);
    bool range_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_DATA_FORMAT && w[1] == (0x08 | 0x01)) {
            range_ok = true;
        }
    }
    check_true(range_ok, "set_range_4g");

    // Data-rate selection: 100 Hz → BW_RATE = 0x0A.
    mock2.setRegister(REG_BW_RATE, {0x0A});
    accel_full.set_data_rate(100);
    bool rate_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_BW_RATE && w[1] == 0x0A) rate_ok = true;
    }
    check_true(rate_ok, "set_data_rate_100hz");

    // Low-power toggles bit 4 of BW_RATE.
    accel_full.set_low_power(true);
    bool lp_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_BW_RATE && (w[1] & 0x10)) lp_ok = true;
    }
    check_true(lp_ok, "set_low_power");

    // Offset encoding: 0.5 g → 32 LSB at 15.6 mg/LSB.
    accel_full.set_offset(0.5f, -0.5f, 0.0f);
    bool ofsx_ok = false, ofsy_ok = false, ofsz_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_OFSX && w[1] == 32) ofsx_ok = true;
        // -32 → 0xE0.
        if (w.size() == 2 && w[0] == REG_OFSY && w[1] == 0xE0) ofsy_ok = true;
        if (w.size() == 2 && w[0] == REG_OFSZ && w[1] == 0)    ofsz_ok = true;
    }
    check_true(ofsx_ok, "set_offset_x");
    check_true(ofsy_ok, "set_offset_y_signed");
    check_true(ofsz_ok, "set_offset_z_zero");

    // Interrupt enable: set_interrupt(INT_WATERMARK, true, 1) → bit 1 of INT_ENABLE.
    mock2.setRegister(REG_INT_ENABLE, {0x00});
    mock2.setRegister(REG_INT_MAP, {0x00});
    accel_full.set_interrupt(ADXL345Full::INT_WATERMARK, true, 1);
    bool int_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_INT_ENABLE && (w[1] & 0x02)) int_ok = true;
    }
    check_true(int_ok, "enable_watermark_on_int1");

    // Single-tap threshold: 0.5 g / 62.5 mg = 8 LSB.
    accel_full.set_tap_detection(0.5f, 10.0f);
    bool tap_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_THRESH_TAP && w[1] == 8) tap_ok = true;
    }
    check_true(tap_ok, "set_tap_threshold");

    // Free-fall threshold: 0.3 g / 62.5 mg ≈ 4.8 → 5 LSB.
    accel_full.set_free_fall(0.3f, 100.0f);
    bool ff_t_ok = false, ff_time_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_THRESH_FF && w[1] == 5)  ff_t_ok = true;
        if (w.size() == 2 && w[0] == REG_TIME_FF    && w[1] == 20) ff_time_ok = true;
    }
    check_true(ff_t_ok, "set_free_fall_threshold");
    check_true(ff_time_ok, "set_free_fall_time");

    // Sleep mode: Sleep bit (0x04) set.
    accel_full.set_sleep(true);
    bool sleep_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_POWER_CTL && (w[1] & 0x04)) sleep_ok = true;
    }
    check_true(sleep_ok, "set_sleep_true");

    accel_full.set_sleep(false);
    bool wake_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_POWER_CTL && !(w[1] & 0x04)) wake_ok = true;
    }
    check_true(wake_ok, "set_sleep_false");

    // FIFO mode: stream = 0x80, watermark 16 → FIFO_CTL = 0x80 | 16 = 0x90.
    accel_full.set_fifo_mode(ADXL345Full::FIFO_STREAM, 16);
    bool fifo_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_FIFO_CTL && w[1] == (0x80 | 16)) fifo_ok = true;
    }
    check_true(fifo_ok, "set_fifo_mode_stream_16");

    // FIFO count: FIFO_STATUS register's low 6 bits.
    mock2.setRegister(REG_FIFO_STATUS, {0x07});
    check_true(accel_full.fifo_count() == 7, "fifo_count");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}