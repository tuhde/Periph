"""ST25DV unit test for Python/Linux — exercises the driver against two
I2CConnectionMock instances (user area 0x53, system area 0x57), with no
hardware required.

Run via ``python/test_linux.sh --level unit rfid/st25dv``.
"""

import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.rfid.st25dv import ST25DVMinimal, ST25DVFull, ST25DVSource

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


def raises(fn, exc=ValueError):
    try:
        fn()
    except exc:
        return True
    return False


def make(ic_ref=0x26, mem=(0xFF, 0x01)):
    user = I2CConnectionMock(reg_bytes=2)
    system = I2CConnectionMock(reg_bytes=2)
    system.set_register(0x0014, mem[0], mem[1], 0x03, ic_ref)
    system.set_register(0x0018, 0x11, 0x22, 0x33, 0x44, 0x55, 0x26, 0x02, 0xE0)
    system.set_register(0x0020, 0x12)
    system.set_register(0x0000, 0x88)
    system.set_register(0x0002, 0x01)
    return user, system


# --- Minimal ---------------------------------------------------------------
user, system = make()
tag = ST25DVMinimal(user, system)
check_true('init_reads_ident', system.writes == [bytes([0x00, 0x14])])
check_true('init_no_user_traffic', user.writes == [])
check_true('size_16k', tag._size == 2048)

user.set_register(0x0100, 1, 2, 3)
check_true('read', tag.read(0x0100, 3) == bytes([1, 2, 3]))
check_true('read_address_bytes', user.writes[-1] == bytes([0x01, 0x00]))
check_true('read_out_of_range', raises(lambda: tag.read(2047, 2)))
check_true('read_negative', raises(lambda: tag.read(-1, 1)))

del user.writes[:]
data = bytes(range(40))
tag.write(0x0008, data)
frames = [w for w in user.writes if len(w) > 2]
polls = [w for w in user.writes if len(w) == 2]
check_true('write_first_chunk_to_16_boundary', frames[0] == bytes([0x00, 0x08]) + data[:8])
check_true('write_second_chunk', frames[1] == bytes([0x00, 0x10]) + data[8:24])
check_true('write_third_chunk', frames[2] == bytes([0x00, 0x20]) + data[24:40])
check_true('write_chunk_count', len(frames) == 3)
check_true('write_ack_polled_each_chunk', len(polls) == 3)
check_true('write_applied', bytes(user.registers[0x08 + i] for i in range(40)) == data)
check_true('write_out_of_range', raises(lambda: tag.write(2040, bytes(9))))

user.fail_next_writes(1000, after=1)
check_true('write_timeout_raises', raises(lambda: tag.write(0, b'\x01'), OSError))
user.fail_next_writes(0)

# 04K and 64K sizes
u4, s4 = make(0x24, (0x7F, 0x00))
check_true('size_04k', ST25DVFull(u4, s4).memory_size() == 512)
u64, s64 = make(0x26, (0xFF, 0x07))
check_true('size_64k', ST25DVFull(u64, s64).memory_size() == 8192)

# --- Full: identification --------------------------------------------------
user, system = make()
tag = ST25DVFull(user, system)
check_true('memory_size', tag.memory_size() == 2048)
check_true('ic_reference', tag.ic_reference() == 0x26)
check_true('uid_msb_first', tag.uid() == bytes([0xE0, 0x02, 0x26, 0x55, 0x44, 0x33, 0x22, 0x11]))
check_true('ic_revision', tag.ic_revision() == 0x12)
check_true('read_system', tag.read_system(0x0017, 1) == bytes([0x26]))

del system.writes[:]
tag.write_system(0x0001, 5)
check_true('write_system_frame', system.writes[0] == bytes([0x00, 0x01, 0x05]))
check_true('write_system_ack_poll', system.writes[1] == bytes([0x00, 0x00]))

# --- password --------------------------------------------------------------
pw = bytes([1, 2, 3, 4, 5, 6, 7, 8])
user.set_register(0x2004, 0x01)
del system.writes[:]
check_true('present_password_open', tag.present_password(pw))
check_true('present_password_frame', system.writes[0] == bytes([0x09, 0x00]) + pw + bytes([0x09]) + pw)
check_true('present_password_len', len(system.writes[0]) == 19)
check_true('session_open', tag.session_open())
user.set_register(0x2004, 0x00)
check_true('present_password_closed', not tag.present_password(pw))
check_true('password_length', raises(lambda: tag.present_password(b'\x00')))
check_true('write_password_closed_raises', raises(lambda: tag.write_password(pw), OSError))
user.set_register(0x2004, 0x01)
del system.writes[:]
tag.write_password(pw)
check_true('write_password_frame', system.writes[0] == bytes([0x09, 0x00]) + pw + bytes([0x07]) + pw)

# --- areas -----------------------------------------------------------------
del system.writes[:]
tag.set_area_end(2, 0x1F)
check_true('set_area_end', system.writes[0] == bytes([0x00, 0x07, 0x1F]))
check_true('set_area_end_range', raises(lambda: tag.set_area_end(4, 1)))
system.set_register(0x000B, 0x00)
tag.set_area_protection(3, 0x05, 0x03)
check_true('area_protection_rf', system.registers[0x0008] == 0x05)
check_true('area_protection_i2c', system.registers[0x000B] == 0x30)
tag.set_area_protection(2, 0x0C, 0x01)
check_true('area_protection_i2c_rmw', system.registers[0x000B] == 0x34)
check_true('get_area_protection', tag.get_area_protection(3) == (0x05, 0x03))
check_true('get_area_protection_2', tag.get_area_protection(2) == (0x0C, 0x01))
check_true('area_protection_range', raises(lambda: tag.set_area_protection(5, 0, 0)))
check_true('area_protection_level', raises(lambda: tag.set_area_protection(1, 0, 4)))

# --- GPO -------------------------------------------------------------------
tag.set_gpo(ST25DVFull.GPO_FIELD_CHANGE | ST25DVFull.GPO_RF_WRITE)
check_true('set_gpo', system.registers[0x0000] == 0xC8)
tag.set_gpo(ST25DVFull.GPO_FIELD_CHANGE, enable=False)
check_true('set_gpo_disabled', system.registers[0x0000] == 0x08)
check_true('get_gpo', tag.get_gpo() == 0x08)
tag.set_gpo_runtime(True)
check_true('set_gpo_runtime', user.registers[0x2000] == 0x80)
tag.set_gpo_runtime(False)
check_true('set_gpo_runtime_off', user.registers[0x2000] == 0x00)
tag.set_interrupt_pulse(3)
check_true('set_interrupt_pulse', system.registers[0x0001] == 3)
check_true('interrupt_pulse_range', raises(lambda: tag.set_interrupt_pulse(8)))

# --- EH, RF ----------------------------------------------------------------
user.set_register(0x2002, 0x0C)
check_true('field_present', tag.field_present())
user.set_register(0x2002, 0x08)
check_true('field_absent', not tag.field_present())
system.set_register(0x0002, 0x01)
del system.writes[:]
tag.set_energy_harvesting(True)
check_true('eh_no_static_write', system.writes == [bytes([0x00, 0x02])])
check_true('eh_dynamic', user.registers[0x2002] == 0x01)
tag.set_energy_harvesting(False, forced=True)
check_true('eh_forced_static', system.registers[0x0002] == 0x00)
check_true('eh_dynamic_off', user.registers[0x2002] == 0x00)
tag.set_rf_state(sleep=True)
check_true('rf_sleep', user.registers[0x2003] == 0x02)
tag.set_rf_state(disable=True)
check_true('rf_disable', user.registers[0x2003] == 0x01)
tag.set_rf_state()
check_true('rf_normal', user.registers[0x2003] == 0x00)

# --- mailbox ---------------------------------------------------------------
system.set_register(0x000D, 0x00)
tag.enable_mailbox()
check_true('mb_mode_static', system.registers[0x000D] == 0x01)
check_true('mb_en', user.registers[0x2006] == 0x01)
del system.writes[:]
tag.enable_mailbox()
check_true('mb_mode_not_rewritten', all(len(w) == 2 for w in system.writes))
user.set_register(0x2006, 0x01 | 0x04 | 0x20)
st = tag.mailbox_status()
check_true('mb_status_enabled', st['enabled'])
check_true('mb_status_rf_put', st['rf_put'] and not st['host_put'])
check_true('mb_status_rf_miss', st['rf_miss'] and not st['host_miss'])
check_true('mb_write_busy', raises(lambda: tag.mailbox_write(b'x'), OSError))
user.set_register(0x2006, 0x01)
del user.writes[:]
msg = bytes(range(100))
tag.mailbox_write(msg)
check_true('mb_write_single_frame', user.writes[-1] == bytes([0x20, 0x08]) + msg)
check_true('mb_write_len', raises(lambda: tag.mailbox_write(b'')))
check_true('mb_write_len_max', raises(lambda: tag.mailbox_write(bytes(257))))
user.set_register(0x2006, 0x00)
check_true('mb_write_disabled', raises(lambda: tag.mailbox_write(b'x'), OSError))
check_true('mb_read_empty', tag.mailbox_read() == b'')
user.set_register(0x2006, 0x05)
user.set_register(0x2007, 19)
for i in range(20):
    user.registers[0x2008 + i] = 0xA0 + i
check_true('mb_read', tag.mailbox_read() == bytes(0xA0 + i for i in range(20)))
tag.disable_mailbox()
check_true('mb_disable', user.registers[0x2006] == 0x00)
tag.set_mailbox_watchdog(4)
check_true('mb_wdg', system.registers[0x000E] == 4)
check_true('mb_wdg_range', raises(lambda: tag.set_mailbox_watchdog(8)))

# --- CC lock, NDEF ---------------------------------------------------------
tag.lock_ccfile()
check_true('lock_ccfile', system.registers[0x000C] == 0x03)
tag.lock_ccfile(False)
check_true('unlock_ccfile', system.registers[0x000C] == 0x00)

ndef = bytes([0xD1, 0x01, 0x04, 0x54, 0x02, 0x65, 0x6E, 0x48])
tag.write_ndef(ndef)
image = bytes(user.registers.get(i, 0) for i in range(4 + 2 + len(ndef) + 1))
check_true('ndef_cc_16k', image[:4] == bytes([0xE1, 0x40, 0xFF, 0x01]))
check_true('ndef_tlv', image[4:6] == bytes([0x03, len(ndef)]))
check_true('ndef_terminator', image[-1] == 0xFE)
check_true('read_ndef', tag.read_ndef() == ndef)
long_msg = bytes(300)
tag.write_ndef(long_msg)
check_true('ndef_long_tlv', bytes(user.registers[i] for i in range(4, 8)) == bytes([0x03, 0xFF, 0x01, 0x2C]))
check_true('read_ndef_long', tag.read_ndef() == long_msg)
check_true('ndef_too_large', raises(lambda: tag.write_ndef(bytes(2048))))
user.set_register(0x0000, 0x00)
check_true('read_ndef_no_cc', tag.read_ndef() == b'')

u4, s4 = make(0x24, (0x7F, 0x00))
t4 = ST25DVFull(u4, s4)
t4.write_ndef(b'\x01')
check_true('ndef_cc_04k', bytes(u4.registers[i] for i in range(4)) == bytes([0xE1, 0x40, 0x40, 0x01]))
u64, s64 = make(0x26, (0xFF, 0x07))
t64 = ST25DVFull(u64, s64)
t64.write_ndef(b'\x01\x02')
check_true('ndef_cc_64k', bytes(u64.registers[i] for i in range(8)) == bytes([0xE2, 0x40, 0x00, 0x01, 0x00, 0x00, 0x03, 0xFF]))
check_true('read_ndef_64k', t64.read_ndef() == b'\x01\x02')

user.fail_next_writes(2)
tag.wait_write_complete()
check_true('wait_write_complete_after_nacks', True)
user.fail_next_writes(1000)
check_true('wait_write_complete_timeout', raises(lambda: tag.wait_write_complete(), OSError))
user.fail_next_writes(0)

# --- interrupts ------------------------------------------------------------
system.set_register(0x0000, 0x80)
tag.enable_interrupt(ST25DVSource.FIELD_RISING | ST25DVSource.RF_WRITE)
check_true('enable_interrupt_gpo', system.registers[0x0000] == 0xC8)
check_true('enable_interrupt_gpo_en', user.registers[0x2000] == 0x80)
tag.enable_interrupt(ST25DVSource.RF_PUT_MSG | ST25DVSource.RF_USER)
check_true('enable_interrupt_mapping', system.registers[0x0000] == 0xD9)
tag.disable_interrupt(ST25DVSource.RF_WRITE)
check_true('disable_interrupt', system.registers[0x0000] == 0x99)
user.set_register(0x2005, 0x90)
check_true('poll_interrupt', tag.poll_interrupt() == 0x90)


class FakePin:
    RISING = 1
    FALLING = 2

    def __init__(self):
        self.handler = None
        self.trigger = None

    def on_edge(self, handler, trigger):
        self.handler = handler
        self.trigger = trigger

    def off_edge(self, handler):
        if handler is self.handler:
            self.handler = None


got = []
pin = FakePin()
tag.on_interrupt(lambda s: got.append(s), int_pin=pin)
check_true('on_interrupt_falling', pin.trigger == FakePin.FALLING)
user.set_register(0x2005, 0x10)
pin.handler()
check_true('on_interrupt_dispatch', got == [0x10])
user.set_register(0x2005, 0x00)
pin.handler()
check_true('on_interrupt_no_spurious', got == [0x10])
tag.off_interrupt()
check_true('off_interrupt', pin.handler is None)

user_h, system_h = make()
tag_h = ST25DVFull(user_h, system_h, gpo_active_high=True)
pin_h = FakePin()
tag_h.on_interrupt(lambda s: None, int_pin=pin_h)
check_true('on_interrupt_rising_jf', pin_h.trigger == FakePin.RISING)
tag_h.off_interrupt()

# --- read retry ------------------------------------------------------------
user.fail_next_writes(2)
user.set_register(0x0040, 0x77)
check_true('read_retries_after_nacks', tag.read(0x0040, 1) == b'\x77')
user.fail_next_writes(3)
check_true('read_raises_after_retries', raises(lambda: tag.read(0x0040, 1), OSError))
user.fail_next_writes(0)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
