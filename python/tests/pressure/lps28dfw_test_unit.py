import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.pressure.lps28dfw import LPS28DFWMinimal, LPS28DFWFull

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


def make_press_raw(hpa, sens=4096.0):
    """Pack a hPa value into the 24-bit two's complement pressure format."""
    raw = int(round(hpa * sens))
    if raw < 0:
        raw += 0x1000000
    return [raw & 0xFF, (raw >> 8) & 0xFF, (raw >> 16) & 0xFF]


def make_temp_raw(celsius):
    """Pack a °C value into the 16-bit two's complement temperature format."""
    raw = int(round(celsius * 100.0))
    if raw < 0:
        raw += 0x10000
    return [raw & 0xFF, (raw >> 8) & 0xFF]


def new_connection():
    c = I2CConnectionMock()
    c.set_register(0x0F, 0xB4)  # WHO_AM_I
    return c


def last_write_to(connection, reg):
    for w in reversed(connection.writes):
        if len(w) == 2 and w[0] == reg:
            return w[1]
    return None


# --- Minimal constructor: WHO_AM_I check, CTRL_REG2/CTRL_REG1 defaults ---
connection = new_connection()
chip = LPS28DFWMinimal(connection)
# Defaults: FS_MODE=0, LFPF_CFG=0, EN_LPFP=1, BDU=1 -> CTRL_REG2 = 0x18
check_true('init_writes_ctrl_reg2', last_write_to(connection, 0x11) == 0x18)
# ODR=0x04, AVG=0x02 -> CTRL_REG1 = (4<<3)|2 = 0x22
check_true('init_writes_ctrl_reg1', last_write_to(connection, 0x10) == 0x22)

# --- WHO_AM_I mismatch raises ---
bad_connection = I2CConnectionMock()
bad_connection.set_register(0x0F, 0x00)
try:
    LPS28DFWMinimal(bad_connection)
    check_true('who_am_i_mismatch_raises', False)
except OSError:
    check_true('who_am_i_mismatch_raises', True)

# --- read_pressure()/read_temperature(): Mode 1, known values ---
connection.set_register(0x28, *make_press_raw(1013.25))
check_true('read_pressure_known', abs(chip.read_pressure() - 1013.25) < 0.001)
connection.set_register(0x2B, *make_temp_raw(23.5))
check_true('read_temperature_known', abs(chip.read_temperature() - 23.5) < 0.001)

# --- Negative pressure/temperature (sign extension) ---
connection.set_register(0x28, *make_press_raw(-50.0))
check_true('read_pressure_negative', abs(chip.read_pressure() - (-50.0)) < 0.001)
connection.set_register(0x2B, *make_temp_raw(-10.0))
check_true('read_temperature_negative', abs(chip.read_temperature() - (-10.0)) < 0.001)

# NOTE: the constructor was fixed to sleep(BOOT_WAIT_MS) *before* reading
# WHO_AM_I rather than after, matching the spec's documented boot sequence
# (wait for boot, then verify WHO_AM_I) -- a synchronous mock with no
# timing model can't distinguish the two orderings, so there's no
# regression assertion for it here; confirmed by code inspection only.
who_reads = [w for w in connection.writes if len(w) == 1 and w[0] == 0x0F]
check_true('init_reads_who_am_i_once', len(who_reads) == 1)

# --- Full: configure() writes CTRL_REG2 then CTRL_REG1 ---
full_conn = new_connection()
full = LPS28DFWFull(full_conn)
full.configure(odr=LPS28DFWFull.ODR_50_HZ, avg=LPS28DFWFull.AVG_64, fs_mode=1, lpf_en=True, lpf_cfg=1)
# CTRL_REG2 = (1<<6)|(1<<5)|(1<<4)|(1<<3) = 0x78
check_true('configure_ctrl_reg2', last_write_to(full_conn, 0x11) == 0x78)
# CTRL_REG1 = (5<<3)|4 = 0x2C
check_true('configure_ctrl_reg1', last_write_to(full_conn, 0x10) == 0x2C)

# --- read(): burst pressure+temperature, Mode 2 sensitivity after configure ---
full_conn.set_register(0x28, *make_press_raw(2000.0, sens=2048.0))
full_conn.set_register(0x2B, *make_temp_raw(18.25))
r = full.read()
check_true('read_pressure_mode2', abs(r['pressure'] - 2000.0) < 0.001)
check_true('read_temperature', abs(r['temperature'] - 18.25) < 0.001)

# --- is_data_ready() ---
full_conn.set_register(0x27, 0x01)
check_true('is_data_ready_true', full.is_data_ready() is True)
full_conn.set_register(0x27, 0x00)
check_true('is_data_ready_false', full.is_data_ready() is False)

# --- read_oneshot(): saves/restores ODR, triggers ONESHOT, polls P_DA ---
oneshot_conn = new_connection()
oneshot = LPS28DFWFull(oneshot_conn)
oneshot_conn.set_register(0x10, 0x22)  # saved CTRL_REG1 (ODR=4)
oneshot_conn.set_register(0x11, 0x18)  # saved CTRL_REG2
oneshot_conn.set_register(0x27, 0x01)  # P_DA already set -> no poll loop needed
oneshot_conn.set_register(0x28, *make_press_raw(1000.0))
oneshot_conn.set_register(0x2B, *make_temp_raw(20.0))
result = oneshot.read_oneshot()
check_true('read_oneshot_result', abs(result['pressure'] - 1000.0) < 0.001)
# CTRL_REG1 during the oneshot: ODR forced to 0 -> (0<<3)|avg=0x02
odr_writes = [w[1] for w in oneshot_conn.writes if len(w) == 2 and w[0] == 0x10]
check_true('read_oneshot_forces_odr0', 0x02 in odr_writes)
check_true('read_oneshot_restores_odr', odr_writes[-1] == 0x22)  # restored saved_odr=4

# --- read_oneshot() timeout regression: CPython must not hang forever ---
# (the buggy version left `deadline = None` on CPython, so the timeout
# branch never fired and this call would spin forever if P_DA never sets)
timeout_conn = new_connection()
timeout_sensor = LPS28DFWFull(timeout_conn)
timeout_conn.set_register(0x27, 0x00)  # P_DA never set
try:
    timeout_sensor.read_oneshot()
    check_true('read_oneshot_times_out', False)
except OSError:
    check_true('read_oneshot_times_out', True)

# --- set_offset(): packs signed 16-bit RPDS ---
full.set_offset(-0.5)  # Mode 2 active (sens=2048): -0.5*2048 = -1024 = 0xFC00
check_true('set_offset_low', last_write_to(full_conn, 0x1A) == 0x00)
check_true('set_offset_high', last_write_to(full_conn, 0x1B) == 0xFC)

# --- softreset() ---
full.softreset()
check_true('softreset_writes_swreset', last_write_to(full_conn, 0x11) == 0x02)

# --- fifo_configure(): regression for missing unconditional Bypass pass-through ---
fifo_conn = new_connection()
fifo_chip = LPS28DFWFull(fifo_conn)
fifo_chip.fifo_configure(mode=LPS28DFWFull.FIFO_CONTINUOUS, wtm=50, stop_on_wtm=True)
fifo_ctrl_writes = [w[1] for w in fifo_conn.writes if len(w) == 2 and w[0] == 0x14]
# Must see a 0x00 (bypass reset) BEFORE the final continuous-mode value.
check_true('fifo_configure_bypass_pass_through', 0x00 in fifo_ctrl_writes[:-1])
# Continuous (TRIG=0, F_MODE=10) with stop_on_wtm -> (0<<2)|(1<<3)|2 = 0x0A
check_true('fifo_configure_final_ctrl', fifo_ctrl_writes[-1] == 0x0A)
check_true('fifo_configure_watermark', last_write_to(fifo_conn, 0x15) == 50)

# --- fifo_read(): N=3 samples packed back-to-back ---
fifo_bytes = []
for hpa in (1000.0, 1010.0, 1020.0):
    fifo_bytes.extend(make_press_raw(hpa))
fifo_conn.set_register(0x78, *fifo_bytes)
samples = fifo_chip.fifo_read(3)
check_true('fifo_read_length', len(samples) == 3)
check_true('fifo_read_values',
           abs(samples[0] - 1000.0) < 0.01 and abs(samples[1] - 1010.0) < 0.01 and abs(samples[2] - 1020.0) < 0.01)

# --- fifo_read() empty / clamped ---
check_true('fifo_read_zero', fifo_chip.fifo_read(0) == [])
try:
    fifo_chip.fifo_read(-1)
    check_true('fifo_read_negative_raises', False)
except ValueError:
    check_true('fifo_read_negative_raises', True)

# --- fifo_level() ---
fifo_conn.set_register(0x25, 42)
check_true('fifo_level', fifo_chip.fifo_level() == 42)

# --- set_threshold(): packs 15-bit unsigned THS_P + enables PHE/PLE ---
thresh_conn = new_connection()
thresh_chip = LPS28DFWFull(thresh_conn)
thresh_conn.set_register(0x0B, 0x00)
thresh_chip.set_threshold(1020.0, high=True, low=True)  # Mode 1: 1020*16=16320=0x3FC0
check_true('set_threshold_low', last_write_to(thresh_conn, 0x0C) == 0xC0)
check_true('set_threshold_high', last_write_to(thresh_conn, 0x0D) == 0x3F)
check_true('set_threshold_enables_phe_ple', last_write_to(thresh_conn, 0x0B) == 0x03)

# --- chip_id() ---
check_true('chip_id', full.chip_id() == 0xB4)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
