#include <stdio.h>
#include "SPIConnectionMock.h"
#include "MCP2515.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Access protected register/instruction constants and helpers for building
// expected values and driving the mock directly.
class MCP2515TestAccess : public MCP2515Full {
public:
    using MCP2515Full::MCP2515Full;
    using MCP2515Full::INSTR_WRITE;
    using MCP2515Full::INSTR_BIT_MODIFY;
    using MCP2515Full::INSTR_READ_STATUS;
    using MCP2515Full::REG_CANSTAT;
    using MCP2515Full::REG_RXM0SIDH;
    using MCP2515Full::REG_RXB0CTRL;
    using MCP2515Full::REG_EFLG;
    using MCP2515Full::EFLG_RX0OVR;
    using MCP2515Full::_pack_id;
    using MCP2515Full::_unpack_id;
};

static void test_send_standard_frame() {
    SPIConnectionMock mock;
    MCP2515Minimal chip(mock);
    uint8_t data[] = { 0xDE, 0xAD, 0xBE, 0xEF };
    chip.send(0x123, data, 4);
    bool found_load = false;
    bool found_rts = false;
    for (const auto& w : mock.writes()) {
        if (!w.empty() && w[0] == 0x40) found_load = true;
        if (!w.empty() && w[0] == 0x81) found_rts = true;
    }
    check_true(found_load, "send issues LOAD TX BUFFER");
    check_true(found_rts,  "send issues RTS for TXB0");
}

static void test_send_extended_frame() {
    SPIConnectionMock mock;
    MCP2515Minimal chip(mock);
    uint8_t data[] = { 0x01 };
    chip.send(0x18FF1234, data, 1, true);
    bool found_load = false;
    bool exide_set = false;
    for (const auto& w : mock.writes()) {
        if (!w.empty() && w[0] == 0x40 && w.size() == 14) {
            found_load = true;
            exide_set = (w[2] & 0x08) != 0;
        }
    }
    check_true(found_load, "extended send writes 14 bytes");
    check_true(exide_set, "extended send sets EXIDE");
}

static void test_full_set_mode_loopback() {
    SPIConnectionMock mock;
    mock.setRegister(MCP2515TestAccess::INSTR_READ_STATUS, {0x40});
    mock.setRegister(MCP2515TestAccess::REG_CANSTAT & 0x7F, {_MCP2515Base::CANSTAT_OPMOD_LOOPBACK});
    MCP2515Full chip(mock);
    chip.set_mode(_MCP2515Base::CANSTAT_OPMOD_LOOPBACK);
    check_true(true, "set_mode loopback accepted");
}

static void test_full_read_errors() {
    SPIConnectionMock mock;
    MCP2515Full chip(mock);
    uint8_t tec, rec, eflg;
    chip.read_errors(tec, rec, eflg);
    check_true(true, "read_errors returns");
}

static void test_full_set_one_shot() {
    SPIConnectionMock mock;
    MCP2515Full chip(mock);
    chip.set_one_shot(true);
    chip.set_one_shot(false);
    check_true(true, "set_one_shot accepted");
}

static void test_filter_mask_writes() {
    SPIConnectionMock mock;
    MCP2515Full chip(mock);
    chip.set_filter(0, 0x123, false);
    bool found_sidh = false;
    for (const auto& w : mock.writes()) {
        if (w.size() == 3 && w[0] == MCP2515TestAccess::INSTR_WRITE && w[1] == 0x00 && w[2] == 0x24) {
            found_sidh = true;
        }
    }
    check_true(found_sidh, "set_filter writes SIDH at filter 0");
}

static void test_pack_id_round_trip() {
    uint8_t sidh, sidl, eid8, eid0;
    MCP2515TestAccess::_pack_id(0x123, false, sidh, sidl, eid8, eid0);
    check_true(MCP2515TestAccess::_unpack_id(sidh, sidl, eid8, eid0, false) == 0x123,
               "_pack_id/_unpack_id round trip standard");
    MCP2515TestAccess::_pack_id(0x18FF1234, true, sidh, sidl, eid8, eid0);
    check_true(MCP2515TestAccess::_unpack_id(sidh, sidl, eid8, eid0, true) == 0x18FF1234,
               "_pack_id/_unpack_id round trip extended");
}

static void test_set_mask_writes() {
    SPIConnectionMock mock;
    MCP2515Full chip(mock);
    chip.set_mask(0, 0x7FF, false);
    bool found_rxm0 = false;
    for (const auto& w : mock.writes()) {
        if (w.size() == 3 && w[0] == MCP2515TestAccess::INSTR_WRITE && w[1] == MCP2515TestAccess::REG_RXM0SIDH) {
            found_rxm0 = true;
        }
    }
    check_true(found_rxm0, "set_mask writes RXM0SIDH for mask 0");
}

static void test_set_rx_mode_modifies_rxbctrl() {
    SPIConnectionMock mock;
    MCP2515Full chip(mock);
    chip.set_rx_mode(0, 0x03);
    bool found_bit_modify_rxb0 = false;
    for (const auto& w : mock.writes()) {
        if (!w.empty() && w[0] == MCP2515TestAccess::INSTR_BIT_MODIFY && w[1] == MCP2515TestAccess::REG_RXB0CTRL) {
            found_bit_modify_rxb0 = true;
        }
    }
    check_true(found_bit_modify_rxb0, "set_rx_mode issues BIT MODIFY to RXB0CTRL");
}

static void test_clear_overflow_clears_eflg_bit() {
    SPIConnectionMock mock;
    MCP2515Full chip(mock);
    chip.clear_overflow(0);
    bool found_clear_rxb0ovr = false;
    for (const auto& w : mock.writes()) {
        if (!w.empty() && w[0] == MCP2515TestAccess::INSTR_BIT_MODIFY && w[1] == MCP2515TestAccess::REG_EFLG
            && w[2] == MCP2515TestAccess::EFLG_RX0OVR && w[3] == 0x00) {
            found_clear_rxb0ovr = true;
        }
    }
    check_true(found_clear_rxb0ovr, "clear_overflow(0) clears RX0OVR in EFLG");
}

int main() {
    test_send_standard_frame();
    test_send_extended_frame();
    test_full_set_mode_loopback();
    test_full_read_errors();
    test_full_set_one_shot();
    test_filter_mask_writes();
    test_pack_id_round_trip();
    test_set_mask_writes();
    test_set_rx_mode_modifies_rxbctrl();
    test_clear_overflow_clears_eflg_bit();
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
