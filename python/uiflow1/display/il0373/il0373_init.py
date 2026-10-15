from machine import SPI as _SPI, Pin as _Pin
from periph.connection.display_micropython import DisplayConnection as _periph_display_conn
from periph.connection.output_pin import MicroPythonOutputPin as _periph_out_pin
from periph.connection.input_pin import MicroPythonPin as _periph_in_pin
from periph.chips.display.il0373 import IL0373Full as _IL0373Full

_periph_il0373 = _IL0373Full(
    _periph_display_conn(_SPI(2, baudrate=4000000, polarity=0, phase=0,
                              sck=_Pin(${_sck}), mosi=_Pin(${_mosi})),
                         _periph_out_pin(${_cs}), _periph_out_pin(${_dc}),
                         _periph_out_pin(${_res}), _periph_in_pin(_Pin(${_busy}, _Pin.IN))))
