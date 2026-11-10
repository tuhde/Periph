'use strict';

const { SPIConnection } = require('periph/src/connection/spi');
const { GpioOutputPin } = require('periph/src/connection/output_pin');
const { Default } = require('opengpio');
const { TMC5160Minimal } = require('periph/src/chips/motor/tmc5160');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);

(async () => {
    const spi = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 3, maxSpeedHz: 1_000_000 });                       // Create SPI connection, (busNumber, deviceNumber, {mode=3, maxSpeedHz=1 MHz})
    const enPin = new GpioOutputPin(Default.output({ chip: 0, line: 17 }));                                    // Create DRV_ENN pin, (chip, line=17) → OutputPin
    const motor = new TMC5160Minimal(spi, { enPin });                               // Create TMC5160 driver, (connection, {busType='spi', rSenseOhm=0.075 Ω, enPin, address=0, echo=true, multiNode=false, fclkHz=12000000 Hz})
    await motor.init();                                                            // Probe the chip and apply defaults, () → None

    await motor.setCurrent(1.5);                                                   // Set run current, (rmsA A RMS) → None
    await motor.setSpeed(300);                                                     // Set target speed, (fullStepsPerS full steps/s) → None
    await motor.setAcceleration(800);                                              // Set acceleration, (fullStepsPerS2 full steps/s²) → None
    await motor.enable();                                                          // Power stage on, () → None
    await motor.moveTo(400);                                                       // Start a positioning move, (position full steps) → None
    await motor.waitUntilReached();                                                // Wait for the target, (timeoutS) → boolean
    console.log('position:', await motor.position());                              // Read actual position, () → number full steps
    await motor.move(-400);                                                        // Blocking relative move, (fullSteps, fullStepsPerS) → None
    await motor.run(200);                                                          // Velocity mode, (fullStepsPerS full steps/s) → None
    await sleep(2000);
    console.log('velocity:', await motor.velocity());                              // Read actual velocity, () → number full steps/s
    await motor.stop();                                                            // Controlled stop, () → None
    await motor.waitUntilReached();                                                // Wait for standstill, (timeoutS) → boolean
    console.log('standstill:', await motor.isStandstill());                        // Read standstill flag, () → boolean
    console.log('fault:', await motor.readFault());                                // Read fault bitmask, () → number
    await motor.disable();                                                         // Power stage off, () → None
    await spi.close();
})();
