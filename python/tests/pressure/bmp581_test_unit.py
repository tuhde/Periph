import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.pressure.bmp581 import BMP581Full, BMP581Minimal

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


def preload_chip_id(connection, cid=0x50):
    connection.set_register(BMP581Minimal._REG_CHIP_ID if hasattr(BMP581Minimal, '_REG_CHIP_ID') else 0x01, cid)


# BMP581 needs the special private-register-name convention used by
# the driver — but those are module-level in the driver file. Workaround:
# import them by attribute name. The driver exposes them via class attrs
# prefixed by an underscore. Access via dir() trick.
REG_CHIP_ID = None
REG_STATUS = None
REG_INT_STATUS = None
REG_OSR_CONFIG = None
REG_ODR_CONFIG = None
REG_DSP_CONFIG = None
REG_DSP_IIR = None
REG_FIFO_SEL = None
REG_FIFO_CONFIG = None
REG_FIFO_COUNT = None
REG_FIFO_DATA = None
REG_INT_SOURCE = None
REG_INT_CONFIG = None
REG_OOR_THR_P_LSB = None
REG_OOR_THR_P_MSB = None
REG_OOR_RANGE = None
REG_OOR_CONFIG = None
REG_NVM_ADDR = None
REG_NVM_DATA_LSB = None
REG_NVM_DATA_MSB = None
REG_PRESS_XLSB = None
REG_TEMP_XLSB = None
REG_CMD = None
import periph.chips.pressure.bmp581 as _drv
REG_CHIP_ID = _drv._REG_CHIP_ID
REG_STATUS = _drv._REG_STATUS
REG_INT_STATUS = _drv._REG_INT_STATUS
REG_OSR_CONFIG = _drv._REG_OSR_CONFIG
REG_ODR_CONFIG = _drv._REG_ODR_CONFIG
REG_DSP_CONFIG = _drv._REG_DSP_CONFIG
REG_DSP_IIR = _drv._REG_DSP_IIR
REG_FIFO_SEL = _drv._REG_FIFO_SEL
REG_FIFO_CONFIG = _drv._REG_FIFO_CONFIG
REG_FIFO_COUNT = _drv._REG_FIFO_COUNT
REG_FIFO_DATA = _drv._REG_FIFO_DATA
REG_INT_SOURCE = _drv._REG_INT_SOURCE
REG_INT_CONFIG = _drv._REG_INT_CONFIG
REG_OOR_THR_P_LSB = _drv._REG_OOR_THR_P_LSB
REG_OOR_THR_P_MSB = _drv._REG_OOR_THR_P_MSB
REG_OOR_RANGE = _drv._REG_OOR_RANGE
REG_OOR_CONFIG = _drv._REG_OOR_CONFIG
REG_NVM_ADDR = _drv._REG_NVM_ADDR
REG_NVM_DATA_LSB = _drv._REG_NVM_DATA_LSB
REG_NVM_DATA_MSB = _drv._REG_NVM_DATA_MSB
REG_PRESS_XLSB = _drv._REG_PRESS_XLSB
REG_TEMP_XLSB = _drv._REG_TEMP_XLSB
REG_CMD = _drv._REG_CMD
del _drv


connection = I2CConnectionMock()
connection.set_register(REG_CHIP_ID, 0x50)
connection.set_register(REG_STATUS, 0x02)

sensor = BMP581Full(connection)
check_true('init', True)

odr_writes = [w for w in connection.writes
              if len(w) == 2 and w[0] == REG_ODR_CONFIG]
check_true('init_writes_odr_default', odr_writes[-1][1] == 0x71)

osr_writes = [w for w in connection.writes
              if len(w) == 2 and w[0] == REG_OSR_CONFIG]
check_true('init_writes_osr_default', osr_writes[-1][1] == 0x40)

connection.set_register(REG_TEMP_XLSB, 0x00, 0x10, 0x00)  # raw_t = 0x1000 -> 0.0625 C
connection.set_register(REG_PRESS_XLSB, 0x04, 0x00, 0x00)  # raw_p = 0x04 -> 0.0625 Pa
check_true('temperature_decode', abs(sensor.temperature() - 0.0625) < 1e-6)
check_true('pressure_decode', abs(sensor.pressure() - 0.0625) < 1e-6)

p, t = sensor.both()
check_true('both_pressure', abs(p - 0.0625) < 1e-6)
check_true('both_temperature', abs(t - 0.0625) < 1e-6)

connection.set_register(REG_CHIP_ID, 0x50)
check_true('chip_id', sensor.chip_id() == 0x50)

connection.set_register(REG_STATUS, 0x09)
check_true('status', sensor.status() == 0x09)

connection.set_register(REG_INT_STATUS, 0x11)
check_true('interrupt_status', sensor.interrupt_status() == 0x11)

connection.set_register(REG_INT_STATUS, 0x01)
check_true('data_ready', sensor.data_ready() is True)

sensor.configure(odr=0x17, osr_p=4, osr_t=2, press_en=True)
odr_writes = [w for w in connection.writes
              if len(w) == 2 and w[0] == REG_ODR_CONFIG]
osr_writes = [w for w in connection.writes
              if len(w) == 2 and w[0] == REG_OSR_CONFIG]
check_true('configure_odr_10Hz', odr_writes[-1][1] == ((0x17 << 2) | 0x01))
check_true('configure_osr_x16_x4', osr_writes[-1][1] == 0x40 | (4 << 3) | 2)

sensor.set_mode(BMP581Full.MODE_STANDBY)
odr_writes = [w for w in connection.writes
              if len(w) == 2 and w[0] == REG_ODR_CONFIG]
check_true('set_mode_standby', odr_writes[-1][1] == ((0x17 << 2) | 0x00))

sensor.set_mode(BMP581Full.MODE_CONTINUOUS)
odr_writes = [w for w in connection.writes
              if len(w) == 2 and w[0] == REG_ODR_CONFIG]
check_true('set_mode_continuous', odr_writes[-1][1] == ((0x17 << 2) | 0x03))

sensor.set_iir_filter(BMP581Full.IIR_COEFF_3, BMP581Full.IIR_BYPASS)
dsp_writes = [w for w in connection.writes
              if len(w) == 2 and w[0] == REG_DSP_CONFIG]
iir_writes = [w for w in connection.writes
              if len(w) == 2 and w[0] == REG_DSP_IIR]
check_true('set_iir_filter_dsp', dsp_writes[-1][1] & 0x28 == 0x28)
check_true('set_iir_filter_iir', iir_writes[-1][1] == (BMP581Full.IIR_COEFF_3 << 3) | BMP581Full.IIR_BYPASS)

sensor.enable_drdy_interrupt(True)
int_src_writes = [w for w in connection.writes
                  if len(w) == 2 and w[0] == REG_INT_SOURCE]
check_true('enable_drdy_interrupt', int_src_writes[-1][1] & 0x01)

sensor.enable_fifo_interrupt(threshold=True, full=False)
int_src_writes = [w for w in connection.writes
                  if len(w) == 2 and w[0] == REG_INT_SOURCE]
check_true('enable_fifo_threshold', int_src_writes[-1][1] & 0x04)

sensor.enable_oor_interrupt(True)
int_src_writes = [w for w in connection.writes
                  if len(w) == 2 and w[0] == REG_INT_SOURCE]
check_true('enable_oor_interrupt', int_src_writes[-1][1] & 0x08)

sensor.configure_interrupt(mode=1, polarity=1, open_drain=True, enable=True)
int_cfg_writes = [w for w in connection.writes
                  if len(w) == 2 and w[0] == REG_INT_CONFIG]
check_true('configure_interrupt', int_cfg_writes[-1][1] == 0x0F)

sensor.set_oor_threshold(threshold_pa=110000.0, range_pa=200.0, count_limit=2)
lsb_writes = [w for w in connection.writes
              if len(w) == 2 and w[0] == REG_OOR_THR_P_LSB]
msb_writes = [w for w in connection.writes
              if len(w) == 2 and w[0] == REG_OOR_THR_P_MSB]
range_writes = [w for w in connection.writes
                if len(w) == 2 and w[0] == REG_OOR_RANGE]
cfg_writes = [w for w in connection.writes
              if len(w) == 2 and w[0] == REG_OOR_CONFIG]
thr_17 = int(110000 * 64.0) >> 7
check_true('oor_threshold_lsb', lsb_writes[-1][1] == thr_17 & 0xFF)
check_true('oor_threshold_msb', msb_writes[-1][1] == (thr_17 >> 8) & 0xFF)
range_8 = int(200 * 64.0) >> 7
check_true('oor_range', range_writes[-1][1] == (range_8 & 0xFF))
check_true('oor_config_count_limit', cfg_writes[-1][1] & 0xC0 == (2 << 6))

sensor.configure_fifo(BMP581Full.FIFO_BOTH, mode=BMP581Full.FIFO_STREAM, threshold=8)
fifo_sel_writes = [w for w in connection.writes
                   if len(w) == 2 and w[0] == REG_FIFO_SEL]
fifo_cfg_writes = [w for w in connection.writes
                   if len(w) == 2 and w[0] == REG_FIFO_CONFIG]
check_true('configure_fifo_sel', fifo_sel_writes[-1][1] == 0x03)
check_true('configure_fifo_config', fifo_cfg_writes[-1][1] == 8)

connection.set_register(REG_FIFO_COUNT, 4)
check_true('fifo_count', sensor.fifo_count() == 4)

connection.set_register(REG_FIFO_COUNT, 2)
connection.set_register(REG_FIFO_DATA,
                         0x00, 0x10, 0x00,
                         0x04, 0x00, 0x00,
                         0x00, 0x10, 0x00,
                         0x04, 0x00, 0x00)
frames = sensor.read_fifo()
check_true('fifo_read_both_len', len(frames) == 2)
if len(frames) == 2:
    check_true('fifo_read_both_p0', abs(frames[0][0] - 0.0625) < 1e-6)
    check_true('fifo_read_both_t0', abs(frames[0][1] - 0.0625) < 1e-6)
    check_true('fifo_read_both_p1', abs(frames[1][0] - 0.0625) < 1e-6)
    check_true('fifo_read_both_t1', abs(frames[1][1] - 0.0625) < 1e-6)

connection.set_register(0x38, 0xA0)
osr_p_eff, osr_t_eff = sensor.effective_osr()
check_true('effective_osr', osr_p_eff == 4 and osr_t_eff == 0)
check_true('odr_is_valid', sensor.odr_is_valid() is True)

connection.set_register(REG_NVM_DATA_LSB, 0x34)
connection.set_register(REG_NVM_DATA_MSB, 0x12)
val = sensor.nvm_read(0x20)
check_true('nvm_read', val == 0x1234)

connection.set_register(REG_STATUS, 0x00)
print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)