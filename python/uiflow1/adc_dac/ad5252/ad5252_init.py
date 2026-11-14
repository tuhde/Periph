from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.adc_dac.ad5252 import AD5252Full as _AD5252Full

_periph_ad5252 = _AD5252Full(_periph_i2c_conn(0x2C, bus=${_bus}), ${_r_ab})
