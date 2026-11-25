from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.adc_dac.ads1014 import ADS1014Full as _ADS1014Full

_periph_ads1014 = _ADS1014Full(_periph_i2c_conn(${_address}, bus=${_bus}))