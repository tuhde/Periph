import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.io_expander.pca9539 import Pca9539Minimal, Pca9539Full

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


def data_writes(connection):
    return [w for w in connection.writes if len(w) > 1]


connection = I2CConnectionMock()
chip = Pca9539Full(connection)

# Init writes the reset state of both ports as two-byte pair writes.
check_true('init_output', connection.registers[0x02] == 0xFF and connection.registers[0x03] == 0xFF)
check_true('init_polarity', connection.registers[0x04] == 0x00 and connection.registers[0x05] == 0x00)
check_true('init_config', connection.registers[0x06] == 0xFF and connection.registers[0x07] == 0xFF)
check_true('init_shadows', chip._shadow == 0xFFFF and chip._direction == 0xFFFF)
check_true('init_pair_writes', [bytes(w) for w in data_writes(connection)] == [
    b'\x02\xff\xff', b'\x04\x00\x00', b'\x06\xff\xff'])
check_true('init_never_writes_input', all(w[0] > 0x01 for w in data_writes(connection)))

# read_port -> INPUT0 / INPUT1; read_all -> one two-byte read.
connection.registers[0x00] = 0xA5
connection.registers[0x01] = 0x5A
check_true('read_port_0', chip.read_port(0) == 0xA5)
check_true('read_port_1', chip.read_port(1) == 0x5A)
check_true('read_all', chip.read_all() == 0x5AA5)
try:
    chip.read_port(2)
    check_true('read_port_range', False)
except ValueError:
    check_true('read_port_range', True)

# write_port -> OUTPUT0 / OUTPUT1 and shadow.
chip.write_port(0, 0x3C)
check_true('write_port_0_register', connection.registers[0x02] == 0x3C)
chip.write_port(1, 0xC3)
check_true('write_port_1_register', connection.registers[0x03] == 0xC3)
check_true('write_port_shadow', chip._shadow == 0xC33C)

# write_all -> one two-byte write at OUTPUT0.
connection.writes.clear()
chip.write_all(0x1234)
check_true('write_all_registers', connection.registers[0x02] == 0x34 and connection.registers[0x03] == 0x12)
check_true('write_all_single_transfer', len(data_writes(connection)) == 1)
check_true('write_all_shadow', chip._shadow == 0x1234)

# Direction: output clears the CONFIG bit of its port, input sets it.
chip.write_port(0, 0xFF)
chip.write_port(1, 0xFF)
pin7 = chip.pin(7)
pin7.init(Pca9539Full.OUT)
check_true('p07_output', connection.registers[0x06] == 0x7F and connection.registers[0x07] == 0xFF)
pin7.init(Pca9539Full.IN)
check_true('p07_input', connection.registers[0x06] == 0xFF)
pin12 = chip.pin(12)
pin12.init(Pca9539Full.OUT)
check_true('p14_output_port1', connection.registers[0x07] == 0xEF and connection.registers[0x06] == 0xFF)
pin12.init(Pca9539Full.IN)
check_true('p14_input_port1', connection.registers[0x07] == 0xFF)

# OUTPUT is written before CONFIG when a pin becomes an output.
connection.writes.clear()
chip.pin(9).init(Pca9539Full.OUT)
check_true('output_before_config', [w[0] for w in data_writes(connection)] == [0x03, 0x07])
chip.pin(9).init(Pca9539Full.IN)

# Pin read, set, clear, toggle with shadow preservation (both ports).
chip.write_all(0x0000)
connection.registers[0x00] = 0x04
connection.registers[0x01] = 0x80
check_true('pin_read_port0', chip.pin(2).value() == 1)
check_true('pin_read_port1', chip.pin(15).value() == 1)
check_true('pin_read_low', chip.pin(3).value() == 0)
chip.pin(0).on()
check_true('pin0_on', connection.registers[0x02] == 0x01)
chip.pin(2).on()
check_true('pin2_on_preserves_pin0', connection.registers[0x02] == 0x05)
chip.pin(0).off()
check_true('pin0_off_preserves_pin2', connection.registers[0x02] == 0x04)
chip.pin(8).on()
check_true('pin8_on_port1', connection.registers[0x03] == 0x01 and connection.registers[0x02] == 0x04)
chip.pin(8).toggle()
check_true('pin8_toggle_off', connection.registers[0x03] == 0x00)
chip.pin(8).toggle()
check_true('pin8_toggle_on', connection.registers[0x03] == 0x01)

# No pull-ups: requests are rejected.
try:
    chip.pin(3).init(Pca9539Full.IN, pull=2)
    check_true('pull_up_rejected', False)
except AttributeError:
    check_true('pull_up_rejected', True)

# configure_polarity -> POLARITY0/1 in one two-byte write.
connection.writes.clear()
chip.configure_polarity(0xF00F)
check_true('configure_polarity', connection.registers[0x04] == 0x0F and connection.registers[0x05] == 0xF0)
check_true('configure_polarity_single_write', bytes(data_writes(connection)[0]) == b'\x04\x0f\xf0')

# set_direction: 16-bit write, OUTPUT first, get_direction returns shadow.
connection.writes.clear()
chip.set_direction(0xFF00)
check_true('set_direction_registers', connection.registers[0x06] == 0x00 and connection.registers[0x07] == 0xFF)
check_true('set_direction_order', [w[0] for w in data_writes(connection)] == [0x02, 0x06])
check_true('get_direction', chip.get_direction() == 0xFF00)

# Raw access.
check_true('read_register', chip.read_register(0x07) == 0xFF)
chip.write_register(0x03, 0x81)
check_true('write_register_output1_shadow', (chip._shadow >> 8) == 0x81)
chip.write_register(0x06, 0x0F)
check_true('write_register_config0_shadow', chip._direction == 0xFF0F)
chip.write_register(0x05, 0x3C)
check_true('write_register_polarity1_shadow', chip._polarity == 0x3C0F)
for bad in (0, 1, 8):
    try:
        chip.write_register(bad, 0x00)
        check_true('write_register_bad_%d' % bad, False)
    except ValueError:
        check_true('write_register_bad_%d' % bad, True)
try:
    chip.read_register(8)
    check_true('read_register_range', False)
except ValueError:
    check_true('read_register_range', True)

# poll_interrupt: 16-bit changed mask limited to input pins, cache updated.
chip.set_direction(0xF0F0)
connection.registers[0x00] = 0x00
connection.registers[0x01] = 0x00
chip.poll_interrupt()
connection.registers[0x00] = 0x35
connection.registers[0x01] = 0x9A
check_true('poll_interrupt_masks_outputs', chip.poll_interrupt() == 0x9030)
check_true('poll_interrupt_no_change', chip.poll_interrupt() == 0x0000)

# on_interrupt callback receives the changed mask via the INT pin handler.
seen = []
chip.on_interrupt(lambda m: seen.append(m), int_pin=None)
chip.off_interrupt()
check_true('off_interrupt_clears_callback', chip._callback is None)

# Interrupt errata (datasheet 8.2.4.1): with a subscription active, poll_interrupt
# rewrites OUTPUT0 after the INPUT read; without one it does not.
errata_conn = I2CConnectionMock()
errata = Pca9539Full(errata_conn)
n0 = len(data_writes(errata_conn))
errata.poll_interrupt()
check_true('errata_no_subscription_no_write', len(data_writes(errata_conn)) == n0)
errata._callback = lambda m: None
errata.poll_interrupt()
check_true('errata_subscribed_rewrites_output0', len(data_writes(errata_conn)) == n0 + 1 and data_writes(errata_conn)[-1][0] == 0x02)

# watch() on an output pin raises.
chip2 = Pca9539Full(I2CConnectionMock())
chip2.pin(10).init(Pca9539Full.OUT)
try:
    chip2.pin(10).watch(lambda p: None)
    check_true('watch_output_raises', False)
except ValueError:
    check_true('watch_output_raises', True)

# reset: every register back to its reset value.
chip.reset()
check_true('reset_output', connection.registers[0x02] == 0xFF and connection.registers[0x03] == 0xFF)
check_true('reset_polarity', connection.registers[0x04] == 0x00 and connection.registers[0x05] == 0x00)
check_true('reset_config', connection.registers[0x06] == 0xFF and connection.registers[0x07] == 0xFF)
check_true('reset_shadows', chip._shadow == 0xFFFF and chip._direction == 0xFFFF)

# Minimal class is constructible.
check_true('minimal_i2c', Pca9539Minimal(I2CConnectionMock()).read_port(0) == 0)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
