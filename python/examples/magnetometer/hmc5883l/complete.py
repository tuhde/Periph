from periph.connection.i2c_auto import I2CConnection
from periph.chips.magnetometer.hmc5883l import HMC5883LFull
import time

connection = I2CConnection(0x1E)
hmc5883l = HMC5883LFull(connection)                          # Create HMC5883L driver, (connection) → HMC5883LFull

# --- Identification ---
print(hmc5883l.identify())                                 # Read ID registers, () → (int, int, int)

# --- Status ---
print(hmc5883l.status())                                   # Read raw status, () → int
print(hmc5883l.data_ready())                               # Check data ready, () → bool

# --- Magnetic field readings ---
x, y, z = hmc5883l.magnetic_field()                        # Read magnetic field, () → (float T, float T, float T)
print('X=%.6f T  Y=%.6f T  Z=%.6f T' % (x, y, z))

# --- Configuration ---
hmc5883l.configure(odr=15, averaging=8, gain=1)            # Configure chip, (odr 0.75-75 Hz, averaging 1/2/4/8, gain 0-7) → None
                                                          # writes Config A and B registers
hmc5883l.set_gain(2)                                       # Set gain, (gain 0-7) → None
                                                          # updates GN bits in Config B
hmc5883l.set_mode('single')                                # Set operating mode, ('continuous'|'single'|'idle') → None
                                                          # writes MD bits in Mode Register

# --- Single-shot measurement ---
x, y, z = hmc5883l.single_measurement()                    # Single-shot measurement, () → (float T, float T, float T)
                                                          # writes single-measurement mode, waits 6 ms, reads all axes
print('Single: X=%.6f T  Y=%.6f T  Z=%.6f T' % (x, y, z))

hmc5883l.set_mode('continuous')                            # Set operating mode, ('continuous'|'single'|'idle') → None

# --- Self-test ---
x, y, z = hmc5883l.self_test(positive=True)                # Self-test with positive bias, (positive=bool) → (float T, float T, float T)
                                                          # configures bias, takes measurement, restores normal mode
print('Self-test: X=%.6f T  Y=%.6f T  Z=%.6f T' % (x, y, z))