const { I2CConnection } = require('../../src/connection/i2c_linux');
const { L3GD20HFull } = require('../../src/chips/gyroscope/l3gd20h');

const conn = new I2CConnection(1, 0x6A);
const gyro = new L3GD20HFull(conn);

async function main() {
    await gyro.configure(1, 0, 1);  // Configure, (odr 0-3, bw 0-3, fullScale 0-2)
    // sets ODR=190 Hz, bandwidth=default, full-scale=±500 dps

    await gyro.configureHpFilter(0, 0);  // Configure HPF, (mode 0-3, cutoff 0-15)
    await gyro.enableHpFilter(true);      // Enable HPF, (enable=true)

    await gyro.configureFifo(1, 10);  // Configure FIFO, (mode 0/1/2/3/7, watermark 0-31)
    await gyro.enableFifo(true);      // Enable FIFO, (enable=true)

    await gyro.setPowerMode(L3GD20HFull.POWER_NORMAL);  // Set power mode

    const who = await gyro._readReg(0x0F, 1);
    console.log(`WHO_AM_I: 0x${who[0].toString(16).toUpperCase()}`);

    const temp = await gyro.temperature();  // Read temperature, () -> int
    console.log(`Temperature: ${temp}`);

    while (true) {
        if (await gyro.dataReady()) {       // Check data ready, () -> bool
            const [x, y, z] = await gyro.gyro();  // Read angular rate, () -> [float, float, float] rad/s
            console.log(`x=${x.toFixed(3)} y=${y.toFixed(3)} z=${z.toFixed(3)} rad/s`);

            const [rx, ry, rz] = await gyro.gyroRaw();  // Read raw, () -> [int, int, int]

            const level = await gyro.fifoLevel();  // FIFO level, () -> int
            if (level > 0) {
                const samples = await gyro.readFifo();  // Read FIFO, () -> [[float,float,float],...]
                console.log(`FIFO: ${samples.length} samples`);
            }
        }
        await new Promise(r => setTimeout(r, 10));
    }
}

main().catch(console.error);