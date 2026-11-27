'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { RRH46410Minimal, RRH46410Full, RRH46410Error } = require('../../packages/periph/src/chips/gas/rrh46410');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const near = (a, b, tol = 1e-9) => Math.abs(a - b) < tol;
const cs = (bytes) => (~bytes.reduce((a, b) => a + b, 0)) & 0xFF;

async function rejects(fn, cls = Error) {
    try { await fn(); } catch (e) { return e instanceof cls ? e : false; }
    return false;
}

// Simulates the RRH46410 request/response protocol with checksums.
class Sim extends I2CConnectionMock {
    constructor() {
        super();
        this.response = null;
        this.requestsOk = true;
        this.mode = 0;
        this.counter = 0;
        this.hasResult = false;
        this.pid = 0x2310;
        this.badChecksum = false;
        this.statusOverride = null;
        this.gpio = 0x0C;
        this.gpioDir = 0;
        this.humidity = null;
        this.intPin = null;
    }

    sample(n = 1) { this.hasResult = true; this.counter = (this.counter + n) & 0xFF; }

    async write(data) {
        const buf = Buffer.from(data);
        this.writes.push(buf);
        this._maybeFailWrite();
        if ((buf.reduce((a, b) => a + b, 0) & 0xFF) !== 0xFF) this.requestsOk = false;
        const cmd = buf[0];
        const p = buf.subarray(1, buf.length - 1);
        let status = 0;
        let out = [];
        switch (cmd) {
        case 0x00: out = [this.pid >> 8, this.pid & 0xFF, 1, 2, 3]; break;
        case 0x01: out = [1, 2, 3, 4, 5, 6]; break;
        case 0x10: out = [this.mode]; break;
        case 0x11: this.mode = p[0]; this.counter = 0; this.hasResult = false; break;
        case 0x12: this.humidity = p[0]; break;
        case 0x18:
            if (!this.hasResult) status = 0x10;
            out = this.mode === 0x05 ? [this.counter, 0x01, 0xF4, 0x0B, 0xB8]
                : [this.counter, 25, 0x00, 0x32, 0x01, 0x2C, 0x02, 0x58, 3];
            break;
        case 0x20: this.gpioDir = p[0]; break;
        case 0x21: out = [this.gpio]; break;
        case 0x22: this.gpio |= p[0]; break;
        case 0x23: this.gpio &= ~p[0]; break;
        case 0x8F: this.mode = 0; this.response = null; return;
        default: status = 0x80;
        }
        if (this.statusOverride !== null) status = this.statusOverride;
        const frame = [status, ...out];
        let c = cs(frame);
        if (this.badChecksum) c ^= 1;
        this.response = Buffer.from([...frame, c]);
    }

    async read(n) {
        if (this._failReads > 0) { this._failReads--; throw new Error('I2C NACK (mock)'); }
        const out = Buffer.alloc(n, 0xFF);
        if (this.response) this.response.copy(out, 0, 0, Math.min(n, this.response.length));
        return out;
    }
}

class Pin {
    constructor() { this.handler = null; this.trigger = null; this.high = false; }
    async onEdge(h, t) { this.handler = h; this.trigger = t; }
    async offEdge() { this.handler = null; }
    isHigh() { return this.high; }
}

(async () => {
    // --- init ---
    let sim = new Sim();
    let m = new RRH46410Minimal(sim);
    await m.init();
    checkTrue('init_product_id_request', sim.writes[0].equals(Buffer.from([0x00, 0xFF])));
    checkTrue('init_set_mode_request', sim.writes[1].equals(Buffer.from([0x11, 0x01, cs([0x11, 0x01])])));
    checkTrue('init_request_checksums', sim.requestsOk);
    checkTrue('init_mode_iaq', sim.mode === 0x01);

    sim = new Sim();
    sim.pid = 0x1234;
    checkTrue('init_wrong_pid_rejects', await rejects(() => new RRH46410Minimal(sim).init(), RRH46410Error));
    sim = new Sim();
    sim.failNextWrites(3);
    await new RRH46410Minimal(sim).init();
    checkTrue('init_retries_until_ready', sim.mode === 0x01);
    sim = new Sim();
    sim.failNextWrites(1000);
    checkTrue('init_timeout_rejects', await rejects(() => new RRH46410Minimal(sim).init(), RRH46410Error));

    // --- read ---
    sim = new Sim();
    m = new RRH46410Minimal(sim);
    await m.init();
    sim.sample(5);
    let r = await m.read();
    checkTrue('read_iaq', near(r.iaq, 2.5));
    checkTrue('read_tvoc', near(r.tvoc_mg_m3, 0.5));
    checkTrue('read_etoh', near(r.etoh_ppm, 3.0));
    checkTrue('read_eco2', r.eco2_ppm === 600);
    checkTrue('read_rel_iaq', r.relative_iaq === 30);
    checkTrue('read_sample_not_warmed_up', r.sample === 5 && r.warmed_up === false);
    checkTrue('read_request', sim.writes[sim.writes.length - 1].equals(Buffer.from([0x18, 0xE7])));
    sim.sample(95);
    r = await m.read();
    checkTrue('read_warmed_up_after_100', r.warmed_up === true && r.sample === 100);
    sim.sample(200);
    r = await m.read();
    checkTrue('read_counter_wrap', r.sample === 44 && r.warmed_up === true);
    sim.sample(1);
    sim.badChecksum = true;
    checkTrue('bad_checksum_rejects', await rejects(() => m.read(), RRH46410Error));
    sim.badChecksum = false;
    sim.statusOverride = 0x0A;
    const e = await rejects(() => m.read(), RRH46410Error);
    checkTrue('damage_status_rejects', e && e.status === 0x0A);
    sim.statusOverride = null;

    // --- Full ---
    sim = new Sim();
    let f = new RRH46410Full(sim);
    f._pollMs = 1;
    await f.init();
    const id = await f.productId();
    checkTrue('product_id', id.productId === 0x2310 && id.firmware.join('.') === '1.2.3');
    checkTrue('tracking_number', (await f.trackingNumber()).equals(Buffer.from([1, 2, 3, 4, 5, 6])));
    checkTrue('get_mode', (await f.getMode()) === RRH46410Full.MODE_IAQ_2ND_GEN);
    checkTrue('read_results_null_before_sample', (await f.readResults()) === null);
    checkTrue('status_not_complete', f.status().not_complete === true);
    sim.sample(3);
    r = await f.readResults();
    checkTrue('read_results', r.sample === 3 && r.eco2_ppm === 600);
    checkTrue('status_ok', f.status().raw === 0 && f.status().mode_error === 0);
    checkTrue('wait_for_sample_false', (await f.waitForSample(0.005)) === false);
    sim.sample();
    checkTrue('poll_interrupt_true', (await f.pollInterrupt()) === true);
    checkTrue('wait_for_sample_true', (await f.waitForSample(0.1)) === true);
    checkTrue('poll_interrupt_false', (await f.pollInterrupt()) === false);

    await f.setHumidity(50);
    checkTrue('set_humidity_code', sim.humidity === 128);
    await f.setHumidity(100);
    checkTrue('set_humidity_100', sim.humidity === 255);
    checkTrue('set_humidity_range', await rejects(() => f.setHumidity(101), RangeError));

    await f.setMode(RRH46410Full.MODE_PBAQ);
    checkTrue('set_mode_pbaq', sim.mode === 0x05);
    checkTrue('pbaq_timing', f.samplePeriod() === 5 && f.warmupSamples() === 60);
    checkTrue('ulp_timing', f.samplePeriod(RRH46410Full.MODE_ULP) === 90 && f.warmupSamples(RRH46410Full.MODE_ULP) === 10);
    checkTrue('iaq_timing', f.samplePeriod(RRH46410Full.MODE_IAQ_2ND_GEN) === 3 && f.warmupSamples(RRH46410Full.MODE_IAQ_2ND_GEN) === 100);
    sim.sample(2);
    r = await f.readResults();
    checkTrue('pbaq_decode', near(r.tvoc_mg_m3, 0.5) && near(r.etoh_ppm, 3.0) && !('iaq' in r) && !('eco2_ppm' in r));
    checkTrue('set_mode_invalid', await rejects(() => f.setMode(0x04), RangeError));

    checkTrue('label_very_good', RRH46410Full.airQualityLabel(1.5) === 'very good');
    checkTrue('label_good', f.airQualityLabel(2.0) === 'good');
    checkTrue('label_medium', RRH46410Full.airQualityLabel(3.9) === 'medium');
    checkTrue('label_poor', RRH46410Full.airQualityLabel(4.0) === 'poor');
    checkTrue('label_bad', RRH46410Full.airQualityLabel(5.0) === 'bad');

    await f.configGpio(0x03);
    checkTrue('config_gpio', sim.gpioDir === 0x03);
    await f.setGpio(0x01);
    checkTrue('set_gpio', ((await f.getGpio()) & 0x01) === 1);
    await f.clearGpio(0x01);
    checkTrue('clear_gpio', ((await f.getGpio()) & 0x01) === 0);
    checkTrue('gpio_mask_range', await rejects(() => f.setGpio(4), RangeError));

    await f.suspend();
    checkTrue('suspend', sim.mode === 0);
    checkTrue('clean_requires_confirm', (await rejects(() => f.cleanSensor())) && sim.mode === 0);

    checkTrue('send_command', (await f.sendCommand(0x21, [], 1)).equals(Buffer.from([sim.gpio])));
    await f.sendCommand(0x55);
    checkTrue('send_command_invalid_status', f.status().invalid_command === true);

    await f.setMode(RRH46410Full.MODE_IAQ_2ND_GEN);
    await f.reset();
    checkTrue('reset_request', sim.writes.some((w) => w.equals(Buffer.from([0x8F, 0x70]))));
    checkTrue('reset_suspend', f.samplePeriod() === 0 && sim.mode === 0);

    // --- interrupt ---
    sim = new Sim();
    f = new RRH46410Full(sim);
    await f.init();
    const pin = new Pin();
    const hits = [];
    await f.onInterrupt((res) => hits.push(res), pin);
    checkTrue('on_interrupt_rising', pin.handler !== null && pin.trigger === 'rising');
    sim.sample();
    await f._service();
    checkTrue('on_interrupt_callback', hits.length === 1 && hits[0].sample === 1);
    await f._service();
    checkTrue('on_interrupt_no_duplicate', hits.length === 1);
    await f.offInterrupt();
    checkTrue('off_interrupt', pin.handler === null);

    f._pollMs = 5;
    await f.onInterrupt((res) => hits.push(res));
    sim.sample();
    await new Promise((res) => setTimeout(res, 50));
    await f.offInterrupt();
    checkTrue('on_interrupt_polling', hits.length === 2 && hits[1].sample === 2);

    sim = new Sim();
    sim.intPin = new Pin();
    f = new RRH46410Full(sim);
    await f.init();
    checkTrue('poll_interrupt_pin_low', (await f.pollInterrupt()) === false);
    sim.intPin.high = true;
    checkTrue('poll_interrupt_pin_high', (await f.pollInterrupt()) === true);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
})();
