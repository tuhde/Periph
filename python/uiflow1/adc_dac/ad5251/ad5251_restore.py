from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.adc_dac.ad5251 import AD5251Full as _AD5251Full

_periph_ad5251 = _AD5251Full(_periph_i2c_conn(0x2C, bus=${_bus}), ${_r_ab})
_periph_ad5251.restore((None if ${_channel} < 0 else ${_channel}))
