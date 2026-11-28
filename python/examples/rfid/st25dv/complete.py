from periph.connection.i2c_auto import I2CConnection
from periph.chips.rfid.st25dv import ST25DVFull, ST25DVSource

user = I2CConnection(0x53, reg_bytes=2)                                               # Create user-memory connection, (addr=0x53, reg_bytes=2) → Connection
system = I2CConnection(0x57, reg_bytes=2)                                             # Create system-area connection, (addr=0x57, reg_bytes=2) → Connection
tag = ST25DVFull(user, system)                                                        # Create ST25DV driver, (user, system, gpo_active_high=False) → None
                                                                                      # reads MEM_SIZE, BLK_SIZE and IC_REF from the system area

print('Size:', tag.memory_size(), 'bytes')                                            # Get user memory size, () → int bytes
                                                                                      # (MEM_SIZE + 1) * (BLK_SIZE + 1)
print('IC_REF:', hex(tag.ic_reference()))                                             # Get IC reference, () → int
                                                                                      # 0x24 = ST25DV04K, 0x26 = ST25DV16K/64K
print('UID:', tag.uid().hex())                                                        # Read unique ID, () → bytes (8)
                                                                                      # most significant byte first, starts with E0 02
print('IC_REV:', tag.ic_revision())                                                   # Read IC revision, () → int
                                                                                      # IC_REV register 0x0020

tag.write(0x0100, bytes(range(40)))                                                   # Write user memory, (address, data) → None
                                                                                      # split into 16-byte-aligned writes, ACK-polled each
print('Data:', tag.read(0x0100, 40).hex())                                            # Read user memory, (address, length) → bytes
                                                                                      # 16-byte-aligned reads, retried if an RF reader holds the bus
tag.wait_write_complete()                                                             # Wait for the write cycle, () → None
                                                                                      # ACK polling on 0x53

print('IT_TIME:', tag.read_system(0x0001, 1)[0])                                      # Read system area, (address, length) → bytes
                                                                                      # raw access to the static registers at 0x57
print('Session:', tag.session_open())                                                 # Query I²C security session, () → bool
                                                                                      # I2C_SSO_Dyn bit 0
print('Password OK:', tag.present_password(bytes(8)))                                 # Present I²C password, (password 8 bytes) → bool
                                                                                      # opens the session needed for system-area writes
tag.write_password(bytes(8))                                                          # Change I²C password, (password 8 bytes) → None
                                                                                      # here rewritten with the factory value
tag.write_system(0x0001, 0x03)                                                        # Write system area, (address, data) → None
                                                                                      # IT_TIME back to its reset value, ACK-polled

tag.set_area_end(1, tag.memory_size() // 32 - 1)                                      # Set end of area 1-3, (area 1–3, end_block 0–255) → None
                                                                                      # area n ends at byte end_block * 32 + 31
tag.set_area_protection(1, 0x00, 0x00)                                                # Set area protection, (area 1–4, rf 0–15, i2c 0–3) → None
                                                                                      # RFAnSS value and the area's I2CSS bits
print('Area 1 protection:', tag.get_area_protection(1))                               # Get area protection, (area 1–4) → (rf, i2c)

tag.set_gpo(ST25DVFull.GPO_FIELD_CHANGE)                                              # Write static GPO register, (sources, enable=True) → None
                                                                                      # chip copies it into GPO_CTRL_Dyn
print('GPO:', hex(tag.get_gpo()))                                                     # Read static GPO register, () → int
tag.set_gpo_runtime(True)                                                             # Switch GPO output at runtime, (enable) → None
                                                                                      # GPO_CTRL_Dyn.GPO_EN, no session needed
tag.set_interrupt_pulse(3)                                                            # Set GPO pulse duration, (value 0–7) → None
                                                                                      # 301 µs − value × 37.65 µs

print('Field present:', tag.field_present())                                          # Query RF field, () → bool
                                                                                      # EH_CTRL_Dyn.FIELD_ON
tag.set_energy_harvesting(False)                                                      # Control energy harvesting, (enabled, forced=False) → None
                                                                                      # EH_EN now, EH_MODE written only when it changes
tag.set_rf_state()                                                                    # Set RF interface state, (disable=False, sleep=False) → None
                                                                                      # runtime RF_MNGT_Dyn

tag.enable_mailbox()                                                                  # Enable fast transfer mode, () → None
                                                                                      # sets MB_MODE if needed, then MB_CTRL_Dyn.MB_EN
tag.set_mailbox_watchdog(0)                                                           # Set mailbox watchdog, (value 0–7) → None
                                                                                      # 2^(value−1) × 30 ms, 0 disables it
tag.mailbox_write(b'ping')                                                            # Put mailbox message, (data 1–256 bytes) → None
                                                                                      # single write starting at 0x2008
print('Mailbox:', tag.mailbox_status())                                               # Decode mailbox status, () → dict
                                                                                      # enabled, host_put, rf_put, host_miss, rf_miss, …
print('Message:', tag.mailbox_read())                                                 # Read mailbox message, () → bytes
                                                                                      # MB_LEN_Dyn + 1 bytes, empty if no message
tag.disable_mailbox()                                                                 # Disable fast transfer mode, () → None
                                                                                      # clears MB_EN, which also drops the message

tag.write_ndef(bytes([0xD1, 0x01, 0x05, 0x54, 0x02, 0x65, 0x6E, 0x48, 0x69, 0x21]))   # Write NDEF message, (message) → None
                                                                                      # capability container + TLV 03 <len> … FE
print('NDEF:', tag.read_ndef().hex())                                                 # Read NDEF message, () → bytes
                                                                                      # parses the CC and the first NDEF TLV
tag.lock_ccfile(False)                                                                # Lock CC file blocks, (lock=True) → None
                                                                                      # False keeps blocks 0/1 RF-writable


def on_event(status):
    print('GPO event:', hex(status))


tag.enable_interrupt(ST25DVSource.FIELD_RISING | ST25DVSource.RF_WRITE)              # Enable interrupt sources, (source) → None
                                                                                      # static GPO register bits plus GPO_EN
tag.on_interrupt(on_event)                                                            # Subscribe to GPO events, (callback, int_pin=None) → None
                                                                                      # 10 ms IT_STS_Dyn polling without a GPO pin
print('Pending:', hex(tag.poll_interrupt()))                                          # Read and clear IT_STS_Dyn, () → int
                                                                                      # ST25DVSource mask, 0 = none
tag.off_interrupt()                                                                   # Unsubscribe, () → None
tag.disable_interrupt(ST25DVSource.RF_WRITE)                                          # Disable interrupt sources, (source) → None
                                                                                      # clears the matching GPO enable bits
