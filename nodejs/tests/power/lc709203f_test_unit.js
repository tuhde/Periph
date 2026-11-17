'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { LC709203FFull, LC709203FMinimal, crc8Atm } = require('../../packages/periph/src/chips/power/lc709203f');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

// Each command code holds one (low, high, crc) word; writes do not overlap neighbours.
class WordMock extends I2CConnectionMock {
    constructor() { super(); this.words = new Map(); }
    put(cmd, value) {
        const lo = value & 0xFF, hi = value >> 8;
        this.words.set(cmd, Buffer.from([lo, hi, crc8Atm([0x16, cmd, 0x17, lo, hi])]));
    }
    async write(data) {
        const buf = Buffer.from(data);
        this.writes.push(buf);
        if (buf.length === 4) this.words.set(buf[0], buf.subarray(1, 4));
    }
    async readReg(reg, length) {
        const w = this.words.get(reg) || Buffer.alloc(length);
        return Buffer.from(w);
    }
}

const last = (c) => c.writes[c.writes.length - 1];
const lastIs = (c, cmd, lo, hi) => {
    const w = last(c);
    return w.length === 4 && w[0] === cmd && w[1] === lo && w[2] === hi && w[3] === crc8Atm([0x16, cmd, lo, hi]);
};
const wrote = (c, cmd, lo, hi) => c.writes.some((w) => w.length === 4 && w[0] === cmd && w[1] === lo && w[2] === hi);

async function main() {
    checkTrue('crc_read', crc8Atm([0x16, 0x09, 0x17, 0xC2, 0x0E]) === 0x86);
    checkTrue('crc_write', crc8Atm([0x16, 0x09, 0x55, 0xAA]) === 0x3B);

    const conn = new WordMock();
    conn.put(0x13, 8);
    conn.put(0x14, 0);
    const sensor = new LC709203FFull(conn);
    await sensor.ready;

    checkTrue('init_power_mode', wrote(conn, 0x15, 0x01, 0x00));
    checkTrue('init_apa', wrote(conn, 0x0B, 0x19, 0x00));
    checkTrue('init_profile', wrote(conn, 0x12, 0x01, 0x00));
    checkTrue('init_status_i2c', wrote(conn, 0x16, 0x00, 0x00));
    checkTrue('init_temp_25C', wrote(conn, 0x08, 0xA6, 0x0B));

    conn.put(0x09, 3778);
    checkTrue('voltage', Math.abs((await sensor.voltage()) - 3.778) < 1e-9);
    conn.put(0x0F, 755);
    checkTrue('percent', Math.abs((await sensor.percent()) - 75.5) < 1e-9);
    conn.put(0x08, 0x0BA6);
    checkTrue('temperature', Math.abs((await sensor.temperature()) - 25.0) < 0.06);

    conn.words.set(0x09, Buffer.from([0xC2, 0x0E, 0x00]));
    let threw = false;
    try { await sensor.voltage(); } catch (e) { threw = true; }
    checkTrue('crc_mismatch_throws', threw);

    await sensor.setTemperature(100);
    checkTrue('set_temperature_clamp_high', lastIs(conn, 0x08, 0x04, 0x0D));
    await sensor.setTemperature(-50);
    checkTrue('set_temperature_clamp_low', lastIs(conn, 0x08, 0xE4, 0x09));

    conn.put(0x0D, 42);
    checkTrue('rsoc', (await sensor.rsoc()) === 42);
    await sensor.setRsoc(55);
    checkTrue('set_rsoc', lastIs(conn, 0x0D, 55, 0));
    await sensor.initRsoc();
    checkTrue('init_rsoc', lastIs(conn, 0x07, 0x55, 0xAA));
    await sensor.beforeRsoc();
    checkTrue('before_rsoc', lastIs(conn, 0x04, 0x55, 0xAA));

    await sensor.useThermistor(3380);
    checkTrue('use_thermistor', wrote(conn, 0x06, 0x34, 0x0D) && lastIs(conn, 0x16, 1, 0));
    conn.put(0x16, 1);
    checkTrue('is_thermistor_mode', (await sensor.isThermistorMode()) === true);
    await sensor.useI2cTemperature(25);
    checkTrue('use_i2c_temperature', lastIs(conn, 0x08, 0xA6, 0x0B));

    await sensor.setCurrentDirection(LC709203FFull.DIRECTION_DISCHARGE);
    checkTrue('direction_discharge', lastIs(conn, 0x0A, 0xFF, 0xFF));
    threw = false;
    try { await sensor.setCurrentDirection(5); } catch (e) { threw = true; }
    checkTrue('direction_invalid', threw);

    await sensor.setApa(0x2D);
    checkTrue('set_apa', lastIs(conn, 0x0B, 0x2D, 0));
    await sensor.setApt(0x1234);
    checkTrue('set_apt', lastIs(conn, 0x0C, 0x34, 0x12));
    await sensor.setProfile(0);
    checkTrue('set_profile', wrote(conn, 0x12, 0, 0) && lastIs(conn, 0x15, 1, 0));

    conn.put(0x1A, 0x0301);
    checkTrue('profile_id', (await sensor.profileId()) === 0x0301);
    conn.put(0x11, 0x1234);
    checkTrue('ic_version', (await sensor.icVersion()) === 0x1234);

    await sensor.sleep();
    checkTrue('sleep', lastIs(conn, 0x15, 2, 0));
    conn.put(0x15, 2);
    checkTrue('is_sleeping', (await sensor.isSleeping()) === true);

    await sensor.setAlarmRsoc(20);
    await sensor.setAlarmVoltage(3.5);
    checkTrue('set_alarm_voltage', lastIs(conn, 0x14, 0xAC, 0x0D));
    conn.put(0x0D, 10); conn.put(0x09, 3400);
    checkTrue('poll_both', (await sensor.pollInterrupt()) === 3);
    conn.put(0x0D, 50); conn.put(0x09, 3900);
    checkTrue('poll_none', (await sensor.pollInterrupt()) === 0);

    // APA interpolation: 700 mAh -> 0x14; clamped at both ends
    const c2 = new WordMock();
    c2.put(0x13, 8); c2.put(0x14, 0);
    const s2 = new LC709203FMinimal(c2, 700, 1);
    await s2.ready;
    checkTrue('apa_interp', wrote(c2, 0x0B, 0x14, 0x00));
    const c3 = new WordMock();
    const s3 = new LC709203FMinimal(c3, 9000, 1);
    await s3.ready;
    checkTrue('apa_clamp', wrote(c3, 0x0B, 0x36, 0x00));

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
