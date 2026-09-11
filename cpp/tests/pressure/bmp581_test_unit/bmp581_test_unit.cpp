#include <stdio.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "BMP581.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

class BMP581TestAccess : public BMP581Full {
public:
    using BMP581Full::BMP581Full;
    using BMP581Full::REG_CHIP_ID;
    using BMP581Full::REG_STATUS;
    using BMP581Full::REG_INT_STATUS;
    using BMP581Full::REG_OSR_CONFIG;
    using BMP581Full::REG_ODR_CONFIG;
    using BMP581Full::REG_DSP_CONFIG;
    using BMP581Full::REG_DSP_IIR;
    using BMP581Full::REG_FIFO_SEL;
    using BMP581Full::REG_FIFO_CONFIG;
    using BMP581Full::REG_FIFO_COUNT;
    using BMP581Full::REG_FIFO_DATA;
    using BMP581Full::REG_INT_SOURCE;
    using BMP581Full::REG_INT_CONFIG;
    using BMP581Full::REG_OOR_THR_P_LSB;
    using BMP581Full::REG_OOR_THR_P_MSB;
    using BMP581Full::REG_OOR_RANGE;
    using BMP581Full::REG_OOR_CONFIG;
    using BMP581Full::REG_REV_ID;
    using BMP581Full::REG_OSR_EFF;
    using BMP581Full::REG_NVM_ADDR;
    using BMP581Full::REG_NVM_DATA_LSB;
    using BMP581Full::REG_NVM_DATA_MSB;
    using BMP581Full::REG_TEMP_XLSB;
    using BMP581Full::REG_PRESS_XLSB;
    using BMP581Full::REG_CMD;
};

int main() {
    I2CConnectionMock connection;
    connection.setRegister(BMP581TestAccess::REG_CHIP_ID, {0x50});
    connection.setRegister(BMP581TestAccess::REG_STATUS, {0x02});

    BMP581TestAccess sensor(connection);
    check_true(true, "init");

    bool sawOdrDefault = false, sawOsrDefault = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP581TestAccess::REG_ODR_CONFIG && w[1] == 0x71) sawOdrDefault = true;
        if (w.size() == 2 && w[0] == BMP581TestAccess::REG_OSR_CONFIG && w[1] == 0x40) sawOsrDefault = true;
    }
    check_true(sawOdrDefault, "init_writes_odr_default");
    check_true(sawOsrDefault, "init_writes_osr_default");

    connection.setRegister(BMP581TestAccess::REG_TEMP_XLSB, {0x00, 0x10, 0x00});
    connection.setRegister(BMP581TestAccess::REG_PRESS_XLSB, {0x04, 0x00, 0x00});
    check_true(fabsf(sensor.temperature() - 0.0625f) < 1e-6f, "temperature_decode");
    check_true(fabsf(sensor.pressure() - 0.0625f) < 1e-6f, "pressure_decode");

    float p, t;
    sensor.both(p, t);
    check_true(fabsf(p - 0.0625f) < 1e-6f, "both_pressure");
    check_true(fabsf(t - 0.0625f) < 1e-6f, "both_temperature");

    connection.setRegister(BMP581TestAccess::REG_CHIP_ID, {0x50});
    check_true(sensor.chip_id() == 0x50, "chip_id");

    connection.setRegister(BMP581TestAccess::REG_REV_ID, {0x32});
    check_true(sensor.rev_id() == 0x32, "rev_id");

    connection.setRegister(BMP581TestAccess::REG_STATUS, {0x09});
    check_true(sensor.status() == 0x09, "status");

    connection.setRegister(BMP581TestAccess::REG_INT_STATUS, {0x11});
    check_true(sensor.interrupt_status() == 0x11, "interrupt_status");

    connection.setRegister(BMP581TestAccess::REG_INT_STATUS, {0x01});
    check_true(sensor.data_ready() == true, "data_ready");

    sensor.configure(0x17, 4, 2, true);
    bool sawConfigureOdr = false, sawConfigureOsr = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP581TestAccess::REG_ODR_CONFIG && w[1] == ((0x17 << 2) | 0x01)) sawConfigureOdr = true;
        if (w.size() == 2 && w[0] == BMP581TestAccess::REG_OSR_CONFIG && w[1] == (0x40 | (4 << 3) | 2)) sawConfigureOsr = true;
    }
    check_true(sawConfigureOdr, "configure_odr_10Hz");
    check_true(sawConfigureOsr, "configure_osr_x16_x4");

    sensor.set_mode(BMP581Full::MODE_STANDBY);
    bool sawStandby = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP581TestAccess::REG_ODR_CONFIG && w[1] == ((0x17 << 2) | 0x00)) sawStandby = true;
    }
    check_true(sawStandby, "set_mode_standby");

    sensor.set_mode(BMP581Full::MODE_CONTINUOUS);
    bool sawContinuous = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP581TestAccess::REG_ODR_CONFIG && w[1] == ((0x17 << 2) | 0x03)) sawContinuous = true;
    }
    check_true(sawContinuous, "set_mode_continuous");

    sensor.set_iir_filter(BMP581Full::IIR_COEFF_3, BMP581Full::IIR_BYPASS);
    bool sawIirDsp = false, sawIirIir = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP581TestAccess::REG_DSP_CONFIG && (w[1] & 0x28) == 0x28) sawIirDsp = true;
        if (w.size() == 2 && w[0] == BMP581TestAccess::REG_DSP_IIR && w[1] == ((BMP581Full::IIR_COEFF_3 << 3) | BMP581Full::IIR_BYPASS)) sawIirIir = true;
    }
    check_true(sawIirDsp, "set_iir_filter_dsp");
    check_true(sawIirIir, "set_iir_filter_iir");

    sensor.enable_drdy_interrupt(true);
    bool sawDrdy = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP581TestAccess::REG_INT_SOURCE && (w[1] & 0x01)) sawDrdy = true;
    }
    check_true(sawDrdy, "enable_drdy_interrupt");

    sensor.enable_fifo_interrupt(true, false);
    bool sawFifoThs = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP581TestAccess::REG_INT_SOURCE && (w[1] & 0x04)) sawFifoThs = true;
    }
    check_true(sawFifoThs, "enable_fifo_threshold");

    sensor.enable_oor_interrupt(true);
    bool sawOor = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP581TestAccess::REG_INT_SOURCE && (w[1] & 0x08)) sawOor = true;
    }
    check_true(sawOor, "enable_oor_interrupt");

    sensor.configure_interrupt(1, 1, true, true);
    bool sawIntCfg = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP581TestAccess::REG_INT_CONFIG && w[1] == 0x0F) sawIntCfg = true;
    }
    check_true(sawIntCfg, "configure_interrupt");

    sensor.set_oor_threshold(110000.0f, 200.0f, 2);
    bool sawThrLsb = false, sawThrMsb = false, sawRange = false, sawCfg = false;
    int32_t thr17 = (int32_t)(110000.0f * 64.0f) >> 7;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP581TestAccess::REG_OOR_THR_P_LSB && w[1] == (thr17 & 0xFF)) sawThrLsb = true;
        if (w.size() == 2 && w[0] == BMP581TestAccess::REG_OOR_THR_P_MSB && w[1] == ((thr17 >> 8) & 0xFF)) sawThrMsb = true;
        if (w.size() == 2 && w[0] == BMP581TestAccess::REG_OOR_RANGE && w[1] == ((((int32_t)(200.0f * 64.0f) >> 7)) & 0xFF)) sawRange = true;
        if (w.size() == 2 && w[0] == BMP581TestAccess::REG_OOR_CONFIG && (w[1] & 0xC0) == (2 << 6)) sawCfg = true;
    }
    check_true(sawThrLsb, "oor_threshold_lsb");
    check_true(sawThrMsb, "oor_threshold_msb");
    check_true(sawRange, "oor_range");
    check_true(sawCfg, "oor_config_count_limit");

    sensor.configure_fifo(BMP581Full::FIFO_BOTH, BMP581Full::FIFO_STREAM, 8);
    bool sawFifoSel = false, sawFifoCfg = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP581TestAccess::REG_FIFO_SEL && w[1] == 0x03) sawFifoSel = true;
        if (w.size() == 2 && w[0] == BMP581TestAccess::REG_FIFO_CONFIG && w[1] == 8) sawFifoCfg = true;
    }
    check_true(sawFifoSel, "configure_fifo_sel");
    check_true(sawFifoCfg, "configure_fifo_config");

    connection.setRegister(BMP581TestAccess::REG_FIFO_COUNT, {4});
    check_true(sensor.fifo_count() == 4, "fifo_count");

    connection.setRegister(BMP581TestAccess::REG_FIFO_COUNT, {2});
    connection.setRegister(BMP581TestAccess::REG_FIFO_DATA, {
        0x00, 0x10, 0x00, 0x04, 0x00, 0x00,
        0x00, 0x10, 0x00, 0x04, 0x00, 0x00,
    });

    connection.setRegister(BMP581TestAccess::REG_OSR_EFF, {0xA0});
    uint8_t op, ot;
    sensor.effective_osr(op, ot);
    check_true(op == 4 && ot == 0, "effective_osr");
    check_true(sensor.odr_is_valid() == true, "odr_is_valid");

    connection.setRegister(BMP581TestAccess::REG_STATUS, {0x00});
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}