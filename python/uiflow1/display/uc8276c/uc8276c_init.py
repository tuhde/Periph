from machine import SPI as _SPI, Pin as _Pin
from periph.connection.display_micropython import DisplayConnection as _periph_display_conn
from periph.connection.output_pin import MicroPythonOutputPin as _periph_out_pin
from periph.connection.input_pin import MicroPythonPin as _periph_in_pin
from periph.chips.display.uc8276c import UC8276CFull as _UC8276CFull

_periph_uc8276c = _UC8276CFull(
    _periph_display_conn(_SPI(2, baudrate=4000000, polarity=0, phase=0,
                              sck=_Pin(${_sck}), mosi=_Pin(${_mosi})),
                         _periph_out_pin(${_cs}), _periph_out_pin(${_dc}),
                         _periph_out_pin(${_res}), _periph_in_pin(_Pin(${_busy}, _Pin.IN))))
