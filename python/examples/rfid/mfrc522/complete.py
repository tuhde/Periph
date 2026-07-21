import time
from periph.transport.spi_micropython import SPITransport
from periph.chips.rfid.mfrc522 import MFRC522Full
from machine import SPI, Pin

spi = SPI(1, baudrate=1000000, polarity=0, phase=0)
cs  = Pin('P9', Pin.OUT)
transport = SPITransport(spi, cs)                          # Create SPI transport, (bus, cs)
mfrc = MFRC522Full(transport)                              # Create MFRC522 driver, (transport, bus_type='spi')

chip_type, version = mfrc.version()                       # Read version register, () → tuple (chip_type, version)
                                                          # for MFRC522 chip_type=0x09, version=1 (v1.0) or 2 (v2.0)
print('MFRC522 chip_type=0x{:X} version={}'.format(chip_type, version))

ok = mfrc.self_test()                                     # Run digital self test, () → bool
                                                          # compares 64 FIFO bytes against the version-specific reference
print('self_test: {}'.format('PASS' if ok else 'FAIL'))

mfrc.antenna_on()                                         # Enable antenna driver (TX1+TX2), () → None
mfrc.set_antenna_gain(38)                                 # Set receiver gain, (dB=18/23/33/38/43/48) → None
                                                          # 38 dB gives better read range on most antennas
print('current gain: {} dB'.format(mfrc.antenna_gain()))  # Read receiver gain, () → int dB

mfrc.reset()                                              # Soft reset and reinitialise, () → None
                                                          # re-runs the full initialization sequence

uid = mfrc.select_card()                                  # Anticollision/Select (leaves card active), () → bytes | None
if uid is not None:
    print('UID: {}'.format(uid.hex()))
    # Authenticate MIFARE Classic sector 1 block 4 with factory default key A
    factory_key = bytes([0xFF] * 6)                        # well-known default key — see spec
    if mfrc.authenticate(4, MFRC522Full.KEY_A, factory_key, uid[0:4]):  # Run MFAuthent, (block, key_type, key=6 B, uid=4 B) → bool
        block = mfrc.read_block(4)                        # Read 16-byte block, (block_address) → bytes
                                                          # requires successful authenticate for the containing sector
        if block is not None:
            print('block 4: {}'.format(block.hex()))
        mfrc.decrement_value(4, 1)                        # Decrement value block, (block, delta=uint32) → bool
                                                          # runs Decrement + Transfer to the same block
        mfrc.stop_crypto()                                # Clear MFCrypto1On, () → None
                                                          # required before authenticating a different sector
    mfrc.halt_card()                                      # Send HLTA, () → None

print('===DONE: 0 passed, 0 failed===')
