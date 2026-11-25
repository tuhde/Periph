from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.adc_dac.ads1013 import ADS1013Full as _ADS1013Full

_periph_ads1013 = _ADS1013Full(_periph_i2c_conn(${_address}, bus=${_bus}))