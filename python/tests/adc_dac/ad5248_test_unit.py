import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.adc_dac.ad5248 import AD5248Full

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
chip = AD5248Full(connection)
check_true('init_no_traffic', connection.writes == [])

# set_wiper(0, 100) -> [0x00, 100]; set_wiper(1, 200) -> [0x80, 200].
chip.set_wiper(0, 100)
check_true('set_wiper_ch0', connection.writes[-1] == bytes([0x00, 100]))
chip.set_wiper(1, 200)
check_true('set_wiper_ch1', connection.writes[-1] == bytes([0x80, 200]))
chip.set_wiper(0, 999)
check_true('set_wiper_clamps_high', connection.writes[-1] == bytes([0x00, 255]))
chip.set_wiper(0, -5)
check_true('set_wiper_clamps_low', connection.writes[-1] == bytes([0x00, 0]))
try:
    chip.set_wiper(2, 0)
    check_true('invalid_channel_raises', False)
except ValueError:
    check_true('invalid_channel_raises', True)

# get_wiper sends the instruction byte alone, then reads one byte.
chip.set_wiper(0, 100)
chip.set_wiper(1, 200)
connection.writes.clear()
check_true('get_wiper_ch0', chip.get_wiper(0) == 100)
check_true('get_wiper_instruction_alone', connection.writes == [bytes([0x00])])
check_true('get_wiper_ch1', chip.get_wiper(1) == 200)

# Resistance model: R = D/256*RAB + 2*RW (RAB=10k, RW=160).
chip.set_wiper(0, 128)
check_true('get_resistance', abs(chip.get_resistance(0) - 5320.0) < 1e-9)
chip.set_wiper(0, 0)
check_true('get_resistance_zero_scale', abs(chip.get_resistance(0) - 320.0) < 1e-9)
chip.set_resistance(1, 5320.0)
check_true('set_resistance', connection.writes[-1] == bytes([0x80, 128]))
chip.set_resistance(1, 1.0)
check_true('set_resistance_clamps_low', connection.writes[-1] == bytes([0x80, 0]))
chip.set_resistance(1, 1e9)
check_true('set_resistance_clamps_high', connection.writes[-1] == bytes([0x80, 255]))

# Full: set_both / ratio.
chip.set_both(10, 20)
check_true('set_both', connection.writes[-2:] == [bytes([0x00, 10]), bytes([0x80, 20])])
chip.set_ratio(0, 0.5)
check_true('set_ratio', connection.writes[-1] == bytes([0x00, 128]))
chip.set_ratio(0, 1.0)
check_true('set_ratio_clamps_255', connection.writes[-1] == bytes([0x00, 255]))
chip.set_wiper(1, 64)
check_true('get_ratio', abs(chip.get_ratio(1) - 0.25) < 1e-9)

# increment reads, adds, clamps, writes.
chip.set_wiper(0, 250)
check_true('increment_clamps', chip.increment(0, 10) == 255 and connection.writes[-1] == bytes([0x00, 255]))
check_true('decrement', chip.increment(0, -5) == 250 and connection.writes[-1] == bytes([0x00, 250]))

# shutdown writes the instruction with SD=1 and the current code; resume clears SD.
chip.set_wiper(1, 77)
check_true('not_shutdown_initially', not chip.is_shutdown(1))
chip.shutdown(1)
check_true('shutdown_write', connection.writes[-1] == bytes([0xC0, 77]))
check_true('is_shutdown', chip.is_shutdown(1) and not chip.is_shutdown(0))
chip.set_wiper(1, 99)
check_true('set_wiper_keeps_sd', connection.writes[-1] == bytes([0xC0, 99]))
chip.resume(1)
check_true('resume_write', connection.writes[-1] == bytes([0x80, 99]))
check_true('resume_clears_state', not chip.is_shutdown(1))

# reset writes midscale to both channels with SD=0.
chip.shutdown(0)
chip.reset()
check_true('reset_writes', connection.writes[-2:] == [bytes([0x00, 0x80]), bytes([0x80, 0x80])])
check_true('reset_clears_shutdown', not chip.is_shutdown(0))

# set_rab changes the model without I2C traffic.
n = len(connection.writes)
chip.set_rab(50000.0, 100.0)
check_true('set_rab_no_traffic', len(connection.writes) == n)
chip.set_wiper(0, 128)
check_true('set_rab_resistance', abs(chip.get_resistance(0) - (25000.0 + 200.0)) < 1e-9)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
