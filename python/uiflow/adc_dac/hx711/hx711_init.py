from periph.connection.hx711_auto import HX711Connection as _periph_hx711_conn
from periph.chips.adc_dac.hx711 import HX711Full as _HX711Full

_periph_hx711 = _HX711Full(_periph_hx711_conn(${_dout}, ${_pd_sck}))
