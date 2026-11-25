#include <algorithm>
#include <stdio.h>
#include "I2CConnectionMock.h"
#include "PCA9536.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static uint8_t g_changed = 0;
static void onChange(uint8_t changed) { g_changed = changed; }
static int g_watchHits = 0;
static void onWatch(PCA9536Full::IOExpanderPin*) { g_watchHits++; }

int main() {
    I2CConnectionMock connection;
    PCA9536Full chip(connection);
    const auto& regs = connection.registers();

    check_true(regs.at(PCA9536Minimal::REG_OUTPUT) == 0xFF, "init_output");
    check_true(regs.at(PCA9536Minimal::REG_POLARITY) == 0x00, "init_polarity");
    check_true(regs.at(PCA9536Minimal::REG_CONFIG) == 0xFF, "init_config");
    check_true(chip._shadow == 0xFF && chip._direction == 0xFF, "init_shadows");

    connection.setRegister(PCA9536Minimal::REG_INPUT, {0xF5});
    check_true(chip.read_port() == 0x05, "read_port_masks_unused_bits");
    check_true(chip.read_port(1) == 0x05, "read_port_ignores_port");

    chip.write_port(0, 0x0C);
    check_true(regs.at(PCA9536Minimal::REG_OUTPUT) == 0xFC, "write_port_register");
    check_true(chip._shadow == 0xFC, "write_port_shadow");
    chip.write_port(0, 0xFF);
    check_true(regs.at(PCA9536Minimal::REG_OUTPUT) == 0xFF, "write_port_masks_high_nibble");

    auto pin3 = chip.pin(3);
    pin3.mode(OUTPUT);
    check_true(regs.at(PCA9536Minimal::REG_CONFIG) == 0xF7, "p3_output");
    pin3.mode(INPUT);
    check_true(regs.at(PCA9536Minimal::REG_CONFIG) == 0xFF, "p3_input");

    // OUTPUT is written before CONFIG when a pin becomes an output.
    chip.write_port(0, 0x00);
    size_t before = connection.writes().size();
    chip.pin(1).mode(OUTPUT);
    check_true(connection.writes().size() == before + 2 &&
               connection.writes()[before][0] == PCA9536Minimal::REG_OUTPUT &&
               connection.writes()[before + 1][0] == PCA9536Minimal::REG_CONFIG, "output_before_config");

    chip.write_port(0, 0x00);
    connection.setRegister(PCA9536Minimal::REG_INPUT, {0x04});
    check_true(chip.pin(2).read() == 1, "pin_read");
    chip.pin(0).high();
    check_true(regs.at(PCA9536Minimal::REG_OUTPUT) == 0xF1, "pin0_on");
    chip.pin(2).high();
    check_true(regs.at(PCA9536Minimal::REG_OUTPUT) == 0xF5, "pin2_on_preserves_pin0");
    chip.pin(0).low();
    check_true(regs.at(PCA9536Minimal::REG_OUTPUT) == 0xF4, "pin0_off_preserves_pin2");
    chip.pin(0).toggle();
    check_true(regs.at(PCA9536Minimal::REG_OUTPUT) == 0xF5, "pin0_toggle_on");
    chip.pin(0).toggle();
    check_true(regs.at(PCA9536Minimal::REG_OUTPUT) == 0xF4, "pin0_toggle_off");

    // The weak pull-up is fixed: INPUT_PULLUP is rejected without a write.
    before = connection.writes().size();
    check_true(!chip.pin(3).mode(INPUT_PULLUP) && connection.writes().size() == before, "pull_up_rejected");

    chip.configure_polarity(0x0F);
    check_true(regs.at(PCA9536Minimal::REG_POLARITY) == 0x0F, "configure_polarity");
    chip.configure_polarity(0x00);

    before = connection.writes().size();
    chip.set_direction(0x0C);
    check_true(regs.at(PCA9536Minimal::REG_CONFIG) == 0xFC, "set_direction_register");
    check_true(connection.writes().size() >= before + 2 &&
               connection.writes()[before][0] == PCA9536Minimal::REG_OUTPUT &&
               connection.writes()[before + 1][0] == PCA9536Minimal::REG_CONFIG, "set_direction_order");
    check_true(chip.get_direction() == 0x0C, "get_direction");

    check_true(chip.read_register(PCA9536Minimal::REG_CONFIG) == 0xFC, "read_register");
    chip.write_register(PCA9536Minimal::REG_OUTPUT, 0xF1);
    check_true(chip._shadow == 0xF1, "write_register_output_shadow");
    chip.write_register(PCA9536Minimal::REG_CONFIG, 0x0F);
    check_true(chip._direction == 0x0F, "write_register_config_shadow");
    check_true(!chip.write_register(PCA9536Minimal::REG_INPUT, 0x00), "write_register_input_rejected");

    // pollChanges: changed mask limited to inputs, cache updated.
    chip.set_direction(0x0C);
    connection.setRegister(PCA9536Minimal::REG_INPUT, {0x00});
    chip.pollChanges();
    connection.setRegister(PCA9536Minimal::REG_INPUT, {0x0D});
    check_true(chip.pollChanges() == 0x0C, "poll_changes_masks_outputs");
    check_true(chip.pollChanges() == 0x00, "poll_changes_no_change");

    // A polarity change refreshes the cache and is not reported.
    connection.setRegister(PCA9536Minimal::REG_INPUT, {0x00});
    chip.configure_polarity(0x0C);
    check_true(chip.pollChanges() == 0x00, "polarity_not_reported");
    chip.configure_polarity(0x00);
    chip.pollChanges();

    // service() dispatches callback and pin watches.
    chip.watchChanges(onChange);
    auto p2 = chip.pin(2);
    check_true(p2.watch(onWatch, InputPin::kFalling), "watch_input_ok");
    connection.setRegister(PCA9536Minimal::REG_INPUT, {0x0C});
    chip.pollChanges();
    connection.setRegister(PCA9536Minimal::REG_INPUT, {0x08});  // P2 falls, P3 unchanged (1)
    chip.service();
    check_true(g_changed == 0x04, "service_callback_mask");
    check_true(g_watchHits == 1, "service_watch_falling");
    connection.setRegister(PCA9536Minimal::REG_INPUT, {0x0C});  // P2 rises: falling filter blocks
    chip.service();
    check_true(g_watchHits == 1, "service_watch_filter");
    p2.unwatch();

    auto p0 = chip.pin(0);
    p0.mode(OUTPUT);
    check_true(!p0.watch(onWatch), "watch_output_rejected");

    chip.reset();
    check_true(regs.at(PCA9536Minimal::REG_OUTPUT) == 0xFF, "reset_output");
    check_true(regs.at(PCA9536Minimal::REG_POLARITY) == 0x00, "reset_polarity");
    check_true(regs.at(PCA9536Minimal::REG_CONFIG) == 0xFF, "reset_config");
    check_true(chip._shadow == 0xFF && chip._direction == 0xFF, "reset_shadows");

    I2CConnectionMock c2;
    PCA9536Minimal minimal(c2);
    check_true(minimal.read_port() == 0, "minimal_constructible");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
