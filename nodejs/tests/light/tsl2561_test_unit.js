'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { TSL2561Minimal, TSL2561Full, calculateLux } = require('../../packages/periph/src/chips/light/tsl2561');

TSL2561Minimal.waitFactor = 0;   // skip the integration waits

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const near = (a, b, rel = 1e-9) => Math.abs(a - b) <= rel * Math.max(1, Math.abs(b));
const reg = (conn, r) => conn.registers.get(r);
const wrote = (conn, bytes) => conn.writes.some((w) => w.equals(Buffer.from(bytes)));
const setCounts = (conn, ch0, ch1) => conn.setRegister(0x9C, [ch0 & 0xFF, ch0 >> 8, ch1 & 0xFF, ch1 >> 8]);

const T = [[0.50, 0.0304, 0.062], [0.61, 0.0224, 0.031], [0.80, 0.0128, 0.0153], [1.30, 0.00146, 0.00112]];
const CS = [[0.52, 0.0315, 0.0593], [0.65, 0.0229, 0.0291], [0.80, 0.0157, 0.0180], [1.30, 0.00338, 0.00260]];
function ref(ch0, ch1, gain, scale, k) {
    const c0 = ch0 * (16 / gain) / scale;
    const c1 = ch1 * (16 / gain) / scale;
    const r = c1 / c0;
    if (r <= k[0][0]) return k[0][1] * c0 - k[0][2] * c0 * r ** 1.4;
    for (const [lim, a, b] of k.slice(1)) if (r <= lim) return a * c0 - b * c1;
    return 0;
}

async function fresh(ch0 = 0, ch1 = 0) {
    const conn = new I2CConnectionMock();
    conn.setRegister(0x8A, [0x15]);
    setCounts(conn, ch0, ch1);
    const chip = new TSL2561Full(conn);
    await chip.init();
    return { conn, chip };
}

async function throws(fn) {
    try { await fn(); return false; } catch (e) { return true; }
}

// Counts follow the TIMING register: the light level is fixed, counts scale with gain and time.
class RangeMock extends I2CConnectionMock {
    constructor(base) { super(); this.base = base; }

    async writeRead(data, n) {
        if (data[0] === 0x9C) {
            const t = this.registers.get(0x81) ?? 0x02;
            const gain = t & 0x10 ? 16 : 1;
            const [scale, limit] = [[0.034, 5047], [0.252, 37177], [1.0, 65535]][t & 0x03];
            const c0 = Math.min(limit, Math.floor(this.base * gain * scale));
            const c1 = Math.min(limit, Math.floor(this.base * 0.2 * gain * scale));
            setCounts(this, c0, c1);
        }
        return super.writeRead(data, n);
    }
}

async function main() {
    // -- init
    {
        const conn = new I2CConnectionMock();
        const chip = new TSL2561Full(conn);
        checkTrue('constructor_no_bus_traffic', conn.writes.length === 0);
        await chip.init();
        const data = conn.writes.filter((w) => w.length > 1).map((w) => [...w]);
        checkTrue('init_sequence', JSON.stringify(data) === JSON.stringify([[0x80, 0x03], [0x81, 0x02]]));
        checkTrue('init_reads_control', wrote(conn, [0x80]));
    }
    {
        class DeadMock extends I2CConnectionMock {
            async write(data) { await super.write(data); this.registers.set(0x80, 0); }
        }
        checkTrue('init_rejects_bad_control', await throws(() => new TSL2561Minimal(new DeadMock()).init()));
    }
    // -- lux formula
    {
        const { conn, chip } = await fresh(1000, 200);
        const lx = await chip.readLux();
        checkTrue('read_lux_segment1', near(lx, ref(1000, 200, 1, 1, T)) && Math.abs(lx - 382.2) < 0.5);
        for (const [c1, label] of [[550, 'segment2'], [700, 'segment3'], [1000, 'segment4']]) {
            setCounts(conn, 1000, c1);
            checkTrue('read_lux_' + label, near(await chip.readLux(), ref(1000, c1, 1, 1, T)));
        }
        setCounts(conn, 1000, 1400);
        checkTrue('read_lux_ratio_above_1_30', (await chip.readLux()) === 0);
        setCounts(conn, 0, 0);
        checkTrue('read_lux_dark', (await chip.readLux()) === 0);
        checkTrue('calculate_gain16_101ms', near(calculateLux(500, 100, 16, 101), ref(500, 100, 16, 0.252, T)));
        checkTrue('calculate_14ms', near(calculateLux(500, 300, 1, 14), ref(500, 300, 1, 0.034, T)));
        for (const c1 of [100, 600, 700, 1000]) {
            checkTrue(`calculate_cs_${c1}`, near(calculateLux(1000, c1, 1, 402, 'CS'), ref(1000, c1, 1, 1, CS)));
        }
    }
    // -- raw block read and saturation
    {
        const { conn, chip } = await fresh(0x1234, 0x0567);
        const raw = await chip.readRaw();
        checkTrue('read_raw', raw.ch0 === 0x1234 && raw.ch1 === 0x0567);
        checkTrue('read_raw_block_cmd', conn.writes[conn.writes.length - 1].equals(Buffer.from([0x9C])));
        const r = await chip.readLuxRaw();
        checkTrue('read_lux_raw', r.ch0 === 0x1234 && near(r.lux, ref(0x1234, 0x0567, 1, 1, T)));
        setCounts(conn, 65535, 100);
        checkTrue('saturated_throws', await throws(() => chip.readLux()));
        await chip.setIntegrationTime(101);
        setCounts(conn, 37177, 10);
        checkTrue('saturated_101ms_throws', await throws(() => chip.readLux()));
        setCounts(conn, 37176, 10);
        checkTrue('below_limit_ok', (await chip.readLux()) > 0);
    }
    // -- gain / integration time / package / manual
    {
        const { conn, chip } = await fresh(1000, 100);
        await chip.setGain(16);
        checkTrue('set_gain_16', reg(conn, 0x81) === 0x12 && chip.getGain() === 16);
        await chip.setIntegrationTime(14);
        checkTrue('set_integration_14', reg(conn, 0x81) === 0x10 && chip.getIntegrationTime() === 14);
        await chip.setGain(1);
        await chip.setIntegrationTime(101);
        checkTrue('set_integration_101', reg(conn, 0x81) === 0x01 && chip.getIntegrationTime() === 101);
        checkTrue('set_gain_rejects', await throws(() => chip.setGain(4)));
        checkTrue('set_integration_rejects', await throws(() => chip.setIntegrationTime(50)));
        checkTrue('set_package_rejects', await throws(() => chip.setPackage('X')));
        await chip.setIntegrationTime(402);
        chip.setPackage('CS');
        checkTrue('set_package_cs', near(await chip.readLux(), ref(1000, 100, 1, 1, CS)));
        await chip.setGain(16);
        await chip.startManual();
        checkTrue('start_manual', reg(conn, 0x81) === 0x1B);
        await chip.stopManual();
        checkTrue('stop_manual', reg(conn, 0x81) === 0x13 && chip.getIntegrationTime() === 0);
    }
    // -- auto-range
    {
        let chip = new TSL2561Full(new RangeMock(20));
        await chip.init();
        let lx = await chip.autoRange();
        checkTrue('auto_range_dim_16x', chip.getGain() === 16 && chip.getIntegrationTime() === 402 && lx > 0);
        chip = new TSL2561Full(new RangeMock(148000));
        await chip.init();
        lx = await chip.autoRange();
        checkTrue('auto_range_bright_14ms', chip.getGain() === 1 && chip.getIntegrationTime() === 14 && lx > 0);
        chip = new TSL2561Full(new RangeMock(5000));
        await chip.init();
        lx = await chip.autoRange();
        checkTrue('auto_range_mid_keeps_1x_402', chip.getGain() === 1 && chip.getIntegrationTime() === 402);
        checkTrue('auto_range_lux_consistent', near(lx, calculateLux(5000, 1000), 1e-6));
    }
    // -- power, thresholds, interrupt
    {
        const { conn, chip } = await fresh(500, 0);
        checkTrue('is_powered', (await chip.isPowered()) === true);
        await chip.powerDown();
        checkTrue('power_down', reg(conn, 0x80) === 0 && (await chip.isPowered()) === false);
        await chip.powerUp();
        checkTrue('power_up', reg(conn, 0x80) === 0x03);
        await chip.setThresholds(0x0102, 0x0A0B);
        checkTrue('set_thresholds_word', wrote(conn, [0xA2, 0x02, 0x01]) && wrote(conn, [0xA4, 0x0B, 0x0A]));
        const th = await chip.getThresholds();
        checkTrue('get_thresholds', th.low === 0x0102 && th.high === 0x0A0B);
        checkTrue('thresholds_range', await throws(() => chip.setThresholds(-1, 70000)));
        await chip.configureInterrupt(1, 2);
        checkTrue('configure_interrupt', reg(conn, 0x86) === 0x12);
        checkTrue('configure_interrupt_rejects', await throws(() => chip.configureInterrupt(3, 0))
            && await throws(() => chip.configureInterrupt(1, 16)));
        const id = await chip.readId();
        checkTrue('read_id', id === 0x15 && (id >> 4) === 1);
        await chip.setThresholds(100, 1000);
        checkTrue('poll_inside_window', (await chip.pollInterrupt()) === false);
        setCounts(conn, 100, 0);
        checkTrue('poll_at_low', (await chip.pollInterrupt()) === true);
        setCounts(conn, 1001, 0);
        checkTrue('poll_above_high', (await chip.pollInterrupt()) === true);
        await chip.clearInterrupt();
        checkTrue('clear_interrupt', conn.writes[conn.writes.length - 1].equals(Buffer.from([0xC0])));

        const pin = { handler: null, async onEdge(h) { this.handler = h; }, async offEdge() { this.handler = null; } };
        const seen = [];
        await chip.onInterrupt((c0) => seen.push(c0), { intPin: pin });
        setCounts(conn, 4242, 0);
        await chip._serviceEdge();
        checkTrue('on_interrupt_callback', seen.length === 1 && seen[0] === 4242
            && conn.writes[conn.writes.length - 1].equals(Buffer.from([0xC0])));
        await chip.offInterrupt();
        checkTrue('off_interrupt', pin.handler === null);
    }
    checkTrue('minimal_has_no_thresholds', typeof new TSL2561Minimal(new I2CConnectionMock()).setThresholds === 'undefined');

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
