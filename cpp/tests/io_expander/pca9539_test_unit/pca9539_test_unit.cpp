#include <algorithm>
#include <stdio.h>
#include "I2CConnectionMock.h"
#include "PCA9539.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static uint16_t g_changed = 0;
static void onChange(uint16_t changed) { g_changed = changed; }
static int g_watchHits = 0;
static void onWatch(PCA9539Full::IOExpanderPin*) { g_watchHits++; }

int main() {
    I2CConnectionMock connection;
    PCA9539Full chip(connection);
    const auto& regs = connection.registers();

    // Init writes the reset state of both ports as two-byte pair writes.
    check_true(regs.at(0x02) == 0xFF && regs.at(0x03) == 0xFF, "init_output");
    check_true(regs.at(0x04) == 0x00 && regs.at(0x05) == 0x00, "init_polarity");
    check_true(regs.at(0x06) == 0xFF && regs.at(0x07) == 0xFF, "init_config");
    check_true(chip._shadow == 0xFFFF && chip._direction == 0xFFFF, "init_shadows");
    bool wroteInput = false;
    for (const auto& w : connection.writes()) if (w.size() > 1 && w[0] < 2) wroteInput = true;
    check_true(!wroteInput, "init_never_writes_input");

    connection.setRegister(PCA9539Minimal::REG_INPUT, {0xA5, 0x5A});
    check_true(chip.read_port(0) == 0xA5, "read_port_0");
    check_true(chip.read_port(1) == 0x5A, "read_port_1");
    check_true(chip.read_all() == 0x5AA5, "read_all");

    chip.write_port(0, 0x3C);
    check_true(regs.at(0x02) == 0x3C, "write_port_0_register");
    chip.write_port(1, 0xC3);
    check_true(regs.at(0x03) == 0xC3, "write_port_1_register");
    check_true(chip._shadow == 0xC33C, "write_port_shadow");

    size_t before = connection.writes().size();
    chip.write_all(0x1234);
    check_true(regs.at(0x02) == 0x34 && regs.at(0x03) == 0x12, "write_all_registers");
    check_true(connection.writes().size() == before + 1 && connection.writes()[before].size() == 3, "write_all_single_transfer");
    check_true(chip._shadow == 0x1234, "write_all_shadow");

    // Direction: output clears the CONFIG bit of its port, input sets it.
    chip.write_all(0xFFFF);
    auto pin7 = chip.pin(7);
    pin7.mode(OUTPUT);
    check_true(regs.at(0x06) == 0x7F && regs.at(0x07) == 0xFF, "p07_output");
    pin7.mode(INPUT);
    check_true(regs.at(0x06) == 0xFF, "p07_input");
    auto pin12 = chip.pin(12);
    pin12.mode(OUTPUT);
    check_true(regs.at(0x07) == 0xEF && regs.at(0x06) == 0xFF, "p14_output_port1");
    pin12.mode(INPUT);
    check_true(regs.at(0x07) == 0xFF, "p14_input_port1");

    // OUTPUT is written before CONFIG when a pin becomes an output.
    before = connection.writes().size();
    chip.pin(9).mode(OUTPUT);
    check_true(connection.writes().size() == before + 2 &&
               connection.writes()[before][0] == 0x03 &&
               connection.writes()[before + 1][0] == 0x07, "output_before_config");
    chip.pin(9).mode(INPUT);

    chip.write_all(0x0000);
    connection.setRegister(PCA9539Minimal::REG_INPUT, {0x04, 0x80});
    check_true(chip.pin(2).read() == 1, "pin_read_port0");
    check_true(chip.pin(15).read() == 1, "pin_read_port1");
    check_true(chip.pin(3).read() == 0, "pin_read_low");
    chip.pin(0).high();
    check_true(regs.at(0x02) == 0x01, "pin0_on");
    chip.pin(2).high();
    check_true(regs.at(0x02) == 0x05, "pin2_on_preserves_pin0");
    chip.pin(0).low();
    check_true(regs.at(0x02) == 0x04, "pin0_off_preserves_pin2");
    chip.pin(8).high();
    check_true(regs.at(0x03) == 0x01 && regs.at(0x02) == 0x04, "pin8_on_port1");
    chip.pin(8).toggle();
    check_true(regs.at(0x03) == 0x00, "pin8_toggle_off");
    chip.pin(8).toggle();
    check_true(regs.at(0x03) == 0x01, "pin8_toggle_on");

    // No pull-ups: INPUT_PULLUP is rejected without a write.
    before = connection.writes().size();
    check_true(!chip.pin(3).mode(INPUT_PULLUP) && connection.writes().size() == before, "pull_up_rejected");

    before = connection.writes().size();
    chip.configure_polarity(0xF00F);
    check_true(regs.at(0x04) == 0x0F && regs.at(0x05) == 0xF0, "configure_polarity");
    check_true(connection.writes()[before].size() == 3, "configure_polarity_single_write");

    before = connection.writes().size();
    chip.set_direction(0xFF00);
    check_true(regs.at(0x06) == 0x00 && regs.at(0x07) == 0xFF, "set_direction_registers");
    check_true(connection.writes().size() >= before + 2 &&
               connection.writes()[before][0] == 0x02 &&
               connection.writes()[before + 1][0] == 0x06, "set_direction_order");
    check_true(chip.get_direction() == 0xFF00, "get_direction");

    check_true(chip.read_register(0x07) == 0xFF, "read_register");
    chip.write_register(0x03, 0x81);
    check_true((chip._shadow >> 8) == 0x81, "write_register_output1_shadow");
    chip.write_register(0x06, 0x0F);
    check_true(chip._direction == 0xFF0F, "write_register_config0_shadow");
    chip.write_register(0x05, 0x3C);
    check_true(!chip.write_register(0x00, 0x00) && !chip.write_register(0x01, 0x00) && !chip.write_register(0x08, 0x00),
               "write_register_rejects_input_and_range");

    // pollInterrupt: 16-bit changed mask limited to inputs, cache updated.
    chip.set_direction(0xF0F0);
    connection.setRegister(PCA9539Minimal::REG_INPUT, {0x00, 0x00});
    chip.pollInterrupt();
    connection.setRegister(PCA9539Minimal::REG_INPUT, {0x35, 0x9A});
    check_true(chip.pollInterrupt() == 0x9030, "poll_interrupt_masks_outputs");
    check_true(chip.pollInterrupt() == 0x0000, "poll_interrupt_no_change");

    // Interrupt errata (datasheet 8.2.4.1): with a subscription active pollInterrupt
    // rewrites OUTPUT0 after the INPUT read; without one it does not.
    before = connection.writes().size();
    chip.pollInterrupt();
    bool wroteData = false;
    for (size_t i = before; i < connection.writes().size(); ++i) if (connection.writes()[i].size() > 1) wroteData = true;
    check_true(!wroteData, "errata_no_subscription_no_write");
    chip.onInterrupt(onChange);
    before = connection.writes().size();
    chip.pollInterrupt();
    wroteData = false;
    for (size_t i = before; i < connection.writes().size(); ++i)
        if (connection.writes()[i].size() > 1 && connection.writes()[i][0] == 0x02) wroteData = true;
    check_true(wroteData, "errata_subscribed_rewrites_output0");
    chip.offInterrupt();

    // service() dispatches callback and pin watches.
    chip.onInterrupt(onChange);
    auto p12 = chip.pin(12);
    check_true(p12.watch(onWatch, InputPin::kFalling), "watch_input_ok");
    connection.setRegister(PCA9539Minimal::REG_INPUT, {0x35, 0x8A});  // P14 (pin 12) falls
    chip.service();
    check_true(g_changed == 0x1000, "service_callback_mask");
    check_true(g_watchHits == 1, "service_watch_falling");
    connection.setRegister(PCA9539Minimal::REG_INPUT, {0x35, 0x9A});  // pin 12 rises: falling filter blocks
    chip.service();
    check_true(g_watchHits == 1, "service_watch_filter");
    p12.unwatch();

    auto p0 = chip.pin(0);
    p0.mode(OUTPUT);
    check_true(!p0.watch(onWatch), "watch_output_rejected");

    chip.reset();
    check_true(regs.at(0x02) == 0xFF && regs.at(0x03) == 0xFF, "reset_output");
    check_true(regs.at(0x04) == 0x00 && regs.at(0x05) == 0x00, "reset_polarity");
    check_true(regs.at(0x06) == 0xFF && regs.at(0x07) == 0xFF, "reset_config");
    check_true(chip._shadow == 0xFFFF && chip._direction == 0xFFFF, "reset_shadows");

    I2CConnectionMock c2;
    PCA9539Minimal minimal(c2);
    check_true(minimal.read_port(0) == 0, "minimal_constructible");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
