'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { APDS9250Minimal, APDS9250Full } = require('../../packages/periph/src/chips/light/apds9250');

APDS9250Minimal.startWaitMs = 0;
APDS9250Minimal.pollStepMs = 0;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const dataWrites = (conn) => conn.writes.filter((w) => w.length > 1).map((w) => Array.from(w));
const reg = (conn, r) => conn.registers.get(r);
const near = (a, b) => Math.abs(a - b) < 1e-6;

async function fresh(part = 0xB5) {
    const conn = new I2CConnectionMock();
    conn.setRegister(0x06, [part]);
    conn.setRegister(0x07, [0x08]);
    conn.setRegister(0x04, [0x22]);
    conn.setRegister(0x05, [0x01]);
    conn.setRegister(0x19, [0x10]);
    const chip = new APDS9250Full(conn);
    await chip.init();
    return { conn, chip };
}

async function throws(fn) {
    try { await fn(); return false; } catch (e) { return true; }
}

async function main() {
    {
        const { conn } = await fresh();
        checkTrue('init_sequence', JSON.stringify(dataWrites(conn)) === JSON.stringify([[0x04, 0x22], [0x05, 0x01], [0x19, 0x10], [0x00, 0x06]]));
        checkTrue('init_rejects_wrong_part', await throws(() => fresh(0x00)));
        checkTrue('init_rejects_wrong_nibble', await throws(() => fresh(0xC5)));
    }
    {
        const { conn, chip } = await fresh();
        conn.setRegister(0x0A, [0x01, 0x02, 0x03, 0x10, 0x00, 0x00, 0xFF, 0xFF, 0x0F, 0x00, 0x00, 0xF4]);
        const c = await chip.read();
        checkTrue('read_ir', c.ir === 0x030201);
        checkTrue('read_green', c.green === 16);
        checkTrue('read_blue', c.blue === 0xFFFFF);
        checkTrue('read_red_masks_nibble', c.red === 0x40000);
        const b = await chip.readChannels();
        checkTrue('read_channels', b.red === 0x40000 && b.ir === 0x030201);
        checkTrue('read_single', (await chip.readRed()) === 0x40000 && (await chip.readGreen()) === 16 &&
            (await chip.readBlue()) === 0xFFFFF && (await chip.readIr()) === 0x030201);
    }
    {
        const { conn, chip } = await fresh();
        await chip.setResolution(20);
        checkTrue('resolution_20', reg(conn, 0x04) === 0x02 && (await chip.getResolution()) === 20);
        await chip.setResolution(13);
        checkTrue('resolution_13', reg(conn, 0x04) === 0x52 && near(await chip.integrationTime(), 0.003125));
        await chip.setResolution(16);
        await chip.setMeasurementRate(2000);
        checkTrue('rate_2000', reg(conn, 0x04) === 0x46 && (await chip.getMeasurementRate()) === 2000);
        await chip.setMeasurementRate(25);
        checkTrue('rate_25_keeps_resolution', reg(conn, 0x04) === 0x40);
        let ok = true;
        const codes = [[1, 0], [3, 1], [6, 2], [9, 3], [18, 4]];
        for (const [g, code] of codes) {
            await chip.setGain(g);
            ok = ok && reg(conn, 0x05) === code && (await chip.getGain()) === g;
        }
        checkTrue('gains', ok);
        checkTrue('invalid_gain', await throws(() => chip.setGain(2)));
        checkTrue('invalid_resolution', await throws(() => chip.setResolution(12)));
        checkTrue('invalid_rate', await throws(() => chip.setMeasurementRate(30)));
        checkTrue('invalid_persistence', await throws(() => chip.setPersistence(0)) && await throws(() => chip.setPersistence(17)));
        checkTrue('invalid_variance', await throws(() => chip.setVariance(12)));
        checkTrue('invalid_channel', await throws(() => chip.setInterruptChannel('x')));
        checkTrue('invalid_thresholds', await throws(() => chip.setThresholds(5, 4)));
        await chip.setResolution(18);
        await chip.setGain(3);
        checkTrue('normalized', near(await chip.normalized(3000), 10000));
    }
    {
        const { conn, chip } = await fresh();
        conn.setRegister(0x00, [0x06]);
        await chip.setMode(false);
        checkTrue('mode_als', reg(conn, 0x00) === 0x02);
        await chip.setMode(true);
        checkTrue('mode_cs', reg(conn, 0x00) === 0x06);
        await chip.disable();
        checkTrue('disable', reg(conn, 0x00) === 0x04);
        await chip.enable();
        checkTrue('enable', reg(conn, 0x00) === 0x06);
        conn.writes.length = 0;
        await chip.softReset();
        checkTrue('soft_reset', JSON.stringify(dataWrites(conn)) === JSON.stringify([[0x00, 0x10]]));
        const id = await chip.partId();
        checkTrue('part_id', id.part === 0xB && id.revision === 0x5);
    }
    {
        const { conn, chip } = await fresh();
        await chip.setThresholds(0x12345, 0xABCDE);
        checkTrue('threshold_low_bytes', [reg(conn, 0x24), reg(conn, 0x25), reg(conn, 0x26)].join() === [0x45, 0x23, 0x01].join());
        checkTrue('threshold_high_bytes', [reg(conn, 0x21), reg(conn, 0x22), reg(conn, 0x23)].join() === [0xDE, 0xBC, 0x0A].join());
        const t = await chip.getThresholds();
        checkTrue('thresholds_roundtrip', t.low === 0x12345 && t.high === 0xABCDE);
    }
    {
        const { conn, chip } = await fresh();
        await chip.setInterruptChannel('red');
        checkTrue('channel_red', reg(conn, 0x19) === 0x20);
        await chip.setInterruptChannel('blue');
        checkTrue('channel_blue', reg(conn, 0x19) === 0x30);
        await chip.enableInterrupt(APDS9250Full.SOURCE_VARIATION);
        checkTrue('enable_variation', reg(conn, 0x19) === 0x3C);
        await chip.enableInterrupt(APDS9250Full.SOURCE_THRESHOLD);
        checkTrue('enable_threshold', reg(conn, 0x19) === 0x34);
        await chip.disableInterrupt();
        checkTrue('disable_interrupt', reg(conn, 0x19) === 0x30);
        await chip.setPersistence(16);
        checkTrue('persistence_16', reg(conn, 0x1A) === 0xF0);
        await chip.setVariance(1024);
        checkTrue('variance_1024', reg(conn, 0x27) === 7);
    }
    {
        const { conn, chip } = await fresh();
        conn.setRegister(0x07, [0x18]);
        checkTrue('data_ready_true', (await chip.dataReady()) === true);
        conn.setRegister(0x07, [0x00]);
        checkTrue('data_ready_false', (await chip.dataReady()) === false);
        checkTrue('interrupt_kept_after_data_ready', (await chip.pollInterrupt()) === 0x10);
        checkTrue('poll_clears_latch', (await chip.pollInterrupt()) === 0);
        conn.setRegister(0x07, [0x20]);
        checkTrue('status_power_on', (await chip.status()) === 0x20);
    }
    {
        const { conn, chip } = await fresh();
        const seen = [];
        chip._callback = (s) => seen.push(s);
        conn.setRegister(0x07, [0x10]);
        await chip._service();
        conn.setRegister(0x07, [0x00]);
        await chip._service();
        checkTrue('callback_receives_status', seen.length === 1 && seen[0] === 0x10);
        await chip.offInterrupt();
        checkTrue('off_interrupt_clears_callback', chip._callback === null);
    }
    checkTrue('minimal_has_no_gain', typeof new APDS9250Minimal(new I2CConnectionMock()).setGain === 'undefined');

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
