'use strict';
const { I2CTransport } = require('../../../packages/periph/src/transport/i2c');
const { BMP280Full } = require('../../../packages/periph/src/chips/pressure/bmp280');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x76', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const bmp = new BMP280Full(transport);                // Create BMP280 driver, (transport, addr=0x76, osrs_t=1, osrs_p=1, mode=1, filter=0, t_sb=0)

const cid = bmp.chipId();                             // Read chip ID, () → int
console.log('chip_id=' + cid.toString(16));
const s = bmp.status();                               // Read status register, () → int
console.log('status=0x' + s.toString(16));

bmp.configure(BMP280Full.OSRS_X2, BMP280Full.OSRS_X4,  // Configure ADC, (osrs_t 0–5, osrs_p 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → None
              BMP280Full.MODE_FORCED, BMP280Full.FILTER_4,
              BMP280Full.T_SB_62_5_MS);
bmp.setOversampling(BMP280Full.OSRS_X1, BMP280Full.OSRS_X1);  // Update oversampling, (osrs_t, osrs_p) → None
bmp.setFilter(BMP280Full.FILTER_OFF);                 // Update IIR filter, (coeff 0–4) → None
bmp.setStandby(BMP280Full.T_SB_250_MS);              // Update standby time, (t_sb 0–7) → None
bmp.reset();                                          // Soft reset and re-init, () → None

const t = bmp.temperature();                          // Read temperature, () → float C
const p = bmp.pressure();                             // Read pressure, () → float hPa
console.log(`T=${t.toFixed(1)} C, P=${p.toFixed(1)} hPa`);
transport.close();
console.log('===DONE: 0 passed, 0 failed===');