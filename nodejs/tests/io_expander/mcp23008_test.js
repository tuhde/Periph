'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { Mcp23008Minimal, Mcp23008Full } = require('../../packages/periph/src/chips/io_expander/mcp23008');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',    10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x20', 16);

let passed = 0;
let failed = 0;

function checkEq(label, got, expected) {
    if (got === expected) { console.log('PASS', label); passed++; }
    else { console.log(`FAIL ${label}: got ${got}, expected ${expected}`); failed++; }
}

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

// Wiring for the loopback checks: jumper GP0-GP3 to GP4-GP7 (GPn <-> GPn+4).
async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const chip       = new Mcp23008Minimal(connection);
    await chip.ready;

    checkEq('init_iodir', chip._direction, 0xFF);
    checkEq('init_shadow', chip._shadow, 0x00);

    const port = await chip.readPort();
    checkTrue('read_port_range', port >= 0 && port <= 0xFF);

    await chip.writePort(0, 0x55);
    checkEq('write_port_shadow', chip._shadow, 0x55);
    await chip.writePort(0, 0x00);

    const full = new Mcp23008Full(connection);
    await full.ready;
    checkEq('full_init_iodir', full._direction, 0xFF);

    // Loopback: GP0-GP3 outputs drive GP4-GP7 inputs.
    await full.writeRegister(0x00, 0xF0);
    checkEq('iodir_readback', await full.readRegister(0x00), 0xF0);
    for (const [label, pattern] of [['0x5', 0x05], ['0xA', 0x0A], ['0x0', 0x00]]) {
        await full.writePort(0, pattern);
        checkEq('loopback_' + label, ((await full.readPort()) >> 4) & 0x0F, pattern);
    }

    await full.configurePolarity(0xF0);
    checkEq('polarity_inverted', ((await full.readPort()) >> 4) & 0x0F, 0x0F);
    await full.configurePolarity(0x00);

    await full.configurePullup(0xF0);
    checkEq('pullup_readback', await full.readRegister(0x06), 0xF0);
    await full.configurePullup(0x00);

    await full.setDefaultValue(0x00);
    await full.enablePinInterrupt(0x10);
    checkEq('gpinten_readback', await full.readRegister(0x02), 0x10);
    await full.writePort(0, 0x01);
    const flags = await full.pollInterrupt();
    checkTrue('poll_interrupt_range', flags >= 0 && flags <= 0xFF);
    await full.enablePinInterrupt(0x00);
    await full.writePort(0, 0x00);

    await full.reset();
    checkEq('reset_iodir', await full.readRegister(0x00), 0xFF);
    checkEq('reset_olat', await full.readRegister(0x0A), 0x00);

    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
