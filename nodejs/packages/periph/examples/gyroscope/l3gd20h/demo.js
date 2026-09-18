const { I2CConnection } = require('../../src/connection/i2c_linux');
const { L3GD20HFull } = require('../../src/chips/gyroscope/l3gd20h');

const conn = new I2CConnection(1, 0x6A);
const gyro = new L3GD20HFull(conn);

async function main() {
    // --- Configure for shake detection at 190 Hz, ±500 dps ---
    // 190 Hz ODR provides good temporal resolution for shake detection;
    // ±500 dps full scale gives 17.5 mdps/digit sensitivity, suitable for
    // detecting moderate to strong motion without clipping.
    await gyro.configure(1, 0, 1);  // Configure, (odr 0-3, bw 0-3, fullScale 0-2)

    console.log('L3GD20H shake detector running. Shake the device...');

    while (true) {
        if (await gyro.dataReady()) {       // Check data ready, () -> bool
            const [x, y, z] = await gyro.gyro();  // Read angular rate, () -> [float, float, float] rad/s
            const magnitude = Math.sqrt(x*x + y*y + z*z);
            if (magnitude > 1.0) {
                console.log(`SHAKE DETECTED: mag=${magnitude.toFixed(3)} (x=${x.toFixed(3)} y=${y.toFixed(3)} z=${z.toFixed(3)})`);
            } else {
                console.log(`x=${x.toFixed(3)} y=${y.toFixed(3)} z=${z.toFixed(3)} mag=${magnitude.toFixed(3)}`);
            }
        }
    }
}

main().catch(console.error);