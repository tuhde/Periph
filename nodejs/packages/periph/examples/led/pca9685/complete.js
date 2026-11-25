'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { PCA9685Full } = require('../../../src/chips/led/pca9685');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x40', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

(async () => {
    const pwm = new PCA9685Full(connection);                  // Create PCA9685 driver, (connection)
    await pwm.ready;
    await pwm.setDuty(0, 0.25);                               // Set one channel's duty cycle, (channel 0–15, duty 0.0–1.0) → void
    await pwm.setPwm(1, 409, 1228);                           // Phase-shifted PWM, (channel, on 0–4095, off 0–4095) → void
                                                              // 10 % delay, 20 % duty
    console.log(await pwm.getPwm(1));                         // Read the ON/OFF counts, (channel) → {on, off}
    await pwm.setFullOn(2);                                   // Hold a channel high, (channel) → void
    await pwm.setFullOff(2);                                  // Hold a channel low, (channel) → void
    await pwm.setAllDuty(0.1);                                // Same duty on all 16 channels, (duty 0.0–1.0) → void
    await pwm.setAllPwm(0, 2048);                             // Same ON/OFF counts on all channels, (on, off) → void
    await pwm.setAllDuty(0.0);
    await pwm.setFrequency(50);                               // PWM frequency, (hz 24–1526, oscHz = 25 MHz) → void
    console.log(await pwm.getFrequency());                    // Programmed PWM frequency, (oscHz = 25 MHz) → number Hz
    await pwm.setPulseWidth(15, 0.0015);                      // Servo pulse width, (channel, seconds) → void
                                                              // 1.5 ms at 50 Hz = servo centre
    await sleep(1000);
    await pwm.sleep();                                        // Low-power mode, outputs stop, () → void
    await pwm.wake();                                         // Wake and restart running channels, () → void
    await pwm.restart();                                      // Datasheet restart sequence, () → void
    await pwm.setOutputMode(false, false, PCA9685Full.OFF_LOW); // MODE2 output driver, (openDrain, invert, offState OFF_LOW/OFF_HIGH_OR_HIGHZ/OFF_HIGHZ) → void
    await pwm.setUpdateOnAck(false);                          // Outputs change on STOP (false) or ACK (true), (enabled) → void
    await pwm.setAllcall(false, 0x70);                        // LED All Call address, (enabled, address) → void
    await pwm.setSubaddress(1, 0x71, false);                  // LED Sub Call address, (index 1–3, address, enabled) → void
    console.log(await pwm.readMode());                        // Raw MODE1/MODE2, () → {mode1, mode2}
    // await pwm.useExternalClock();                          // Sticky EXTCLK switch, () → void  (needs a clock on EXTCLK)
    // await PCA9685Full.softwareReset(generalCall);          // Reset every PCA9685 on the bus, (Connection at address 0x00) → void
    await pwm.reset();                                        // Write power-on defaults, () → void

    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
