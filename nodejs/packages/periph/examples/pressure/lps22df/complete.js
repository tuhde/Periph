'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { LPS22DFFull } = require('../../../src/chips/pressure/lps22df');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x5C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const lps = new LPS22DFFull(connection);                     // Create LPS22DF driver, (connection, busType='i2c')

(async () => {
    await lps.configure(3, 0, false, 0, true);               // Configure chip, (odr=10 Hz, avg=4, enLpfp=false, lfpfCfg=0, bdu=true) → undefined
                                                              // writes CTRL_REG1 and CTRL_REG2
    await lps.oneshot();                                      // Trigger one-shot conversion, () → undefined
                                                              // sets power-down then ONESHOT=1, waits for data
    const p = await lps.pressure();                           // Read pressure, () → number Pa
                                                              // 24-bit two's complement, 4096 LSB/hPa → Pa
    const t = await lps.temperature();                        // Read temperature, () → number °C
                                                              // 16-bit two's complement, 100 LSB/°C
    const alt = await lps.altitude(101325.0);                 // Compute altitude, (seaLevelPa=101325.0) → number m
                                                              // barometric formula to convert pressure to metres
    await lps.softwareReset();                                // Reset chip, () → undefined
                                                              // self-clears SWRESET bit after <5 µs
    await lps.setPressureOffset(-50.0);                       // Set pressure offset, (offsetPa=-50.0) → undefined
                                                              // one-point calibration in pascals; persists in NVM
    await lps.setPressureThreshold(102000.0);                 // Set pressure threshold, (thresholdPa=102000.0) → undefined
                                                              // 15-bit unsigned; raises INT when pressure exceeds it
    await lps.configureInterrupt(false, false, true, false, true, false, false, false);  // Configure interrupt, (intHL, ppOd, drdy, drdyPls, intEn, intFWtm, intFFull, intFOvr) → undefined
                                                              // routes DRDY + pressure-threshold events to INT pin
    await lps.configurePressureEvent(true, false, false);   // Configure pressure event, (phe=true, ple=false, lir=false) → undefined
                                                              // arms high-event pressure interrupt
    await lps.autozero();                                     // Capture AUTOZERO reference, () → undefined
                                                              // current pressure becomes the zero reference
    await lps.resetReference();                              // Reset reference, () → undefined
                                                              // clears AUTOZERO/AUTOREFP and REF_P registers
    const ref = await lps.referencePressure();               // Read reference pressure, () → number Pa
    await lps.setFifoMode(LPS22DFFull.FIFO_FIFO);            // Set FIFO mode, (mode 0–5) → undefined
                                                              // selects FIFO mode; pass 0 first when switching
    await lps.setFifoWatermark(64);                          // Set FIFO watermark, (level 0–127) → undefined
                                                              // raises INT when 64 samples are buffered
    const count = await lps.fifoSampleCount();               // Read FIFO sample count, () → number
    const samples = await lps.readFifo();                    // Read FIFO samples, () → number[] Pa
                                                              // burst-reads all available pressure samples
    const src = await lps.interruptSource();                 // Read interrupt source, () → {boot_on, ia, ph, pl}
                                                              // clears register on read
    console.log(`T=${t.toFixed(2)} C, P=${p.toFixed(0)} Pa, alt=${alt.toFixed(1)} m`);
    console.log(`fifo=${samples.length}/${count}, src=${JSON.stringify(src)}`);
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();