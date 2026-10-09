'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { BMA150Full }    = require('periph/src/chips/accelerometer/bma150');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x38', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const accel = new BMA150Full(connection);                  // Create BMA150 Full driver, (connection)

(async () => {
    await accel.setRange(8);                              // Set measurement range, (range_g) → g
                                                          // selects ±8 g; LSB scale changes from 256 to 64 LSB/g
    await accel.setBandwidth(190);                        // Set bandwidth, (bandwidthHz) → Hz
                                                          // picks nearest valid value (190 Hz)
    const raw = await accel.readRaw();                    // Read raw 10-bit counts, () → [int, int, int]
                                                          // signed 10-bit two's-complement acceleration counts
    const temp = await accel.readTemperature();           // Read temperature, () → °C
                                                          // 0.5 °C/LSB, 0x00 maps to −30 °C
    const ready = await accel.newDataAvailable();         // Check new data, () → bool
                                                          // True once all three new_data_X/Y/Z bits are set
    await accel.setShadow(false);                         // Set shadow mode, (enabled) → None
                                                          // keep LSB-then-MSB ordering (shadow_dis=0)

    await accel.setLowG(0.4, 40);                        // Configure low-g, (thresholdG, durationMs, hysteresisG=0, counter=0) → g, ms
                                                          // 0.4 g threshold, 40 ms duration; enables SOURCE_LOW_G
    await accel.setHighG(4.0, 2);                        // Configure high-g, (thresholdG, durationMs, hysteresisG=0, counter=0) → g, ms
                                                          // 4.0 g threshold, 2 ms duration; enables SOURCE_HIGH_G
    await accel.setAnyMotion(0.5, 3);                     // Configure any-motion, (thresholdG, samples=1) → g, samples
                                                          // 0.5 g threshold, 3 consecutive samples; enables SOURCE_ANY_MOTION
    await accel.setAlert(false);                          // Toggle alert mode, (enabled) → None
                                                          // mutually exclusive with any-motion; not used here
    await accel.setLatch(true);                           // Set latched interrupts, (enabled) → None
                                                          // latched until clearInterrupt(); latch_INT=1

    const status = await accel.pollInterrupt();           // Read STATUS, () → bitmask
                                                          // STATUS byte; does not clear latched bits
    await accel.clearInterrupt();                         // Clear latched interrupts, () → None
                                                          // writes reset_INT to CTRL (cleared on next sample)
    await accel.setWakeUp(true, 80);                     // Set self-wake-up, (enabled, pauseMs=20) → ms
                                                          // 80 ms sleep portion of the cycle

    const xyz = await accel.read();                       // Read 3-axis acceleration, () → [number, number, number] g
                                                          // burst read of 0x02–0x07, scale 64 LSB/g
    const [al, ml] = await accel.readVersion();           // Read version, () → [int, int]
                                                          // (al_version, ml_version) from VERSION register
    const c1 = await accel.readCustomer(0);               // Read scratch byte, (index) → byte
                                                          // 0 → CUSTOMER_1, 1 → CUSTOMER_2
    await accel.writeCustomer(0, 0xA5);                  // Write scratch byte, (index, value) → None
                                                          // 0xA5 into CUSTOMER_1
    const st = await accel.selfTest();                    // Run self-test, () → bool
                                                          // electrostatic self-test; reads STATUS.st_result
    await accel.softReset();                              // Soft reset, () → None
                                                          // CTRL.soft_reset=1; 30 ms wait; range/bandwidth restored
    await accel.sleep();                                  // Enter sleep mode, () → None
    await accel.wake();                                   // Leave sleep mode, () → None
                                                          // 1.5 ms settle wait

    console.log(`raw=${JSON.stringify(raw)} temp=${temp.toFixed(1)} status=0x${status.toString(16)} al=${al} ml=${ml} c1=0x${c1.toString(16)} st=${st ? 'PASS' : 'FAIL'} xyz=[${xyz.map(v => v.toFixed(3)).join(', ')}]`);
    await connection.close();
})();
