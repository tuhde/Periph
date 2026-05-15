import os
from periph.transport.spi_linux import SPITransport
from periph.chips.comms.rfm9x import RFM95Full

passed = 0
failed = 0


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got 0x{:04X}, expected 0x{:04X}'.format(label, got, expected))
        failed += 1


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


SPI_BUS = int(os.environ.get('SPI_BUS', '0'))
SPI_DEVICE = int(os.environ.get('SPI_DEVICE', '0'))

transport = SPITransport(SPI_BUS, SPI_DEVICE)
rfm = RFM95Full(transport, 868_000_000)

version = rfm.version()
check_eq('version_reg', version, 0x12)
check_true('version_nonzero', version != 0)
check_true('rssi_sane', rfm.rssi() > -150 and rfm.rssi() < 0)

rfm.send(b'test')

rfm.standby()
rfm.sleep()
rfm.standby()

rfm.set_tx_power(14, use_pa_boost=False)
rfm.set_tx_power(17, use_pa_boost=True)
rfm.set_frequency(868_000_000)
rfm.configure(sf=7, bandwidth_khz=125, coding_rate=5, crc=True)

transport.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))