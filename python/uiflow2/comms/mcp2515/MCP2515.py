"""
file     MCP2515
time     2026-09-15
author
email
license  Apache License 2.0
"""

from periph.connection.spi_auto import SPIConnection
from periph.chips.comms.mcp2515 import MCP2515Full


class MCP2515:
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

    def __init__(self, bus: int = 1, cs_pin: int = 5, bitrate_kbps: int = 125):
        """
        label:
            en: '%1 init SPI bus %2 CS pin %3 bitrate (kbit/s) %4'
        params:
            bus:
                name: bus
                type: int
                default: '1'
                field: number
            cs_pin:
                name: cs_pin
                type: int
                default: '5'
                field: number
            bitrate_kbps:
                name: bitrate_kbps
                type: int
                default: '125'
                field: number
        """
        connection = SPIConnection(bus=bus, cs_pin=cs_pin)
        self._driver = MCP2515Full(connection, bitrate_kbps=bitrate_kbps)

    def send(self, id: int, data: str = '', extended: int = 0):
        """
        label:
            en: '%1 send CAN id %2 data (hex) %3 extended (1) / standard (0) %4'
        params:
            id:
                name: id
                type: int
                field: number
            data:
                name: data
                type: str
            extended:
                name: extended
                type: int
                default: '0'
                field: number
        """
        self._driver.send(id, bytes.fromhex(data) if data else b'', extended=bool(extended))

    def recv(self, timeout_ms: int = 100) -> str:
        """
        label:
            en: '%1 receive CAN frame (hex: id,extended,data or empty), timeout (ms) %2'
        params:
            timeout_ms:
                name: timeout_ms
                type: int
                default: '100'
                field: number
        """
        frame = self._driver.recv(timeout_ms=timeout_ms)
        if frame is None:
            return ''
        return '%X,%d,%s' % (frame.id, 1 if frame.extended else 0, frame.data.hex())

    def set_mode(self, mode: str = 'normal'):
        """
        label:
            en: '%1 set mode %2'
        params:
            mode:
                name: mode
                type: str
                default: 'normal'
        """
        self._driver.set_mode(mode)

    def set_filter(self, filter_num: int = 0, id: int = 0, extended: int = 0):
        """
        label:
            en: '%1 set filter %2 id %3 extended (1/0) %4'
        params:
            filter_num:
                name: filter_num
                type: int
                default: '0'
                field: number
                min: '0'
                max: '5'
            id:
                name: id
                type: int
                field: number
            extended:
                name: extended
                type: int
                default: '0'
                field: number
        """
        self._driver.set_filter(filter_num, id, extended=bool(extended))

    def set_mask(self, mask_num: int = 0, mask: int = 0, extended: int = 0):
        """
        label:
            en: '%1 set mask %2 mask %3 extended (1/0) %4'
        params:
            mask_num:
                name: mask_num
                type: int
                default: '0'
                field: number
                min: '0'
                max: '1'
            mask:
                name: mask
                type: int
                field: number
            extended:
                name: extended
                type: int
                default: '0'
                field: number
        """
        self._driver.set_mask(mask_num, mask, extended=bool(extended))

    def set_one_shot(self, enable: int = 1):
        """
        label:
            en: '%1 set one-shot mode (1/0) %2'
        params:
            enable:
                name: enable
                type: int
                default: '1'
                field: number
        """
        self._driver.set_one_shot(bool(enable))

    def read_errors(self) -> str:
        """
        label:
            en: '%1 read errors (TEC, REC, EFLG hex)'
        """
        e = self._driver.read_errors()
        return '%d,%d,%02X' % (e['tec'], e['rec'], e['eflg'])

    def reset(self):
        """
        label:
            en: '%1 reset'
        """
        self._driver.reset()
