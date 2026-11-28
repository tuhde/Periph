import time

from periph.connection.i2c_auto import I2CConnection
from periph.chips.rfid.st25dv import ST25DVFull, ST25DVSource

# --- Two connections, one chip ---
# The ST25DV answers on 0x53 (user memory, dynamic registers, mailbox) and on
# 0x57 (system configuration). Both use 16-bit register addresses.
user = I2CConnection(0x53, reg_bytes=2)                                               # Create user-memory connection, (addr=0x53, reg_bytes=2) → Connection
system = I2CConnection(0x57, reg_bytes=2)                                             # Create system-area connection, (addr=0x57, reg_bytes=2) → Connection
tag = ST25DVFull(user, system)                                                        # Create ST25DV driver, (user, system, gpo_active_high=False) → None

# --- Identify the tag ---
# IC_REF tells the 04K apart from the 16K/64K; the size comes from MEM_SIZE.
print('IC_REF 0x%02X, revision %d' % (tag.ic_reference(), tag.ic_revision()))       # Get IC reference, () → int ; Read IC revision, () → int
print('UID', tag.uid().hex(), '-', tag.memory_size(), 'bytes')                        # Read unique ID, () → bytes (8) ; Get user memory size, () → int bytes

# --- NDEF text record a phone can read ---
# Record header D1 (MB/ME/SR, well-known), type 'T', status 02 (language "en").
text = b'Hello from Periph'
record = bytes([0xD1, 0x01, 3 + len(text), 0x54, 0x02]) + b'en' + text
tag.write_ndef(record)                                                                # Write NDEF message, (message) → None
print('NDEF read back:', tag.read_ndef() == record)                                   # Read NDEF message, () → bytes

# --- Raw bytes across a 256-byte page boundary ---
# The driver splits the write into 16-byte-aligned pieces and ACK-polls each.
base = 0x00F8
saved = tag.read(base, 32)                                                            # Read user memory, (address, length) → bytes
pattern = bytes(range(0x40, 0x60))
tag.write(base, pattern)                                                              # Write user memory, (address, data) → None
tag.wait_write_complete()                                                             # Wait for the write cycle, () → None
print('Page-crossing write ok:', tag.read(base, 32) == pattern)                       # Read user memory, (address, length) → bytes
tag.write(base, saved)                                                                # Write user memory, (address, data) → None

# --- Password session and two memory areas ---
# Area 1 = first 1 KiB (stays open), area 2 = the rest: RF writes need RF
# password 1, I2C writes need the I2C session. The factory I2C password is 0.
pwd = bytes(8)
print('Session before:', tag.session_open())                                          # Query I²C security session, () → bool
print('Password accepted:', tag.present_password(pwd))                                # Present I²C password, (password 8 bytes) → bool
end1 = min(tag.memory_size(), 1024) // 32 - 1
last = tag.memory_size() // 32 - 1
tag.set_area_end(1, end1)                                                             # Set end of area 1-3, (area 1–3, end_block 0–255) → None
tag.set_area_end(2, last)                                                             # Set end of area 1-3, (area 1–3, end_block 0–255) → None
tag.set_area_end(3, last)                                                             # Set end of area 1-3, (area 1–3, end_block 0–255) → None
tag.set_area_protection(2, 0x05, 0x01)                                                # Set area protection, (area 1–4, rf 0–15, i2c 0–3) → None
print('Area 2 protection (rf, i2c):', tag.get_area_protection(2))                     # Get area protection, (area 1–4) → (rf, i2c)

# --- Change and restore the I2C password ---
new_pwd = bytes([0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF0])
tag.write_password(new_pwd)                                                           # Change I²C password, (password 8 bytes) → None
print('New password accepted:', tag.present_password(new_pwd))                        # Present I²C password, (password 8 bytes) → bool
tag.write_password(pwd)                                                               # Change I²C password, (password 8 bytes) → None
print('Factory password restored:', tag.present_password(pwd))                       # Present I²C password, (password 8 bytes) → bool

# --- GPO events while a phone approaches ---
# Field change and RF write events pulse GPO; without a GPO pin the driver
# polls IT_STS_Dyn every 10 ms. Hold a phone to the antenna now.
tag.set_interrupt_pulse(3)                                                            # Set GPO pulse duration, (value 0–7) → None
tag.set_gpo(ST25DVFull.GPO_FIELD_CHANGE)                                              # Write static GPO register, (sources, enable=True) → None
tag.enable_interrupt(ST25DVSource.FIELD_RISING | ST25DVSource.RF_WRITE)              # Enable interrupt sources, (source) → None
print('GPO register: 0x%02X' % tag.get_gpo())                                         # Read static GPO register, () → int
print('Pending before:', hex(tag.poll_interrupt()))                                   # Read and clear IT_STS_Dyn, () → int


def on_event(status):
    names = []
    if status & ST25DVSource.FIELD_RISING:
        names.append('field on')
    if status & ST25DVSource.FIELD_FALLING:
        names.append('field off')
    if status & ST25DVSource.RF_WRITE:
        names.append('RF write')
    print('  GPO event:', ', '.join(names) or hex(status))


tag.on_interrupt(on_event)                                                            # Subscribe to GPO events, (callback, int_pin=None) → None
for _ in range(10):
    print('  field present:', tag.field_present())                                    # Query RF field, () → bool
    time.sleep(1)
tag.off_interrupt()                                                                   # Unsubscribe, () → None
tag.disable_interrupt(ST25DVSource.RF_WRITE)                                          # Disable interrupt sources, (source) → None

# --- Fast transfer mode: host <-> phone mailbox ---
# The host puts a message; an RF reader (e.g. the ST25 app) reads it and can
# answer. The watchdog releases an unread message after 2^(7-1) x 30 ms.
tag.enable_mailbox()                                                                  # Enable fast transfer mode, () → None
tag.set_mailbox_watchdog(7)                                                           # Set mailbox watchdog, (value 0–7) → None
tag.mailbox_write(b'hello reader')                                                    # Put mailbox message, (data 1–256 bytes) → None
for _ in range(10):
    st = tag.mailbox_status()                                                         # Decode mailbox status, () → dict
    if st['rf_put']:
        print('Reader says:', tag.mailbox_read())                                     # Read mailbox message, () → bytes
        break
    print('  mailbox:', st)
    time.sleep(1)
tag.disable_mailbox()                                                                 # Disable fast transfer mode, () → None

# --- Energy harvesting and RF state ---
tag.set_energy_harvesting(True)                                                       # Control energy harvesting, (enabled, forced=False) → None
time.sleep(1)
tag.set_energy_harvesting(False)                                                      # Control energy harvesting, (enabled, forced=False) → None
tag.set_rf_state(sleep=True)                                                          # Set RF interface state, (disable=False, sleep=False) → None
print('RF asleep: a phone no longer sees the tag')
time.sleep(2)
tag.set_rf_state()                                                                    # Set RF interface state, (disable=False, sleep=False) → None

# --- Restore the delivery defaults ---
# One area, no protection, field-change GPO only, mailbox off, CC unlocked.
tag.set_area_protection(2, 0x00, 0x00)                                                # Set area protection, (area 1–4, rf 0–15, i2c 0–3) → None
tag.set_area_end(1, last)                                                             # Set end of area 1-3, (area 1–3, end_block 0–255) → None
tag.set_gpo(ST25DVFull.GPO_FIELD_CHANGE)                                              # Write static GPO register, (sources, enable=True) → None
tag.set_gpo_runtime(True)                                                             # Switch GPO output at runtime, (enable) → None
tag.write_system(0x000D, 0x00)                                                        # Write system area, (address, data) → None
print('MB_MODE:', tag.read_system(0x000D, 1)[0])                                      # Read system area, (address, length) → bytes
tag.lock_ccfile(False)                                                                # Lock CC file blocks, (lock=True) → None
print('Done.')
