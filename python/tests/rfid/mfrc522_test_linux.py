import os
import sys
import time
from periph.transport.spi_linux import SPITransport
from periph.chips.rfid.mfrc522 import MFRC522Full

SPI_BUS      = int(os.environ.get('LINUX_SPI_BUS', '0'))
SPI_DEVICE   = int(os.environ.get('LINUX_SPI_DEVICE', '0'))
SPI_SPEED_HZ = int(os.environ.get('LINUX_SPI_SPEED_HZ', '1000000'))

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


transport = SPITransport(SPI_BUS, SPI_DEVICE, max_speed_hz=SPI_SPEED_HZ, mode=0)
mfrc = MFRC522Full(transport)

chip_type, version = mfrc.version()
check_true('chip_type == 0x09 (MFRC522)', chip_type == 0x09)
check_true('version in {1, 2}', version in (1, 2))

mfrc.antenna_on()
ctrl = mfrc._read_reg(0x14)
check_true('antenna_on sets TxControlReg bits 0|1', (ctrl & 0x03) == 0x03)
mfrc.antenna_off()
ctrl = mfrc._read_reg(0x14)
check_true('antenna_off clears TxControlReg bits 0|1', (ctrl & 0x03) == 0x00)
mfrc.antenna_on()

for dB in (18, 23, 33, 38, 43, 48):
    mfrc.set_antenna_gain(dB)
    g = mfrc.antenna_gain()
    check_true('set_antenna_gain({}) read back == {}'.format(dB, dB), g == dB)

present = mfrc.is_card_present()
check_true('is_card_present returns bool', isinstance(present, bool))

raw = mfrc._read_reg(0x37)
check_true('raw VersionReg in 0x90/0x91/0x92', raw in (0x90, 0x91, 0x92))

transport.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
