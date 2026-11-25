from periph.connection.i2c_auto import I2CConnection
from periph.chips.memory.mb85rc import MB85RCFull
import struct
import time

connection = I2CConnection(0x50, reg_bytes=2)                                         # Create I2C connection, (addr=0x50, reg_bytes=2) → Connection
fram = MB85RCFull(connection)                                                         # Create MB85RC driver, (connection) → None

# --- Persistent boot counter ---
# A 4-byte big-endian counter at 0x0000 survives power loss. FRAM has no
# write cycle, so the increment is committed the moment the write ends.
counter = struct.unpack('>I', fram.read(0x0000, 4))[0] + 1                            # Sequential read, (address, length) → bytes
fram.write(0x0000, struct.pack('>I', counter))                                        # Sequential write, (address, data) → None
print('Boot count:', counter)

# --- Ring-buffer logger, back-to-back with no delays ---
# 1,000 records of 16 bytes (index + counter + padding) are written one
# after the other without any pause — an EEPROM would need ACK polling
# after every page, FRAM does not.
LOG_BASE = 0x0100
RECORD = 16
COUNT = 1000
RING = 200                                                                            # records that fit before 0x7FFF
t0 = time.time()
for i in range(COUNT):
    rec = struct.pack('>II8x', i, counter)
    fram.write(LOG_BASE + (i % RING) * RECORD, rec)                                   # Sequential write, (address, data) → None
dt = time.time() - t0
print('Logged %d bytes in %.3f s = %d B/s' % (COUNT * RECORD, dt, int(COUNT * RECORD / dt)))

# --- Verify the last five records ---
# Read back the newest records from the ring and check their contents.
ok = True
for i in range(COUNT - 5, COUNT):
    rec = fram.read(LOG_BASE + (i % RING) * RECORD, RECORD)                           # Sequential read, (address, length) → bytes
    if struct.unpack('>II', rec[:8]) != (i, counter):
        ok = False
print('Last 5 records verified:', ok)

# --- Clear the log area ---
# fill() zeroes the whole ring in chunked transactions.
fram.fill(LOG_BASE, RING * RECORD, 0)                                                 # Fill a range, (address, length, value=0) → None
print('Log cleared; array size:', fram.size(), 'bytes')                               # Get array size, () → int

# --- Device ID (needs the reserved address 0x7C) ---
# Some hosts (Linux i2c-dev) refuse addresses 0x78-0x7F; skip then.
try:
    id_connection = I2CConnection(0x7C, reg_bytes=1)                                  # Create Device ID connection, (addr=0x7C, reg_bytes=1) → Connection
    print('Device ID:', fram.read_device_id(id_connection, 0x50))                     # Read Device ID, (id_connection, address=0x50) → (int, int, int)
except OSError:
    print('Device ID not available')
