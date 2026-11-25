'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { MPL3115A2Full } = require('../../packages/periph/src/chips/pressure/mpl3115a2');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x60', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const inRange = (p) => p >= 20000 && p <= 110000;

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new MPL3115A2Full(connection);
    await sensor.init();
    checkTrue('who_am_i', (await sensor.whoAmI()) === 0xC4);

    const m = await sensor.read();
    checkTrue('read_pressure_range', inRange(m.pressure_pa));
    checkTrue('read_temperature_range', m.temperature_c > -40 && m.temperature_c < 85);
    checkTrue('read_pressure_single', inRange(await sensor.readPressure()));
    const t = await sensor.readTemperature();
    checkTrue('read_temperature_single', t > -40 && t < 85);
    for (const ratio of [1, 8, 128]) {
        await sensor.setOversample(ratio);
        checkTrue('oversample_roundtrip_' + ratio, sensor.getOversample() === ratio);
        checkTrue('read_at_osr_' + ratio, inRange(await sensor.readPressure()));
    }
    await sensor.setOversample(16);
    await sensor.setSeaLevelPressure(101325);
    const alt = await sensor.readAltitude();
    checkTrue('read_altitude_range', alt > -500 && alt < 9000);
    await sensor.setMode(false);
    await sensor.startContinuous(0);
    checkTrue('is_active', await sensor.isActive());
    const c = await sensor.readContinuous();
    checkTrue('read_continuous', inRange(c.value));
    await sensor.stopContinuous();
    checkTrue('is_standby', !(await sensor.isActive()));
    await sensor.fifoSetup(MPL3115A2Full.FIFO_CIRCULAR, 4);
    await sensor.startContinuous(0);
    await sleep(5000);
    const [, , count] = await sensor.fifoStatus();
    checkTrue('fifo_collects', count >= 2);
    checkTrue('fifo_read_count', (await sensor.fifoRead()).length >= 2);
    await sensor.stopContinuous();
    await sensor.fifoSetup(MPL3115A2Full.FIFO_OFF);
    await sensor.enableInterrupt(MPL3115A2Full.SOURCE_DRDY, 1);
    await sensor.disableInterrupt(MPL3115A2Full.SOURCE_DRDY);
    await sensor.reset();
    checkTrue('read_after_reset', inRange(await sensor.readPressure()));

    await connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
