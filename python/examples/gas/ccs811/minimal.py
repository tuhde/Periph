from periph.connection.i2c_auto import I2CConnection
from periph.chips.gas.ccs811 import CCS811Minimal

connection = I2CConnection(0x5A)                                         # Create I²C connection, (addr=0x5A) → Connection
sensor = CCS811Minimal(connection)                                       # Create CCS811 driver, (connection) → None
sensor.init()                                                            # Check HW_ID, start application, mode 1, () → None

while True:
    m = sensor.read()                                                    # Wait for the next sample, () → dict {eco2_ppm ppm, tvoc_ppb ppb}
    print('eCO2 %d ppm, TVOC %d ppb' % (m['eco2_ppm'], m['tvoc_ppb']))
