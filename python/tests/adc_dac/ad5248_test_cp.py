import _testconfig_cp as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.adc_dac.ad5248 import AD5248Full

import board
import busio

passed = 0
failed = 0


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got {}, expected {}'.format(label, got, expected))
        failed += 1


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


i2c = busio.I2C(board.SCL, board.SDA, frequency=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)
chip = AD5248Full(connection)

# --- Wiper write / readback ---
chip.set_wiper(0, 100)
check_eq('set/get wiper ch0', chip.get_wiper(0), 100)
chip.set_wiper(1, 200)
check_eq('set/get wiper ch1', chip.get_wiper(1), 200)
check_eq('ch0 unchanged by ch1 write', chip.get_wiper(0), 100)

# --- Resistance ---
chip.set_resistance(0, 5320.0)
check_eq('set_resistance midscale code', chip.get_wiper(0), 128)
check_true('get_resistance ~5320', abs(chip.get_resistance(0) - 5320.0) < 1.0)

# --- Ratio / increment / both ---
chip.set_both(10, 20)
check_eq('set_both ch0', chip.get_wiper(0), 10)
check_eq('set_both ch1', chip.get_wiper(1), 20)
chip.set_ratio(0, 0.5)
check_true('get_ratio ~0.5', abs(chip.get_ratio(0) - 0.5) < 0.01)
chip.set_wiper(0, 250)
check_eq('increment clamps', chip.increment(0, 10), 255)

# --- Shutdown keeps RDAC ---
chip.set_wiper(1, 77)
chip.shutdown(1)
check_true('is_shutdown', chip.is_shutdown(1))
chip.resume(1)
check_eq('RDAC kept across shutdown', chip.get_wiper(1), 77)

# --- Reset ---
chip.reset()
check_eq('reset ch0', chip.get_wiper(0), 0x80)
check_eq('reset ch1', chip.get_wiper(1), 0x80)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
