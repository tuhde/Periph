'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { LC709203FFull } = require('../../../src/chips/power/lc709203f');

async function main() {
    const connection = new I2CConnection(1, 0x0B);              // Create I²C connection, (bus, addr=0x0B) → Connection
    const gauge = new LC709203FFull(connection, 1000, 1);       // Create LC709203F driver, (connection, capacityMah, profile)
    await gauge.ready;                                          // Wait for the starting flow, () → Promise<void>

    console.log(await gauge.voltage());                         // Cell voltage, () → number V
    console.log(await gauge.percent());                         // State of charge (ITE), () → number %
    console.log(await gauge.temperature());                     // Cell temperature, () → number °C
    await gauge.setTemperature(25.0);                           // Host temperature (I²C mode), (celsius) → Promise<void>
                                                                // clamped to -20...+60 °C

    console.log(await gauge.rsoc());                            // RSOC register, () → number % (0-100)
    await gauge.setRsoc(50);                                    // Force RSOC, (percent) → Promise<void>
    await gauge.initRsoc();                                     // Initial RSOC from present voltage, () → Promise<void>
    await gauge.beforeRsoc();                                   // RSOC from max voltage since reset, () → Promise<void>

    await gauge.useThermistor(3380);                            // Thermistor mode, (bConstant) → Promise<void>
    console.log(await gauge.isThermistorMode());                // Temperature source, () → boolean
    console.log(await gauge.getBConstant());                    // Thermistor B constant, () → number K
    await gauge.useI2cTemperature(25.0);                        // Back to I²C temperature mode, (celsius) → Promise<void>

    await gauge.setApa(0x19);                                   // Adjustment Pack Application, (value) → Promise<void>
    console.log(await gauge.getApa());                          // () → number
    await gauge.setApt(0x1E);                                   // Adjustment Pack Thermistor, (value) → Promise<void>
    console.log(await gauge.getApt());                          // () → number

    await gauge.setCurrentDirection(LC709203FFull.DIRECTION_AUTO);   // Current direction, (mode) → Promise<void>
                                                                // DIRECTION_AUTO / _CHARGE / _DISCHARGE
    console.log(await gauge.getCurrentDirection());             // () → number
    await gauge.setProfile(1);                                  // Battery profile 0/1, (profile) → Promise<void>
                                                                // re-initializes the chip (90 ms)
    console.log(await gauge.getProfile());                      // () → number
    console.log((await gauge.profileId()).toString(16));        // Number of the parameter, () → number
    console.log((await gauge.icVersion()).toString(16));        // IC version, () → number

    await gauge.setAlarmRsoc(20);                               // Low-RSOC alarm, (percent) → Promise<void>; 0 = off
    console.log(await gauge.getAlarmRsoc());                    // () → number %
    await gauge.setAlarmVoltage(3.5);                           // Low-voltage alarm, (volts) → Promise<void>; 0 = off
    console.log(await gauge.getAlarmVoltage());                 // () → number V
    console.log(await gauge.pollInterrupt());                   // Alarm bitmask, () → number
                                                                // ALARM_RSOC = 1, ALARM_VOLTAGE = 2
    await gauge.onInterrupt((status) => console.log('alarm', status));   // Subscribe to ALARMB, (callback, {intPin}) → Promise<void>
    await new Promise((r) => setTimeout(r, 2000));
    await gauge.offInterrupt();                                 // Unsubscribe, () → Promise<void>

    await gauge.sleep();                                        // Sleep mode, () → Promise<void>
    await gauge.wake();                                         // Wake up, () → Promise<void>
    console.log(await gauge.isSleeping());                      // Power mode, () → boolean
    await connection.close();
}

main();
