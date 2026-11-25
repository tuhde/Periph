'use strict';
const { SPIConnectionMock } = require('../../packages/periph/src/connection/spi_mock');
const { HY3131Minimal, HY3131Full } = require('../../packages/periph/src/chips/adc_dac/hy3131');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

// Models the HY3131 framing: (addr<<1)|R/W command, one blank clock on reads,
// auto-increment. INTF reads back intfValue once, then clears.
class HY3131Mock extends SPIConnectionMock {
    constructor() {
        super();
        this.mem = {};
        this.intfValue = 0x07;
        this.log = [];
    }
    async write(data) {
        data = Buffer.from(data);
        this.log.push(data);
        const addr = data[0] >> 1;
        for (let i = 1; i < data.length; i++) this.mem[addr + i - 1] = data[i];
    }
    async writeRead(data, n) {
        const addr = Buffer.from(data)[0] >> 1;
        const vals = [];
        for (let i = 0; i < n; i++) {
            const a = addr + i;
            if (a === 0x1E) { vals.push(this.intfValue); this.intfValue = 0; }
            else vals.push(this.mem[a] || 0);
        }
        const out = Buffer.alloc(n);
        for (let i = 0; i < n; i++) {
            out[i] = i === 0 ? vals[0] >> 1 : (((vals[i - 1] & 1) << 7) | (vals[i] >> 1));
        }
        return out;
    }
}

async function main() {
    const conn = new HY3131Mock();
    const adc = await HY3131Minimal.create(conn);
    checkTrue('init_reset_first', Buffer.compare(conn.log[0], Buffer.from([0x37 << 1, 0x60])) === 0);
    checkTrue('init_osc', conn.mem[0x33] === 0x28);
    checkTrue('init_reference', conn.mem[0x31] === 0xC0 && conn.mem[0x30] === 0x00);
    checkTrue('init_mux', conn.mem[0x24] === 0xA3 && conn.mem[0x28] === 0x11);
    checkTrue('init_enable_ad1', conn.mem[0x23] === 0x80 && conn.mem[0x22] === 0x07);

    conn.mem[0x70] = 0xA5; conn.mem[0x71] = 0x5A;
    checkTrue('read_register_shift', Buffer.compare(await adc.readRegister(0x70, 2), Buffer.from([0xA5, 0x5A])) === 0);
    await adc.writeRegister(0x60, Buffer.from([1, 2, 3]));
    checkTrue('write_register_autoincrement', conn.mem[0x60] === 1 && conn.mem[0x61] === 2 && conn.mem[0x62] === 3);

    conn.mem[0x00] = 0x00; conn.mem[0x01] = 0x00; conn.mem[0x02] = 0x40;
    conn.intfValue = 0x04;
    checkTrue('read_raw', (await adc.readRaw()) === 0x400000);
    conn.intfValue = 0x04;
    checkTrue('read_voltage', Math.abs((await adc.readVoltage()) - 0.5 * 1.2 / 0.9) < 1e-9);
    conn.mem[0x02] = 0x80;
    conn.intfValue = 0x04;
    checkTrue('read_raw_negative', (await adc.readRaw()) === -8388608);

    conn.intfValue = 0x04;
    checkTrue('data_ready_true', (await adc.dataReady()) === true);
    conn.mem[0x00] = 1; conn.mem[0x01] = 0; conn.mem[0x02] = 0;
    checkTrue('latched_flag_used', (await adc.readRaw()) === 1);

    const conn2 = new HY3131Mock();
    const full = await HY3131Full.create(conn2);
    await full.configureAd1({ osr: 4, chop: 2, inputGain: 3, refGain: 1, offset: 1, buffers: 3 });
    checkTrue('configure_ad1_r22', conn2.mem[0x22] === ((1 << 5) | (2 << 3) | 4));
    checkTrue('configure_ad1_r23', conn2.mem[0x23] === (0x80 | 0x10 | 0x03));
    checkTrue('configure_ad1_gain', ((conn2.mem[0x25] >> 4) & 3) === 3);
    conn2.mem[0x00] = 0; conn2.mem[0x01] = 0; conn2.mem[0x02] = 0x40;
    conn2.intfValue = 0x04;
    checkTrue('configure_ad1_voltage',
        Math.abs((await full.readVoltage()) - (0.5 * 0.3333333333 - 0.25) * 1.2 / 3.6) < 1e-6);

    await full.setAd1Input(0x8, 0x5, 1);
    checkTrue('set_ad1_input', conn2.mem[0x24] === 0x85 && ((conn2.mem[0x33] >> 2) & 3) === 1);
    await full.setAd1Reference(2, 6);
    checkTrue('set_ad1_reference', conn2.mem[0x28] === 0x26);

    await full.enableAd2({ osr: 3, inputGain: 2, source: 1, useAd3: true });
    checkTrue('enable_ad2_r26', conn2.mem[0x26] === 0x83);
    checkTrue('enable_ad2_sdo23', (conn2.mem[0x34] & 0x04) && (conn2.mem[0x34] & 0x80));
    checkTrue('enable_ad2_gain', ((conn2.mem[0x25] >> 6) & 3) === 2);
    conn2.mem[0x03] = 0xFF; conn2.mem[0x04] = 0xFF; conn2.mem[0x05] = 0xFF;
    conn2.intfValue = 0x02;
    checkTrue('read_ad2_sign', (await full.readAd2Raw()) === -1);

    await full.enableLpf(3);
    await full.enableRms();
    checkTrue('lpf_rms_bits', conn2.mem[0x29] === (0xC0 | (3 << 3)));
    [0x00, 0x90, 0x01, 0x00, 0x00].forEach((v, i) => { conn2.mem[0x09 + i] = v; });
    conn2.intfValue = 0x10;
    checkTrue('read_rms', Math.abs((await full.readRms()) - 320) < 1e-9);

    await full.enablePeakHold(2);
    checkTrue('peak_hold_bits', (conn2.mem[0x29] & 0x07) === 0x06);
    [0x00, 0x00, 0xFE, 0x00, 0x00, 0x01].forEach((v, i) => { conn2.mem[0x0E + i] = v; });
    const pk = await full.readPeak();
    checkTrue('read_peak', pk.min === -131072 && pk.max === 65536);

    await full.startCounter(0x1234);
    checkTrue('counter_preload', conn2.mem[0x1C] === 0x34 && conn2.mem[0x1D] === 0x12);
    checkTrue('counter_start', (conn2.mem[0x20] & 0x0A) === 0x0A);
    conn2.mem[0x14] = 0x01;
    conn2.intfValue = 0x01;
    checkTrue('read_counter', (await full.readCounter()).overflow === true);

    conn2.intfValue = 0x14;
    checkTrue('read_flags', (await full.readFlags()) === 0x14);
    await full.enableInterrupts(0xFF);
    checkTrue('enable_interrupts_masked', conn2.mem[0x1F] === 0x1F);

    const vals = [100, 300];
    full._waitFlag = async () => true;
    full.readRaw = async () => vals.shift();
    checkTrue('temperature_code', (await full.readTemperatureCode()) === 100);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
