'use strict';
const { SPIConnectionMock } = require('../../packages/periph/src/connection/spi_mock');
const { OutputPin } = require('../../packages/periph/src/connection/output_pin');
const { AD7705Minimal, AD7705Full } = require('../../packages/periph/src/chips/adc_dac/ad7705');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function eq(a, b) {
    return Buffer.isBuffer(a) && Buffer.isBuffer(b) && Buffer.compare(a, b) === 0;
}

class FakeOutputPin extends OutputPin {
    constructor() {
        super();
        this.calls = [];
    }
    async set(high) { this.calls.push(high); }
}

// A tiny event-loop tick, so unawaited fire-and-forget async work queued by
// a constructor (documented convention -- see MFRC522Minimal / AD7705Minimal)
// has settled before we inspect connection.writes.
function tick() {
    return new Promise((resolve) => setImmediate(resolve));
}

async function main() {
    // --- AD7705Minimal ctor: Clock + Setup Register writes, self-calibrate ---
    // mclkHz=4915200 -> CLKDIV=1, CLK=1, FS1:FS0=00 (50 Hz) -> Clock reg = 0x0C
    // (matches the spec's own worked example). Setup reg = MODE_SELF_CAL|GAIN_1|
    // BIPOLAR|UNBUFFERED|FSYNC_RUN = 0x40.
    const connection = new SPIConnectionMock();
    const sensor = new AD7705Minimal(connection, 2.5, 4915200);
    await tick();
    checkTrue('init_clock_write', eq(connection.writes[0], Buffer.from([0x20, 0x0C])));
    checkTrue('init_setup_write', eq(connection.writes[1], Buffer.from([0x10, 0x40])));
    checkTrue('init_waits_drdy', eq(connection.writes[2], Buffer.from([0x08])));

    try {
        new AD7705Minimal(connection, 2.5, 123);
        checkTrue('init_rejects_bad_mclk', false);
    } catch (e) {
        checkTrue('init_rejects_bad_mclk', true);
    }

    // --- AD7705Minimal.readRaw / readVoltage: Channel 1, gain 1, bipolar ---
    // Data Register CH1 read comm byte = REG_DATA|RW_READ|CH1 = 0x38.
    // code=0xC000 (49152) -> bipolar: ((49152-32768)/32768)*(2.5/1) = 1.25 V
    connection.setRegister(0x38, [0xC0, 0x00]);
    checkTrue('read_raw', (await sensor.readRaw()) === 0xC000);
    checkTrue('read_voltage', Math.abs((await sensor.readVoltage()) - 1.25) < 1e-9);

    // --- AD7705Full.configure + readVoltageChannel: per-channel independence ---
    // Regression test for a driver bug found while writing this test: configure()
    // only updated the shared _gain/_bipolar when channel===1, so readVoltageChannel(2)
    // silently converted using channel 1's gain/bipolar instead of channel 2's.
    const connection2 = new SPIConnectionMock();
    const full = new AD7705Full(connection2, 2.5, 4915200);
    await tick();
    connection2.writes.length = 0;  // drop the init sequence, only assert on configure() below

    // configure(2, 4, false, true, 250):
    // Clock reg CH2 (comm=0x21): CLKDIV=1,CLK=1,FS=index(250)=2 -> 0x0E
    // Setup reg CH2 (comm=0x11): MODE_NORMAL|GAIN_4(0x10)|UNIPOLAR(0x04)|BUFFERED(0x02) = 0x16
    await full.configure(2, 4, false, true, 250);
    checkTrue('configure_ch2_clock', eq(connection2.writes[0], Buffer.from([0x21, 0x0E])));
    checkTrue('configure_ch2_setup', eq(connection2.writes[1], Buffer.from([0x11, 0x16])));

    // Data Register CH2 read comm = REG_DATA|RW_READ|CH2 = 0x39.
    // code=0x8000 (32768), gain=4, unipolar -> (32768/65536)*(2.5/4) = 0.3125 V
    connection2.setRegister(0x39, [0x80, 0x00]);
    checkTrue('read_voltage_ch2_uses_own_gain', Math.abs((await full.readVoltageChannel(2)) - 0.3125) < 1e-9);

    // Channel 1 was never configured, so it must still use the ctor default
    // (gain 1, bipolar) -- unaffected by channel 2's configure() above.
    connection2.setRegister(0x38, [0xC0, 0x00]);
    checkTrue('read_voltage_ch1_unaffected_by_ch2_configure', Math.abs((await full.readVoltageChannel(1)) - 1.25) < 1e-9);

    try {
        await full.configure(3, 1, true, false, 50);
        checkTrue('configure_rejects_bad_channel', false);
    } catch (e) {
        checkTrue('configure_rejects_bad_channel', true);
    }

    try {
        await full.configure(1, 3, true, false, 50);
        checkTrue('configure_rejects_bad_gain', false);
    } catch (e) {
        checkTrue('configure_rejects_bad_gain', true);
    }

    // --- selfCalibrate: also regression-checks per-channel gain/bipolar use ---
    // setup = MODE_SELF_CAL(0x40)|GAIN_4(0x10)|UNIPOLAR(0x04)|BUFFERED(0x02) = 0x56
    // (channel 2's configured state from above, not channel 1's defaults)
    connection2.writes.length = 0;
    await full.selfCalibrate(2);
    checkTrue('self_calibrate_ch2_uses_own_state', eq(connection2.writes[0], Buffer.from([0x11, 0x56])));

    // --- systemCalibrateZero / systemCalibrateFull: mode bits, channel 1 ---
    connection2.writes.length = 0;
    await full.systemCalibrateZero(1);
    checkTrue('system_calibrate_zero', eq(connection2.writes[0], Buffer.from([0x10, 0x80])));  // MODE_ZERO_SYS|GAIN_1|BIPOLAR

    connection2.writes.length = 0;
    await full.systemCalibrateFull(1);
    checkTrue('system_calibrate_full', eq(connection2.writes[0], Buffer.from([0x10, 0xC0])));  // MODE_FULL_SYS|GAIN_1|BIPOLAR

    // --- offset / gain calibration: 24-bit read/write ---
    // Zero-Scale reg CH1 read comm = REG_OFFSET|RW_READ|CH1 = 0x68.
    connection2.setRegister(0x68, [0x12, 0x34, 0x56]);
    checkTrue('get_offset_calibration', (await full.getOffsetCalibration(1)) === 0x123456);

    connection2.writes.length = 0;
    await full.setOffsetCalibration(0xABCDEF, 1);
    checkTrue('set_offset_calibration', eq(connection2.writes[0], Buffer.from([0x60, 0xAB, 0xCD, 0xEF])));

    // Full-Scale reg CH1 read comm = REG_GAIN|RW_READ|CH1 = 0x78.
    connection2.setRegister(0x78, [0x01, 0x02, 0x03]);
    checkTrue('get_gain_calibration', (await full.getGainCalibration(1)) === 0x010203);

    connection2.writes.length = 0;
    await full.setGainCalibration(0x040506, 1);
    checkTrue('set_gain_calibration', eq(connection2.writes[0], Buffer.from([0x70, 0x04, 0x05, 0x06])));

    // --- standby / wakeup ---
    connection2.writes.length = 0;
    await full.standby();
    checkTrue('standby', eq(connection2.writes[0], Buffer.from([0x04])));  // comm(COMM,WRITE,CH1)|STBY_SLEEP

    connection2.writes.length = 0;
    await full.wakeup();
    checkTrue('wakeup_clears_stby', eq(connection2.writes[0], Buffer.from([0x00])));
    checkTrue('wakeup_waits_drdy', eq(connection2.writes[1], Buffer.from([0x08])));

    // --- reset: requires a resetPin, pulses it low then high ---
    try {
        await full.reset();
        checkTrue('reset_without_pin_raises', false);
    } catch (e) {
        checkTrue('reset_without_pin_raises', true);
    }

    const resetPin = new FakeOutputPin();
    const connection3 = new SPIConnectionMock();
    const withReset = new AD7705Full(connection3, 2.5, 4915200, resetPin);
    await tick();
    checkTrue('init_with_reset_pin_pulses', resetPin.calls.length === 2 && resetPin.calls[0] === false && resetPin.calls[1] === true);
    resetPin.calls.length = 0;
    await withReset.reset();
    checkTrue('reset_pulses_pin', resetPin.calls.length === 2 && resetPin.calls[0] === false && resetPin.calls[1] === true);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
