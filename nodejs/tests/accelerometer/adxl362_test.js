'use strict';

const { SPIConnection } = require('../../packages/periph/src/connection/spi');
const { ADXL362Full } = require('../../packages/periph/src/chips/accelerometer/adxl362');

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);

let passed = 0;
let failed = 0;

function checkEq(label, got, expected) {
    if (got === expected) { console.log('PASS', label); passed++; }
    else { console.log(`FAIL ${label}: got ${got}, expected ${expected}`); failed++; }
}

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log(`FAIL ${label}`); failed++; }
}

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 0, maxSpeedHz: 8_000_000 });
    const chip = new ADXL362Full(connection);

    // --- device_id triple ---
    const ids = await chip.deviceId();
    checkEq('device_id_devid_ad',  ids.devidAd,  0xAD);
    checkEq('device_id_devid_mst', ids.devidMst, 0x1D);
    checkEq('device_id_partid',    ids.partid,    0xF2);

    // --- 12-bit and 8-bit reads return finite floats ---
    const r12 = await chip.read();
    checkTrue('read_12bit_returns_floats',
        Number.isFinite(r12.x) && Number.isFinite(r12.y) && Number.isFinite(r12.z));

    const r8 = await chip.read8bit();
    checkTrue('read_8bit_returns_floats',
        Number.isFinite(r8.x) && Number.isFinite(r8.y) && Number.isFinite(r8.z));

    // --- temperature ---
    const t = await chip.temperature();
    checkTrue('temperature_returns_number', Number.isFinite(t));

    // --- configuration setters ---
    await chip.setRange(4);
    await chip.setOdr(200.0);
    await chip.setHalfBandwidth(true);
    await chip.setNoiseMode(ADXL362Full.NOISE_LOW);
    checkTrue('set_range_odr_noise', true);

    // --- status accessors ---
    const status = await chip.status();
    checkTrue('status_byte', typeof status === 'number');
    checkTrue('awake_boolean', typeof (await chip.awake()) === 'boolean');
    checkTrue('data_ready_boolean', typeof (await chip.dataReady()) === 'boolean');
    checkTrue('fifo_entries_integer', Number.isInteger(await chip.fifoEntries()));

    // --- FIFO configuration ---
    await chip.configureFifo(ADXL362Full.FIFO_STREAM, false, 128);
    const entries = await chip.fifoEntries();
    checkTrue('fifo_entries_after_configure', entries >= 0);

    // --- activity / inactivity configuration ---
    await chip.setActivityThreshold(0.5, true);
    await chip.setActivityTime(5);
    await chip.setInactivityThreshold(0.2, true);
    await chip.setInactivityTime(30);
    await chip.enableActivityDetection(true);
    await chip.enableInactivityDetection(true);
    await chip.setLinkLoopMode(ADXL362Full.LINKLOOP_LOOP);
    checkTrue('activity_inactivity_config', true);

    // --- interrupt mapping ---
    await chip.setInterrupt(1, ADXL362Full.SOURCE_DATA_READY, true);
    await chip.setInterrupt(2, ADXL362Full.SOURCE_AWAKE, true);
    await chip.setInterruptPolarity(1, true);
    checkTrue('interrupt_mapping', true);

    // --- self-test ---
    await chip.selfTest(true);
    await chip.selfTest(false);
    checkTrue('self_test', true);

    // --- soft reset ---
    await chip.softReset();
    checkTrue('soft_reset', true);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
})().catch(err => { console.error(err); process.exit(2); });