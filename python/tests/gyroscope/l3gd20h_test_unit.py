import os
import sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..'))

from periph.connection.i2c_mock import I2CMock
from periph.chips.gyroscope.l3gd20h import L3GD20HMinimal, L3GD20HFull

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

mock = I2CMock()
mock.set_reg(0x0F, bytes([0xD7]))  # WHO_AM_I = L3GD20H
mock.set_reg(0x20, bytes([0x0F]))  # CTRL_REG1 default
mock.set_reg(0x23, bytes([0x80]))  # CTRL_REG4 default

# Test 1: Minimal init with L3GD20H WHO_AM_I
try:
    gyro = L3GD20HMinimal(mock)
    check("Minimal init (L3GD20H WHO_AM_I=0xD7)", True)
except Exception as e:
    check("Minimal init (L3GD20H WHO_AM_I=0xD7)", False)

# Test 2: Minimal init with L3GD20 WHO_AM_I
mock2 = I2CMock()
mock2.set_reg(0x0F, bytes([0xD4]))  # WHO_AM_I = L3GD20
mock2.set_reg(0x20, bytes([0x0F]))
mock2.set_reg(0x23, bytes([0x80]))
try:
    gyro2 = L3GD20HMinimal(mock2)
    check("Minimal init (L3GD20 WHO_AM_I=0xD4)", True)
except Exception as e:
    check("Minimal init (L3GD20 WHO_AM_I=0xD4)", False)

# Test 3: Minimal init with invalid WHO_AM_I
mock3 = I2CMock()
mock3.set_reg(0x0F, bytes([0x00]))
try:
    gyro3 = L3GD20HMinimal(mock3)
    check("Minimal init rejects invalid WHO_AM_I", False)
except ValueError:
    check("Minimal init rejects invalid WHO_AM_I", True)
except Exception:
    check("Minimal init rejects invalid WHO_AM_I", False)

# Test 4: gyro() returns tuple of floats
mock4 = I2CMock()
mock4.set_reg(0x0F, bytes([0xD7]))
mock4.set_reg(0x20, bytes([0x0F]))
mock4.set_reg(0x23, bytes([0x80]))
# Set up output registers for a known value: 0x0100 = 256 raw * 8.75 mdps = 2.24 dps = 0.039 rad/s
mock4.set_reg(0x28, bytes([0x00, 0x01, 0x00, 0x02, 0x00, 0x03]))  # X=256, Y=512, Z=768
gyro4 = L3GD20HMinimal(mock4)
x, y, z = gyro4.gyro()
check("gyro() returns 3 floats", isinstance(x, float) and isinstance(y, float) and isinstance(z, float))

# Test 5: Full class init
mock5 = I2CMock()
mock5.set_reg(0x0F, bytes([0xD7]))
mock5.set_reg(0x20, bytes([0x0F]))
mock5.set_reg(0x23, bytes([0x80]))
try:
    gyro5 = L3GD20HFull(mock5)
    check("Full init", True)
except Exception as e:
    check("Full init", False)

# Test 6: configure() sets registers
mock6 = I2CMock()
mock6.set_reg(0x0F, bytes([0xD7]))
mock6.set_reg(0x20, bytes([0x0F]))
mock6.set_reg(0x23, bytes([0x80]))
gyro6 = L3GD20HFull(mock6)
gyro6.configure(odr=1, bw=0, full_scale=1)  # 190 Hz, BW=0, ±500 dps
ctrl1 = mock6.get_reg(0x20)
ctrl4 = mock6.get_reg(0x23)
check("configure() sets CTRL_REG1 ODR/BW", ctrl1 == bytes([0x4F]))  # 0x0F | (1<<6) | (0<<4) = 0x4F
check("configure() sets CTRL_REG4 FS", ctrl4 == bytes([0x90]))      # 0x80 | (1<<4) = 0x90

# Test 7: gyro_raw() returns raw int16
mock7 = I2CMock()
mock7.set_reg(0x0F, bytes([0xD7]))
mock7.set_reg(0x20, bytes([0x0F]))
mock7.set_reg(0x23, bytes([0x80]))
mock7.set_reg(0x28, bytes([0x00, 0x80, 0xFF, 0x7F, 0x00, 0x00]))  # X=-32768, Y=32767, Z=0
gyro7 = L3GD20HFull(mock7)
x, y, z = gyro7.gyro_raw()
check("gyro_raw() returns signed int16", x == -32768 and y == 32767 and z == 0)

# Test 8: temperature() returns signed 8-bit
mock8 = I2CMock()
mock8.set_reg(0x0F, bytes([0xD7]))
mock8.set_reg(0x20, bytes([0x0F]))
mock8.set_reg(0x23, bytes([0x80]))
mock8.set_reg(0x26, bytes([0x80]))  # -128
gyro8 = L3GD20HFull(mock8)
check("temperature() negative", gyro8.temperature() == -128)
mock8.set_reg(0x26, bytes([0x7F]))  # 127
check("temperature() positive", gyro8.temperature() == 127)

# Test 9: data_ready() returns ZYXDA bit
mock9 = I2CMock()
mock9.set_reg(0x0F, bytes([0xD7]))
mock9.set_reg(0x20, bytes([0x0F]))
mock9.set_reg(0x23, bytes([0x80]))
mock9.set_reg(0x27, bytes([0x08]))  # ZYXDA=1
gyro9 = L3GD20HFull(mock9)
check("data_ready() true", gyro9.data_ready() == True)
mock9.set_reg(0x27, bytes([0x00]))  # ZYXDA=0
check("data_ready() false", gyro9.data_ready() == False)

# Test 10: configure_hp_filter()
mock10 = I2CMock()
mock10.set_reg(0x0F, bytes([0xD7]))
mock10.set_reg(0x20, bytes([0x0F]))
mock10.set_reg(0x23, bytes([0x80]))
gyro10 = L3GD20HFull(mock10)
gyro10.configure_hp_filter(mode=1, cutoff=5)
ctrl2 = mock10.get_reg(0x21)
check("configure_hp_filter() sets CTRL_REG2", ctrl2 == bytes([0x15]))  # (1<<4) | 5 = 0x15

# Test 11: enable_hp_filter()
mock11 = I2CMock()
mock11.set_reg(0x0F, bytes([0xD7]))
mock11.set_reg(0x20, bytes([0x0F]))
mock11.set_reg(0x23, bytes([0x80]))
gyro11 = L3GD20HFull(mock11)
gyro11.enable_hp_filter(True)
ctrl5 = mock11.get_reg(0x24)
check("enable_hp_filter(True) sets HPen", ctrl5 & 0x10 == 0x10)
gyro11.enable_hp_filter(False)
ctrl5 = mock11.get_reg(0x24)
check("enable_hp_filter(False) clears HPen", ctrl5 & 0x10 == 0)

# Test 12: configure_fifo()
mock12 = I2CMock()
mock12.set_reg(0x0F, bytes([0xD7]))
mock12.set_reg(0x20, bytes([0x0F]))
mock12.set_reg(0x23, bytes([0x80]))
gyro12 = L3GD20HFull(mock12)
gyro12.configure_fifo(mode=1, watermark=10)
ctrl5 = mock12.get_reg(0x24)
fifo_ctrl = mock12.get_reg(0x2E)
check("configure_fifo() sets FIFO_EN", ctrl5 & 0x40 == 0x40)
check("configure_fifo() sets FIFO_CTRL_REG", fifo_ctrl == bytes([0x2A]))  # (1<<5) | 10 = 0x2A

# Test 13: enable_fifo()
mock13 = I2CMock()
mock13.set_reg(0x0F, bytes([0xD7]))
mock13.set_reg(0x20, bytes([0x0F]))
mock13.set_reg(0x23, bytes([0x80]))
gyro13 = L3GD20HFull(mock13)
gyro13.enable_fifo(True)
ctrl5 = mock13.get_reg(0x24)
check("enable_fifo(True) sets FIFO_EN", ctrl5 & 0x40 == 0x40)
gyro13.enable_fifo(False)
ctrl5 = mock13.get_reg(0x24)
fifo_ctrl = mock13.get_reg(0x2E)
check("enable_fifo(False) clears FIFO_EN", ctrl5 & 0x40 == 0)
check("enable_fifo(False) sets FIFO_CTRL_REG=0", fifo_ctrl == bytes([0x00]))

# Test 14: fifo_level()
mock14 = I2CMock()
mock14.set_reg(0x0F, bytes([0xD7]))
mock14.set_reg(0x20, bytes([0x0F]))
mock14.set_reg(0x23, bytes([0x80]))
mock14.set_reg(0x2F, bytes([0x05]))  # FSS=5
gyro14 = L3GD20HFull(mock14)
check("fifo_level() returns FSS", gyro14.fifo_level() == 5)

# Test 15: set_power_mode()
mock15 = I2CMock()
mock15.set_reg(0x0F, bytes([0xD7]))
mock15.set_reg(0x20, bytes([0x0F]))
mock15.set_reg(0x23, bytes([0x80]))
gyro15 = L3GD20HFull(mock15)
gyro15.set_power_mode(L3GD20HFull.POWER_NORMAL)
ctrl1 = mock15.get_reg(0x20)
check("set_power_mode(NORMAL) enables all axes", ctrl1 & 0x0F == 0x0F)
gyro15.set_power_mode(L3GD20HFull.POWER_SLEEP)
ctrl1 = mock15.get_reg(0x20)
check("set_power_mode(SLEEP) disables axes", ctrl1 & 0x0F == 0x08)
gyro15.set_power_mode(L3GD20HFull.POWER_POWERDOWN)
ctrl1 = mock15.get_reg(0x20)
check("set_power_mode(POWERDOWN) clears PD", ctrl1 & 0x08 == 0)

print("\n=== DONE: {} passed, {} failed ===".format(passed, failed))
sys.exit(0 if failed == 0 else 1)