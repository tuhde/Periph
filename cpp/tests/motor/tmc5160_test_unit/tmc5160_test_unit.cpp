#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <map>
#include <vector>
#include "TMC5160.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

struct RegWrite { uint8_t reg; uint32_t value; };

/** Scripted TMC5160 over SPI: pipelined 40-bit datagrams, instant ramp generator. */
class SpiSim : public Connection {
public:
    std::map<uint8_t, uint32_t> regs;
    std::vector<RegWrite> writes;
    uint32_t pending = 0;
    bool mirrorBad = false;

    SpiSim() { regs[0x04] = 0x30000000UL; }

    uint32_t lastWrite(uint8_t reg) const {
        for (size_t i = writes.size(); i-- > 0;) if (writes[i].reg == reg) return writes[i].value;
        return 0xDEADBEEFUL;
    }

protected:
    uint32_t datagram(const uint8_t* data, size_t len) {
        uint8_t f[5] = {0};
        memcpy(f, data, len < 5 ? len : 5);
        uint32_t reply = pending;
        uint8_t reg = f[0] & 0x7F;
        if (f[0] & 0x80) {
            uint32_t v = ((uint32_t)f[1] << 24) | ((uint32_t)f[2] << 16) | ((uint32_t)f[3] << 8) | f[4];
            regs[reg] = v;
            writes.push_back({reg, v});
            if (reg == 0x2D) { regs[0x21] = v; regs[0x35] = 1UL << 9; }
            pending = mirrorBad ? 0 : v;
        } else {
            pending = regs.count(reg) ? regs[reg] : 0;
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

/** Scripted TMC5160 over single-wire UART with echo, CRC8 and IFCNT. */
class UartSim : public Connection {
public:
    std::map<uint8_t, uint32_t> regs;
    std::vector<RegWrite> writes;
    std::vector<uint8_t> rx;
    uint8_t ifcnt = 0;

    UartSim() { regs[0x04] = 0x30000000UL; }

    uint32_t lastWrite(uint8_t reg) const {
        for (size_t i = writes.size(); i-- > 0;) if (writes[i].reg == reg) return writes[i].value;
        return 0xDEADBEEFUL;
    }
    size_t available() const override { return rx.size(); }

protected:
    void _write(const uint8_t* data, size_t len) override {
        rx.insert(rx.end(), data, data + len);
        if (len == 8) {
            uint8_t reg = data[2] & 0x7F;
            uint32_t v = ((uint32_t)data[3] << 24) | ((uint32_t)data[4] << 16) | ((uint32_t)data[5] << 8) | data[6];
            regs[reg] = v;
            writes.push_back({reg, v});
            ifcnt++;
        }
    }
    void _read(uint8_t* buf, size_t len) override {
        for (size_t i = 0; i < len; i++) {
            if (rx.empty()) { buf[i] = 0; continue; }
            buf[i] = rx.front();
            rx.erase(rx.begin());
        }
    }
    void _write_read(const uint8_t* data, size_t data_len, uint8_t* buf, size_t buf_len) override {
        uint8_t reg = data[2] & 0x7F;
        uint32_t v = reg == 0x02 ? ifcnt : (regs.count(reg) ? regs[reg] : 0);
        uint8_t out[12];
        memcpy(out, data, 4);
        uint8_t* r = out + 4;
        r[0] = 0x05; r[1] = 0xFF; r[2] = reg;
        r[3] = (uint8_t)(v >> 24); r[4] = (uint8_t)(v >> 16); r[5] = (uint8_t)(v >> 8); r[6] = (uint8_t)v;
        r[7] = TMC5160Minimal::crc8(r, 7);
        (void)data_len;
        if (buf_len == 12) memcpy(buf, out, 12);
        else memcpy(buf, r, buf_len < 8 ? buf_len : 8);
    }
};

class HighPin : public InputPin {
public:
    bool onEdge(Handler, uint8_t) override { return true; }
    void offEdge(Handler) override {}
    bool isHigh() override { return true; }
};

int main() {
    {
        uint8_t body[3] = {0x05, 0x00, 0x00};
        check_true(TMC5160Minimal::crc8(body, 3) == 0x48, "crc8_vector");
    }

    SpiSim sim;
    TMC5160Minimal motor(sim);
    check_true(motor.ok(), "init_ok");
    check_true(sim.lastWrite(0x6C) == 0x000100C3UL, "init_chopconf");
    check_true(sim.lastWrite(0x00) == 0x4, "init_gconf");
    check_true(sim.lastWrite(0x13) == 500, "init_tpwmthrs");
    check_true(sim.lastWrite(0x01) == 0x07, "init_gstat_clear");
    check_true(sim.lastWrite(0x27) == 71583, "init_vmax");
    check_true(sim.lastWrite(0x26) == 1564, "init_amax");
    check_true(sim.lastWrite(0x28) == 1564 && sim.lastWrite(0x24) == 1564 && sim.lastWrite(0x2A) == 1564, "init_trapezoid");
    check_true(sim.lastWrite(0x0B) == 128 && ((sim.lastWrite(0x10) >> 8) & 0x1F) == 9, "init_globalscaler_irun");

    {
        SpiSim wrong;
        wrong.regs[0x04] = 0x11000000UL;
        TMC5160Minimal bad(wrong);
        check_true(!bad.ok(), "reject_wrong_version");
        SpiSim sd;
        sd.regs[0x04] = 0x30000040UL;
        TMC5160Minimal bad2(sd);
        check_true(!bad2.ok(), "reject_stepdir_strap");
    }

    check_true(motor.moveTo(400.0f), "move_to");
    check_true(sim.lastWrite(0x2D) == 400UL * 256, "move_to_xtarget");
    check_true(motor.waitUntilReached(1.0f), "wait_until_reached");
    check_true(motor.position() == 400.0f, "position");
    check_true(motor.run(-200.0f) && sim.lastWrite(0x20) == 2, "run_negative");
    check_true(motor.stop() && sim.lastWrite(0x27) == 0, "stop");
    check_true(!motor.setCurrent(10.0f), "set_current_range");
    check_true(motor.setCurrent(1.0f), "set_current");
    check_true(!motor.setSpeed(0.0f), "set_speed_range");
    check_true(motor.setPosition(5.0f) && sim.lastWrite(0x21) == 5 * 256, "set_position");

    motor.disable();
    check_true((sim.lastWrite(0x6C) & 0xF) == 0, "disable_toff0");
    motor.enable();
    check_true((sim.lastWrite(0x6C) & 0xF) == 3, "enable_toff3");

    sim.regs[0x6F] = (1UL << 31) | (1UL << 26);
    check_true(motor.isStandstill(), "standstill");
    check_true(motor.readFault() == TMC5160Minimal::FAULT_OVERTEMP_WARNING, "fault_otpw");

    SpiSim fs;
    TMC5160Full full(fs);
    check_true(full.ok(), "full_init_ok");
    check_true(full.configureRamp(0, 800, 400, 400, 200, 400, 800, 1) && fs.lastWrite(0x24) == 3127, "configure_ramp");
    check_true(!full.configureRamp(0, 800, 400, 400, 200, 400, 800, 0), "configure_ramp_range");
    check_true(full.setMicrosteps(16) && ((fs.lastWrite(0x6C) >> 24) & 0xF) == 4, "set_microsteps");
    check_true(!full.setMicrosteps(3), "set_microsteps_range");
    check_true(!full.setCoilCurrents(1, 1), "coil_currents_need_direct");
    check_true(full.setDirectMode(true) && full.setCoilCurrents(-1, 255), "coil_currents");
    check_true(fs.lastWrite(0x2D) == (0x1FFUL | (255UL << 16)), "coil_currents_word");
    check_true(!full.setSlaveAddress(1) && !full.setSendDelay(2), "uart_only_rejected");
    full.readIfcnt();
    check_true(!full.ok(), "ifcnt_spi_not_ok");
    check_true(full.setStallThreshold(-64) && ((fs.lastWrite(0x6D) >> 16) & 0x7F) == 0x40, "stall_threshold");
    check_true(!full.setStallThreshold(64), "stall_threshold_range");
    check_true(full.configureSwitches(true, true, false, false, false, true), "switches_softstop");
    check_true(!full.enableStallStop(true), "sg_stop_conflicts_softstop");
    check_true(full.configureStealthchop(true, true, 36, 14, 1, 4, 12) && (fs.lastWrite(0x70) & 0xFF) == 36, "stealthchop");
    check_true(full.writeRegister(0x13, 5, true) && fs.lastWrite(0x13) == 5, "write_register");
    check_true(!full.writeRegister(0x6F, 1), "write_register_readonly");
    fs.mirrorBad = true;
    check_true(!full.writeRegister(0x13, 6, true) && !full.ok(), "write_register_mirror_mismatch");
    fs.mirrorBad = false;
    fs.regs[0x6F] = (1UL << 31) | (1UL << 25) | (1UL << 24) | (17UL << 16) | 300;
    TMC5160Full::Status st = full.readStatus();
    check_true(st.stst && st.ot && st.stallguard && st.csActual == 17 && st.sgResult == 300 && !st.otpw, "status");
    fs.regs[0x35] = (1UL << 9) | (1UL << 0);
    TMC5160Full::RampStatus rs = full.readRampStatus();
    check_true(rs.positionReached && rs.statusStopL && !rs.statusStopR, "ramp_status");
    check_true(!full.readDiag(0), "diag_unwired");

    HighPin hp;
    SpiSim ds;
    TMC5160Full dfull(ds, 0.075f, nullptr, &hp, nullptr);
    check_true(dfull.readDiag(0) && dfull.ok(), "diag_pin");

    UartSim us;
    TMC5160Full ufull(us, 0.075f, 0, nullptr, true);
    check_true(ufull.ok(), "uart_init_ok");
    check_true(us.lastWrite(0x6C) == 0x000100C3UL, "uart_chopconf");
    check_true(ufull.readIfcnt() > 0 && ufull.ok(), "uart_ifcnt");
    check_true(ufull.setSendDelay(2) && us.regs[0x03] == (2UL << 8), "uart_senddelay");
    {
        UartSim bad;
        TMC5160Minimal b(bad, 0.075f, 254, nullptr, true);
        check_true(!b.ok(), "uart_address_range");
    }

    printf("===DONE=== passed=%d failed=%d\n", passed, failed);
    return failed ? 1 : 0;
}
