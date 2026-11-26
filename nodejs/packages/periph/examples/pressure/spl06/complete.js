'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { SPL06Full } = require('../../../src/chips/pressure/spl06');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x77', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x77) → Connection
// Default address 0x77 (SDO high or open); 0x76 with SDO low.
const sensor = new SPL06Full(connection);                                // Create SPL06 driver, (connection) → void
// Stores the connection only; no bus traffic yet.

async function main() {
    await sensor.init();                                                 // Check ID, read coefficients, apply defaults, () → void
    // ID 0x10, waits for COEF_RDY, 16x pressure / 1x external temperature.
    console.log(sensor.coefficients());                                  // Decoded calibration, () → {c0, c1, c00, c10, c01, c11, c20, c21, c30}
    // The nine factory coefficients used by every compensation.
    console.log(await sensor.read());                                    // Temperature and pressure, () → {temperature_c °C, pressure_pa Pa}
    // Command mode: one temperature, then one pressure measurement.
    console.log(await sensor.readPressure());                            // Pressure, () → number Pa
    // Measures temperature first, because the compensation needs it.
    console.log(await sensor.readTemperature());                         // Temperature, () → number °C
    // One temperature measurement only.
    const [praw, traw] = await sensor.readRaw();                         // Raw results, () → [praw, traw]
    // Signed 24-bit values from the result registers.
    console.log(sensor.compensate(praw, traw, 16, 1));                   // Compensate raw values, (praw, traw, pOversampling, tOversampling) → {temperature_c, pressure_pa}
    // Pure computation with the stored coefficients; no bus access.
    console.log(await sensor.altitude(101325));                          // Altitude, (seaLevelPa=101325) → number m
    // International barometric formula against the given sea-level pressure.

    await sensor.setPressureConfig(64, 4);                               // Pressure oversampling and rate, (oversampling=16, rate=1) → void
    // 64x oversampling, 4 background readings per second; sets P_SHIFT.
    await sensor.setTemperatureConfig(2, 4);                             // Temperature oversampling and rate, (oversampling=1, rate=1) → void
    // 2x oversampling, 4 per second; always keeps the external sensor.
    await sensor.startContinuous(true, true);                            // Background mode, (pressure=true, temperature=true) → void
    // MEAS_CTRL = 111: the chip measures both on its own.
    console.log(await sensor.readContinuous());                          // Latest background result, () → {temperature_c, pressure_pa}
    // Waits for both ready flags; does not start a measurement.
    console.log(await sensor.dataReady());                               // Ready flags, () → [pressureReady, temperatureReady]
    // MEAS_CFG.PRS_RDY and TMP_RDY.
    await sensor.stop();                                                 // Standby, () → void
    // MEAS_CTRL = 000.

    await sensor.enableFifo();                                           // FIFO on, () → void
    // Background results now queue in the 32-entry FIFO.
    await sensor.startContinuous();                                      // Background mode, (pressure=true, temperature=true) → void
    // Fill the FIFO for a second.
    await sleep(1000);
    await sensor.stop();                                                 // Standby, () → void
    // Stop adding entries.
    console.log(await sensor.fifoStatus());                              // FIFO state, () → [empty, full]
    // FIFO_STS flags.
    console.log(await sensor.readFifo());                                // Drain FIFO, () → [{kind, value}]
    // "pressure" in Pa or "temperature" in °C, in measured order.
    await sensor.flushFifo();                                            // Flush FIFO, () → void
    // Discards any remaining entries.
    await sensor.disableFifo();                                          // FIFO off, () → void
    // Results go to the result registers again.

    await sensor.enableInterrupt(SPL06Full.SOURCE_PRESSURE);             // Enable interrupt source, (source) → void
    // Pressure-ready interrupt on SDO (active low).
    console.log(await sensor.pollInterrupt());                           // Read and clear INT_STS, () → number
    // Bitmask of SOURCE_PRESSURE / SOURCE_TEMPERATURE / SOURCE_FIFO_FULL.
    await sensor.onInterrupt((status) => console.log('INT', status));    // Subscribe, (callback, intPin=null) → void
    // Uses the connection's INT pin, or a 5 ms polling timer without one.
    await sensor.read();                                                 // Trigger a measurement, () → {temperature_c, pressure_pa}
    // The pressure result raises the interrupt.
    await sleep(100);
    await sensor.offInterrupt();                                         // Unsubscribe, () → void
    // Removes this driver's handler only.
    await sensor.disableInterrupt(SPL06Full.SOURCE_PRESSURE);            // Disable interrupt source, (source) → void
    // Clears CFG_REG.INT_PRS.

    await sensor.reset();                                                // Soft reset, () → void
    // RESET = 0x09, re-reads the coefficients and restores the init defaults.
    await connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
