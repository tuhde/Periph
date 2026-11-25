from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.adc_dac.ads7828 import ADS7828Full as _ADS7828Full

_periph_ads7828 = _ADS7828Full(_periph_i2c_conn(${_address}, bus=${_bus}), ${_vref} or None)
