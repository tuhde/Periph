import machine
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.display.pcf8576 import PCF8576Full
from machine import Pin

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
lcd = PCF8576Full(transport)                             # Create PCF8576 driver, (transport)
lcd.clear()                                              # Blank the display, () → None
                                                         # zeros all 40 columns of display RAM
lcd.device_select(0)                                     # Select device on the bus, (subaddress 0–7) → None
                                                         # sets the subaddress counter for cascaded use
lcd.set_mode(PCF8576Full.BACKPLANES_4, PCF8576Full.BIAS_1_3)  # Set drive mode, (backplanes 1–4, bias 0/1) → None
                                                         # configures 1:4 multiplex with 1/3 bias
lcd.set_blink(PCF8576Full.BLINK_2_HZ)                    # Set blink frequency, (frequency 0–3) → None
                                                         # ~2 Hz blink for visual attention
lcd.set_bank(PCF8576Full.BANK_0, PCF8576Full.BANK_0)     # Select RAM bank, (input_bank 0/1, output_bank 0/1) → None
                                                         # selects rows 0-1 for both input and output
digits = [5, 6, 7, 8]
out = bytearray()
for d in digits:
    out.append(PCF8576Full._SEVEN_SEG[d])               # Encode 7-segment digit, (digit 0–9) → int byte
lcd.write_raw(0, bytes(out))                             # Write raw bytes, (address 0–39, data bytes) → None
                                                         # sets data pointer to 0 and writes all four digits
lcd.disable()                                            # Disable display output, () → None
                                                         # blanks the panel while keeping RAM contents
lcd.enable()                                             # Enable display output, () → None
                                                         # resumes output from RAM with the prior configuration
print('===DONE: 0 passed, 0 failed===')
