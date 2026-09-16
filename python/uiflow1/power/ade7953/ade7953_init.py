from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.power.ade7953 import ADE7953Full as _ADE7953Full

_periph_ade7953 = _ADE7953Full(_periph_i2c_conn(0x38), ${voltage_gain}, ${current_gain}, 'i2c')