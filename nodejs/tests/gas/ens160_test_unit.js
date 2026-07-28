'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { ENS160Full } = require('../../packages/periph/src/chips/gas/ens160');

const _REG_PART_ID       = 0x00;
const _REG_OPMODE        = 0x10;
const _REG_CONFIG        = 0x11;
const _REG_TEMP_IN       = 0x13;
const _REG_RH_IN         = 0x15;
const _REG_DEVICE_STATUS = 0x20;
const _REG_DATA_AQI      = 0x21;
const _REG_DATA_T        = 0x30;
const _REG_GPR_READ      = 0x48;
const _OPMODE_IDLE       = 0x01;
const _OPMODE_STANDARD   = 0x02;
const _OPMODE_DEEP_SLEEP = 0x00;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function flushMicrotasks() {
    return new Promise((resolve) => setImmediate(resolve));
}

async function main() {
    const connection = new I2CConnectionMock();
    // PART_ID read during (fire-and-forget) construction must return 0x0160 (LE: 0x60, 0x01).
    connection.setRegister(_REG_PART_ID, [0x60, 0x01]);

    const sensor = new ENS160Full(connection);
    await flushMicrotasks(); // let the fire-and-forget _init() finish before asserting on it
    checkTrue('init', true);

    const opmodeWrites = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_OPMODE);
    checkTrue('init_writes_idle_then_standard',
        opmodeWrites[0][1] === _OPMODE_IDLE && opmodeWrites[opmodeWrites.length - 1][1] === _OPMODE_STANDARD);

    // DEVICE_STATUS: NEWDAT (bit1) set, VALIDITY_FLAG (bits 3:2) = 0 (OK).
    connection.setRegister(_REG_DEVICE_STATUS, [0x02]);
    checkTrue('status_ok', (await sensor.status()) === ENS160Full.VALIDITY_OK);

    // DATA_AQI block (5 bytes): aqi=2, tvocPpb=150 (0x0096 LE), eco2Ppm=500 (0x01F4 LE).
    connection.setRegister(_REG_DATA_AQI, [2, 0x96, 0x00, 0xF4, 0x01]);

    const data = await sensor.readAirQuality();
    checkTrue('read_air_quality_aqi', data.aqi === 2);
    checkTrue('read_air_quality_tvoc', data.tvocPpb === 150);
    checkTrue('read_air_quality_eco2', data.eco2Ppm === 500);

    checkTrue('read_tvoc', (await sensor.readTvoc()) === 150);
    checkTrue('read_eco2', (await sensor.readEco2()) === 500);
    checkTrue('read_aqi', (await sensor.readAqi()) === 2);
    checkTrue('read_ethanol_aliases_tvoc', (await sensor.readEthanol()) === 150);

    await sensor.setCompensation(25.0, 50.0);
    const tempRaw = Math.round((25.0 + 273.15) * 64);
    const rhRaw = Math.round(50.0 * 512);
    checkTrue('set_compensation_temp',
        connection.registers.get(_REG_TEMP_IN) === (tempRaw & 0xFF) &&
        connection.registers.get(_REG_TEMP_IN + 1) === ((tempRaw >> 8) & 0xFF));
    checkTrue('set_compensation_rh',
        connection.registers.get(_REG_RH_IN) === (rhRaw & 0xFF) &&
        connection.registers.get(_REG_RH_IN + 1) === ((rhRaw >> 8) & 0xFF));

    // DATA_T/DATA_RH actuals (4 bytes): tempRaw=0x5202 (~55.0 degC), rhRaw=0x6400 (50.0 %RH).
    connection.setRegister(_REG_DATA_T, [0x02, 0x52, 0x00, 0x64]);
    const actuals = await sensor.readCompensationActuals();
    checkTrue('read_compensation_actuals_temp', Math.abs(actuals.tempCelsius - ((0x5202 / 64.0) - 273.15)) < 1e-9);
    checkTrue('read_compensation_actuals_rh', actuals.rhPercent === 0x6400 / 512.0);

    // GPR_READ sensor 1 (offset 0): raw=2048 -> resistance = 2**(2048/2048) = 2.0 Ohm.
    connection.setRegister(_REG_GPR_READ, [0x00, 0x08]);
    checkTrue('read_raw_resistance_1', (await sensor.readRawResistance(1)) === 2.0);

    // GPR_READ sensor 4 (offset 6): raw=4096 -> resistance = 2**(4096/2048) = 4.0 Ohm.
    connection.setRegister(_REG_GPR_READ + 6, [0x00, 0x10]);
    checkTrue('read_raw_resistance_4', (await sensor.readRawResistance(4)) === 4.0);

    let raisedInvalidSensor = false;
    try {
        await sensor.readRawResistance(2);
    } catch (e) {
        raisedInvalidSensor = true;
    }
    checkTrue('read_raw_resistance_invalid_throws', raisedInvalidSensor);

    // GET_APPVER response at GPR_READ+4: major=1, minor=2, release=3.
    connection.setRegister(_REG_GPR_READ + 4, [1, 2, 3]);
    const fw = await sensor.getFirmwareVersion();
    checkTrue('get_firmware_version', fw.major === 1 && fw.minor === 2 && fw.release === 3);

    await sensor.configureInterrupt(true, true, true, true, true);
    const configWrites = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CONFIG);
    checkTrue('configure_interrupt', configWrites[configWrites.length - 1][1] === 0x6B);

    await sensor.sleep();
    const lastWrite1 = connection.writes[connection.writes.length - 1];
    checkTrue('sleep', lastWrite1.length === 2 && lastWrite1[0] === _REG_OPMODE && lastWrite1[1] === _OPMODE_DEEP_SLEEP);

    await sensor.wake();
    const lastWrite2 = connection.writes[connection.writes.length - 1];
    checkTrue('wake', lastWrite2.length === 2 && lastWrite2[0] === _REG_OPMODE && lastWrite2[1] === _OPMODE_STANDARD);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
