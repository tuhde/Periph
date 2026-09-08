import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.power.ina3221 import INA3221Full

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

# Construction (default r_shunt=0.1 for all 3 channels) writes nothing -
# the chip's power-on default (all channels on, continuous shunt+bus) is
# used as-is.
sensor = INA3221Full(connection)
check_true('init_writes_nothing', len(connection.writes) == 0)

# --- Channel 1 ---
# Bus1 raw=10000 (0x2710) -> (10000>>3)*8e-3 = 10.0 V
connection.set_register(INA3221Full._REG_BUS1, 0x27, 0x10)
check_true('voltage_ch1', sensor.voltage(1) == 10.0)

# Shunt1 raw signed = -400 (0xFE70) -> -400 * 5e-6 = -0.002 V
connection.set_register(INA3221Full._REG_SHUNT1, 0xFE, 0x70)
check_true('shunt_voltage_ch1', abs(sensor.shunt_voltage(1) - (-0.002)) < 1e-9)
check_true('current_ch1', abs(sensor.current(1) - (-0.02)) < 1e-9)

# power(1): SHUNT1 (0x01) and BUS1 (0x02) are adjacent registers, and the
# mock's byte-slot model can't hold two independent 16-bit values across
# adjacent addresses at once (writing one clobbers the shared byte slot) -
# so the SHUNT1 low byte and BUS1 high byte are chosen equal (0x10) to
# survive either write order. SHUNT1=0xFF10 (-240 signed) -> -0.0012 V;
# BUS1=0x1000 (4096) -> 4.096 V.
connection.set_register(INA3221Full._REG_SHUNT1, 0xFF, 0x10)
connection.set_register(INA3221Full._REG_BUS1, 0x10, 0x00)
check_true('power_ch1', abs(sensor.power(1) - (4.096 * -0.012)) < 1e-9)

# --- Channel 2 ---
# Bus2 raw=4096 (0x1000) -> (4096>>3)*8e-3 = 4.096 V
connection.set_register(INA3221Full._REG_BUS2, 0x10, 0x00)
check_true('voltage_ch2', sensor.voltage(2) == 4.096)

# Shunt2 raw=800 (0x0320) -> 800 * 5e-6 = 0.004 V
connection.set_register(INA3221Full._REG_SHUNT2, 0x03, 0x20)
check_true('shunt_voltage_ch2', abs(sensor.shunt_voltage(2) - 0.004) < 1e-9)
check_true('current_ch2', abs(sensor.current(2) - 0.04) < 1e-9)

# power(2): same adjacent-register overlap as power(1) above; SHUNT2 low
# byte and BUS2 high byte chosen equal (0x08). SHUNT2=0x0108 (264) ->
# 0.00132 V; BUS2=0x0800 (2048) -> 2.048 V.
connection.set_register(INA3221Full._REG_SHUNT2, 0x01, 0x08)
connection.set_register(INA3221Full._REG_BUS2, 0x08, 0x00)
check_true('power_ch2', abs(sensor.power(2) - (2.048 * 0.0132)) < 1e-9)

# Invalid channel raises ValueError.
try:
    sensor.voltage(4)
    check_true('invalid_channel_raises', False)
except ValueError:
    check_true('invalid_channel_raises', True)

# configure(avg=3, vbus_ct=2, vsh_ct=1, mode=5) preserves channel-enable
# bits (0x7000) from the current Configuration Register.
connection.set_register(INA3221Full._REG_CONFIG, 0x71, 0x27)
sensor.configure(avg=3, vbus_ct=2, vsh_ct=1, mode=5)
config_writes = [w for w in connection.writes if len(w) == 3 and w[0] == INA3221Full._REG_CONFIG]
check_true('configure', config_writes[-1][1] == 0x76 and config_writes[-1][2] == 0x8D)

# enable_channel(2, True): CH2en is bit 13.
connection.set_register(INA3221Full._REG_CONFIG, 0x01, 0x27)
sensor.enable_channel(2, True)
config_writes = [w for w in connection.writes if len(w) == 3 and w[0] == INA3221Full._REG_CONFIG]
check_true('enable_channel', config_writes[-1][1] == 0x21 and config_writes[-1][2] == 0x27)

# channel_enabled(1): CH1en is bit 14.
connection.set_register(INA3221Full._REG_CONFIG, 0x41, 0x27)
check_true('channel_enabled', sensor.channel_enabled(1) is True)

# conversion_ready(): CVRF is bit 0.
connection.set_register(INA3221Full._REG_MASK_EN, 0x00, 0x01)
check_true('conversion_ready', sensor.conversion_ready() is True)

# set_critical_alert(channel=2, limit_v=0.048, latch=True):
# raw = (int(0.048/40e-6) << 3) & 0xFFF8 = (1200 << 3) & 0xFFF8 = 0x2580.
connection.set_register(INA3221Full._REG_MASK_EN, 0x00, 0x00)
sensor.set_critical_alert(2, 0.048, latch=True)
crit_writes = [w for w in connection.writes if len(w) == 3 and w[0] == INA3221Full._REG_CH2_CRIT]
check_true('set_critical_alert_limit', crit_writes[-1][1] == 0x25 and crit_writes[-1][2] == 0x80)
mask_writes = [w for w in connection.writes if len(w) == 3 and w[0] == INA3221Full._REG_MASK_EN]
check_true('set_critical_alert_latch', mask_writes[-1][1] == 0x04 and mask_writes[-1][2] == 0x00)

# set_warning_alert(channel=1, limit_v=0.024, latch=False):
# raw = (int(0.024/40e-6) << 3) & 0xFFF8 = (600 << 3) & 0xFFF8 = 0x12C0.
connection.set_register(INA3221Full._REG_MASK_EN, 0x04, 0x00)
sensor.set_warning_alert(1, 0.024, latch=False)
warn_writes = [w for w in connection.writes if len(w) == 3 and w[0] == INA3221Full._REG_CH1_WARN]
check_true('set_warning_alert_limit', warn_writes[-1][1] == 0x12 and warn_writes[-1][2] == 0xC0)
mask_writes = [w for w in connection.writes if len(w) == 3 and w[0] == INA3221Full._REG_MASK_EN]
check_true('set_warning_alert_latch', mask_writes[-1][1] == 0x04 and mask_writes[-1][2] == 0x00)

# alert_flags(): raw Mask/Enable register.
connection.set_register(INA3221Full._REG_MASK_EN, 0x02, 0x41)
check_true('alert_flags', sensor.alert_flags() == 0x0241)

# set_summation_channels([1], limit_v=0.1) with a stale SCC3 bit (0x1000)
# already set: the fix must clear bits 14:12 (0x7000), not just 15:13
# (0xE000), or SCC3 would incorrectly survive.
connection.set_register(INA3221Full._REG_MASK_EN, 0x10, 0x00)
sensor.set_summation_channels([1], 0.1)
mask_writes = [w for w in connection.writes if len(w) == 3 and w[0] == INA3221Full._REG_MASK_EN]
check_true('set_summation_channels_clears_stale_scc3',
           mask_writes[-1][1] == 0x40 and mask_writes[-1][2] == 0x00)
sum_limit_writes = [w for w in connection.writes if len(w) == 3 and w[0] == INA3221Full._REG_SUM_LIMIT]
check_true('set_summation_channels_limit',
           sum_limit_writes[-1][1] == 0x13 and sum_limit_writes[-1][2] == 0x88)

# summation_value(): raw=0x2328 (9000) -> 9000 * 20e-6 = 0.18 V.
connection.set_register(INA3221Full._REG_SUM, 0x23, 0x28)
check_true('summation_value', abs(sensor.summation_value() - 0.18) < 1e-9)

# set_power_valid_limits(upper_v=8.112, lower_v=4.096):
# raw_upper = (int(8.112/8e-3) << 3) & 0xFFF8 = (1014 << 3) & 0xFFF8 = 0x1FB0
# raw_lower = (int(4.096/8e-3) << 3) & 0xFFF8 = (512 << 3) & 0xFFF8 = 0x1000
sensor.set_power_valid_limits(8.112, 4.096)
pv_upper_writes = [w for w in connection.writes if len(w) == 3 and w[0] == INA3221Full._REG_PV_UPPER]
pv_lower_writes = [w for w in connection.writes if len(w) == 3 and w[0] == INA3221Full._REG_PV_LOWER]
check_true('set_power_valid_upper', pv_upper_writes[-1][1] == 0x1F and pv_upper_writes[-1][2] == 0xB0)
check_true('set_power_valid_lower', pv_lower_writes[-1][1] == 0x10 and pv_lower_writes[-1][2] == 0x00)

# power_valid(): PVF is bit 2.
connection.set_register(INA3221Full._REG_MASK_EN, 0x00, 0x04)
check_true('power_valid', sensor.power_valid() is True)

# shutdown(): reads CONFIG, saves MODE bits, writes CONFIG & 0xFFF8.
connection.set_register(INA3221Full._REG_CONFIG, 0x71, 0x27)
sensor.shutdown()
config_writes = [w for w in connection.writes if len(w) == 3 and w[0] == INA3221Full._REG_CONFIG]
check_true('shutdown', config_writes[-1][1] == 0x71 and config_writes[-1][2] == 0x20)
check_true('shutdown_saves_mode', sensor._mode == 0x07)

# wake(): reads CONFIG, restores saved MODE bits.
connection.set_register(INA3221Full._REG_CONFIG, 0x71, 0x20)
sensor.wake()
config_writes = [w for w in connection.writes if len(w) == 3 and w[0] == INA3221Full._REG_CONFIG]
check_true('wake', config_writes[-1][1] == 0x71 and config_writes[-1][2] == 0x27)

# reset(): writes CONFIG = 0x8000 (RST bit) only.
sensor.reset()
check_true('reset', connection.writes[-1] == bytes([INA3221Full._REG_CONFIG, 0x80, 0x00]))

# manufacturer_id() / die_id()
connection.set_register(INA3221Full._REG_MFR_ID, 0x54, 0x49)
check_true('manufacturer_id', sensor.manufacturer_id() == 0x5449)
connection.set_register(INA3221Full._REG_DIE_ID, 0x32, 0x20)
check_true('die_id', sensor.die_id() == 0x3220)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
