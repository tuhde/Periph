'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { CCS811Full } = require('../../packages/periph/src/chips/gas/ccs811');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x5A', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const eco2Ok = (v) => v >= 400 && v <= 8192;

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new CCS811Full(connection);
    await sensor.init();
    const [hwId, hwVersion] = await sensor.hardwareVersion();
    checkTrue('hardware_id', hwId === 0x81);
    checkTrue('hardware_version', (hwVersion & 0xF0) === 0x10);
    const fw = await sensor.firmwareVersion();
    checkTrue('firmware_version', fw.app[0] >= 1);
    const st = await sensor.status();
    checkTrue('status_app_mode', (st & CCS811Full.STATUS_FW_MODE) !== 0);
    checkTrue('status_no_error', (st & CCS811Full.STATUS_ERROR) === 0);

    const m = await sensor.read();
    checkTrue('read_eco2_range', eco2Ok(m.eco2_ppm));
    checkTrue('read_tvoc_range', m.tvoc_ppb >= 0 && m.tvoc_ppb <= 1187);
    checkTrue('read_eco2_single', eco2Ok(await sensor.readEco2()));
    const tvoc = await sensor.readTvoc();
    checkTrue('read_tvoc_single', tvoc >= 0 && tvoc <= 1187);
    const [current, voltage] = await sensor.readRaw();
    checkTrue('read_raw_current', current >= 0 && current <= 63);
    checkTrue('read_raw_voltage', voltage >= 0 && voltage <= 1.65);
    checkTrue('data_ready_cleared', !(await sensor.dataReady()));
    await sensor.setEnvironment(50, 25);
    await sensor.setThresholds(1000, 2000, 50);
    const [vRef, vNtc] = await sensor.readNtc();
    checkTrue('read_ntc', vRef >= 0 && vNtc >= 0);
    await sensor.setBaseline(await sensor.getBaseline());
    checkTrue('baseline_roundtrip', (await sensor.error()) === 0);

    await sensor.enableInterrupt(CCS811Full.SOURCE_DATA_READY);
    await sleep(1200);
    checkTrue('poll_interrupt_data_ready', ((await sensor.pollInterrupt()) & CCS811Full.STATUS_DATA_READY) !== 0);
    await sensor.read();
    await sensor.disableInterrupt(CCS811Full.SOURCE_DATA_READY);
    await sensor.setMode(4);
    await sleep(600);
    checkTrue('mode_4_raw', (await sensor.readRaw())[0] <= 63);
    await sensor.setMode(0);
    await sensor.setMode(1);
    await sensor.softReset();
    checkTrue('status_after_reset', ((await sensor.status()) & CCS811Full.STATUS_FW_MODE) !== 0);
    checkTrue('read_after_reset', eco2Ok((await sensor.read()).eco2_ppm));

    await connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
