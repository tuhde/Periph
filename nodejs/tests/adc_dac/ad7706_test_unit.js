'use strict';
const { SPIConnectionMock } = require('../../packages/periph/src/connection/spi_mock');
const { OutputPin } = require('../../packages/periph/src/connection/output_pin');
const { AD7706Minimal, AD7706Full } = require('../../packages/periph/src/chips/adc_dac/ad7706');

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

function tick() {
    return new Promise((resolve) => setImmediate(resolve));
}

async function main() {
    // --- AD7706Minimal ctor: Clock + Setup Register writes, self-calibrate ---
    const connection = new SPIConnectionMock();
    const sensor = new AD7706Minimal(connection, 2.5, 4915200);
    await tick();
    checkTrue('init_clock_write', eq(connection.writes[0], Buffer.from([0x20, 0x0C])));
    checkTrue('init_setup_write', eq(connection.writes[1], Buffer.from([0x10, 0x40])));
    checkTrue('init_waits_drdy', eq(connection.writes[2], Buffer.from([0x08])));

    // --- AD7706Minimal.readRaw / readVoltage: Channel 1, gain 1, bipolar ---
    connection.setRegister(0x38, [0xC0, 0x00]);
    checkTrue('read_raw', (await sensor.readRaw()) === 0xC000);
    checkTrue('read_voltage', Math.abs((await sensor.readVoltage()) - 1.25) < 1e-9);

    // --- AD7706Full: three independent channels ---
    // Regression tests for driver bugs found while writing this test: (1)
    // configure() only updated the shared _gain/_bipolar/_buffered for
    // channel 1; (2) configureClock() was hardcoded to always write
    // Channel 1's Clock Register; (3) the constructor's stray
    // `return waitDrdy(...)` made `new AD7706Minimal(...)` evaluate to a
    // Promise instead of an instance.
    const connection2 = new SPIConnectionMock();
    const full = new AD7706Full(connection2, 2.5, 4915200);
    await tick();
    connection2.writes.length = 0;

    // configure(2, gain=4, bipolar=false, buffered=true, 250 Hz):
    // Clock reg CH2 (comm=0x21) -> 0x0E, Setup reg CH2 (comm=0x11) -> 0x16.
    await full.configure(2, 4, false, true, 250);
    checkTrue('configure_ch2_clock', eq(connection2.writes[0], Buffer.from([0x21, 0x0E])));
    checkTrue('configure_ch2_setup', eq(connection2.writes[1], Buffer.from([0x11, 0x16])));

    // configure(3, gain=8, bipolar=true, buffered=false, 500 Hz):
    // Channel 3 select = CH1:CH0=11 -> ch3=0x03.
    // Clock reg CH3 (comm=0x23): CLKDIV=1,CLK=1,FS=index(500)=3 -> 0x0F
    // Setup reg CH3 (comm=0x13): MODE_NORMAL|GAIN_8(0x18)|BIPOLAR|UNBUFFERED = 0x18
    await full.configure(3, 8, true, false, 500);
    checkTrue('configure_ch3_clock', eq(connection2.writes[2], Buffer.from([0x23, 0x0F])));
    checkTrue('configure_ch3_setup', eq(connection2.writes[3], Buffer.from([0x13, 0x18])));

    // Data Register reads: CH2 comm=0x39, CH3 comm=0x3B.
    connection2.setRegister(0x39, [0x80, 0x00]);  // code=0x8000, gain=4, unipolar -> 0.3125 V
    checkTrue('read_voltage_ch2_uses_own_gain', Math.abs((await full.readVoltageChannel(2)) - 0.3125) < 1e-9);

    connection2.setRegister(0x3B, [0xE0, 0x00]);  // code=0xE000, gain=8, bipolar -> 0.234375 V
    checkTrue('read_voltage_ch3_uses_own_gain', Math.abs((await full.readVoltageChannel(3)) - 0.234375) < 1e-9);

    // Channel 1 was never configured -> still the ctor default (gain 1, bipolar).
    connection2.setRegister(0x38, [0xC0, 0x00]);
    checkTrue('read_voltage_ch1_unaffected', Math.abs((await full.readVoltageChannel(1)) - 1.25) < 1e-9);

    try {
        await full.configure(4, 1, true, false, 50);
        checkTrue('configure_rejects_bad_channel', false);
    } catch (e) {
        checkTrue('configure_rejects_bad_channel', true);
    }

    // --- selfCalibrate: also regression-checks per-channel gain/bipolar use ---
    // setup = MODE_SELF_CAL(0x40)|GAIN_8(0x18)|BIPOLAR|UNBUFFERED = 0x58
    connection2.writes.length = 0;
    await full.selfCalibrate(3);
    checkTrue('self_calibrate_ch3_uses_own_state', eq(connection2.writes[0], Buffer.from([0x13, 0x58])));

    // --- offset calibration: 24-bit read/write, channel 3 ---
    connection2.setRegister(0x6B, [0x12, 0x34, 0x56]);  // REG_OFFSET|RW_READ|CH3(0x03) = 0x6B
    checkTrue('get_offset_calibration_ch3', (await full.getOffsetCalibration(3)) === 0x123456);

    connection2.writes.length = 0;
    await full.setOffsetCalibration(0xABCDEF, 3);
    checkTrue('set_offset_calibration_ch3', eq(connection2.writes[0], Buffer.from([0x63, 0xAB, 0xCD, 0xEF])));

    // --- standby / wakeup (channel-1-only) ---
    connection2.writes.length = 0;
    await full.standby();
    checkTrue('standby', eq(connection2.writes[0], Buffer.from([0x04])));

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
    const withReset = new AD7706Full(connection3, 2.5, 4915200, resetPin);
    await tick();
    checkTrue('init_with_reset_pin_pulses', resetPin.calls.length === 2 && resetPin.calls[0] === false && resetPin.calls[1] === true);
    resetPin.calls.length = 0;
    await withReset.reset();
    checkTrue('reset_pulses_pin', resetPin.calls.length === 2 && resetPin.calls[0] === false && resetPin.calls[1] === true);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
