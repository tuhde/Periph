import machine
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.display.pcf8576 import PCF8576Minimal, PCF8576Full
from machine import Pin

passed = 0
failed = 0

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)

lcd = PCF8576Minimal(transport)

expected = 0x40
got = lcd._cmd_mode(enable=False)
if got == expected:
    print('PASS mode_set_off')
    passed += 1
else:
    print('FAIL mode_set_off: expected 0x{:02X}, got 0x{:02X}'.format(expected, got))
    failed += 1

expected = 0x48
got = lcd._cmd_mode(enable=True)
if got == expected:
    print('PASS mode_set_on')
    passed += 1
else:
    print('FAIL mode_set_on: expected 0x{:02X}, got 0x{:02X}'.format(expected, got))
    failed += 1

expected = 0x49
got = lcd._cmd_mode(enable=True, mode=PCF8576Minimal._MODE_STATIC)
if got == expected:
    print('PASS mode_set_static')
    passed += 1
else:
    print('FAIL mode_set_static: expected 0x{:02X}, got 0x{:02X}'.format(expected, got))
    failed += 1

expected = 0x4C
got = lcd._cmd_mode(enable=True, bias=PCF8576Minimal._BIAS_1_2)
if got == expected:
    print('PASS mode_set_half_bias')
    passed += 1
else:
    print('FAIL mode_set_half_bias: expected 0x{:02X}, got 0x{:02X}'.format(expected, got))
    failed += 1

if PCF8576Minimal._SEVEN_SEG[0] == 0xED and PCF8576Minimal._SEVEN_SEG[9] == 0xEB:
    print('PASS seven_seg_lookup')
    passed += 1
else:
    print('FAIL seven_seg_lookup')
    failed += 1

try:
    lcd.write_raw(0, bytes([0x00] * 20))
    print('PASS write_raw_full')
    passed += 1
except Exception as e:
    print('FAIL write_raw_full: {}'.format(e))
    failed += 1

try:
    lcd.set_digit_7seg(0, 0xED)
    lcd.set_digit_7seg(1, 0x60)
    print('PASS set_digit_7seg')
    passed += 1
except Exception as e:
    print('FAIL set_digit_7seg: {}'.format(e))
    failed += 1

try:
    lcd.write_raw(0, bytes([0xED, 0x60, 0xA7]))
    lcd.clear()
    print('PASS clear')
    passed += 1
except Exception as e:
    print('FAIL clear: {}'.format(e))
    failed += 1

lcd_full = PCF8576Full(transport)
try:
    lcd_full.enable()
    lcd_full.disable()
    lcd_full.enable()
    print('PASS enable_disable')
    passed += 1
except Exception as e:
    print('FAIL enable_disable: {}'.format(e))
    failed += 1

try:
    lcd_full.set_mode(PCF8576Full.BACKPLANES_4, PCF8576Full.BIAS_1_3)
    lcd_full.set_mode(PCF8576Full.BACKPLANES_2, PCF8576Full.BIAS_1_2)
    lcd_full.set_mode(PCF8576Full.BACKPLANES_1, PCF8576Full.BIAS_1_3)
    print('PASS set_mode')
    passed += 1
except Exception as e:
    print('FAIL set_mode: {}'.format(e))
    failed += 1

try:
    lcd_full.set_blink(PCF8576Full.BLINK_2_HZ)
    lcd_full.set_blink(PCF8576Full.BLINK_OFF)
    print('PASS set_blink')
    passed += 1
except Exception as e:
    print('FAIL set_blink: {}'.format(e))
    failed += 1

try:
    lcd_full.set_bank(0, 0)
    lcd_full.set_bank(1, 1)
    print('PASS set_bank')
    passed += 1
except Exception as e:
    print('FAIL set_bank: {}'.format(e))
    failed += 1

try:
    lcd_full.device_select(0)
    lcd_full.device_select(7)
    print('PASS device_select')
    passed += 1
except Exception as e:
    print('FAIL device_select: {}'.format(e))
    failed += 1

print('===DONE: {} passed, {} failed==='.format(passed, failed))
