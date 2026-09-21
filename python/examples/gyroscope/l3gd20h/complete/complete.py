from periph.connection.i2c_linux import I2CConnection
from periph.chips.gyroscope.l3gd20h import L3GD20HFull

conn = I2CConnection(bus=1, addr=0x6A)
gyro = L3GD20HFull(conn)                                            # Create L3GD20H driver, (connection, bus_type='i2c')

gyro.configure(odr=1, bw=0, full_scale=1)                           # Configure ADC, (odr 0-3, bw 0-3, full_scale 0-2) -> None
# sets ODR=190 Hz, bandwidth=default, full-scale=±500 dps

gyro.configure_hp_filter(mode=0, cutoff=0)                          # Configure HPF, (mode 0-3, cutoff 0-15) -> None
# sets high-pass filter mode=normal, cutoff=lowest

gyro.enable_hp_filter(True)                                         # Enable HPF, (enable=True) -> None
# enables high-pass filter on output path

gyro.configure_fifo(mode=1, watermark=10)                           # Configure FIFO, (mode 0/1/2/3/7, watermark 0-31) -> None
# sets FIFO mode=FIFO, watermark=10 samples

gyro.enable_fifo(True)                                              # Enable FIFO, (enable=True) -> None
# enables FIFO_EN bit in CTRL_REG5

gyro.set_power_mode(L3GD20HFull.POWER_NORMAL)                       # Set power mode, (mode='normal'/'sleep'/'power_down') -> None
# ensures normal mode with all axes enabled

print("WHO_AM_I: 0x{:02X}".format(gyro._read_reg(0x0F, 1)[0]))      # Read WHO_AM_I, () -> int
# returns 0xD4 (L3GD20) or 0xD7 (L3GD20H)

temp = gyro.temperature()                                           # Read temperature, () -> int
# returns relative temperature count (1 LSB/degC)

while True:
    if gyro.data_ready():                                           # Check data ready, () -> bool
        x, y, z = gyro.gyro()                                       # Read angular rate, () -> (float, float, float) rad/s
        print("x={:.3f} y={:.3f} z={:.3f} rad/s".format(x, y, z))
        samples = gyro.read_fifo()                                  # Read all FIFO samples, () -> list[(float,float,float)]
        if samples:
            print("FIFO: {} samples".format(len(samples)))