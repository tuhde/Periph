#include <algorithm>
#include <stdio.h>
#include "I2CConnectionMock.h"
#include "PCA6408A.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static uint8_t g_changed = 0;
static void onChange(uint8_t changed) { g_changed = changed; }
static int g_watchHits = 0;
static void onWatch(PCA6408AFull::IOExpanderPin*) { g_watchHits++; }

int main() {
    I2CConnectionMock connection;
    PCA6408AFull chip(connection);
    const auto& regs = connection.registers();

    check_true(regs.at(PCA6408AMinimal::REG_OUTPUT) == 0xFF, "init_output");
    check_true(regs.at(PCA6408AMinimal::REG_POLARITY) == 0x00, "init_polarity");
    check_true(regs.at(PCA6408AMinimal::REG_CONFIG) == 0xFF, "init_config");
    check_true(chip._shadow == 0xFF && chip._direction == 0xFF, "init_shadows");

    connection.setRegister(PCA6408AMinimal::REG_INPUT, {0xA5});
    check_true(chip.read_port() == 0xA5, "read_port");
    check_true(chip.read_port(1) == 0xA5, "read_port_ignores_port");

    chip.write_port(0, 0x3C);
    check_true(regs.at(PCA6408AMinimal::REG_OUTPUT) == 0x3C, "write_port_register");
    check_true(chip._shadow == 0x3C, "write_port_shadow");

    auto pin7 = chip.pin(7);
    pin7.mode(OUTPUT);
    check_true(regs.at(PCA6408AMinimal::REG_CONFIG) == 0x7F, "p7_output");
    pin7.mode(INPUT);
    check_true(regs.at(PCA6408AMinimal::REG_CONFIG) == 0xFF, "p7_input");

    // OUTPUT is written before CONFIG when a pin becomes an output.
    chip.write_port(0, 0x00);
    size_t before = connection.writes().size();
    chip.pin(1).mode(OUTPUT);
    check_true(connection.writes().size() == before + 2 &&
               connection.writes()[before][0] == PCA6408AMinimal::REG_OUTPUT &&
               connection.writes()[before + 1][0] == PCA6408AMinimal::REG_CONFIG, "output_before_config");

    chip.write_port(0, 0x00);
    connection.setRegister(PCA6408AMinimal::REG_INPUT, {0x04});
    check_true(chip.pin(2).read() == 1, "pin_read");
    chip.pin(0).high();
    check_true(regs.at(PCA6408AMinimal::REG_OUTPUT) == 0x01, "pin0_on");
    chip.pin(2).high();
    check_true(regs.at(PCA6408AMinimal::REG_OUTPUT) == 0x05, "pin2_on_preserves_pin0");
    chip.pin(0).low();
    check_true(regs.at(PCA6408AMinimal::REG_OUTPUT) == 0x04, "pin0_off_preserves_pin2");
    chip.pin(0).toggle();
    check_true(regs.at(PCA6408AMinimal::REG_OUTPUT) == 0x05, "pin0_toggle_on");
    chip.pin(0).toggle();
    check_true(regs.at(PCA6408AMinimal::REG_OUTPUT) == 0x04, "pin0_toggle_off");

    // No pull-ups: INPUT_PULLUP is rejected without a write.
    before = connection.writes().size();
    check_true(!chip.pin(3).mode(INPUT_PULLUP) && connection.writes().size() == before, "pull_up_rejected");

    chip.configure_polarity(0x0F);
    check_true(regs.at(PCA6408AMinimal::REG_POLARITY) == 0x0F, "configure_polarity");

    before = connection.writes().size();
    chip.set_direction(0xF0);
    check_true(regs.at(PCA6408AMinimal::REG_CONFIG) == 0xF0, "set_direction_register");
    check_true(connection.writes().size() >= before + 2 &&
               connection.writes()[before][0] == PCA6408AMinimal::REG_OUTPUT &&
               connection.writes()[before + 1][0] == PCA6408AMinimal::REG_CONFIG, "set_direction_order");
    check_true(chip.get_direction() == 0xF0, "get_direction");

    check_true(chip.read_register(PCA6408AMinimal::REG_CONFIG) == 0xF0, "read_register");
    chip.write_register(PCA6408AMinimal::REG_OUTPUT, 0x81);
    check_true(chip._shadow == 0x81, "write_register_output_shadow");
    chip.write_register(PCA6408AMinimal::REG_CONFIG, 0x0F);
    check_true(chip._direction == 0x0F, "write_register_config_shadow");
    check_true(!chip.write_register(PCA6408AMinimal::REG_INPUT, 0x00), "write_register_input_rejected");

    // pollInterrupt: changed mask limited to inputs, cache updated.
    chip.set_direction(0xF0);
    connection.setRegister(PCA6408AMinimal::REG_INPUT, {0x00});
    chip.pollInterrupt();
    connection.setRegister(PCA6408AMinimal::REG_INPUT, {0x35});
    check_true(chip.pollInterrupt() == 0x30, "poll_interrupt_masks_outputs");
    check_true(chip.pollInterrupt() == 0x00, "poll_interrupt_no_change");

    // service() dispatches callback and pin watches.
    chip.onInterrupt(onChange);
    auto p4 = chip.pin(4);
    check_true(p4.watch(onWatch, InputPin::kFalling), "watch_input_ok");
    connection.setRegister(PCA6408AMinimal::REG_INPUT, {0x25});  // P4 falls, P5 unchanged (1)
    chip.service();
    check_true(g_changed == 0x10, "service_callback_mask");
    check_true(g_watchHits == 1, "service_watch_falling");
    connection.setRegister(PCA6408AMinimal::REG_INPUT, {0x35});  // P4 rises: falling filter blocks
    chip.service();
    check_true(g_watchHits == 1, "service_watch_filter");
    p4.unwatch();

    auto p0 = chip.pin(0);
    p0.mode(OUTPUT);
    check_true(!p0.watch(onWatch), "watch_output_rejected");

    chip.reset();
    check_true(regs.at(PCA6408AMinimal::REG_OUTPUT) == 0xFF, "reset_output");
    check_true(regs.at(PCA6408AMinimal::REG_POLARITY) == 0x00, "reset_polarity");
    check_true(regs.at(PCA6408AMinimal::REG_CONFIG) == 0xFF, "reset_config");
    check_true(chip._shadow == 0xFF && chip._direction == 0xFF, "reset_shadows");

    I2CConnectionMock c2;
    PCA6408AMinimal minimal(c2);
    check_true(minimal.read_port() == 0, "minimal_constructible");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
