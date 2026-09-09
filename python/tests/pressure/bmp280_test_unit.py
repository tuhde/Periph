import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.pressure.bmp280 import BMP280Full

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


def preload_calibration(connection):
    # Spec's Data Conversion "Validation" worked example (datasheet page 23):
    # dig_T1=27504, dig_T2=26435, dig_T3=-1000, dig_P1=36477, dig_P2=-10685,
    # dig_P3=3024, dig_P4=2855, dig_P5=140, dig_P6=-7, dig_P7=15500,
    # dig_P8=-14600, dig_P9=6000. Calibration NVM is little-endian.
    connection.set_register(BMP280Full._REG_CAL_START,
                             0x70, 0x6B,  # dig_T1 = 27504
                             0x43, 0x67,  # dig_T2 = 26435
                             0x18, 0xFC,  # dig_T3 = -1000
                             0x7D, 0x8E,  # dig_P1 = 36477
                             0x43, 0xD6,  # dig_P2 = -10685
                             0xD0, 0x0B,  # dig_P3 = 3024
                             0x27, 0x0B,  # dig_P4 = 2855
                             0x8C, 0x00,  # dig_P5 = 140
                             0xF9, 0xFF,  # dig_P6 = -7
                             0x8C, 0x3C,  # dig_P7 = 15500
                             0xF8, 0xC6,  # dig_P8 = -14600
                             0x70, 0x17)  # dig_P9 = 6000


def preload_data(connection):
    # UT=519888, UP=415148 (same worked example) packed as the 20-bit
    # left-aligned ADC format: adc_P = raw[0]<<12|raw[1]<<4|raw[2]>>4,
    # adc_T = raw[3]<<12|raw[4]<<4|raw[5]>>4. Both are read from one 6-byte
    # burst at DATA_START (0xF7), so - unlike BMP180 - this mock can
    # represent the exact worked example (no shared-register aliasing).
    connection.set_register(BMP280Full._REG_DATA_START,
                             0x65, 0x5A, 0xC0,  # adc_P = 415148
                             0x7E, 0xED, 0x00)  # adc_T = 519888


connection = I2CConnectionMock()
preload_calibration(connection)
preload_data(connection)

sensor = BMP280Full(connection)
check_true('init', True)

ctrl_meas_writes = [w for w in connection.writes if len(w) == 2 and w[0] == BMP280Full._REG_CTRL_MEAS]
check_true('init_writes_ctrl_meas_sleep', ctrl_meas_writes[0][1] == 0x24)  # osrs_t=1, osrs_p=1, mode=sleep(0)

config_writes = [w for w in connection.writes if len(w) == 2 and w[0] == BMP280Full._REG_CONFIG]
check_true('init_writes_config_default', config_writes[0][1] == 0x00)

# temperature(): worked example -> T = 25.08 degC.
check_true('temperature', abs(sensor.temperature() - 25.08) < 1e-6)

ctrl_meas_writes = [w for w in connection.writes if len(w) == 2 and w[0] == BMP280Full._REG_CTRL_MEAS]
check_true('temperature_triggers_forced', ctrl_meas_writes[-1][1] == 0x25)  # osrs_t=1, osrs_p=1, mode=forced(1)

# pressure(): worked example -> p = 25767233/256/100 = 1006.5325390625 hPa.
check_true('pressure', abs(sensor.pressure() - 1006.5325390625) < 1e-6)

# chip_id(): expect 0x58.
connection.set_register(BMP280Full._REG_ID, 0x58)
check_true('chip_id', sensor.chip_id() == 0x58)

# status(): raw status byte.
connection.set_register(BMP280Full._REG_STATUS, 0x09)
check_true('status', sensor.status() == 0x09)

# configure(osrs_t=2, osrs_p=3, mode=3, filter=2, t_sb=4):
# CONFIG = (4<<5)|(2<<2) = 0x88; CTRL_MEAS = (2<<5)|(3<<2)|3 = 0x4F.
sensor.configure(osrs_t=2, osrs_p=3, mode=3, filter=2, t_sb=4)
config_writes = [w for w in connection.writes if len(w) == 2 and w[0] == BMP280Full._REG_CONFIG]
ctrl_meas_writes = [w for w in connection.writes if len(w) == 2 and w[0] == BMP280Full._REG_CTRL_MEAS]
check_true('configure_config', config_writes[-1][1] == 0x88)
check_true('configure_ctrl_meas', ctrl_meas_writes[-1][1] == 0x4F)

# set_oversampling(osrs_t=4, osrs_p=5): mode stays 3 (normal, from configure).
# CTRL_MEAS = (4<<5)|(5<<2)|3 = 0x97.
sensor.set_oversampling(4, 5)
ctrl_meas_writes = [w for w in connection.writes if len(w) == 2 and w[0] == BMP280Full._REG_CTRL_MEAS]
check_true('set_oversampling', ctrl_meas_writes[-1][1] == 0x97)

# set_mode(1): CTRL_MEAS = (4<<5)|(5<<2)|1 = 0x95.
sensor.set_mode(1)
ctrl_meas_writes = [w for w in connection.writes if len(w) == 2 and w[0] == BMP280Full._REG_CTRL_MEAS]
check_true('set_mode', ctrl_meas_writes[-1][1] == 0x95)

# set_filter(3): CONFIG = (4<<5)|(3<<2) = 0x8C.
sensor.set_filter(3)
config_writes = [w for w in connection.writes if len(w) == 2 and w[0] == BMP280Full._REG_CONFIG]
check_true('set_filter', config_writes[-1][1] == 0x8C)

# set_standby(6): CONFIG = (6<<5)|(3<<2) = 0xCC.
sensor.set_standby(6)
config_writes = [w for w in connection.writes if len(w) == 2 and w[0] == BMP280Full._REG_CONFIG]
check_true('set_standby', config_writes[-1][1] == 0xCC)

# altitude(sea_level_hpa=1013.25 default): pressure() re-reads DATA_START,
# still preloaded with the same worked-example bytes.
alt = sensor.altitude()
check_true('altitude_default_sea_level', abs(alt - 56.07668235692459) < 1e-3)

# sea_level_pressure(altitude_m=200)
slp = sensor.sea_level_pressure(200)
check_true('sea_level_pressure', abs(slp - 1030.736388797547) < 1e-3)

# reset(): writes RESET=0xB6, re-reads calibration, re-applies current
# configuration (t_sb=6, filter=3, osrs_t=4, osrs_p=5, mode=1 from above).
preload_calibration(connection)
sensor.reset()
reset_writes = [w for w in connection.writes if len(w) == 2 and w[0] == BMP280Full._REG_RESET]
check_true('reset_writes_reset_cmd', reset_writes[-1][1] == BMP280Full._RESET_CMD)
cal_reads = [w for w in connection.writes if len(w) == 1 and w[0] == BMP280Full._REG_CAL_START]
check_true('reset_rereads_calibration', len(cal_reads) >= 2)
config_writes = [w for w in connection.writes if len(w) == 2 and w[0] == BMP280Full._REG_CONFIG]
ctrl_meas_writes = [w for w in connection.writes if len(w) == 2 and w[0] == BMP280Full._REG_CTRL_MEAS]
check_true('reset_reapplies_config', config_writes[-1][1] == 0xCC)
check_true('reset_reapplies_ctrl_meas', ctrl_meas_writes[-1][1] == 0x95)

# --- SPI transport (bus_type='spi') ---------------------------------------
# Per specs/pressure/bmp280.md's SPI Register-address protocol: BMP280's I2C
# register addresses already have bit 7 set (0x88-0xFC), so SPI reads use the
# same reg value unmasked; only writes differ, clearing bit 7 (reg & 0x7F).
# The mock's write_read()/write() only look at the byte(s) actually sent, so
# preloading calibration/data at the plain REG_* constants (unmasked) works
# unchanged for reads - only write-address assertions need the mask applied.
spi_connection = I2CConnectionMock()
preload_calibration(spi_connection)
preload_data(spi_connection)

spi_sensor = BMP280Full(spi_connection, bus_type='spi')
check_true('spi_init', True)

spi_ctrl_meas_writes = [w for w in spi_connection.writes
                         if len(w) == 2 and w[0] == (BMP280Full._REG_CTRL_MEAS & 0x7F)]
check_true('spi_init_writes_ctrl_meas_masked', spi_ctrl_meas_writes[0][1] == 0x24)
check_true('spi_init_no_unmasked_ctrl_meas_write',
           all(w[0] != BMP280Full._REG_CTRL_MEAS for w in spi_connection.writes if len(w) == 2))

check_true('spi_temperature', abs(spi_sensor.temperature() - 25.08) < 1e-6)
check_true('spi_pressure', abs(spi_sensor.pressure() - 1006.5325390625) < 1e-6)

spi_connection.set_register(BMP280Full._REG_ID, 0x58)
check_true('spi_chip_id', spi_sensor.chip_id() == 0x58)

spi_sensor.configure(osrs_t=2, osrs_p=3, mode=3, filter=2, t_sb=4)
spi_config_writes = [w for w in spi_connection.writes
                      if len(w) == 2 and w[0] == (BMP280Full._REG_CONFIG & 0x7F)]
check_true('spi_configure_config_masked', spi_config_writes[-1][1] == 0x88)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
