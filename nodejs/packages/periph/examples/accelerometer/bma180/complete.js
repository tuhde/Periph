'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { BMA180Full } = require('../../packages/periph/src/chips/accelerometer/bma180');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x40', 16);

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const accel = new BMA180Full(connection);              // Create BMA180 full driver, (connection)
    await new Promise(r => setImmediate(r));
    await new Promise(r => setImmediate(r));

    await accel.setRange(8);                                // Set range, (range_g=8 g) → None
                                                            // selects ±8 g; LSB scale changes from 4096 to 1024 LSB/g
    await accel.setBandwidth(40);                           // Set bandwidth, (bandwidth_hz=40 Hz) → None
                                                            // picks nearest low-pass value
    await accel.setFilterMode(1);                          // Set filter mode, (mode=1 high-pass 1 Hz) → None
                                                            // bw code 1000 selects the high-pass filter
    await accel.setMode(0);                                 // Set mode, (mode=0 low-noise) → None
                                                            // mode_config in TCO_Z (0x30) bits 1:0
    await accel.setResolution(14);                          // Set resolution, (bits=14) → None
                                                            // 14-bit readout (readout_12bit cleared)

    const [rx, ry, rz] = await accel.readRaw();            // Read raw acceleration, () → [x, y, z] int counts
                                                            // burst-reads six LSB-then-MSB bytes, returns signed 14-bit counts
    const t = await accel.readTemperature();               // Read temperature, () → float °C
                                                            // reads 8-bit register, returns 25.0 + (signed - 2) * 0.5 °C
    const ready = await accel.newDataAvailable();          // Check new data, () → bool
                                                            // reads the new_data bits of the three LSB registers

    await accel.setShadow(false);                           // Set shadow, (enabled=False) → None
                                                            // shadow_dis bit in GAIN_Y (0x33) — False enforces LSB-then-MSB
    await accel.setSampleSkip(false);                      // Set sample skip, (enabled=False) → None
                                                            // smp_skip bit in OFFSET_LSB1 (0x35) bit 0

    await accel.setLowG(0.3, 40, 0.05, 0x07, 0, true);     // Configure low-g, (threshold_g=0.3, duration_ms=40, hysteresis_g=0.05, axes=0x07, counter=0, filtered=true) → None
                                                            // writes low_th, low_dur, low_hy; enables SOURCE_LOW_G
    await accel.setHighG(1.8, 20, 0.1, 0x07, 0, true);     // Configure high-g, (threshold_g=1.8, duration_ms=20, hysteresis_g=0.1, axes=0x07, counter=0, filtered=true) → None
                                                            // writes high_th, high_dur, high_hy; enables SOURCE_HIGH_G
    await accel.setSlope(0.3, 3, 0x07, true);              // Configure slope, (threshold_g=0.3, samples=3, axes=0x07, filtered=true) → None
                                                            // writes slope_th, slope_dur; enables SOURCE_SLOPE (exclusive with alert)
    await accel.setAlert(false);                            // Set alert, (enabled=False) → None
                                                            // slope_alert off; clears SOURCE_ALERT
    await accel.setTap(0.5, 250, 0x07, true);              // Configure tap, (threshold_g=0.5, window_ms=250, axes=0x07, filtered=true) → None
                                                            // writes tapsens_th, tapsens_dur; enables SOURCE_TAP

    await accel.setLatch(true);                             // Set latch, (enabled=True) → None
                                                            // lat_int bit in CTRL_REG3 (0x21) — clears by reset_int

    const flags = await accel.pollInterrupt();             // Poll interrupt, () → int
                                                            // reads STATUS_REG3 — latched flags + first-axis
    await accel.clearInterrupt();                           // Clear interrupt, () → None
                                                            // writes reset_int to CTRL_REG0 (0x0D)

    await accel.disableInterrupt(BMA180Full.SOURCE_LOW_G); // Disable interrupt, (source=SOURCE_LOW_G) → None
    await accel.disableInterrupt(BMA180Full.SOURCE_HIGH_G);// Disable interrupt, (source=SOURCE_HIGH_G) → None
    await accel.disableInterrupt(BMA180Full.SOURCE_SLOPE); // Disable interrupt, (source=SOURCE_SLOPE) → None
    await accel.disableInterrupt(BMA180Full.SOURCE_TAP);   // Disable interrupt, (source=SOURCE_TAP) → None

    await accel.enableInterrupt(BMA180Full.SOURCE_NEW_DATA); // Enable interrupt, (source=SOURCE_NEW_DATA) → None
    await accel.disableInterrupt(BMA180Full.SOURCE_NEW_DATA);// Disable interrupt, (source=SOURCE_NEW_DATA) → None

    const onInt = (status) => console.log(`INT! status=0x${status.toString(16)}`);
    accel.onInterrupt(onInt);                              // Subscribe to INT, (callback) → None
                                                            // INT pin rising → callback(onInt)
    accel.offInterrupt();                                   // Unsubscribe from INT, () → None
                                                            // detaches the handler from the INT pin

    const cd1 = await accel.readCustomer(0);               // Read customer byte, (index=0) → int
    await accel.writeCustomer(1, 0xA5);                     // Write customer byte, (index=1, value=0xA5) → None

    await accel.calibrateOffset(0x07, 1);                   // Calibrate offset, (axes=0x07, mode=1 fine) → None
                                                            // in-field zero-g calibration (volatile)

    await accel.sleep();                                    // Sleep, () → None
    await accel.wake();                                     // Wake-up, () → None

    console.log(`raw=(${rx},${ry},${rz}) temp=${t.toFixed(2)} ready=${ready} flags=0x${flags.toString(16)} cd1=0x${cd1.toString(16)}`);
}

main().catch(e => { console.error(e); process.exit(2); });