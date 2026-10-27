'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { LSM303Full } = require('periph/src/chips/accelerometer/lsm303');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const compass = new LSM303Full(new I2CConnection(I2C_BUS, 0x19),
                               new I2CConnection(I2C_BUS, 0x1E));  // Create LSM303 Full driver, (accelConnection, magConnection)

const NAMES = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE',
               'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW'];

(async () => {
    await compass.init();
    await compass.setRange(2);                               // Set accelerometer range, (rangeG) -> g
    await compass.setMode(2);                                // Set accelerometer mode, (mode) -> None
    await compass.setDataRate(50);                           // Set accelerometer data rate, (rateHz) -> Hz
    await compass.setMagRange(130);                          // Set magnetometer range, (rangeUt) -> uT
    await compass.setMagDataRate(15);                        // Set magnetometer data rate, (rateHz) -> Hz

    await compass.setClick(0, 0x04, 0.8, 20, 40, 200);       // Configure click detection, (...)
    await compass.setFreeFall(1, 0.35, 60);                  // Configure free-fall, (...)
    await compass.setLatch(1, true);                         // Latch generator 1, (generator, enabled)
    await compass.enableInterrupt(0x0020, 1);                // Route source to INT1, (source, pin)
    await compass.enableInterrupt(0x0008, 1);                // Route source to INT1, (source, pin)
    await compass.setFifo(2, 16, false);                     // Configure FIFO, (mode, watermark, triggerInt2)

    const start = Date.now();
    let last = start;
    while (Date.now() - start < 60000) {
        if (Date.now() - last >= 1000) {
            const level = await compass.fifoLevel();         // Read FIFO level, () -> int
            const samples = await compass.readFifo();        // Drain FIFO, (count) -> list
            let ax = 0, ay = 0, az = 0;
            if (samples.length > 0) {
                for (const s of samples) { ax += s.x; ay += s.y; az += s.z; }
                ax /= samples.length; ay /= samples.length; az /= samples.length;
            } else {
                const a = await compass.readAcceleration();  // Read 3-axis acceleration, () -> {x, y, z} g
                ax = a.x; ay = a.y; az = a.z;
            }
            const m = await compass.readMagnetic();          // Read 3-axis magnetic field, () -> {x, y, z} uT
            const field = Math.sqrt(m.x * m.x + m.y * m.y + m.z * m.z);
            const pitch = Math.atan2(ax, Math.sqrt(ay * ay + az * az)) * 180 / Math.PI;
            const roll = Math.atan2(ay, az) * 180 / Math.PI;
            const hdg = await compass.heading(true);         // Compute compass heading, (tiltCompensate) -> deg
            const name = NAMES[Math.floor((hdg + 11.25) / 22.5) % 16];
            const temp = await compass.readTemperatureDelta(); // Read relative temperature, () -> float C
            console.log(`${hdg.toFixed(1)} deg ${name}  pitch=${pitch.toFixed(1)} roll=${roll.toFixed(1)} |B|=${field.toFixed(1)} uT  T=${temp.toFixed(1)} C  fifo=${level}`);
            const isr = await compass.pollInterrupt();       // Read interrupt status, () -> int
            if (isr & 0x0020) console.log('DOUBLE TAP');
            if (isr & 0x0008) console.log('FREE FALL');
            last = Date.now();
        }
        await new Promise(r => setTimeout(r, 10));
    }
    console.log('done');
})();
