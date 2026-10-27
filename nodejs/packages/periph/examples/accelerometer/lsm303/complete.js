'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { LSM303Full } = require('periph/src/chips/accelerometer/lsm303');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const compass = new LSM303Full(new I2CConnection(I2C_BUS, 0x19),
                               new I2CConnection(I2C_BUS, 0x1E));  // Create LSM303 Full driver, (accelConnection, magConnection)

(async () => {
    await compass.init();

    // --- Accelerometer configuration ---
    await compass.setRange(8);                               // Set accelerometer range, (rangeG) -> g
    await compass.setMode(1);                                // Set accelerometer mode, (mode) -> None
    await compass.setDataRate(400);                          // Set accelerometer data rate, (rateHz) -> Hz
    await compass.setAxes(true, true, true);                 // Enable accelerometer axes, (x, y, z) -> None
    await compass.powerDown();                               // Stop accelerometer conversions, () -> None
    await compass.setDataRate(100);                          // Set accelerometer data rate, (rateHz) -> Hz
    await compass.setMode(2);                                // Set accelerometer mode, (mode) -> None
    await compass.setRange(2);                               // Set accelerometer range, (rangeG) -> g

    // --- Reads ---
    const a = await compass.readAcceleration();              // Read 3-axis acceleration, () -> {x, y, z} g
    const raw = await compass.readAccelerationRaw();         // Read raw acceleration counts, () -> {x, y, z} LSB
    const ready = await compass.newDataAvailable();          // Check new acceleration data, () -> bool
    const status = await compass.readStatus();               // Read accelerometer status, () -> int

    // --- High-pass filter ---
    await compass.setHighPass(1, 2, true, true, false, true); // Configure high-pass filter, (...)
    const ref = await compass.resetHighPass();               // Reset high-pass filter, () -> int
    await compass.setReference(0x40);                        // Set high-pass reference, (value) -> None

    // --- FIFO ---
    await compass.setFifo(1, 16, false);                     // Configure FIFO, (mode, watermark, triggerInt2) -> None
    const level = await compass.fifoLevel();                 // Read FIFO level, () -> int
    const fifoStatus = await compass.readFifoStatus();       // Read FIFO status, () -> {watermark, overrun, empty, level}
    const samples = await compass.readFifo();                // Drain FIFO, (count) -> list
    await compass.setFifo(0);                                // Configure FIFO, (mode) -> None

    // --- Inertial interrupt generators ---
    await compass.setMotionInterrupt(1, 0x3F, 0.5, 30, true); // Configure motion interrupt, (...)
    await compass.setFreeFall(1, 0.35, 30);                  // Configure free-fall, (...)
    await compass.setOrientation(1, false, false);           // Configure orientation, (...)
    await compass.setLatch(1, true);                         // Latch generator 1, (generator, enabled) -> None
    const src = await compass.readInterruptSource(1);        // Read interrupt source, (generator) -> int
    await compass.setLatch(1, false);                        // Latch generator 1, (generator, enabled) -> None

    // --- Click detection ---
    await compass.setClick(0x01, 0x04, 0.5, 10, 20, 50);     // Configure click detection, (...)
    const click = await compass.readClickSource();           // Read click source, () -> int

    // --- Interrupt routing and delivery ---
    await compass.setInterruptPolarity(false);               // Set interrupt polarity, (activeLow) -> None
    await compass.enableInterrupt(0x0001, 1);                // Enable interrupt source, (source, pin) -> None
    await compass.enableInterrupt(0x0020, 1);                // Enable interrupt source, (source, pin) -> None
    await compass.disableInterrupt(0x0001, 1);               // Disable interrupt source, (source, pin) -> None
    await compass.onInterrupt(status => console.log('interrupt status: 0x' + status.toString(16)), 1); // Subscribe to INT1, (callback, pin)
    const isr = await compass.pollInterrupt();               // Poll interrupt status, () -> int
    await compass.offInterrupt(1);                           // Unsubscribe from INT1, (pin) -> None

    // --- Magnetometer ---
    await compass.setMagRange(470);                          // Set magnetometer range, (rangeUt) -> uT
    await compass.setMagDataRate(30);                        // Set magnetometer data rate, (rateHz) -> Hz
    await compass.setMagMode(1);                             // Set magnetometer mode, (mode) -> None
    await compass.setMagMode(0);                             // Set magnetometer mode, (mode) -> None
    const mraw = await compass.readMagneticRaw();            // Read raw magnetic counts, () -> {x, y, z} LSB
    const m = await compass.readMagnetic();                  // Read 3-axis magnetic field, () -> {x, y, z} uT
    const mready = await compass.magDataReady();             // Check new magnetometer data, () -> bool
    const mstatus = await compass.readMagStatus();           // Read magnetometer status, () -> {drdy, lock}
    const id = await compass.readMagIdentity();              // Read magnetometer identity, () -> Buffer
    await compass.enableTemperature(true);                   // Enable temperature sensor, (enabled) -> None
    const temp = await compass.readTemperatureDelta();       // Read relative temperature, () -> float C
    const heading = await compass.heading(true);             // Compute compass heading, (tiltCompensate) -> deg
    const flat = await compass.heading(false);               // Compute compass heading, (tiltCompensate) -> deg

    // --- System ---
    await compass.reboot();                                  // Reboot accelerometer memory, () -> None

    console.log('accel:', a, 'raw:', raw, 'ready:', ready, 'status: 0x' + status.toString(16));
    console.log('reference: 0x' + ref.toString(16), 'fifo:', level, fifoStatus, 'samples:', samples.length);
    console.log('src: 0x' + src.toString(16), 'click: 0x' + click.toString(16), 'isr: 0x' + isr.toString(16));
    console.log('mag:', m, 'raw:', mraw, 'ready:', mready, mstatus);
    console.log('id:', [...id].map(b => b.toString(16).padStart(2, '0')).join(' '));
    console.log('temp:', temp, 'heading:', heading, 'flat:', flat);
})();
