#include <algorithm>
#include <stdio.h>
#include "I2CConnectionMock.h"
#include "SPIConnectionMock.h"
#include "MCP23008.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool lastWriteIs(const std::vector<std::vector<uint8_t>>& w, std::initializer_list<uint8_t> bytes) {
    if (w.empty()) return false;
    const auto& last = w.back();
    return last.size() == bytes.size() && std::equal(last.begin(), last.end(), bytes.begin());
}

int main() {
    // ---------------------------------------------------------------- I2C
    I2CConnectionMock connection;
    MCP23008Full chip(connection);
    const auto& regs = connection.registers();

    check_true(regs.at(MCP23x08Minimal::REG_OLAT) == 0x00, "init_olat");
    check_true(chip._shadow == 0x00 && chip._direction == 0xFF, "init_shadows");
    bool wroteIodir = false;
    for (const auto& w : connection.writes()) if (w[0] == MCP23x08Minimal::REG_IODIR) wroteIodir = true;
    check_true(!wroteIodir, "init_no_iodir_write");

    connection.setRegister(MCP23x08Minimal::REG_GPIO, {0xA5});
    check_true(chip.read_port() == 0xA5, "read_port");
    check_true(chip.read_port(1) == 0xA5, "read_port_ignores_port");

    chip.write_port(0, 0x3C);
    check_true(regs.at(MCP23x08Minimal::REG_OLAT) == 0x3C, "write_port_register");
    check_true(chip._shadow == 0x3C, "write_port_shadow");

    // GP7 is a normal bidirectional pin.
    auto pin7 = chip.pin(7);
    pin7.mode(OUTPUT);
    check_true(regs.at(MCP23x08Minimal::REG_IODIR) == 0x7F, "gp7_output");
    pin7.mode(INPUT);
    check_true(regs.at(MCP23x08Minimal::REG_IODIR) == 0xFF, "gp7_input");

    // OLAT is written before IODIR when a pin becomes an output.
    chip.write_port(0, 0x00);
    size_t before = connection.writes().size();
    chip.pin(1).mode(OUTPUT);
    check_true(connection.writes().size() == before + 2 &&
               connection.writes()[before][0] == MCP23x08Minimal::REG_OLAT &&
               connection.writes()[before + 1][0] == MCP23x08Minimal::REG_IODIR, "olat_before_iodir");

    chip.write_port(0, 0x00);
    connection.setRegister(MCP23x08Minimal::REG_GPIO, {0x04});
    check_true(chip.pin(2).read() == 1, "pin_read");
    chip.pin(0).high();
    check_true(regs.at(MCP23x08Minimal::REG_OLAT) == 0x01, "pin0_on");
    chip.pin(2).high();
    check_true(regs.at(MCP23x08Minimal::REG_OLAT) == 0x05, "pin2_on_preserves_pin0");
    chip.pin(0).low();
    check_true(regs.at(MCP23x08Minimal::REG_OLAT) == 0x04, "pin0_off_preserves_pin2");
    chip.pin(0).toggle();
    check_true(regs.at(MCP23x08Minimal::REG_OLAT) == 0x05, "pin0_toggle_on");
    chip.pin(0).toggle();
    check_true(regs.at(MCP23x08Minimal::REG_OLAT) == 0x04, "pin0_toggle_off");

    // OutputPin interface.
    OutputPin* op = nullptr;
    auto p3 = chip.pin(3);
    op = &p3;
    op->set(true);
    check_true(regs.at(MCP23x08Minimal::REG_OLAT) == 0x0C, "output_pin_set");

    chip.configure_pullup(0xF0);
    check_true(regs.at(MCP23x08Minimal::REG_GPPU) == 0xF0, "configure_pullup");
    chip.configure_polarity(0x0F);
    check_true(regs.at(MCP23x08Minimal::REG_IPOL) == 0x0F, "configure_polarity");
    chip.set_default_value(0x11);
    check_true(regs.at(MCP23x08Minimal::REG_DEFVAL) == 0x11, "set_default_value");
    chip.enable_pin_interrupt(0xF0);
    check_true(regs.at(MCP23x08Minimal::REG_GPINTEN) == 0xF0, "enable_pin_interrupt");

    // INPUT_PULLUP sets the pin's GPPU bit.
    chip.configure_pullup(0x00);
    chip.pin(5).mode(INPUT_PULLUP);
    check_true(regs.at(MCP23x08Minimal::REG_GPPU) == 0x20, "pin_input_pullup");

    chip.configure_int_pin(false, true);
    check_true(regs.at(MCP23x08Minimal::REG_IOCON) == 0x02, "iocon_intpol");
    chip.configure_int_pin(true, true);
    check_true(regs.at(MCP23x08Minimal::REG_IOCON) == 0x04, "iocon_odr");
    chip.configure_int_pin();
    check_true(regs.at(MCP23x08Minimal::REG_IOCON) == 0x00, "iocon_default");
    chip.set_sequential(false);
    check_true(regs.at(MCP23x08Minimal::REG_IOCON) == 0x20, "iocon_seqop_off");
    chip.set_sequential(true);
    check_true(regs.at(MCP23x08Minimal::REG_IOCON) == 0x00, "iocon_seqop_on");

    connection.setRegister(MCP23x08Minimal::REG_INTF, {0x10});
    connection.setRegister(MCP23x08Minimal::REG_INTCAP, {0xFF});
    check_true(chip.pollInterrupt() == 0x10, "poll_interrupt");
    connection.setRegister(MCP23x08Minimal::REG_INTCAP, {0x22});
    check_true(chip.read_capture() == 0x22, "read_capture");

    chip.write_register(MCP23x08Minimal::REG_IODIR, 0x0F);
    check_true(chip._direction == 0x0F, "write_register_iodir_shadow");
    chip.write_register(MCP23x08Minimal::REG_OLAT, 0x81);
    check_true(chip._shadow == 0x81 && chip.read_register(MCP23x08Minimal::REG_OLAT) == 0x81,
               "write_register_olat");

    // onInterrupt with no armed pins fails; watch on an output fails.
    I2CConnectionMock c2;
    MCP23008Full chip2(c2);
    check_true(!chip2.onInterrupt(nullptr), "on_interrupt_unarmed");
    chip2.pin(0).mode(OUTPUT);
    check_true(!chip2.pin(0).watch(nullptr), "watch_output_rejected");

    chip.reset();
    check_true(regs.at(MCP23x08Minimal::REG_IODIR) == 0xFF, "reset_iodir");
    check_true(regs.at(MCP23x08Minimal::REG_OLAT) == 0x00, "reset_olat");
    check_true(regs.at(MCP23x08Minimal::REG_GPPU) == 0x00, "reset_gppu");
    check_true(regs.at(MCP23x08Minimal::REG_GPINTEN) == 0x00, "reset_gpinten");
    check_true(chip._shadow == 0x00 && chip._direction == 0xFF, "reset_shadows");

    MCP23008Minimal minimal(c2);
    check_true(minimal.read_port() == 0x00, "minimal_i2c");

    // ---------------------------------------------------------------- SPI
    SPIConnectionMock spi;
    MCP23S08Full schip(spi, 2);
    const auto& w = spi.writes();

    check_true(w.size() >= 2 && w[0] == std::vector<uint8_t>({0x40, 0x05, 0x08}), "spi_init_haen_addr0");
    check_true(w.size() >= 2 && w[1] == std::vector<uint8_t>({0x44, 0x0A, 0x00}), "spi_init_olat");

    schip.write_port(0, 0x5A);
    check_true(lastWriteIs(w, {0x44, 0x0A, 0x5A}), "spi_write_port");

    // The mock treats a 2-byte header as {opcode, reg}.
    spi.setRegister(0x09, {0xC3});
    check_true(schip.read_port() == 0xC3, "spi_read_port");
    check_true(lastWriteIs(w, {0x45, 0x09}), "spi_read_header");

    schip.configure_pullup(0x0F);
    check_true(lastWriteIs(w, {0x44, 0x06, 0x0F}), "spi_configure_pullup");
    schip.configure_int_pin(true);
    check_true(lastWriteIs(w, {0x44, 0x05, 0x0C}), "spi_iocon_keeps_haen");
    schip.reset();
    bool haenKept = false;
    for (const auto& f : w) if (f == std::vector<uint8_t>({0x44, 0x05, 0x08})) haenKept = true;
    check_true(haenKept, "spi_reset_keeps_haen");

    SPIConnectionMock spi2;
    MCP23S08Minimal sminimal(spi2, 0);
    check_true(sminimal.read_port() == 0x00, "minimal_spi");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
