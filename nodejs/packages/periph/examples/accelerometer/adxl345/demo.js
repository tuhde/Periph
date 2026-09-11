'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { ADXL345Minimal } = require('periph/src/chips/accelerometer/adxl345');

(async () => {
    const bus  = parseInt(process.env.I2C_BUS || '1', 10);
    const addr = parseInt(process.env.I2C_ADDR || '0x53', 16);
    const connection = new I2CConnection(bus, addr);
    const accel = new ADXL345Minimal(connection);                                  // Create ADXL345 driver, (connection, bus_type='i2c')

    // --- 50-sample stationary tilt characterization at 10 Hz ---
    // With the sensor flat and the Z axis up, gravity should project entirely
    // onto Z. Tilting the board visibly redistributes the 1 *g* magnitude
    // across X and Y; the total vector magnitude stays near 1 *g*.
    const SAMPLES = 50;
    const PERIOD_MS = 100;

    let magMin = Infinity;
    let magMax = -Infinity;

    for (let n = 0; n < SAMPLES; n++) {
        const [x, y, z] = await accel.read();                                       // Read 3-axis acceleration, () → [float, float, float] g
        const mag = Math.sqrt(x * x + y * y + z * z);
        if (mag < magMin) magMin = mag;
        if (mag > magMax) magMax = mag;
        console.log(`${String(n).padStart(2, ' ')}  x=${x >= 0 ? '+' : ''}${x.toFixed(3)}  y=${y >= 0 ? '+' : ''}${y.toFixed(3)}  z=${z >= 0 ? '+' : ''}${z.toFixed(3)}  |a|=${mag.toFixed(3)} g`);
        await new Promise(r => setTimeout(r, PERIOD_MS));
    }

    console.log(`min |a|=${magMin.toFixed(3)} g  max |a|=${magMax.toFixed(3)} g`);

    await connection.close();
})();