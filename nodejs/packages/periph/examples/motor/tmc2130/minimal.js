'use strict';

const { SPIConnection } = require('periph/src/connection/spi');
const { GpioOutputPin } = require('periph/src/connection/output_pin');
const { Default } = require('opengpio');
const { TMC2130Minimal } = require('periph/src/chips/motor/tmc2130');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);

(async () => {
    const spi = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 3, maxSpeedHz: 1_000_000 });                       // Create SPI connection, (busNumber, deviceNumber, {mode=3, maxSpeedHz=1 MHz})
    const stepPin = new GpioOutputPin(Default.output({ chip: 0, line: 27 }));                                  // Create STEP pin, (chip, line=27) → OutputPin
    const dirPin = new GpioOutputPin(Default.output({ chip: 0, line: 22 }));                                   // Create DIR pin, (chip, line=22) → OutputPin
    const motor = new TMC2130Minimal(spi, stepPin, dirPin, { rSenseOhm: 0.11 });   // Create TMC2130 driver, (connection, stepPin, dirPin, {rSenseOhm=0.11 Ω, enPin=null})
    await motor.init();                                                            // Probe the chip and apply defaults, () → None

    await motor.setCurrent(0.8);                                                   // Set run current, (rmsA A RMS) → None
    await motor.enable();                                                          // Power stage on, () → None
    for (const steps of [400, -400]) {
        await motor.move(steps, 200);                                              // Move, (fullSteps, fullStepsPerS full steps/s) → None
        await sleep(500);
        console.log('standstill:', await motor.isStandstill());                    // Read standstill flag, () → boolean
        console.log('fault:', await motor.readFault());                            // Read fault bitmask, () → number
    }

    await motor.disable();                                                         // Power stage off, () → None
    await spi.close();
})();
