#include <stdio.h>
#include <math.h>
#include <vector>
#include <string>
#include "I2CConnectionMock.h"
#include "MCP4XXX.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

using Bytes = std::vector<uint8_t>;

/** Models the MCP4XXX command protocol: sixteen nine-bit cells, inc/dec of the volatile wipers, two-byte reads. */
class ChipMock : public I2CConnectionMock {
public:
    int mem[16];
    int max_code;
    int status = 0x1F0;
    std::vector<Bytes> log;   // every write payload

    explicit ChipMock(int max) : max_code(max) {
        for (int i = 0; i < 16; i++) mem[i] = 0;
        mem[0] = mem[1] = mem[2] = mem[3] = max / 2;
        mem[4] = 0x1FF;
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        I2CConnectionMock::_write(data, len);
        log.emplace_back(data, data + len);
        for (size_t i = 0; i < len;) {
            int cmd = data[i], addr = cmd >> 4, op = (cmd >> 2) & 3;
            if (op == 0) { mem[addr] = ((cmd & 1) << 8) | data[i + 1]; i += 2; }
            else {
                if (addr < 2) {
                    if (op == 1 && mem[addr] < max_code) mem[addr]++;
                    else if (op == 2 && mem[addr] > 0) mem[addr]--;
                }
                i += 1;
            }
        }
    }

    void _write_read(const uint8_t* data, size_t data_len, uint8_t* buf, size_t buf_len) override {
        I2CConnectionMock::_write_read(data, data_len, buf, buf_len);
        int addr = data[0] >> 4;
        int v = addr == 5 ? status : mem[addr];
        uint8_t out[2] = {(uint8_t)((v >> 8) & 1), (uint8_t)v};
        for (size_t i = 0; i < buf_len && i < 2; i++) buf[i] = out[i];
    }
};

class Pin : public OutputPin {
public:
    std::string log;
    void set(bool high) override { log += high ? "1" : "0"; }
};

int main() {
    // Part table.
    check_true(MCP4XXXMinimal::default_address(MCP4XXXPart::MCP4531) == 0x2E &&
               MCP4XXXMinimal::default_address(MCP4XXXPart::MCP4632) == 0x58 &&
               MCP4XXXMinimal::default_address(MCP4XXXPart::MCP4661) == 0x28, "default_address");

    {   // init performs only reads; properties.
        ChipMock c(256);
        MCP4XXXFull chip(c, MCP4XXXPart::MCP4662);
        check_true(c.writes().empty(), "construct_no_traffic");
        chip.init();
        check_true(c.log.empty() && c.writes().size() == 4 &&
                   c.writes()[0] == Bytes({0x0C}) && c.writes()[1] == Bytes({0x1C}) &&
                   c.writes()[2] == Bytes({0x4C}) && c.writes()[3] == Bytes({0x5C}), "init_reads_only");
        check_true(chip.channels() == 2 && chip.steps() == 257 && !chip.is_potentiometer() && chip.is_nonvolatile(),
                   "props_4662");
    }

    {   // 7-bit part: formats, clamps, resistance model, ratio.
        ChipMock c(128);
        MCP4XXXFull chip(c, MCP4XXXPart::MCP4531);
        check_true(chip.channels() == 1 && chip.steps() == 129 && chip.is_potentiometer() && !chip.is_nonvolatile(), "props_4531");
        chip.set_wiper(100);
        check_true(c.log.back() == Bytes({0x00, 100}), "set_wiper_7bit");
        chip.set_wiper(999);
        check_true(c.log.back() == Bytes({0x00, 128}), "set_wiper_clamps_128");
        chip.set_wiper(-3);
        check_true(c.log.back() == Bytes({0x00, 0}), "set_wiper_clamps_0");
        chip.set_wiper(64);
        check_true(fabsf(chip.get_resistance() - 5075.0f) < 1e-2f, "get_resistance");
        check_true(fabsf(chip.get_resistance_wa() - 5075.0f) < 1e-2f, "get_resistance_wa");
        chip.set_resistance(1e9f);
        check_true(chip.get_wiper() == 128, "set_resistance_clamps");
        chip.set_resistance_wa(5075.0f);
        check_true(chip.get_wiper() == 64, "set_resistance_wa");
        chip.set_ratio(0.5f);
        check_true(chip.get_wiper() == 64 && fabsf(chip.get_ratio() - 0.5f) < 1e-6f, "ratio");
        check_true(fabsf(chip.get_resistance_step() - 10000.0f / 128.0f) < 1e-3f, "step");
        check_true(!chip.set_both(1, 2), "set_both_single_rejected");
        chip.set_rab(50000.0f, 100.0f);
        chip.set_wiper(128);
        check_true(fabsf(chip.get_resistance() - 50100.0f) < 1e-1f, "set_rab");
        MCP4XXXStatus ignored;
        check_true(!chip.read_status(ignored) && chip.get_nv_wiper() == -1 && chip.store_wiper() == -1 &&
                   chip.read_data(0) == -1 && !chip.write_data(0, 1) && !chip.wait_ready() && !chip.set_write_protect(true),
                   "nv_ops_rejected_on_volatile");
    }

    {   // rheostat: no W-A.
        ChipMock c(128);
        MCP4XXXFull chip(c, MCP4XXXPart::MCP4532);
        check_true(chip.get_resistance_wa() < 0.0f && !chip.set_resistance_wa(1000.0f), "rheostat_no_wa");
        check_true(chip.read_register(4) == 0x1FF && chip.read_register(2) == -1 && chip.read_register(16) == -1, "read_register");
        check_true(chip.write_register(0, 77) && c.mem[0] == 77 && !chip.write_register(5, 0), "write_register");
    }

    {   // 8-bit dual part: D8 in the command byte.
        ChipMock c(256);
        MCP4XXXFull chip(c, MCP4XXXPart::MCP4652);
        chip.set_wiper(256, 1);
        check_true(c.log.back() == Bytes({0x11, 0x00}), "set_wiper_8bit_d8");
        check_true(chip.get_wiper(1) == 256, "get_wiper_8bit");
        c.mem[0] = 0x3FF;
        check_true(chip.get_wiper(0) == 256, "get_wiper_clamps_above_full_scale");
    }

    {   // increment / decrement, set_both.
        ChipMock c(128);
        MCP4XXXFull chip(c, MCP4XXXPart::MCP4631);
        chip.set_both(10, 20);
        check_true(c.mem[0] == 10 && c.mem[1] == 20, "set_both");
        chip.set_wiper(120, 1);
        size_t n = c.log.size();
        check_true(chip.increment(10, 1) == 128, "increment_saturates");
        check_true(c.log.size() == n + 1 && c.log[n] == Bytes(10, 0x14), "increment_one_transaction");
        chip.set_wiper(5, 0);
        check_true(chip.decrement(10, 0) == 0, "decrement_saturates");
        check_true(chip.increment(1, 0) == 1 && c.log.back() == Bytes({0x04}), "increment_single");
        n = c.log.size();
        chip.set_wiper(0, 0);
        n = c.log.size();
        chip.increment(40, 0);
        check_true(c.log[n].size() == 32 && c.log[n + 1].size() == 8 && c.mem[0] == 40, "increment_chunked");
    }

    {   // TCON.
        ChipMock c(128);
        MCP4XXXFull chip(c, MCP4XXXPart::MCP4631);
        chip.shutdown(1);
        check_true(c.mem[4] == (0x1FF & ~0x80) && chip.is_shutdown(1) && !chip.is_shutdown(0), "shutdown_ch1");
        chip.resume(1);
        check_true(c.mem[4] == 0x1FF, "resume");
        chip.shutdown(0);
        check_true(c.mem[4] == (0x1FF & ~0x08), "shutdown_ch0");
        chip.resume(0);
        chip.set_terminals(true, false, true, 1);
        bool a, w, b, a0, w0, b0;
        chip.get_terminals(1, a, w, b);
        chip.get_terminals(0, a0, w0, b0);
        check_true(c.mem[4] == (0x1FF & ~0x20) && a && !w && b && a0 && w0 && b0, "terminals_ch1");
        chip.set_general_call(false);
        check_true((chip.get_tcon() & 0x100) == 0, "general_call_off");
        chip.set_general_call(true);
        check_true((c.mem[4] & 0x100) != 0, "general_call_on");
        chip.set_wiper(3, 0);
        chip.shutdown(1);
        chip.reset();
        check_true(c.mem[0] == 64 && c.mem[1] == 64 && c.mem[4] == 0x1FF, "reset_volatile");
    }

    {   // Non-volatile part.
        ChipMock c(256);
        Pin pin;
        MCP4XXXFull chip(c, MCP4XXXPart::MCP4662, 10000.0f, 75.0f, &pin, 1);
        MCP4XXXStatus st;
        check_true(chip.read_status(st) && !st.eeprom_busy && !st.wiper_lock[0] && !st.write_protect, "status_idle");
        c.status = 0x1F0 | 0x08 | 0x02 | 0x01;
        chip.read_status(st);
        check_true(st.eeprom_busy && st.wiper_lock[0] && !st.wiper_lock[1] && st.write_protect, "status_flags");
        check_true(chip.is_wiper_locked(0) && !chip.is_wiper_locked(1) && chip.is_write_protected(), "lock_queries");
        check_true(!chip.wait_ready(3), "wait_ready_timeout");
        c.status = 0x1F0;
        check_true(chip.set_nv_wiper(200, 1) && c.mem[3] == 200 && chip.get_nv_wiper(1) == 200, "set_nv_wiper");
        chip.set_wiper(150, 0);
        check_true(chip.store_wiper(0) == 150 && c.mem[2] == 150, "store_wiper");
        check_true(chip.write_data(3, 0x1AB) && c.mem[9] == 0x1AB && chip.read_data(3) == 0x1AB, "data_register");
        bool seen = false;
        for (const auto& l : c.log) seen = seen || l == Bytes({0x91, 0xAB});
        check_true(seen && chip.read_data(10) == -1, "data_cmd_and_bad_index");
        chip.set_wiper(7, 0);
        chip.reset();
        check_true(c.mem[0] == 150 && c.mem[1] == 200, "nv_reset");

        // High-voltage commands: exact single command bytes, pin asserted around the write.
        c.log.clear();
        pin.log.clear();
        check_true(chip.set_wiper_lock(true, 0) && c.log[0] == Bytes({0x28}) && pin.log == "10", "wl_enable_0");
        c.log.clear();
        chip.set_wiper_lock(false, 1);
        check_true(c.log[0] == Bytes({0x34}), "wl_disable_1");
        c.log.clear();
        chip.set_wiper_lock(true, 1);
        check_true(c.log[0] == Bytes({0x38}), "wl_enable_1");
        c.log.clear();
        chip.set_write_protect(true);
        check_true(c.log[0] == Bytes({0xF8}), "wp_enable");
        c.log.clear();
        chip.set_write_protect(false);
        check_true(c.log[0] == Bytes({0xF4}), "wp_disable");

        ChipMock c2(256);
        MCP4XXXFull nohv(c2, MCP4XXXPart::MCP4562);
        check_true(!nohv.set_wiper_lock(true, 0) && c2.log.empty(), "hv_needs_pin");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
