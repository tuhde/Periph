#include <stdio.h>
#include <stdint.h>
#include <cmath>
#include "SPIConnectionMock.h"
#include "ADXL362.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool close_f(float a, float b, float eps = 1e-6f) {
    return std::fabs(a - b) < eps;
}

static SPIConnectionMock newConnection() {
    SPIConnectionMock c;
    c.setRegister(0x00, {0xAD, 0x1D, 0xF2, 0x01});
    return c;
}

int main() {
    // --- Construction: device-ID triple check, FILTER_CTL/POWER_CTL writes ---
    SPIConnectionMock conn = newConnection();
    ADXL362Minimal chip(conn);
    const auto& w = conn.writes();
    check_true(w[w.size() - 2] == std::vector<uint8_t>({0x0A, 0x2C, 0x13}), "init_writes_filter_ctl");
    check_true(w[w.size() - 1] == std::vector<uint8_t>({0x0A, 0x2D, 0x02}), "init_writes_power_ctl");

    // --- read(): 12-bit sign-extended XYZ at +-2g (0.001 g/LSB) ---
    conn.setRegister(0x0E, {0x64, 0x00, 0xCE, 0x0F, 0xD0, 0x07}); // x=100,y=-50,z=2000 raw
    float x, y, z;
    chip.read(x, y, z);
    check_true(close_f(x, 0.1f), "read_x");
    check_true(close_f(y, -0.05f), "read_y");
    check_true(close_f(z, 2.0f), "read_z");

    // --- ADXL362Full ---
    SPIConnectionMock fullConn = newConnection();
    ADXL362Full full(fullConn);

    // device_id()
    fullConn.setRegister(0x00, {0xAD, 0x1D, 0xF2, 0x07});
    uint8_t devidAd, devidMst, partid, revid;
    full.device_id(devidAd, devidMst, partid, revid);
    check_true(devidAd == 0xAD && devidMst == 0x1D && partid == 0xF2 && revid == 0x07, "device_id");

    // soft_reset()
    full.soft_reset();
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x0A, 0x1F, 0x52}), "soft_reset_writes_key");

    // set_range(): read-modify-write FILTER_CTL
    fullConn.setRegister(0x2C, {0x13});
    full.set_range(4);
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x0A, 0x2C, 0x53}), "set_range_4g");
    fullConn.setRegister(0x2C, {0x53});
    full.set_range(8);
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x0A, 0x2C, 0x93}), "set_range_8g");

    // set_odr(): nearest supported rate
    fullConn.setRegister(0x2C, {0x93});
    full.set_odr(60.0f); // nearest of 50/100 -> 50 Hz (code 0x02)
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x0A, 0x2C, 0x92}), "set_odr_nearest");

    // set_half_bandwidth()
    fullConn.setRegister(0x2C, {0x00});
    full.set_half_bandwidth(true);
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x0A, 0x2C, 0x10}), "set_half_bandwidth_on");
    fullConn.setRegister(0x2C, {0x10});
    full.set_half_bandwidth(false);
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x0A, 0x2C, 0x00}), "set_half_bandwidth_off");

    // set_noise_mode()
    fullConn.setRegister(0x2D, {0x02});
    full.set_noise_mode(ADXL362Full::NOISE_ULTRALOW);
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x0A, 0x2D, 0x22}), "set_noise_mode_ultralow");

    // set_wakeup_mode()
    fullConn.setRegister(0x2D, {0x22});
    full.set_wakeup_mode(true);
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x0A, 0x2D, 0x2A}), "set_wakeup_mode_on");

    // set_autosleep()
    fullConn.setRegister(0x2D, {0x2A});
    full.set_autosleep(true);
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x0A, 0x2D, 0x2E}), "set_autosleep_on");

    // set_external_clock()
    fullConn.setRegister(0x2D, {0x2E});
    full.set_external_clock(true);
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x0A, 0x2D, 0x6E}), "set_external_clock_on");

    // set_external_sample_trigger()
    fullConn.setRegister(0x2C, {0x92});
    full.set_external_sample_trigger(true);
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x0A, 0x2C, 0x9A}), "set_external_sample_trigger_on");

    // read_8bit(): signed 8-bit, 16x LSB scale (range is currently 8g from set_range(8) above)
    fullConn.setRegister(0x08, {100, 206, 50}); // x=100, y=-50 (0xCE), z=50
    float rx8, ry8, rz8;
    full.read_8bit(rx8, ry8, rz8);
    float sens8 = 0.004255f * 16.0f;
    check_true(close_f(rx8, 100 * sens8), "read_8bit_x");
    check_true(close_f(ry8, -50 * sens8), "read_8bit_y");
    check_true(close_f(rz8, 50 * sens8), "read_8bit_z");

    // temperature(): bias=350 LSB @25C, 0.065 C/LSB -> raw 427 = 30 C
    fullConn.setRegister(0x14, {0xAB, 0x01});
    check_true(close_f(full.temperature(), 30.0f, 0.01f), "temperature");

    // status()/awake()/data_ready()
    fullConn.setRegister(0x0B, {0x41}); // AWAKE + DATA_READY
    check_true(full.status() == 0x41, "status_raw");
    check_true(full.awake() == true, "awake_true");
    check_true(full.data_ready() == true, "data_ready_true");
    fullConn.setRegister(0x0B, {0x00});
    check_true(full.awake() == false, "awake_false");

    // fifo_entries(): 10-bit count from FIFO_ENTRIES_L/H
    fullConn.setRegister(0x0C, {0xFF, 0x01}); // 0x1FF = 511
    check_true(full.fifo_entries() == 0x1FF, "fifo_entries");

    // configure_fifo(): FIFO_CONTROL (AH/FIFO_TEMP/FIFO_MODE) + FIFO_SAMPLES
    full.configure_fifo(ADXL362Full::FIFO_STREAM, true, 300);
    const auto& w2 = fullConn.writes();
    check_true(w2[w2.size() - 2] == std::vector<uint8_t>({0x0A, 0x28, 0x0E}), "configure_fifo_control");
    check_true(w2[w2.size() - 1] == std::vector<uint8_t>({0x0A, 0x29, 0x2C}), "configure_fifo_samples");

    // read_fifo(): decodes axis + value per entry, including temperature axis
    fullConn.setRegister(0x0C, {2, 0}); // 2 entries
    fullConn.setRegister(0x0D, {100, 0, 171, 193}); // FIFO read command is [0x0D] alone
    uint8_t axisOut[8];
    float valueOut[8];
    uint16_t n = full.read_fifo(axisOut, valueOut, 8);
    check_true(n == 2, "read_fifo_count");
    check_true(axisOut[0] == ADXL362Full::AXIS_X, "read_fifo_axis0");
    check_true(close_f(valueOut[0], 100 * 0.004255f), "read_fifo_value0");
    check_true(axisOut[1] == ADXL362Full::AXIS_TEMP, "read_fifo_axis1");
    check_true(close_f(valueOut[1], 30.0f, 0.01f), "read_fifo_value1");

    fullConn.setRegister(0x0C, {0, 0});
    check_true(full.read_fifo(axisOut, valueOut, 8) == 0, "read_fifo_empty");

    // set_activity_threshold(): regression for the 11-bit (not 10-bit) H-register bug.
    fullConn.setRegister(0x2C, {0x92});
    full.set_range(2);
    fullConn.setRegister(0x27, {0x00});
    full.set_activity_threshold(1.5f, true);
    // raw = round(1.5 / 0.001) = 1500 = 0x5DC -> L=0xDC, H bits[10:8]=0x05.
    // writes.back()-2 is the read_reg(ACT_INACT_CTL) command phase, not a write.
    const auto& w3 = fullConn.writes();
    check_true(w3[w3.size() - 4] == std::vector<uint8_t>({0x0A, 0x20, 0xDC}), "activity_threshold_low_byte");
    check_true(w3[w3.size() - 3] == std::vector<uint8_t>({0x0A, 0x21, 0x05}), "activity_threshold_high_byte_11bit");
    check_true(w3[w3.size() - 1] == std::vector<uint8_t>({0x0A, 0x27, 0x02}), "activity_threshold_referenced");

    // set_activity_time()
    full.set_activity_time(200);
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x0A, 0x22, 200}), "activity_time");

    // set_inactivity_threshold(): same 11-bit regression, absolute (not referenced)
    fullConn.setRegister(0x27, {0x00});
    full.set_inactivity_threshold(1.5f, false);
    const auto& w4 = fullConn.writes();
    check_true(w4[w4.size() - 4] == std::vector<uint8_t>({0x0A, 0x23, 0xDC}), "inactivity_threshold_low_byte");
    check_true(w4[w4.size() - 3] == std::vector<uint8_t>({0x0A, 0x24, 0x05}), "inactivity_threshold_high_byte_11bit");
    check_true(w4[w4.size() - 1] == std::vector<uint8_t>({0x0A, 0x27, 0x00}), "inactivity_threshold_absolute");

    // set_inactivity_time(): 16-bit
    full.set_inactivity_time(0x1234);
    const auto& w5 = fullConn.writes();
    check_true(w5[w5.size() - 2] == std::vector<uint8_t>({0x0A, 0x25, 0x34}), "inactivity_time_low");
    check_true(w5[w5.size() - 1] == std::vector<uint8_t>({0x0A, 0x26, 0x12}), "inactivity_time_high");

    // enable_activity_detection() / enable_inactivity_detection()
    fullConn.setRegister(0x27, {0x00});
    full.enable_activity_detection(true);
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x0A, 0x27, 0x01}), "enable_activity_detection");
    fullConn.setRegister(0x27, {0x01});
    full.enable_inactivity_detection(true);
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x0A, 0x27, 0x05}), "enable_inactivity_detection");

    // set_link_loop_mode()
    fullConn.setRegister(0x27, {0x05});
    full.set_link_loop_mode(ADXL362Full::LINKLOOP_LOOP);
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x0A, 0x27, 0x35}), "set_link_loop_mode");

    // set_interrupt() / set_interrupt_polarity()
    fullConn.setRegister(0x2A, {0x00});
    full.set_interrupt(1, ADXL362Full::SOURCE_AWAKE, true);
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x0A, 0x2A, 0x40}), "set_interrupt_int1_awake");
    fullConn.setRegister(0x2B, {0x00});
    full.set_interrupt(2, ADXL362Full::SOURCE_FIFO_WATERMARK, true);
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x0A, 0x2B, 0x04}), "set_interrupt_int2_watermark");

    fullConn.setRegister(0x2A, {0x40});
    full.set_interrupt_polarity(1, true);
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x0A, 0x2A, 0xC0}), "set_interrupt_polarity_active_low");

    // self_test()
    fullConn.setRegister(0x2E, {0x00});
    full.self_test(true);
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x0A, 0x2E, 0x01}), "self_test_on");
    fullConn.setRegister(0x2E, {0x01});
    full.self_test(false);
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x0A, 0x2E, 0x00}), "self_test_off");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
