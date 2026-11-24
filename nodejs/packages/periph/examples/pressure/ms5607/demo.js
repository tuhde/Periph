'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { MS5607Full } = require('../../../src/chips/pressure/ms5607');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x77', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x77) → Connection
const sensor = new MS5607Full(connection);                                      // Create MS5607 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Reset, read PROM, verify CRC-4, () → void

    // --- Calibration data ---
    // Six coefficients and a CRC-4 are factory-programmed into the PROM.
    console.log('PROM', (await sensor.readProm()).map((w) => '0x' + w.toString(16)));  // Read the PROM words, () → number[8]
    console.log('CRC ok', sensor.promCrcOk());                           // Verify the PROM CRC-4, () → boolean
    console.log('coefficients', sensor.coefficients());                  // Calibration coefficients, () → [c1 .. c6]
    console.log('serial', sensor.serialCode());                          // Serial code, () → number

    // --- Barometric altimeter / variometer: 20 s, one reading per second ---
    const reference = await sensor.readPressure();                       // Read pressure only, () → number Pa
    let previous = null;
    let lowest = 100.0;
    for (let i = 0; i < 20; i++) {
        const m = await sensor.read();                                   // Read temperature and pressure, () → {temperature_c: number °C, pressure_pa: number Pa}
        lowest = Math.min(lowest, m.temperature_c);
        const height = 44330.0 * (1.0 - Math.pow(m.pressure_pa / reference, 1.0 / 5.255));
        const climb = previous === null ? 0.0 : height - previous;
        previous = height;
        console.log('%s C  %s Pa  %s m  %s m/s', m.temperature_c.toFixed(2), m.pressure_pa.toFixed(1), height.toFixed(2), climb.toFixed(2));
        await sleep(1000);
    }
    console.log('altitude vs standard sea level %s m', (await sensor.altitude()).toFixed(1));  // Altitude from pressure, (seaLevelPa=101325) → number m

    // --- Noise and time versus oversampling: ten readings each ---
    for (const osr of [256, 512, 1024, 2048, 4096]) {
        sensor.setOversampling(osr);                                     // Select oversampling ratio, (osr) → void
        const start = Date.now();
        const values = [];
        for (let n = 0; n < 10; n++) values.push(await sensor.readPressure());
        console.log('OSR %d  %s ms/read  spread %s Pa', osr, ((Date.now() - start) / 10).toFixed(1), (Math.max(...values) - Math.min(...values)).toFixed(1));
    }
    sensor.setOversampling(4096);

    // --- Raw codes with and without the second-order correction ---
    const [d1, d2] = await sensor.readRaw();                             // Read raw ADC codes, () → [number, number]
    console.log('raw', d1, d2);
    console.log('first order ', sensor.compensate(d1, d2, false));       // Compensate raw codes, (d1, d2, secondOrder) → {temperature_c, pressure_pa}
    console.log('second order', sensor.compensate(d1, d2));
    console.log('lowest temperature seen %s C', lowest.toFixed(2));

    // --- Non-blocking conversion ---
    await sensor.startConversion(MS5607Full.PRESSURE);                   // Start a conversion, (kind, osr?) → void
    await sleep(sensor.conversionTime() * 1000 + 1);                     // Max conversion time, (osr?) → number s
    console.log('D1', await sensor.readAdc());                           // Read the ADC result, () → number

    // --- Finish with a reset ---
    await sensor.reset();                                                // Reset and reload PROM, () → void
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
