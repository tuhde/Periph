from periph.connection.i2c_auto import I2CConnection
from periph.chips.rfid.pn532 import PN532Full
import time

connection = I2CConnection(0x24)                                         # Create I²C connection, (addr=0x24) → Connection
# The PN532 address is fixed; I0 low and I1 high select the I²C interface.
reader = PN532Full(connection)                                           # Create PN532 driver, (connection) → None
# Constructor stores the connection only; no bus traffic yet.
reader.init()                                                            # Wake, check firmware, SAM normal mode, finite retries, () → None
# GetFirmwareVersion (IC 0x32), SAMConfiguration normal mode, MxRtyPassiveActivation = 2.

print(reader.firmware_version())                                         # GetFirmwareVersion, () → dict {ic, version, revision, support, iso14443a, iso14443b, iso18092}
# IC 0x32, firmware 1.6 on current parts; support bits list the protocols.
print(reader.general_status())                                           # GetGeneralStatus, () → dict {error, field, targets, sam_status}
# Last error code, external field detected, active targets, SAM status.
print(reader.self_test())                                                # Diagnose, (test=0x00) → bool
# Communication line test: the chip echoes an 8-byte pattern.
print(reader.self_test(0x01))                                            # Diagnose, (test=0x01) → bool
# ROM checksum test.

uid = reader.read_uid()                                                  # Detect a type A card, (timeout=1.0 s) → bytes | None
# InListPassiveTarget at 106 kbps type A, then InRelease.
print('UID', uid)

targets = reader.list_passive_targets(PN532Full.BAUD_106_A, 1)           # InListPassiveTarget, (baud=BAUD_106_A, max_targets=1, initiator_data=b'', timeout=1.0 s) → list[Target]
# Keeps the target active; Target.tg is the logical number for the next commands.
if targets:
    t = targets[0]
    print(t)
    reader.deselect(t.tg)                                                # InDeselect, (target=0) → None
    # Puts the card to sleep but keeps its information in the PN532.
    reader.select(t.tg)                                                  # InSelect, (target=0) → None
    # Wakes and reselects the deselected card.
    if len(t.uid) == 7:
        print(reader.ntag_read_page(t.tg, 4))                            # NTAG READ, (target, page) → bytes (16)
        # Returns pages 4..7 (16 bytes).
        reader.ntag_write_page(t.tg, 4, b'\x01\x02\x03\x04')             # NTAG WRITE, (target, page, data 4 bytes) → None
        # Writes one 4-byte user page.
    else:
        reader.mifare_authenticate(t.tg, 4, b'\xff' * 6, t.uid)          # MIFARE auth, (target, block, key 6 bytes, uid, key_b=False) → None
        # Key A authentication of sector 1 with the transport key.
        data = reader.mifare_read_block(t.tg, 4)                         # MIFARE READ, (target, block) → bytes (16)
        # Reads block 4.
        reader.mifare_write_block(t.tg, 4, data)                         # MIFARE WRITE, (target, block, data 16 bytes) → None
        # Writes the same 16 bytes back.
    print(reader.data_exchange(t.tg, b'\x30\x00'))                       # InDataExchange, (target, data) → bytes
    # Raw READ of block/page 0 with PN532 framing and CRC.
    reader.release(t.tg)                                                 # InRelease, (target=0) → None
    # Ends the session with the card.

reader.set_rf_field(False)                                               # RFConfiguration item 1, (on, auto_rfca=False) → None
# RF field off; a card in the field loses power.
reader.set_rf_field(True)                                                # RFConfiguration item 1, (on, auto_rfca=False) → None
# RF field back on.
reader.set_max_retries(passive_activation=2)                             # RFConfiguration item 5, (passive_activation=2, atr=0xFF, psl=1) → None
# 0xFF would retry forever.
print(hex(reader.read_register(0x6302)))                                 # ReadRegister, (address) → int
# 0x6302 is CIU_TxMode in the PN532's SFR space.
reader.write_register(0x6302, reader.read_register(0x6302))              # WriteRegister, (address, value) → None
# Writes the value back unchanged.
print(reader.send_command(0x02))                                         # Raw command, (command, data=b'', timeout=1.0 s) → bytes
# GetFirmwareVersion through the raw frame interface.
try:
    print(reader.communicate_thru(b'\x26'))                              # InCommunicateThru, (data) → bytes
    # Raw RF exchange with the current target; fails without one.
except OSError as e:
    print('communicate_thru:', e)

reader.start_command(0x02)                                               # Command without waiting, (command, data=b'') → None
# Writes the frame and reads the ACK only.
print(reader.wait_ready(1.0))                                            # Wait for a frame, (timeout s) → bool
# Polls the status byte every 1 ms.
print(reader.poll_interrupt())                                           # Frame ready?, () → bool
# A single status-byte read.
print(reader.read_response(0x02))                                        # Read the pending response, (command, timeout=1.0 s) → bytes
# Header, NACK, full frame, checksums.

reader.on_interrupt(lambda: print('frame ready'))                        # Subscribe to IRQ, (callback, int_pin=None) → None
# Falling edge on IRQ, or a 10 ms status-byte poll without an IRQ pin.
reader.send_command(0x02)                                                # Raw command, (command, data=b'', timeout=1.0 s) → bytes
time.sleep(0.1)
reader.off_interrupt()                                                   # Unsubscribe, () → None
# Stops the edge handler or the polling thread.

reader.power_down()                                                      # PowerDown, (wakeup_sources=0x20) → None
# Sleeps until the next I²C access; the driver retries the wake-up write.
