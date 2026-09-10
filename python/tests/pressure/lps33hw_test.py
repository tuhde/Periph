import machine
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.pressure.lps33hw import LPS33HWMinimal, LPS33HWFull
from machine import Pin

passed = 0
failed = 0

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)

lps = LPS33HWMinimal(connection)

# Data conversion: raw 24-bit pressure = 0x000001 = 1 -> 100/4096 Pa = 0.0244 Pa
# raw 16-bit temp = 0x0064 = 100 -> 1.0 C; 0xFF9C = -100 -> -1.0 C.
# Preload PRESS_OUT_XL..TEMP_OUT_H (5 bytes starting at 0x28) by writing to
# the mock-style registers via _read_reg only - here we exercise the
# conversion logic via the public pressure()/temperature() entry points.
# Since we're on real hardware, we instead test:
#   1. chip_id() at the wire level (Full-only, but read directly here)
#   2. status() returns sensible defaults after init

raw = lps._read_reg(LPS33HWMinimal._REG_WHO_AM_I, 1)[0]
if raw == 0xB1:
    print('PASS chip_id'); passed += 1
else:
    print('FAIL chip_id: got 0x{:02X}'.format(raw)); failed += 1

ctrl1 = lps._read_reg(LPS33HWMinimal._REG_CTRL_REG1, 1)[0]
if (ctrl1 & 0x70) == 0x10 and (ctrl1 & 0x02) == 0x02:
    print('PASS default_ctrl_reg1 (ODR=1Hz BDU=1)'); passed += 1
else:
    print('FAIL default_ctrl_reg1: got 0x{:02X}'.format(ctrl1)); failed += 1

ctrl2 = lps._read_reg(LPS33HWMinimal._REG_CTRL_REG2, 1)[0]
if ctrl2 & 0x10:
    print('PASS default_ctrl_reg2 (IF_ADD_INC=1)'); passed += 1
else:
    print('FAIL default_ctrl_reg2: got 0x{:02X}'.format(ctrl2)); failed += 1

lps_full = LPS33HWFull(connection)

lps_full.configure(odr=2, bdu=True, en_lpfp=True, lpfp_cfg=1)
ctrl1 = lps_full._read_reg(LPS33HWMinimal._REG_CTRL_REG1, 1)[0]
if (ctrl1 & 0x70) == 0x20 and (ctrl1 & 0x0C) == 0x0C:
    print('PASS configure_writes_ctrl_reg1'); passed += 1
else:
    print('FAIL configure_writes_ctrl_reg1: got 0x{:02X}'.format(ctrl1)); failed += 1

lps_full.set_pressure_offset(2.5)
rpds_l = lps_full._read_reg(LPS33HWMinimal._REG_RPDS_L, 1)[0]
rpds_h = lps_full._read_reg(LPS33HWMinimal._REG_RPDS_H, 1)[0]
if (rpds_h << 8) | rpds_l == 40:
    print('PASS set_pressure_offset (2.5 hPa)'); passed += 1
else:
    print('FAIL set_pressure_offset: got {}/{}'.format(rpds_l, rpds_h)); failed += 1

st = lps_full.status()
if isinstance(st, dict) and 'p_da' in st and 't_da' in st:
    print('PASS status_dict_keys'); passed += 1
else:
    print('FAIL status_dict_keys: got {}'.format(st)); failed += 1

lps_full.enable_fifo(mode=1, watermark=15)
fifo_ctrl = lps_full._read_reg(LPS33HWMinimal._REG_FIFO_CTRL, 1)[0]
if (fifo_ctrl >> 5) == 1 and (fifo_ctrl & 0x1F) == 15:
    print('PASS enable_fifo'); passed += 1
else:
    print('FAIL enable_fifo: got 0x{:02X}'.format(fifo_ctrl)); failed += 1
lps_full.disable_fifo()

fs = lps_full.fifo_status()
if isinstance(fs, dict) and 'count' in fs and 'ovr' in fs:
    print('PASS fifo_status_dict_keys'); passed += 1
else:
    print('FAIL fifo_status_dict_keys: got {}'.format(fs)); failed += 1

lps_full.configure_interrupt(drdy=True, int_s=3, active_low=True, open_drain=True)
ctrl3 = lps_full._read_reg(LPS33HWMinimal._REG_CTRL_REG3, 1)[0]
if (ctrl3 & 0xC0) == 0xC0 and (ctrl3 & 0x04) == 0x04 and (ctrl3 & 0x03) == 0x03:
    print('PASS configure_interrupt'); passed += 1
else:
    print('FAIL configure_interrupt: got 0x{:02X}'.format(ctrl3)); failed += 1

print('===DONE: {} passed, {} failed==='.format(passed, failed))