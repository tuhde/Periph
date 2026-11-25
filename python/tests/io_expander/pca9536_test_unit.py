import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.io_expander.pca9536 import Pca9536Minimal, Pca9536Full

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
chip = Pca9536Full(connection)

# Init writes the reset state: OUTPUT 0xFF, POLARITY 0x00, CONFIG 0xFF.
check_true('init_output', connection.registers[0x01] == 0xFF)
check_true('init_polarity', connection.registers[0x02] == 0x00)
check_true('init_config', connection.registers[0x03] == 0xFF)
check_true('init_shadows', chip._shadow == 0xFF and chip._direction == 0xFF)
check_true('init_never_writes_input', all(w[0] != 0x00 for w in connection.writes if len(w) > 1))

# read_port -> INPUT (0x00) masked to bits 3:0; port argument ignored.
connection.registers[0x00] = 0xF5
check_true('read_port_masks_unused_bits', chip.read_port() == 0x05)
check_true('read_port_ignores_port', chip.read_port(1) == 0x05)

# write_port -> OUTPUT (0x01) with bits 7:4 written as 1.
chip.write_port(0, 0x0C)
check_true('write_port_register', connection.registers[0x01] == 0xFC)
check_true('write_port_shadow', chip._shadow == 0xFC)
chip.write_port(0, 0xFF)
check_true('write_port_masks_high_nibble', connection.registers[0x01] == 0xFF)

# Direction: output clears the CONFIG bit, input sets it; P3 is bidirectional.
pin3 = chip.pin(3)
pin3.init(Pca9536Full.OUT)
check_true('p3_output', connection.registers[0x03] == 0xF7)
pin3.init(Pca9536Full.IN)
check_true('p3_input', connection.registers[0x03] == 0xFF)

# OUTPUT is written before CONFIG when a pin becomes an output.
connection.writes.clear()
chip.pin(1).init(Pca9536Full.OUT)
check_true('output_before_config', [w[0] for w in connection.writes if len(w) > 1] == [0x01, 0x03])

# Pin range is 0-3.
try:
    chip.pin(4)
    check_true('pin_range', False)
except ValueError:
    check_true('pin_range', True)

# Pin read, set, clear, toggle with shadow preservation.
chip.write_port(0, 0x00)
connection.registers[0x00] = 0x04
check_true('pin_read', chip.pin(2).value() == 1)
chip.pin(0).on()
check_true('pin0_on', connection.registers[0x01] == 0xF1)
chip.pin(2).on()
check_true('pin2_on_preserves_pin0', connection.registers[0x01] == 0xF5)
chip.pin(0).off()
check_true('pin0_off_preserves_pin2', connection.registers[0x01] == 0xF4)
chip.pin(0).toggle()
check_true('pin0_toggle_on', connection.registers[0x01] == 0xF5)
chip.pin(0).toggle()
check_true('pin0_toggle_off', connection.registers[0x01] == 0xF4)

# The always-on weak pull-up cannot be controlled: requests are rejected.
try:
    chip.pin(3).init(Pca9536Full.IN, pull=2)
    check_true('pull_up_rejected', False)
except AttributeError:
    check_true('pull_up_rejected', True)

# configure_polarity -> POLARITY.
chip.configure_polarity(0x0F)
check_true('configure_polarity', connection.registers[0x02] == 0x0F)
chip.configure_polarity(0x00)

# set_direction: whole-port write, OUTPUT first, get_direction returns 4 bits.
connection.writes.clear()
chip.set_direction(0x0C)
check_true('set_direction_register', connection.registers[0x03] == 0xFC)
check_true('set_direction_order', [w[0] for w in connection.writes if len(w) > 1] == [0x01, 0x03])
check_true('get_direction', chip.get_direction() == 0x0C)

# Raw access.
check_true('read_register', chip.read_register(0x03) == 0xFC)
chip.write_register(0x01, 0xF1)
check_true('write_register_output_shadow', chip._shadow == 0xF1)
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

# poll_changes: changed mask limited to input pins, cache updated.
chip.set_direction(0x0C)
connection.registers[0x00] = 0x00
chip.poll_changes()
connection.registers[0x00] = 0x0D
check_true('poll_changes_masks_outputs', chip.poll_changes() == 0x0C)
check_true('poll_changes_no_change', chip.poll_changes() == 0x00)

# A polarity change refreshes the cache and is not reported as a change.
connection.registers[0x00] = 0x00
chip.configure_polarity(0x0C)
check_true('polarity_not_reported', chip.poll_changes() == 0x00)
chip.configure_polarity(0x00)
chip.poll_changes()

# watch_changes calls back with the changed mask.
import time
seen = []
connection.registers[0x00] = 0x00
chip.poll_changes()
chip.watch_changes(seen.append, 0.002)
connection.registers[0x00] = 0x04
time.sleep(0.1)
chip.unwatch_changes()
check_true('watch_changes_callback', seen == [0x04])

# watch() on an output pin raises.
chip2 = Pca9536Full(I2CConnectionMock())
chip2.pin(0).init(Pca9536Full.OUT)
try:
    chip2.pin(0).watch(lambda p: None)
    check_true('watch_output_raises', False)
except ValueError:
    check_true('watch_output_raises', True)

# Pin.watch is polling-based and filters by trigger.
conn3 = I2CConnectionMock()
chip3 = Pca9536Full(conn3)
hits = []
chip3.pin(2).watch(lambda p: hits.append(p._n), Pca9536Full.IRQ_RISING)
conn3.registers[0x00] = 0x04
time.sleep(0.1)
conn3.registers[0x00] = 0x00
time.sleep(0.1)
chip3.unwatch_changes()
check_true('pin_watch_rising_only', hits == [2])

# reset: every register back to its reset value.
chip.reset()
check_true('reset_output', connection.registers[0x01] == 0xFF)
check_true('reset_polarity', connection.registers[0x02] == 0x00)
check_true('reset_config', connection.registers[0x03] == 0xFF)
check_true('reset_shadows', chip._shadow == 0xFF and chip._direction == 0xFF)

# Minimal class is constructible.
check_true('minimal_i2c', Pca9536Minimal(I2CConnectionMock()).read_port() == 0)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
