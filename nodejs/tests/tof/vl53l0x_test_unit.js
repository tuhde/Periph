'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { VL53L0XMinimal, VL53L0XFull } = require('../../packages/periph/src/chips/tof/vl53l0x');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function checkRejects(label, fn, type) {
    try {
        await fn();
        console.log(`FAIL ${label}: did not reject`); failed++;
    } catch (e) {
        checkTrue(label, !type || e instanceof type);
    }
}

const eq = (a, b) => Buffer.compare(Buffer.from(a), Buffer.from(b)) === 0;

// Page-aware VL53L0X simulator on top of the byte-slot mock. Registers
// written while 0xFF != 0 go to a separate per-page store, so the
// private-bank tuning writes don't clobber page-0 registers. Starting a
// ranging (or calibration) raises RESULT_INTERRUPT_STATUS; the interrupt
// clear drops it unless continuous mode is active. The SPAD-info handshake
// (page 7, 0x83) completes immediately.
class VL53L0XSim extends I2CConnectionMock {
    constructor() {
        super();
        this.page = 0;
        this.pages = new Map();
        this.continuous = false;
        this.log = [];
        this.setRegister(0xC0, [0xEE, 0xAA, 0x10]);
        this.setRegister(0x89, [0x00]);
        this.setRegister(0x60, [0x00]);
        this.setRegister(0x84, [0x11]);
        this.setRegister(0xB0, [0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]);
        this.setRegister(0xF8, [0x00, 0x10]);
        // Result block: status 11, 10.0 SPADs, 5.0 MCPS signal, 0.5 MCPS ambient, 250 mm.
        this.setRegister(0x14, [11 << 3, 0x00, 0x0A, 0x00, 0x00, 0x00, 0x02, 0x80, 0x00, 0x40, 0x00, 0xFA]);
        this.pages.set('1:145', 0x3C);   // page 1, 0x91 stop variable
        this.pages.set('7:146', 0x85);   // page 7, 0x92 SPAD info: aperture, 5 SPADs
    }

    pageReg(page, reg) { return this.pages.get(`${page}:${reg}`); }

    reg(r) { return this.registers.get(r) || 0; }

    reg16(r) { return (this.reg(r) << 8) | this.reg(r + 1); }

    page0Writes(r) { return this.log.filter(([p, w]) => p === 0 && w.length >= 2 && w[0] === r).map(([, w]) => w); }

    logged(bytes) { return this.log.some(([, w]) => eq(w, bytes)); }

    async write(data) {
        const buf = Buffer.from(data);
        const reg = buf[0];
        if (reg === 0xFF) this.page = buf[1];
        this.log.push([this.page, buf]);
        if (this.page !== 0 && reg !== 0xFF) {
            for (let i = 1; i < buf.length; i++) this.pages.set(`${this.page}:${reg + i - 1}`, buf[i]);
            if (this.page === 7 && reg === 0x83 && buf[1] === 0x00) this.pages.set('7:131', 0x01);
            return;
        }
        await super.write(buf);
        if (reg === 0x00 && buf.length === 2) {
            const value = buf[1];
            if (value & 0x06) {
                this.continuous = true;
                this.registers.set(0x13, 0x04);
            } else if (value & 0x01) {
                if (this.continuous) this.continuous = false;
                else this.registers.set(0x13, 0x04);
            }
            this.registers.set(0x00, 0x00);
        } else if (reg === 0x0B && buf[1] === 0x01 && !this.continuous) {
            this.registers.set(0x13, 0x00);
        }
    }

    async writeRead(data, n) {
        if (this.page !== 0) {
            const out = Buffer.alloc(n);
            for (let i = 0; i < n; i++) out[i] = this.pageReg(this.page, data[0] + i) || 0;
            return out;
        }
        return super.writeRead(data, n);
    }
}

async function main() {
    // --- Identity check -----------------------------------------------------
    const bad = new VL53L0XSim();
    bad.registers.set(0xC0, 0xEF);
    await checkRejects('init rejects wrong model id', () => new VL53L0XMinimal(bad).init());

    // --- Initialization -----------------------------------------------------
    const mock = new VL53L0XSim();
    const sensor = new VL53L0XMinimal(mock);
    await sensor.init();
    checkTrue('init 2v8 mode', (mock.reg(0x89) & 0x01) === 0x01);
    checkTrue('init i2c standard mode', mock.registers.get(0x88) === 0x00);
    checkTrue('init stop variable', sensor._stopVariable === 0x3C);
    checkTrue('init signal checks disabled', eq(mock.page0Writes(0x60)[0], [0x60, 0x12]));
    checkTrue('init signal rate limit', eq(mock.page0Writes(0x44)[0], [0x44, 0x00, 0x20]));
    checkTrue('init spad map aperture 5',
        [0, 1, 2, 3, 4, 5].map((i) => mock.reg(0xB0 + i)).join() === [0x00, 0xF0, 0x01, 0, 0, 0].join());
    checkTrue('init ref en start select', mock.reg(0xB6) === 0xB4);
    checkTrue('init dynamic spad page1', mock.pageReg(1, 0x4E) === 0x2C && mock.pageReg(1, 0x4F) === 0x00);
    checkTrue('init tuning page0', mock.reg(0x46) === 0x25 && mock.reg(0x70) === 0x04);
    checkTrue('init tuning page1', mock.pageReg(1, 0x46) === 0x05);
    checkTrue('init gpio new sample', mock.reg(0x0A) === 0x04);
    checkTrue('init gpio active low', mock.reg(0x84) === 0x01);
    checkTrue('init sequence config', mock.reg(0x01) === 0xE8);
    const starts = mock.page0Writes(0x00).map((w) => w[1]);
    checkTrue('init vhv then phase calibration', starts.slice(-4).join() === [0x41, 0x00, 0x01, 0x00].join());
    checkTrue('init returns to page0', mock.page === 0);
    const seq = mock.page0Writes(0x01).map((w) => w[1]);
    checkTrue('init sequence order', seq.slice(-4).join() === [0xE8, 0x01, 0x02, 0xE8].join());

    // --- Single-shot ranging ------------------------------------------------
    mock.log = [];
    checkTrue('distance mm', (await sensor.distance()) === 250);
    checkTrue('range valid', await sensor.rangeValid());
    const preamble = [[0x80, 0x01], [0xFF, 0x01], [0x00, 0x00], [0x91, 0x3C], [0x00, 0x01], [0xFF, 0x00], [0x80, 0x00]];
    checkTrue('distance stop variable preamble', preamble.every((w, i) => eq(mock.log[i][1], w)));
    checkTrue('distance start', eq(mock.log[7][1], [0x00, 0x01]));
    checkTrue('distance clears interrupt', eq(mock.log[mock.log.length - 1][1], [0x0B, 0x01]));

    mock.setRegister(0x14, [4 << 3]);
    mock.setRegister(0x1E, [0x1F, 0xFF]);
    checkTrue('distance out of range raw', (await sensor.distance()) === 8191);
    checkTrue('range invalid', !(await sensor.rangeValid()));

    // --- Full: measurement record -------------------------------------------
    const fm = new VL53L0XSim();
    const full = new VL53L0XFull(fm);
    checkTrue('full is minimal', full instanceof VL53L0XMinimal);
    checkTrue('minimal has no full api', typeof VL53L0XMinimal.prototype.startContinuous === 'undefined');
    await full.distance();
    const m = await full.readMeasurement();
    checkTrue('measurement distance', m.distanceMm === 250);
    checkTrue('measurement status', m.rangeStatus === 11 && (await full.rangeStatus()) === 11);
    checkTrue('measurement signal rate', m.signalRateMcps === 5.0);
    checkTrue('measurement ambient rate', m.ambientRateMcps === 0.5);
    checkTrue('measurement spads', m.effectiveSpadCount === 10.0);

    // --- Continuous ranging -------------------------------------------------
    fm.log = [];
    await full.startContinuous();
    checkTrue('continuous back to back', eq(fm.log[fm.log.length - 1][1], [0x00, 0x02]));
    checkTrue('continuous stop variable', fm.logged([0x91, 0x3C]));
    checkTrue('data ready', await full.dataReady());
    checkTrue('read continuous', (await full.readContinuous()) === 250);
    await full.stopContinuous();
    const stop = [[0x00, 0x01], [0xFF, 0x01], [0x00, 0x00], [0x91, 0x00], [0x00, 0x01], [0xFF, 0x00]];
    checkTrue('stop continuous sequence', stop.every((w, i) => eq(fm.log[fm.log.length - 6 + i][1], w)));
    await full.startContinuous(100);
    checkTrue('timed period', [0, 1, 2, 3].map((i) => fm.reg(0x04 + i)).join() === [0x00, 0x00, 0x06, 0x40].join());
    checkTrue('timed start', eq(fm.log[fm.log.length - 1][1], [0x00, 0x04]));
    await full.stopContinuous();

    // --- Timing budget ------------------------------------------------------
    const budget = await full.timingBudget();
    checkTrue('default budget about 33ms', budget >= 32000 && budget <= 34000);
    await full.setTimingBudget(50000);
    checkTrue('budget roundtrip', Math.abs((await full.timingBudget()) - 50000) < 50);
    await checkRejects('budget rejects below min', () => full.setTimingBudget(19999), RangeError);

    // --- Signal rate --------------------------------------------------------
    await full.setSignalRateLimit(0.1);
    checkTrue('signal rate encode', fm.reg16(0x44) === 13);
    checkTrue('signal rate decode', (await full.signalRateLimit()) === 13 / 128);
    await checkRejects('signal rate rejects negative', () => full.setSignalRateLimit(-1), RangeError);

    // --- VCSEL periods ------------------------------------------------------
    checkTrue('vcsel pre default', (await full.vcselPulsePeriod('pre_range')) === 14);
    checkTrue('vcsel final default', (await full.vcselPulsePeriod('final_range')) === 10);
    await full.setVcselPulsePeriod('pre_range', 18);
    checkTrue('vcsel pre 18', (await full.vcselPulsePeriod('pre_range')) === 18 &&
        fm.reg(0x57) === 0x50 && fm.reg(0x56) === 0x08);
    await full.setVcselPulsePeriod('final_range', 14);
    checkTrue('vcsel final 14', (await full.vcselPulsePeriod('final_range')) === 14 && fm.reg(0x48) === 0x48 &&
        fm.reg(0x32) === 0x03 && fm.reg(0x30) === 0x07 && fm.pageReg(1, 0x30) === 0x20);
    checkTrue('vcsel keeps budget', Math.abs((await full.timingBudget()) - 50000) < 300);
    checkTrue('vcsel restores sequence', fm.reg(0x01) === 0xE8);
    await checkRejects('vcsel rejects odd', () => full.setVcselPulsePeriod('pre_range', 13), RangeError);
    await checkRejects('vcsel rejects type', () => full.setVcselPulsePeriod('mid', 10), RangeError);

    await full.setProfile('high_speed');
    checkTrue('profile high speed', (await full.vcselPulsePeriod('pre_range')) === 14 &&
        (await full.vcselPulsePeriod('final_range')) === 10 &&
        Math.abs((await full.timingBudget()) - 20000) < 50 && fm.reg16(0x44) === 32);
    await full.setProfile('long_range');
    checkTrue('profile long range', (await full.vcselPulsePeriod('pre_range')) === 18 &&
        (await full.vcselPulsePeriod('final_range')) === 14 && fm.reg16(0x44) === 13);
    await checkRejects('profile rejects unknown', () => full.setProfile('turbo'), RangeError);

    // --- Offset and crosstalk -----------------------------------------------
    await full.setOffset(-10.25);
    checkTrue('offset encode', fm.reg16(0x28) === ((-41) & 0x0FFF));
    checkTrue('offset decode', (await full.offset()) === -10.25);
    await full.setOffset(12.5);
    checkTrue('offset positive', (await full.offset()) === 12.5);
    await checkRejects('offset rejects range', () => full.setOffset(512), RangeError);
    await full.setCrosstalkCompensation(0.5);
    checkTrue('crosstalk encode', fm.reg16(0x20) === 4096);
    await full.setCrosstalkCompensation(0);
    checkTrue('crosstalk off', fm.reg16(0x20) === 0);
    await checkRejects('crosstalk rejects range', () => full.setCrosstalkCompensation(8), RangeError);

    // --- Recalibrate --------------------------------------------------------
    fm.log = [];
    await full.recalibrate();
    checkTrue('recalibrate vhv and phase', fm.logged([0x00, 0x41]) && fm.logged([0x01, 0x02]) && fm.reg(0x01) === 0xE8);

    // --- Thresholds, address, identification --------------------------------
    await full.setInterruptThresholds(100, 801);
    checkTrue('thresholds encode', fm.reg16(0x0E) === 50 && fm.reg16(0x0C) === 400);
    const th = await full.interruptThresholds();
    checkTrue('thresholds decode', th.lowMm === 100 && th.highMm === 800);
    await checkRejects('thresholds reject order', () => full.setInterruptThresholds(500, 100), RangeError);
    await full.setAddress(0x30);
    checkTrue('set address', fm.reg(0x8A) === 0x30);
    await checkRejects('address rejects range', () => full.setAddress(0x78), RangeError);
    checkTrue('model id', (await full.modelId()) === 0xEE);
    checkTrue('revision id', (await full.revisionId()) === 0x10);

    // --- Interrupt API ------------------------------------------------------
    await full.enableInterrupt(VL53L0XFull.SOURCE_OUT_OF_WINDOW);
    checkTrue('enable interrupt', fm.reg(0x0A) === 0x03);
    await full.disableInterrupt(VL53L0XFull.SOURCE_LEVEL_LOW);
    checkTrue('disable inactive source ignored', fm.reg(0x0A) === 0x03);
    await full.disableInterrupt(VL53L0XFull.SOURCE_OUT_OF_WINDOW);
    checkTrue('disable active source', fm.reg(0x0A) === 0x00);
    await full.enableInterrupt(VL53L0XFull.SOURCE_NEW_SAMPLE_READY);
    fm.registers.set(0x13, 0x03 | 0x08);
    checkTrue('poll interrupt value', (await full.pollInterrupt()) === VL53L0XFull.SOURCE_OUT_OF_WINDOW);
    checkTrue('poll interrupt clears', fm.reg(0x13) === 0x00);
    checkTrue('poll interrupt none', (await full.pollInterrupt()) === 0);
    await checkRejects('enable rejects range', () => full.enableInterrupt(5), RangeError);

    fm.registers.set(0x13, 0x04);
    const got = await new Promise((resolve) => {
        full.onInterrupt((status) => resolve(status));
    });
    await full.offInterrupt();
    checkTrue('on interrupt polling fallback', got === VL53L0XFull.SOURCE_NEW_SAMPLE_READY);

    console.log(`Passed: ${passed}, Failed: ${failed}`);
    console.log('===DONE===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
