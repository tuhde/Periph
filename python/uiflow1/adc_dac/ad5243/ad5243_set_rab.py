from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.adc_dac.ad5243 import AD5243Full as _AD5243Full

_periph_ad5243 = _AD5243Full(_periph_i2c_conn(0x2F, bus=${_bus}), ${_r_ab})
_periph_ad5243.set_rab(${_r_ab}, ${_r_wiper})
