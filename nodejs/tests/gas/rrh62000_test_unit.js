'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { RRH62000Full, RRH62000Error, crc8 } = require('../../packages/periph/src/chips/gas/rrh62000');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const near = (a, b, tol = 1e-9) => Math.abs(a - b) < tol;

async function rejects(fn, cls = Error) {
    try { await fn(); } catch (e) { return e instanceof cls ? e : false; }
    return false;
}

const WORDS = [1234, 1000, 500, 100, 20, 52, 125, 203, 61, 140, 230, 2345, 4567, 30, 812, 215, 0];

// 37-byte block: status little-endian, 17 big-endian words, CRC-8.
function makeBlock(status = 0, words = WORDS) {
    const b = [status & 0xFF, status >> 8];
    for (const w of words) b.push((w >> 8) & 0xFF, w & 0xFF);
    b.push(crc8(b));
    return b;
}

const lastWrite = (c) => [...c.writes[c.writes.length - 1]];
const eq = (a, b) => a.length === b.length && a.every((x, i) => x === b[i]);

(async () => {
    checkTrue('crc8_beef', crc8([0xBE, 0xEF]) === 0x92);
    checkTrue('crc8_zero', crc8([0x00, 0x00]) === 0x81);

    const c = new I2CConnectionMock();
    c.setRegister(0x75, [2, 7]);
    const s = new RRH62000Full(c, { accessIntervalMs: 0 });
    await s.init();
    checkTrue('init_reads_fwver', eq(lastWrite(c), [0x75]));

    c.setRegister(0x40, [0x01]);
    c.setRegister(0x00, makeBlock(0x0005));
    const r = await s.read();
    checkTrue('read_keys', Object.keys(r).sort().join() ===
        ['eco2_ppm', 'humidity_rh', 'iaq', 'pm10_ug_m3', 'pm1_ug_m3', 'pm2_5_ug_m3', 'temperature_c', 'tvoc'].join());
    checkTrue('read_pm', r.pm1_ug_m3 === 5.2 && r.pm2_5_ug_m3 === 12.5 && r.pm10_ug_m3 === 20.3);
    checkTrue('read_t_rh', near(r.temperature_c, 23.45) && near(r.humidity_rh, 45.67));
    checkTrue('read_tvoc_eco2_iaq', r.tvoc === 300 && r.eco2_ppm === 812 && near(r.iaq, 2.15));
    const n = c.writes.length;
    checkTrue('read_polls_data_then_block', eq([...c.writes[n - 2]], [0x40]) && eq([...c.writes[n - 1]], [0x00]));

    const a = await s.readAll();
    checkTrue('read_all_status', a.status === 5 && a.high_concentration && a.fan_speed && !a.dust_accumulation && !a.fan);
    checkTrue('read_all_nc', a.nc_0_3 === 123.4 && a.nc_4 === 2);
    checkTrue('read_all_smoke', a.pm1_smoke_ug_m3 === 6.1 && a.pm10_smoke_ug_m3 === 23);

    const neg = new Array(17).fill(0);
    neg[11] = (-512) & 0xFFFF;
    c.setRegister(0x00, makeBlock(0x0002, neg));
    c.writes = [];
    const st = await s.readStatus();
    checkTrue('read_status', st.raw === 2 && st.dust_accumulation && !st.high_concentration && !st.fan_speed && !st.fan);
    checkTrue('read_status_no_data_poll', c.writes.length === 1);
    checkTrue('negative_temperature', (await s.readAll()).temperature_c === -5.12);
    c.setRegister(0x00, makeBlock());
    const p = await s.readParticles();
    checkTrue('read_particles', Object.keys(p).length === 11 && p.pm2_5_smoke_ug_m3 === 14 && p.nc_1 === 50);

    const bad = makeBlock();
    bad[36] ^= 0xFF;
    c.setRegister(0x00, bad);
    checkTrue('crc_error_rejects', Boolean(await rejects(() => s.read(), RRH62000Error)));
    c.setRegister(0x00, new Array(37).fill(0xFF));
    const e = await rejects(() => s.read(), RRH62000Error);
    checkTrue('startup_block_rejects', Boolean(e) && /start-up/.test(e.message));
    c.setRegister(0x40, [0x00]);
    c.setRegister(0x00, makeBlock());
    s._dataTimeoutMs = 0;
    checkTrue('data_timeout_rejects', Boolean(await rejects(() => s.read(), RRH62000Error)));
    checkTrue('data_ready_false', (await s.dataReady()) === false);
    c.setRegister(0x40, [0x01]);
    checkTrue('data_ready_true', (await s.dataReady()) === true);

    await s.sleep();
    checkTrue('sleep', eq(lastWrite(c), [0x50, 0x00]));
    await s.wake();
    checkTrue('wake', eq(lastWrite(c), [0x50, 0x80]));
    await s.startCleaning();
    checkTrue('start_cleaning', eq(lastWrite(c), [0x51, 0x01]));
    c.setRegister(0x74, [0x01]);
    checkTrue('cleaning_done', (await s.cleaningDone()) === true);
    await s.softReset();
    checkTrue('soft_reset', eq(lastWrite(c), [0x52, 0x81]));
    c.failNextWrites(1);
    await s.softReset();
    checkTrue('soft_reset_nack_ignored', true);

    await s.setMovingAverage(30);
    checkTrue('moving_average', (await s.getMovingAverage()) === 30);
    await s.setFanSpeed(70);
    checkTrue('fan_speed', (await s.getFanSpeed()) === 70);
    await s.setCleaningInterval(86400);
    checkTrue('cleaning_interval_bytes', c.registers.get(0x5A) === 0x0B && c.registers.get(0x5B) === 0x40);
    checkTrue('cleaning_interval', (await s.getCleaningInterval()) === 86400);
    await s.setCleaningTime(20);
    checkTrue('cleaning_time', (await s.getCleaningTime()) === 20);
    for (const [label, fn] of [['moving_average_range', () => s.setMovingAverage(0)],
        ['moving_average_range_hi', () => s.setMovingAverage(61)],
        ['fan_speed_range', () => s.setFanSpeed(59)], ['fan_speed_range_hi', () => s.setFanSpeed(101)],
        ['cleaning_interval_multiple', () => s.setCleaningInterval(31)],
        ['cleaning_interval_range', () => s.setCleaningInterval(1814430)],
        ['cleaning_time_range', () => s.setCleaningTime(61)]]) {
        checkTrue(label, Boolean(await rejects(fn, RangeError)));
    }

    c.setRegister(0x71, [0x00, 0x01, 0xE2, 0x40]);
    checkTrue('mox_resistance', (await s.readMoxResistance()) === 123456);
    c.setRegister(0x72, [1, 2, 3, 4, 5, 6]);
    checkTrue('unique_id', eq([...(await s.uniqueId())], [1, 2, 3, 4, 5, 6]));
    c.setRegister(0x73, [3, 1, 4]);
    checkTrue('algorithm_version', eq(await s.algorithmVersion(), [3, 1, 4]));
    c.setRegister(0x75, [2, 7]);
    checkTrue('firmware_version', eq(await s.firmwareVersion(), [2, 7]));

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
})();
