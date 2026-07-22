import time
import _testconfig as cfg
from periph.transport.spi_micropython import SPITransport
from periph.chips.rfid.mfrc522 import MFRC522Full
from machine import SPI, Pin

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


spi = SPI(1, baudrate=1000000, polarity=0, phase=0)
cs  = Pin('P9', Pin.OUT)
transport = SPITransport(spi, cs)
mfrc = MFRC522Full(transport)

# --- version ---
chip_type, version = mfrc.version()
check_true('chip_type == 0x09 (MFRC522)', chip_type == 0x09)
check_true('version in {1, 2}', version in (1, 2))

# --- antenna control ---
mfrc.antenna_on()
ctrl = mfrc._read_reg(0x14)
check_true('antenna_on sets TxControlReg bits 0|1', (ctrl & 0x03) == 0x03)
mfrc.antenna_off()
ctrl = mfrc._read_reg(0x14)
check_true('antenna_off clears TxControlReg bits 0|1', (ctrl & 0x03) == 0x00)
mfrc.antenna_on()

# --- gain control ---
for dB in (18, 23, 33, 38, 43, 48):
    mfrc.set_antenna_gain(dB)
    g = mfrc.antenna_gain()
    check_true('set_antenna_gain({}) read back == {}'.format(dB, dB), g == dB)

try:
    mfrc.set_antenna_gain(50)
    check_true('set_antenna_gain(50) raises ValueError', False)
except ValueError:
    check_true('set_antenna_gain(50) raises ValueError', True)

# --- self test ---
ok = mfrc.self_test()
check_true('self_test returns bool', isinstance(ok, bool))

# --- card detection (may pass or fail depending on whether a card is in field) ---
present = mfrc.is_card_present()
check_true('is_card_present returns bool', isinstance(present, bool))
if present:
    uid = mfrc.read_uid()
    check_true('read_uid returns bytes when card present', isinstance(uid, (bytes, bytearray)))
    check_true('read_uid length 4/7/10', uid is not None and len(uid) in (4, 7, 10))

# --- version reg raw read ---
raw = mfrc._read_reg(0x37)
check_true('raw VersionReg in 0x90/0x91/0x92', raw in (0x90, 0x91, 0x92))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
