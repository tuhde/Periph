from periph.connection.dhtxx_auto import DHTxxConnection as _periph_dhtxx_conn
from periph.chips.humidity.dht11 import DHT11Full as _DHT11Full

_periph_dht11 = _DHT11Full(_periph_dhtxx_conn(${_pin}))
