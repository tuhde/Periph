'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { SHT31Full } = require('../../../src/chips/humidity/sht31');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x44', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x44) → Connection
const sensor = new SHT31Full(connection);                                // Create SHT31 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Wait for the soft reset, () → void

    const [t, rh] = await sensor.read();                                 // Read temperature and humidity, () → [number, number]
                                                                         // single shot, high repeatability, CRC checked
    console.log(t, rh);
    console.log(await sensor.readTemperature());                         // Read temperature only, () → number °C
    console.log(await sensor.readHumidity());                            // Read humidity only, () → number %RH
    console.log(await sensor.readRaw());                                 // Read raw words, () → [number, number]
                                                                         // 16-bit temperature and humidity ticks

    sensor.setRepeatability('low');                                      // Select repeatability, (level) → void
                                                                         // 'low' / 'medium' / 'high' (4 / 6 / 15 ms)
    console.log(await sensor.read());
    sensor.setRepeatability('high');
    sensor.setClockStretching(false);                                    // Use clock-stretching commands, (on) → void
                                                                         // needs bus-layer support; off polls the NACKed read header

    await sensor.startPeriodic(2, 'high');                               // Start periodic mode, (rate, repeatability='high', art=false) → void
                                                                         // rate 0.5 / 1 / 2 / 4 / 10 mps; art=true selects the 4 mps ART mode
    await sleep(1000);
    console.log(await sensor.fetch());                                   // Fetch the latest periodic result, () → [number, number] | null
                                                                         // null while the read header is NACKed
    await sensor.stopPeriodic();                                         // Stop periodic mode, () → void
                                                                         // break command 0x3093, waits 1 ms

    await sensor.setHeater(true);                                        // Switch the heater, (on) → void
                                                                         // about 5-25 mW; readings are unusable while on
    console.log((await sensor.readStatus()).toString(16));               // Read status register, () → number
                                                                         // CRC verified; bit 13 = heater on
    await sensor.setHeater(false);
    await sensor.clearStatus();                                          // Clear status flags, () → void
                                                                         // command 0x3041
    console.log(await sensor.dewPoint());                                // Compute dew point, () → number °C
                                                                         // one measurement, Magnus formula
    await sensor.softReset();                                            // Soft reset, () → void
                                                                         // 0x30A2, waits 1 ms, back to single shot
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
