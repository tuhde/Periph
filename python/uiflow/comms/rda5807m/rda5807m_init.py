from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.comms.rda5807m import RDA5807MFull as _RDA5807MFull

_periph_rda5807m = _RDA5807MFull(_periph_i2c_conn(${_address}, bus=${_bus}),
                                  frequency_mhz=${_frequency_mhz}, volume=${_volume})
