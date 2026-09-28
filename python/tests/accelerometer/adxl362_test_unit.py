import sys

from periph.connection.spi_mock import SPIConnectionMock
from periph.chips.accelerometer.adxl362 import ADXL362Minimal, ADXL362Full

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


def close(a, b, eps=1e-6):
    return abs(a - b) < eps


def new_connection():
    c = SPIConnectionMock()
    c.set_register(0x00, (0xAD, 0x1D, 0xF2, 0x01))  # DEVID_AD, DEVID_MST, PARTID, REVID
    return c


# --- Construction: device-ID triple check, FILTER_CTL/POWER_CTL writes ---
conn = new_connection()
chip = ADXL362Minimal(conn)
check_true('init_writes_filter_ctl', conn.writes[-2] == bytes([0x0A, 0x2C, 0x13]))
check_true('init_writes_power_ctl', conn.writes[-1] == bytes([0x0A, 0x2D, 0x02]))

# --- Construction fails on device-ID mismatch ---
bad_conn = new_connection()
bad_conn.set_register(0x00, (0xFF,))  # corrupt DEVID_AD
try:
    ADXL362Minimal(bad_conn)
    check_true('init_raises_on_bad_devid', False)
except ValueError:
    check_true('init_raises_on_bad_devid', True)

# --- read(): 12-bit sign-extended XYZ at +-2g (0.001 g/LSB) ---
conn.set_register(0x0E, (0x64, 0x00, 0xCE, 0x0F, 0xD0, 0x07))  # x=100,y=-50,z=2000 raw
x, y, z = chip.read()
check_true('read_x', close(x, 0.1))
check_true('read_y', close(y, -0.05))
check_true('read_z', close(z, 2.0))

# --- ADXL362Full ---
full_conn = new_connection()
full = ADXL362Full(full_conn)

# device_id()
full_conn.set_register(0x00, (0xAD, 0x1D, 0xF2, 0x07))
check_true('device_id', full.device_id() == (0xAD, 0x1D, 0xF2, 0x07))

# soft_reset()
full.soft_reset()
check_true('soft_reset_writes_key', full_conn.writes[-1] == bytes([0x0A, 0x1F, 0x52]))
check_true('soft_reset_resets_cached_range', full._range_bits == 0x00)

# set_range(): read-modify-write FILTER_CTL
full_conn.set_register(0x2C, (0x13,))  # current FILTER_CTL
full.set_range(4)
check_true('set_range_4g', full_conn.writes[-1] == bytes([0x0A, 0x2C, 0x53]))
check_true('set_range_4g_cached', full._range_bits == 0x40)
full_conn.set_register(0x2C, (0x53,))
full.set_range(8)
check_true('set_range_8g', full_conn.writes[-1] == bytes([0x0A, 0x2C, 0x93]))
try:
    full.set_range(16)
    check_true('set_range_invalid_raises', False)
except ValueError:
    check_true('set_range_invalid_raises', True)

# set_odr(): nearest supported rate
full_conn.set_register(0x2C, (0x93,))
full.set_odr(60)  # nearest of 50/100 -> 50 Hz (code 0x02)
check_true('set_odr_nearest', full_conn.writes[-1] == bytes([0x0A, 0x2C, 0x92]))
check_true('set_odr_cached', full._odr_hz == 50.0)

# set_half_bandwidth()
full_conn.set_register(0x2C, (0x00,))
full.set_half_bandwidth(True)
check_true('set_half_bandwidth_on', full_conn.writes[-1] == bytes([0x0A, 0x2C, 0x10]))
full_conn.set_register(0x2C, (0x10,))
full.set_half_bandwidth(False)
check_true('set_half_bandwidth_off', full_conn.writes[-1] == bytes([0x0A, 0x2C, 0x00]))

# set_noise_mode()
full_conn.set_register(0x2D, (0x02,))
full.set_noise_mode(ADXL362Full.NOISE_ULTRALOW)
check_true('set_noise_mode_ultralow', full_conn.writes[-1] == bytes([0x0A, 0x2D, 0x22]))
try:
    full.set_noise_mode(9)
    check_true('set_noise_mode_invalid_raises', False)
except ValueError:
    check_true('set_noise_mode_invalid_raises', True)

# set_wakeup_mode()
full_conn.set_register(0x2D, (0x22,))
full.set_wakeup_mode(True)
check_true('set_wakeup_mode_on', full_conn.writes[-1] == bytes([0x0A, 0x2D, 0x2A]))

# set_autosleep()
full_conn.set_register(0x2D, (0x2A,))
full.set_autosleep(True)
check_true('set_autosleep_on', full_conn.writes[-1] == bytes([0x0A, 0x2D, 0x2E]))

# set_external_clock()
full_conn.set_register(0x2D, (0x2E,))
full.set_external_clock(True)
check_true('set_external_clock_on', full_conn.writes[-1] == bytes([0x0A, 0x2D, 0x6E]))

# set_external_sample_trigger()
full_conn.set_register(0x2C, (0x92,))
full.set_external_sample_trigger(True)
check_true('set_external_sample_trigger_on', full_conn.writes[-1] == bytes([0x0A, 0x2C, 0x9A]))

# read_8bit(): signed 8-bit, 16x LSB scale
full_conn.set_register(0x08, (100, 206, 50))  # x=100, y=-50 (0xCE), z=50
rx8, ry8, rz8 = full.read_8bit()
sens8 = 0.004255 * 16  # full._range_bits is currently 8g (0x80) after set_range(8) above
check_true('read_8bit_x', close(rx8, 100 * sens8))
check_true('read_8bit_y', close(ry8, -50 * sens8))
check_true('read_8bit_z', close(rz8, 50 * sens8))

# temperature(): bias=350 LSB @ 25C, 0.065 C/LSB -> raw 427 = 30 C
full_conn.set_register(0x14, (0xAB, 0x01))
check_true('temperature', close(full.temperature(), 30.0, eps=0.01))

# status()/awake()/data_ready()
full_conn.set_register(0x0B, (0x41,))  # AWAKE + DATA_READY
check_true('status_raw', full.status() == 0x41)
check_true('awake_true', full.awake() is True)
check_true('data_ready_true', full.data_ready() is True)
full_conn.set_register(0x0B, (0x00,))
check_true('awake_false', full.awake() is False)
check_true('data_ready_false', full.data_ready() is False)

# fifo_entries(): 10-bit count from FIFO_ENTRIES_L/H
full_conn.set_register(0x0C, (0xFF, 0x01))  # 0x1FF = 511
check_true('fifo_entries', full.fifo_entries() == 0x1FF)

# configure_fifo(): FIFO_CONTROL (AH/FIFO_TEMP/FIFO_MODE) + FIFO_SAMPLES
full.configure_fifo(ADXL362Full.FIFO_STREAM, store_temp=True, watermark=300)
# watermark=300=0x12C -> AH=1 (bit8 set), low byte=0x2C
check_true('configure_fifo_control', full_conn.writes[-2] == bytes([0x0A, 0x28, 0x0E]))  # AH<<3=0x08 | TEMP=0x04 | MODE=2
check_true('configure_fifo_samples', full_conn.writes[-1] == bytes([0x0A, 0x29, 0x2C]))
try:
    full.configure_fifo(9)
    check_true('configure_fifo_invalid_mode_raises', False)
except ValueError:
    check_true('configure_fifo_invalid_mode_raises', True)
try:
    full.configure_fifo(0, watermark=1000)
    check_true('configure_fifo_invalid_watermark_raises', False)
except ValueError:
    check_true('configure_fifo_invalid_watermark_raises', True)

# read_fifo(): decodes axis + value per entry, including temperature axis
full_conn.set_register(0x0C, (2, 0))  # 2 entries
full_conn.set_register(0x00, (100, 0, 171, 193))  # FIFO read has no address byte;
# the mock's write_read keys off data[0] when len(data)==1 -- FIFO read command
# is bytes([0x0D]) alone, so registers must be preloaded at address 0x0D.
full_conn.set_register(0x0D, (100, 0, 171, 193))
entries = full.read_fifo()
check_true('read_fifo_count', len(entries) == 2)
check_true('read_fifo_axis0', entries[0][0] == ADXL362Full.AXIS_X)
check_true('read_fifo_value0', close(entries[0][1], 100 * 0.004255))
check_true('read_fifo_axis1', entries[1][0] == ADXL362Full.AXIS_TEMP)
check_true('read_fifo_value1', close(entries[1][1], 30.0, eps=0.01))

full_conn.set_register(0x0C, (0, 0))
check_true('read_fifo_empty', full.read_fifo() == [])

# set_activity_threshold(): regression for the 11-bit (not 10-bit) H-register bug.
# threshold=1.5g at the chip's current range (8g, sensitivity 0.004255) would
# round differently, so pin the range back to 2g first for an exact fixture.
full_conn.set_register(0x2C, (0x92,))
full.set_range(2)
full_conn.set_register(0x27, (0x00,))
full.set_activity_threshold(1.5, referenced=True)
# raw = round(1.5 / 0.001) = 1500 = 0x5DC -> L=0xDC, H bits[10:8]=0x05.
# writes[-2] is the read_reg(ACT_INACT_CTL) command phase, not a write.
check_true('activity_threshold_low_byte', full_conn.writes[-4] == bytes([0x0A, 0x20, 0xDC]))
check_true('activity_threshold_high_byte_11bit', full_conn.writes[-3] == bytes([0x0A, 0x21, 0x05]))
check_true('activity_threshold_referenced', full_conn.writes[-1] == bytes([0x0A, 0x27, 0x02]))

# set_activity_time()
full.set_activity_time(200)
check_true('activity_time', full_conn.writes[-1] == bytes([0x0A, 0x22, 200]))
try:
    full.set_activity_time(300)
    check_true('activity_time_invalid_raises', False)
except ValueError:
    check_true('activity_time_invalid_raises', True)

# set_inactivity_threshold(): same 11-bit regression, absolute (not referenced)
full_conn.set_register(0x27, (0x00,))
full.set_inactivity_threshold(1.5, referenced=False)
check_true('inactivity_threshold_low_byte', full_conn.writes[-4] == bytes([0x0A, 0x23, 0xDC]))
check_true('inactivity_threshold_high_byte_11bit', full_conn.writes[-3] == bytes([0x0A, 0x24, 0x05]))
check_true('inactivity_threshold_absolute', full_conn.writes[-1] == bytes([0x0A, 0x27, 0x00]))

# set_inactivity_time(): 16-bit
full.set_inactivity_time(0x1234)
check_true('inactivity_time_low', full_conn.writes[-2] == bytes([0x0A, 0x25, 0x34]))
check_true('inactivity_time_high', full_conn.writes[-1] == bytes([0x0A, 0x26, 0x12]))
try:
    full.set_inactivity_time(-1)
    check_true('inactivity_time_invalid_raises', False)
except ValueError:
    check_true('inactivity_time_invalid_raises', True)

# enable_activity_detection() / enable_inactivity_detection()
full_conn.set_register(0x27, (0x00,))
full.enable_activity_detection(True)
check_true('enable_activity_detection', full_conn.writes[-1] == bytes([0x0A, 0x27, 0x01]))
full_conn.set_register(0x27, (0x01,))
full.enable_inactivity_detection(True)
check_true('enable_inactivity_detection', full_conn.writes[-1] == bytes([0x0A, 0x27, 0x05]))

# set_link_loop_mode()
full_conn.set_register(0x27, (0x05,))
full.set_link_loop_mode(ADXL362Full.LINKLOOP_LOOP)
check_true('set_link_loop_mode', full_conn.writes[-1] == bytes([0x0A, 0x27, 0x35]))
try:
    full.set_link_loop_mode(2)
    check_true('set_link_loop_mode_invalid_raises', False)
except ValueError:
    check_true('set_link_loop_mode_invalid_raises', True)

# set_interrupt() / set_interrupt_polarity()
full_conn.set_register(0x2A, (0x00,))
full.set_interrupt(1, ADXL362Full.SOURCE_AWAKE, True)
check_true('set_interrupt_int1_awake', full_conn.writes[-1] == bytes([0x0A, 0x2A, 0x40]))
full_conn.set_register(0x2B, (0x00,))
full.set_interrupt(2, ADXL362Full.SOURCE_FIFO_WATERMARK, True)
check_true('set_interrupt_int2_watermark', full_conn.writes[-1] == bytes([0x0A, 0x2B, 0x04]))
try:
    full.set_interrupt(3, ADXL362Full.SOURCE_ACT, True)
    check_true('set_interrupt_invalid_pin_raises', False)
except ValueError:
    check_true('set_interrupt_invalid_pin_raises', True)

full_conn.set_register(0x2A, (0x40,))
full.set_interrupt_polarity(1, True)
check_true('set_interrupt_polarity_active_low', full_conn.writes[-1] == bytes([0x0A, 0x2A, 0xC0]))

# self_test()
full_conn.set_register(0x2E, (0x00,))
full.self_test(True)
check_true('self_test_on', full_conn.writes[-1] == bytes([0x0A, 0x2E, 0x01]))
full_conn.set_register(0x2E, (0x01,))
full.self_test(False)
check_true('self_test_off', full_conn.writes[-1] == bytes([0x0A, 0x2E, 0x00]))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
