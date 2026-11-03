from machine import SPI as _SPI, Pin as _Pin
from periph.connection.display_micropython import DisplayConnection as _periph_display_conn
from periph.connection.output_pin import MicroPythonOutputPin as _periph_out_pin
from periph.chips.display.st7036 import ST7036Full as _ST7036Full

_periph_st7036 = _ST7036Full(
    _periph_display_conn(_SPI(2, baudrate=1000000, polarity=0, phase=0,
                              sck=_Pin(${_sck}), mosi=_Pin(${_mosi})),
                         _periph_out_pin(${_cs}), _periph_out_pin(${_rs}),
                         _periph_out_pin(${_res}), None))
