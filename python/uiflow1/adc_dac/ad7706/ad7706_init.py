from periph.connection.spi_auto import SPIConnection as _periph_spi_conn
from periph.chips.adc_dac.ad7706 import AD7706Full as _AD7706Full

_periph_ad7706 = _AD7706Full(_periph_spi_conn(bus=${_bus}, cs_pin=${_cs}, polarity=1, phase=1), ${_vref}, ${_mclk_hz})
