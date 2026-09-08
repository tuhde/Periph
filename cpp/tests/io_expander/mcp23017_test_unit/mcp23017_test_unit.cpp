#include <stdio.h>
#include "I2CConnectionMock.h"
#include "MCP23017.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Access protected register constants for building expected values.
class MCP23017TestAccess : public MCP23017Full {
public:
    using MCP23017Full::MCP23017Full;
    using MCP23017Minimal::REG_IODIRA;
    using MCP23017Minimal::REG_IODIRB;
    using MCP23017Minimal::REG_IPOLA;
    using MCP23017Minimal::REG_IPOLB;
    using MCP23017Full::REG_DEFVALB;
    using MCP23017Minimal::REG_GPPUA;
    using MCP23017Minimal::REG_GPPUB;
    using MCP23017Minimal::REG_GPIOA;
    using MCP23017Minimal::REG_GPIOB;
    using MCP23017Minimal::REG_OLATA;
    using MCP23017Minimal::REG_OLATB;
    using MCP23017Full::REG_GPINTENA;
    using MCP23017Full::REG_DEFVALA;
    using MCP23017Full::REG_IOCON;
    using MCP23017Full::REG_INTFA;
    using MCP23017Full::REG_INTCAPA;
    using MCP23017Full::REG_INTCAPB;
};

int main() {
    I2CConnectionMock connection;
    MCP23017TestAccess chip(connection);
    check_true(true, "init");

    const auto& regs = connection.registers();

    // Init sequence: OLATA/OLATB=0x00, IODIRA/IODIRB=0x7F (GPA7/GPB7 forced
    // output-only), IPOLA/IPOLB=0x00, GPPUA/GPPUB=0x00.
    check_true(regs.at(MCP23017TestAccess::REG_OLATA) == 0x00, "init_olata");
    check_true(regs.at(MCP23017TestAccess::REG_OLATB) == 0x00, "init_olatb");
    check_true(regs.at(MCP23017TestAccess::REG_IODIRA) == 0x7F, "init_iodira_output_only");
    check_true(regs.at(MCP23017TestAccess::REG_IODIRB) == 0x7F, "init_iodirb_output_only");
    check_true(regs.at(MCP23017TestAccess::REG_IPOLA) == 0x00, "init_ipola");
    check_true(regs.at(MCP23017TestAccess::REG_GPPUA) == 0x00, "init_gppua");

    // read_port(0)/(1) -> GPIOA/GPIOB (register-addressed write_read, unlike
    // PCF8574/PCF8575's plain read()/write() — setRegister() works directly).
    connection.setRegister(MCP23017TestAccess::REG_GPIOA, {0xA5});
    check_true(chip.read_port(0) == 0xA5, "read_port_a");
    connection.setRegister(MCP23017TestAccess::REG_GPIOB, {0x5A});
    check_true(chip.read_port(1) == 0x5A, "read_port_b");

    // write_port updates OLAT register and shadow.
    chip.write_port(0, 0x3C);
    check_true(regs.at(MCP23017TestAccess::REG_OLATA) == 0x3C, "write_port_a_register");
    check_true(chip._shadow[0] == 0x3C, "write_port_a_shadow");

    // pin() read on PORTA and PORTB.
    connection.setRegister(MCP23017TestAccess::REG_GPIOA, {0x01});
    auto pin0 = chip.pin(0);
    check_true(pin0.read() == 1, "pin_read_porta");

    connection.setRegister(MCP23017TestAccess::REG_GPIOB, {0x02});
    auto pin9 = chip.pin(9);
    check_true(pin9.read() == 1, "pin_read_portb");

    // Pin direction: OUTPUT clears the IODIRA bit; INPUT sets it.
    auto pin1 = chip.pin(1);
    pin1.mode(OUTPUT);
    check_true(regs.at(MCP23017TestAccess::REG_IODIRA) == (uint8_t)(0x7F & ~0x02), "pin_output_clears_iodir");
    pin1.mode(INPUT);
    check_true(regs.at(MCP23017TestAccess::REG_IODIRA) == 0x7F, "pin_input_sets_iodir");

    // Pin set high/low preserves other output bits (shadow read-modify-write).
    chip.write_port(0, 0x00);
    pin0.high();
    check_true(regs.at(MCP23017TestAccess::REG_OLATA) == 0x01, "pin0_on");
    auto pin2 = chip.pin(2);
    pin2.high();
    check_true(regs.at(MCP23017TestAccess::REG_OLATA) == 0x05, "pin2_on_preserves_pin0");
    pin0.low();
    check_true(regs.at(MCP23017TestAccess::REG_OLATA) == 0x04, "pin0_off_preserves_pin2");

    // Toggle: IOExpanderPin::toggle() reads the actual GPIO pin level (not
    // the OLAT shadow) — on real hardware these agree for an output pin, so
    // the mock's GPIOA register must be kept in sync with OLATA here.
    connection.setRegister(MCP23017TestAccess::REG_GPIOA, {0x04});
    pin0.toggle();
    check_true(regs.at(MCP23017TestAccess::REG_OLATA) == 0x05, "pin0_toggle_on");
    connection.setRegister(MCP23017TestAccess::REG_GPIOA, {0x05});
    pin0.toggle();
    check_true(regs.at(MCP23017TestAccess::REG_OLATA) == 0x04, "pin0_toggle_off");

    // Full: configure_pullup / configure_polarity / set_default_value.
    chip.configure_pullup(0, 0xFF);
    check_true(regs.at(MCP23017TestAccess::REG_GPPUA) == 0xFF, "configure_pullup");
    chip.configure_polarity(1, 0x0F);
    check_true(regs.at(MCP23017TestAccess::REG_IPOLB) == 0x0F, "configure_polarity");
    chip.set_default_value(0, 0x11);
    check_true(regs.at(MCP23017TestAccess::REG_DEFVALA) == 0x11, "set_default_value");

    // pollInterrupt(port): reads INTF then INTCAP (discarded); returns INTF value.
    connection.setRegister(MCP23017TestAccess::REG_INTFA, {0x08});
    connection.setRegister(MCP23017TestAccess::REG_INTCAPA, {0xFF});
    check_true(chip.pollInterrupt(0) == 0x08, "poll_interrupt");

    // read_capture(port): reads INTCAP directly.
    connection.setRegister(MCP23017TestAccess::REG_INTCAPB, {0x22});
    check_true(chip.read_capture(1) == 0x22, "read_capture");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
