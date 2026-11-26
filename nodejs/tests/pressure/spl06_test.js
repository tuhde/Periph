'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { SPL06Full } = require('../../packages/periph/src/chips/pressure/spl06');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x77', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const inRange = (p) => p >= 30000 && p <= 110000;

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new SPL06Full(connection);
    await sensor.init();
    const co = sensor.coefficients();
    checkTrue('coefficients_decoded', Object.keys(co).length === 9 && Object.values(co).some((v) => v !== 0));

    const m = await sensor.read();
    checkTrue('read_pressure_range', inRange(m.pressure_pa));
    checkTrue('read_temperature_range', m.temperature_c > -40 && m.temperature_c < 85);
    checkTrue('read_pressure_single', inRange(await sensor.readPressure()));
    const t = await sensor.readTemperature();
    checkTrue('read_temperature_single', t > -40 && t < 85);
    const [praw, traw] = await sensor.readRaw();
    checkTrue('compensate_raw', inRange(sensor.compensate(praw, traw, 16, 1).pressure_pa));
    const alt = await sensor.altitude();
    checkTrue('altitude_range', alt > -500 && alt < 9000);
    for (const osr of [1, 8, 128]) {
        await sensor.setPressureConfig(osr, 1);
        checkTrue(`read_at_osr_${osr}`, inRange(await sensor.readPressure()));
    }
    await sensor.setPressureConfig(16, 8);
    await sensor.setTemperatureConfig(1, 8);
    await sensor.startContinuous();
    checkTrue('read_continuous', inRange((await sensor.readContinuous()).pressure_pa));
    await sleep(300);
    checkTrue('data_ready', (await sensor.dataReady())[0]);
    await sensor.stop();
    await sensor.flushFifo();
    await sensor.enableFifo();
    await sensor.startContinuous();
    await sleep(1000);
    await sensor.stop();
    checkTrue('fifo_not_empty', !(await sensor.fifoStatus())[0]);
    const entries = await sensor.readFifo();
    checkTrue('fifo_read', entries.length >= 4);
    checkTrue('fifo_kinds', entries.every((e) => e.kind === 'pressure' || e.kind === 'temperature'));
    checkTrue('fifo_values', entries.filter((e) => e.kind === 'pressure').every((e) => inRange(e.value)));
    checkTrue('fifo_empty_after_read', (await sensor.fifoStatus())[0]);
    await sensor.disableFifo();
    await sensor.enableInterrupt(SPL06Full.SOURCE_PRESSURE);
    await sensor.pollInterrupt();
    await sensor.read();
    checkTrue('poll_interrupt_pressure', ((await sensor.pollInterrupt()) & SPL06Full.SOURCE_PRESSURE) !== 0);
    await sensor.disableInterrupt(SPL06Full.SOURCE_PRESSURE);
    await sensor.reset();
    checkTrue('read_after_reset', inRange(await sensor.readPressure()));

    await connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
