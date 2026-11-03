from machine import SPI as _SPI, Pin as _Pin
from periph.connection.display_micropython import DisplayConnection as _periph_display_conn
from periph.connection.output_pin import MicroPythonOutputPin as _periph_out_pin
from periph.chips.display.ssd1331 import SSD1331Full as _SSD1331Full

_periph_ssd1331 = _SSD1331Full(
    _periph_display_conn(_SPI(2, baudrate=4000000, polarity=0, phase=0,
                              sck=_Pin(${_sck}), mosi=_Pin(${_mosi})),
                         _periph_out_pin(${_cs}), _periph_out_pin(${_dc}),
                         _periph_out_pin(${_res}), None))
