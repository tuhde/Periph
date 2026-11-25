'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { AD5243Minimal, AD5243Full } = require('../../packages/periph/src/chips/adc_dac/ad5243');

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

async function main() {
    // -- construction / init: no I2C traffic ------------------------------
    {
        const conn = new I2CConnectionMock();
        const pot = new AD5243Minimal(conn);
        await pot.init();
        checkEq('init_no_traffic', conn.writes.length, 0);
    }

    // -- setWiper: instruction byte + clamping ----------------------------
    {
        const conn = new I2CConnectionMock();
        const pot = new AD5243Minimal(conn);
        await pot.setWiper(0, 100);
        checkEq('set_wiper_ch0', last(conn), '0,100');
        await pot.setWiper(1, 200);
        checkEq('set_wiper_ch1', last(conn), '128,200');
        await pot.setWiper(0, 300);
        checkEq('set_wiper_clamps_high', last(conn), '0,255');
        await pot.setWiper(0, -5);
        checkEq('set_wiper_clamps_low', last(conn), '0,0');
        let threw = false;
        try { await pot.setWiper(2, 1); } catch (e) { threw = true; }
        checkTrue('invalid_channel_throws', threw);
    }

    // -- readback selects channel through the instruction byte ------------
    {
        const conn = new I2CConnectionMock();
        conn.registers.set(0x00, 17);
        conn.registers.set(0x80, 99);
        const pot = new AD5243Minimal(conn);
        checkEq('get_wiper_ch0', await pot.getWiper(0), 17);
        checkEq('get_wiper_ch1', await pot.getWiper(1), 99);
    }

    // -- resistance conversions (10 kOhm, RW = 160) -----------------------
    {
        const conn = new I2CConnectionMock();
        const pot = new AD5243Minimal(conn);
        await pot.setResistance(0, 5320.0);
        checkEq('set_resistance_mid', last(conn), '0,128');
        await pot.setResistance(0, 0);
        checkEq('set_resistance_clamps_low', last(conn), '0,0');
        await pot.setResistance(1, 1e6);
        checkEq('set_resistance_clamps_high', last(conn), '128,255');
        conn.registers.set(0x00, 128);
        conn.registers.set(0x80, 0);
        checkTrue('get_resistance_mid', Math.abs(await pot.getResistance(0) - 5320.0) < 0.01);
        checkTrue('get_resistance_zero', Math.abs(await pot.getResistance(1) - 320.0) < 0.01);
    }

    // -- Full: both / ratio / W-A -----------------------------------------
    {
        const conn = new I2CConnectionMock();
        const pot = new AD5243Full(conn);
        await pot.setBoth(1, 2);
        checkEq('set_both', conn.writes.map((w) => Array.from(w).join(',')).join(';'), '0,1;128,2');
        await pot.setRatio(0, 0.25);
        checkEq('set_ratio_quarter', last(conn), '0,64');
        await pot.setRatio(0, 1.0);
        checkEq('set_ratio_clamps_255', last(conn), '0,255');
        await pot.setResistanceWa(1, 5320.0);
        checkEq('set_resistance_wa_mid', last(conn), '128,128');
    }
    {
        const conn = new I2CConnectionMock();
        conn.registers.set(0x00, 250);
        conn.registers.set(0x80, 128);
        const pot = new AD5243Full(conn);
        checkEq('get_ratio', await pot.getRatio(1), 0.5);
        checkTrue('get_resistance_wa', Math.abs(await pot.getResistanceWa(1) - 5320.0) < 0.01);
        checkEq('increment_returns', await pot.increment(0, 3), 253);
        checkEq('increment_write', last(conn), '0,253');
        checkEq('increment_clamps_high', await pot.increment(0, 100), 255);
        checkEq('increment_negative', await pot.increment(1, -28), 100);
    }

    // -- shutdown tracking -------------------------------------------------
    {
        const conn = new I2CConnectionMock();
        const pot = new AD5243Full(conn);
        await pot.setWiper(1, 77);
        checkTrue('not_shutdown_initially', !pot.isShutdown(1));
        await pot.shutdown(1);
        checkEq('shutdown_write', last(conn), '192,77');
        checkTrue('is_shutdown', pot.isShutdown(1) && !pot.isShutdown(0));
        await pot.setWiper(1, 50);
        checkEq('set_wiper_keeps_sd', last(conn), '192,50');
        await pot.resume(1);
        checkEq('resume_write', last(conn), '128,50');
        checkTrue('resume_clears', !pot.isShutdown(1));
        await pot.shutdown(0);
        checkEq('shutdown_ch0_midscale', last(conn), '64,128');
        await pot.reset();
        checkEq('reset_writes', conn.writes.slice(-2).map((w) => Array.from(w).join(',')).join(';'), '0,128;128,128');
        checkTrue('reset_clears_shutdown', !pot.isShutdown(0));
    }

    // -- setRab -------------------------------------------------------------
    {
        const conn = new I2CConnectionMock();
        conn.registers.set(0x00, 128);
        const pot = new AD5243Full(conn);
        pot.setRab(50000.0, 100.0);
        checkEq('set_rab_no_traffic', conn.writes.length, 0);
        checkTrue('set_rab_conversion', Math.abs(await pot.getResistance(0) - 25200.0) < 0.01);
        pot.setRab(10000.0);
        checkTrue('set_rab_keeps_rw', Math.abs(await pot.getResistance(0) - 5200.0) < 0.01);
    }

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
