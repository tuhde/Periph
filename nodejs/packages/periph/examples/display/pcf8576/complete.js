'use strict';
const { I2CTransport } = require('../../../src/transport/i2c');
const { PCF8576Full } = require('../../../src/chips/display/pcf8576');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x38', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const lcd = new PCF8576Full(transport);                   // Create PCF8576 driver, (transport)
lcd.clear();                                              // Blank the display, () → void
                                                          // zeros all 40 columns of display RAM
lcd.deviceSelect(0);                                      // Select device on the bus, (subaddress 0–7) → void
                                                          // sets the subaddress counter for cascaded use
lcd.setMode(PCF8576Full.BACKPLANES_4, PCF8576Full.BIAS_1_3);  // Set drive mode, (backplanes 1–4, bias 0/1) → void
                                                          // configures 1:4 multiplex with 1/3 bias
lcd.setBlink(PCF8576Full.BLINK_2_HZ);                     // Set blink frequency, (frequency 0–3) → void
                                                          // ~2 Hz blink for visual attention
lcd.setBank(PCF8576Full.BANK_0, PCF8576Full.BANK_0);      // Select RAM bank, (input_bank 0/1, output_bank 0/1) → void
                                                          // selects rows 0-1 for both input and output

const digits = [5, 6, 7, 8];
const out = digits.map(d => PCF8576Full.SEVEN_SEG[d]);    // Encode 7-segment digit, (digit 0–9) → number
lcd.writeRaw(0, out);                                     // Write raw bytes, (address 0–39, data) → void
                                                          // sets data pointer to 0 and writes all four digits

lcd.disable();                                            // Disable display output, () → void
                                                          // blanks the panel while keeping RAM contents
lcd.enable();                                             // Enable display output, () → void
                                                          // resumes output from RAM with the prior configuration

transport.close();
console.log('===DONE: 0 passed, 0 failed===');
