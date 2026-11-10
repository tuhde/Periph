from periph.connection.spi_auto import SPIConnection as _periph_spi_conn
from periph.connection.output_pin import MicroPythonOutputPin as _periph_out_pin
from periph.chips.motor.tmc5160 import TMC5160Full as _TMC5160Full

_periph_tmc5160 = _TMC5160Full(_periph_spi_conn(bus=${_bus}, cs_pin=${_cs_pin}, polarity=1, phase=1),
                               r_sense_ohm=${_rsense}, en_pin=_periph_out_pin(${_en}))
