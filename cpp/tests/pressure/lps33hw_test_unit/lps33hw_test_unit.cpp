#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include "I2CConnectionMock.h"
#include "Lps33hw.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Access protected register constants for building expected values.
class Lps33hwTestAccess : public LPS33HWFull {
public:
    using LPS33HWFull::LPS33HWFull;
    using LPS33HWFull::REG_CTRL_REG1;
    using LPS33HWFull::REG_CTRL_REG2;
    using LPS33HWFull::REG_RES_CONF;
    using LPS33HWFull::REG_INTERRUPT_CFG;
    using LPS33HWFull::REG_FIFO_CTRL;
    using LPS33HWFull::REG_THS_P_L;
    using LPS33HWFull::REG_THS_P_H;
    using LPS33HWFull::REG_REF_P_XL;
    using LPS33HWFull::REG_RPDS_L;
};

int main() {
    I2CConnectionMock connection;

    Lps33hwTestAccess sensor(connection);
    check_true(true, "init");

    // Constructor writes CTRL_REG2_RESET (0x04), then CTRL_REG2_DEFAULT (0x10),
    // then CTRL_REG1_DEFAULT (0x12). It also issues a WHO_AM_I read.
    bool sawCtrlReg2Reset = false, sawCtrlReg2Default = false, sawCtrlReg1Default = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == Lps33hwTestAccess::REG_CTRL_REG2 && w[1] == 0x04) sawCtrlReg2Reset = true;
        if (w.size() == 2 && w[0] == Lps33hwTestAccess::REG_CTRL_REG2 && w[1] == 0x10) sawCtrlReg2Default = true;
        if (w.size() == 2 && w[0] == Lps33hwTestAccess::REG_CTRL_REG1 && w[1] == 0x12) sawCtrlReg1Default = true;
    }
    check_true(sawCtrlReg2Reset, "init_writes_ctrl_reg2_reset");
    check_true(sawCtrlReg2Default, "init_writes_ctrl_reg2_default");
    check_true(sawCtrlReg1Default, "init_writes_ctrl_reg1_default");

    // configure(odr=2=10Hz, bdu=1, en_lpfp=1, lpfp_cfg=1, lc_en=0, sim=0):
    // CTRL_REG1 = (odr<<4)|(en_lpfp<<3)|(lpfp_cfg<<2)|(bdu<<1)|sim
    //           = (2<<4)|(1<<3)|(1<<2)|(1<<1)|0 = 0x20|0x08|0x04|0x02 = 0x2E.
    // RES_CONF writes (RES_CONF read returns 0x00 by default, so write is 0x00).
    sensor.configure(2, true, true, 1, false, false);
    bool sawConfigureCtrlReg1 = false, sawConfigureResConf = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == Lps33hwTestAccess::REG_CTRL_REG1 && w[1] == 0x2E) sawConfigureCtrlReg1 = true;
        if (w.size() == 2 && w[0] == Lps33hwTestAccess::REG_RES_CONF && w[1] == 0x00) sawConfigureResConf = true;
    }
    check_true(sawConfigureCtrlReg1, "configure_writes_ctrl_reg1");
    check_true(sawConfigureResConf, "configure_writes_res_conf");

    // enable_fifo(mode=FIFO=1, watermark=16):
    // FIFO_CTRL = ((mode&7)<<5) | (watermark&0x1F) = (1<<5) | 16 = 0x20|0x10 = 0x30.
    // Also writes CTRL_REG2 with F_EN bit (0x40) OR'd in.
    sensor.enable_fifo(1, 16);
    bool sawFifoCtrl = false, sawFifoFEn = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == Lps33hwTestAccess::REG_FIFO_CTRL && w[1] == 0x30) sawFifoCtrl = true;
        if (w.size() == 2 && w[0] == Lps33hwTestAccess::REG_CTRL_REG2 && (w[1] & 0x40)) sawFifoFEn = true;
    }
    check_true(sawFifoCtrl, "enable_fifo_writes_fifo_ctrl");
    check_true(sawFifoFEn, "enable_fifo_sets_f_en");

    // disable_fifo() clears F_EN and writes FIFO_CTRL = 0.
    sensor.disable_fifo();
    bool sawFifoBypass = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == Lps33hwTestAccess::REG_FIFO_CTRL && w[1] == 0x00) sawFifoBypass = true;
    }
    check_true(sawFifoBypass, "disable_fifo_writes_fifo_ctrl_bypass");

    // set_autozero(): reads INTERRUPT_CFG, OR's in AZ_EN (0x20), writes back.
    // cur is 0x00 by default, so write is 0x20.
    sensor.set_autozero();
    bool sawAzEn = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == Lps33hwTestAccess::REG_INTERRUPT_CFG && w[1] == 0x20) sawAzEn = true;
    }
    check_true(sawAzEn, "set_autozero_writes_az_en");

    // set_autorifp(): reads INTERRUPT_CFG (now 0x20 from above), OR's in AUTORIFP (0x80), writes back.
    // Result: 0x20 | 0x80 = 0xA0.
    sensor.set_autorifp();
    bool sawAutoRifp = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == Lps33hwTestAccess::REG_INTERRUPT_CFG && w[1] == 0xA0) sawAutoRifp = true;
    }
    check_true(sawAutoRifp, "set_autorifp_writes_autorifp");

    // configure_pressure_interrupt(high_en=1, low_en=0, threshold=1050 hPa, latch=1):
    // THS_P_L = (1050*16)&0xFF = 16800&0xFF = 0xA0
    // THS_P_H = (16800>>8)&0xFF = 0x41
    // INTERRUPT_CFG = (cur & 0xF0) | (latch?0x04:0) | (high_en?0x02:0) | (low_en?0x01:0)
    //              = (0xA0 & 0xF0) | 0x04 | 0x02 | 0 = 0xA0 | 0x06 = 0xA6
    sensor.configure_pressure_interrupt(true, false, 1050.0f, true);
    bool sawThsPL = false, sawThsPH = false, sawInterruptCfg = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == Lps33hwTestAccess::REG_THS_P_L && w[1] == 0xA0) sawThsPL = true;
        if (w.size() == 2 && w[0] == Lps33hwTestAccess::REG_THS_P_H && w[1] == 0x41) sawThsPH = true;
        if (w.size() == 2 && w[0] == Lps33hwTestAccess::REG_INTERRUPT_CFG && w[1] == 0xA6) sawInterruptCfg = true;
    }
    check_true(sawThsPL, "configure_pressure_interrupt_ths_p_l");
    check_true(sawThsPH, "configure_pressure_interrupt_ths_p_h");
    check_true(sawInterruptCfg, "configure_pressure_interrupt_cfg");

    // set_pressure_offset(0.5 hPa): 1 LSB = 1/16 hPa, so 0.5 hPa = 8 counts.
    // RPDS_L = 8; RPDS_H = 0.
    sensor.set_pressure_offset(0.5f);
    bool sawRpdsL = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == Lps33hwTestAccess::REG_RPDS_L && w[1] == 0x08) sawRpdsL = true;
    }
    check_true(sawRpdsL, "set_pressure_offset_writes_rpds_l");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}