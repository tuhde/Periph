import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.power.ina226 import INA226Full

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

# Construction: r_shunt=0.1, max_current=2.0 (defaults) -> current_lsb=6.103515625e-5,
# cal=int(0.00512/(current_lsb*0.1))=838 (0x0346). Constructor writes CONFIG then CAL.
sensor = INA226Full(connection)
check_true('init', True)

config_writes = [w for w in connection.writes if len(w) == 3 and w[0] == INA226Full._REG_CONFIG]
check_true('init_writes_config_default',
           config_writes[0][1] == 0x41 and config_writes[0][2] == 0x27)

cal_writes = [w for w in connection.writes if len(w) == 3 and w[0] == INA226Full._REG_CAL]
check_true('init_writes_calibration', cal_writes[0][1] == 0x03 and cal_writes[0][2] == 0x46)

# Bus voltage: raw=6400 (0x1900) -> 6400 * 1.25e-3 = 8.0 V
connection.set_register(INA226Full._REG_BUS, 0x19, 0x00)
check_true('voltage', sensor.voltage() == 8.0)

# Shunt voltage: raw signed = -100 (0xFF9C) -> -100 * 2.5e-6 = -2.5e-4 V
connection.set_register(INA226Full._REG_SHUNT, 0xFF, 0x9C)
check_true('shunt_voltage', abs(sensor.shunt_voltage() - (-2.5e-4)) < 1e-12)

# Current: raw signed = 1000 (0x03E8) -> 1000 * current_lsb
connection.set_register(INA226Full._REG_CURRENT, 0x03, 0xE8)
check_true('current', abs(sensor.current() - (1000 * sensor._current_lsb)) < 1e-12)

# Power: raw = 500 (0x01F4) -> 500 * 25 * current_lsb
connection.set_register(INA226Full._REG_POWER, 0x01, 0xF4)
check_true('power', abs(sensor.power() - (500 * 25 * sensor._current_lsb)) < 1e-9)

# configure(avg=2, vbus_ct=3, vsh_ct=5, mode=6) -> config = 0x04EE
sensor.configure(avg=2, vbus_ct=3, vsh_ct=5, mode=6)
last_config_write = [w for w in connection.writes if len(w) == 3 and w[0] == INA226Full._REG_CONFIG][-1]
check_true('configure', last_config_write[1] == 0x04 and last_config_write[2] == 0xEE)

# conversion_ready(): Mask/Enable CVRF bit (0x0008) set
connection.set_register(INA226Full._REG_MASK, 0x00, 0x08)
check_true('conversion_ready_true', sensor.conversion_ready() is True)
connection.set_register(INA226Full._REG_MASK, 0x00, 0x00)
check_true('conversion_ready_false', sensor.conversion_ready() is False)

# overflow(): Mask/Enable OVF bit (0x0004) set
connection.set_register(INA226Full._REG_MASK, 0x00, 0x04)
check_true('overflow_true', sensor.overflow() is True)

# set_alert(POL, limit=1.5, polarity=1, latch=1):
# raw = int(1.5 / (25*current_lsb)) = 983 (0x03D7); mask = POL|0x0002|0x0001 = 0x0803
sensor.set_alert(INA226Full.POL, limit=1.5, polarity=1, latch=1)
mask_write = [w for w in connection.writes if len(w) == 3 and w[0] == INA226Full._REG_MASK][-1]
alert_write = [w for w in connection.writes if len(w) == 3 and w[0] == INA226Full._REG_ALERT][-1]
check_true('set_alert_mask', mask_write[1] == 0x08 and mask_write[2] == 0x03)
check_true('set_alert_limit', alert_write[1] == 0x03 and alert_write[2] == 0xD7)

# alert_flags(): raw Mask/Enable register
connection.set_register(INA226Full._REG_MASK, 0x08, 0x03)
check_true('alert_flags', sensor.alert_flags() == 0x0803)

# reset(): writes CONFIG=0x8000, then re-writes CAL
sensor.reset()
last_writes = connection.writes[-2:]
check_true('reset_config', last_writes[0] == bytes([INA226Full._REG_CONFIG, 0x80, 0x00]))
check_true('reset_cal', last_writes[1] == bytes([INA226Full._REG_CAL, 0x03, 0x46]))

# shutdown(): reads CONFIG, saves mode, writes CONFIG & 0xFFF8
connection.set_register(INA226Full._REG_CONFIG, 0x41, 0x27)
sensor.shutdown()
shutdown_write = connection.writes[-1]
check_true('shutdown', shutdown_write == bytes([INA226Full._REG_CONFIG, 0x41, 0x20]))
check_true('shutdown_saves_mode', sensor._mode == 0x07)

# wake(): reads CONFIG, writes back with saved mode restored
connection.set_register(INA226Full._REG_CONFIG, 0x41, 0x20)
sensor.wake()
wake_write = connection.writes[-1]
check_true('wake', wake_write == bytes([INA226Full._REG_CONFIG, 0x41, 0x27]))

# manufacturer_id() / die_id()
connection.set_register(INA226Full._REG_MFR_ID, 0x54, 0x49)
check_true('manufacturer_id', sensor.manufacturer_id() == 0x5449)
connection.set_register(INA226Full._REG_DIE_ID, 0x22, 0x60)
check_true('die_id', sensor.die_id() == 0x2260)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
