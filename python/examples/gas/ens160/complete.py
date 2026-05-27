import machine
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.gas.ens160 import ENS160Full
from machine import Pin

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
sensor = ENS160Full(transport)                           # Create ENS160 driver, (transport)

fw = sensor.get_firmware_version()                       # Get firmware version, () → tuple (major, minor, release)
                                                         # switches to IDLE, issues GET_APPVER, returns to STANDARD
print('Firmware: {}.{}.{}'.format(fw[0], fw[1], fw[2]))

sensor.set_compensation(25.0, 50.0)                      # Set compensation, (temp_celsius, rh_percent) → None
                                                         # improves accuracy with external T/RH readings

sensor.configure_interrupt(enabled=True, active_high=False, push_pull=False, on_data=True, on_gpr=False)  # Configure interrupt, (enabled, active_high, push_pull, on_data, on_gpr) → None
                                                         # sets INTn pin behavior for new data notification

print('Waiting for warm-up...')
while sensor.status() != 0:                              # Poll validity, () → int 0–3
    machine.sleep(1000)

tvoc = sensor.read_tvoc()                                # Read TVOC, () → float ppb
eco2 = sensor.read_eco2()                                # Read eCO2, () → float ppm
aqi = sensor.read_aqi()                                  # Read AQI, () → int 1–5
ethanol = sensor.read_ethanol()                          # Read ethanol, () → float ppb
                                                         # alias of DATA_TVOC at 0x22
r1 = sensor.read_raw_resistance(1)                       # Read raw resistance, (sensor=1 or 4) → float Ohms
r4 = sensor.read_raw_resistance(4)                       # Read raw resistance, (sensor=1 or 4) → float Ohms
actuals = sensor.read_compensation_actuals()             # Read compensation actuals, () → dict {temp_celsius, rh_percent}
                                                         # returns T/RH values used by sensor

print('TVOC={} ppb, eCO2={} ppm, AQI={}'.format(tvoc, eco2, aqi))
print('Ethanol={} ppb, R1={} Ohm, R4={} Ohm'.format(ethanol, r1, r4))
print('Actual T={} C, RH={} %'.format(actuals['temp_celsius'], actuals['rh_percent']))

sensor.sleep()                                           # Enter deep sleep, () → None
                                                         # reduces current to ~10 uA
machine.sleep(1000)
sensor.wake()                                            # Wake and resume sensing, () → None
                                                         # transitions IDLE then STANDARD

print('===DONE: 0 passed, 0 failed===')
