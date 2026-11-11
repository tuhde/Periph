from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.adc_dac.cat5171 import CAT5171Full as _CAT5171Full

_periph_cat5171 = _CAT5171Full(_periph_i2c_conn(0x2C, bus=${_bus}), ${_r_ab})
_periph_cat5171.is_shutdown()
