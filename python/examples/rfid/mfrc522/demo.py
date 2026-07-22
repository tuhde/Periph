import time
from periph.transport.spi_micropython import SPITransport
from periph.chips.rfid.mfrc522 import MFRC522Full
from machine import SPI, Pin

spi = SPI(1, baudrate=1000000, polarity=0, phase=0)
cs  = Pin('P9', Pin.OUT)
transport = SPITransport(spi, cs)                          # Create SPI transport, (bus, cs)
mfrc = MFRC522Full(transport)                              # Create MFRC522 driver, (transport, bus_type='spi')

# --- Prepaid-card credit counter ---
# Simulates a transit-gate / vending-machine credit system using a MIFARE
# Classic value block. The factory default key A (FF FF FF FF FF FF) is
# used for the demo only — replace with a per-deployment secret in any
# real access-control system.
CREDITS_BLOCK = 4
INITIAL_CREDITS = 10

# --- Detect a card and select it for authenticated access ---
uid = mfrc.select_card()                                  # Anticollision/Select only, () → bytes | None
if uid is None:
    print('no card in field')
else:
    # --- Authenticate with the well-known MIFARE factory default key A ---
    # In a real deployment this would be a per-card key stored securely
    # (e.g. diversified per card UID and held in an HSM or secure element).
    factory_key = bytes([0xFF] * 6)
    if not mfrc.authenticate(CREDITS_BLOCK, MFRC522Full.KEY_A, factory_key, uid[0:4]):  # MFAuthent, (block, key, uid) → bool
        print('authentication failed')
    else:
        # --- Read the current value block; initialise it if unprogrammed ---
        block = mfrc.read_block(CREDITS_BLOCK)            # Read 16-byte block, (block_address) → bytes
        if block is not None and all(b == 0 for b in block):
            value_bytes = bytearray(16)
            value_bytes[0:4] = INITIAL_CREDITS.to_bytes(4, 'little')
            # value-block layout: value, ~value, value, addr, addr, addr, addr
            v = int.from_bytes(value_bytes[0:4], 'little')
            value_bytes[4:8]   = ((~v) & 0xFFFFFFFF).to_bytes(4, 'little')
            value_bytes[8:12]  = value_bytes[0:4]
            value_bytes[12]    = CREDITS_BLOCK & 0xFF
            value_bytes[13]    = (~CREDITS_BLOCK) & 0xFF
            value_bytes[14]    = CREDITS_BLOCK & 0xFF
            value_bytes[15]    = (~CREDITS_BLOCK) & 0xFF
            mfrc.write_block(CREDITS_BLOCK, bytes(value_bytes))  # Write 16 bytes, (block, data=16 B) → bool
            mfrc.restore_value(CREDITS_BLOCK)             # Restore + Transfer, (block) → bool
                                                          # normalises the value-block layout

        # --- "Spend" one credit; refuse if balance is zero ---
        block = mfrc.read_block(CREDITS_BLOCK)            # Read current value, (block) → bytes
        if block is not None:
            credits = int.from_bytes(block[0:4], 'little', signed=False)
            if credits <= 0:
                print('Access denied — no credits remaining')
            else:
                mfrc.decrement_value(CREDITS_BLOCK, 1)    # Decrement + Transfer, (block, delta) → bool
                updated = mfrc.read_block(CREDITS_BLOCK)  # Read updated value, (block) → bytes
                if updated is not None:
                    new_balance = int.from_bytes(updated[0:4], 'little', signed=False)
                    print('spent 1 credit — remaining: {}'.format(new_balance))
        mfrc.stop_crypto()                                # Clear MFCrypto1On, () → None
    mfrc.halt_card()                                      # Send HLTA, () → None

print('===DONE: 0 passed, 0 failed===')
