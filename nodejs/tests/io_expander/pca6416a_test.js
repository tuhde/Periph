'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { Pca6416aMinimal, Pca6416aFull } = require('../../packages/periph/src/chips/io_expander/pca6416a');

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

// Wiring for the loopback checks: jumper port 0 to port 1 (P0_n <-> P1_n).
async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const chip       = new Pca6416aMinimal(connection);
    await chip.ready;

    checkEq('init_config', chip._direction, 0xFFFF);
    checkEq('init_shadow', chip._shadow, 0xFFFF);

    checkTrue('read_port_0_range', (await chip.readPort(0)) <= 0xFF);
    checkTrue('read_port_1_range', (await chip.readPort(1)) <= 0xFF);

    await chip.writePort(0, 0x55);
    await chip.writePort(1, 0xAA);
    checkEq('write_port_shadow', chip._shadow, 0xAA55);
    await chip.writePort(0, 0xFF);
    await chip.writePort(1, 0xFF);

    const full = new Pca6416aFull(connection);
    await full.ready;
    checkEq('full_init_config', full._direction, 0xFFFF);

    // Loopback: port 0 outputs drive port 1 inputs.
    await full.setDirection(0xFF00);
    checkEq('config0_readback', await full.readRegister(0x06), 0x00);
    checkEq('config1_readback', await full.readRegister(0x07), 0xFF);
    for (const [label, pattern] of [['0x5A', 0x5A], ['0xA5', 0xA5], ['0x00', 0x00]]) {
        await full.writePort(0, pattern);
        checkEq('loopback_' + label, await full.readPort(1), pattern);
    }
    await full.writeAll(0x00FF);
    checkEq('read_all_pair', await full.readAll(), 0xFFFF);
    await full.writeAll(0x0000);

    await full.configurePolarity(0xFF00);
    checkEq('polarity1_readback', await full.readRegister(0x05), 0xFF);
    checkEq('polarity_inverted', await full.readPort(1), 0xFF);
    await full.configurePolarity(0x0000);

    await full.pollInterrupt();
    await full.writePort(0, 0x01);
    checkEq('poll_interrupt_changed', await full.pollInterrupt(), 0x0100);
    await full.writePort(0, 0x00);

    await full.reset();
    checkEq('reset_config0', await full.readRegister(0x06), 0xFF);
    checkEq('reset_config1', await full.readRegister(0x07), 0xFF);
    checkEq('reset_output0', await full.readRegister(0x02), 0xFF);
    checkEq('reset_output1', await full.readRegister(0x03), 0xFF);

    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
