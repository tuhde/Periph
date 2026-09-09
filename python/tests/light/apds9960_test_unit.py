import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.light.apds9960 import APDS9960Full

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


def new_sensor():
    connection = I2CConnectionMock()
    connection.set_register(APDS9960Full._REG_ID, 0xAB)
    return connection, APDS9960Full(connection)


# Construction verifies the ID register and raises on mismatch.
bad_connection = I2CConnectionMock()
bad_connection.set_register(APDS9960Full._REG_ID, 0x00)
try:
    APDS9960Full(bad_connection)
    check_true('init_rejects_bad_id', False)
except ValueError:
    check_true('init_rejects_bad_id', True)

connection, sensor = new_sensor()
check_true('init', True)

# Init writes: ENABLE=0x00, ATIME=0xB6, CONTROL=0x01, CONFIG2=0x01, ENABLE=0x03 (in order).
enable_writes = [w for w in connection.writes if len(w) == 2 and w[0] == APDS9960Full._REG_ENABLE]
check_true('init_writes_enable_off_then_on',
           enable_writes[0][1] == 0x00 and enable_writes[-1][1] == 0x03)
check_true('init_writes_atime_default',
           connection.registers[APDS9960Full._REG_ATIME] == 0xB6)
check_true('init_writes_control_default',
           connection.registers[APDS9960Full._REG_CONTROL] == 0x01)
check_true('init_writes_config2_default',
           connection.registers[APDS9960Full._REG_CONFIG2] == 0x01)

# color(): CDATAL burst of 8 bytes, LE 16-bit words: clear=0x1234, red=0x0102,
# green=0x0304, blue=0x0506.
connection.set_register(APDS9960Full._REG_CDATAL, 0x34, 0x12, 0x02, 0x01, 0x04, 0x03, 0x06, 0x05)
c, r, g, b = sensor.color()
check_true('color_clear', c == 0x1234)
check_true('color_red', r == 0x0102)
check_true('color_green', g == 0x0304)
check_true('color_blue', b == 0x0506)

check_true('color_clear_method', sensor.color_clear() == 0x1234)
check_true('color_red_method', sensor.color_red() == 0x0102)
check_true('color_green_method', sensor.color_green() == 0x0304)
check_true('color_blue_method', sensor.color_blue() == 0x0506)

# enable_proximity(True): ENABLE currently 0x03 (from init) -> PEN (bit 2) set -> 0x07.
sensor.enable_proximity(True)
check_true('enable_proximity_sets_pen', connection.registers[APDS9960Full._REG_ENABLE] == 0x07)
sensor.enable_proximity(False)
check_true('enable_proximity_clears_pen', connection.registers[APDS9960Full._REG_ENABLE] == 0x03)

connection.set_register(APDS9960Full._REG_PDATA, 200)
check_true('proximity', sensor.proximity() == 200)

sensor.enable_wait(True)
check_true('enable_wait_sets_wen', connection.registers[APDS9960Full._REG_ENABLE] == 0x0B)
sensor.enable_wait(False)
check_true('enable_wait_clears_wen', connection.registers[APDS9960Full._REG_ENABLE] == 0x03)

# configure_wait: CONFIG1 reserved bits 7:2 must be preserved as read-back (0x60 baked
# in when the mock has no prior CONFIG1 write, since the driver ORs in 0x60 regardless).
sensor.configure_wait(100, long=True)
check_true('configure_wait_wtime', connection.registers[APDS9960Full._REG_WTIME] == 100)
check_true('configure_wait_config1_wlong',
           connection.registers[APDS9960Full._REG_CONFIG1] == 0x62)
sensor.configure_wait(50, long=False)
check_true('configure_wait_config1_no_wlong',
           connection.registers[APDS9960Full._REG_CONFIG1] == 0x60)

sensor.configure_als(0xDB, 2)
check_true('configure_als_atime', connection.registers[APDS9960Full._REG_ATIME] == 0xDB)
check_true('configure_als_again',
           connection.registers[APDS9960Full._REG_CONTROL] & 0x03 == 2)

sensor.configure_proximity_led(1, 2, 10, 3)
ctrl = connection.registers[APDS9960Full._REG_CONTROL]
check_true('configure_proximity_led_ldrive', (ctrl >> 6) & 0x03 == 1)
check_true('configure_proximity_led_pgain', (ctrl >> 2) & 0x03 == 2)
check_true('configure_proximity_led_ppulse',
           connection.registers[APDS9960Full._REG_PPULSE] == ((3 << 6) | 10))

sensor.set_led_boost(2)
check_true('set_led_boost',
           connection.registers[APDS9960Full._REG_CONFIG2] == ((2 << 4) | 0x01))

sensor.als_threshold(0x1234, 0x5678)
check_true('als_threshold_low',
           connection.registers[APDS9960Full._REG_AILTL] == 0x34 and
           connection.registers[APDS9960Full._REG_AILTH] == 0x12)
check_true('als_threshold_high',
           connection.registers[APDS9960Full._REG_AIHTL] == 0x78 and
           connection.registers[APDS9960Full._REG_AIHTH] == 0x56)

sensor.proximity_threshold(10, 200)
check_true('proximity_threshold',
           connection.registers[APDS9960Full._REG_PILT] == 10 and
           connection.registers[APDS9960Full._REG_PIHT] == 200)

sensor.set_persistence(5, 3)
check_true('set_persistence',
           connection.registers[APDS9960Full._REG_PERS] == ((5 << 4) | 3))

sensor.enable_als_interrupt(True)
check_true('enable_als_interrupt', connection.registers[APDS9960Full._REG_ENABLE] & 0x10 != 0)
sensor.enable_proximity_interrupt(True)
check_true('enable_proximity_interrupt', connection.registers[APDS9960Full._REG_ENABLE] & 0x20 != 0)

sensor.clear_proximity_interrupt()
check_true('clear_proximity_interrupt',
           connection.writes[-1] == bytes([APDS9960Full._REG_PICLEAR]))
sensor.clear_als_interrupt()
check_true('clear_als_interrupt',
           connection.writes[-1] == bytes([APDS9960Full._REG_CICLEAR]))
sensor.clear_all_interrupts()
check_true('clear_all_interrupts',
           connection.writes[-1] == bytes([APDS9960Full._REG_AICLEAR]))

# Sign-magnitude proximity offset encoding: -50 -> 0x80|50=0xB2, 100 -> 0x64.
sensor.set_proximity_offset(-50, 100)
check_true('set_proximity_offset_negative',
           connection.registers[APDS9960Full._REG_POFFSET_UR] == 0xB2)
check_true('set_proximity_offset_positive',
           connection.registers[APDS9960Full._REG_POFFSET_DL] == 0x64)

sensor.set_proximity_mask(True, False, True, False)
check_true('set_proximity_mask',
           connection.registers[APDS9960Full._REG_CONFIG3] == (0x08 | 0x02))

sensor.enable_gesture(True)
check_true('enable_gesture_sets_gen', connection.registers[APDS9960Full._REG_ENABLE] & 0x40 != 0)
check_true('enable_gesture_sets_gmode', connection.registers[APDS9960Full._REG_GCONF4] & 0x01 != 0)
sensor.enable_gesture(False)
check_true('enable_gesture_clears_gen', connection.registers[APDS9960Full._REG_ENABLE] & 0x40 == 0)
check_true('enable_gesture_clears_gmode', connection.registers[APDS9960Full._REG_GCONF4] & 0x01 == 0)

sensor.configure_gesture(1, 2, 20, 3, 5, 30, 10)
check_true('configure_gesture_gpenth', connection.registers[APDS9960Full._REG_GPENTH] == 30)
check_true('configure_gesture_gexth', connection.registers[APDS9960Full._REG_GEXTH] == 10)
check_true('configure_gesture_gconf2',
           connection.registers[APDS9960Full._REG_GCONF2] == ((1 << 5) | (2 << 3) | 5))
check_true('configure_gesture_gpulse',
           connection.registers[APDS9960Full._REG_GPULSE] == ((3 << 6) | 20))

connection.set_register(APDS9960Full._REG_GSTATUS, 0x01)
check_true('gesture_available', sensor.gesture_available() is True)

connection.set_register(APDS9960Full._REG_GFLVL, 2)
connection.set_register(APDS9960Full._REG_GFIFO_U, 10, 20, 30, 40)
fifo = sensor.read_gesture_fifo()
check_true('read_gesture_fifo_level', len(fifo) == 2)
check_true('read_gesture_fifo_first_dataset', fifo[0] == (10, 20, 30, 40))

connection.set_register(APDS9960Full._REG_GFLVL, 0)
check_true('read_gesture_fifo_empty', sensor.read_gesture_fifo() == [])
check_true('gesture_fifo_level', sensor.gesture_fifo_level() == 0)

sensor.clear_gesture_fifo()
check_true('clear_gesture_fifo', connection.registers[APDS9960Full._REG_GCONF4] & 0x04 != 0)

sensor.enable_gesture_interrupt(True)
check_true('enable_gesture_interrupt', connection.registers[APDS9960Full._REG_GCONF4] & 0x02 != 0)

connection.set_register(APDS9960Full._REG_STATUS, 0x93)  # CPSAT|PVALID|AVALID
check_true('status', sensor.status() == 0x93)
check_true('is_als_valid', sensor.is_als_valid() is True)
check_true('is_proximity_valid', sensor.is_proximity_valid() is True)
check_true('is_als_saturated', sensor.is_als_saturated() is True)
check_true('is_proximity_saturated', sensor.is_proximity_saturated() is False)

check_true('chip_id', sensor.chip_id() == 0xAB)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
