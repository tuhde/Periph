'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { AD5251Minimal, AD5251Full } = require('../../packages/periph/src/chips/adc_dac/ad5251');

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

const wr = (conn) => conn.writes.map((w) => Array.from(w).join(',')).join(';');

async function main() {
    // -- construction / init: no I2C traffic ------------------------------
    {
        const conn = new I2CConnectionMock();
        const pot = new AD5251Minimal(conn);
        await pot.init();
        checkEq('init_no_traffic', conn.writes.length, 0);
    }

    // -- setWiper / getWiper / resistance -----------------------------------
    {
        const conn = new I2CConnectionMock();
        const pot = new AD5251Minimal(conn);
        await pot.setWiper(0, 40);
        checkEq('set_wiper_ch0', last(conn), '1,40');
        await pot.setWiper(1, 60);
        checkEq('set_wiper_ch1', last(conn), '3,60');
        await pot.setWiper(0, 300);
        checkEq('set_wiper_clamps_high', last(conn), '1,63');
        await pot.setWiper(0, -5);
        checkEq('set_wiper_clamps_low', last(conn), '1,0');
        let threw = false;
        try { await pot.setWiper(2, 1); } catch (e) { threw = true; }
        checkTrue('invalid_channel_throws', threw);
        await pot.setResistance(0, 25075.0);
        checkEq('set_resistance_mid', last(conn), '1,32');
        await pot.setResistance(0, 0.0);
        checkEq('set_resistance_clamps_low', last(conn), '1,0');
        await pot.setResistance(1, 1e6);
        checkEq('set_resistance_clamps_high', last(conn), '3,63');
    }
    {
        const conn = new I2CConnectionMock();
        conn.registers.set(0x01, 17);
        conn.registers.set(0x03, 0);
        const pot = new AD5251Minimal(conn);
        checkEq('get_wiper_ch0', await pot.getWiper(0), 17);
        checkEq('get_wiper_pointer_write', wr(conn), '1');
        checkTrue('get_resistance_zero', Math.abs(await pot.getResistance(1) - 75.0) < 0.01);
        conn.registers.set(0x01, 32);
        checkTrue('get_resistance_mid', Math.abs(await pot.getResistance(0) - 25075.0) < 0.01);
    }

    // -- Full: both / ratio / W-A -------------------------------------------
    {
        const conn = new I2CConnectionMock();
        const pot = new AD5251Full(conn);
        await pot.setBoth(1, 2);
        checkEq('set_both_one_transaction', wr(conn), '1,1,2');
        await pot.setRatio(0, 0.25);
        checkEq('set_ratio_quarter', last(conn), '1,16');
        await pot.setRatio(0, 1.0);
        checkEq('set_ratio_clamps_255', last(conn), '1,63');
        await pot.setResistanceWa(1, 25075.0);
        checkEq('set_resistance_wa_mid', last(conn), '3,32');
        conn.registers.set(0x03, 32);
        checkEq('get_ratio', await pot.getRatio(1), 0.5);
        checkTrue('get_resistance_wa', Math.abs(await pot.getResistanceWa(1) - 25075.0) < 0.01);
    }

    // -- quick commands ------------------------------------------------------
    {
        const conn = new I2CConnectionMock();
        const pot = new AD5251Full(conn);
        await pot.increment(0); await pot.increment(1); await pot.increment();
        await pot.decrement(0); await pot.decrement(1); await pot.decrement();
        checkEq('increment_decrement_bytes', wr(conn), '209;211;216;169;171;176');
        conn.writes.length = 0;
        await pot.double(0); await pot.double(1); await pot.double();
        await pot.halve(0); await pot.halve(1); await pot.halve();
        checkEq('double_halve_bytes', wr(conn), '193;195;200;153;155;160');
        conn.writes.length = 0;
        await pot.nop();
        checkEq('nop', wr(conn), '128');
        let threw = false;
        try { await pot.increment(2); } catch (e) { threw = true; }
        checkTrue('quick_invalid_channel_throws', threw);
    }

    // -- store / restore / reset with ACK polling ----------------------------
    {
        const conn = new I2CConnectionMock();
        const pot = new AD5251Full(conn);
        await pot.store(1);
        checkEq('store_ch1', wr(conn), '147;128');
        conn.writes.length = 0;
        conn.failNextWrites(2, 1);
        await pot.store(0);
        checkEq('store_polls_until_ack', wr(conn), '145;128;128;128');
        conn.writes.length = 0;
        await pot.store();
        checkEq('store_both', wr(conn), '145;128;147;128');
        conn.writes.length = 0;
        await pot.restore(0);
        checkEq('restore_ch0', wr(conn), '137;128');
        conn.writes.length = 0;
        await pot.restore();
        checkEq('restore_both', wr(conn), '137;139;128');
        conn.writes.length = 0;
        await pot.reset();
        checkEq('reset', wr(conn), '184;128');
        conn.failNextWrites(1000, 1);
        let threw = false;
        try { await pot.store(0); } catch (e) { threw = true; }
        checkTrue('store_timeout_throws', threw);
    }

    // -- user EEMEM ----------------------------------------------------------
    {
        const conn = new I2CConnectionMock();
        conn.registers.set(0x24, 0x5A);
        conn.registers.set(0x2F, 0xA5);
        const pot = new AD5251Full(conn);
        checkEq('read_eemem_4', await pot.readEemem(4), 0x5A);
        checkEq('read_eemem_15', await pot.readEemem(15), 0xA5);
        conn.writes.length = 0;
        await pot.writeEemem(9, 0x42);
        checkEq('write_eemem', wr(conn), '41,66;128');
        let threw = false;
        try { await pot.readEemem(3); } catch (e) { threw = true; }
        checkTrue('read_eemem_range', threw);
        threw = false;
        try { await pot.writeEemem(16, 0); } catch (e) { threw = true; }
        checkTrue('write_eemem_range', threw);
    }

    // -- tolerance -------------------------------------------------------------
    {
        const conn = new I2CConnectionMock();
        conn.registers.set(0x3A, 0x1C);
        conn.registers.set(0x3B, 0x0F);
        conn.registers.set(0x3E, 0x82);
        conn.registers.set(0x3F, 0x80);
        const pot = new AD5251Full(conn);
        checkTrue('tolerance_positive', Math.abs(await pot.readTolerance(0) - 28.05859375) < 1e-6);
        checkTrue('tolerance_negative', Math.abs(await pot.readTolerance(1) + 2.5) < 1e-9);
        checkTrue('rab_actual', Math.abs(await pot.rabActual(0) - 64029.296875) < 0.01);
    }

    // -- setRab -------------------------------------------------------------
    {
        const conn = new I2CConnectionMock();
        conn.registers.set(0x01, 32);
        const pot = new AD5251Full(conn);
        pot.setRab(50000.0, 100.0);
        checkEq('set_rab_no_traffic', conn.writes.length, 0);
        checkTrue('set_rab_conversion', Math.abs(await pot.getResistance(0) - 25100.0) < 0.01);
        pot.setRab(10000.0);
        checkTrue('set_rab_keeps_rw', Math.abs(await pot.getResistance(0) - 5100.0) < 0.01);
    }

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
