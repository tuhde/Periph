'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { Pca9536Minimal, Pca9536Full } = require('../../packages/periph/src/chips/io_expander/pca9536');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',    10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x41', 16);

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

// Wiring for the loopback checks: jumper P0-P1 to P2-P3 (Pn <-> Pn+2).
async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const chip       = new Pca9536Minimal(connection);
    await chip.ready;

    checkEq('init_config', chip._direction, 0xFF);
    checkEq('init_shadow', chip._shadow, 0xFF);

    const port = await chip.readPort();
    checkTrue('read_port_range', port >= 0 && port <= 0x0F);

    await chip.writePort(0, 0x05);
    checkEq('write_port_shadow', chip._shadow & 0x0F, 0x05);
    await chip.writePort(0, 0x0F);

    const full = new Pca9536Full(connection);
    await full.ready;
    checkEq('full_init_config', full._direction, 0xFF);

    // Loopback: P0-P1 outputs drive P2-P3 inputs.
    await full.setDirection(0x0C);
    checkEq('config_readback', (await full.readRegister(0x03)) & 0x0F, 0x0C);
    for (const [label, pattern] of [['0x1', 0x01], ['0x2', 0x02], ['0x0', 0x00]]) {
        await full.writePort(0, pattern);
        checkEq('loopback_' + label, ((await full.readPort()) >> 2) & 0x03, pattern);
    }

    await full.configurePolarity(0x0C);
    checkEq('polarity_readback', (await full.readRegister(0x02)) & 0x0F, 0x0C);
    checkEq('polarity_inverted', ((await full.readPort()) >> 2) & 0x03, 0x03);
    await full.configurePolarity(0x00);

    await full.pollChanges();
    await full.writePort(0, 0x01);
    checkEq('poll_changes_changed', await full.pollChanges(), 0x04);
    await full.writePort(0, 0x00);

    await full.reset();
    checkEq('reset_config', (await full.readRegister(0x03)) & 0x0F, 0x0F);
    checkEq('reset_output', (await full.readRegister(0x01)) & 0x0F, 0x0F);

    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
