import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.io_expander.pca6408a import Pca6408aMinimal, Pca6408aFull

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
chip = Pca6408aFull(connection)

# Init writes the reset state: OUTPUT 0xFF, POLARITY 0x00, CONFIG 0xFF.
check_true('init_output', connection.registers[0x01] == 0xFF)
check_true('init_polarity', connection.registers[0x02] == 0x00)
check_true('init_config', connection.registers[0x03] == 0xFF)
check_true('init_shadows', chip._shadow == 0xFF and chip._direction == 0xFF)
check_true('init_never_writes_input', all(w[0] != 0x00 for w in connection.writes if len(w) > 1))

# read_port -> INPUT (0x00); port argument ignored.
connection.registers[0x00] = 0xA5
check_true('read_port', chip.read_port() == 0xA5)
check_true('read_port_ignores_port', chip.read_port(1) == 0xA5)

# write_port -> OUTPUT (0x01) and shadow.
chip.write_port(0, 0x3C)
check_true('write_port_register', connection.registers[0x01] == 0x3C)
check_true('write_port_shadow', chip._shadow == 0x3C)

# Direction: output clears the CONFIG bit, input sets it; P7 is bidirectional.
pin7 = chip.pin(7)
pin7.init(Pca6408aFull.OUT)
check_true('p7_output', connection.registers[0x03] == 0x7F)
pin7.init(Pca6408aFull.IN)
check_true('p7_input', connection.registers[0x03] == 0xFF)

# OUTPUT is written before CONFIG when a pin becomes an output.
connection.writes.clear()
chip.pin(1).init(Pca6408aFull.OUT)
check_true('output_before_config', [w[0] for w in connection.writes if len(w) > 1] == [0x01, 0x03])

# Pin read, set, clear, toggle with shadow preservation.
chip.write_port(0, 0x00)
connection.registers[0x00] = 0x04
check_true('pin_read', chip.pin(2).value() == 1)
chip.pin(0).on()
check_true('pin0_on', connection.registers[0x01] == 0x01)
chip.pin(2).on()
check_true('pin2_on_preserves_pin0', connection.registers[0x01] == 0x05)
chip.pin(0).off()
check_true('pin0_off_preserves_pin2', connection.registers[0x01] == 0x04)
chip.pin(0).toggle()
check_true('pin0_toggle_on', connection.registers[0x01] == 0x05)
chip.pin(0).toggle()
check_true('pin0_toggle_off', connection.registers[0x01] == 0x04)

# No pull-ups: requests are rejected.
try:
    chip.pin(3).init(Pca6408aFull.IN, pull=2)
    check_true('pull_up_rejected', False)
except AttributeError:
    check_true('pull_up_rejected', True)

# configure_polarity -> POLARITY.
chip.configure_polarity(0x0F)
check_true('configure_polarity', connection.registers[0x02] == 0x0F)

# set_direction: whole-port write, OUTPUT first, get_direction returns shadow.
connection.writes.clear()
chip.set_direction(0xF0)
check_true('set_direction_register', connection.registers[0x03] == 0xF0)
check_true('set_direction_order', [w[0] for w in connection.writes if len(w) > 1] == [0x01, 0x03])
check_true('get_direction', chip.get_direction() == 0xF0)

# Raw access.
check_true('read_register', chip.read_register(0x03) == 0xF0)
chip.write_register(0x01, 0x81)
check_true('write_register_output_shadow', chip._shadow == 0x81)
chip.write_register(0x03, 0x0F)
check_true('write_register_config_shadow', chip._direction == 0x0F)
try:
    chip.write_register(0x00, 0x00)
    check_true('write_register_input_raises', False)
except ValueError:
    check_true('write_register_input_raises', True)
try:
    chip.read_register(4)
    check_true('read_register_range', False)
except ValueError:
    check_true('read_register_range', True)

# poll_interrupt: changed mask limited to input pins, cache updated.
chip.set_direction(0xF0)
connection.registers[0x00] = 0x00
chip.poll_interrupt()
connection.registers[0x00] = 0x35
check_true('poll_interrupt_masks_outputs', chip.poll_interrupt() == 0x30)
check_true('poll_interrupt_no_change', chip.poll_interrupt() == 0x00)

# watch() on an output pin raises.
chip2 = Pca6408aFull(I2CConnectionMock())
chip2.pin(0).init(Pca6408aFull.OUT)
try:
    chip2.pin(0).watch(lambda p: None)
    check_true('watch_output_raises', False)
except ValueError:
    check_true('watch_output_raises', True)

# reset: every register back to its reset value.
chip.reset()
check_true('reset_output', connection.registers[0x01] == 0xFF)
check_true('reset_polarity', connection.registers[0x02] == 0x00)
check_true('reset_config', connection.registers[0x03] == 0xFF)
check_true('reset_shadows', chip._shadow == 0xFF and chip._direction == 0xFF)

# Minimal class is constructible.
check_true('minimal_i2c', Pca6408aMinimal(I2CConnectionMock()).read_port() == 0)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
