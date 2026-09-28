#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include "I2CConnectionMock.h"
#include "LPS28DFW.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void pack_press_raw(float hPa, float sens, uint8_t out[3]) {
    int32_t raw = (int32_t)lroundf(hPa * sens);
    if (raw < 0) raw += 0x1000000;
    out[0] = (uint8_t)(raw & 0xFF);
    out[1] = (uint8_t)((raw >> 8) & 0xFF);
    out[2] = (uint8_t)((raw >> 16) & 0xFF);
}

static void pack_temp_raw(float celsius, uint8_t out[2]) {
    int32_t raw = (int32_t)lroundf(celsius * 100.0f);
    if (raw < 0) raw += 0x10000;
    out[0] = (uint8_t)(raw & 0xFF);
    out[1] = (uint8_t)((raw >> 8) & 0xFF);
}

static I2CConnectionMock newConnection() {
    I2CConnectionMock c;
    c.setRegister(0x0F, {0xB4});  // WHO_AM_I
    return c;
}

static int lastWriteTo(const I2CConnectionMock& conn, uint8_t reg) {
    const auto& w = conn.writes();
    for (auto it = w.rbegin(); it != w.rend(); ++it) {
        if (it->size() == 2 && (*it)[0] == reg) return (*it)[1];
    }
    return -1;
}

int main() {
    // --- Minimal constructor: WHO_AM_I check, CTRL_REG2/CTRL_REG1 defaults ---
    I2CConnectionMock conn = newConnection();
    LPS28DFWMinimal chip(conn);
    check_true(lastWriteTo(conn, 0x11) == 0x18, "init_writes_ctrl_reg2");  // FS=0,LPF=1,BDU=1 -> 0x18
    check_true(lastWriteTo(conn, 0x10) == 0x22, "init_writes_ctrl_reg1");  // (4<<3)|2 = 0x22

    // --- read_pressure()/read_temperature(): Mode 1, known values ---
    uint8_t pbuf[3];
    pack_press_raw(1013.25f, 4096.0f, pbuf);
    conn.setRegister(0x28, {pbuf[0], pbuf[1], pbuf[2]});
    check_true(fabsf(chip.read_pressure() - 1013.25f) < 0.001f, "read_pressure_known");

    uint8_t tbuf[2];
    pack_temp_raw(23.5f, tbuf);
    conn.setRegister(0x2B, {tbuf[0], tbuf[1]});
    check_true(fabsf(chip.read_temperature() - 23.5f) < 0.001f, "read_temperature_known");

    // --- Negative pressure/temperature (sign extension) ---
    pack_press_raw(-50.0f, 4096.0f, pbuf);
    conn.setRegister(0x28, {pbuf[0], pbuf[1], pbuf[2]});
    check_true(fabsf(chip.read_pressure() - (-50.0f)) < 0.001f, "read_pressure_negative");
    pack_temp_raw(-10.0f, tbuf);
    conn.setRegister(0x2B, {tbuf[0], tbuf[1]});
    check_true(fabsf(chip.read_temperature() - (-10.0f)) < 0.001f, "read_temperature_negative");

    // --- Full: configure() writes CTRL_REG2 then CTRL_REG1 ---
    I2CConnectionMock fullConn = newConnection();
    LPS28DFWFull full(fullConn);
    full.configure(LPS28DFWFull::ODR_50_HZ, LPS28DFWFull::AVG_64, 1, true, 1);
    check_true(lastWriteTo(fullConn, 0x11) == 0x78, "configure_ctrl_reg2");  // (1<<6)|(1<<5)|(1<<4)|(1<<3)
    check_true(lastWriteTo(fullConn, 0x10) == 0x2C, "configure_ctrl_reg1");  // (5<<3)|4

    // --- read(): burst pressure+temperature, Mode 2 sensitivity ---
    pack_press_raw(2000.0f, 2048.0f, pbuf);
    fullConn.setRegister(0x28, {pbuf[0], pbuf[1], pbuf[2]});
    pack_temp_raw(18.25f, tbuf);
    fullConn.setRegister(0x2B, {tbuf[0], tbuf[1]});
    float pressure, temperature;
    full.read(pressure, temperature);
    check_true(fabsf(pressure - 2000.0f) < 0.001f, "read_pressure_mode2");
    check_true(fabsf(temperature - 18.25f) < 0.001f, "read_temperature");

    // --- is_data_ready() ---
    fullConn.setRegister(0x27, {0x01});
    check_true(full.is_data_ready() == 1, "is_data_ready_true");
    fullConn.setRegister(0x27, {0x00});
    check_true(full.is_data_ready() == 0, "is_data_ready_false");

    // --- read_oneshot(): saves/restores ODR, triggers ONESHOT, polls P_DA ---
    I2CConnectionMock oneshotConn = newConnection();
    LPS28DFWFull oneshot(oneshotConn);
    oneshotConn.setRegister(0x10, {0x22});  // saved CTRL_REG1 (ODR=4)
    oneshotConn.setRegister(0x11, {0x18});  // saved CTRL_REG2
    oneshotConn.setRegister(0x27, {0x01});  // P_DA already set
    pack_press_raw(1000.0f, 4096.0f, pbuf);
    oneshotConn.setRegister(0x28, {pbuf[0], pbuf[1], pbuf[2]});
    pack_temp_raw(20.0f, tbuf);
    oneshotConn.setRegister(0x2B, {tbuf[0], tbuf[1]});
    float osP, osT;
    oneshot.read_oneshot(osP, osT);
    check_true(fabsf(osP - 1000.0f) < 0.001f, "read_oneshot_result");
    check_true(lastWriteTo(oneshotConn, 0x10) == 0x22, "read_oneshot_restores_odr");

    // --- read_oneshot() timeout: bounded loop must not hang (200 * 5ms = 1s) ---
    I2CConnectionMock timeoutConn = newConnection();
    LPS28DFWFull timeoutSensor(timeoutConn);
    timeoutConn.setRegister(0x27, {0x00});  // P_DA never set
    float toP, toT;
    timeoutSensor.read_oneshot(toP, toT);  // must return, not hang
    check_true(true, "read_oneshot_bounded_no_hang");

    // --- set_offset(): packs signed 16-bit RPDS ---
    full.set_offset(-0.5f);  // Mode 2 active: -0.5*2048 = -1024 = 0xFC00
    check_true(lastWriteTo(fullConn, 0x1A) == 0x00, "set_offset_low");
    check_true(lastWriteTo(fullConn, 0x1B) == 0xFC, "set_offset_high");

    // --- softreset() --- (CTRL_REG2 is currently 0x78 from configure() above,
    // via the mock's auto-register-update; softreset() ORs in SWRESET=0x02)
    full.softreset();
    check_true(lastWriteTo(fullConn, 0x11) == 0x7A, "softreset_writes_swreset");

    // --- fifo_configure(): regression for missing unconditional Bypass pass-through ---
    I2CConnectionMock fifoConn = newConnection();
    LPS28DFWFull fifoChip(fifoConn);
    fifoChip.fifo_configure(LPS28DFWFull::FIFO_CONTINUOUS, 50, true);
    const auto& fifoWrites = fifoConn.writes();
    int fifoCtrlCount = 0, lastFifoCtrl = -1;
    bool sawBypassBeforeLast = false;
    for (size_t i = 0; i < fifoWrites.size(); i++) {
        if (fifoWrites[i].size() == 2 && fifoWrites[i][0] == 0x14) {
            fifoCtrlCount++;
            if (fifoWrites[i][1] == 0x00 && i + 1 < fifoWrites.size()) sawBypassBeforeLast = true;
            lastFifoCtrl = fifoWrites[i][1];
        }
    }
    check_true(sawBypassBeforeLast, "fifo_configure_bypass_pass_through");
    check_true(lastFifoCtrl == 0x0A, "fifo_configure_final_ctrl");  // TRIG=0,STOP=1,F_MODE=10 -> 0x0A
    check_true(lastWriteTo(fifoConn, 0x15) == 50, "fifo_configure_watermark");

    // --- fifo_read(): N=3 samples packed back-to-back ---
    uint8_t fifoBytes[9];
    float hpas[3] = {1000.0f, 1010.0f, 1020.0f};
    for (int i = 0; i < 3; i++) pack_press_raw(hpas[i], 4096.0f, &fifoBytes[i * 3]);
    fifoConn.setRegister(0x78, {fifoBytes[0], fifoBytes[1], fifoBytes[2],
                                 fifoBytes[3], fifoBytes[4], fifoBytes[5],
                                 fifoBytes[6], fifoBytes[7], fifoBytes[8]});
    float samples[3];
    fifoChip.fifo_read(3, samples);
    check_true(fabsf(samples[0] - 1000.0f) < 0.01f && fabsf(samples[1] - 1010.0f) < 0.01f
               && fabsf(samples[2] - 1020.0f) < 0.01f, "fifo_read_values");

    // --- fifo_level() ---
    fifoConn.setRegister(0x25, {42});
    check_true(fifoChip.fifo_level() == 42, "fifo_level");

    // --- set_threshold(): packs 15-bit unsigned THS_P + enables PHE/PLE ---
    I2CConnectionMock threshConn = newConnection();
    LPS28DFWFull threshChip(threshConn);
    threshConn.setRegister(0x0B, {0x00});
    threshChip.set_threshold(1020.0f, true, true);  // Mode 1: 1020*16=16320=0x3FC0
    check_true(lastWriteTo(threshConn, 0x0C) == 0xC0, "set_threshold_low");
    check_true(lastWriteTo(threshConn, 0x0D) == 0x3F, "set_threshold_high");
    check_true(lastWriteTo(threshConn, 0x0B) == 0x03, "set_threshold_enables_phe_ple");

    // --- chip_id() ---
    check_true(full.chip_id() == 0xB4, "chip_id");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
