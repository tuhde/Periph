from periph.connection.spi_auto import SPIConnection as _periph_spi_conn
from periph.chips.adc_dac.hy3131 import HY3131Full as _HY3131Full

_periph_hy3131 = _HY3131Full(_periph_spi_conn(bus=${_bus}, cs_pin=${_cs}, polarity=0, phase=0), ${_vref})
