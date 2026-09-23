'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { DRV8830Full } = require('periph/src/chips/motor/drv8830');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, DRV8830Full.I2C_ADDRESS);
    const motor = new DRV8830Full(connection);                                     // Create DRV8830 Full driver, (connection)
    await motor.init();                                                            // Confirm device presence, () → None
                                                                                       // plain CONTROL read; the chip has no identity register

    await motor.drive(2.5);                                                        // Drive at regulated voltage, (voltage V, + = forward) → None
                                                                                       // maps 2.5 V to the nearest VSET code and sets IN1=1, IN2=0
    await sleep(1000);
    const out = await motor.readOutput();                                          // Read back CONTROL, () → {voltage V, direction}
                                                                                       // decodes VSET to volts and IN1/IN2 to a direction name
    console.log(`commanded ${out.voltage.toFixed(2)} V ${out.direction}`);

    await motor.drive(-1.5);                                                       // Drive at regulated voltage, (voltage V, - = reverse) → None
                                                                                       // a negative voltage sets IN1=0, IN2=1
    await sleep(1000);

    await motor.setOutput(37, true, false);                                        // Write raw CONTROL fields, (vset 6–63, in1, in2) → None
                                                                                       // VSET 37 is ~2.97 V forward; codes 0–5 throw RangeError
    await sleep(1000);

    await motor.brake();                                                           // Short-brake, () → None
                                                                                       // IN1=IN2=1 drives both outputs high
    await sleep(500);
    await motor.stop();                                                            // Coast to standby, () → None
                                                                                       // IN1=IN2=0 leaves both outputs high-impedance

    const fault = await motor.readFault();                                         // Read fault status, () → {fault, ocp, uvlo, ots, ilimit}
                                                                                       // does not clear — latched OCP/ILIMIT keep the bridge off
    console.log('fault', fault);
    await motor.clearFault();                                                      // Clear fault bits, () → None
                                                                                       // writes CLEAR=1; re-enables a latched-off bridge

    await motor.onInterrupt((status) => console.log('fault interrupt', status));   // Subscribe to FAULTn, (callback) → None
                                                                                       // falls back to 5 ms polling when no intPin is wired
    const status = await motor.pollInterrupt();                                    // Poll fault status, () → {fault, ocp, uvlo, ots, ilimit}
                                                                                       // same as readFault(); never clears implicitly
    await motor.offInterrupt();                                                    // Unsubscribe, () → None
    console.log('poll', status);

    await connection.close();
})();
