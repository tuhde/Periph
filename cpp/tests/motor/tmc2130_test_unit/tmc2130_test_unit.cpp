#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <map>
#include <vector>
#include "TMC2130.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

/** Scripted TMC2130 implementing the pipelined 40-bit datagram protocol. */
class ChipSim : public Connection {
public:
    struct RegWrite { uint8_t reg; uint32_t value; };
    std::map<uint8_t, uint32_t> regs;
    std::vector<std::vector<uint8_t>> frames;
    std::vector<RegWrite> writes;
    uint32_t pending = 0;
    bool mirrorBroken = false;
    int gstatReads = 0;

    ChipSim() { regs[0x04] = 0x11000040UL; regs[0x01] = 0x01; }

    uint32_t lastWrite(uint8_t reg) const {
        for (size_t i = writes.size(); i-- > 0;) if (writes[i].reg == reg) return writes[i].value;
        return 0xDEADBEEFUL;
    }

protected:
    uint32_t datagram(const uint8_t* data, size_t len) {
        std::vector<uint8_t> f(5, 0);
        memcpy(f.data(), data, len < 5 ? len : 5);
        frames.push_back(f);
        uint32_t reply = pending;
        uint8_t reg = f[0] & 0x7F;
        if (f[0] & 0x80) {
            uint32_t v = ((uint32_t)f[1] << 24) | ((uint32_t)f[2] << 16) | ((uint32_t)f[3] << 8) | f[4];
            regs[reg] = v;
            writes.push_back({reg, v});
            pending = mirrorBroken ? 0 : v;
        } else {
            if (reg == 0x01) gstatReads++;
            pending = regs.count(reg) ? regs[reg] : 0;
            if (reg == 0x01) regs[0x01] = 0;
        }
        return reply;
    }
    void _write(const uint8_t* data, size_t len) override { datagram(data, len); }
    void _read(uint8_t* buf, size_t len) override { memset(buf, 0, len); }
    void _write_read(const uint8_t* data, size_t data_len, uint8_t* buf, size_t buf_len) override {
        uint32_t r = datagram(data, data_len);
        uint8_t b[4] = {(uint8_t)(r >> 24), (uint8_t)(r >> 16), (uint8_t)(r >> 8), (uint8_t)r};
        for (size_t i = 0; i < buf_len; i++) buf[i] = i < 4 ? b[i] : 0;
    }
};

class Pin : public OutputPin {
public:
    std::vector<bool> levels;
    void set(bool high) override { levels.push_back(high); }
    int highs() const { int n = 0; for (bool b : levels) if (b) n++; return n; }
};

class HighPin : public InputPin {
public:
    bool onEdge(Handler, uint8_t) override { return true; }
    void offEdge(Handler) override {}
    bool isHigh() override { return true; }
};

int main() {
    ChipSim sim;
    Pin step, dir;
    TMC2130Minimal motor(sim, &step, &dir);
    check_true(motor.ok(), "init_ok");
    {
        const std::vector<uint8_t> chop = {0xEC, 0x14, 0x03, 0x00, 0xC3};
        bool found = false;
        for (const auto& f : sim.frames) if (f == chop) found = true;
        check_true(found, "chopconf_write_frame");
    }
    check_true(sim.lastWrite(0x11) == 10, "init_tpowerdown");
    check_true(sim.lastWrite(0x00) == 0x4, "init_gconf");
    check_true(sim.lastWrite(0x13) == 500, "init_tpwmthrs");
    check_true(sim.lastWrite(0x70) == 0x000401C8UL, "init_pwmconf");
    uint32_t ih = sim.lastWrite(0x10);
    check_true(((ih >> 8) & 0x1F) == 15 && (ih & 0x1F) == 5 && ((ih >> 16) & 0xF) == 6, "init_current_0_5a");
    check_true(sim.gstatReads == 1, "init_clears_gstat_once");

    motor.setCurrent(1.0f);
    check_true(((sim.lastWrite(0x10) >> 8) & 0x1F) == 17 && !(sim.lastWrite(0x6C) & (1UL << 17)), "set_current_1a");
    motor.setCurrent(0.5f);
    check_true(((sim.lastWrite(0x10) >> 8) & 0x1F) == 15 && (sim.lastWrite(0x6C) & (1UL << 17)), "set_current_auto_vsense");
    check_true(!motor.setCurrent(1.5f), "set_current_range");

    check_true(motor.move(2, 1.0e6f) && step.highs() == 32 && dir.levels.back(), "move_pulses");
    motor.move(-1, 1.0e6f);
    check_true(!dir.levels.back() && step.highs() == 48, "move_reverse");
    check_true(!motor.move(1, 0.0f), "move_speed_range");

    motor.disable();
    check_true((sim.lastWrite(0x6C) & 0xF) == 0, "disable_toff0");
    motor.enable();
    check_true((sim.lastWrite(0x6C) & 0xF) == 3, "enable_toff3");

    sim.regs[0x6F] = (1UL << 31) | (1UL << 26) | (1UL << 28) | (1UL << 30);
    check_true(motor.isStandstill(), "standstill");
    check_true(motor.readFault() == (TMC2130Minimal::FAULT_OVERTEMP_WARNING | TMC2130Minimal::FAULT_SHORT_GND |
                                     TMC2130Minimal::FAULT_OPEN_LOAD), "fault_flags");

    {
        ChipSim s;
        Pin en, st, di;
        TMC2130Minimal m(s, &st, &di, 0.11f, &en);
        check_true(en.levels.size() == 1 && en.levels[0], "en_pin_high_at_init");
        m.enable();
        check_true(!en.levels.back(), "en_pin_low_on_enable");
    }
    {
        ChipSim a; a.regs[0x04] = 0;
        Pin s1, d1;
        TMC2130Minimal bad(a, &s1, &d1);
        check_true(!bad.ok(), "bad_version_not_ok");
        ChipSim b; b.regs[0x04] = 0x11000000UL;
        TMC2130Minimal bad2(b, &s1, &d1);
        check_true(!bad2.ok(), "bad_bit6_not_ok");
        ChipSim c; c.mirrorBroken = true;
        TMC2130Minimal bad3(c, &s1, &d1);
        check_true(!bad3.ok(), "broken_mirror_not_ok");
    }

    // ---- Full ----
    ChipSim fs;
    Pin fstep, fdir;
    HighPin diag;
    TMC2130Full full(fs, &fstep, &fdir, 0.11f, nullptr, &diag);
    check_true(full.ok(), "full_init_ok");
    full.setMicrosteps(256);
    check_true(((fs.lastWrite(0x6C) >> 24) & 0xF) == 0, "microsteps_256");
    full.setMicrosteps(1);
    check_true(((fs.lastWrite(0x6C) >> 24) & 0xF) == 8, "microsteps_1");
    check_true(!full.setMicrosteps(3), "microsteps_invalid");
    full.setMicrosteps(32);
    full.move(1, 1.0e6f);
    check_true(fstep.highs() == 32, "move_uses_microsteps");
    full.setInterpolation(false);
    check_true(((fs.lastWrite(0x6C) >> 28) & 1) == 0, "intpol_off");
    full.setChopperMode(true);
    check_true((fs.lastWrite(0x00) & 0x4) == 0, "spreadcycle");
    full.setChopperMode(false);
    check_true((fs.lastWrite(0x00) & 0x4) == 0x4, "stealthchop");
    full.setDirectionInverted(true);
    check_true((fs.lastWrite(0x00) & 0x10) == 0x10, "shaft");
    full.configureDiag(false, false, true, false, true, false, true, false);
    uint32_t g = fs.lastWrite(0x00);
    check_true(((g >> 7) & 1) && ((g >> 9) & 1) && ((g >> 12) & 1) && !((g >> 5) & 1) && (g & 0x14) == 0x14, "diag_bits");
    full.setDoubleEdge(true);
    check_true(((fs.lastWrite(0x6C) >> 29) & 1) == 1, "dedge");
    full.setShortProtection(false);
    check_true(((fs.lastWrite(0x6C) >> 30) & 1) == 1, "diss2g");
    full.setVsense(false);
    full.setCurrent(0.5f);
    check_true(((fs.lastWrite(0x10) >> 8) & 0x1F) == 8 && !((fs.lastWrite(0x6C) >> 17) & 1), "manual_vsense_kept");
    full.setHoldCurrent(0.2f);
    check_true((fs.lastWrite(0x10) & 0x1F) == 3, "hold_current");
    full.setCurrentDelay(2, 100);
    check_true(((fs.lastWrite(0x10) >> 16) & 0xF) == 2 && fs.lastWrite(0x11) == 100, "current_delay");
    full.setStealthchopThreshold(300);
    check_true(fs.lastWrite(0x13) == 300, "tpwmthrs");
    full.setHighVelocityThreshold(77);
    check_true(fs.lastWrite(0x15) == 77, "thigh");
    full.configureChopper(4, 1, 3, 2);
    uint32_t c = fs.lastWrite(0x6C);
    check_true((c & 0xF) == 4 && ((c >> 15) & 3) == 1 && ((c >> 4) & 7) == 3 && ((c >> 7) & 0xF) == 2 && !((c >> 14) & 1), "configure_chopper");
    full.configureConstantOffTime(5, 2, 11, -3, true, true);
    c = fs.lastWrite(0x6C);
    check_true((c & 0xF) == 5 && ((c >> 14) & 1) && ((c >> 4) & 7) == 3 && ((c >> 11) & 1) && ((c >> 7) & 0xF) == 0 &&
               ((c >> 12) & 3) == 3, "configure_constant_off_time");
    full.configureHighVelocity(true, true);
    check_true(((fs.lastWrite(0x6C) >> 18) & 3) == 3, "high_velocity");
    full.setChopperSync(5);
    check_true(((fs.lastWrite(0x6C) >> 20) & 0xF) == 5, "chopper_sync");
    full.configureStealthchop(true, false, 40, 5, 1);
    full.setFreewheel(1);
    uint32_t p = fs.lastWrite(0x70);
    check_true((p & 0xFF) == 40 && ((p >> 8) & 0xFF) == 5 && ((p >> 16) & 3) == 1 && ((p >> 20) & 3) == 1 &&
               ((p >> 18) & 1) && !((p >> 19) & 1), "configure_stealthchop_freewheel");
    full.setSmallHysteresis(true);
    full.setEmergencyStop(true);
    full.setAnalogScaling(true);
    check_true(((fs.lastWrite(0x00) >> 14) & 3) == 3 && (fs.lastWrite(0x00) & 1), "gconf_flags");

    full.setStallThreshold(-5);
    full.setStallguardFilter(true);
    full.configureCoolstep(5, 2, 1, 1, 0);
    uint32_t cc = fs.lastWrite(0x6D);
    check_true((cc & 0xF) == 5 && ((cc >> 5) & 3) == 1 && ((cc >> 8) & 0xF) == 2 && ((cc >> 13) & 3) == 1 &&
               ((cc >> 24) & 1) && ((cc >> 16) & 0x7F) == (uint32_t)(-5 & 0x7F), "coolconf_keeps_sgt");
    check_true(!full.setStallThreshold(64) && !full.setStallThreshold(-65) && !full.configureCoolstep(16, 0, 0, 0, 0) &&
               !full.setCoolstepThreshold(1UL << 20), "range_errors");
    full.setCoolstepThreshold(500);
    check_true(fs.lastWrite(0x14) == 500, "tcoolthrs");
    fs.regs[0x6F] = (1UL << 24) | (17UL << 16) | 0x2A;
    check_true(full.readStallguard() == 0x2A, "read_stallguard");
    check_true(full.isStalled(), "is_stalled");
    check_true(full.readDiag(0) && full.ok(), "read_diag0");
    full.readDiag(1);
    check_true(!full.ok(), "read_diag_unwired");

    check_true(!full.setCoilCurrents(1, 1), "coil_requires_direct");
    full.setDirectMode(true);
    check_true(full.setCoilCurrents(100, -100) && fs.lastWrite(0x2D) == (100UL | ((0x1FFUL & (uint32_t)-100) << 16)), "xdirect");
    full.setDcstep(1000, 200, 3);
    check_true(fs.lastWrite(0x33) == 1000 && fs.lastWrite(0x6E) == (200UL | (3UL << 16)), "dcstep");
    const uint32_t table[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    full.writeMicrostepTable(table, 0x12345678UL, 0x00F70000UL);
    check_true(fs.lastWrite(0x60) == 1 && fs.lastWrite(0x67) == 8 && fs.lastWrite(0x68) == 0x12345678UL &&
               fs.lastWrite(0x69) == 0x00F70000UL, "mslut");

    fs.regs[0x6A] = 513;
    fs.regs[0x6B] = (0x1FFUL << 16) | 0x100;
    fs.regs[0x12] = 1234;
    fs.regs[0x71] = 77;
    fs.regs[0x73] = 0x123456;
    fs.regs[0x6F] = (1UL << 31) | (1UL << 15) | (17UL << 16) | (1UL << 25) | (1UL << 24) | 300;
    fs.regs[0x04] = 0x11000000UL | 0x40 | 0x1 | 0x10;
    fs.regs[0x01] = 0x03;
    check_true(full.microstepPosition() == 513, "microstep_position");
    TMC2130Full::CoilCurrents cur = full.readCoilCurrents();
    check_true(cur.curA == -1 && cur.curB == -256, "coil_currents");
    check_true(full.readTstep() == 1234, "tstep");
    check_true(full.readPwmScale() == 77, "pwm_scale");
    check_true(full.readLostSteps() == 0x23456, "lost_steps");
    check_true(full.readCurrentScale() == 17, "current_scale");
    TMC2130Full::Status st = full.readStatus();
    check_true(st.stst && st.fsactive && st.ot && st.stallguard && !st.otpw && st.csActual == 17 && st.sgResult == 300, "status");
    TMC2130Full::GlobalStatus gs = full.readGlobalStatus();
    check_true(gs.reset && gs.drvErr && !gs.uvCp && fs.regs[0x01] == 0, "global_status");
    TMC2130Full::Ioin io = full.readIoin();
    check_true(io.version == 0x11 && io.step && io.enn && !io.dir, "ioin");
    check_true(full.readRegister(0x6A) == 513 && full.ok(), "read_register");
    full.readRegister(0x10);
    check_true(!full.ok(), "read_register_writeonly");
    check_true(full.writeRegister(0x13, 5, true) && fs.lastWrite(0x13) == 5, "write_register");
    check_true(!full.writeRegister(0x72, 1), "write_register_encm");

    full.setInternalRsense(true);
    check_true(!full.setCurrent(0.5f), "internal_rsense_current");
    full.setInternalRsense(false);
    check_true(full.step(3, 0.0f) && fstep.highs() == 35 && fdir.levels.back(), "step_pulses");
    full.step(-2, 0.0f);
    check_true(!fdir.levels.back(), "step_reverse");

    printf("===DONE=== passed=%d failed=%d\n", passed, failed);
    return failed ? 1 : 0;
}
