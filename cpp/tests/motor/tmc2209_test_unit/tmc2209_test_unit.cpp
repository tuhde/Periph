#include <stdio.h>
#include <stdint.h>
#include <vector>
#include "UARTConnectionMock.h"
#include "TMC2209.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

class Pin : public OutputPin {
public:
    std::vector<bool> levels;
    void set(bool high) override { levels.push_back(high); }
    int highs() const { int n = 0; for (bool b : levels) if (b) n++; return n; }
};

int main() {
    // CRC vectors from the spec.
    {
        const uint8_t w[7] = {5, 0, 0x80, 0, 0, 1, 0xC1};
        const uint8_t r[3] = {5, 0, 6};
        check_true(UARTConnectionMock::crc8(w, 7) == 0x7F, "crc_write_gconf");
        check_true(UARTConnectionMock::crc8(r, 3) == 0x6F, "crc_read_ioin");
    }

    UARTConnectionMock mock;
    TMC2209Minimal motor(mock);
    check_true(motor.ok(), "init_ok");
    check_true(mock.lastWrite(0x00) == 0x1C1, "init_gconf");
    check_true(mock.lastWrite(0x01) == 0x07, "init_gstat_clear");
    check_true(mock.lastWrite(0x11) == 20, "init_tpowerdown");
    check_true(mock.lastWrite(0x70) == 0xC10D0024LL, "init_pwmconf");
    {
        const std::vector<uint8_t> gconf = {5, 0, 0x80, 0, 0, 1, 0xC1, 0x7F};
        const std::vector<uint8_t> ioin = {5, 0, 6, 0x6F};
        bool g = false, i = false;
        for (const auto& d : mock.datagrams()) { if (d == gconf) g = true; if (d == ioin) i = true; }
        check_true(g, "init_wrote_gconf_datagram");
        check_true(i, "init_read_request_ioin");
    }
    uint32_t chop = (uint32_t)mock.lastWrite(0x6C);
    check_true((chop & 0xF) == 3 && ((chop >> 15) & 3) == 2 && ((chop >> 4) & 7) == 5 &&
               ((chop >> 24) & 0xF) == 4 && ((chop >> 28) & 1) == 1, "init_chopconf_fields");
    uint32_t ih = (uint32_t)mock.lastWrite(0x10);
    check_true(((ih >> 8) & 0x1F) == 8 && (ih & 0x1F) == 2, "init_current_0_5a");
    check_true(((ih >> 16) & 0xF) == 6, "init_ihold_delay");

    motor.setCurrent(1.0f);
    check_true((((uint32_t)mock.lastWrite(0x10) >> 8) & 0x1F) == 17, "set_current_1a");
    motor.setCurrent(5.0f);
    check_true((((uint32_t)mock.lastWrite(0x10) >> 8) & 0x1F) == 31, "set_current_clamped");

    motor.run(200.0f);
    check_true(mock.lastWrite(0x22) == 4474, "run_forward");
    motor.run(-200.0f);
    check_true(mock.lastWrite(0x22) == ((-4474) & 0xFFFFFF), "run_reverse");
    motor.stop();
    check_true(mock.lastWrite(0x22) == 0, "stop");

    motor.disable();
    check_true((mock.lastWrite(0x6C) & 0xF) == 0, "disable_toff0");
    motor.enable();
    check_true((mock.lastWrite(0x6C) & 0xF) == 3, "enable_toff3");

    mock.regs[0x6F] = (1UL << 31) | 0x01 | 0x08 | 0x20;
    check_true(motor.isStandstill(), "standstill");
    check_true(motor.readFault() == (TMC2209Minimal::FAULT_OVERTEMP_WARNING |
                                     TMC2209Minimal::FAULT_SHORT_GND |
                                     TMC2209Minimal::FAULT_SHORT_SUPPLY), "fault_flags");

    {
        Pin pin;
        UARTConnectionMock m;
        TMC2209Minimal en(m, 0.11f, 0, &pin);
        check_true(pin.levels.size() == 1 && pin.levels[0], "en_pin_high_at_init");
        en.enable();
        check_true(!pin.levels.back(), "en_pin_low_on_enable");
    }
    {
        UARTConnectionMock m(false);
        TMC2209Minimal noecho(m, 0.11f, 0, nullptr, false);
        check_true(noecho.ok() && m.lastWrite(0x00) == 0x1C1, "no_echo_init");
    }
    {
        UARTConnectionMock m;
        m.regs[0x06] = 0;
        TMC2209Minimal bad(m);
        check_true(!bad.ok(), "bad_version_not_ok");
    }
    {
        UARTConnectionMock m;
        m.corruptNextReply = true;
        TMC2209Minimal flaky(m);
        check_true(flaky.ok() && m.lastWrite(0x00) == 0x1C1, "crc_retry");
    }

    // ---- Full ----
    UARTConnectionMock fm;
    Pin step, dir;
    TMC2209Full full(fm, 0.11f, 0, nullptr, true, &step, &dir);
    check_true(full.ok(), "full_init_ok");
    full.setMicrosteps(256);
    check_true((((uint32_t)fm.lastWrite(0x6C) >> 24) & 0xF) == 0, "microsteps_256");
    full.setMicrosteps(1);
    check_true((((uint32_t)fm.lastWrite(0x6C) >> 24) & 0xF) == 8, "microsteps_1");
    check_true(!full.setMicrosteps(3), "microsteps_invalid");
    full.setMicrosteps(32);
    full.run(100.0f);
    check_true(fm.lastWrite(0x22) == 4474, "run_uses_microsteps");
    full.setInterpolation(false);
    check_true((((uint32_t)fm.lastWrite(0x6C) >> 28) & 1) == 0, "intpol_off");
    full.setChopperMode(true);
    check_true((fm.lastWrite(0x00) & 0x4) == 0x4, "spreadcycle");
    full.setDirectionInverted(true);
    check_true((fm.lastWrite(0x00) & 0x8) == 0x8, "shaft");
    full.setIndexOutput(1);
    check_true((fm.lastWrite(0x00) & 0x30) == 0x10, "index_otpw");
    full.setDoubleEdge(true);
    check_true(((fm.lastWrite(0x6C) >> 29) & 1) == 1, "dedge");
    full.setDriverProtection(false, true);
    check_true(((fm.lastWrite(0x6C) >> 31) & 1) == 1 && ((fm.lastWrite(0x6C) >> 30) & 1) == 0, "diss2vs");
    full.setVsense(true);
    check_true(((fm.lastWrite(0x6C) >> 17) & 1) == 1, "vsense");
    full.setHoldCurrent(0.2f);
    check_true((fm.lastWrite(0x10) & 0x1F) == 6, "hold_current");
    full.setCurrentDelay(2, 100);
    check_true(((fm.lastWrite(0x10) >> 16) & 0xF) == 2 && fm.lastWrite(0x11) == 100, "current_delay");
    full.setStealthchopThreshold(300);
    check_true(fm.lastWrite(0x13) == 300, "tpwmthrs");
    full.configureChopper(4, 1, 3, 2);
    uint32_t c = (uint32_t)fm.lastWrite(0x6C);
    check_true((c & 0xF) == 4 && ((c >> 15) & 3) == 1 && ((c >> 4) & 7) == 3 && ((c >> 7) & 0xF) == 2,
               "configure_chopper");
    full.configureStealthchop(true, false, 40, 5, 1, 4, 12);
    uint32_t p = (uint32_t)fm.lastWrite(0x70);
    check_true((p & 0xFF) == 40 && ((p >> 8) & 0xFF) == 5 && ((p >> 16) & 3) == 1 &&
               ((p >> 18) & 1) == 1 && ((p >> 19) & 1) == 0 && ((p >> 24) & 0xF) == 4 &&
               (p >> 28) == 12, "configure_stealthchop");
    full.setFreewheel(1);
    check_true(((fm.lastWrite(0x70) >> 20) & 3) == 1, "freewheel");
    full.setSendDelay(4);
    check_true(fm.lastWrite(0x03) == (4 << 8), "send_delay");
    check_true(!full.setSendDelay(16), "send_delay_range");
    full.setInternalRsense(true);
    check_true(!full.setCurrent(0.5f), "internal_rsense_current");
    full.setInternalRsense(false);

    fm.regs[0x6A] = 513;
    fm.regs[0x6B] = (0x1FFUL << 16) | 0x100;
    fm.regs[0x12] = 1234;
    fm.regs[0x6F] = (1UL << 31) | (1UL << 30) | (17UL << 16) | (0x5UL << 8) | 0x02;
    fm.regs[0x71] = (0x1FEUL << 16) | 77;
    fm.regs[0x72] = (9UL << 16) | 33;
    fm.regs[0x01] = 0x03;
    fm.regs[0x05] = 0x123456;
    check_true(full.microstepPosition() == 513, "microstep_position");
    TMC2209Full::CoilCurrents cc = full.readCoilCurrents();
    check_true(cc.low == -256 && cc.high == -1, "coil_currents");
    check_true(full.readTstep() == 1234, "tstep");
    check_true(full.readCurrentScale() == 17, "current_scale");
    check_true(full.readTemperatureFlags() == 5, "temp_flags");
    TMC2209Full::Status st = full.readStatus();
    check_true(st.stst && st.stealth && st.ot && st.csActual == 17 && st.t120 && st.t150 && !st.t143, "status");
    TMC2209Full::GlobalStatus gs = full.readGlobalStatus();
    check_true(gs.reset && gs.drvErr && !gs.uvCp, "global_status");
    full.readGlobalStatus(true);
    check_true(fm.lastWrite(0x01) == 0x07, "global_status_clear");
    TMC2209Full::PwmAuto pa = full.readPwmAuto();
    check_true(pa.pwmScaleSum == 77 && pa.pwmScaleAuto == -2 && pa.pwmOfsAuto == 33 && pa.pwmGradAuto == 9,
               "pwm_auto");
    fm.regs[0x06] = 0x21000000UL | 0x04 | 0x10 | 0x200;
    TMC2209Full::Ioin io = full.readIoin();
    check_true(io.ms1 && io.diag && io.dir && !io.ms2 && io.version == 0x21, "ioin");
    check_true(full.readOtp() == 0x123456, "otp");
    check_true(full.readIfcnt() == fm.regs[0x02], "ifcnt");
    check_true(full.readRegister(0x6A) == 513 && full.ok(), "read_register");
    full.readRegister(0x10);
    check_true(!full.ok(), "read_register_writeonly");
    check_true(full.writeRegister(0x13, 5) && fm.lastWrite(0x13) == 5, "write_register");
    check_true(!full.writeRegister(0x04, 1), "write_register_otp_prog");

    check_true(full.step(3, 0.0f) && step.highs() == 3 && dir.levels.back(), "step_pulses");
    full.step(-2, 0.0f);
    check_true(!dir.levels.back(), "step_reverse");
    {
        UARTConnectionMock m;
        TMC2209Full nopin(m);
        check_true(!nopin.step(1, 0.0f), "step_requires_pin");
    }
    {
        UARTConnectionMock m;
        m.regs[0x06] = 0x20000100UL;
        TMC2209Minimal old(m);
        check_true(!old.ok(), "tmc2208_rejected");
    }
    {
        UARTConnectionMock m;
        TMC2209Minimal addr(m, 0.11f, 2);
        bool all = addr.ok() && !m.datagrams().empty();
        for (const auto& d : m.datagrams()) if (d[1] != 2) all = false;
        check_true(all, "address_in_datagrams");
        const std::vector<uint8_t>& g = m.datagrams()[2];
        check_true(g.size() == 8 && g[7] == UARTConnectionMock::crc8(g.data(), 7), "address_write_crc");
        UARTConnectionMock m2;
        TMC2209Minimal bad(m2, 0.11f, 4);
        check_true(!bad.ok() && m2.datagrams().empty(), "address_range");
    }
    {
        UARTConnectionMock m;
        TMC2209Full mn(m, 0.11f, 0, nullptr, true, nullptr, nullptr, nullptr, true);
        check_true(mn.ok() && m.writes()[0].reg == 0x03 && m.writes()[0].value == (2UL << 8), "multi_node_senddelay");
        check_true(m.regs[0x02] == 7, "multi_node_ifcnt");
    }
    {
        full.setStallThreshold(60);
        check_true(fm.lastWrite(0x40) == 60, "sgthrs");
        check_true(full.setCoolstepThreshold(500) && fm.lastWrite(0x14) == 500, "tcoolthrs");
        check_true(!full.setCoolstepThreshold(1UL << 20), "tcoolthrs_range");
        check_true(full.configureCoolstep(5, 2, 1, 1, 0) &&
                   fm.lastWrite(0x42) == (5 | (1 << 5) | (2 << 8) | (1 << 13)), "coolconf");
        check_true(full.configureCoolstep(0, 0, 0, 0, 1) && fm.lastWrite(0x42) == (1 << 15), "coolconf_seimin");
        check_true(!full.configureCoolstep(16, 0, 0, 0, 0), "coolconf_range");
        fm.regs[0x41] = 0x1FE;
        check_true(full.readStallguard() == 510, "read_stallguard");
        full.isStalled();
        check_true(!full.ok(), "is_stalled_requires_pin");
        class HighPin : public InputPin {
        public:
            bool onEdge(Handler, uint8_t) override { return true; }
            void offEdge(Handler) override {}
            bool isHigh() override { return true; }
        } diag;
        UARTConnectionMock m;
        TMC2209Full sf(m, 0.11f, 0, nullptr, true, nullptr, nullptr, &diag);
        check_true(sf.ok() && sf.isStalled(), "is_stalled");
    }

    printf("===DONE=== passed=%d failed=%d\n", passed, failed);
    return failed ? 1 : 0;
}
