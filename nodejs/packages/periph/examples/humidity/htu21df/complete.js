'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { HTU21DFFull } = require('../../../src/chips/humidity/htu21df');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x40', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x40) → Connection
const sensor = new HTU21DFFull(connection);                               // Create HTU21D-F driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Soft reset, () → void
                                                                         // 15 ms power-up wait, soft reset, 15 ms
    const [t, rh] = await sensor.read();                                 // Read temperature and humidity, () → [number °C, number %RH]
                                                                         // no hold master, CRC checked, one retry on a mismatch
    console.log(t, rh);
    console.log(await sensor.readHumidity());                            // Read humidity only, () → number %RH
                                                                         // not clamped, ASIC range -6 ... 118 %RH
    console.log(await sensor.readTemperature());                         // Read temperature only, () → number °C
    console.log(await sensor.readHumidityCompensated());                 // Temperature-compensated humidity, () → number %RH
                                                                         // -0.15 %RH/°C relative to 25 °C
    console.log(await sensor.readRaw());                                 // Read raw codes, () → [number, number]
                                                                         // [temperature, humidity], status bits cleared

    await sensor.setResolution(3);                                       // Set resolution, (res) → void
                                                                         // 0 = 12/14 bit, 1 = 8/12, 2 = 10/13, 3 = 11/11
    console.log(await sensor.getResolution());                           // Get resolution, () → number
    await sensor.setResolution(0);

    await sensor.setHeater(true);                                        // Switch the heater, (on) → void
                                                                         // about 5.5 mW; readings drift while on
    console.log(await sensor.heater());                                  // Heater state, () → boolean
    await sensor.setHeater(false);
    console.log(await sensor.batteryLow());                              // End-of-battery flag, () → boolean
                                                                         // true when VDD is below about 2.25 V
    console.log((await sensor.readUserRegister()).toString(16));         // Read the user register, () → number
                                                                         // 0x02 after reset
    sensor.setHoldMaster(false);                                         // Use hold-master commands, (on) → void
                                                                         // needs bus-layer clock stretching; off polls the NACKed read header
    console.log(await sensor.dewPoint());                                // Compute dew point, () → number °C
                                                                         // one measurement
    await sensor.softReset();                                            // Soft reset, () → void
                                                                         // 0xFE, waits 15 ms; the heater bit survives
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
