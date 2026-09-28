import sys

from periph.connection.sipo_mock import SiPoConnectionMock
from periph.chips.io_expander.tpic6b595 import Tpic6b595Minimal, Tpic6b595Full

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


# --- Construction (single device, SRCLR wired) ---
connection = SiPoConnectionMock()
chip = Tpic6b595Minimal(connection)
check_true('init_clears', connection.clear_count == 1)
check_true('init_writes_all_zero', connection.writes[-1] == bytes([0x00]))
check_true('init_shadow_zero', chip._shadow == bytearray([0x00]))

# --- Construction with SRCLR not wired: clear() raises, driver swallows it ---
no_srclr = SiPoConnectionMock(has_srclr=False)
Tpic6b595Minimal(no_srclr)
check_true('init_no_srclr_does_not_raise', True)
check_true('init_no_srclr_still_flushes', no_srclr.writes[-1] == bytes([0x00]))
check_true('init_no_srclr_clear_not_counted', no_srclr.clear_count == 0)

# --- pin() proxy: on/off/toggle/value/set ---
pin3 = chip.pin(3)
pin3.on()
check_true('pin3_on_shadow', chip._shadow[0] == 0x08)
check_true('pin3_on_wire', connection.writes[-1] == bytes([0x08]))
check_true('pin3_value_reads_shadow', pin3.value() == 1)

pin3.off()
check_true('pin3_off_shadow', chip._shadow[0] == 0x00)
check_true('pin3_value_after_off', pin3.value() == 0)

pin3.toggle()
check_true('pin3_toggle_to_on', chip._shadow[0] == 0x08)
pin3.toggle()
check_true('pin3_toggle_to_off', chip._shadow[0] == 0x00)

pin5 = chip.pin(5)
pin5.set(True)
check_true('pin5_set_true', chip._shadow[0] == 0x20)
pin3.on()
check_true('pin3_on_preserves_pin5', chip._shadow[0] == 0x28)
pin5.set(False)
check_true('pin5_set_false_preserves_pin3', chip._shadow[0] == 0x08)

# --- write_port(): direct port write, replaces the whole byte ---
chip.write_port(0, 0x3C)
check_true('write_port_shadow', chip._shadow[0] == 0x3C)
check_true('write_port_wire', connection.writes[-1] == bytes([0x3C]))

# --- fill()/off() ---
chip.fill(True)
check_true('fill_true_shadow', chip._shadow == bytearray([0xFF]))
check_true('fill_true_wire', connection.writes[-1] == bytes([0xFF]))
chip.off()
check_true('off_is_fill_false', chip._shadow == bytearray([0x00]))
check_true('off_wire', connection.writes[-1] == bytes([0x00]))

# --- Cascading wire-order reversal (num_devices=3) ---
# Device 0 is nearest the controller; the wire-order is REVERSED so the
# farthest device (index num_devices-1) is shifted in first. See
# specs/io_expander/tpic6b595.md's Data Conversion section.
cascade_conn = SiPoConnectionMock()
cascade = Tpic6b595Minimal(cascade_conn, num_devices=3)
cascade.write_port(0, 0xAA)
cascade.write_port(1, 0xBB)
cascade.write_port(2, 0xCC)
check_true('cascade_wire_order_reversed', cascade_conn.writes[-1] == bytes([0xCC, 0xBB, 0xAA]))

pin_far = cascade.pin(16)  # device 2, bit 0
pin_far.on()
check_true('cascade_far_device_pin', cascade._shadow[2] == 0xCD)
check_true('cascade_far_device_wire', cascade_conn.writes[-1] == bytes([0xCD, 0xBB, 0xAA]))

# --- Full.clear(): propagates to the connection, or raises if unwired ---
full_conn = SiPoConnectionMock()
full = Tpic6b595Full(full_conn)
full.clear()
check_true('full_clear_calls_through', full_conn.clear_count == 2)  # +1 from construction

no_srclr_full = Tpic6b595Full(SiPoConnectionMock(has_srclr=False))
try:
    no_srclr_full.clear()
    check_true('full_clear_raises_when_unwired', False)
except RuntimeError:
    check_true('full_clear_raises_when_unwired', True)

# --- Full.set_output_enable(): propagates, or raises if G unwired ---
full.set_output_enable(True)
check_true('full_output_enable_true', full_conn.output_enable_calls[-1] is True)
full.set_output_enable(False)
check_true('full_output_enable_false', full_conn.output_enable_calls[-1] is False)

no_g_full = Tpic6b595Full(SiPoConnectionMock(has_g=False))
try:
    no_g_full.set_output_enable(True)
    check_true('full_output_enable_raises_when_unwired', False)
except RuntimeError:
    check_true('full_output_enable_raises_when_unwired', True)

# --- Full.write_all(): zero-extends and truncates to num_devices ---
cascade_full_conn = SiPoConnectionMock()
cascade_full = Tpic6b595Full(cascade_full_conn, num_devices=3)
cascade_full.write_all([0x11, 0x22])  # shorter than num_devices -> zero-extend
check_true('write_all_zero_extends', cascade_full._shadow == bytearray([0x11, 0x22, 0x00]))
check_true('write_all_zero_extends_wire', cascade_full_conn.writes[-1] == bytes([0x00, 0x22, 0x11]))

cascade_full.write_all([0x44, 0x55, 0x66, 0x77])  # longer -> truncate
check_true('write_all_truncates', cascade_full._shadow == bytearray([0x44, 0x55, 0x66]))
check_true('write_all_truncates_wire', cascade_full_conn.writes[-1] == bytes([0x66, 0x55, 0x44]))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
