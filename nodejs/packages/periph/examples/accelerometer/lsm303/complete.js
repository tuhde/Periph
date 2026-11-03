'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { LSM303Full } = require('periph/src/chips/accelerometer/lsm303');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);

const accelConnection = new I2CConnection(I2C_BUS, 0x19);   // Open I²C bus, (busNumber, addr=0x19) → I2CConnection
const magConnection = new I2CConnection(I2C_BUS, 0x1E);     // Open I²C bus, (busNumber, addr=0x1E) → I2CConnection
const compass = new LSM303Full(accelConnection, magConnection);      // Create LSM303 driver, (accelConnection, magConnection) → LSM303Full

(async () => {
    // --- Accelerometer readings ---
    const accel = await compass.readAcceleration();           // Read acceleration, () → Promise<{x:float, y:float, z:float}> g
    console.log(`accel x=${accel.x.toFixed(4)} y=${accel.y.toFixed(4)} z=${accel.z.toFixed(4)} g`);

    const rawAccel = await compass.readAccelerationRaw();    // Read raw acceleration, () → Promise<{x:int, y:int, z:int}> counts
    console.log(`raw accel ${rawAccel.x} ${rawAccel.y} ${rawAccel.z}`);

    const status = await compass.readStatus();               // Read status register, () → Promise<number> byte
    console.log(`status 0x${status.toString(16)}`);

    console.log(`new data ${await compass.newDataAvailable() ? 1 : 0}`);   // Check new data, () → Promise<boolean>

    // --- Accelerometer configuration ---
    await compass.setRange(4);                               // Set full-scale range, (rangeG=4) → Promise<boolean>
    await compass.setDataRate(50);                           // Set output data rate, (rateHz=50) → Promise<boolean>
    await compass.setMode(LSM303Full.MODE_HIGH_RESOLUTION);  // Set operating mode, (mode=MODE_HIGH_RESOLUTION=2) → Promise<boolean>
    await compass.setAxes(true, true, true);                 // Enable axes, (x=true, y=true, z=true) → Promise<void>

    // --- High-pass filter ---
    await compass.setHighPass(0, 0, false, true, false, false);  // Configure high-pass filter, (mode=0, cutoff=0, filterOutput=false, onIa1=true, onIa2=false, onClick=false) → Promise<boolean>
    console.log(`reference ${await compass.resetHighPass()}`);   // Reset high-pass filter, () → Promise<number>
    await compass.setReference(0);                           // Set reference value, (value=0) → Promise<void>

    // --- FIFO ---
    await compass.setFifo(LSM303Full.FIFO_STREAM, 16);      // Configure FIFO, (mode=FIFO_STREAM=2, watermark=16, triggerInt2=false) → Promise<boolean>
    console.log(`fifo level ${await compass.fifoLevel()}`);  // Read FIFO level, () → Promise<number>
    const fifo = await compass.readFifoStatus();             // Read FIFO status, () → Promise<{watermark:bool, overrun:bool, empty:bool, level:int}>
    console.log(`fifo wm=${fifo.watermark} ov=${fifo.overrun} empty=${fifo.empty} level=${fifo.level}`);
    const samples = await compass.readFifo(8);               // Read FIFO, (count=8) → Promise<Array<{x,y,z}>>
    console.log(`fifo samples ${samples.length}`);

    // --- Inertial interrupt generators ---
    await compass.setMotionInterrupt(1, 0x02, 0.5, 10, false);   // Configure motion interrupt, (generator=1, events=0x02, thresholdG=0.5, durationMs=10, andMode=false) → Promise<boolean>
    await compass.setFreeFall(1, 0.35, 30);                  // Arm free-fall, (generator=1, thresholdG=0.35, durationMs=30) → Promise<boolean>
    await compass.setOrientation(1, false, false);           // Configure orientation, (generator=1, position=false, fourD=false) → Promise<boolean>
    await compass.setLatch(1, true);                         // Latch the interrupt, (generator=1, enabled=true) → Promise<boolean>
    console.log(`int1 source 0x${(await compass.readInterruptSource(1)).toString(16)}`);   // Read interrupt source, (generator=1) → Promise<number>

    // --- Click detection ---
    await compass.setClick(0, 0x04, 0.8, 20, 40, 200);      // Configure click detector, (single=0, double=0x04, thresholdG=0.8, timeLimitMs=20, latencyMs=40, windowMs=200) → Promise<boolean>
    console.log(`click source 0x${(await compass.readClickSource()).toString(16)}`);   // Read click source, () → Promise<number>

    // --- Interrupt routing ---
    await compass.setInterruptPolarity(false);               // Set interrupt polarity, (activeLow=false) → Promise<void>
    await compass.enableInterrupt(LSM303Full.SOURCE_CLICK, 1);  // Route an interrupt, (source=SOURCE_CLICK=0x20, pin=1) → Promise<boolean>
    await compass.disableInterrupt(LSM303Full.SOURCE_CLICK, 1); // Remove an interrupt, (source=SOURCE_CLICK=0x20, pin=1) → Promise<boolean>

    // --- Magnetometer ---
    await compass.setMagRange(250);                          // Set magnetometer range, (rangeUt=250) → Promise<boolean>
    await compass.setMagDataRate(30);                        // Set magnetometer data rate, (rateHz=30) → Promise<boolean>
    await compass.setMagMode(LSM303Full.MAG_MODE_CONTINUOUS);  // Set magnetometer mode, (mode=MAG_MODE_CONTINUOUS=0) → Promise<boolean>
    const rawMag = await compass.readMagneticRaw();          // Read raw magnetic field, () → Promise<{x:int, y:int, z:int}> counts
    console.log(`raw mag ${rawMag.x} ${rawMag.y} ${rawMag.z}`);
    const magStatus = await compass.readMagStatus();         // Read magnetometer status, () → Promise<{drdy:bool, lock:bool}>
    console.log(`mag drdy=${magStatus.drdy} lock=${magStatus.lock}`);
    const identity = await compass.readMagIdentity();        // Read magnetometer identity, () → Promise<Buffer>
    console.log(`identity 0x${identity.toString('hex')}`);
    await compass.enableTemperature(true);                   // Enable the temperature sensor, (enabled=true) → Promise<void>
    console.log(`temperature ${(await compass.readTemperatureDelta()).toFixed(2)} C`);   // Read relative temperature, () → Promise<number>

    // --- Compass heading ---
    console.log(`heading ${(await compass.heading(true)).toFixed(1)} deg`);     // Read the compass heading, (tiltCompensate=true) → Promise<number>
    console.log(`flat heading ${(await compass.heading(false)).toFixed(1)} deg`);  // Read the compass heading, (tiltCompensate=false) → Promise<number>

    // --- Miscellaneous ---
    await compass.reboot();                                  // Reboot the accelerometer, () → Promise<void>

    await accelConnection.close();
    await magConnection.close();
})();
