'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { HS40xxFull } = require('../../../src/chips/humidity/hs40xx');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x54', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x54) → Connection
const sensor = new HS40xxFull(connection);                               // Create HS40xx driver, (connection) → void

async function main() {
    const d = await sensor.read();                                       // Read temperature and humidity, () → Promise<{temperature_c, humidity_pct}>
                                                                         // no-hold 0xF5, 5 bytes, CRC verified; one retry on mismatch
    console.log(d.temperature_c, d.humidity_pct);
    console.log(await sensor.readTemperature());                         // Read temperature only, () → Promise<number °C>
                                                                         // no-hold 0xF3, 3 bytes
    console.log(await sensor.readHumidity());                            // Read humidity only, () → Promise<number %RH>
    console.log(await sensor.readHold());                                // Read with clock stretching, () → Promise<{temperature_c, humidity_pct}>
                                                                         // 0xE5; needs a stretching controller and SCL >= 200 kHz
    console.log(await sensor.readRaw());                                 // Read raw values, () → Promise<{raw_humidity, raw_temperature}>
                                                                         // 14-bit values, CRC verified
    await sensor.setResolution(12, 12);                                  // Set resolution, (humidityBits, temperatureBits) → Promise<void>
                                                                         // 8, 10, 12 or 14 bit; null leaves a channel unchanged
    console.log(await sensor.getResolution());                           // Get resolution, () → Promise<{humidity_bits, temperature_bits}>
    console.log(await sensor.readRegister(0x00));                        // Read a register, (reg) → Promise<number>
    await sensor.writeRegister(0x00, 0x0F);                              // Write a register, (reg, value) → Promise<void>
                                                                         // 0x0F = 14 bit for both channels (default)
    await sensor.setAlertThresholds({ rhHigh: 60, rhHighReset: 55, tempHigh: 40, tempHighReset: 38 });// Set alert thresholds, ({rhHigh, rhHighReset, rhLow, rhLowReset, tempHigh, tempHighReset, tempLow, tempLowReset}) → Promise<void>
                                                                         // %RH / °C; omitted thresholds stay unchanged
    await sensor.setAlertPolarity(true);                                 // Set ALERT polarity, (activeHigh) → Promise<void>
    await sensor.enableInterrupt(HS40xxFull.SOURCE_RH_HIGH | HS40xxFull.SOURCE_TEMP_HIGH);// Enable alert sources, (source) → Promise<void>
    await sensor.startPeriodic(1);                                       // Start periodic mode, (periodS) → Promise<void>
                                                                         // 0.5, 1 or 2.5 s
    await sleep(1500);
    console.log(await sensor.readPeriodic());                            // Fetch the latest periodic result, () → Promise<{temperature_c, humidity_pct, status}>
    console.log(await sensor.pollInterrupt());                           // Read alert status, () → Promise<number>
                                                                         // SOURCE_* bitmask
    await sensor.onInterrupt((status) => console.log('alert', status));  // Subscribe to the ALERT pin, (callback, intPin?) → Promise<void>
                                                                         // polls at the periodic interval without a pin
    await sleep(5000);
    await sensor.offInterrupt();                                         // Unsubscribe, () → Promise<void>
    await sensor.disableInterrupt(HS40xxFull.SOURCE_RH_HIGH | HS40xxFull.SOURCE_TEMP_HIGH);// Disable alert sources, (source) → Promise<void>
    await sensor.stopPeriodic();                                         // Stop periodic mode, () → Promise<void>
    console.log((await sensor.readId()).toString(16));                   // Read the 32-bit sensor ID, () → Promise<number>
    console.log(await sensor.dewPoint());                                // Compute dew point, () → Promise<number °C>
                                                                         // one measurement, Magnus formula
    await sensor.softReset();                                            // Soft reset, () → Promise<void>
                                                                         // stops periodic mode, waits 500 ms
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
