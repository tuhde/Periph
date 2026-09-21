"""
file     Tpic6b595
time     2026-09-21
author
email
license  Apache License 2.0
"""

from machine import SPI, Pin
from periph.connection.sipo_micropython import SiPoConnection
from periph.chips.io_expander.tpic6b595 import Tpic6b595Full


class Tpic6b595:
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

    def __init__(self, bus: int = 0, device: int = 0, rck: int = 17,
                 srclr: int = -1, g: int = -1, num_devices: int = 1):
        """
        label:
            en: '%1 init bus %2 device %3 rck %4 srclr %5 g %6 num_devices %7'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            device:
                name: device
                type: int
                default: '0'
                field: number
            rck:
                name: rck
                type: int
                default: '17'
                field: number
            srclr:
                name: srclr
                type: int
                default: '-1'
                field: number
            g:
                name: g
                type: int
                default: '-1'
                field: number
            num_devices:
                name: num_devices
                type: int
                default: '1'
                field: number
        """
        _spi = SPI(bus, baudrate=1000000, polarity=0, phase=0,
                   sck=Pin(rck + 1), mosi=Pin(rck + 2))
        _rck_pin = Pin(rck, Pin.OUT)
        _srclr_pin = Pin(srclr, Pin.OUT) if srclr >= 0 else None
        _g_pin = Pin(g, Pin.OUT) if g >= 0 else None
        _connection = SiPoConnection(_spi, _rck_pin, srclr=_srclr_pin, g=_g_pin)
        self._driver = Tpic6b595Full(_connection, num_devices=num_devices)

    def write_pin(self, pin: int, value: int):
        """
        label:
            en: '%1 write pin value (0/1) pin %2 value %3'
        params:
            pin:
                name: pin
                type: int
                field: number
            value:
                name: value
                type: int
                field: number
        """
        self._driver.pin(pin).value(bool(value))

    def write_port(self, port: int, mask: int):
        """
        label:
            en: '%1 write device byte (port) %2 mask (0x00-0xFF) %3'
        params:
            port:
                name: port
                type: int
                field: number
            mask:
                name: mask
                type: int
                field: number
        """
        self._driver.write_port(port, mask)

    def fill(self, value: int):
        """
        label:
            en: '%1 fill all outputs (0/1) value %2'
        params:
            value:
                name: value
                type: int
                field: number
        """
        self._driver.fill(bool(value))

    def off(self):
        """
        label:
            en: '%1 turn every output off'
        """
        self._driver.off()

    def clear(self):
        """
        label:
            en: '%1 pulse SRCLR (shift-register clear)'
        """
        self._driver.clear()

    def set_output_enable(self, enabled: int):
        """
        label:
            en: '%1 set output enable (0/1) via G enabled %2'
        params:
            enabled:
                name: enabled
                type: int
                field: number
        """
        self._driver.set_output_enable(bool(enabled))

    def write_all(self, d0: int, d1: int):
        """
        label:
            en: '%1 write all device bytes [d0 %2 d1 %3]'
        params:
            d0:
                name: d0
                type: int
                field: number
            d1:
                name: d1
                type: int
                field: number
        """
        self._driver.write_all([d0, d1])
