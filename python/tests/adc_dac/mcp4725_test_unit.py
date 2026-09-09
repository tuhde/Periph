import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.adc_dac.mcp4725 import MCP4725Full

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
sensor = MCP4725Full(connection)
check_true('init', True)

# set_voltage(0.5) -> code = round(0.5*4095) = 2048 (0x800), PD=00.
# Fast Write byte1 = (PD<<4)|(code>>8 & 0xF) = 0x08, byte2 = code & 0xFF = 0x00.
sensor.set_voltage(0.5)
check_true('set_voltage', connection.writes[-1] == bytes([0x08, 0x00]))

# set_voltage clamps to [0.0, 1.0].
sensor.set_voltage(2.0)
check_true('set_voltage_clamps_high', connection.writes[-1] == bytes([0x0F, 0xFF]))
sensor.set_voltage(-1.0)
check_true('set_voltage_clamps_low', connection.writes[-1] == bytes([0x00, 0x00]))

# set_raw(4095) -> byte1=0x0F, byte2=0xFF.
sensor.set_raw(4095)
check_true('set_raw', connection.writes[-1] == bytes([0x0F, 0xFF]))

# set_raw clamps to [0, 4095].
sensor.set_raw(5000)
check_true('set_raw_clamps', connection.writes[-1] == bytes([0x0F, 0xFF]))

# set_voltage_eeprom(0.5) -> code=2048 (0x800). Write DAC+EEPROM:
# byte1 = 0x60 | (PD&3)<<1 = 0x60, byte2 = (code>>4)&0xFF = 0x80, byte3 = (code&0xF)<<4 = 0x00.
sensor.set_voltage_eeprom(0.5)
check_true('set_voltage_eeprom', connection.writes[-1] == bytes([0x60, 0x80, 0x00]))

# set_raw_eeprom(4095) -> byte2=(4095>>4)&0xFF=0xFF, byte3=(4095&0xF)<<4=0xF0.
sensor.set_raw_eeprom(4095)
check_true('set_raw_eeprom', connection.writes[-1] == bytes([0x60, 0xFF, 0xF0]))

# read(): 5-byte response. rdy_bsy=1, por=1, pd_dac=2, code=0x123, eeprom
# byte4=0x40 (0100_0000) -> PD1:PD0 at bits 6:5 = 2, eeprom_code=0xAB.
connection.set_register(0x00, 0xC8, 0x12, 0x30, 0x40, 0xAB)
data = sensor.read()
check_true('read_code', data['code'] == 0x123)
check_true('read_voltage_fraction', abs(data['voltage_fraction'] - (0x123 / 4095.0)) < 1e-9)
check_true('read_power_down', data['power_down'] == 2)
check_true('read_eeprom_code', data['eeprom_code'] == 0xAB)
check_true('read_eeprom_power_down', data['eeprom_power_down'] == 2)
check_true('read_eeprom_ready', data['eeprom_ready'] is True)

# set_power_down(2): first reads the current 2-byte DAC code (0x0AB), then Fast
# Writes that same code back with PD=2. byte1=(2<<4)|(0xAB>>8&0xF)=0x20, byte2=0xAB.
connection.set_register(0x00, 0x00, 0xAB)
sensor.set_power_down(2)
check_true('set_power_down', connection.writes[-1] == bytes([0x20, 0xAB]))

# set_power_down clamps mode to [0, 3].
connection.set_register(0x00, 0x00, 0x00)
sensor.set_power_down(9)
check_true('set_power_down_clamps', connection.writes[-1] == bytes([0x30, 0x00]))

# wake_up() / reset(): General Call commands -> [ADDR_GENERAL_CALL, cmd].
sensor.wake_up()
check_true('wake_up', connection.writes[-1] == bytes([0x00, 0x09]))
sensor.reset()
check_true('reset', connection.writes[-1] == bytes([0x00, 0x06]))

# is_eeprom_ready(): RDY/BSY bit (bit 7) of the status byte.
connection.set_register(0x00, 0x80)
check_true('is_eeprom_ready_true', sensor.is_eeprom_ready() is True)
connection.set_register(0x00, 0x00)
check_true('is_eeprom_ready_false', sensor.is_eeprom_ready() is False)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
