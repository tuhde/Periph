import sys
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0, 'python/periph')
from periph.transport.spi_circuitpython import SPITransport


class TestSPITransportCircuitPython(unittest.TestCase):

    def setUp(self):
        self.mock_bus = MagicMock()
        self.mock_cs = MagicMock()
        self.transport = SPITransport(self.mock_bus, self.mock_cs)

    def test_write_locks_bus_configures_cs_low_sends_and_unlocks(self):
        self.mock_bus.try_lock.return_value = True
        data = b'\x01\x02\x03'
        self.transport.write(data)
        self.mock_bus.try_lock.assert_called_once()
        self.mock_bus.configure.assert_called_once()
        self.mock_cs.value.assert_any_call(False)
        self.mock_bus.write.assert_called_once_with(data)
        self.mock_cs.value.assert_any_call(True)
        self.mock_bus.unlock.assert_called_once()

    def test_read_locks_bus_configures_cs_low_reads_and_unlocks(self):
        self.mock_bus.try_lock.return_value = True
        buf = bytearray(3)
        self.mock_bus.readinto.return_value = buf
        result = self.transport.read(3)
        self.mock_bus.try_lock.assert_called_once()
        self.mock_cs.value.assert_any_call(False)
        self.mock_bus.readinto.assert_called_once()
        self.mock_cs.value.assert_any_call(True)
        self.mock_bus.unlock.assert_called_once()

    def test_write_read_full_duplex_exchange(self):
        self.mock_bus.try_lock.return_value = True
        data = b'\x55\xAA'
        self.transport.write_read(data, 2)
        self.mock_bus.try_lock.assert_called_once()
        self.mock_bus.write_readinto.assert_called_once()
        self.mock_cs.value.assert_any_call(False)
        self.mock_cs.value.assert_any_call(True)
        self.mock_bus.unlock.assert_called_once()


if __name__ == '__main__':
    unittest.main()