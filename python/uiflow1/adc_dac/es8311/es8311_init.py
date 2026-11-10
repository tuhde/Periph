from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.adc_dac.es8311 import ES8311Full as _ES8311Full

_periph_es8311 = _ES8311Full(_periph_i2c_conn(${_address}, bus=${_bus}), ${_mclk}, ${_fs})
