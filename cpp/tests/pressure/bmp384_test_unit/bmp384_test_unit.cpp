#include <stdio.h>
#include <math.h>
#include <string>
#include "I2CConnectionMock.h"
#include "BMP384.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Arbitrary but fixed calibration NVM block (21 bytes at 0x31).
// NVM: T1=27664, T2=27728, T3=3, P1=-4079, P2=802, P3=-8, P4=5, P5=32832,
//      P6=7696, P7=-16, P8=10, P9=4064, P10=-5, P11=2.
static void preloadCalibration(I2CConnectionMock& conn) {
    conn.setRegister(0x31, {
        0x10, 0x6C,  // T1 u16 LE
        0x50, 0x6C,  // T2 u16 LE
        0x03,        // T3 s8
        0x11, 0xF0,  // P1 s16 LE
        0x22, 0x03,  // P2 s16 LE
        0xF8,        // P3 s8
        0x05,        // P4 s8
        0x40, 0x80,  // P5 u16 LE
        0x10, 0x1E,  // P6 u16 LE
        0xF0,        // P7 s8
        0x0A,        // P8 s8
        0xE0, 0x0F,  // P9 s16 LE
        0xFB,        // P10 s8
        0x02,        // P11 s8
    });
    conn.setRegister(0x00, {0x50}); // CHIP_ID
}

static int lastWriteTo(const I2CConnectionMock& conn, uint8_t reg) {
    const auto& w = conn.writes();
    for (auto it = w.rbegin(); it != w.rend(); ++it) {
        if (it->size() == 2 && (*it)[0] == reg) return (*it)[1];
    }
    return -1;
}

// uncomp_press=6000000, uncomp_temp=8000000 -> t_lin=23.715563300065696 degC,
// pressure=1447.6955007429672 hPa (computed independently from the same
// Bosch compensation formula; cross-checked across all language ports).
static const uint8_t PRESS_BYTES[3] = {0x80, 0x8D, 0x5B};
static const uint8_t TEMP_BYTES[3]  = {0x00, 0x12, 0x7A};
static constexpr double EXPECTED_T_LIN = 23.715563300065696;
static constexpr double EXPECTED_PRESSURE_HPA = 1447.6955007429672;

static void setBurst(I2CConnectionMock& conn) {
    conn.setRegister(0x04, {PRESS_BYTES[0], PRESS_BYTES[1], PRESS_BYTES[2],
                            TEMP_BYTES[0], TEMP_BYTES[1], TEMP_BYTES[2]});
}

int main() {
    // --- construction reads calibration and applies default config ---
    I2CConnectionMock conn;
    preloadCalibration(conn);
    BMP384Minimal chip(conn);
    check_true(lastWriteTo(conn, 0x1C) == ((1 << 3) | 4), "ctor_writes_osr");
    check_true(lastWriteTo(conn, 0x1F) == (2 << 1), "ctor_writes_config");
    check_true(lastWriteTo(conn, 0x1D) == 0x03, "ctor_writes_odr");
    check_true(lastWriteTo(conn, 0x1B) == ((0x03 << 4) | 0x02 | 0x01), "ctor_writes_pwr");

    // --- temperature()/pressure() against the fixed fixture ---
    I2CConnectionMock conn2;
    preloadCalibration(conn2);
    BMP384Minimal chip2(conn2);
    setBurst(conn2);
    check_true(fabs(chip2.temperature() - EXPECTED_T_LIN) < 1e-3, "temperature_value");

    setBurst(conn2);
    check_true(fabs(chip2.pressure() - EXPECTED_PRESSURE_HPA) < 1e-3, "pressure_value");

    // --- forced-mode temperature() triggers PWR_CTRL before reading ---
    I2CConnectionMock conn3;
    preloadCalibration(conn3);
    BMP384Minimal chip3(conn3);
    chip3._mode = 0x01; // MODE_FORCED
    setBurst(conn3);
    chip3.temperature();
    check_true(lastWriteTo(conn3, 0x1B) == ((0x01 << 4) | 0x02 | 0x01), "forced_temperature_triggers");

    // --- Full: configure() writes OSR/CONFIG/ODR ---
    I2CConnectionMock conn4;
    preloadCalibration(conn4);
    BMP384Full full(conn4);
    full.configure(1, 1, 2, 0x03);
    check_true(lastWriteTo(conn4, 0x1C) == ((1 << 3) | 1), "configure_writes_osr");
    check_true(lastWriteTo(conn4, 0x1F) == (2 << 1), "configure_writes_config");
    check_true(lastWriteTo(conn4, 0x1D) == 0x03, "configure_writes_odr");

    // --- read(): combined burst read ---
    I2CConnectionMock conn5;
    preloadCalibration(conn5);
    BMP384Full full5(conn5);
    setBurst(conn5);
    float p, t;
    full5.read(p, t);
    check_true(fabs(p - EXPECTED_PRESSURE_HPA) < 1e-3, "read_pressure");
    check_true(fabs(t - EXPECTED_T_LIN) < 1e-3, "read_temperature");

    // --- read() in forced mode also triggers PWR_CTRL (regression: read()
    // must trigger exactly like temperature()/pressure()/read_forced() do) ---
    I2CConnectionMock conn6;
    preloadCalibration(conn6);
    BMP384Full full6(conn6);
    full6.set_mode(BMP384Full::MODE_FORCED);
    setBurst(conn6);
    float p6, t6;
    full6.read(p6, t6);
    check_true(lastWriteTo(conn6, 0x1B) == ((0x01 << 4) | 0x02 | 0x01), "read_forced_mode_triggers");

    // --- read_forced(): triggers, waits, reads, then restores previous mode ---
    I2CConnectionMock conn7;
    preloadCalibration(conn7);
    BMP384Full full7(conn7);
    setBurst(conn7);
    float p7, t7;
    full7.read_forced(p7, t7);
    check_true(fabs(p7 - EXPECTED_PRESSURE_HPA) < 1e-3, "read_forced_value");
    check_true(lastWriteTo(conn7, 0x1B) == ((0x03 << 4) | 0x02 | 0x01), "read_forced_restores_mode");

    // --- set_mode() ---
    I2CConnectionMock conn8;
    preloadCalibration(conn8);
    BMP384Full full8(conn8);
    full8.set_mode(BMP384Full::MODE_SLEEP);
    check_true(lastWriteTo(conn8, 0x1B) == ((0x00 << 4) | 0x02 | 0x01), "set_mode_writes_pwr");

    // --- is_data_ready() ---
    I2CConnectionMock conn9;
    preloadCalibration(conn9);
    BMP384Full full9(conn9);
    conn9.setRegister(0x03, {1 << 5});
    check_true(full9.is_data_ready() == true, "is_data_ready_true");
    conn9.setRegister(0x03, {0x00});
    check_true(full9.is_data_ready() == false, "is_data_ready_false");

    // --- softreset(): writes CMD, re-reads calibration, re-applies config ---
    I2CConnectionMock conn10;
    preloadCalibration(conn10);
    BMP384Full full10(conn10);
    full10.softreset();
    check_true(lastWriteTo(conn10, 0x7E) == 0xB6, "softreset_writes_cmd");
    check_true(lastWriteTo(conn10, 0x1B) == ((0x03 << 4) | 0x02 | 0x01), "softreset_reapplies_pwr");

    // --- fifo_configure() ---
    I2CConnectionMock conn11;
    preloadCalibration(conn11);
    BMP384Full full11(conn11);
    full11.fifo_configure(true, true, 300, true);
    check_true(lastWriteTo(conn11, 0x17) == ((1 << 4) | (1 << 3) | (1 << 1) | 1), "fifo_configure_cfg1");
    check_true(lastWriteTo(conn11, 0x15) == (300 & 0xFF), "fifo_configure_wtm_lo");
    check_true(lastWriteTo(conn11, 0x16) == ((300 >> 8) & 0x01), "fifo_configure_wtm_hi");

    // --- fifo_read(): pressure, temperature, sensortime, error, empty, unknown frames ---
    I2CConnectionMock conn12;
    preloadCalibration(conn12);
    BMP384Full full12(conn12);
    constexpr size_t fifoLen = 15;
    conn12.setRegister(0x12, {(uint8_t)(fifoLen & 0xFF), (uint8_t)((fifoLen >> 8) & 0x01)});
    conn12.setRegister(0x14, {
        0x84, PRESS_BYTES[0], PRESS_BYTES[1], PRESS_BYTES[2],  // pressure frame
        0x90, TEMP_BYTES[0], TEMP_BYTES[1], TEMP_BYTES[2],     // temperature frame
        0xA0, 0x01, 0x02, 0x03,                                // sensortime frame
        0x44,                                                  // error frame
        0x80,                                                  // empty frame
        0xFF,                                                  // unknown header
    });
    full12._t_lin = EXPECTED_T_LIN; // so a lone pressure frame is comparable to the fixture
    const char* types[8];
    double values[8];
    size_t n = full12.fifo_read(types, values, 8);
    check_true(n == 6, "fifo_read_count");
    check_true(std::string(types[0]) == "pressure", "fifo_read_press_type");
    check_true(fabs(values[0] - EXPECTED_PRESSURE_HPA) < 1e-3, "fifo_read_press_value");
    check_true(std::string(types[1]) == "temperature", "fifo_read_temp_type");
    check_true(fabs(values[1] - EXPECTED_T_LIN) < 1e-3, "fifo_read_temp_value");
    check_true(std::string(types[2]) == "sensortime" && values[2] == (double)0x030201, "fifo_read_sensortime");
    check_true(std::string(types[3]) == "error", "fifo_read_error");
    check_true(std::string(types[4]) == "empty", "fifo_read_empty");
    check_true(std::string(types[5]) == "unknown", "fifo_read_unknown");

    // --- fifo_read(): empty FIFO returns 0 frames ---
    I2CConnectionMock conn13;
    preloadCalibration(conn13);
    BMP384Full full13(conn13);
    conn13.setRegister(0x12, {0x00, 0x00});
    const char* types13[4];
    double values13[4];
    check_true(full13.fifo_read(types13, values13, 4) == 0, "fifo_read_empty_fifo");

    // --- fifo_flush() ---
    I2CConnectionMock conn14;
    preloadCalibration(conn14);
    BMP384Full full14(conn14);
    full14.fifo_flush();
    check_true(lastWriteTo(conn14, 0x7E) == 0xB0, "fifo_flush_writes_cmd");

    // --- altitude(): fixture pressure (1447 hPa) is above the sea-level
    // reference, so altitude comes out negative ---
    I2CConnectionMock conn15;
    preloadCalibration(conn15);
    BMP384Full full15(conn15);
    setBurst(conn15);
    check_true(full15.altitude(1013.25f) < 0.0f, "altitude_negative_for_high_pressure");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
