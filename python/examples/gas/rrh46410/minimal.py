from periph.connection.i2c_auto import I2CConnection
from periph.chips.gas.rrh46410 import RRH46410Minimal

connection = I2CConnection(0x38)                                         # Create I²C connection, (addr=0x38) → Connection
sensor = RRH46410Minimal(connection)                                     # Create RRH46410 driver, (connection) → None
sensor.init()                                                            # Check product ID, start IAQ 2nd Gen mode, () → None

while True:
    r = sensor.read()                                                    # Wait for the next sample, () → dict {iaq, tvoc_mg_m3 mg/m³, etoh_ppm ppm, eco2_ppm ppm, relative_iaq, sample, warmed_up}
    print('IAQ %.1f  TVOC %.2f mg/m3  EtOH %.2f ppm  eCO2 %d ppm  %s' % (
        r['iaq'], r['tvoc_mg_m3'], r['etoh_ppm'], r['eco2_ppm'], 'ok' if r['warmed_up'] else 'warming up'))
