from periph.transport.i2c_auto import I2CTransport
from periph.chips.memory._24aa02uid import EEPROM24AA02UIDFull
import time

transport = I2CTransport(0x50)                                                      # Create I2C transport, (addr=0x50) → Transport
eeprom = EEPROM24AA02UIDFull(transport)                                             # Create 24AA02UID driver, (transport) → None

uid = eeprom.read_uid()                                                             # Read 32-bit unique serial number, () → bytes
                                                                                    # reads 4 bytes at 0xFC-0xFF
print('UID bytes:', uid.hex().upper())
print('UID int:  ', int.from_bytes(uid, 'big'))

mfr = eeprom.read_manufacturer_code()                                               # Read manufacturer code, () → int
                                                                                    # reads 0xFA; expect 0x29 (Microchip)
dev = eeprom.read_device_code()                                                     # Read device code, () → int
                                                                                    # reads 0xFB; expect 0x41
print('MFR:', hex(mfr), 'DEV:', hex(dev))

first = eeprom.read_byte(0x00)                                                      # Read a single byte, (address=0x00-0x7F) → int
                                                                                    # random read at user EEPROM address
print('First byte:', hex(first))

eeprom.write_byte(0x10, 0xA5)                                                       # Write a single byte, (address, value) → None
                                                                                    # byte write + ACK-poll until complete (max 5 ms)
verify = eeprom.read_byte(0x10)                                                     # Read a single byte, (address=0x00-0x7F) → int
print('Wrote 0xA5, read back:', hex(verify))

data = eeprom.read(0x20, 8)                                                         # Sequential read, (address, length) → bytes
                                                                                    # reads N bytes starting at address
print('Block @ 0x20:', data.hex())

eeprom.write_page(0x40, b'\x01\x02\x03\x04')                                        # Page write, (address, data<=8 bytes) → None
                                                                                    # writes up to 8 bytes within one page; ACK-polls when done
eeprom.write(0x44, b'\xAA\xBB\xCC\xDD\xEE')                                        # Arbitrary-length write, (address, data) → None
                                                                                    # splits at 8-byte page boundaries and ACK-polls each chunk
print('Multi-page write complete')
