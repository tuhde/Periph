#include <stdio.h>
#include "I2CConnectionMock.h"
#include "PCF8575.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    // PCF8575 has no sub-registers: every transaction is a plain 2-byte
    // read()/write() (Port 0 first, Port 1 second; no register pointer), so
    // I2CConnectionMock's register map is never consulted — reads must be
    // preloaded via queueRead() in the exact order the driver will issue
    // them. PCF8575Full's constructor issues one extra 2-byte read to seed
    // `_prev`.
    I2CConnectionMock connection;
    PCF8575Full chip(connection);
    check_true(true, "init");

    const auto& writes = connection.writes();
    check_true(writes.size() >= 1 && writes[0].size() == 2 && writes[0][0] == 0xFF && writes[0][1] == 0xFF,
               "init_writes_ff_ff");
    check_true(chip._shadow[0] == 0xFF && chip._shadow[1] == 0xFF, "init_shadow");

    // read_port(0)/(1): both derived from one 2-byte read.
    connection.queueRead({0x5A, 0xA5});
    check_true(chip.read_port(0) == 0x5A, "read_port_0");
    connection.queueRead({0x5A, 0xA5});
    check_true(chip.read_port(1) == 0xA5, "read_port_1");

    // write_port(): writes both shadow bytes, preserving the untouched port.
    chip.write_port(0, 0x3C);
    check_true(writes.back()[0] == 0x3C && writes.back()[1] == 0xFF, "write_port_0");
    chip.write_port(1, 0x0F);
    check_true(writes.back()[0] == 0x3C && writes.back()[1] == 0x0F, "write_port_1_preserves_port0");

    // pin() read on Port 0 and Port 1.
    auto pin3 = chip.pin(3);   // Port 0, bit 3
    connection.queueRead({0x08, 0x00});
    check_true(pin3.read() == 1, "pin_read_port0");

    auto pin11 = chip.pin(11); // Port 1, bit 3
    connection.queueRead({0x00, 0x08});
    check_true(pin11.read() == 1, "pin_read_port1");

    // Pin set high/low preserves other shadow bits within the same port.
    chip.write_port(0, 0xFF);
    chip.write_port(1, 0xFF);
    pin3.low();
    check_true(writes.back()[0] == (uint8_t)(0xFF & ~0x08) && writes.back()[1] == 0xFF, "pin3_off");
    auto pin5 = chip.pin(5);
    pin5.low();
    check_true(writes.back()[0] == (uint8_t)(0xFF & ~0x08 & ~0x20) && writes.back()[1] == 0xFF,
               "pin5_off_preserves_pin3");
    pin11.low();
    check_true(writes.back()[0] == (uint8_t)(0xFF & ~0x08 & ~0x20) && writes.back()[1] == (uint8_t)(0xFF & ~0x08),
               "pin11_off_only_touches_port1");

    // Toggle: IOExpanderPin::toggle() reads the actual bus level (not the
    // shadow) — on real hardware these agree, so the mock's queued read
    // must mirror the current shadow bytes here.
    uint8_t shadow0 = (uint8_t)(0xFF & ~0x08 & ~0x20);
    uint8_t shadow1 = (uint8_t)(0xFF & ~0x08);
    connection.queueRead({shadow0, shadow1});
    pin3.toggle();
    check_true(writes.back()[0] == (uint8_t)(shadow0 | 0x08), "pin3_toggle_on");
    connection.queueRead({(uint8_t)(shadow0 | 0x08), shadow1});
    pin3.toggle();
    check_true(writes.back()[0] == shadow0, "pin3_toggle_off");

    // mode(INPUT)/(OUTPUT) releases high (input) or drives low (output).
    auto pin0 = chip.pin(0);
    pin0.mode(OUTPUT);
    check_true((writes.back()[0] & 0x01) == 0, "pin_mode_out_drives_low");
    pin0.mode(INPUT);
    check_true((writes.back()[0] & 0x01) == 1, "pin_mode_in_releases_high");

    // Full: pollInterrupt() compares to the previous 2-byte read and returns
    // the 16-bit changed-pin bitmask (bits 0-7 = Port 0, bits 8-15 = Port 1).
    connection.queueRead({0xFF, 0xFF});
    chip.pollInterrupt(); // resync _prev to a known value
    connection.queueRead({0xF7, 0xFE}); // Port0 bit3 low, Port1 bit0 low
    check_true(chip.pollInterrupt() == (uint16_t)(0x08 | (0x01 << 8)), "poll_interrupt_detects_change");
    connection.queueRead({0xF7, 0xFE}); // no further change
    check_true(chip.pollInterrupt() == 0x00, "poll_interrupt_no_change");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
