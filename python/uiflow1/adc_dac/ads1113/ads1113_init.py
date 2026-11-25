from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.adc_dac.ads1113 import ADS1113Full as _ADS1113Full

_periph_ads1113 = _ADS1113Full(_periph_i2c_conn(${_address}, bus=${_bus}))
