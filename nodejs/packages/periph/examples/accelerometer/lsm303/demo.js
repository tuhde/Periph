'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { LSM303Full, LSM303Source } = require('periph/src/chips/accelerometer/lsm303');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);

const accelConnection = new I2CConnection(I2C_BUS, 0x19);   // Open I²C bus, (busNumber, addr=0x19) → I2CConnection
const magConnection = new I2CConnection(I2C_BUS, 0x1E);     // Open I²C bus, (busNumber, addr=0x1E) → I2CConnection
const compass = new LSM303Full(accelConnection, magConnection);      // Create LSM303 driver, (accelConnection, magConnection) → LSM303Full

const DIRECTIONS = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE',
                    'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW'];

// --- Configure the sensor for a responsive compass ---
// 50 Hz gives a fresh sample every 20 ms without flooding the FIFO, and
// the high-resolution mode keeps 1 mg/LSB so the tilt compensation stays
// precise.
async function setup() {
    await compass.setDataRate(50);                           // Set output data rate, (rateHz=50) → Promise<boolean>
    await compass.setMode(LSM303Full.MODE_HIGH_RESOLUTION);  // Set operating mode, (mode=MODE_HIGH_RESOLUTION=2) → Promise<boolean>
    await compass.setMagRange(130);                          // Set magnetometer range, (rangeUt=130) → Promise<boolean>
    await compass.setMagDataRate(15);                        // Set magnetometer data rate, (rateHz=15) → Promise<boolean>
}

// --- Arm the gestures ---
// A double tap on the Z axis and a free-fall event both end up on INT1.
// The latch keeps the line asserted until the source register is read, so
// a short tap is never missed between two polling rounds.
async function armGestures() {
    await compass.setClick(0, 0x04, 0.8, 20, 40, 200);      // Configure click detector, (single=0, double=0x04, thresholdG=0.8, timeLimitMs=20, latencyMs=40, windowMs=200) → Promise<boolean>
    await compass.setFreeFall(1, 0.35, 60);                  // Arm free-fall, (generator=1, thresholdG=0.35, durationMs=60) → Promise<boolean>
    await compass.setLatch(1, true);                         // Latch the interrupt, (generator=1, enabled=true) → Promise<boolean>
    await compass.enableInterrupt(LSM303Source.SOURCE_CLICK, 1);  // Route an interrupt, (source=SOURCE_CLICK=0x20, pin=1) → Promise<boolean>
    await compass.enableInterrupt(LSM303Source.SOURCE_IA1, 1);    // Route an interrupt, (source=SOURCE_IA1=0x08, pin=1) → Promise<boolean>
}

// --- Collect the samples in the FIFO ---
// Stream mode keeps the newest 32 sample sets; the watermark interrupts as
// soon as 16 of them have piled up.
async function setupFifo() {
    await compass.setFifo(LSM303Full.FIFO_STREAM, 16);      // Configure FIFO, (mode=FIFO_STREAM=2, watermark=16, triggerInt2=false) → Promise<boolean>
}

(async () => {
    await setup();
    await armGestures();
    await setupFifo();

    for (let round = 0; round < 60; round++) {
        // --- Average the samples the FIFO collected ---
        // Averaging over the whole burst cancels most of the vibration noise a
        // handheld compass would otherwise show.
        let ax = 0, ay = 0, az = 0;
        const samples = await compass.readFifo(8);           // Read FIFO, (count=8) → Promise<Array<{x,y,z}>>
        if (samples.length > 0) {
            for (const s of samples) {
                ax += s.x;
                ay += s.y;
                az += s.z;
            }
            ax /= samples.length;
            ay /= samples.length;
            az /= samples.length;
        } else {
            const a = await compass.readAcceleration();      // Read acceleration, () → Promise<{x:float, y:float, z:float}> g
            ax = a.x; ay = a.y; az = a.z;
        }

        // --- Compute the heading and the tilt ---
        const heading = await compass.heading(true);          // Read the compass heading, (tiltCompensate=true) → Promise<number>
        const roll = Math.atan2(ay, az) * 180.0 / Math.PI;
        const pitch = Math.atan2(-ax, Math.sqrt(ay * ay + az * az)) * 180.0 / Math.PI;
        const mag = await compass.readMagnetic();             // Read magnetic field, () → Promise<{x:float, y:float, z:float}> uT
        const magnitude = Math.sqrt(mag.x * mag.x + mag.y * mag.y + mag.z * mag.z);
        const temperature = await compass.readTemperatureDelta();  // Read relative temperature, () → Promise<number>
        let index = Math.floor((heading + 11.25) / 22.5) % 16;
        if (index < 0) index += 16;

        console.log(`heading ${heading.toFixed(1)} deg (${DIRECTIONS[index]})  pitch ${pitch.toFixed(1)} deg  roll ${roll.toFixed(1)} deg  field ${magnitude.toFixed(1)} uT  temp ${temperature.toFixed(1)} C`);

        // --- Report the gestures ---
        const status = await compass.pollInterrupt();        // Read interrupt status, () → Promise<number>
        if (status & LSM303Source.SOURCE_CLICK) {
            console.log('DOUBLE TAP');
        }
        if (status & LSM303Source.SOURCE_IA1) {
            console.log('FREE FALL');
        }

        await new Promise(r => setTimeout(r, 1000));
    }

    await accelConnection.close();
    await magConnection.close();
})();
