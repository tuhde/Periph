'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { RDA5807MFull } = require('../../packages/periph/src/chips/comms/rda5807m');

// Register bit constants, mirrored from
// nodejs/packages/periph/src/chips/comms/rda5807m.js (module-private there,
// so re-declared here to build expected values).
const BAND_BASE_KHZ = [87000, 76000, 76000, 65000];
const SPACE_KHZ = [100, 200, 50, 25];

const BAND_WORLD = 2;
const SPACE_100K = 0;
const BAND_US_EUROPE = 0;
const SPACE_50K = 2;

const DHIZ = 0x8000;
const DMUTE = 0x4000;
const MONO = 0x2000;
const BASS = 0x1000;
const SEEKUP = 0x0200;
const SEEK = 0x0100;
const SKMODE = 0x0080;
const RDS_EN = 0x0008;
const NEW_METHOD = 0x0004;
const SOFT_RESET = 0x0002;
const ENABLE = 0x0001;
const TUNE = 0x0010;
const DE = 0x0800;
const SOFTMUTE_EN = 0x0200;
const AFCD = 0x0100;
const INT_MODE = 0x8000;
const BAND_65M_50M = 0x0200;
const RDSR = 0x8000;
const STC = 0x4000;
const SF = 0x2000;
const ST = 0x0400;
const FM_TRUE = 0x0100;
const FM_READY = 0x0080;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function flushMicrotasks() {
    return new Promise((resolve) => setImmediate(resolve));
}

function freqToChan(band, space, east50, freqMhz) {
    const base = (band === 3 && east50) ? 50000 : BAND_BASE_KHZ[band];
    const freqKhz = Math.round(freqMhz * 1000);
    let chan = Math.round((freqKhz - base) / SPACE_KHZ[space]);
    if (chan < 0) chan = 0;
    if (chan > 1023) chan = 1023;
    return chan;
}

function chanToFreq(band, space, east50, chan) {
    const base = (band === 3 && east50) ? 50000 : BAND_BASE_KHZ[band];
    return (base + chan * SPACE_KHZ[space]) / 1000.0;
}

function regsBytes(regs) {
    const buf = Buffer.alloc(12);
    for (let i = 0; i < 6; i++) buf.writeUInt16BE(regs[i] & 0xFFFF, i * 2);
    return buf;
}

function statusBytes(words) {
    const buf = Buffer.alloc(words.length * 2);
    for (let i = 0; i < words.length; i++) buf.writeUInt16BE(words[i] & 0xFFFF, i * 2);
    return buf;
}

// Construct a fresh RDA5807MFull with a queued STC-set status so the
// fire-and-forget waitStc() started by the constructor resolves on its
// first poll, and return { connection, sensor, regs, band, space, east50 }
// where regs is the expected post-init shadow register array (TUNE already
// cleared, mirroring what the driver does once it observes STC).
async function newSensor(frequencyMhz = 100.0, volume = 8) {
    const connection = new I2CConnectionMock();
    connection.queueRead(statusBytes([STC]));
    const band = BAND_WORLD;
    const space = SPACE_100K;
    const east50 = false;
    const chan0 = freqToChan(band, space, east50, frequencyMhz);
    const regs = [
        DHIZ | DMUTE | SKMODE | NEW_METHOD | ENABLE,
        (chan0 << 6) | TUNE | (band << 2) | space,
        SOFTMUTE_EN | DE,
        INT_MODE | (8 << 8) | (volume & 0x0F),
        0x0000,
        (16 << 10) | BAND_65M_50M | 0x0002,
    ];
    const sensor = new RDA5807MFull(connection, frequencyMhz, volume);
    await flushMicrotasks(); // let the fire-and-forget waitStc().then(...) finish
    regs[1] &= ~TUNE;
    return { connection, sensor, regs, band, space, east50 };
}

async function main() {
    // --- init ---
    {
        const { connection, regs } = await newSensor();
        const expected = regs.slice();
        expected[1] |= TUNE; // write happened before the shadow TUNE bit was cleared
        checkTrue('init_writes_regs', connection.writes[0].equals(regsBytes(expected)));
    }

    // --- frequency() ---
    {
        const { connection, sensor, band, space, east50 } = await newSensor();
        connection.queueRead(statusBytes([250]));
        const freq = await sensor.frequency();
        checkTrue('frequency', freq === chanToFreq(band, space, east50, 250));
    }

    // --- setFrequency() ---
    {
        const { connection, sensor, regs, band, space, east50 } = await newSensor();
        connection.queueRead(statusBytes([STC]));
        await sensor.setFrequency(103.5);
        const chan1 = freqToChan(band, space, east50, 103.5);
        regs[1] = (chan1 << 6) | TUNE | (band << 2) | space;
        checkTrue('set_frequency_writes', connection.writes[connection.writes.length - 1].equals(regsBytes(regs)));
    }

    // --- setVolume() ---
    {
        const { connection, sensor, regs } = await newSensor();
        await sensor.setVolume(5);
        regs[3] = (regs[3] & ~0x000F) | (5 & 0x0F);
        checkTrue('set_volume', connection.writes[connection.writes.length - 1].equals(regsBytes(regs)));
    }

    // --- mute() ---
    {
        const { connection, sensor, regs } = await newSensor();
        await sensor.mute(true);
        regs[0] &= ~DMUTE;
        checkTrue('mute_true', connection.writes[connection.writes.length - 1].equals(regsBytes(regs)));
        await sensor.mute(false);
        regs[0] |= DMUTE;
        checkTrue('mute_false', connection.writes[connection.writes.length - 1].equals(regsBytes(regs)));
    }

    // --- seek() found ---
    {
        const { connection, sensor, regs, band, space, east50 } = await newSensor();
        connection.queueRead(statusBytes([STC | 300]));
        const result = await sensor.seek(true);
        regs[0] |= SEEKUP;
        regs[0] |= SEEK;
        const firstWrite = regsBytes(regs);
        regs[0] &= ~SEEK;
        const secondWrite = regsBytes(regs);
        const w = connection.writes;
        checkTrue('seek_up_writes', w[w.length - 2].equals(firstWrite) && w[w.length - 1].equals(secondWrite));
        checkTrue('seek_up_result', result === chanToFreq(band, space, east50, 300));
    }

    // --- seek() fails (SF set) ---
    {
        const { connection, sensor } = await newSensor();
        connection.queueRead(statusBytes([STC | SF]));
        const result = await sensor.seek(false);
        checkTrue('seek_fail_returns_null', result === null);
    }

    // --- configure() with retune (band/space change) ---
    {
        const { connection, sensor, regs, east50 } = await newSensor();
        let band = BAND_WORLD, space = SPACE_100K;
        connection.queueRead(statusBytes([500])); // configure() reads current frequency() first
        const currentFreq = chanToFreq(band, space, east50, 500);
        connection.queueRead(statusBytes([STC])); // for the resulting retune's waitStc
        await sensor.configure({
            band: BAND_US_EUROPE, space: SPACE_50K, deEmphasis: false,
            seekThreshold: 10, seekMode: false, clkMode: 3, afcDisable: true,
        });
        band = BAND_US_EUROPE;
        space = SPACE_50K;
        regs[2] &= ~DE;
        regs[2] |= AFCD;
        regs[3] = (regs[3] & ~0x0F00) | ((10 & 0x0F) << 8);
        regs[0] &= ~SKMODE;
        regs[0] = (regs[0] & ~0x0070) | ((3 & 0x07) << 4);
        const chan2 = freqToChan(band, space, east50, currentFreq);
        regs[1] = (chan2 << 6) | TUNE | (band << 2) | space;
        checkTrue('configure_retunes', connection.writes[connection.writes.length - 1].equals(regsBytes(regs)));
    }

    // --- configure() without retune (band/space unchanged) ---
    {
        const { connection, sensor, regs } = await newSensor();
        connection.queueRead(statusBytes([0])); // configure() still reads frequency() first
        await sensor.configure({ seekThreshold: 4 });
        regs[3] = (regs[3] & ~0x0F00) | ((4 & 0x0F) << 8);
        checkTrue('configure_no_retune', connection.writes[connection.writes.length - 1].equals(regsBytes(regs)));
    }

    // --- setBassBoost / setMono / setSoftmute / enableRds (chained: no reads involved) ---
    {
        const { connection, sensor, regs } = await newSensor();
        await sensor.setBassBoost(true);
        regs[0] |= BASS;
        checkTrue('set_bass_boost', connection.writes[connection.writes.length - 1].equals(regsBytes(regs)));

        await sensor.setMono(true);
        regs[0] |= MONO;
        checkTrue('set_mono', connection.writes[connection.writes.length - 1].equals(regsBytes(regs)));

        await sensor.setSoftmute(false);
        regs[2] &= ~SOFTMUTE_EN;
        checkTrue('set_softmute', connection.writes[connection.writes.length - 1].equals(regsBytes(regs)));

        await sensor.enableRds(true);
        regs[0] |= RDS_EN;
        checkTrue('enable_rds', connection.writes[connection.writes.length - 1].equals(regsBytes(regs)));
    }

    // --- rdsReady() ---
    {
        const { connection, sensor } = await newSensor();
        connection.queueRead(statusBytes([RDSR]));
        checkTrue('rds_ready_true', await sensor.rdsReady());
        connection.queueRead(statusBytes([0]));
        checkTrue('rds_ready_false', !(await sensor.rdsReady()));
    }

    // --- readRdsGroup() ---
    {
        const { connection, sensor } = await newSensor();
        connection.queueRead(statusBytes([RDSR, 0, 0x1122, 0x3344, 0x5566, 0x7788]));
        const group = await sensor.readRdsGroup();
        checkTrue('read_rds_group', group[0] === 0x1122 && group[1] === 0x3344 && group[2] === 0x5566 && group[3] === 0x7788);
        connection.queueRead(statusBytes([0, 0, 0, 0, 0, 0]));
        checkTrue('read_rds_group_none', (await sensor.readRdsGroup()) === null);
    }

    // --- isStereo / isStation / isReady / signalStrength ---
    {
        const { connection, sensor } = await newSensor();
        connection.queueRead(statusBytes([ST]));
        checkTrue('is_stereo_true', await sensor.isStereo());
        connection.queueRead(statusBytes([0, FM_TRUE]));
        checkTrue('is_station_true', await sensor.isStation());
        connection.queueRead(statusBytes([0, FM_READY]));
        checkTrue('is_ready_true', await sensor.isReady());
        connection.queueRead(statusBytes([0, (100 << 9) & 0xFFFF]));
        checkTrue('signal_strength', (await sensor.signalStrength()) === 100);
    }

    // --- standby() ---
    {
        const { connection, sensor, regs, band, space, east50 } = await newSensor();
        await sensor.standby(true);
        regs[0] &= ~ENABLE;
        checkTrue('standby_down', connection.writes[connection.writes.length - 1].equals(regsBytes(regs)));

        connection.queueRead(statusBytes([STC])); // standby(false)'s internal setFrequency's waitStc
        await sensor.standby(false);
        regs[0] |= ENABLE;
        const enableWrite = regsBytes(regs);
        const chan3 = freqToChan(band, space, east50, 100.0); // newSensor()'s default frequency, unchanged so far
        regs[1] = (chan3 << 6) | TUNE | (band << 2) | space;
        const retuneWrite = regsBytes(regs);
        const w = connection.writes;
        checkTrue('standby_up_writes', w[w.length - 2].equals(enableWrite) && w[w.length - 1].equals(retuneWrite));
    }

    // --- softReset() ---
    {
        const { connection, sensor, regs, band, space, east50 } = await newSensor();
        connection.queueRead(statusBytes([STC])); // softReset()'s internal setFrequency's waitStc
        await sensor.softReset();
        regs[0] |= SOFT_RESET;
        const setWrite = regsBytes(regs);
        regs[0] &= ~SOFT_RESET;
        const clearWrite = regsBytes(regs);
        const chan4 = freqToChan(band, space, east50, 100.0); // newSensor()'s default frequency, unchanged so far
        regs[1] = (chan4 << 6) | TUNE | (band << 2) | space;
        const retuneWrite = regsBytes(regs);
        const w = connection.writes;
        checkTrue('soft_reset_writes',
            w[w.length - 3].equals(setWrite) && w[w.length - 2].equals(clearWrite) && w[w.length - 1].equals(retuneWrite));
    }

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
