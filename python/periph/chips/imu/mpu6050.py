import struct
import time


class MPU6050Minimal:
    """MPU-6050 6-axis MotionTracking device (accelerometer + gyroscope) — minimal interface.

    Provides 3-axis acceleration and 3-axis angular rate readings with no
    configuration beyond the transport. Performs device reset, WHO_AM_I check,
    and enables all sensors at defaults during initialization.

    Default configuration (baked in at construction):
        - Gyroscope full-scale: ±250 dps (FS_SEL=0)
        - Accelerometer full-scale: ±2 g (AFS_SEL=0)
        - DLPF: 44 Hz bandwidth (CONFIG DLPF_CFG=3, 1 kHz gyro rate)
        - Sample rate: 200 Hz (SMPLRT_DIV=4)
        - Clock: PLL with gyro X reference (CLKSEL=1)

    Args:
        transport: Configured I²C transport pointing at the device.
    """

    _REG_SMPLRT_DIV   = 0x19
    _REG_CONFIG       = 0x1A
    _REG_GYRO_CONFIG  = 0x1B
    _REG_ACCEL_CONFIG = 0x1C
    _REG_FIFO_EN      = 0x23
    _REG_INT_PIN_CFG  = 0x37
    _REG_INT_ENABLE   = 0x38
    _REG_INT_STATUS   = 0x3A
    _REG_ACCEL_XOUT_H = 0x3B
    _REG_TEMP_OUT_H   = 0x41
    _REG_GYRO_XOUT_H  = 0x43
    _REG_USER_CTRL    = 0x6A
    _REG_PWR_MGMT_1   = 0x6B
    _REG_PWR_MGMT_2   = 0x6C
    _REG_FIFO_COUNTH  = 0x72
    _REG_FIFO_COUNTL  = 0x73
    _REG_FIFO_R_W     = 0x74
    _REG_WHO_AM_I     = 0x75

    _WHO_AM_I_VALUE = 0x68

    _ACCEL_SENSITIVITY = (16384.0, 8192.0, 4096.0, 2048.0)
    _GYRO_SENSITIVITY  = (131.0, 65.5, 32.8, 16.4)

    def __init__(self, transport):
        self._transport = transport
        self._accel_fs = 0
        self._gyro_fs = 0
        self._write_reg(self._REG_PWR_MGMT_1, 0x80)
        time.sleep(0.1)
        self._write_reg(self._REG_PWR_MGMT_1, 0x01)
        who = self._read_reg(self._REG_WHO_AM_I)
        if who != self._WHO_AM_I_VALUE:
            raise ValueError('MPU6050 WHO_AM_I: expected 0x{:02X}, got 0x{:02X}'.format(
                self._WHO_AM_I_VALUE, who))
        self._write_reg(self._REG_GYRO_CONFIG, 0x00)
        self._write_reg(self._REG_ACCEL_CONFIG, 0x00)
        self._write_reg(self._REG_CONFIG, 0x03)
        self._write_reg(self._REG_SMPLRT_DIV, 0x04)
        time.sleep(0.035)

    def _write_reg(self, reg, value):
        self._transport.write(bytes([reg, value]))

    def _read_reg(self, reg):
        return self._transport.write_read(bytes([reg]), 1)[0]

    def _read_reg16_signed(self, reg):
        raw = self._transport.write_read(bytes([reg]), 2)
        return struct.unpack('>h', raw)[0]

    def _read_burst(self, reg, n):
        return self._transport.write_read(bytes([reg]), n)

    def accel(self):
        """Read 3-axis linear acceleration.

        Returns:
            tuple: (x, y, z) acceleration in m/s².
        """
        raw = self._read_burst(self._REG_ACCEL_XOUT_H, 6)
        ax, ay, az = struct.unpack('>hhh', raw)
        sens = self._ACCEL_SENSITIVITY[self._accel_fs]
        return (ax / sens * 9.80665, ay / sens * 9.80665, az / sens * 9.80665)

    def gyro(self):
        """Read 3-axis angular rate.

        Returns:
            tuple: (x, y, z) angular rate in rad/s.
        """
        raw = self._read_burst(self._REG_GYRO_XOUT_H, 6)
        gx, gy, gz = struct.unpack('>hhh', raw)
        sens = self._GYRO_SENSITIVITY[self._gyro_fs]
        return (gx / sens * 3.141592653589793 / 180.0,
                gy / sens * 3.141592653589793 / 180.0,
                gz / sens * 3.141592653589793 / 180.0)
