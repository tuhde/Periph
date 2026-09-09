'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { Mcp23017Full } = require('../../packages/periph/src/chips/io_expander/mcp23017');

const REG_IODIRA   = 0x00;
const REG_IPOLA    = 0x02;
const REG_IPOLB    = 0x03;
const REG_DEFVALA  = 0x06;
const REG_GPPUA    = 0x0C;
const REG_INTFA    = 0x0E;
const REG_INTCAPA  = 0x10;
const REG_INTCAPB  = 0x11;
const REG_GPIOA    = 0x12;
const REG_GPIOB    = 0x13;
const REG_OLATA    = 0x14;
const REG_OLATB    = 0x15;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function flushMicrotasks() {
    return new Promise((resolve) => setImmediate(resolve));
}

async function main() {
    // MCP23017 reads are register-addressed (writeRead), so
    // I2CConnectionMock's register map can be preloaded via setRegister().
    const connection = new I2CConnectionMock();
    const chip = new Mcp23017Full(connection);
    await flushMicrotasks(); // let the fire-and-forget constructor writes finish
    checkTrue('init', true);

    // Init sequence: OLATA/OLATB=0x00, IODIRA/IODIRB=0x7F (GPA7/GPB7 forced
    // output-only), IPOLA/IPOLB=0x00, GPPUA/GPPUB=0x00.
    checkTrue('init_olata', connection.registers.get(REG_OLATA) === 0x00);
    checkTrue('init_olatb', connection.registers.get(REG_OLATB) === 0x00);
    checkTrue('init_iodira_output_only', connection.registers.get(REG_IODIRA) === 0x7F);
    checkTrue('init_ipola', connection.registers.get(REG_IPOLA) === 0x00);
    checkTrue('init_gppua', connection.registers.get(REG_GPPUA) === 0x00);

    // readPort(0)/(1) -> GPIOA/GPIOB.
    connection.setRegister(REG_GPIOA, [0xA5]);
    checkTrue('read_port_a', (await chip.readPort(0)) === 0xA5);
    connection.setRegister(REG_GPIOB, [0x5A]);
    checkTrue('read_port_b', (await chip.readPort(1)) === 0x5A);

    // writePort updates OLAT register and shadow.
    await chip.writePort(0, 0x3C);
    checkTrue('write_port_a_register', connection.registers.get(REG_OLATA) === 0x3C);
    checkTrue('write_port_a_shadow', chip._shadow[0] === 0x3C);

    // pin() read on PORTA and PORTB.
    connection.setRegister(REG_GPIOA, [0x01]);
    const pin0 = chip.pin(0);
    checkTrue('pin_read_porta', (await pin0.read()) === 1);

    connection.setRegister(REG_GPIOB, [0x02]);
    const pin9 = chip.pin(9);
    checkTrue('pin_read_portb', (await pin9.read()) === 1);

    // Pin set high/low preserves other output bits (shadow read-modify-write).
    await chip.writePort(0, 0x00);
    await pin0.write(1);
    checkTrue('pin0_on', connection.registers.get(REG_OLATA) === 0x01);
    const pin2 = chip.pin(2);
    await pin2.write(1);
    checkTrue('pin2_on_preserves_pin0', connection.registers.get(REG_OLATA) === 0x05);
    await pin0.write(0);
    checkTrue('pin0_off_preserves_pin2', connection.registers.get(REG_OLATA) === 0x04);

    // Full: configurePullup / configurePolarity / setDefaultValue.
    await chip.configurePullup(0, 0xFF);
    checkTrue('configure_pullup', connection.registers.get(REG_GPPUA) === 0xFF);
    await chip.configurePolarity(1, 0x0F);
    checkTrue('configure_polarity', connection.registers.get(REG_IPOLB) === 0x0F);
    await chip.setDefaultValue(0, 0x11);
    checkTrue('set_default_value', connection.registers.get(REG_DEFVALA) === 0x11);

    // pollInterrupt(port): reads INTF then INTCAP (discarded); returns INTF value.
    connection.setRegister(REG_INTFA, [0x08]);
    connection.setRegister(REG_INTCAPA, [0xFF]);
    checkTrue('poll_interrupt', (await chip.pollInterrupt(0)) === 0x08);

    // readCapture(port): reads INTCAP directly.
    connection.setRegister(REG_INTCAPB, [0x22]);
    checkTrue('read_capture', (await chip.readCapture(1)) === 0x22);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
