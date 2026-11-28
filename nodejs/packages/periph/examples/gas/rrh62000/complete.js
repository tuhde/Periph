'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { RRH62000Full } = require('../../../src/chips/gas/rrh62000');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x69', 16);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x69) → Connection
// The module has the fixed address 0x69; SEL (pin 5) must be at GND for I²C.
const sensor = new RRH62000Full(connection);                             // Create RRH62000 driver, (connection, {accessIntervalMs=1000}) → void
// Starts the start-up timers: I²C after 1 s, valid data after 3.3 s.

async function main() {
    await sensor.init();                                                 // Wait for start-up, check firmware version, () → void
    // Reads the firmware version as a presence check.

    console.log(await sensor.firmwareVersion());                         // Firmware version, () → [major, minor]
    // Every access is at least 1 s after the previous one (tRD); the driver waits.
    console.log(await sensor.algorithmVersion());                        // TVOC algorithm version, () → [major, minor, patch]
    // Version of the ZMOD4410 algorithm in the module.
    console.log((await sensor.uniqueId()).toString('hex'));              // Unique ID, () → Buffer (6)
    // Identifies the individual module.

    console.log(await sensor.read());                                    // Main values, () → {pm1_ug_m3, pm2_5_ug_m3, pm10_ug_m3, temperature_c, humidity_rh, tvoc, eco2_ppm, iaq}
    // Waits for DATA, reads the 37-byte block, checks the CRC; PM uses the KCl reference.
    console.log(await sensor.readAll());                                 // Complete block, () → object
    // Status flags, 5 number-concentration bins (cm⁻³), PM KCl and smoke, T, RH, TVOC, eCO₂, IAQ.
    console.log(await sensor.readStatus());                              // Status flags, () → {raw, high_concentration, dust_accumulation, fan_speed, fan}
    // Reads the current block without waiting for new data.
    console.log(await sensor.readParticles());                           // Particle values, () → object
    // PM1/2.5/10 (KCl and cigarette smoke, µg/m³) and the number bins (cm⁻³).
    console.log(await sensor.dataReady());                               // New sample available, () → boolean
    // DATA (0x40); a sample arrives every 3 s.

    await sensor.setMovingAverage(20);                                   // Moving average, (count 1–60) → void
    // More averages: smoother values, slower response (24 s at 10).
    console.log(await sensor.getMovingAverage());                        // Moving average, () → number
    // 20.
    await sensor.setFanSpeed(80);                                        // Fan speed, (percent 60–100) → void
    // Lower speed is quieter; the default is 86 %.
    console.log(await sensor.getFanSpeed());                             // Fan speed, () → number %
    // 80.
    await sensor.setCleaningInterval(3 * 24 * 3600);                     // Cleaning interval, (seconds, multiple of 30) → void
    // Written as a 16-bit count of 30 s units, high byte first.
    console.log(await sensor.getCleaningInterval());                     // Cleaning interval, () → number s
    // 259200.
    await sensor.setCleaningTime(15);                                    // Cleaning time, (seconds 0–60) → void
    // How long the fan runs at full speed during the automatic cleaning.
    console.log(await sensor.getCleaningTime());                         // Cleaning time, () → number s
    // 15.

    await sensor.startCleaning();                                        // Start fan cleaning, () → void
    // Blows dust out of the air channel.
    await sleep(15000);
    console.log(await sensor.cleaningDone());                            // Cleaning status, () → boolean
    // CSTATUS (0x74): true when completed.
    console.log(await sensor.readMoxResistance());                       // MOX resistance, () → number Ω
    // Raw resistance of the TVOC sensing element.

    await sensor.sleep();                                                // Sleep, () → void
    // Stops the fan and the measurement.
    await sleep(5000);
    await sensor.wake();                                                 // Wake up, () → void
    // The next access waits 1 s, the next measurement 3.3 s.
    await sensor.softReset();                                            // Reset, () → void
    // Same as power-on: restarts the start-up timers.
    console.log(await sensor.read());                                    // Main values, () → object
    // First sample after the reset.
    await connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
