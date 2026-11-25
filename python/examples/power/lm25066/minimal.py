from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.lm25066 import LM25066Minimal
import time

connection = I2CConnection(0x40)                                       # Create I²C connection, (addr=0x40) → Connection
hotswap = LM25066Minimal(connection, r_sense_mohm=1.0, current_limit_high=False)  # Create LM25066 driver, (connection, r_sense_mohm, current_limit_high) → None

while True:
    print(hotswap.voltage_in(), hotswap.voltage_out())                 # Read VIN / VOUT, () → float V each
    print(hotswap.current(), hotswap.power(), hotswap.temperature())   # Read IIN A / PIN W / MOSFET °C, () → float each
    time.sleep(1)
