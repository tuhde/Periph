from machine import SPI as _SPI, Pin as _Pin
from periph.connection.display_micropython import DisplayConnection as _periph_display_conn
from periph.connection.output_pin import MicroPythonOutputPin as _periph_out_pin
from periph.chips.display.uc1611s import UC1611sSpiFull as _UC1611sSpiFull

_periph_uc1611s = _UC1611sSpiFull(
    _periph_display_conn(_SPI(2, baudrate=4000000, polarity=0, phase=0,
                              sck=_Pin(${_sck}), mosi=_Pin(${_mosi})),
                         _periph_out_pin(${_cs}), _periph_out_pin(${_cd}),
                         _periph_out_pin(${_rst}), None))
