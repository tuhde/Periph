'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { VL6180XMinimal, VL6180XFull } = require('../../packages/periph/src/chips/tof/vl6180x');

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

// VL6180X simulator with explicit 16-bit register indices: starting a
// measurement makes a result pending, SYSTEM__INTERRUPT_CLEAR drops it.
class VL6180XSim extends I2CConnectionMock {
    constructor(fresh = 1) {
        super();
        this.setAddressWidth(2);
        this.log = [];
        this.regs = new Map();
        this.set(0x000, [0xB4, 0x01, 0x03]);
        this.set(0x016, [fresh]);
        this.set(0x062, [42]);
        this.set(0x050, [0x01, 0x90]);   // ALS 400 counts
    }

    set(reg, values) { values.forEach((v, i) => this.regs.set(reg + i, v)); }
    reg(r) { return this.regs.get(r) || 0; }

    writesTo(reg) {
        return this.log.filter((w) => w.length === 3 && ((w[0] << 8) | w[1]) === reg).map((w) => w[2]);
    }

    lastWrite(reg) { const v = this.writesTo(reg); return v.length ? v[v.length - 1] : -1; }

    async write(data) {
        const buf = Buffer.from(data);
        this.log.push(buf);
        const reg = (buf[0] << 8) | buf[1];
        for (let i = 2; i < buf.length; i++) this.regs.set(reg + i - 2, buf[i]);
        if (buf.length === 3) {
            if (reg === 0x018 && (buf[2] & 1)) this.regs.set(0x04F, (this.reg(0x04F) & ~0x07) | 4);
            else if (reg === 0x038 && (buf[2] & 1)) this.regs.set(0x04F, (this.reg(0x04F) & ~0x38) | (4 << 3));
            else if (reg === 0x015) this.regs.set(0x04F, 0);
            else if (reg === 0x02E) this.regs.set(0x02E, 0);
        }
    }

    async writeRead(data, n) {
        const reg = (data[0] << 8) | data[1];
        const out = Buffer.alloc(n);
        for (let i = 0; i < n; i++) out[i] = this.reg(reg + i);
        return out;
    }
}

async function main() {
    // --- Initialization ---
    const sim = new VL6180XSim();
    const chip = new VL6180XMinimal(sim);
    checkTrue('ctor no bus access', sim.log.length === 0);
    await chip.init();
    checkTrue('private 0x0207', sim.writesTo(0x0207).includes(0x01));
    checkTrue('private 0x00DB', sim.writesTo(0x00DB).includes(0xCE));
    checkTrue('fresh flag cleared', sim.lastWrite(0x016) === 0);
    checkTrue('gpio1 mode', sim.lastWrite(0x011) === 0x10);
    checkTrue('averaging period', sim.lastWrite(0x10A) === 0x30);
    checkTrue('als gain 1', sim.lastWrite(0x03F) === 0x46);
    checkTrue('vhv repeat rate', sim.lastWrite(0x031) === 0xFF);
    checkTrue('integration 100 ms', sim.reg(0x040) === 0x00 && sim.reg(0x041) === 0x63);
    checkTrue('interrupt config', sim.lastWrite(0x014) === 0x24);
    checkTrue('range period', sim.lastWrite(0x01B) === 0x09);

    const sim2 = new VL6180XSim(0);
    await new VL6180XMinimal(sim2).init();
    checkTrue('no private settings when not fresh', sim2.writesTo(0x0207).length === 0);
    checkTrue('recommended still applied', sim2.lastWrite(0x014) === 0x24);

    // --- Single-shot range ---
    checkTrue('readRange', (await chip.readRange()) === 42);
    checkTrue('range start', sim.writesTo(0x018).includes(0x01));
    checkTrue('interrupt cleared', sim.lastWrite(0x015) === 0x07);
    sim.set(0x04D, [0x70]);
    await checkRejects('range error rejects', () => chip.readRange(), Error);
    sim.set(0x04D, [0x00]);

    // --- Full ---
    const fm = new VL6180XSim();
    const full = new VL6180XFull(fm);
    await full.init();
    fm.set(0x04D, [0x60]);
    const st = await full.readRangeStatus();
    checkTrue('readRangeStatus', st.mm === 42 && st.error === 6);
    fm.set(0x04D, [0x00]);

    checkTrue('readAlsRaw', (await full.readAlsRaw()) === 400);
    checkTrue('lux gain 1', Math.abs((await full.readLux()) - 0.32 * 400 / 1.01) < 1e-9);
    await full.setAlsGain(0);
    checkTrue('gain written', fm.lastWrite(0x03F) === 0x40);
    await full.setAlsIntegrationTime(50);
    checkTrue('integration code', fm.reg(0x040) === 0x00 && fm.reg(0x041) === 0x31);
    checkTrue('lux gain 20, 50 ms', Math.abs((await full.readLux()) - 0.32 * (400 / 20.0) * 2) < 1e-9);
    const hold = fm.writesTo(0x017);
    checkTrue('grouped hold', hold[hold.length - 2] === 1 && hold[hold.length - 1] === 0);
    await checkRejects('bad gain', () => full.setAlsGain(8), RangeError);
    await checkRejects('bad integration', () => full.setAlsIntegrationTime(513), RangeError);
    await full.setAlsScaler(4);
    checkTrue('scaler', fm.lastWrite(0x120) === 4);
    await checkRejects('bad scaler', () => full.setAlsScaler(17), RangeError);

    await full.startContinuousRange(50);
    checkTrue('continuous period code', fm.lastWrite(0x01B) === 0x04);
    checkTrue('continuous start', fm.lastWrite(0x018) === 0x03);
    fm.regs.set(0x04F, 4);
    checkTrue('readRangeContinuous', (await full.readRangeContinuous()) === 42);
    await full.stopContinuousRange();
    checkTrue('stop', fm.lastWrite(0x018) === 0x01);
    await checkRejects('bad period low', () => full.startContinuousRange(5), RangeError);
    await checkRejects('bad period high', () => full.startContinuousRange(2560), RangeError);

    await full.startContinuousAls(100);
    checkTrue('als period code', fm.lastWrite(0x03E) === 0x09);
    checkTrue('als continuous start', fm.lastWrite(0x038) === 0x03);
    await full.stopContinuousAls();
    checkTrue('als stop', fm.lastWrite(0x038) === 0x01);
    await full.setInterleaved(true);
    checkTrue('interleaved on', fm.lastWrite(0x2A3) === 1);
    await full.setInterleaved(false);
    checkTrue('interleaved off', fm.lastWrite(0x2A3) === 0);

    await full.setRangeThresholds(30, 80);
    checkTrue('range thresholds', fm.lastWrite(0x019) === 80 && fm.lastWrite(0x01A) === 30);
    await checkRejects('bad range thresholds', () => full.setRangeThresholds(90, 80), RangeError);
    await full.setAlsThresholds(100, 5000);
    checkTrue('als thresholds', fm.reg(0x03A) === 0x13 && fm.reg(0x03B) === 0x88 && fm.reg(0x03C) === 0 && fm.reg(0x03D) === 100);
    await full.setMaxConvergenceTime(20);
    checkTrue('convergence', fm.lastWrite(0x01C) === 20);
    await checkRejects('bad convergence', () => full.setMaxConvergenceTime(64), RangeError);
    await full.setAveragingPeriod(100);
    checkTrue('averaging', fm.lastWrite(0x10A) === 100);

    await full.setRangeOffset(-5);
    checkTrue('offset written', fm.lastWrite(0x024) === 0xFB);
    checkTrue('offset read', (await full.getRangeOffset()) === -5);
    await checkRejects('bad offset', () => full.setRangeOffset(128), RangeError);
    await full.setCrosstalkRate(0.5);
    checkTrue('crosstalk 9.7', fm.reg(0x01E) === 0x00 && fm.reg(0x01F) === 0x40);
    await checkRejects('bad crosstalk', () => full.setCrosstalkRate(-1), RangeError);
    await full.recalibrateVhv();
    checkTrue('vhv started', fm.lastWrite(0x02E) === 0x01);

    await full.setAddress(0x30);
    checkTrue('address', fm.lastWrite(0x212) === 0x30);
    await checkRejects('bad address', () => full.setAddress(0x80), RangeError);
    checkTrue('model id', (await full.modelId()) === 0xB4);
    const rev = await full.revision();
    checkTrue('revision', rev.major === 1 && rev.minor === 3);
    fm.set(0x052, [0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 1, 0]);
    const hist = await full.readHistory();
    checkTrue('history', hist.length === 8 && hist[0] === 1 && hist[7] === 256);

    // --- Interrupts ---
    fm.regs.set(0x014, 0x24);
    await full.enableInterrupt(VL6180XFull.SOURCE_RANGE, VL6180XFull.MODE_OUT_OF_WINDOW);
    checkTrue('range mode', fm.lastWrite(0x014) === 0x23);
    await full.enableInterrupt(VL6180XFull.SOURCE_ALS, VL6180XFull.MODE_LEVEL_LOW);
    checkTrue('als mode', fm.lastWrite(0x014) === 0x0B);
    await full.disableInterrupt(VL6180XFull.SOURCE_RANGE);
    checkTrue('range disabled', fm.lastWrite(0x014) === 0x08);
    await checkRejects('bad source', () => full.enableInterrupt(VL6180XFull.SOURCE_ERROR, 1), RangeError);
    await checkRejects('bad mode', () => full.enableInterrupt(VL6180XFull.SOURCE_RANGE, 5), RangeError);

    fm.regs.set(0x04F, 0x04);
    checkTrue('poll range', (await full.pollInterrupt()) === VL6180XFull.SOURCE_RANGE && fm.reg(0x04F) === 0);
    fm.regs.set(0x04F, 0x24);
    checkTrue('poll range+als', (await full.pollInterrupt()) === (VL6180XFull.SOURCE_RANGE | VL6180XFull.SOURCE_ALS));
    fm.regs.set(0x04F, 0x40);
    checkTrue('poll error', (await full.pollInterrupt()) === VL6180XFull.SOURCE_ERROR);
    checkTrue('poll none', (await full.pollInterrupt()) === 0);

    fm.regs.set(0x04F, 0x04);
    let got = 0;
    await full.onInterrupt((status) => { got = status; });
    await new Promise((resolve) => setTimeout(resolve, 30));
    await full.offInterrupt();
    checkTrue('on interrupt polling fallback', got === VL6180XFull.SOURCE_RANGE);
    checkTrue('i2c address constant', VL6180XMinimal.I2C_ADDRESS === 0x29);

    console.log(`Passed: ${passed}, Failed: ${failed}`);
    console.log('===DONE===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
