"""
file     MB85RC
time     2026-11-03
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.memory.mb85rc import MB85RCFull


class MB85RC:
    """
    note:
        en: ''
    details:
        color: '#C084FC'
        link: https://github.com/tuhde/Periph
        image: ''
        category: Custom
    example: ''
    """

    def __init__(self, bus: int = 0, address: int = 80):
        """
        label:
            en: '%1 init bus %2 address %3'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '80'
                field: number
        """
        self._bus = bus
        self._address = address
        connection = I2CConnection(address, bus=bus, reg_bytes=2)
        self._driver = MB85RCFull(connection)

    def read_byte(self, address: int) -> int:
        """
        label:
            en: '%1 read byte address %2'
        params:
            address:
                name: address
                type: int
                field: number
        """
        return self._driver.read_byte(address)

    def write_byte(self, address: int, value: int):
        """
        label:
            en: '%1 write byte address %2 value %3'
        params:
            address:
                name: address
                type: int
                field: number
            value:
                name: value
                type: int
                field: number
        """
        self._driver.write_byte(address, value)

    def read(self, address: int, length: int) -> str:
        """
        label:
            en: '%1 read address %2 length %3 (hex)'
        params:
            address:
                name: address
                type: int
                field: number
            length:
                name: length
                type: int
                field: number
        """
        return self._driver.read(address, length).hex()

    def write(self, address: int, data: str):
        """
        label:
            en: '%1 write address %2 data (hex) %3'
        params:
            address:
                name: address
                type: int
                field: number
            data:
                name: data
                type: str
        """
        self._driver.write(address, bytes.fromhex(data))

    def fill(self, address: int, length: int, value: int = 0):
        """
        label:
            en: '%1 fill address %2 length %3 value %4'
        params:
            address:
                name: address
                type: int
                field: number
            length:
                name: length
                type: int
                field: number
            value:
                name: value
                type: int
                default: '0'
                field: number
        """
        self._driver.fill(address, length, value)

    def size(self) -> int:
        """
        label:
            en: '%1 size (bytes)'
        """
        return self._driver.size()

    def read_device_id(self) -> str:
        """
        label:
            en: '%1 device ID (hex, empty if 0x7C unavailable)'
        """
        try:
            id_connection = I2CConnection(0x7C, bus=self._bus, reg_bytes=1)
            m, d, p = self._driver.read_device_id(id_connection, self._address)
        except OSError:
            return ''
        return '%03X%X%02X' % (m, d, p)
