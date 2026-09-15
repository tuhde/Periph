import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.pressure.lps33hw import LPS33HWFull

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


def preload_identity(connection):
    """Preload registers so the chip responds to its expected defaults and
    returns sensible pressure/temperature values from the burst read."""
    connection.set_register(LPS33HWFull._REG_WHO_AM_I, 0xB1)
    connection.set_register(LPS33HWFull._REG_STATUS, 0x03)
    # PRESS_OUT_XL..TEMP_OUT_H (5 bytes starting at 0x28):
    # raw_press = 0x100000 = 1048576 -> 1048576 * 100 / 4096 = 25600 Pa = 256 hPa
    # raw_temp  = 0x0A00 = 2560 -> 25.60 C
    connection.set_register(LPS33HWFull._REG_PRESS_XL, 0x00, 0x00, 0x10, 0x00, 0x0A)


connection = I2CConnectionMock()
preload_identity(connection)

sensor = LPS33HWFull(connection)
check_true('init_who_am_i_check', True)

# Verify the three init writes: SWRESET, default CTRL_REG2, default CTRL_REG1
ctrl2_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS33HWFull._REG_CTRL_REG2]
check_true('init_writes_swreset', len(ctrl2_writes) >= 1 and ctrl2_writes[0][1] == 0x04)
check_true('init_writes_ctrl_reg2_default', len(ctrl2_writes) >= 2 and ctrl2_writes[1][1] == 0x10)
ctrl1_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS33HWFull._REG_CTRL_REG1]
check_true('init_writes_ctrl_reg1_default', ctrl1_writes[-1][1] == 0x12)

# pressure(): 1048576 * 100 / 4096 = 25600.0 Pa
check_true('pressure', abs(sensor.pressure() - 25600.0) < 1e-6)
# temperature(): 2560 / 100 = 25.60 C
check_true('temperature', abs(sensor.temperature() - 25.60) < 1e-6)

# status(): STATUS = 0x03 -> p_da=1, t_da=1, p_or=0, t_or=0
check_true('status', sensor.status() == {'p_da': True, 't_da': True, 'p_or': False, 't_or': False})

# configure(odr=2, bdu=True, en_lpfp=True, lpfp_cfg=1): CTRL_REG1 =
# (2<<4)|(1<<3)|(1<<2)|(1<<1)|0 = 0x20|0x08|0x04|0x02 = 0x2E
sensor.configure(odr=2, bdu=True, en_lpfp=True, lpfp_cfg=1)
ctrl1_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS33HWFull._REG_CTRL_REG1]
check_true('configure_writes_ctrl_reg1', ctrl1_writes[-1][1] == 0x2E)

# set_pressure_offset(2.5): raw = 2.5 * 16 = 40 -> RPDS_L=40, RPDS_H=0
sensor.set_pressure_offset(2.5)
rpds_l_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS33HWFull._REG_RPDS_L]
rpds_h_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS33HWFull._REG_RPDS_H]
check_true('set_pressure_offset_rpds_l', rpds_l_writes[-1][1] == 40)
check_true('set_pressure_offset_rpds_h', rpds_h_writes[-1][1] == 0)

# set_pressure_offset(-1.0): raw = -16 -> two's complement = 0xFFF0
sensor.set_pressure_offset(-1.0)
rpds_l_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS33HWFull._REG_RPDS_L]
rpds_h_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS33HWFull._REG_RPDS_H]
check_true('set_pressure_offset_negative_rpds_l', rpds_l_writes[-1][1] == 0xF0)
check_true('set_pressure_offset_negative_rpds_h', rpds_h_writes[-1][1] == 0xFF)

# configure_pressure_interrupt(high_en=True, low_en=True, threshold_hPa=5.0,
# latch=True): raw_ths = 5.0 * 16 = 80 -> THS_P_L=80, THS_P_H=0; INTERRUPT_CFG
# keeps top nibble (currently 0) | 0x04 (LIR) | 0x02 (PLE) | 0x01 (PHE) = 0x07
sensor.configure_pressure_interrupt(high_en=True, low_en=True, threshold_hPa=5.0, latch=True)
ths_l_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS33HWFull._REG_THS_P_L]
ths_h_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS33HWFull._REG_THS_P_H]
check_true('configure_pressure_interrupt_ths_l', ths_l_writes[-1][1] == 80)
check_true('configure_pressure_interrupt_ths_h', ths_h_writes[-1][1] == 0)
ic_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS33HWFull._REG_INTERRUPT_CFG]
check_true('configure_pressure_interrupt_cfg', ic_writes[-1][1] == 0x07)

# configure_interrupt(drdy=True, f_fth=False, f_ovr=False, f_fss5=False,
# int_s=3, active_low=True, open_drain=True): CTRL_REG3 =
# (1<<7)|(1<<6)|(0<<5)|(0<<4)|(0<<3)|(1<<2)|3 = 0xC4|0x04|0x03 = 0xC7
sensor.configure_interrupt(drdy=True, f_fth=False, f_ovr=False, f_fss5=False,
                          int_s=3, active_low=True, open_drain=True)
ctrl3_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS33HWFull._REG_CTRL_REG3]
check_true('configure_interrupt_ctrl_reg3', ctrl3_writes[-1][1] == 0xC7)

# enable_fifo(mode=1, watermark=15): FIFO_CTRL = (1<<5)|15 = 0x2F; CTRL_REG2 |= 0x40
sensor.enable_fifo(mode=1, watermark=15)
fifo_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS33HWFull._REG_FIFO_CTRL]
check_true('enable_fifo_writes_fifo_ctrl', fifo_writes[-1][1] == 0x2F)
ctrl2_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS33HWFull._REG_CTRL_REG2]
check_true('enable_fifo_sets_fifo_en', ctrl2_writes[-1][1] & 0x40)

# disable_fifo: FIFO_EN cleared, FIFO_CTRL=0
sensor.disable_fifo()
ctrl2_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS33HWFull._REG_CTRL_REG2]
fifo_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS33HWFull._REG_FIFO_CTRL]
check_true('disable_fifo_clears_fifo_en', not (ctrl2_writes[-1][1] & 0x40))
check_true('disable_fifo_writes_zero', fifo_writes[-1][1] == 0)

# fifo_status(): FIFO_STATUS = 0xE2 -> fth=1, ovr=1, count=34 (clip to mask & 0x3F)
connection.set_register(LPS33HWFull._REG_FIFO_STATUS, 0xE2)
check_true('fifo_status',
           sensor.fifo_status() == {'fth': True, 'ovr': True, 'count': 0x22})

# read_fifo() with 2 samples preloaded. Each is 5 bytes.
connection2 = I2CConnectionMock()
preload_identity(connection2)
# Override FIFO_STATUS to claim 2 samples queued
connection2.set_register(LPS33HWFull._REG_WHO_AM_I, 0xB1)
connection2.set_register(LPS33HWFull._REG_STATUS, 0x03)
connection2.set_register(LPS33HWFull._REG_FIFO_STATUS, 0x02)
connection2.set_register(LPS33HWFull._REG_PRESS_XL,
                         0x00, 0x00, 0x10, 0x00, 0x0A,
                         0x00, 0x00, 0x10, 0x00, 0x0A)
sensor2 = LPS33HWFull(connection2)
samples = sensor2.read_fifo()
check_true('read_fifo_length', len(samples) == 2)
check_true('read_fifo_pressure', abs(samples[0][0] - 25600.0) < 1e-6)
check_true('read_fifo_temperature', abs(samples[0][1] - 25.60) < 1e-6)

# one_shot(): sets ONE_SHOT bit, returns (P, T) tuple
connection3 = I2CConnectionMock()
preload_identity(connection3)
connection3.set_register(LPS33HWFull._REG_WHO_AM_I, 0xB1)
connection3.set_register(LPS33HWFull._REG_STATUS, 0x03)
connection3.set_register(LPS33HWFull._REG_PRESS_XL, 0x00, 0x00, 0x10, 0x00, 0x0A)
sensor3 = LPS33HWFull(connection3)
p_pa, t_c = sensor3.one_shot()
check_true('one_shot_pressure', abs(p_pa - 25600.0) < 1e-6)
check_true('one_shot_temperature', abs(t_c - 25.60) < 1e-6)

# reset(): writes SWRESET then default CTRL_REG2/CTRL_REG1
sensor.reset()
ctrl2_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS33HWFull._REG_CTRL_REG2]
ctrl1_writes = [w for w in connection.writes if len(w) == 2 and w[0] == LPS33HWFull._REG_CTRL_REG1]
check_true('reset_writes_swreset_last', ctrl2_writes[-2][1] == 0x04)
check_true('reset_restores_ctrl_reg2_default', ctrl2_writes[-1][1] == 0x10)
check_true('reset_restores_ctrl_reg1_default', ctrl1_writes[-1][1] == 0x12)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)