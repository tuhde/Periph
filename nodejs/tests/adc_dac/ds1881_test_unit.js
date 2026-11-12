'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { DS1881Minimal, DS1881Full } = require('../../packages/periph/src/chips/adc_dac/ds1881');

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
const mockWith = (...reads) => {
    const conn = new I2CConnectionMock();
    for (const r of reads) conn.queueRead(r);
    return conn;
};

async function rejects(fn) {
    try { await fn(); } catch (e) { return true; }
    return false;
}

async function main() {
    // Construction makes no traffic; init writes the config only when it differs.
    {
        const conn = new I2CConnectionMock();
        const pot = new DS1881Minimal(conn);
        checkEq('ctor_no_traffic', conn.writes.length, 0);
        await pot.init();
        checkEq('init_writes_config', conn.writes.map((w) => Array.from(w).join(',')).join('|'), '134');
    }
    {
        const conn = mockWith([10, 20, 0x86]);
        await new DS1881Minimal(conn).init();
        checkEq('init_matching_sends_nothing', conn.writes.length, 0);
    }
    {
        const conn = mockWith([0, 0, 0xB6]);
        await new DS1881Minimal(conn).init();
        checkEq('init_ignores_upper_bits', conn.writes.length, 0);
    }

    // Attenuation: command byte, rounding, clamping.
    {
        const conn = new I2CConnectionMock();
        const pot = new DS1881Minimal(conn);
        await pot.setAttenuation(0, 20);
        checkEq('set_attenuation_ch0', last(conn), '20');
        await pot.setAttenuation(1, 20.4);
        checkEq('set_attenuation_rounds_down', last(conn), String(0x40 | 20));
        await pot.setAttenuation(1, 20.6);
        checkEq('set_attenuation_rounds_up', last(conn), String(0x40 | 21));
        await pot.setAttenuation(0, 100);
        checkEq('set_attenuation_clamps_62', last(conn), '62');
        await pot.setAttenuation(0, -5);
        checkEq('set_attenuation_clamps_0', last(conn), '0');
        checkTrue('bad_channel', await rejects(() => pot.setAttenuation(2, 0)));
        checkTrue('bad_channel_get', await rejects(() => pot.getPosition(-1)));
        await pot.mute(1);
        checkEq('mute', last(conn), '127');
        await pot.setPosition(0, 99);
        checkEq('set_position_clamps', last(conn), '63');
        await pot.setPosition(0, -3);
        checkEq('set_position_clamps_low', last(conn), '0');
    }

    // Reads: three-byte round robin, upper bits masked.
    {
        const conn = mockWith(...Array(4).fill([0xC5, 0x3F, 0x86]));
        const pot = new DS1881Minimal(conn);
        checkEq('get_position_ch0', await pot.getPosition(0), 5);
        checkEq('get_position_ch1', await pot.getPosition(1), 63);
        checkEq('get_attenuation_mute', await pot.getAttenuation(1), 80);
        checkEq('get_attenuation_value', await pot.getAttenuation(0), 5);
        checkEq('reads_no_writes', conn.writes.length, 0);
    }

    // Full: stereo write in one transaction.
    {
        const conn = new I2CConnectionMock();
        const full = new DS1881Full(conn);
        await full.setBoth(10, 99);
        checkEq('set_both_one_transaction', conn.writes.length + ':' + last(conn), '1:10,127');
        await full.setAttenuationBoth(30);
        checkEq('set_attenuation_both_default', last(conn), '30,94');
        await full.setAttenuationBoth(5, 70);
        checkEq('set_attenuation_both_mute', last(conn), '5,127');
        checkTrue('opt1_db', full.positionToDb(0) === 0 && full.positionToDb(62) === 62 && full.positionToDb(63) === 80);
        checkEq('max_position_opt1', full.maxPosition(), 62);
        checkTrue('db_to_position_opt1', full.dbToPosition(12.4) === 12 && full.dbToPosition(75) === 63);
    }

    // configure: Option 2.
    {
        const conn = new I2CConnectionMock();
        const full = new DS1881Full(conn);
        await full.configure(2, true, false);
        checkEq('configure_option2', last(conn), '135');
        const n = conn.writes.length;
        await full.configure(2, true, false);
        checkEq('configure_unchanged_no_write', conn.writes.length, n);
        checkEq('opt2_table', [0, 12, 13, 24, 25, 32, 33].map((p) => full.positionToDb(p)).join(','), '0,12,14,36,39,60,80');
        checkEq('max_position_opt2', full.maxPosition(), 32);
        checkTrue('db_to_position_opt2', full.dbToPosition(14) === 13 && full.dbToPosition(36) === 24 && full.dbToPosition(60) === 32);
        checkTrue('db_to_position_opt2_nearest', full.dbToPosition(37) === 24 && full.dbToPosition(38) === 25);
        checkEq('db_to_position_opt2_mute', full.dbToPosition(70), 33);
        await full.setBoth(40, 40);
        checkEq('opt2_positions_clamped', last(conn), '33,97');
        await full.mute(0);
        checkEq('opt2_mute', last(conn), '33');
        checkTrue('bad_option', await rejects(() => full.configure(3)));
    }

    // setZeroCrossing / setNonvolatile / setOption keep the other bits.
    {
        const conn = new I2CConnectionMock();
        const full = new DS1881Full(conn);
        await full.setZeroCrossing(false);
        checkEq('zc_off', last(conn), '132');
        await full.setNonvolatile(true);
        checkEq('nv_on', last(conn), '128');
        await full.setOption(2);
        checkEq('option2_keeps_bits', last(conn), '129');
        conn.failNextReads(1);
        await full.setBoth(1, 2);
        checkEq('nv_write_polls', last(conn), '1,66');
    }

    // getConfig / readAll.
    {
        const conn = mockWith([0, 0, 0x87], [1, 2, 0x82], [0x41, 0x02, 0x86]);
        const full = new DS1881Full(conn);
        const cfg = await full.getConfig();
        checkTrue('get_config', cfg.option === 2 && cfg.zeroCrossing && !cfg.nonvolatile && cfg.raw === 0x87);
        checkEq('get_config_updates_cache', full.maxPosition(), 32);
        const cfg2 = await full.getConfig();
        checkTrue('get_config_nv', cfg2.nonvolatile && cfg2.option === 1 && cfg2.zeroCrossing);
        checkEq('read_all', (await full.readAll()).join(','), '1,2,134');
    }

    // waitReady.
    {
        const conn = new I2CConnectionMock();
        const full = new DS1881Full(conn);
        conn.failNextReads(3);
        await full.waitReady(0.05);
        checkTrue('wait_ready_recovers', true);
        conn.failNextReads(1000);
        checkTrue('wait_ready_times_out', await rejects(() => full.waitReady(0.01)));
    }

    // store.
    {
        const conn = mockWith([12, 34, 0x86]);
        const full = new DS1881Full(conn);
        await full.store();
        checkEq('store_sequence', conn.writes.map((w) => Array.from(w).join(',')).join('|'), '130|12,98|134');
    }

    // reset.
    {
        const conn = new I2CConnectionMock();
        const full = new DS1881Full(conn);
        await full.configure(2, false, true);
        await full.reset();
        checkEq('reset', last(conn), '134,63,127');
        checkEq('reset_restores_option1', full.maxPosition(), 62);
    }

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
