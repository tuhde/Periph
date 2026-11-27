from periph.connection.i2c_auto import I2CConnection
from periph.chips.rfid.pn532 import PN532Full
import time

connection = I2CConnection(0x24)                                         # Create I²C connection, (addr=0x24) → Connection
reader = PN532Full(connection)                                           # Create PN532 driver, (connection) → None
reader.init()                                                            # Wake, check firmware, SAM normal mode, finite retries, () → None


def hexs(data):
    return ''.join('%02X' % b for b in data)


# --- Identity and health ---
# IC 0x32 is the PN532; the support byte lists ISO 14443 A/B and ISO 18092.
# The communication-line self test echoes a pattern through the firmware.
fw = reader.firmware_version()                                           # GetFirmwareVersion, () → dict
print('PN532 IC 0x%02X firmware %d.%d support 0x%02X' % (fw['ic'], fw['version'], fw['revision'], fw['support']))
print('status', reader.general_status())                                 # GetGeneralStatus, () → dict
print('self test', reader.self_test())                                   # Diagnose, (test=0x00) → bool

# --- Card polling: 15 s at 1 Hz ---
# Present a card or tag. The finite retry count makes every poll return
# after a few milliseconds when the field is empty.
for i in range(15):
    targets = reader.list_passive_targets()                              # InListPassiveTarget, (baud=BAUD_106_A, max_targets=1, ...) → list[Target]
    if targets:
        t = targets[0]
        print('%2d s  UID %s  SENS_RES 0x%04X  SEL_RES 0x%02X' % (i, hexs(t.uid), t.sens_res, t.sel_res))
        reader.release(t.tg)                                             # InRelease, (target=0) → None
    else:
        print('%2d s  no card' % i)
    time.sleep(1)

# --- Tag access ---
# A 7-byte UID with SEL_RES 0x00 is an NTAG / MIFARE Ultralight: read page 4
# and write it back. A 4-byte UID with SEL_RES 0x08 is a MIFARE Classic 1K:
# authenticate sector 1 with the transport key FF FF FF FF FF FF, read block
# 4 and write it back. Writing sector trailers can lock a sector forever.
targets = reader.list_passive_targets()                                  # InListPassiveTarget, (baud=BAUD_106_A, ...) → list[Target]
if targets:
    t = targets[0]
    reader.deselect(t.tg)                                                # InDeselect, (target=0) → None
    reader.select(t.tg)                                                  # InSelect, (target=0) → None
    try:
        if t.sel_res == 0x00:
            page = reader.ntag_read_page(t.tg, 4)                        # NTAG READ, (target, page) → bytes (16)
            print('pages 4..7', hexs(page))
            reader.ntag_write_page(t.tg, 4, page[:4])                    # NTAG WRITE, (target, page, data 4 bytes) → None
            print('raw read', hexs(reader.data_exchange(t.tg, b'\x30\x00')))   # InDataExchange, (target, data) → bytes
        elif t.sel_res & 0x08:
            reader.mifare_authenticate(t.tg, 4, b'\xff' * 6, t.uid)      # MIFARE auth, (target, block, key, uid, key_b=False) → None
            block = reader.mifare_read_block(t.tg, 4)                    # MIFARE READ, (target, block) → bytes (16)
            print('block 4', hexs(block))
            reader.mifare_write_block(t.tg, 4, block)                    # MIFARE WRITE, (target, block, data 16 bytes) → None
    except OSError as e:
        print('tag access failed:', e)
    reader.release(t.tg)                                                 # InRelease, (target=0) → None
else:
    print('no card for the tag access part')

# --- Raw RF and raw commands ---
# InCommunicateThru sends bytes without PN532 protocol handling; with no
# target selected it reports an error. send_command reaches every command.
try:
    print('thru', hexs(reader.communicate_thru(b'\x30\x00')))            # InCommunicateThru, (data) → bytes
except OSError as e:
    print('communicate_thru:', e)
print('raw GetFirmwareVersion', hexs(reader.send_command(0x02)))         # Raw command, (command, data=b'', timeout=1.0 s) → bytes
txmode = reader.read_register(0x6302)                                    # ReadRegister, (address) → int
print('CIU_TxMode 0x%02X' % txmode)
reader.write_register(0x6302, txmode)                                    # WriteRegister, (address, value) → None

# --- Wake on card with on_interrupt ---
# Infinite activation retries make InListPassiveTarget wait for a card;
# IRQ (or the status-byte poll) wakes the callback when the response is ready.
reader.set_max_retries(passive_activation=0xFF)                          # RFConfiguration item 5, (passive_activation=2, atr=0xFF, psl=1) → None
ready = []
reader.on_interrupt(lambda: ready.append(True))                          # Subscribe to IRQ, (callback, int_pin=None) → None
reader.start_command(0x4A, b'\x01\x00')                                  # Command without waiting, (command, data=b'') → None
print('waiting 10 s for a card ...')
for _ in range(100):
    if ready or reader.poll_interrupt():                                 # Frame ready?, () → bool
        break
    time.sleep(0.1)
reader.off_interrupt()                                                   # Unsubscribe, () → None
if reader.wait_ready(0.01):                                              # Wait for a frame, (timeout s) → bool
    resp = reader.read_response(0x4A)                                    # Read the pending response, (command, timeout=1.0 s) → bytes
    print('card arrived, UID', hexs(resp[6:6 + resp[5]]))
    reader.release()                                                     # InRelease, (target=0) → None
else:
    try:
        reader.read_response(0x4A, 0)                                    # Read the pending response, (command, timeout=1.0 s) → bytes
    except OSError:
        pass                                                             # timed out: the command was aborted with an ACK frame
    print('no card arrived')
reader.set_max_retries()                                                 # RFConfiguration item 5, (passive_activation=2, atr=0xFF, psl=1) → None
print('read_uid', reader.read_uid())                                     # Detect a type A card, (timeout=1.0 s) → bytes | None

# --- RF field off and on, then power down ---
# With the field off a card loses power; the reader draws less current.
reader.set_rf_field(False)                                               # RFConfiguration item 1, (on, auto_rfca=False) → None
time.sleep(1)
reader.set_rf_field(True)                                                # RFConfiguration item 1, (on, auto_rfca=False) → None
reader.power_down()                                                      # PowerDown, (wakeup_sources=0x20) → None
print('powered down; the next I²C access wakes the chip')
