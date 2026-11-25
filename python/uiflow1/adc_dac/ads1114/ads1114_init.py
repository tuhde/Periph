from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.adc_dac.ads1114 import ADS1114Full as _ADS1114Full

_periph_ads1114 = _ADS1114Full(_periph_i2c_conn(${_address}, bus=${_bus}))
