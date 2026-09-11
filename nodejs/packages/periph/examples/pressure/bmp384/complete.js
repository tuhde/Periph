'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { BMP384Full }    = require('../../../src/chips/pressure/bmp384');

async function main() {
    const connection = new I2CConnection(1, 0x76);
    const bmp = new BMP384Full(connection);                     // Create BMP384 driver, (connection, busType='i2c')

    await bmp.configure(4, 1, 2, 0x03);                        // Configure ADC and IIR filter, (osrP 0–5, osrT 0–5, iirFilter 0–7, odrSel 0x00–0x11) → Promise<void>
                                                                // sets oversampling, IIR coefficient, and output data rate
    await bmp.setMode(BMP384Full.MODE_NORMAL);                  // Set power mode, (mode 0/1/3) → Promise<void>
    const ready = await bmp.isDataReady();                      // Check data-ready flag, () → Promise<bool>
                                                                // true if STATUS.drdy_press is set
    const t = await bmp.temperature();                          // Read temperature, () → Promise<float> °C
    const p = await bmp.pressure();                             // Read pressure, () → Promise<float> hPa
    const reading = await bmp.read();                           // Read both values in one burst, () → Promise<{pressure, temperature}>
    const forced = await bmp.readForced();                      // Trigger forced measurement and read, () → Promise<{pressure, temperature}>
    await bmp.fifoConfigure(true, true, 64);                   // Configure FIFO, (pressEn bool, tempEn bool, wtm 0–511, stopOnFull=false) → Promise<void>
                                                                // enables FIFO, sets watermark, arms pressure+temperature frames
    const frames = await bmp.fifoRead();                        // Read and parse FIFO frames, () → Promise<Array<{type, value}>>
    await bmp.fifoFlush();                                      // Flush FIFO contents, () → Promise<void>
    await bmp.softreset();                                      // Soft reset chip, () → Promise<void>
                                                                // writes 0xB6 to CMD, waits 2 ms, re-reads calibration

    console.log(`T=${t.toFixed(1)} C, P=${p.toFixed(1)} hPa, ready=${ready}, frames=${frames.length}`);
    await connection.close();
}

main().catch((e) => { console.error(e); process.exit(1); });
