'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { L3G4200DFull } = require('../../../src/chips/gyroscope/l3g4200d');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x68', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

(async () => {
    const gyro = new L3G4200DFull(connection);            // Create L3G4200D driver, (connection, busType='i2c')
    const cid = await gyro.whoAmI();                      // Read WHO_AM_I, () → number
                                                          // returns 0xD3 for L3G4200D
    await gyro.configure(1, 0, 500);                      // Configure chip, (odr 0–3, bandwidth 0–3, full_scale 250/500/2000) → undefined
                                                          // sets CTRL_REG1 DR/BW and CTRL_REG4 FS
    await gyro.enableAxes(true, true, true);             // Enable axes, (x, y, z) → undefined
                                                          // sets Xen/Yen/Zen bits in CTRL_REG1
    await gyro.setFullScale(2000);                        // Set full scale, (full_scale 250/500/2000) → undefined
                                                          // updates FS[1:0] in CTRL_REG4
    const ready = await gyro.dataReady();                // Check data ready, () → boolean
                                                          // returns STATUS_REG.ZYXDA
    const status = await gyro.status();                  // Read STATUS, () → number
                                                          // raw status byte (ZYXOR, ZOR, YOR, XOR, ZYXDA, ZDA, YDA, XDA)
    const temp = await gyro.temperature();               // Read temperature, () → number
                                                          // 8-bit signed relative count (−1 °C/digit)
    await gyro.enableHighpass(0, 4);                     // Enable high-pass, (mode 0–3, cutoff 0–9) → undefined
                                                          // sets HPen and HPM/HPCF; cutoff depends on ODR
    await gyro.disableHighpass();                        // Disable high-pass, () → undefined
                                                          // clears HPen in CTRL_REG5
    await gyro.setInterrupt(true, false, true, false, true, false, false, true);  // Configure INT1, (x_high, x_low, y_high, y_low, z_high, z_low, and_mode, latch) → undefined
                                                          // enable high events on x/y/z; latch until INT1_SRC read
    await gyro.setThreshold('x', 87.5);                  // Set X threshold, (axis 'x'/'y'/'z', threshold_dps) → undefined
                                                          // converts dps to raw 15-bit value via sensitivity
    await gyro.setDuration(4, false);                    // Set INT1 duration, (samples 0–127, wait=false) → undefined
                                                          // INT1 must be true for `samples` ODR cycles before firing
    await gyro.setDataReadyPin(true);                    // Route DRDY to INT2, (enable=true) → undefined
                                                          // sets I2_DRDY in CTRL_REG3
    await gyro.enableFifo(2, 10);                        // Enable FIFO, (mode 0–4, watermark=0) → undefined
                                                          // mode 2 = stream mode; watermark=10 frames
    await gyro.disableFifo();                            // Disable FIFO, () → undefined
                                                          // bypass mode and clear FIFO_EN
    const samples = await gyro.fifoSamples();            // Read FIFO count, () → number
                                                          // FSS[4:0] from FIFO_SRC_REG
    await gyro.powerDown();                              // Enter power-down, () → undefined
                                                          // clears PD in CTRL_REG1
    await gyro.wakeUp();                                 // Wake from power-down, () → undefined
                                                          // sets PD; previously enabled axes restored
    await gyro.sleep();                                  // Enter sleep mode, () → undefined
                                                          // PD=1, all axes off
    const intSrc = await gyro.readIntSource();           // Read & clear INT1_SRC, () → number
                                                          // reading clears the interrupt-active bit
    const [x, y, z] = await gyro.angularRate();          // Read X/Y/Z angular rate, () → [number, number, number] rad/s
    console.log(`X=${x.toFixed(2)} Y=${y.toFixed(2)} Z=${z.toFixed(2)} rad/s, T=${temp}, ready=${ready}, status=0x${status.toString(16)}, fifo=${samples}, src=0x${intSrc.toString(16)}, cid=0x${cid.toString(16)}`);
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
