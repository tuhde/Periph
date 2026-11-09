from periph.connection.spi_auto import SPIConnection as _periph_spi_conn
from periph.connection.output_pin import MicroPythonOutputPin as _periph_out_pin
from periph.chips.motor.tmc2130 import TMC2130Full as _TMC2130Full

_periph_tmc2130 = _TMC2130Full(_periph_spi_conn(bus=${_bus}, cs_pin=${_cs_pin}, polarity=1, phase=1),
                               _periph_out_pin(${_step}), _periph_out_pin(${_dir}),
                               r_sense_ohm=${_rsense}, en_pin=_periph_out_pin(${_en}))
