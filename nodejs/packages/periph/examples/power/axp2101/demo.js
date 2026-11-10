'use strict';

// Demo for the AXP2101 — battery and power-rail monitor.
// Prints the power-on source and every rail, programs a 4.2 V / 500 mA charge profile, arms six
// interrupts and then prints one status line per second (plus an event line per interrupt) for 120 s.

const { I2CConnection } = require('periph/src/connection/i2c');
const { AXP2101Full }   = require('periph/src/chips/power/axp2101');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x34', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const chip = new AXP2101Full(connection);                      // Create AXP2101 Full driver, (connection)

const EVENTS = [
    [AXP2101Full.SOURCE_VBUS_INSERT, 'VBUS inserted'],
    [AXP2101Full.SOURCE_VBUS_REMOVE, 'VBUS removed'],
    [AXP2101Full.SOURCE_CHARGE_START, 'charging started'],
    [AXP2101Full.SOURCE_CHARGE_DONE, 'charging done'],
    [AXP2101Full.SOURCE_PWRON_SHORT, 'power key short press'],
    [AXP2101Full.SOURCE_SOC_WARN_L2, 'battery warning level 2'],
];
const RAILS = ['dcdc1', 'dcdc2', 'dcdc3', 'dcdc4', 'aldo1', 'aldo2', 'aldo3', 'aldo4',
               'bldo1', 'bldo2', 'cpusldo', 'dldo1', 'dldo2'];

(async () => {
    // --- Report why the board started and what every rail is doing ---
    // eFuse defaults differ per product, so the rails are read, never assumed.
    const src = await chip.powerOnSource();                    // Read power-on flags, () → number
    console.log('power-on source: 0x' + src.toString(16).padStart(2, '0'));
    for (const name of RAILS) {
        const [enabled, volts] = await chip.getRail(name);     // Read rail state, (name) → [boolean, number V]
        console.log(`${name.padEnd(8)} ${enabled ? 'on ' : 'off'} ${volts.toFixed(2)} V`);
    }

    // --- Program a conservative 4.2 V / 500 mA charge profile ---
    // 4.2 V suits a standard Li-ion cell; 500 mA keeps heating low; the 1.5 A input
    // limit lets a USB charger feed both the system and the charger.
    await chip.setChargeVoltage(4.2);                          // Set charge target, (volts 4.0/4.1/4.2/4.35/4.4) → void
    await chip.setChargeCurrent(0.5);                          // Set charge current, (amps 0–1.5) → void
    await chip.setInputCurrentLimit(1.5);                      // Set input current limit, (amps 0.1/0.5/0.9/1.0/1.5/2.0) → void
    await chip.setTermination(0.05);                           // Set termination current, (amps 0–0.2, enabled=true) → void

    // --- Arm low-battery warnings and the power events of interest ---
    // The warning levels are 15 % and 5 %; the six sources below raise the IRQ pin.
    await chip.configureBatteryWarning(15, 5);                 // Set warning levels, (level2=10 %, level1=1 %) → void
    for (const [mask] of EVENTS) {
        await chip.enableInterrupt(mask);                      // Enable interrupt source, (source) → void
    }
    await chip.pollInterrupt();                                // Clear stale status, () → number

    // --- Monitor for 120 s ---
    // One status line per second; pollInterrupt() returns and clears every pending event.
    for (let i = 0; i < 120; i++) {
        const d = await chip.readAll();                        // Read all measurements, () → object
        const direction = (await chip.status()).batteryDirection; // Read status flags, () → object
        console.log(`${d.batteryVoltage.toFixed(3)} V ${String(d.batteryPercent).padStart(3)} %  ` +
            `vbus ${d.vbusPresent} ${d.vbusVoltage.toFixed(2)} V  sys ${d.systemVoltage.toFixed(2)} V  ` +
            `die ${d.dieTemperature.toFixed(1)} C  state ${d.chargeState}  ${direction}`);
        const events = await chip.pollInterrupt();             // Read and clear events, () → number
        for (const [mask, text] of EVENTS) {
            if (events & mask) console.log('event: ' + text);
        }
        await new Promise(r => setTimeout(r, 1000));
    }
    await connection.close();
})();
