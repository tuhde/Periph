"""
file     PCF8579
time     2026-11-05
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.display.pcf8579 import PCF8579Full


class PCF8579:
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

    def __init__(self, bus: int = 0, address: int = 60, subaddress: int = 0, rows: int = 16, mixed: int = 0):
        """
        label:
            en: '%1 init bus %2 address %3 subaddress (0-15) %4 rows (8/16/24/32) %5 PCF8578 mixed mode (0/1) %6'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '60'
                field: number
            subaddress:
                name: subaddress
                type: int
                default: '0'
                field: number
                max: '15'
                min: '0'
            rows:
                name: rows
                type: int
                default: '16'
                field: number
                max: '32'
                min: '8'
            mixed:
                name: mixed
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = PCF8579Full(connection, subaddress, rows, bool(mixed))

    def clear(self):
        """
        label:
            en: '%1 clear display'
        """
        self._driver.clear()

    def set_pixel(self, x: int, y: int, state: int):
        """
        label:
            en: '%1 set pixel x %2 y %3 state (0/1) %4'
        params:
            x:
                name: x
                type: int
                default: '0'
                field: number
                max: '39'
                min: '0'
            y:
                name: y
                type: int
                default: '0'
                field: number
                max: '31'
                min: '0'
            state:
                name: state
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_pixel(x, y, bool(state))

    def get_pixel(self, x: int, y: int) -> bool:
        """
        label:
            en: '%1 pixel x %2 y %3 set?'
        params:
            x:
                name: x
                type: int
                default: '0'
                field: number
                max: '39'
                min: '0'
            y:
                name: y
                type: int
                default: '0'
                field: number
                max: '31'
                min: '0'
        """
        return self._driver.get_pixel(x, y)

    def write_column(self, x: int, bank: int, value: int):
        """
        label:
            en: '%1 write column byte x %2 bank %3 value %4'
        params:
            x:
                name: x
                type: int
                default: '0'
                field: number
                max: '39'
                min: '0'
            bank:
                name: bank
                type: int
                default: '0'
                field: number
                max: '3'
                min: '0'
            value:
                name: value
                type: int
                default: '255'
                field: number
                max: '255'
                min: '0'
        """
        self._driver.write_columns(x, bank, bytes([value & 0xFF]))

    def fill(self, state: int):
        """
        label:
            en: '%1 fill display (0/1) %2'
        params:
            state:
                name: state
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.fill(bool(state))

    def draw_line(self, x0: int, y0: int, x1: int, y1: int, state: int):
        """
        label:
            en: '%1 draw line x0 %2 y0 %3 x1 %4 y1 %5 state (0/1) %6'
        params:
            x0:
                name: x0
                type: int
                default: '0'
                field: number
                max: '39'
                min: '0'
            y0:
                name: y0
                type: int
                default: '0'
                field: number
                max: '31'
                min: '0'
            x1:
                name: x1
                type: int
                default: '0'
                field: number
                max: '39'
                min: '0'
            y1:
                name: y1
                type: int
                default: '0'
                field: number
                max: '31'
                min: '0'
            state:
                name: state
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.draw_line(x0, y0, x1, y1, bool(state))

    def draw_rect(self, x: int, y: int, w: int, h: int, state: int, fill: int):
        """
        label:
            en: '%1 draw rectangle x %2 y %3 width %4 height %5 state (0/1) %6 fill (0/1) %7'
        params:
            x:
                name: x
                type: int
                default: '0'
                field: number
                max: '39'
                min: '0'
            y:
                name: y
                type: int
                default: '0'
                field: number
                max: '31'
                min: '0'
            w:
                name: w
                type: int
                default: '1'
                field: number
                max: '40'
                min: '1'
            h:
                name: h
                type: int
                default: '1'
                field: number
                max: '32'
                min: '1'
            state:
                name: state
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
            fill:
                name: fill
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.draw_rect(x, y, w, h, bool(state), bool(fill))

    def set_display(self, status: int):
        """
        label:
            en: '%1 set display status (0=blank 1=normal 2=all on 3=inverse) %2'
        params:
            status:
                name: status
                type: int
                default: '1'
                field: number
                max: '3'
                min: '0'
        """
        self._driver.set_display(status)

    def set_mode(self, rows: int, mixed: int):
        """
        label:
            en: '%1 set rows (8/16/24/32) %2 PCF8578 mixed mode (0/1) %3'
        params:
            rows:
                name: rows
                type: int
                default: '16'
                field: number
                max: '32'
                min: '8'
            mixed:
                name: mixed
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_mode(rows, bool(mixed))

    def set_start_bank(self, bank: int):
        """
        label:
            en: '%1 set start bank %2'
        params:
            bank:
                name: bank
                type: int
                default: '0'
                field: number
                max: '3'
                min: '0'
        """
        self._driver.set_start_bank(bank)

    def scroll(self, banks: int):
        """
        label:
            en: '%1 scroll by banks %2'
        params:
            banks:
                name: banks
                type: int
                default: '1'
                field: number
                max: '3'
                min: '-3'
        """
        self._driver.scroll(banks)

    def reset(self):
        """
        label:
            en: '%1 reset display'
        """
        self._driver.reset()
