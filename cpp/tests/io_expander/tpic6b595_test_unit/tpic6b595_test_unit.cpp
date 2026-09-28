#include <stdio.h>
#include "SiPoConnectionMock.h"
#include "TPIC6B595.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    // --- Construction (single device, SRCLR wired) ---
    SiPoConnectionMock connection;
    TPIC6B595Minimal<SiPoConnectionMock> chip(connection);
    check_true(connection.clearCount() == 1, "init_clears");
    check_true(connection.writes().back().size() == 1 && connection.writes().back()[0] == 0x00, "init_writes_all_zero");
    check_true(chip._shadow[0] == 0x00, "init_shadow_zero");

    // --- Construction with SRCLR not wired: Linux's clear() throws,
    // the constructor must swallow it (regression test for the missing
    // try/catch bug found while writing this test) ---
    SiPoConnectionMock noSrclr(false, true);
    TPIC6B595Minimal<SiPoConnectionMock> chip2(noSrclr);
    check_true(true, "init_no_srclr_does_not_throw");
    check_true(noSrclr.writes().back()[0] == 0x00, "init_no_srclr_still_flushes");
    check_true(noSrclr.clearCount() == 0, "init_no_srclr_clear_not_counted");

    // --- pin() proxy: high/low/toggle/read/set ---
    auto pin3 = chip.pin(3);
    pin3.high();
    check_true(chip._shadow[0] == 0x08, "pin3_high_shadow");
    check_true(connection.writes().back()[0] == 0x08, "pin3_high_wire");
    check_true(pin3.read() == 1, "pin3_read_after_high");

    pin3.low();
    check_true(chip._shadow[0] == 0x00, "pin3_low_shadow");
    check_true(pin3.read() == 0, "pin3_read_after_low");

    pin3.toggle();
    check_true(chip._shadow[0] == 0x08, "pin3_toggle_to_on");
    pin3.toggle();
    check_true(chip._shadow[0] == 0x00, "pin3_toggle_to_off");

    auto pin5 = chip.pin(5);
    pin5.set(true);
    check_true(chip._shadow[0] == 0x20, "pin5_set_true");
    pin3.high();
    check_true(chip._shadow[0] == 0x28, "pin3_high_preserves_pin5");
    pin5.set(false);
    check_true(chip._shadow[0] == 0x08, "pin5_set_false_preserves_pin3");

    // --- write_port(): direct port write, replaces the whole byte ---
    chip.write_port(0, 0x3C);
    check_true(chip._shadow[0] == 0x3C, "write_port_shadow");
    check_true(connection.writes().back()[0] == 0x3C, "write_port_wire");

    // --- fill()/off() ---
    chip.fill(true);
    check_true(chip._shadow[0] == 0xFF, "fill_true_shadow");
    check_true(connection.writes().back()[0] == 0xFF, "fill_true_wire");
    chip.off();
    check_true(chip._shadow[0] == 0x00, "off_is_fill_false");
    check_true(connection.writes().back()[0] == 0x00, "off_wire");

    // --- Cascading wire-order reversal (num_devices=3) ---
    SiPoConnectionMock cascadeConn;
    TPIC6B595Minimal<SiPoConnectionMock> cascade(cascadeConn, 3);
    cascade.write_port(0, 0xAA);
    cascade.write_port(1, 0xBB);
    cascade.write_port(2, 0xCC);
    const auto& w1 = cascadeConn.writes().back();
    check_true(w1.size() == 3 && w1[0] == 0xCC && w1[1] == 0xBB && w1[2] == 0xAA, "cascade_wire_order_reversed");

    auto pinFar = cascade.pin(16); // device 2, bit 0
    pinFar.high();
    check_true(cascade._shadow[2] == 0xCD, "cascade_far_device_pin");
    const auto& w2 = cascadeConn.writes().back();
    check_true(w2[0] == 0xCD && w2[1] == 0xBB && w2[2] == 0xAA, "cascade_far_device_wire");

    // --- Full::clear(): propagates to the connection, or throws if unwired ---
    SiPoConnectionMock fullConn;
    TPIC6B595Full<SiPoConnectionMock> full(fullConn);
    full.clear();
    check_true(fullConn.clearCount() == 2, "full_clear_calls_through"); // +1 from construction

    SiPoConnectionMock noSrclrConn(false, true);
    TPIC6B595Full<SiPoConnectionMock> noSrclrFull(noSrclrConn);
    bool threw = false;
    try { noSrclrFull.clear(); } catch (const std::runtime_error&) { threw = true; }
    check_true(threw, "full_clear_throws_when_unwired");

    // --- Full::set_output_enable(): propagates, or throws if G unwired ---
    full.set_output_enable(true);
    check_true(fullConn.outputEnableCalls().back() == true, "full_output_enable_true");
    full.set_output_enable(false);
    check_true(fullConn.outputEnableCalls().back() == false, "full_output_enable_false");

    SiPoConnectionMock noGConn(true, false);
    TPIC6B595Full<SiPoConnectionMock> noGFull(noGConn);
    threw = false;
    try { noGFull.set_output_enable(true); } catch (const std::runtime_error&) { threw = true; }
    check_true(threw, "full_output_enable_throws_when_unwired");

    // --- Full::write_all(): zero-extends and truncates to num_devices ---
    SiPoConnectionMock cascadeFullConn;
    TPIC6B595Full<SiPoConnectionMock> cascadeFull(cascadeFullConn, 3);
    uint8_t short_vals[] = {0x11, 0x22};
    cascadeFull.write_all(short_vals, 2); // shorter than num_devices -> zero-extend
    check_true(cascadeFull._shadow[0] == 0x11 && cascadeFull._shadow[1] == 0x22 && cascadeFull._shadow[2] == 0x00,
               "write_all_zero_extends");
    const auto& w3 = cascadeFullConn.writes().back();
    check_true(w3[0] == 0x00 && w3[1] == 0x22 && w3[2] == 0x11, "write_all_zero_extends_wire");

    uint8_t long_vals[] = {0x44, 0x55, 0x66, 0x77};
    cascadeFull.write_all(long_vals, 4); // longer -> truncate
    check_true(cascadeFull._shadow[0] == 0x44 && cascadeFull._shadow[1] == 0x55 && cascadeFull._shadow[2] == 0x66,
               "write_all_truncates");
    const auto& w4 = cascadeFullConn.writes().back();
    check_true(w4[0] == 0x66 && w4[1] == 0x55 && w4[2] == 0x44, "write_all_truncates_wire");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
