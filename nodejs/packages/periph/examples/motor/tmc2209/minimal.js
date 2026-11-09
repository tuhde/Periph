'use strict';

const { UARTConnection } = require('periph/src/connection/uart');
const { TMC2209Minimal } = require('periph/src/chips/motor/tmc2209');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const port = process.env.UART_PORT || '/dev/ttyS0';
    const connection = new UARTConnection(port, { baudRate: 115200, timeoutMs: 100 });
    await connection.open();
    const motor = new TMC2209Minimal(connection, { rSenseOhm: 0.11 });             // Create TMC2209 driver, (connection, {rSenseOhm=0.11 Ω, address=0, enPin=null, echo=true})
    await motor.init();                                                            // Probe the chip and apply defaults, () → None

    await motor.setCurrent(0.8);                                                   // Set run current, (rmsA A RMS) → None
    for (const speed of [200, -200]) {
        await motor.run(speed);                                                    // Spin with the internal generator, (stepsPerS full steps/s, sign = direction) → None
        await sleep(3000);
        console.log('standstill:', await motor.isStandstill());                    // Read standstill flag, () → boolean
        console.log('fault:', await motor.readFault());                            // Read fault bitmask, () → number
    }

    await motor.stop();                                                            // Stop the motor, () → None
    await connection.close();
})();
