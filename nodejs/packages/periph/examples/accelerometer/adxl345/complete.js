'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { ADXL345Full } = require('periph/src/chips/accelerometer/adxl345');

(async () => {
    const bus  = parseInt(process.env.I2C_BUS || '1', 10);
    const addr = parseInt(process.env.I2C_ADDR || '0x53', 16);
    const connection = new I2CConnection(bus, addr);
    const accel = new ADXL345Full(connection);                                     // Create ADXL345 Full driver, (connection, bus_type='i2c')

    await accel.setRange(4);                                                       // Set measurement range, (range_g) → g
                                                                                       // selects ±4 g; FULL_RES is preserved so scale stays 3.9 mg/LSB
    await accel.setDataRate(200);                                                  // Set output data rate, (rate_hz) → Hz
                                                                                       // picks the nearest supported value (200 Hz)
    await accel.setLowPower(false);                                                // Set low-power mode, (enabled) → None
                                                                                       // normal-power mode; LOW_POWER bit in BW_RATE cleared
    await accel.calibrateOffset(0, 0, 1, 64);                                      // Calibrate offsets, (target_x=0 g, target_y=0 g, target_z=1 g, samples=64) → None g
                                                                                       // averages 64 samples with Z axis up and writes OFSX/OFSY/OFSZ
    await accel.setTapDetection(0.5, 10);                                          // Configure single-tap, (threshold_g, duration_ms, axes=0x07, suppress=false) → None g, ms
                                                                                       // 0.5 g threshold, 10 ms duration, all axes, no suppress
    await accel.setDoubleTap(50, 200);                                             // Configure double-tap, (latency_ms, window_ms) → None ms, ms
                                                                                       // 50 ms latency, 200 ms window between taps
    await accel.setFifoMode(ADXL345Full.FIFO_STREAM, 16);                           // Configure FIFO, (mode, samples=16) → None
                                                                                       // stream mode, watermark 16 entries
    await accel.setInterrupt(ADXL345Full.INT_WATERMARK, true, 1);                  // Configure interrupt, (source, enabled, pin=1) → None
                                                                                       // enable watermark interrupt on INT1

    const [x, y, z] = await accel.read();                                           // Read 3-axis acceleration, () → [float, float, float] g
                                                                                       // single-shot burst read of all 6 data bytes
    const samples = await accel.readFifo();                                         // Drain the FIFO, () → [[float, float, float]] g
                                                                                       // up to 32 (x, y, z) samples in *g*
    const count = await accel.fifoCount();                                          // FIFO entries available, () → int
                                                                                       // from FIFO_STATUS register
    const src = await accel.readInterruptSource();                                  // Read interrupt source, () → int
                                                                                       // bitmask of active INT_* sources; clears latches

    await accel.selfTest(false);                                                    // Toggle self-test, (enabled) → None
                                                                                       // SELF_TEST bit in DATA_FORMAT cleared
    await accel.setSleep(false);                                                    // Set sleep mode, (enabled, wakeup_hz=8) → None Hz
                                                                                       // wake up; no further state changes
    await accel.setLinkMode(false);                                                 // Set activity/inactivity link, (enabled) → None
                                                                                       // Link bit in POWER_CTL cleared
    await accel.setAutoSleep(false);                                                // Set auto-sleep, (enabled) → None
                                                                                       // AUTO_SLEEP bit cleared

    console.log(`x=${x.toFixed(3)} y=${y.toFixed(3)} z=${z.toFixed(3)} g`);
    console.log(`fifo_count=${count} interrupts=0x${src.toString(16).padStart(2, '0')}`);
    console.log(`samples=${samples.length}`);

    await connection.close();
})();