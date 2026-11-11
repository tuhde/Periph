'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { CAT5171Minimal, CAT5171Full } = require('../../packages/periph/src/chips/adc_dac/cat5171');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function checkEq(label, got, expected) {
    if (got === expected) { console.log('PASS', label); passed++; }
    else { console.log(`FAIL ${label}: got ${got}, expected ${expected}`); failed++; }
}

const last = (conn) => Array.from(conn.writes[conn.writes.length - 1]).join(',');

function mockWith(...reads) {
    const conn = new I2CConnectionMock();
    for (const r of reads) conn.queueRead([r]);
    return conn;
}

async function main() {
    // -- construction / init: no I2C traffic ------------------------------
    {
        const conn = new I2CConnectionMock();
        const pot = new CAT5171Minimal(conn);
        await pot.init();
        checkEq('init_no_traffic', conn.writes.length, 0);
    }

    // -- setWiper: instruction byte + clamping ----------------------------
    {
        const conn = new I2CConnectionMock();
        const pot = new CAT5171Minimal(conn);
        await pot.setWiper(100);
        checkEq('set_wiper', last(conn), '0,100');
        await pot.setWiper(300);
        checkEq('set_wiper_clamps_high', last(conn), '0,255');
        await pot.setWiper(-5);
        checkEq('set_wiper_clamps_low', last(conn), '0,0');
    }

    // -- readback is a bare one-byte read ---------------------------------
    {
        const conn = mockWith(17);
        const pot = new CAT5171Minimal(conn);
        checkEq('get_wiper', await pot.getWiper(), 17);
        checkEq('get_wiper_no_write', conn.writes.length, 0);
    }

    // -- resistance conversions (100 kOhm, RW = 50) -----------------------
    {
        const conn = new I2CConnectionMock();
        const pot = new CAT5171Minimal(conn);
        await pot.setResistance(50050.0);
        checkEq('set_resistance_mid', last(conn), '0,128');
        await pot.setResistance(0);
        checkEq('set_resistance_clamps_low', last(conn), '0,0');
        await pot.setResistance(1e7);
        checkEq('set_resistance_clamps_high', last(conn), '0,255');
    }
    {
        const conn = mockWith(128, 0);
        const pot = new CAT5171Minimal(conn);
        checkTrue('get_resistance_mid', Math.abs(await pot.getResistance() - 50050.0) < 0.01);
        checkTrue('get_resistance_zero', Math.abs(await pot.getResistance() - 50.0) < 0.01);
    }

    // -- Full: ratio / W-A ------------------------------------------------
    {
        const conn = new I2CConnectionMock();
        const pot = new CAT5171Full(conn);
        await pot.setRatio(0.25);
        checkEq('set_ratio_quarter', last(conn), '0,64');
        await pot.setRatio(1.0);
        checkEq('set_ratio_clamps_255', last(conn), '0,255');
        await pot.setResistanceWa(50050.0);
        checkEq('set_resistance_wa_mid', last(conn), '0,128');
    }
    {
        const conn = mockWith(128);
        const pot = new CAT5171Full(conn);
        checkEq('get_ratio', await pot.getRatio(), 0.5);
    }
    {
        const conn = mockWith(64);
        const pot = new CAT5171Full(conn);
        checkTrue('get_resistance_wa', Math.abs(await pot.getResistanceWa() - 75050.0) < 0.01);
    }
    {
        const conn = mockWith(250, 250, 128);
        const pot = new CAT5171Full(conn);
        checkEq('increment_returns', await pot.increment(3), 253);
        checkEq('increment_write', last(conn), '0,253');
        checkEq('increment_clamps_high', await pot.increment(100), 255);
        checkEq('increment_negative', await pot.increment(-28), 100);
        checkEq('increment_negative_write', last(conn), '0,100');
    }

    // -- shutdown / midscale / reset tracking -----------------------------
    {
        const conn = new I2CConnectionMock();
        const pot = new CAT5171Full(conn);
        checkTrue('not_shutdown_initially', !pot.isShutdown());
        await pot.shutdown();
        checkEq('shutdown_midscale', last(conn), '32,128');
        checkTrue('is_shutdown', pot.isShutdown());
        await pot.resume();
        checkEq('resume_write', last(conn), '0,128');
        checkTrue('resume_clears', !pot.isShutdown());
        await pot.setWiper(77);
        await pot.shutdown();
        checkEq('shutdown_write', last(conn), '32,77');
        await pot.setWiper(50);
        checkEq('set_wiper_keeps_sd', last(conn), '32,50');
        await pot.resume();
        checkEq('resume_tracked', last(conn), '0,50');
        await pot.midscale();
        checkEq('midscale_write', last(conn), '64,128');
        await pot.shutdown();
        await pot.midscale();
        checkEq('midscale_keeps_sd', last(conn), '96,128');
        checkTrue('midscale_still_shutdown', pot.isShutdown());
        await pot.reset();
        checkEq('reset_write', last(conn), '64,128');
        checkTrue('reset_clears_shutdown', !pot.isShutdown());
    }

    // -- setRab -------------------------------------------------------------
    {
        const conn = mockWith(128, 128);
        const pot = new CAT5171Full(conn);
        pot.setRab(50000.0, 100.0);
        checkEq('set_rab_no_traffic', conn.writes.length, 0);
        checkTrue('set_rab_conversion', Math.abs(await pot.getResistance() - 25100.0) < 0.01);
        pot.setRab(50000.0);
        checkTrue('set_rab_keeps_rw', Math.abs(await pot.getResistance() - 25100.0) < 0.01);
    }

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
