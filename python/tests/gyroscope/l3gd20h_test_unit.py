import os
import sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..'))

import periph.chips.gyroscope.l3gd20h as _drv
from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.gyroscope.l3gd20h import L3GD20HMinimal, L3GD20HFull

REG_WHO_AM_I = _drv._REG_WHO_AM_I
REG_CTRL_REG1 = _drv._REG_CTRL_REG1
REG_CTRL_REG2 = _drv._REG_CTRL_REG2
REG_CTRL_REG4 = _drv._REG_CTRL_REG4
REG_CTRL_REG5 = _drv._REG_CTRL_REG5
REG_OUT_TEMP = _drv._REG_OUT_TEMP
REG_STATUS = _drv._REG_STATUS
REG_OUT_X_L = _drv._REG_OUT_X_L
REG_FIFO_CTRL = _drv._REG_FIFO_CTRL
REG_FIFO_SRC = _drv._REG_FIFO_SRC

print("=== L3GD20H Unit Tests ===")

passed = 0
failed = 0

def check(label, condition):
    global passed, failed
    if condition:
        print("PASS", label)
        passed += 1
    else:
        print("FAIL", label)
        failed += 1

# Test 1: Minimal init with L3GD20H WHO_AM_I
connection = I2CConnectionMock()
connection.set_register(REG_WHO_AM_I, 0xD7)
try:
    gyro = L3GD20HMinimal(connection)
    check("Minimal init (L3GD20H WHO_AM_I=0xD7)", True)
except Exception:
    check("Minimal init (L3GD20H WHO_AM_I=0xD7)", False)

# Test 2: Minimal init with L3GD20 WHO_AM_I
connection2 = I2CConnectionMock()
connection2.set_register(REG_WHO_AM_I, 0xD4)
try:
    gyro2 = L3GD20HMinimal(connection2)
    check("Minimal init (L3GD20 WHO_AM_I=0xD4)", True)
except Exception:
    check("Minimal init (L3GD20 WHO_AM_I=0xD4)", False)

# Test 3: Minimal init with invalid WHO_AM_I
connection3 = I2CConnectionMock()
connection3.set_register(REG_WHO_AM_I, 0x00)
try:
    gyro3 = L3GD20HMinimal(connection3)
    check("Minimal init rejects invalid WHO_AM_I", False)
except ValueError:
    check("Minimal init rejects invalid WHO_AM_I", True)
except Exception:
    check("Minimal init rejects invalid WHO_AM_I", False)

# Test 4: gyro() returns tuple of floats; X=+16, Y=0, Z=-16 (LE, sub-addr = reg | 0x80).
connection4 = I2CConnectionMock()
connection4.set_register(REG_WHO_AM_I, 0xD7)
gyro4 = L3GD20HMinimal(connection4)
connection4.set_register(REG_OUT_X_L | 0x80, 0x10, 0x00, 0x00, 0x00, 0xF0, 0xFF)
x, y, z = gyro4.gyro()
check("gyro() returns 3 floats", isinstance(x, float) and isinstance(y, float) and isinstance(z, float))

# Test 5: Full class init
connection5 = I2CConnectionMock()
connection5.set_register(REG_WHO_AM_I, 0xD7)
try:
    gyro5 = L3GD20HFull(connection5)
    check("Full init", True)
except Exception:
    check("Full init", False)

# Test 6: configure() sets registers
connection6 = I2CConnectionMock()
connection6.set_register(REG_WHO_AM_I, 0xD7)
gyro6 = L3GD20HFull(connection6)
gyro6.configure(odr=1, bw=0, full_scale=1)  # 190 Hz, BW=0, ±500 dps
check("configure() sets CTRL_REG1 ODR/BW", connection6.registers[REG_CTRL_REG1] == 0x4F)  # 0x0F | (1<<6) | (0<<4) = 0x4F
check("configure() sets CTRL_REG4 FS", connection6.registers[REG_CTRL_REG4] == 0x90)      # 0x80 | (1<<4) = 0x90

# Test 7: gyro_raw() returns raw int16
connection7 = I2CConnectionMock()
connection7.set_register(REG_WHO_AM_I, 0xD7)
gyro7 = L3GD20HFull(connection7)
connection7.set_register(REG_OUT_X_L | 0x80, 0x00, 0x80, 0xFF, 0x7F, 0x00, 0x00)  # X=-32768, Y=32767, Z=0
x, y, z = gyro7.gyro_raw()
check("gyro_raw() returns signed int16", x == -32768 and y == 32767 and z == 0)

# Test 8: temperature() returns signed 8-bit
connection8 = I2CConnectionMock()
connection8.set_register(REG_WHO_AM_I, 0xD7)
gyro8 = L3GD20HFull(connection8)
connection8.set_register(REG_OUT_TEMP, 0x80)  # -128
check("temperature() negative", gyro8.temperature() == -128)
connection8.set_register(REG_OUT_TEMP, 0x7F)  # 127
check("temperature() positive", gyro8.temperature() == 127)

# Test 9: data_ready() returns ZYXDA bit
connection9 = I2CConnectionMock()
connection9.set_register(REG_WHO_AM_I, 0xD7)
gyro9 = L3GD20HFull(connection9)
connection9.set_register(REG_STATUS, 0x08)  # ZYXDA=1
check("data_ready() true", gyro9.data_ready() == True)
connection9.set_register(REG_STATUS, 0x00)  # ZYXDA=0
check("data_ready() false", gyro9.data_ready() == False)

# Test 10: configure_hp_filter()
connection10 = I2CConnectionMock()
connection10.set_register(REG_WHO_AM_I, 0xD7)
gyro10 = L3GD20HFull(connection10)
gyro10.configure_hp_filter(mode=1, cutoff=5)
check("configure_hp_filter() sets CTRL_REG2", connection10.registers[REG_CTRL_REG2] == 0x15)  # (1<<4) | 5 = 0x15

# Test 11: enable_hp_filter()
connection11 = I2CConnectionMock()
connection11.set_register(REG_WHO_AM_I, 0xD7)
gyro11 = L3GD20HFull(connection11)
connection11.set_register(REG_CTRL_REG5, 0x00)
gyro11.enable_hp_filter(True)
check("enable_hp_filter(True) sets HPen", connection11.registers[REG_CTRL_REG5] & 0x10 == 0x10)
gyro11.enable_hp_filter(False)
check("enable_hp_filter(False) clears HPen", connection11.registers[REG_CTRL_REG5] & 0x10 == 0)

# Test 12: configure_fifo()
connection12 = I2CConnectionMock()
connection12.set_register(REG_WHO_AM_I, 0xD7)
gyro12 = L3GD20HFull(connection12)
connection12.set_register(REG_CTRL_REG5, 0x00)
gyro12.configure_fifo(mode=1, watermark=10)
check("configure_fifo() sets FIFO_EN", connection12.registers[REG_CTRL_REG5] & 0x40 == 0x40)
check("configure_fifo() sets FIFO_CTRL_REG", connection12.registers[REG_FIFO_CTRL] == 0x2A)  # (1<<5) | 10 = 0x2A

# Test 13: enable_fifo()
connection13 = I2CConnectionMock()
connection13.set_register(REG_WHO_AM_I, 0xD7)
gyro13 = L3GD20HFull(connection13)
connection13.set_register(REG_CTRL_REG5, 0x00)
gyro13.enable_fifo(True)
check("enable_fifo(True) sets FIFO_EN", connection13.registers[REG_CTRL_REG5] & 0x40 == 0x40)
gyro13.enable_fifo(False)
check("enable_fifo(False) clears FIFO_EN", connection13.registers[REG_CTRL_REG5] & 0x40 == 0)
check("enable_fifo(False) sets FIFO_CTRL_REG=0", connection13.registers[REG_FIFO_CTRL] == 0x00)

# Test 14: fifo_level()
connection14 = I2CConnectionMock()
connection14.set_register(REG_WHO_AM_I, 0xD7)
gyro14 = L3GD20HFull(connection14)
connection14.set_register(REG_FIFO_SRC, 0x05)  # FSS=5
check("fifo_level() returns FSS", gyro14.fifo_level() == 5)

# Test 15: set_power_mode()
connection15 = I2CConnectionMock()
connection15.set_register(REG_WHO_AM_I, 0xD7)
gyro15 = L3GD20HFull(connection15)
connection15.set_register(REG_CTRL_REG1, 0x00)
gyro15.set_power_mode(L3GD20HFull.POWER_NORMAL)
check("set_power_mode(NORMAL) enables all axes", connection15.registers[REG_CTRL_REG1] & 0x0F == 0x0F)
gyro15.set_power_mode(L3GD20HFull.POWER_SLEEP)
check("set_power_mode(SLEEP) disables axes", connection15.registers[REG_CTRL_REG1] & 0x0F == 0x08)
gyro15.set_power_mode(L3GD20HFull.POWER_POWERDOWN)
check("set_power_mode(POWERDOWN) clears PD", connection15.registers[REG_CTRL_REG1] & 0x08 == 0)

print("\n=== DONE: {} passed, {} failed ===".format(passed, failed))
sys.exit(0 if failed == 0 else 1)
