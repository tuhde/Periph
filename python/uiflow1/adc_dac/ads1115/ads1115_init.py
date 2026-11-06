from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.adc_dac.ads1115 import ADS1115Full as _ADS1115Full

_periph_ads1115 = _ADS1115Full(_periph_i2c_conn(${_address}, bus=${_bus}))
