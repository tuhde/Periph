import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.gas.ens160 import ENS160Full

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

# PART_ID read during __init__ must return 0x0160 (little-endian: 0x60, 0x01)
# or construction raises ValueError.
connection.set_register(ENS160Full._REG_PART_ID, 0x60, 0x01)

sensor = ENS160Full(connection)
check_true('init', True)

opmode_writes = [w for w in connection.writes if len(w) == 2 and w[0] == ENS160Full._REG_OPMODE]
check_true('init_writes_idle_then_standard',
           opmode_writes[0][1] == ENS160Full._OPMODE_IDLE and
           opmode_writes[-1][1] == ENS160Full._OPMODE_STANDARD)

# DEVICE_STATUS: NEWDAT (bit1) set, VALIDITY_FLAG (bits 3:2) = 0 (OK).
connection.set_register(ENS160Full._REG_DEVICE_STATUS, 0x02)
check_true('status_ok', sensor.status() == ENS160Full.VALIDITY_OK)

# DATA_AQI block (5 bytes): aqi=2, tvoc_ppb=150 (0x0096 LE), eco2_ppm=500 (0x01F4 LE).
connection.set_register(ENS160Full._REG_DATA_AQI, 2, 0x96, 0x00, 0xF4, 0x01)

data = sensor.read_air_quality()
check_true('read_air_quality_aqi', data['aqi'] == 2)
check_true('read_air_quality_tvoc', data['tvoc_ppb'] == 150.0)
check_true('read_air_quality_eco2', data['eco2_ppm'] == 500.0)

check_true('read_tvoc', sensor.read_tvoc() == 150.0)
check_true('read_eco2', sensor.read_eco2() == 500.0)
check_true('read_aqi', sensor.read_aqi() == 2)
check_true('read_ethanol_aliases_tvoc', sensor.read_ethanol() == 150.0)

sensor.set_compensation(25.0, 50.0)
temp_raw = round((25.0 + 273.15) * 64)
rh_raw = round(50.0 * 512)
check_true('set_compensation_temp',
           connection.registers[ENS160Full._REG_TEMP_IN] == (temp_raw & 0xFF) and
           connection.registers[ENS160Full._REG_TEMP_IN + 1] == ((temp_raw >> 8) & 0xFF))
check_true('set_compensation_rh',
           connection.registers[ENS160Full._REG_RH_IN] == (rh_raw & 0xFF) and
           connection.registers[ENS160Full._REG_RH_IN + 1] == ((rh_raw >> 8) & 0xFF))

# DATA_T/DATA_RH actuals (4 bytes): temp_raw=0x5202 (~55.0 degC), rh_raw=0x6400 (50.0 %RH).
connection.set_register(ENS160Full._REG_DATA_T, 0x02, 0x52, 0x00, 0x64)
actuals = sensor.read_compensation_actuals()
check_true('read_compensation_actuals_temp', abs(actuals['temp_celsius'] - ((0x5202 / 64.0) - 273.15)) < 1e-9)
check_true('read_compensation_actuals_rh', actuals['rh_percent'] == 0x6400 / 512.0)

# GPR_READ sensor 1 (offset 0): raw=2048 -> resistance = 2**(2048/2048) = 2.0 Ohm.
connection.set_register(ENS160Full._REG_GPR_READ, 0x00, 0x08)
check_true('read_raw_resistance_1', sensor.read_raw_resistance(1) == 2.0)

# GPR_READ sensor 4 (offset 6): raw=4096 -> resistance = 2**(4096/2048) = 4.0 Ohm.
connection.set_register(ENS160Full._REG_GPR_READ + 6, 0x00, 0x10)
check_true('read_raw_resistance_4', sensor.read_raw_resistance(4) == 4.0)

try:
    sensor.read_raw_resistance(2)
    check_true('read_raw_resistance_invalid_raises', False)
except ValueError:
    check_true('read_raw_resistance_invalid_raises', True)

# GET_APPVER response at GPR_READ+4: major=1, minor=2, release=3.
connection.set_register(ENS160Full._REG_GPR_READ + 4, 1, 2, 3)
check_true('get_firmware_version', sensor.get_firmware_version() == (1, 2, 3))

sensor.configure_interrupt(enabled=True, active_high=True, push_pull=True, on_data=True, on_gpr=True)
config_writes = [w for w in connection.writes if len(w) == 2 and w[0] == ENS160Full._REG_CONFIG]
check_true('configure_interrupt', config_writes[-1][1] == 0x6B)

sensor.sleep()
check_true('sleep', connection.writes[-1] == bytes([ENS160Full._REG_OPMODE, ENS160Full._OPMODE_DEEP_SLEEP]))

sensor.wake()
check_true('wake', connection.writes[-1] == bytes([ENS160Full._REG_OPMODE, ENS160Full._OPMODE_STANDARD]))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
