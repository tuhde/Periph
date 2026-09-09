'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { BMP280Full } = require('../../packages/periph/src/chips/pressure/bmp280');

const _REG_CAL_START  = 0x88;
const _REG_ID         = 0xD0;
const _REG_RESET      = 0xE0;
const _REG_STATUS     = 0xF3;
const _REG_CTRL_MEAS  = 0xF4;
const _REG_CONFIG     = 0xF5;
const _REG_DATA_START = 0xF7;
const _RESET_CMD      = 0xB6;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function flushMicrotasks() {
    return new Promise((resolve) => setImmediate(resolve));
}

function preloadCalibration(connection) {
    // Spec's Data Conversion "Validation" worked example (datasheet page 23):
    // dig_T1=27504, dig_T2=26435, dig_T3=-1000, dig_P1=36477, dig_P2=-10685,
    // dig_P3=3024, dig_P4=2855, dig_P5=140, dig_P6=-7, dig_P7=15500,
    // dig_P8=-14600, dig_P9=6000. Calibration NVM is little-endian.
    connection.setRegister(_REG_CAL_START, [
        0x70, 0x6B, // dig_T1 = 27504
        0x43, 0x67, // dig_T2 = 26435
        0x18, 0xFC, // dig_T3 = -1000
        0x7D, 0x8E, // dig_P1 = 36477
        0x43, 0xD6, // dig_P2 = -10685
        0xD0, 0x0B, // dig_P3 = 3024
        0x27, 0x0B, // dig_P4 = 2855
        0x8C, 0x00, // dig_P5 = 140
        0xF9, 0xFF, // dig_P6 = -7
        0x8C, 0x3C, // dig_P7 = 15500
        0xF8, 0xC6, // dig_P8 = -14600
        0x70, 0x17, // dig_P9 = 6000
    ]);
}

function preloadData(connection) {
    // UT=519888, UP=415148 (same worked example), one 6-byte burst - unlike
    // BMP180, both ADCs come from a single read, so this mock can represent
    // the exact worked example (no shared-register aliasing).
    connection.setRegister(_REG_DATA_START, [
        0x65, 0x5A, 0xC0, // adc_P = 415148
        0x7E, 0xED, 0x00, // adc_T = 519888
    ]);
}

async function main() {
    const connection = new I2CConnectionMock();
    preloadCalibration(connection);
    preloadData(connection);

    // Construction fires _init() unawaited (fire-and-forget) - flush
    // microtasks before asserting on it.
    const sensor = new BMP280Full(connection);
    await flushMicrotasks();
    checkTrue('init', true);

    const ctrlMeasSleep = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_MEAS && w[1] === 0x24);
    checkTrue('init_writes_ctrl_meas_sleep', ctrlMeasSleep.length > 0);
    const configDefault = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CONFIG && w[1] === 0x00);
    checkTrue('init_writes_config_default', configDefault.length > 0);

    // temperature(): worked example -> T = 25.08 degC.
    checkTrue('temperature', Math.abs((await sensor.temperature()) - 25.08) < 1e-6);

    const triggerForced = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_MEAS && w[1] === 0x25);
    checkTrue('temperature_triggers_forced', triggerForced.length > 0);

    // pressure(): worked example -> p = 25767233/256/100 = 1006.5325... hPa.
    checkTrue('pressure', Math.abs((await sensor.pressure()) - 1006.5325390625) < 1e-6);

    // chipId(): expect 0x58.
    connection.setRegister(_REG_ID, [0x58]);
    checkTrue('chip_id', (await sensor.chipId()) === 0x58);

    // status(): raw status byte.
    connection.setRegister(_REG_STATUS, [0x09]);
    checkTrue('status', (await sensor.status()) === 0x09);

    // configure(osrsT=2, osrsP=3, mode=3, filter=2, tSb=4):
    // CONFIG = (4<<5)|(2<<2) = 0x88; CTRL_MEAS = (2<<5)|(3<<2)|3 = 0x4F.
    await sensor.configure(2, 3, 3, 2, 4);
    const configureConfig = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CONFIG).pop();
    const configureCtrl = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_MEAS).pop();
    checkTrue('configure_config', configureConfig[1] === 0x88);
    checkTrue('configure_ctrl_meas', configureCtrl[1] === 0x4F);

    // setOversampling(osrsT=4, osrsP=5): mode stays 3 (from configure).
    // CTRL_MEAS = (4<<5)|(5<<2)|3 = 0x97.
    await sensor.setOversampling(4, 5);
    const setOversamplingWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_MEAS).pop();
    checkTrue('set_oversampling', setOversamplingWrite[1] === 0x97);

    // setMode(1): CTRL_MEAS = (4<<5)|(5<<2)|1 = 0x95.
    await sensor.setMode(1);
    const setModeWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_MEAS).pop();
    checkTrue('set_mode', setModeWrite[1] === 0x95);

    // setFilter(3): CONFIG = (4<<5)|(3<<2) = 0x8C.
    await sensor.setFilter(3);
    const setFilterWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CONFIG).pop();
    checkTrue('set_filter', setFilterWrite[1] === 0x8C);

    // setStandby(6): CONFIG = (6<<5)|(3<<2) = 0xCC.
    await sensor.setStandby(6);
    const setStandbyWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CONFIG).pop();
    checkTrue('set_standby', setStandbyWrite[1] === 0xCC);

    // altitude(seaLevelHpa=1013.25 default): pressure() re-reads DATA_START,
    // still preloaded with the same worked-example bytes.
    const alt = await sensor.altitude();
    checkTrue('altitude_default_sea_level', Math.abs(alt - 56.07668235692459) < 1e-3);

    // seaLevelPressure(altitudeM=200)
    const slp = await sensor.seaLevelPressure(200);
    checkTrue('sea_level_pressure', Math.abs(slp - 1030.736388797547) < 1e-3);

    // reset(): writes RESET=0xB6, re-reads calibration, re-applies current
    // configuration (tSb=6, filter=3, osrsT=4, osrsP=5, mode=1 from above).
    preloadCalibration(connection);
    await sensor.reset();
    const resetWrites = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_RESET && w[1] === _RESET_CMD);
    checkTrue('reset_writes_reset_cmd', resetWrites.length > 0);
    const calReads = connection.writes.filter((w) => w.length === 1 && w[0] === _REG_CAL_START);
    checkTrue('reset_rereads_calibration', calReads.length >= 2);
    const reappliedConfig = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CONFIG).pop();
    const reappliedCtrl = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_MEAS).pop();
    checkTrue('reset_reapplies_config', reappliedConfig[1] === 0xCC);
    checkTrue('reset_reapplies_ctrl_meas', reappliedCtrl[1] === 0x95);

    // --- SPI transport (busType='spi') -------------------------------
    // Per specs/pressure/bmp280.md's SPI Register-address protocol: BMP280's
    // I2C register addresses already have bit 7 set (0x88-0xFC), so SPI
    // reads use the same reg value unmasked; only writes differ, clearing
    // bit 7 (reg & 0x7F). Preloading calibration/data at the plain REG_*
    // constants works unchanged for reads - only write-address checks need
    // the mask applied.
    const spiConnection = new I2CConnectionMock();
    preloadCalibration(spiConnection);
    preloadData(spiConnection);
    const spiSensor = new BMP280Full(spiConnection, 'spi');
    await flushMicrotasks();
    checkTrue('spi_init', true);

    const spiMaskedCtrlMeas = spiConnection.writes.some(
        (w) => w.length === 2 && w[0] === (_REG_CTRL_MEAS & 0x7F) && w[1] === 0x24);
    const spiUnmaskedCtrlMeas = spiConnection.writes.some(
        (w) => w.length === 2 && w[0] === _REG_CTRL_MEAS);
    checkTrue('spi_init_writes_ctrl_meas_masked', spiMaskedCtrlMeas);
    checkTrue('spi_init_no_unmasked_ctrl_meas_write', !spiUnmaskedCtrlMeas);

    checkTrue('spi_temperature', Math.abs((await spiSensor.temperature()) - 25.08) < 1e-6);
    checkTrue('spi_pressure', Math.abs((await spiSensor.pressure()) - 1006.5325390625) < 1e-6);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
