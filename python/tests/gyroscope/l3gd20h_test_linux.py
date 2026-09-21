import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..'))

from periph.connection.i2c_linux import I2CConnection
from periph.chips.gyroscope.l3gd20h import L3GD20HMinimal, L3GD20HFull

print("=== L3GD20H Linux Hardware Test ===")

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

I2C_BUS = int(os.getenv("I2C_BUS", "1"))
I2C_ADDR = int(os.getenv("I2C_ADDR", "0x6A"), 0)

try:
    conn = I2CConnection(bus=I2C_BUS, addr=I2C_ADDR)
    gyro = L3GD20HMinimal(conn)
    check("Minimal init", True)

    x, y, z = gyro.gyro()
    check("gyro() returns 3 floats", isinstance(x, float) and isinstance(y, float) and isinstance(z, float))
    print("  Initial reading: x={:.3f} y={:.3f} z={:.3f} rad/s".format(x, y, z))

    gyro_full = L3GD20HFull(conn)
    check("Full init", True)

    gyro_full.configure(odr=1, bw=0, full_scale=1)
    check("configure()", True)

    gyro_full.enable_hp_filter(True)
    check("enable_hp_filter(True)", True)

    gyro_full.configure_fifo(mode=1, watermark=10)
    gyro_full.enable_fifo(True)
    check("configure_fifo() + enable_fifo()", True)

    x, y, z = gyro_full.gyro()
    check("gyro() after config", isinstance(x, float) and isinstance(y, float) and isinstance(z, float))

    raw_x, raw_y, raw_z = gyro_full.gyro_raw()
    check("gyro_raw() returns ints", isinstance(raw_x, int) and isinstance(raw_y, int) and isinstance(raw_z, int))

    temp = gyro_full.temperature()
    check("temperature() returns int", isinstance(temp, int))

    drdy = gyro_full.data_ready()
    check("data_ready() returns bool", isinstance(drdy, bool))

    level = gyro_full.fifo_level()
    check("fifo_level() returns int", isinstance(level, int) and level >= 0)

    samples = gyro_full.read_fifo()
    check("read_fifo() returns list", isinstance(samples, list))

    gyro_full.set_power_mode(L3GD20HFull.POWER_SLEEP)
    check("set_power_mode(SLEEP)", True)

    gyro_full.set_power_mode(L3GD20HFull.POWER_NORMAL)
    check("set_power_mode(NORMAL)", True)

except Exception as e:
    print("FAIL: Hardware test error:", e)
    failed += 1

print("\n=== DONE: {} passed, {} failed ===".format(passed, failed))
sys.exit(0 if failed == 0 else 1)