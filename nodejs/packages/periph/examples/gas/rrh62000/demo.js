'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { RRH62000Full } = require('../../../src/chips/gas/rrh62000');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x69', 16);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function iaqLabel(iaq) {
    if (iaq < 2) return 'very good';
    if (iaq < 3) return 'good';
    if (iaq < 4) return 'medium';
    if (iaq < 5) return 'poor';
    return 'bad';
}

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x69) → Connection
const sensor = new RRH62000Full(connection);                             // Create RRH62000 driver, (connection, {accessIntervalMs=1000}) → void

async function main() {
    await sensor.init();                                                 // Wait for start-up, check firmware version, () → void

    // --- Identity and settings ---
    // The module may only be accessed once per second; every call below waits
    // for that interval, so this section takes several seconds.
    console.log('firmware %s', (await sensor.firmwareVersion()).join('.'));    // Firmware version, () → [major, minor]
    console.log('algorithm %s', (await sensor.algorithmVersion()).join('.'));  // Algorithm version, () → [major, minor, patch]
    console.log('unique ID %s', (await sensor.uniqueId()).toString('hex'));    // Unique ID, () → Buffer
    console.log('moving average %d, fan %d %%, cleaning every %d s for %d s',
        await sensor.getMovingAverage(), await sensor.getFanSpeed(),           // Moving average, () → number / Fan speed, () → number %
        await sensor.getCleaningInterval(), await sensor.getCleaningTime());   // Cleaning interval, () → number s / Cleaning time, () → number s

    // --- Room air-quality station ---
    // A complete sample arrives every 3 s. The IAQ index follows the German UBA
    // scale; PM2.5 uses the KCl reference. Allow up to 48 h of conditioning on
    // first use before trusting TVOC and eCO2.
    const end = Date.now() + 30000;
    while (Date.now() < end) {
        const m = await sensor.readAll();                                // Complete block, () → object
        console.log('IAQ %s (%s)  PM2.5 %s ug/m3  eCO2 %d ppm  TVOC %d ug/m3  %s C  %s %%RH',
            m.iaq.toFixed(2), iaqLabel(m.iaq), m.pm2_5_ug_m3.toFixed(1), m.eco2_ppm, m.tvoc,
            m.temperature_c.toFixed(1), m.humidity_rh.toFixed(1));
    }

    // --- Particle counts and status ---
    // Number concentrations count particles from the bin size up to 10 µm per cm³.
    const p = await sensor.readParticles();                              // Particle values, () → object
    console.log('particles/cm3  >0.3um %s  >0.5um %s  >1um %s  >2.5um %s  >4um %s',
        p.nc_0_3.toFixed(1), p.nc_0_5.toFixed(1), p.nc_1.toFixed(1), p.nc_2_5.toFixed(1), p.nc_4.toFixed(1));
    console.log('PM2.5 KCl %s / smoke %s ug/m3', p.pm2_5_ug_m3.toFixed(1), p.pm2_5_smoke_ug_m3.toFixed(1));
    console.log('status', await sensor.readStatus());                    // Status flags, () → object
    console.log('new data pending:', await sensor.dataReady());          // New sample available, () → boolean

    // --- Quieter, smoother operation ---
    // 30 moving averages smooth the PM values (slower response); 70 % fan speed
    // is quieter.
    await sensor.setMovingAverage(30);                                   // Moving average, (count 1–60) → void
    await sensor.setFanSpeed(70);                                        // Fan speed, (percent 60–100) → void
    console.log('moving average %d, fan %d %%', await sensor.getMovingAverage(), await sensor.getFanSpeed());

    // --- Fan cleaning ---
    // A manual cleaning blows dust out of the air channel; the automatic
    // cleaning is shortened to every 3 days for 10 s.
    await sensor.startCleaning();                                        // Start fan cleaning, () → void
    for (let i = 0; i < 30; i++) {
        if (await sensor.cleaningDone()) {                               // Cleaning status, () → boolean
            console.log('cleaning done');
            break;
        }
    }
    await sensor.setCleaningInterval(3 * 24 * 3600);                     // Cleaning interval, (seconds) → void
    await sensor.setCleaningTime(10);                                    // Cleaning time, (seconds) → void
    console.log('cleaning every %d s', await sensor.getCleaningInterval());

    // --- Sleep, diagnostics, reset ---
    // Sleep stops the fan; after wake-up the first valid sample takes 3.3 s.
    await sensor.sleep();                                                // Sleep, () → void
    await sleep(5000);
    await sensor.wake();                                                 // Wake up, () → void
    console.log('MOX resistance %d ohm', await sensor.readMoxResistance());    // MOX resistance, () → number Ω
    await sensor.softReset();                                            // Reset, () → void
    console.log('after reset', await sensor.read());                     // Main values, () → object
    await connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
