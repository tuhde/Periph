from periph.connection.spi_auto import SPIConnection as _periph_spi_conn
from periph.chips.comms.rfm9x import RFM95Full as _RFM95Full

_periph_rfm9x = _RFM95Full(_periph_spi_conn(bus=${_bus}, cs_pin=${_cs_pin}),
                            frequency_hz=${_frequency_hz})
