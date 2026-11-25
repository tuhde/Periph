'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { PCA9685Full } = require('../../../src/chips/led/pca9685');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x40', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

// --- LED fader and servo sweep ---
// Fade channel 0 at 1 kHz, show a phase-shifted pair, run a 16-channel chase,
// switch to 50 Hz and sweep a servo on channel 15 from 1 ms to 2 ms, then show
// all-channel writes, full ON/OFF, output modes, sleep/wake/restart with the
// outputs preserved, the all-call and sub addresses, and finish with a reset.
(async () => {
    const pwm = new PCA9685Full(connection);                  // Create PCA9685 driver, (connection)
    await pwm.ready;
    await pwm.setFrequency(1000);                             // 1 kHz PWM for LEDs, (hz) → void

    for (let i = 0; i <= 100; i++) {
        await pwm.setDuty(0, 0.5 - 0.5 * Math.cos(Math.PI * i / 50)); // Fade up and down, (channel, duty) → void
        await sleep(20);
    }

    await pwm.setPwm(1, 0, 1024);                             // 25 % duty, no delay, (channel, on, off) → void
    await pwm.setPwm(2, 2048, 3072);                          // same duty, shifted by half a period
    console.log(await pwm.getPwm(2));                         // Read back the counts, (channel) → {on, off}
    await sleep(1000);

    for (let ch = 0; ch < 16; ch++) {
        await pwm.setDuty(ch, 1.0);                           // Chase across all channels, (channel, duty) → void
        await sleep(50);
        await pwm.setDuty(ch, 0.0);
    }

    await pwm.setAllDuty(0.2);                                // All channels together, (duty) → void
    await sleep(500);
    await pwm.setAllPwm(0, 3072);                             // 75 % on all channels, (on, off) → void
    await sleep(500);
    await pwm.setAllDuty(0.0);
    await pwm.setFullOn(3);                                   // Constant high, (channel) → void
    await sleep(500);
    await pwm.setFullOff(3);                                  // Constant low, (channel) → void

    await pwm.setFrequency(50);                               // Servo frequency, (hz) → void
    for (let us = 1000; us <= 2000; us += 50) {
        await pwm.setPulseWidth(15, us / 1e6);                // Servo pulse 1–2 ms, (channel, seconds) → void
        await sleep(50);
    }
    console.log(await pwm.getFrequency());                    // () → number Hz

    await pwm.setFrequency(1000);
    await pwm.setDuty(4, 0.5);
    await pwm.setOutputMode(false, true, PCA9685Full.OFF_LOW);   // Inverted output, (openDrain, invert, offState) → void
    await sleep(500);
    await pwm.setOutputMode(true, false, PCA9685Full.OFF_HIGHZ); // Open-drain, high-Z when OE = 1
    await sleep(500);
    await pwm.setOutputMode();                                // Back to totem-pole, not inverted
    await pwm.setUpdateOnAck(true);                           // Update on ACK, (enabled) → void
    await pwm.setUpdateOnAck(false);

    await pwm.sleep();                                        // Outputs pause, PWM registers kept, () → void
    await sleep(500);
    await pwm.wake();                                         // Outputs resume, () → void
    await pwm.restart();                                      // Restart sequence, () → void
    await pwm.setAllcall(true, 0x70);                         // Respond to all-call, (enabled, address) → void
    await pwm.setAllcall(false);
    await pwm.setSubaddress(1, 0x71, true);                   // Respond to sub address 1, (index, address, enabled) → void
    await pwm.setSubaddress(1, 0x71, false);
    console.log(await pwm.readMode());                        // () → {mode1, mode2}
    await pwm.reset();                                        // Back to power-on state, () → void

    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
