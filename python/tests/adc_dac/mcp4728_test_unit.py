import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.adc_dac.mcp4728 import MCP4728Full

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
dac = MCP4728Full(connection)
check_true('init', True)

# set_voltage(channel=1, 0.5) -> code=2048 (0x800). Multi-Write:
# byte1 = 0x40 | (ch&3)<<1 | udac=0 -> 0x40 | 0x02 = 0x42
# byte2 = (vref<<7)|(pd<<5)|(gain<<4)|(code>>8&0xF) = 0x08
# byte3 = code & 0xFF = 0x00
dac.set_voltage(1, 0.5)
check_true('set_voltage', connection.writes[-1] == bytes([0x42, 0x08, 0x00]))

# set_voltage clamps to [0.0, 1.0].
dac.set_voltage(1, 2.0)
check_true('set_voltage_clamps_high', connection.writes[-1] == bytes([0x42, 0x0F, 0xFF]))

# set_raw(channel=3, code=4095) -> byte1 = 0x40 | (3<<1) = 0x46, byte2=0x0F, byte3=0xFF.
dac.set_raw(3, 4095)
check_true('set_raw', connection.writes[-1] == bytes([0x46, 0x0F, 0xFF]))

# set_raw clamps channel to [0,3] and code to [0,4095].
dac.set_raw(9, 9000)
check_true('set_raw_clamps', connection.writes[-1] == bytes([0x46, 0x0F, 0xFF]))

# set_all([0.0, 1.0, 0.5, 0.25]) -> Fast Write, 8 bytes, PD=00 for all.
# ch A: code=0 -> [0x00, 0x00]
# ch B: code=4095 -> [0x0F, 0xFF]
# ch C: code=2048 -> [0x08, 0x00]
# ch D: code=round(0.25*4095)=1024 (0x400) -> [0x04, 0x00]
dac.set_all([0.0, 1.0, 0.5, 0.25])
check_true('set_all', connection.writes[-1] == bytes([0x00, 0x00, 0x0F, 0xFF, 0x08, 0x00, 0x04, 0x00]))

try:
    dac.set_all([0.0, 1.0, 0.5])
    check_true('set_all_wrong_length_raises', False)
except ValueError:
    check_true('set_all_wrong_length_raises', True)

# set_voltage_eeprom(channel=2, 0.5, vref=1, gain=2) -> code=2048.
# Single Write byte1 = 0x58 | (2&3)<<1 = 0x58|0x04 = 0x5C
# byte2 = (vref<<7)|(pd<<5)|(gain<<4)|(code>>8&0xF) = 0x80|0x00|0x10|0x08 = 0x98
# byte3 = 0x00
dac.set_voltage_eeprom(2, 0.5, 1, 2)
check_true('set_voltage_eeprom', connection.writes[-1] == bytes([0x5C, 0x98, 0x00]))

# set_raw_eeprom(channel=0, code=4095, vref=0, gain=1) -> byte1=0x58, byte2=0x0F, byte3=0xFF.
dac.set_raw_eeprom(0, 4095, 0, 1)
check_true('set_raw_eeprom', connection.writes[-1] == bytes([0x58, 0x0F, 0xFF]))

# set_all_eeprom: fractions=[0.0,1.0,0.5,0.25], vrefs=[0,1,0,1], gains=[1,2,1,2].
# Command byte: 0x50 | 0x00 = 0x50.
# ch A: v=0,g=0,code=0    -> [0x00, 0x00]
# ch B: v=1,g=1,code=4095 -> [(1<<7)|(1<<4)|0x0F, 0xFF] = [0x9F, 0xFF]
# ch C: v=0,g=0,code=2048 -> [0x08, 0x00]
# ch D: v=1,g=1,code=1024 -> [(1<<7)|(1<<4)|0x04, 0x00] = [0x94, 0x00]
dac.set_all_eeprom([0.0, 1.0, 0.5, 0.25], [0, 1, 0, 1], [1, 2, 1, 2])
check_true('set_all_eeprom', connection.writes[-1] == bytes(
    [0x50, 0x00, 0x00, 0x9F, 0xFF, 0x08, 0x00, 0x94, 0x00]))

try:
    dac.set_all_eeprom([0.0, 1.0], [0, 1], [1, 2])
    check_true('set_all_eeprom_wrong_length_raises', False)
except ValueError:
    check_true('set_all_eeprom_wrong_length_raises', True)

# set_vref(1, 0, 1, 0) -> byte1 = 0x80 | (1<<3)|(0<<2)|(1<<1)|0 = 0x80|0x08|0x02 = 0x8A
dac.set_vref(1, 0, 1, 0)
check_true('set_vref', connection.writes[-1] == bytes([0x8A]))

# set_gain(1, 2, 1, 2) -> byte1 = 0xC0 | (0<<3)|(1<<2)|(0<<1)|1 = 0xC0|0x04|0x01 = 0xC5
dac.set_gain(1, 2, 1, 2)
check_true('set_gain', connection.writes[-1] == bytes([0xC5]))

# set_power_down(0, 1, 2, 3):
# byte1 = 0xA0 | (p2(0)<<4)|(p1(0)<<3)|(p2(1)<<2)|(p1(1)<<1) = 0xA0|0|0|0|(1<<1)=0xA2
# byte2 = (p2(2)<<6)|(p1(2)<<5)|(p2(3)<<4)|(p1(3)<<3) = (1<<6)|0|(1<<4)|(1<<3) = 0x40|0x10|0x08=0x58
dac.set_power_down(0, 1, 2, 3)
check_true('set_power_down', connection.writes[-1] == bytes([0xA2, 0x58]))

# read(): 24-byte response, all read via plain read() (no register write). Use the
# streamed read queue since MCP4728 issues no register-select write before reading.
# Channel A input: vref=0,pd=0,gain=0,code=0x123 -> byte1=0x01,byte2=0x23 (top nibble=0x1)
#   byte1 layout: [V_REF PD1 PD0 Gx D11-D8] = 0b0_00_0_0001 = 0x01
# Channel A EEPROM: vref=1,pd=0,gain=1,code=0x0AB -> byte1=(1<<7)|(1<<4)|0x0=0x90, byte2=0xAB
buf = bytearray(24)
buf[0] = 0x80  # RDY/BSY=1 (eeprom ready)
buf[1] = 0x01
buf[2] = 0x23
buf[12 + 1] = 0x90
buf[12 + 2] = 0xAB
connection.queue_read(bytes(buf))
result = dac.read()
check_true('read_length', len(result) == 4)
check_true('read_ch_a_code', result[0]['code'] == 0x123)
check_true('read_ch_a_vref', result[0]['vref'] == 0)
check_true('read_ch_a_gain', result[0]['gain'] == 1)
check_true('read_ch_a_power_down', result[0]['power_down'] == 0)
check_true('read_ch_a_eeprom_code', result[0]['eeprom_code'] == 0xAB)
check_true('read_ch_a_eeprom_vref', result[0]['eeprom_vref'] == 1)
check_true('read_ch_a_eeprom_gain', result[0]['eeprom_gain'] == 2)
check_true('read_ch_a_eeprom_ready', result[0]['eeprom_ready'] is True)

connection.queue_read(bytes([0x80]))
check_true('is_eeprom_ready_true', dac.is_eeprom_ready() is True)
connection.queue_read(bytes([0x00]))
check_true('is_eeprom_ready_false', dac.is_eeprom_ready() is False)

# software_update()/wake_up()/reset(): General Call commands.
dac.software_update()
check_true('software_update', connection.writes[-1] == bytes([0x00, 0x08]))
dac.wake_up()
check_true('wake_up', connection.writes[-1] == bytes([0x00, 0x09]))
dac.reset()
check_true('reset', connection.writes[-1] == bytes([0x00, 0x06]))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
