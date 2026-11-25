'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { Pca6408aMinimal, Pca6408aFull } = require('../../packages/periph/src/chips/io_expander/pca6408a');

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

// Wiring for the loopback checks: jumper P0-P3 to P4-P7 (Pn <-> Pn+4).
async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const chip       = new Pca6408aMinimal(connection);
    await chip.ready;

    checkEq('init_config', chip._direction, 0xFF);
    checkEq('init_shadow', chip._shadow, 0xFF);

    const port = await chip.readPort();
    checkTrue('read_port_range', port >= 0 && port <= 0xFF);

    await chip.writePort(0, 0x55);
    checkEq('write_port_shadow', chip._shadow, 0x55);
    await chip.writePort(0, 0xFF);

    const full = new Pca6408aFull(connection);
    await full.ready;
    checkEq('full_init_config', full._direction, 0xFF);

    // Loopback: P0-P3 outputs drive P4-P7 inputs.
    await full.setDirection(0xF0);
    checkEq('config_readback', await full.readRegister(0x03), 0xF0);
    for (const [label, pattern] of [['0x5', 0x05], ['0xA', 0x0A], ['0x0', 0x00]]) {
        await full.writePort(0, pattern);
        checkEq('loopback_' + label, ((await full.readPort()) >> 4) & 0x0F, pattern);
    }

    await full.configurePolarity(0xF0);
    checkEq('polarity_readback', await full.readRegister(0x02), 0xF0);
    checkEq('polarity_inverted', ((await full.readPort()) >> 4) & 0x0F, 0x0F);
    await full.configurePolarity(0x00);

    await full.pollInterrupt();
    await full.writePort(0, 0x01);
    checkEq('poll_interrupt_changed', await full.pollInterrupt(), 0x10);
    await full.writePort(0, 0x00);

    await full.reset();
    checkEq('reset_config', await full.readRegister(0x03), 0xFF);
    checkEq('reset_output', await full.readRegister(0x01), 0xFF);

    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
