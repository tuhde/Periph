'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { LPS33HWFull } = require('../../../src/chips/pressure/lps33hw');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x5C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const lps = new LPS33HWFull(connection);                 // Create LPS33HW driver, (connection)

(async () => {
    const cid = await lps._readReg(0x0F, 1);             // Read chip ID, (reg, n) → number
                                                        // returns 0xB1 for LPS33HW
    console.log('chip_id=' + cid[0].toString(16));
    await lps.configure(LPS33HWFull.ODR_10_HZ, 1, 1, LPS33HWFull.LPFP_BW_ODR_20, 0, 0);  // Configure chip, (odr 0–5, bdu 0/1, enLpfp 0/1, lpfpCfg 0/1, lcEn 0/1, sim 0/1) → undefined
                                                        // writes CTRL_REG1 and updates LC_EN bit in RES_CONF
    const sample = await lps.oneShot();                  // Single-shot read, () → {pressure_Pa: number, temperature_C: number}
                                                        // triggers a one-shot conversion and waits for both ADCs to settle
    const st = await lps.status();                      // Read status register, () → number
    await lps.setPressureOffset(0.0);                   // Set pressure offset, (offsetHPa) → undefined
                                                        // writes the reference-pressure correction into RPDS_L/RPDS_H
    await lps.setAutozero();                            // Enable AUTOZERO, () → undefined
                                                        // current pressure becomes the new zero reference
    await lps.clearAutozero();                          // Disable AUTOZERO, () → undefined
    await lps.setAutorifp();                            // Enable AUTOIFP, () → undefined
    await lps.clearAutorifp();                          // Disable AUTOIFP, () → undefined
    await lps.configureInterrupt(1, 0, 0, 0, LPS33HWFull.INT_S_DATA_SIGNALS, 0, 0);  // Configure INT pin, (drdy 0/1, fFth 0/1, fOvr 0/1, fFss5 0/1, intS 0–3, activeLow 0/1, openDrain 0/1) → undefined
                                                        // writes CTRL_REG3 with the selected routing and signal polarity
    await lps.configurePressureInterrupt(1, 1, 5.0, 1);  // Configure pressure interrupt, (highEn 0/1, lowEn 0/1, thresholdHPa, latch 0/1) → undefined
                                                        // programs THS_P_L/THS_P_H and arm bits in INTERRUPT_CFG
    await lps.enableFifo(LPS33HWFull.FIFO_MODE_BYPASS, 0);  // Enable FIFO, (mode 0–7, watermark 0–31) → undefined
    await lps.disableFifo();                             // Disable FIFO, () → undefined
    const fs = await lps.fifoStatus();                   // Read FIFO status, () → number
    await lps.resetLpf();                               // Reset low-pass filter, () → undefined
    await lps.reset();                                  // Soft reset chip, () → undefined
                                                        // writes CTRL_REG2 BOOT bit and re-applies CTRL_REG1 default
    await lps.reboot();                                 // Reboot chip, () → undefined
                                                        // writes CTRL_REG2 BOOT and waits for INT_SOURCE BOOT to clear
    const isrc = await lps.interruptStatus();            // Read interrupt source, () → number
    console.log(`T=${sample.temperature_C.toFixed(2)} C, P=${sample.pressure_Pa.toFixed(1)} Pa, status=0x${st.toString(16)}, fifo=0x${fs.toString(16)}, intSrc=0x${isrc.toString(16)}`);
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
