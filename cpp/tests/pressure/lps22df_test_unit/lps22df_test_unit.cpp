#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include "I2CConnectionMock.h"
#include "LPS22DF.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Access protected register constants for building expected values.
class LPS22DFTestAccess : public LPS22DFFull {
public:
    using LPS22DFFull::LPS22DFFull;
    using LPS22DFFull::REG_WHO_AM_I;
    using LPS22DFFull::REG_CTRL_REG1;
    using LPS22DFFull::REG_CTRL_REG2;
    using LPS22DFFull::REG_CTRL_REG3;
    using LPS22DFFull::REG_CTRL_REG4;
    using LPS22DFFull::REG_INTERRUPT_CFG;
    using LPS22DFFull::REG_THS_P_L;
    using LPS22DFFull::REG_THS_P_H;
    using LPS22DFFull::REG_RPDS_L;
    using LPS22DFFull::REG_RPDS_H;
    using LPS22DFFull::REG_FIFO_CTRL;
    using LPS22DFFull::REG_FIFO_WTM;
    using LPS22DFFull::REG_REF_P_L;
    using LPS22DFFull::REG_PRESS_OUT_XL;
    using LPS22DFFull::REG_TEMP_OUT_L;
    using LPS22DFFull::REG_STATUS;
    using LPS22DFFull::REG_FIFO_STATUS1;
    using LPS22DFFull::REG_INT_SOURCE;
    using LPS22DFFull::REG_FIFO_PRESS_XL;
};

static void pack_press_raw(float hPa, uint8_t out[3]) {
    int32_t raw = (int32_t)lroundf(hPa * 4096.0f);
    if (raw < 0) raw += 0x1000000;
    out[0] = raw & 0xFF;
    out[1] = (raw >> 8) & 0xFF;
    out[2] = (raw >> 16) & 0xFF;
}

static void pack_temp_raw(float celsius, uint8_t out[2]) {
    int16_t raw = (int16_t)lroundf(celsius * 100.0f);
    out[0] = raw & 0xFF;
    out[1] = (raw >> 8) & 0xFF;
}

int main() {
    I2CConnectionMock connection;
    connection.setRegister(LPS22DFTestAccess::REG_WHO_AM_I, {0xB4});

    LPS22DFTestAccess sensor(connection);
    check_true(true, "init");

    // --- Init writes: SWRESET=0x04, CTRL_REG1=0x18, CTRL_REG2=0x08 ---
    bool sawSwReset = false, sawCtrl1Default = false, sawCtrl2Bdu = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == LPS22DFTestAccess::REG_CTRL_REG2 && w[1] == 0x04) sawSwReset = true;
        if (w.size() == 2 && w[0] == LPS22DFTestAccess::REG_CTRL_REG1 && w[1] == 0x18) sawCtrl1Default = true;
    }
    // Second CTRL_REG2 write is BDU
    const auto& writes = connection.writes();
    for (size_t i = writes.size(); i-- > 0; ) {
        const auto& w = writes[i];
        if (!sawCtrl2Bdu && w.size() == 2 && w[0] == LPS22DFTestAccess::REG_CTRL_REG2 && w[1] == 0x08) {
            sawCtrl2Bdu = true;
        }
    }
    check_true(sawSwReset, "init_writes_swreset");
    check_true(sawCtrl1Default, "init_writes_ctrl_reg1_default");
    check_true(sawCtrl2Bdu, "init_writes_ctrl_reg2_bdu");

    // --- pressure(): known hPa -> known Pa ---
    connection.setRegister(LPS22DFTestAccess::REG_STATUS, {0x01});
    uint8_t p_raw[3];
    pack_press_raw(1013.25f, p_raw);
    connection.setRegister(LPS22DFTestAccess::REG_PRESS_OUT_XL, {p_raw[0], p_raw[1], p_raw[2]});
    float p = sensor.pressure();
    check_true(fabsf(p - 101325.0f) < 0.01f, "pressure_known_value");

    // --- temperature(): known °C ---
    uint8_t t_raw[2];
    pack_temp_raw(23.5f, t_raw);
    connection.setRegister(LPS22DFTestAccess::REG_TEMP_OUT_L, {t_raw[0], t_raw[1]});
    float t = sensor.temperature();
    check_true(fabsf(t - 23.5f) < 0.01f, "temperature_known_value");

    // --- configure(odr=4, avg=2, en_lpfp=true, lfpf_cfg=1, bdu=true) ---
    // CTRL_REG1 = (4<<3)|2 = 0x22; CTRL_REG2 = 0x10|0x20|0x08 = 0x38
    sensor.configure(4, 2, true, 1, true);
    bool sawConfigCtrl1 = false, sawConfigCtrl2 = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == LPS22DFTestAccess::REG_CTRL_REG1 && w[1] == 0x22) sawConfigCtrl1 = true;
        if (w.size() == 2 && w[0] == LPS22DFTestAccess::REG_CTRL_REG2 && w[1] == 0x38) sawConfigCtrl2 = true;
    }
    check_true(sawConfigCtrl1, "configure_ctrl_reg1");
    check_true(sawConfigCtrl2, "configure_ctrl_reg2");

    // --- altitude: known pressure -> known altitude ---
    connection.setRegister(LPS22DFTestAccess::REG_STATUS, {0x01});
    pack_press_raw(900.0f, p_raw);
    connection.setRegister(LPS22DFTestAccess::REG_PRESS_OUT_XL, {p_raw[0], p_raw[1], p_raw[2]});
    float alt = sensor.altitude(101325.0f);
    check_true(fabsf(alt - 989.0f) < 5.0f, "altitude_known_value");

    // --- set_pressure_offset(-50 Pa) -> -0.5 hPa * 4096 = -2048 = 0xF800 ---
    sensor.set_pressure_offset(-50.0f);
    bool sawRpdsL = false, sawRpdsH = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == LPS22DFTestAccess::REG_RPDS_L && w[1] == 0x00) sawRpdsL = true;
        if (w.size() == 2 && w[0] == LPS22DFTestAccess::REG_RPDS_H && w[1] == 0xF8) sawRpdsH = true;
    }
    check_true(sawRpdsL, "pressure_offset_l");
    check_true(sawRpdsH, "pressure_offset_h");

    // --- set_pressure_threshold(102000 Pa) -> 1020 hPa * 16 = 16320 = 0x3FC0 ---
    sensor.set_pressure_threshold(102000.0f);
    bool sawThsL = false, sawThsH = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == LPS22DFTestAccess::REG_THS_P_L && w[1] == 0xC0) sawThsL = true;
        if (w.size() == 2 && w[0] == LPS22DFTestAccess::REG_THS_P_H && w[1] == 0x3F) sawThsH = true;
    }
    check_true(sawThsL, "threshold_l");
    check_true(sawThsH, "threshold_h");

    // --- configure_interrupt writes CTRL_REG3 + CTRL_REG4 ---
    sensor.configure_interrupt(true, true, true, true, true, true, true, true);
    // CTRL_REG3 = 0x01 | 0x08 | 0x02 = 0x0B; CTRL_REG4 = 0x77
    bool sawCtrl3 = false, sawCtrl4 = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == LPS22DFTestAccess::REG_CTRL_REG3 && w[1] == 0x0B) sawCtrl3 = true;
        if (w.size() == 2 && w[0] == LPS22DFTestAccess::REG_CTRL_REG4 && w[1] == 0x77) sawCtrl4 = true;
    }
    check_true(sawCtrl3, "configure_interrupt_ctrl_reg3");
    check_true(sawCtrl4, "configure_interrupt_ctrl_reg4");

    // --- configure_pressure_event(phe, ple, lir) all true -> 0x07 ---
    sensor.configure_pressure_event(true, true, true);
    bool sawICfg = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == LPS22DFTestAccess::REG_INTERRUPT_CFG && w[1] == 0x07) sawICfg = true;
    }
    check_true(sawICfg, "configure_pressure_event");

    // --- autozero() -> 0x20 ---
    sensor.autozero();
    bool sawAutozero = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == LPS22DFTestAccess::REG_INTERRUPT_CFG && w[1] == 0x20) sawAutozero = true;
    }
    check_true(sawAutozero, "autozero");

    // --- set_fifo_mode(1) -> FIFO_CTRL = 0x01 ---
    sensor.set_fifo_mode(LPS22DFFull::FIFO_FIFO);
    bool sawFifo = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == LPS22DFTestAccess::REG_FIFO_CTRL && w[1] == 0x01) sawFifo = true;
    }
    check_true(sawFifo, "set_fifo_mode_fifo");

    // --- set_fifo_watermark(100) ---
    sensor.set_fifo_watermark(100);
    bool sawWtm = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == LPS22DFTestAccess::REG_FIFO_WTM && w[1] == 100) sawWtm = true;
    }
    check_true(sawWtm, "set_fifo_watermark");

    // --- reference_pressure() ---
    connection.setRegister(LPS22DFTestAccess::REG_REF_P_L, {0x00, 0x10});  // 4096 = 1.0 hPa = 100 Pa
    float ref = sensor.reference_pressure();
    check_true(fabsf(ref - 100.0f) < 0.01f, "reference_pressure");

    // --- interrupt_source() ---
    connection.setRegister(LPS22DFTestAccess::REG_INT_SOURCE, {0x87});
    check_true(sensor.interrupt_source() == 0x87, "interrupt_source");

    // --- read_fifo: N=3 samples ---
    connection.setRegister(LPS22DFTestAccess::REG_FIFO_STATUS1, {3});
    uint8_t fifo_raw[9];
    for (int i = 0; i < 3; i++) {
        pack_press_raw(1000.0f + i * 10.0f, &fifo_raw[i * 3]);
    }
    connection.setRegister(LPS22DFTestAccess::REG_FIFO_PRESS_XL, {fifo_raw[0], fifo_raw[1], fifo_raw[2], fifo_raw[3], fifo_raw[4], fifo_raw[5], fifo_raw[6], fifo_raw[7], fifo_raw[8]});
    float samples[16];
    uint8_t n = sensor.read_fifo(samples, 16);
    check_true(n == 3, "read_fifo_count");
    check_true(fabsf(samples[0] - 100000.0f) < 0.01f, "read_fifo_sample_0");
    check_true(fabsf(samples[1] - 101000.0f) < 0.01f, "read_fifo_sample_1");
    check_true(fabsf(samples[2] - 102000.0f) < 0.01f, "read_fifo_sample_2");

    // --- SPI transport ---
    I2CConnectionMock spiConnection;
    spiConnection.setRegister(LPS22DFTestAccess::REG_WHO_AM_I, {0xB4});
    LPS22DFTestAccess spiSensor(spiConnection, true);
    bool sawMaskedCtrl1 = false, sawUnmaskedCtrl1 = false;
    for (const auto& w : spiConnection.writes()) {
        if (w.size() == 2 && w[0] == (LPS22DFTestAccess::REG_CTRL_REG1 & 0x7F) && w[1] == 0x18) sawMaskedCtrl1 = true;
        if (w.size() == 2 && w[0] == LPS22DFTestAccess::REG_CTRL_REG1) sawUnmaskedCtrl1 = true;
    }
    check_true(sawMaskedCtrl1, "spi_init_writes_ctrl_reg1_masked");
    check_true(!sawUnmaskedCtrl1, "spi_init_no_unmasked_ctrl_reg1_write");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}