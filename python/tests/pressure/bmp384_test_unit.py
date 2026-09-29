import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.pressure.bmp384 import BMP384Minimal, BMP384Full

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


def close(a, b, tol=1e-6):
    return abs(a - b) < tol


# Arbitrary but fixed calibration NVM block (21 bytes at 0x31).
# NVM: T1=27664, T2=27728, T3=3, P1=-4079, P2=802, P3=-8, P4=5, P5=32832,
#      P6=7696, P7=-16, P8=10, P9=4064, P10=-5, P11=2.
CAL_BYTES = (
    0x10, 0x6C,  # T1 u16 LE
    0x50, 0x6C,  # T2 u16 LE
    0x03,        # T3 s8
    0x11, 0xF0,  # P1 s16 LE
    0x22, 0x03,  # P2 s16 LE
    0xF8,        # P3 s8
    0x05,        # P4 s8
    0x40, 0x80,  # P5 u16 LE
    0x10, 0x1E,  # P6 u16 LE
    0xF0,        # P7 s8
    0x0A,        # P8 s8
    0xE0, 0x0F,  # P9 s16 LE
    0xFB,        # P10 s8
    0x02,        # P11 s8
)

# uncomp_press=6000000, uncomp_temp=8000000 -> t_lin=23.715563300065696 degC,
# pressure=1447.6955007429672 hPa (computed independently from the same
# Bosch compensation formula; cross-checked across all language ports).
PRESS_BYTES = (0x80, 0x8D, 0x5B)   # XLSB, LSB, MSB of 6000000
TEMP_BYTES = (0x00, 0x12, 0x7A)    # XLSB, LSB, MSB of 8000000
EXPECTED_T_LIN = 23.715563300065696
EXPECTED_PRESSURE_HPA = 1447.6955007429672


def new_connection():
    c = I2CConnectionMock()
    c.set_register(0x31, *CAL_BYTES)
    c.set_register(0x00, 0x50)  # CHIP_ID
    return c


def last_write_to(connection, reg):
    for w in reversed(connection.writes):
        if len(w) == 2 and w[0] == reg:
            return w[1]
    return None


# --- construction reads calibration and applies default config ---
conn = new_connection()
chip = BMP384Minimal(conn)
check_true('ctor_writes_osr', last_write_to(conn, 0x1C) == (1 << 3) | 4)      # osr_t=1, osr_p=4
check_true('ctor_writes_config', last_write_to(conn, 0x1F) == (2 << 1))       # iir=2
check_true('ctor_writes_odr', last_write_to(conn, 0x1D) == 0x03)
check_true('ctor_writes_pwr', last_write_to(conn, 0x1B) == (0x03 << 4) | 0x02 | 0x01)

# --- bad chip ID raises ---
bad_conn = new_connection()
bad_conn.set_register(0x00, 0x00)
try:
    BMP384Minimal(bad_conn)
    check_true('bad_chip_id_raises', False)
except OSError:
    check_true('bad_chip_id_raises', True)

# --- temperature()/pressure() against the fixed fixture ---
conn = new_connection()
chip = BMP384Minimal(conn)
conn.set_register(0x04, *PRESS_BYTES, *TEMP_BYTES)
check_true('temperature_value', close(chip.temperature(), EXPECTED_T_LIN, 1e-6))

conn.set_register(0x04, *PRESS_BYTES, *TEMP_BYTES)
check_true('pressure_value', close(chip.pressure(), EXPECTED_PRESSURE_HPA, 1e-6))

# --- forced-mode temperature()/pressure() trigger PWR_CTRL before reading ---
conn = new_connection()
chip = BMP384Minimal(conn)
chip._mode = chip._MODE_FORCED
conn.set_register(0x04, *PRESS_BYTES, *TEMP_BYTES)
chip.temperature()
check_true('forced_temperature_triggers', last_write_to(conn, 0x1B) == (0x01 << 4) | 0x02 | 0x01)

# --- Full: configure() writes OSR/CONFIG/ODR and validates T_conv vs ODR ---
conn = new_connection()
full = BMP384Full(conn)
full.configure(osr_p=1, osr_t=1, iir_filter=2, odr_sel=0x03)  # plenty slow, should not raise
check_true('configure_writes_osr', last_write_to(conn, 0x1C) == (1 << 3) | 1)
check_true('configure_writes_config', last_write_to(conn, 0x1F) == (2 << 1))
check_true('configure_writes_odr', last_write_to(conn, 0x1D) == 0x03)

try:
    full.configure(osr_p=5, osr_t=5, iir_filter=0, odr_sel=0x00)  # 200 Hz way too fast for osr=32,32
    check_true('configure_rejects_fast_odr', False)
except ValueError:
    check_true('configure_rejects_fast_odr', True)

# --- read(): combined burst read ---
conn = new_connection()
full = BMP384Full(conn)
conn.set_register(0x04, *PRESS_BYTES, *TEMP_BYTES)
result = full.read()
check_true('read_pressure', close(result['pressure'], EXPECTED_PRESSURE_HPA, 1e-6))
check_true('read_temperature', close(result['temperature'], EXPECTED_T_LIN, 1e-6))

# --- read() in forced mode also triggers PWR_CTRL (regression: read() must
# trigger+wait exactly like temperature()/pressure()/read_forced() do) ---
conn = new_connection()
full = BMP384Full(conn)
full.set_mode(full.MODE_FORCED)
conn.set_register(0x04, *PRESS_BYTES, *TEMP_BYTES)
full.read()
check_true('read_forced_mode_triggers', last_write_to(conn, 0x1B) == (0x01 << 4) | 0x02 | 0x01)

# --- read_forced(): triggers, waits, reads, then restores previous mode ---
conn = new_connection()
full = BMP384Full(conn)
conn.set_register(0x04, *PRESS_BYTES, *TEMP_BYTES)
result = full.read_forced()
check_true('read_forced_value', close(result['pressure'], EXPECTED_PRESSURE_HPA, 1e-6))
check_true('read_forced_restores_mode', last_write_to(conn, 0x1B) == (0x03 << 4) | 0x02 | 0x01)

# --- set_mode() ---
conn = new_connection()
full = BMP384Full(conn)
full.set_mode(full.MODE_SLEEP)
check_true('set_mode_writes_pwr', last_write_to(conn, 0x1B) == (0x00 << 4) | 0x02 | 0x01)

# --- is_data_ready() ---
conn = new_connection()
full = BMP384Full(conn)
conn.set_register(0x03, 1 << 5)
check_true('is_data_ready_true', full.is_data_ready() is True)
conn.set_register(0x03, 0x00)
check_true('is_data_ready_false', full.is_data_ready() is False)

# --- softreset(): writes CMD, re-reads calibration, re-applies config ---
conn = new_connection()
full = BMP384Full(conn)
full.softreset()
check_true('softreset_writes_cmd', last_write_to(conn, 0x7E) == 0xB6)
check_true('softreset_reapplies_pwr', last_write_to(conn, 0x1B) == (0x03 << 4) | 0x02 | 0x01)

# --- fifo_configure() ---
conn = new_connection()
full = BMP384Full(conn)
full.fifo_configure(press_en=True, temp_en=True, wtm=300, stop_on_full=True)
check_true('fifo_configure_cfg1', last_write_to(conn, 0x17) == (1 << 4) | (1 << 3) | (1 << 1) | 1)
check_true('fifo_configure_wtm_lo', last_write_to(conn, 0x15) == (300 & 0xFF))
check_true('fifo_configure_wtm_hi', last_write_to(conn, 0x16) == ((300 >> 8) & 0x01))

# --- fifo_read(): pressure, temperature, sensortime, error, empty, unknown frames ---
conn = new_connection()
full = BMP384Full(conn)
full._t_lin = EXPECTED_T_LIN  # so a lone pressure frame is comparable to the fixture
fifo_bytes = [
    0x84, *PRESS_BYTES,   # pressure frame
    0x90, *TEMP_BYTES,    # temperature frame
    0xA0, 0x01, 0x02, 0x03,  # sensortime frame
    0x44,                 # error frame
    0x80,                 # empty frame
    0xFF,                 # unknown header
]
conn.set_register(0x12, len(fifo_bytes) & 0xFF, (len(fifo_bytes) >> 8) & 0x01)
conn.set_register(0x14, *fifo_bytes)
frames = full.fifo_read()
check_true('fifo_read_count', len(frames) == 6)
check_true('fifo_read_press_type', frames[0]['type'] == 'pressure')
check_true('fifo_read_press_value', close(frames[0]['value'], EXPECTED_PRESSURE_HPA, 1e-6))
check_true('fifo_read_temp_type', frames[1]['type'] == 'temperature')
check_true('fifo_read_temp_value', close(frames[1]['value'], EXPECTED_T_LIN, 1e-6))
check_true('fifo_read_sensortime', frames[2]['type'] == 'sensortime' and frames[2]['value'] == float(0x030201))
check_true('fifo_read_error', frames[3]['type'] == 'error' and frames[3]['value'] is None)
check_true('fifo_read_empty', frames[4]['type'] == 'empty' and frames[4]['value'] is None)
check_true('fifo_read_unknown', frames[5]['type'] == 'unknown' and frames[5]['value'] is None)

# --- fifo_read(): empty FIFO returns [] ---
conn = new_connection()
full = BMP384Full(conn)
conn.set_register(0x12, 0x00, 0x00)
check_true('fifo_read_empty_fifo', full.fifo_read() == [])

# --- fifo_flush() ---
conn = new_connection()
full = BMP384Full(conn)
full.fifo_flush()
check_true('fifo_flush_writes_cmd', last_write_to(conn, 0x7E) == 0xB0)

# --- altitude(): positive pressure below sea level reference gives positive altitude ---
conn = new_connection()
full = BMP384Full(conn)
conn.set_register(0x04, *PRESS_BYTES, *TEMP_BYTES)
alt = full.altitude(1013.25)
check_true('altitude_negative_for_high_pressure', alt < 0)  # fixture pressure (1447 hPa) is above sea level ref

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
