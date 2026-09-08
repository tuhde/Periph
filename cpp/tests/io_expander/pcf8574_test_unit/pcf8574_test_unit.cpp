#include <stdio.h>
#include "I2CConnectionMock.h"
#include "PCF8574.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    // PCF8574 has no sub-registers: every transaction is a single plain
    // byte read()/write() (no register pointer), so I2CConnectionMock's
    // register map is never consulted — reads must be preloaded via
    // queueRead() in the exact order the driver will issue them.
    I2CConnectionMock connection;
    PCF8574Full chip(connection);
    check_true(true, "init");

    // Construction writes 0xFF (all pins to quasi-bidirectional input mode).
    // PCF8574Full's constructor also issues one extra read to seed `_prev`.
    const auto& writes = connection.writes();
    check_true(writes.size() >= 1 && writes[0].size() == 1 && writes[0][0] == 0xFF, "init_writes_0xff");
    check_true(chip._shadow == 0xFF, "init_shadow");

    // read_port(): plain single-byte read.
    connection.queueRead({0x5A});
    check_true(chip.read_port() == 0x5A, "read_port");

    // write_port(): plain single-byte write; updates shadow.
    chip.write_port(0, 0x3C);
    check_true(writes.back().size() == 1 && writes.back()[0] == 0x3C, "write_port");
    check_true(chip._shadow == 0x3C, "write_port_shadow");

    // pin().read() reads the live bus level (not the shadow).
    auto pin3 = chip.pin(3);
    connection.queueRead({0x08}); // bit 3 high
    check_true(pin3.read() == 1, "pin_read");

    // Pin set high/low preserves other shadow bits (read-modify-write).
    chip.write_port(0, 0xFF);
    pin3.low();
    check_true(writes.back()[0] == (uint8_t)(0xFF & ~0x08), "pin3_off");
    auto pin5 = chip.pin(5);
    pin5.low();
    check_true(writes.back()[0] == (uint8_t)(0xFF & ~0x08 & ~0x20), "pin5_off_preserves_pin3");
    pin3.high();
    check_true(writes.back()[0] == (uint8_t)(0xFF & ~0x20), "pin3_on_preserves_pin5");

    // Toggle: IOExpanderPin::toggle() reads the actual bus level (not the
    // shadow) — on real hardware these agree, so the mock's queued read
    // must mirror the current shadow byte here.
    connection.queueRead({(uint8_t)(0xFF & ~0x20)}); // current shadow: pin3 high, pin5 low
    pin3.toggle();
    check_true(writes.back()[0] == (uint8_t)(0xFF & ~0x20 & ~0x08), "pin3_toggle_off");
    connection.queueRead({(uint8_t)(0xFF & ~0x20 & ~0x08)});
    pin3.toggle();
    check_true(writes.back()[0] == (uint8_t)(0xFF & ~0x20), "pin3_toggle_on");

    // mode(INPUT)/(OUTPUT) releases high (input) or drives low (output).
    auto pin0 = chip.pin(0);
    pin0.mode(OUTPUT);
    check_true((writes.back()[0] & 0x01) == 0, "pin_mode_out_drives_low");
    pin0.mode(INPUT);
    check_true((writes.back()[0] & 0x01) == 1, "pin_mode_in_releases_high");

    // Full: pollInterrupt() compares to the previous read and returns the
    // changed-pin bitmask, also updating the stored previous value.
    connection.queueRead({0xFF});
    chip.pollInterrupt(); // resync _prev to a known value (0xFF)
    connection.queueRead({0xF7}); // bit 3 now low
    check_true(chip.pollInterrupt() == 0x08, "poll_interrupt_detects_change");
    connection.queueRead({0xF7}); // no further change
    check_true(chip.pollInterrupt() == 0x00, "poll_interrupt_no_change");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
