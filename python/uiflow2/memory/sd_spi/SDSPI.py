"""
file     SDSPI
time     2026-10-21
author
email
license  Apache License 2.0
"""

import vfs
from periph.connection.spi_auto import SPIConnection
from periph.chips.memory.sd_spi import SDSPIFull
from periph.storage.block_device import BlockDeviceVFS


class SDSPI:
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

    def __init__(self, bus: int = 2, cs_pin: int = 4):
        """
        label:
            en: '%1 init bus %2 CS pin %3'
        params:
            bus:
                name: bus
                type: int
                default: '2'
                field: number
            cs_pin:
                name: cs_pin
                type: int
                default: '4'
                field: number
        """
        connection = SPIConnection(bus=bus, cs_pin=cs_pin)
        self._driver = SDSPIFull(connection, crc=False)

    def block_count(self) -> int:
        """
        label:
            en: '%1 capacity (512-byte blocks)'
        """
        return self._driver.block_count()

    def card_type(self) -> int:
        """
        label:
            en: '%1 card type (1 SDv1, 2 SDv2, 3 SDHC)'
        """
        return self._driver.card_type()

    def read_byte(self, block: int, offset: int) -> int:
        """
        label:
            en: '%1 read byte block %2 offset %3'
        params:
            block:
                name: block
                type: int
                field: number
            offset:
                name: offset
                type: int
                field: number
        """
        return self._driver.read_block(block)[offset]

    def write_byte(self, block: int, offset: int, value: int):
        """
        label:
            en: '%1 write byte block %2 offset %3 value %4'
        params:
            block:
                name: block
                type: int
                field: number
            offset:
                name: offset
                type: int
                field: number
            value:
                name: value
                type: int
                field: number
        """
        data = bytearray(self._driver.read_block(block))
        data[offset] = value
        self._driver.write_block(block, data)

    def fill_block(self, block: int, value: int = 0):
        """
        label:
            en: '%1 fill block %2 with value %3'
        params:
            block:
                name: block
                type: int
                field: number
            value:
                name: value
                type: int
                default: '0'
                field: number
        """
        self._driver.write_block(block, bytes([value]) * 512)

    def mount(self):
        """
        label:
            en: '%1 mount as FAT at /sd'
        """
        vfs.mount(vfs.VfsFat(BlockDeviceVFS(self._driver)), '/sd')