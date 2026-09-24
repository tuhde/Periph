'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { VL53L1XMinimal, VL53L1XFull } = require('../../packages/periph/src/chips/tof/vl53l1x');

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

// VL53L1X simulator with explicit 16-bit register indices. GPIO__TIO_HV_STATUS
// (0x0031) is computed: bit 0 is 0 (active-low line asserted) while a result
// is pending. Starting a single-shot or timed ranging makes a result pending;
// the interrupt clear drops it unless timed ranging is running.
class VL53L1XSim extends I2CConnectionMock {
    constructor() {
        super();
        this.pending = false;
        this.ranging = false;
        this.log = [];
        this.set(0x00E5, [0x01]);
        this.set(0x010F, [0xEA, 0xCC, 0x10]);
        this.set(0x013E, [0x91]);
        this.set(0x00DE, [0x00, 0x50]);
        // Result block: raw status 9 (valid), 10.0 SPADs, 0.5 MCPS ambient, 250 mm, 5.0 MCPS signal.
        this.set(0x0089, [9, 0, 0, 0x0A, 0x00, 0, 0, 0x00, 0x40, 0, 0, 0, 0, 0x00, 0xFA, 0x02, 0x80]);
    }

    set(reg, values) { values.forEach((v, i) => this.registers.set(reg + i, v)); }

    reg(r) { return this.registers.get(r) || 0; }

    reg16(r) { return (this.reg(r) << 8) | this.reg(r + 1); }

    writesTo(r) { return this.log.filter((w) => w.length === 3 && ((w[0] << 8) | w[1]) === r).map((w) => w[2]); }

    async write(data) {
        const buf = Buffer.from(data);
        this.log.push(buf);
        const reg = (buf[0] << 8) | buf[1];
        for (let i = 2; i < buf.length; i++) this.registers.set(reg + i - 2, buf[i]);
        if (reg === 0x0087 && buf.length === 3) {
            if (buf[2] === 0x10 || buf[2] === 0x40) {
                this.pending = true;
                this.ranging = buf[2] === 0x40;
            } else {
                this.ranging = false;
            }
        } else if (reg === 0x0086 && buf[2] === 0x01) {
            this.pending = this.ranging;
        }
    }

    async writeRead(data, n) {
        const reg = (data[0] << 8) | data[1];
        const out = Buffer.alloc(n);
        for (let i = 0; i < n; i++) out[i] = reg + i === 0x0031 ? (this.pending ? 0x00 : 0x01) : this.reg(reg + i);
        return out;
    }
}

async function main() {
    // --- Identity check -----------------------------------------------------
    const bad = new VL53L1XSim();
    bad.registers.set(0x0110, 0xCD);
    await checkRejects('init rejects wrong sensor id', () => new VL53L1XMinimal(bad).init());
    const unbooted = new VL53L1XSim();
    unbooted.registers.set(0x00E5, 0x00);
    await checkRejects('init boot timeout', () => new VL53L1XMinimal(unbooted).init());

    // --- Initialization -----------------------------------------------------
    const mock = new VL53L1XSim();
    const sensor = new VL53L1XMinimal(mock);
    await sensor.init();
    checkTrue('init 16-bit index', mock.log[0][0] === 0x00 && mock.log[0][1] === 0x2D);
    checkTrue('init config block byte by byte',
        mock.log.slice(0, 91).every((w, i) => w.length === 3 && ((w[0] << 8) | w[1]) === 0x2D + i));
    checkTrue('init config values', mock.writesTo(0x0046)[0] === 0x20 && mock.writesTo(0x0081)[0] === 0x9B);
    checkTrue('init 2v8 mode', mock.reg(0x002E) === 0x01 && mock.reg(0x002F) === 0x01);
    checkTrue('init gpio active low', mock.reg(0x0030) === 0x11);
    const starts = mock.writesTo(0x0087);
    checkTrue('init settling ranging', starts[starts.length - 2] === 0x40 && starts[starts.length - 1] === 0x00);
    checkTrue('init vhv bounds', mock.reg(0x0008) === 0x09 && mock.reg(0x000B) === 0x00);
    checkTrue('init leaves idle', !mock.ranging);

    // --- Single-shot ranging ------------------------------------------------
    mock.log = [];
    checkTrue('distance mm', (await sensor.distance()) === 250);
    checkTrue('range valid', await sensor.rangeValid());
    checkTrue('distance sequence', eq(mock.log[0], [0x00, 0x86, 0x01]) && eq(mock.log[1], [0x00, 0x87, 0x10]));
    checkTrue('distance clears interrupt', eq(mock.log[mock.log.length - 1], [0x00, 0x86, 0x01]));
    mock.registers.set(0x0089, 4);
    checkTrue('distance invalid raw', (await sensor.distance()) === 250 && !(await sensor.rangeValid()));

    // --- Full: measurement record -------------------------------------------
    const fm = new VL53L1XSim();
    const full = new VL53L1XFull(fm);
    await full.init();
    checkTrue('full is minimal', full instanceof VL53L1XMinimal);
    checkTrue('minimal has no full api', typeof VL53L1XMinimal.prototype.startContinuous === 'undefined');
    await full.distance();
    const m = await full.readMeasurement();
    checkTrue('measurement distance', m.distanceMm === 250);
    checkTrue('measurement status', m.rangeStatus === 0 && (await full.rangeStatus()) === 0);
    checkTrue('measurement signal rate', m.signalRateMcps === 5.0);
    checkTrue('measurement ambient rate', m.ambientRateMcps === 0.5);
    checkTrue('measurement spads', m.effectiveSpadCount === 10.0);
    fm.registers.set(0x0089, 0x1F);
    checkTrue('status out of table', (await full.readMeasurement()).rangeStatus === 255);
    fm.registers.set(0x0089, 9);

    // --- Timing budget and distance mode ------------------------------------
    checkTrue('default budget 100 ms', (await full.timingBudget()) === 100000);
    checkTrue('default mode long', (await full.distanceMode()) === 'long');
    await full.setTimingBudget(33000);
    checkTrue('budget long 33', fm.reg16(0x005E) === 0x0060 && fm.reg16(0x0061) === 0x006E);
    checkTrue('budget roundtrip', (await full.timingBudget()) === 33000);
    await checkRejects('budget rejects 15 long', () => full.setTimingBudget(15000), RangeError);
    await checkRejects('budget rejects other', () => full.setTimingBudget(40000), RangeError);
    await full.setDistanceMode('short');
    checkTrue('mode short regs', fm.reg(0x004B) === 0x14 && fm.reg(0x0060) === 0x07 && fm.reg(0x0063) === 0x05 &&
        fm.reg(0x0069) === 0x38 && fm.reg16(0x0078) === 0x0705 && fm.reg16(0x007A) === 0x0606);
    checkTrue('mode short keeps budget', fm.reg16(0x005E) === 0x00D6 && (await full.timingBudget()) === 33000);
    await full.setTimingBudget(15000);
    checkTrue('budget short 15', fm.reg16(0x005E) === 0x001D && fm.reg16(0x0061) === 0x0027);
    await checkRejects('mode long rejects 15 ms', () => full.setDistanceMode('long'), RangeError);
    await full.setTimingBudget(100000);
    await full.setDistanceMode('long');
    checkTrue('mode long regs', fm.reg(0x004B) === 0x0A && fm.reg16(0x0078) === 0x0F0D &&
        fm.reg16(0x005E) === 0x01CC && fm.reg16(0x0061) === 0x01EA);
    await checkRejects('mode rejects unknown', () => full.setDistanceMode('medium'), RangeError);

    // --- Inter-measurement and continuous ranging ---------------------------
    await full.setInterMeasurement(200);
    checkTrue('inter measurement encode', ((fm.reg16(0x006C) << 16) | fm.reg16(0x006E)) === Math.floor((0x50 * 200 * 1075) / 1000));
    checkTrue('inter measurement roundtrip', (await full.interMeasurement()) === 200);
    await checkRejects('inter measurement rejects zero', () => full.setInterMeasurement(0), RangeError);
    fm.log = [];
    await full.startContinuous();
    checkTrue('continuous period is budget', (await full.interMeasurement()) === 100);
    checkTrue('continuous start', eq(fm.log[fm.log.length - 1], [0x00, 0x87, 0x40]));
    checkTrue('data ready', await full.dataReady());
    checkTrue('read continuous', (await full.readContinuous()) === 250);
    checkTrue('continuous stays ready', await full.dataReady());
    await full.stopContinuous();
    checkTrue('stop continuous', eq(fm.log[fm.log.length - 1], [0x00, 0x87, 0x00]));
    await full.startContinuous(50);
    checkTrue('continuous period clamped', (await full.interMeasurement()) === 100);
    await full.stopContinuous();
    await full.startContinuous(500);
    checkTrue('continuous period kept', (await full.interMeasurement()) === 500);
    await full.stopContinuous();
    await checkRejects('continuous rejects negative', () => full.startContinuous(-1), RangeError);

    // --- Signal / sigma -----------------------------------------------------
    checkTrue('signal rate default', (await full.signalRateLimit()) === 1.0);
    await full.setSignalRateLimit(0.25);
    checkTrue('signal rate encode', fm.reg16(0x0066) === 32);
    await checkRejects('signal rate rejects negative', () => full.setSignalRateLimit(-1), RangeError);
    checkTrue('sigma default', (await full.sigmaThreshold()) === 90);
    await full.setSigmaThreshold(45);
    checkTrue('sigma encode', fm.reg16(0x0064) === 180 && (await full.sigmaThreshold()) === 45);
    await checkRejects('sigma rejects range', () => full.setSigmaThreshold(16384), RangeError);

    // --- ROI ----------------------------------------------------------------
    const r0 = await full.roi();
    checkTrue('roi default', r0.width === 16 && r0.height === 16 && (await full.roiCenter()) === 199);
    await full.setRoiCenter(167);
    await full.setRoi(8, 8);
    checkTrue('roi small keeps center', fm.reg(0x0080) === 0x77 && (await full.roiCenter()) === 167);
    await full.setRoi(8, 16);
    const r1 = await full.roi();
    checkTrue('roi large recenters', r1.width === 8 && r1.height === 16 && (await full.roiCenter()) === 199);
    await checkRejects('roi rejects small', () => full.setRoi(3, 8), RangeError);
    await checkRejects('roi center rejects range', () => full.setRoiCenter(256), RangeError);
    checkTrue('optical center', (await full.opticalCenter()) === 0x91);

    // --- Offset and crosstalk -----------------------------------------------
    await full.setOffset(-10.25);
    checkTrue('offset encode', fm.reg16(0x001E) === ((-41) & 0x1FFF) && fm.reg16(0x0020) === 0 && fm.reg16(0x0022) === 0);
    checkTrue('offset decode', (await full.offset()) === -10.25);
    await full.setOffset(700.5);
    checkTrue('offset positive', (await full.offset()) === 700.5);
    await checkRejects('offset rejects range', () => full.setOffset(1024), RangeError);
    await full.setCrosstalkCompensation(0.01);
    checkTrue('crosstalk encode', fm.reg16(0x0016) === 5120 && fm.reg16(0x0018) === 0 && fm.reg16(0x001A) === 0);
    checkTrue('crosstalk decode', (await full.crosstalkCompensation()) === 0.01);
    await checkRejects('crosstalk rejects range', () => full.setCrosstalkCompensation(0.128), RangeError);

    // --- Calibration --------------------------------------------------------
    const off = await full.calibrateOffset(260);
    checkTrue('calibrate offset value', off === 10 && (await full.offset()) === 10);
    checkTrue('calibrate offset stops', !fm.ranging);
    checkTrue('calibrate crosstalk clamped', (await full.calibrateCrosstalk(500)) === 0.127);
    fm.set(0x0098, [0x00, 0x20]);
    const xt = await full.calibrateCrosstalk(500);
    checkTrue('calibrate crosstalk value', Math.abs(xt - 0.0125) < 1e-9 && fm.reg16(0x0016) === 6400);
    await checkRejects('calibrate crosstalk rejects zero', () => full.calibrateCrosstalk(0), RangeError);

    // --- Temperature update -------------------------------------------------
    fm.log = [];
    await full.recalibrate();
    checkTrue('recalibrate sequence', eq(fm.writesTo(0x0008), [0x81, 0x09]) &&
        eq(fm.writesTo(0x000B), [0x92, 0x00]) && eq(fm.writesTo(0x0087), [0x40, 0x00]));

    // --- Thresholds, address, identification --------------------------------
    await full.setInterruptThresholds(100, 801);
    checkTrue('thresholds encode', fm.reg16(0x0074) === 100 && fm.reg16(0x0072) === 801);
    const th = await full.interruptThresholds();
    checkTrue('thresholds decode', th.lowMm === 100 && th.highMm === 801);
    await checkRejects('thresholds reject order', () => full.setInterruptThresholds(500, 100), RangeError);
    await full.setAddress(0x30);
    checkTrue('set address', fm.reg(0x0001) === 0x30);
    await checkRejects('address rejects range', () => full.setAddress(0x78), RangeError);
    checkTrue('model id', (await full.modelId()) === 0xEA);
    checkTrue('module type', (await full.moduleType()) === 0xCC);
    checkTrue('revision id', (await full.revisionId()) === 0x10);

    // --- Interrupt API ------------------------------------------------------
    await full.enableInterrupt(VL53L1XFull.SOURCE_OUT_OF_WINDOW);
    checkTrue('enable out of window', fm.reg(0x0046) === 0x02);
    await full.enableInterrupt(VL53L1XFull.SOURCE_IN_WINDOW);
    checkTrue('enable in window', fm.reg(0x0046) === 0x03);
    await full.disableInterrupt(VL53L1XFull.SOURCE_LEVEL_LOW);
    checkTrue('disable inactive source ignored', fm.reg(0x0046) === 0x03);
    await full.disableInterrupt(VL53L1XFull.SOURCE_IN_WINDOW);
    checkTrue('disable reverts to new sample', fm.reg(0x0046) === 0x20);
    await full.disableInterrupt(VL53L1XFull.SOURCE_NEW_SAMPLE_READY);
    checkTrue('disable new sample noop', fm.reg(0x0046) === 0x20);
    fm.pending = false;
    checkTrue('poll interrupt none', (await full.pollInterrupt()) === 0);
    await full.enableInterrupt(VL53L1XFull.SOURCE_OUT_OF_WINDOW);
    fm.pending = true;
    checkTrue('poll interrupt value', (await full.pollInterrupt()) === VL53L1XFull.SOURCE_OUT_OF_WINDOW);
    checkTrue('poll interrupt clears', !fm.pending);
    await checkRejects('enable rejects range', () => full.enableInterrupt(6), RangeError);

    await full.enableInterrupt(VL53L1XFull.SOURCE_NEW_SAMPLE_READY);
    fm.pending = true;
    let got = 0;
    await full.onInterrupt((status) => { got = status; });
    await new Promise((resolve) => setTimeout(resolve, 30));
    await full.offInterrupt();
    checkTrue('on interrupt polling fallback', got === VL53L1XFull.SOURCE_NEW_SAMPLE_READY);
    checkTrue('i2c address constant', VL53L1XMinimal.I2C_ADDRESS === 0x29);

    console.log(`Passed: ${passed}, Failed: ${failed}`);
    console.log('===DONE===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
