from machine import SPI, Pin
from periph.connection.sipo_micropython import SiPoConnection
from periph.chips.io_expander.tpic6b595 import Tpic6b595Full

_spi = SPI(${_bus}, baudrate=1000000, polarity=0, phase=0,
           sck=Pin(${_rck} + 1), mosi=Pin(${_rck} + 2))
_rck = Pin(${_rck}, Pin.OUT)
_srclr = Pin(${_srclr}, Pin.OUT) if ${_srclr} >= 0 else None
_g = Pin(${_g}, Pin.OUT) if ${_g} >= 0 else None
_periph_sipo = SiPoConnection(_spi, _rck, srclr=_srclr, g=_g)

_periph_tpic6b595 = Tpic6b595Full(_periph_sipo, num_devices=${_num_devices})
