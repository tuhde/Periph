import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.power.ina260 import INA260Full

passed = 0
failed = 0


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


connection = I2CConnectionMock()

# Construction makes no bus access: no calibration, chip power-on state is the Minimal default
sensor = INA260Full(connection)
check_true('init_no_writes', len(connection.writes) == 0)

# Bus voltage: raw=6400 (0x1900) -> 6400 * 1.25e-3 = 8.0 V
connection.set_register(INA260Full._REG_BUS, 0x19, 0x00)
check_true('voltage', sensor.voltage() == 8.0)

# Current: raw signed = -100 (0xFF9C) -> -100 * 1.25e-3 = -0.125 A
connection.set_register(INA260Full._REG_CURRENT, 0xFF, 0x9C)
check_true('current_negative', abs(sensor.current() - (-0.125)) < 1e-12)

# Current: datasheet example 0x2710 = 10000 -> 12.5 A
connection.set_register(INA260Full._REG_CURRENT, 0x27, 0x10)
check_true('current', abs(sensor.current() - 12.5) < 1e-12)

# Power: datasheet example 0x3A7F = 14975 -> 149.75 W
connection.set_register(INA260Full._REG_POWER, 0x3A, 0x7F)
check_true('power', abs(sensor.power() - 149.75) < 1e-9)

# configure(avg=2, vbus_ct=3, ish_ct=5, mode=6) -> 0x6000 | (2<<9) | (3<<6) | (5<<3) | 6 = 0x64EE
sensor.configure(avg=2, vbus_ct=3, ish_ct=5, mode=6)
last_config_write = [w for w in connection.writes if len(w) == 3 and w[0] == INA260Full._REG_CONFIG][-1]
check_true('configure', last_config_write[1] == 0x64 and last_config_write[2] == 0xEE)

# trigger(): same settings, MODE = 3 -> 0x6000 | (2<<9) | (3<<6) | (5<<3) | 3 = 0x64EB
sensor.trigger()
trig = connection.writes[-1]
check_true('trigger', trig == bytes([INA260Full._REG_CONFIG, 0x64, 0xEB]))

# conversion_ready(): Mask/Enable CVRF bit (0x0008) set
connection.set_register(INA260Full._REG_MASK, 0x00, 0x08)
check_true('conversion_ready_true', sensor.conversion_ready() is True)
connection.set_register(INA260Full._REG_MASK, 0x00, 0x00)
check_true('conversion_ready_false', sensor.conversion_ready() is False)

# overflow(): Mask/Enable OVF bit (0x0004) set
connection.set_register(INA260Full._REG_MASK, 0x00, 0x04)
check_true('overflow_true', sensor.overflow() is True)

# set_alert(POL, limit=1.5, polarity=1, latch=1):
# raw = round(1.5 / 10e-3) = 150 (0x0096); mask = POL|0x0002|0x0001 = 0x0803; limit written first
connection.writes.clear()
sensor.set_alert(INA260Full.POL, limit=1.5, polarity=1, latch=1)
check_true('set_alert_limit_first', connection.writes[0] == bytes([INA260Full._REG_ALERT, 0x00, 0x96]))
check_true('set_alert_mask', connection.writes[1] == bytes([INA260Full._REG_MASK, 0x08, 0x03]))

# set_alert(OCL, limit=5.0): raw = 4000 (0x0FA0)
connection.writes.clear()
sensor.set_alert(INA260Full.OCL, limit=5.0)
check_true('set_alert_ocl_limit', connection.writes[0] == bytes([INA260Full._REG_ALERT, 0x0F, 0xA0]))
check_true('set_alert_ocl_mask', connection.writes[1] == bytes([INA260Full._REG_MASK, 0x80, 0x00]))

# set_alert(UCL, limit=-1.0): raw = -800 -> 0xFCE0
connection.writes.clear()
sensor.set_alert(INA260Full.UCL, limit=-1.0)
check_true('set_alert_ucl_limit', connection.writes[0] == bytes([INA260Full._REG_ALERT, 0xFC, 0xE0]))

# set_alert(CNVR): limit register not written
connection.writes.clear()
sensor.set_alert(INA260Full.CNVR)
check_true('set_alert_cnvr', connection.writes == [bytes([INA260Full._REG_MASK, 0x04, 0x00])])

# clear_alert(): Mask/Enable = 0
sensor.clear_alert()
check_true('clear_alert', connection.writes[-1] == bytes([INA260Full._REG_MASK, 0x00, 0x00]))

# alert_flags(): raw Mask/Enable register
connection.set_register(INA260Full._REG_MASK, 0x08, 0x03)
check_true('alert_flags', sensor.alert_flags() == 0x0803)

# reset(): writes CONFIG = 0x8000
sensor.reset()
check_true('reset', connection.writes[-1] == bytes([INA260Full._REG_CONFIG, 0x80, 0x00]))

# shutdown(): MODE = 000 with the default settings -> 0x6120
sensor.shutdown()
check_true('shutdown', connection.writes[-1] == bytes([INA260Full._REG_CONFIG, 0x61, 0x20]))

# wake(): restore MODE = 111 -> 0x6127
sensor.wake()
check_true('wake', connection.writes[-1] == bytes([INA260Full._REG_CONFIG, 0x61, 0x27]))

# manufacturer_id() / die_id()
connection.set_register(INA260Full._REG_MFR_ID, 0x54, 0x49)
check_true('manufacturer_id', sensor.manufacturer_id() == 0x5449)
connection.set_register(INA260Full._REG_DIE_ID, 0x22, 0x70)
check_true('die_id', sensor.die_id() == 0x2270)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
