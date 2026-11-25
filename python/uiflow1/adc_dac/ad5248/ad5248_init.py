from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.adc_dac.ad5248 import AD5248Full as _AD5248Full

_periph_ad5248 = _AD5248Full(_periph_i2c_conn(${_address}, bus=${_bus}), r_ab=${_r_ab})
