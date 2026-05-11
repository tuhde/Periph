import sys
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0, 'python/periph')

try:
    with patch('spidev.SpiDev'):
        from periph.transport.spi_linux import SPITransport
        HAS_SPIDEV = True
except ImportError:
    HAS_SPIDEV = False


@unittest.skipUnless(HAS_SPIDEV, 'spidev not available')
class TestSPITransportLinux(unittest.TestCase):

    def setUp(self):
        self.patcher = patch('spidev.SpiDev')
        self.mock_spi_class = self.patcher.start()
        self.mock_spi = self.mock_spi_class.return_value
        self.transport = SPITransport(0, 0)

    def tearDown(self):
        self.patcher.stop()

    def test_write_sends_bytes_via_writebytes(self):
        data = b'\x01\x02\x03'
        self.transport.write(data)
        self.mock_spi.writebytes.assert_called_once_with(list(data))

    def test_read_returns_bytes_from_readbytes(self):
        self.mock_spi.readbytes.return_value = [0x10, 0x20, 0x30]
        result = self.transport.read(3)
        self.mock_spi.readbytes.assert_called_once_with(3)
        self.assertEqual(result, b'\x10\x20\x30')

    def test_write_read_uses_xfer2_full_duplex(self):
        self.mock_spi.xfer2.return_value = [0x00, 0x00, 0xAB, 0xCD]
        data = b'\x55\xAA'
        result = self.transport.write_read(data, 2)
        self.mock_spi.xfer2.assert_called_once_with([0x55, 0xAA, 0, 0])
        self.assertEqual(result, b'\xAB\xCD')

    def test_close_releases_device(self):
        self.transport.close()
        self.mock_spi.close.assert_called_once()


if __name__ == '__main__':
    unittest.main()