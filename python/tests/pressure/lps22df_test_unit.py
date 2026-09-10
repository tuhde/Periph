import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.pressure.lps22df import LPS22DFFull

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


def make_press_raw(hpa):
    """Pack a hPa value into the 24-bit two's complement pressure format."""
    raw = int(round(hpa * 4096.0))
    if raw < 0:
        raw += 0x1000000
    return [raw & 0xFF, (raw >> 8) & 0xFF, (raw >> 16) & 0xFF]


def make_temp_raw(celsius):
    """Pack a °C value into the 16-bit two's complement temperature format."""
    raw = int(round(celsius * 100.0))
    if raw < 0:
        raw += 0x10000
    return [raw & 0xFF, (raw >> 8) & 0xFF]


def preload_identity(connection):
    connection.set_register(LPS22DFFull._REG_WHO_AM_I, 0xB4)


# --- Minimal constructor: WHO_AM_I check, soft reset, ODR/AVG/BDU defaults ---
connection = I2CConnectionMock()
preload_identity(connection)

sensor = LPS22DFFull(connection)

who_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_WHO_AM_I]
check_true('init_reads_who_am_i', len(who_writes) == 0)  # WHO_AM_I was read, not written

# Initial writes: SWRESET=0x04, CTRL_REG1=0x18 (ODR=10 Hz, AVG=4), CTRL_REG2=0x08 (BDU)
sreset_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_CTRL_REG2]
check_true('init_writes_swreset', sreset_writes[0][1] == 0x04)

ctrl1_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_CTRL_REG1]
check_true('init_writes_ctrl_reg1', ctrl1_writes[0][1] == 0x18)  # (3<<3)|0 = 0x18

ctrl2_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_CTRL_REG2]
check_true('init_writes_ctrl_reg2_bdu', ctrl2_writes[1][1] == 0x08)


# --- WHO_AM_I mismatch raises ---
bad_connection = I2CConnectionMock()
bad_connection.set_register(LPS22DFFull._REG_WHO_AM_I, 0x00)
try:
    LPS22DFFull(bad_connection)
    check_true('who_am_i_mismatch_raises', False)
except ValueError:
    check_true('who_am_i_mismatch_raises', True)


# --- pressure(): P_DA set, raw value -> Pa ---
connection.set_register(LPS22DFFull._REG_STATUS, 0x01)  # P_DA
connection.set_register(LPS22DFFull._REG_PRESS_OUT_XL, *make_press_raw(1013.25))
p = sensor.pressure()
check_true('pressure_known_value', abs(p - 101325.0) < 0.01)


# --- temperature(): raw value -> °C ---
connection.set_register(LPS22DFFull._REG_TEMP_OUT_L, *make_temp_raw(23.5))
t = sensor.temperature()
check_true('temperature_known_value', abs(t - 23.5) < 0.01)


# --- pressure() negative (below sea level) ---
connection.set_register(LPS22DFFull._REG_STATUS, 0x01)
connection.set_register(LPS22DFFull._REG_PRESS_OUT_XL, *make_press_raw(-50.0))
p = sensor.pressure()
check_true('pressure_negative', abs(p - (-5000.0)) < 0.01)


# --- temperature() negative ---
connection.set_register(LPS22DFFull._REG_TEMP_OUT_L, *make_temp_raw(-10.0))
t = sensor.temperature()
check_true('temperature_negative', abs(t - (-10.0)) < 0.01)


# --- Full.configure(odr=4, avg=2, en_lpfp=True, lfpf_cfg=1, bdu=True) ---
# CTRL_REG1 = (4<<3)|2 = 0x22; CTRL_REG2 = 0x10|0x20|0x08 = 0x38
sensor.configure(odr=4, avg=2, en_lpfp=True, lfpf_cfg=1, bdu=True)
ctrl1_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_CTRL_REG1]
check_true('configure_ctrl_reg1', ctrl1_writes[-1][1] == 0x22)
ctrl2_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_CTRL_REG2]
check_true('configure_ctrl_reg2', ctrl2_writes[-1][1] == 0x38)


# --- configure() rejects bad odr/avg ---
try:
    sensor.configure(odr=9)
    check_true('configure_bad_odr_raises', False)
except ValueError:
    check_true('configure_bad_odr_raises', True)
try:
    sensor.configure(avg=6)
    check_true('configure_bad_avg_raises', False)
except ValueError:
    check_true('configure_bad_avg_raises', True)


# --- oneshot() writes power-down + ONESHOT ---
sensor.oneshot()
ctrl1_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_CTRL_REG1]
ctrl2_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_CTRL_REG2]
check_true('oneshot_power_down', ctrl1_writes[-1][1] == 0x00)
check_true('oneshot_trigger', ctrl2_writes[-1][1] == 0x09)  # BDU|ONESHOT


# --- altitude(): known pressure -> known altitude ---
connection.set_register(LPS22DFFull._REG_STATUS, 0x01)
connection.set_register(LPS22DFFull._REG_PRESS_OUT_XL, *make_press_raw(1013.25))
alt = sensor.altitude(101325.0)
check_true('altitude_zero_at_sea_level', abs(alt) < 0.01)

connection.set_register(LPS22DFFull._REG_STATUS, 0x01)
connection.set_register(LPS22DFFull._REG_PRESS_OUT_XL, *make_press_raw(900.0))
alt = sensor.altitude(101325.0)
# ~989 m at 900 hPa
check_true('altitude_high', abs(alt - 989.0) < 5.0)


# --- software_reset() writes SWRESET=0x04 ---
sensor.software_reset()
ctrl2_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_CTRL_REG2]
check_true('software_reset', ctrl2_writes[-1][1] == 0x04)


# --- set_pressure_offset() packs to RPDS_L/H ---
# 1 hPa offset = 4096 raw; -50 Pa = -0.5 hPa = -2048 raw -> two's complement 0xF800
sensor.set_pressure_offset(-50.0)
rpds_l = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_RPDS_L]
rpds_h = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_RPDS_H]
check_true('pressure_offset_l', rpds_l[-1][1] == 0x00)
check_true('pressure_offset_h', rpds_h[-1][1] == 0xF8)


# --- set_pressure_threshold() packs to THS_P_L/H ---
# 102000 Pa = 1020 hPa * 16 = 16320 raw = 0x3FC0 -> L=0xC0, H=0x3F
sensor.set_pressure_threshold(102000.0)
ths_l = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_THS_P_L]
ths_h = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_THS_P_H]
check_true('threshold_l', ths_l[-1][1] == 0xC0)
check_true('threshold_h', ths_h[-1][1] == 0x3F)


# --- configure_interrupt() writes CTRL_REG3 + CTRL_REG4 ---
# int_h_l=True, pp_od=True, drdy=True, drdy_pls=True, int_en=True,
# int_f_wtm=True, int_f_full=True, int_f_ovr=True
# CTRL_REG3 = 0x01|0x08|0x02 = 0x0B
# CTRL_REG4 = 0x40|0x20|0x10|0x04|0x02|0x01 = 0x77
sensor.configure_interrupt(int_h_l=True, pp_od=True, drdy=True, drdy_pls=True,
                           int_en=True, int_f_wtm=True, int_f_full=True, int_f_ovr=True)
ctrl3_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_CTRL_REG3]
ctrl4_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_CTRL_REG4]
check_true('configure_interrupt_ctrl_reg3', ctrl3_writes[-1][1] == 0x0B)
check_true('configure_interrupt_ctrl_reg4', ctrl4_writes[-1][1] == 0x77)


# --- configure_pressure_event(phe=True, ple=True, lir=True) ---
sensor.configure_pressure_event(phe=True, ple=True, lir=True)
icfg_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_INTERRUPT_CFG]
check_true('configure_pressure_event', icfg_writes[-1][1] == 0x07)


# --- autozero() writes AUTOZERO=1 -> 0x20 ---
sensor.autozero()
icfg_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_INTERRUPT_CFG]
check_true('autozero', icfg_writes[-1][1] == 0x20)


# --- autorefp() writes AUTOREFP=1 -> 0x80 ---
sensor.autorefp()
icfg_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_INTERRUPT_CFG]
check_true('autorefp', icfg_writes[-1][1] == 0x80)


# --- reset_reference() writes RESET_AZ|RESET_ARP = 0x50 ---
sensor.reset_reference()
icfg_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_INTERRUPT_CFG]
check_true('reset_reference', icfg_writes[-1][1] == 0x50)


# --- reference_pressure(): raw hPa -> Pa ---
connection.set_register(LPS22DFFull._REG_REF_P_L, 0x00, 0x10)  # 0x1000 = 4096 raw = 1.0 hPa = 100 Pa
ref = sensor.reference_pressure()
check_true('reference_pressure', abs(ref - 100.0) < 0.01)


# --- set_fifo_mode() maps to (TRIG_MODES, F_MODE) bits ---
# mode=1 (FIFO): TRIG=0, F_MODE=01 -> 0x01
sensor.set_fifo_mode(LPS22DFFull.FIFO_FIFO)
fifo_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_FIFO_CTRL]
check_true('set_fifo_mode_fifo', fifo_writes[-1][1] == 0x01)
# mode=2 (continuous): TRIG=0, F_MODE=1x -> 0x02
sensor.set_fifo_mode(LPS22DFFull.FIFO_CONTINUOUS)
fifo_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_FIFO_CTRL]
check_true('set_fifo_mode_continuous', fifo_writes[-1][1] == 0x02)
# mode=5 (continuous-to-FIFO): TRIG=1, F_MODE=11 -> (1<<2)|3 = 0x07
sensor.set_fifo_mode(LPS22DFFull.FIFO_CONT_TO_FIFO)
fifo_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_FIFO_CTRL]
check_true('set_fifo_mode_cont_to_fifo', fifo_writes[-1][1] == 0x07)


# --- set_fifo_watermark() clamps to 7 bits ---
sensor.set_fifo_watermark(100)
wtm_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS22DFFull._REG_FIFO_WTM]
check_true('set_fifo_watermark', wtm_writes[-1][1] == 100)


# --- fifo_sample_count() returns FSS[7:0] ---
connection.set_register(LPS22DFFull._REG_FIFO_STATUS1, 42)
check_true('fifo_sample_count', sensor.fifo_sample_count() == 42)


# --- read_fifo(): N=3 samples packed back-to-back ---
# Build raw bytes for 3 pressure samples: 1000.0, 1010.0, 1020.0 hPa
fifo_bytes = []
for hpa in (1000.0, 1010.0, 1020.0):
    fifo_bytes.extend(make_press_raw(hpa))
connection.set_register(LPS22DFFull._REG_FIFO_STATUS1, 3)
# Set FIFO_PRESS_XL and beyond — mock uses set_register's variadic interface
connection.set_register(LPS22DFFull._REG_FIFO_PRESS_XL, *fifo_bytes)
samples = sensor.read_fifo()
check_true('read_fifo_length', len(samples) == 3)
check_true('read_fifo_values', abs(samples[0] - 100000.0) < 0.01 and abs(samples[1] - 101000.0) < 0.01 and abs(samples[2] - 102000.0) < 0.01)


# --- read_fifo() empty ---
connection.set_register(LPS22DFFull._REG_FIFO_STATUS1, 0)
check_true('read_fifo_empty', sensor.read_fifo() == [])


# --- interrupt_source() decodes INT_SOURCE ---
connection.set_register(LPS22DFFull._REG_INT_SOURCE, 0x87)  # BOOT_ON|IA|PL|PH
src = sensor.interrupt_source()
check_true('interrupt_source_all', src['boot_on'] and src['ia'] and src['ph'] and src['pl'])
connection.set_register(LPS22DFFull._REG_INT_SOURCE, 0x00)
src = sensor.interrupt_source()
check_true('interrupt_source_none', not src['boot_on'] and not src['ia'] and not src['ph'] and not src['pl'])


# --- SPI transport: write addresses masked (reg & 0x7F) ---
spi_connection = I2CConnectionMock()
preload_identity(spi_connection)
spi_sensor = LPS22DFFull(spi_connection, bus_type='spi')
spi_ctrl1 = [w for w in spi_connection.writes if len(w) == 2 and w[0] == (LPS22DFFull._REG_CTRL_REG1 & 0x7F)]
check_true('spi_init_writes_ctrl_reg1_masked', spi_ctrl1[0][1] == 0x18)

spi_connection.set_register(LPS22DFFull._REG_STATUS, 0x01)
spi_connection.set_register(LPS22DFFull._REG_PRESS_OUT_XL, *make_press_raw(1013.25))
check_true('spi_pressure', abs(spi_sensor.pressure() - 101325.0) < 0.01)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)