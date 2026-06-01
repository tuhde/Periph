//! MPU-6050 — 6-axis MotionTracking device (accelerometer + gyroscope).
//!
//! Communicates over I²C (up to 400 kHz). The driver performs device reset,
//! WHO_AM_I verification, and configures sensible defaults at construction.
//!
//! ## Default configuration
//!
//! - Gyroscope full-scale: ±250 dps (FS_SEL=0)
//! - Accelerometer full-scale: ±2 g (AFS_SEL=0)
//! - DLPF: 44 Hz bandwidth (CONFIG DLPF_CFG=3, 1 kHz gyro rate)
//! - Sample rate: 200 Hz (SMPLRT_DIV=4)
//! - Clock: PLL with gyro X reference (CLKSEL=1)

use embedded_hal::i2c::I2c;
use embedded_hal::delay::DelayNs;

const REG_SMPLRT_DIV: u8 = 0x19;
const REG_CONFIG: u8 = 0x1A;
const REG_GYRO_CONFIG: u8 = 0x1B;
const REG_ACCEL_CONFIG: u8 = 0x1C;
const REG_FIFO_EN: u8 = 0x23;
const REG_INT_STATUS: u8 = 0x3A;
const REG_ACCEL_XOUT_H: u8 = 0x3B;
const REG_TEMP_OUT_H: u8 = 0x41;
const REG_GYRO_XOUT_H: u8 = 0x43;
const REG_USER_CTRL: u8 = 0x6A;
const REG_PWR_MGMT_1: u8 = 0x6B;
const REG_PWR_MGMT_2: u8 = 0x6C;
const REG_FIFO_COUNTH: u8 = 0x72;
const REG_FIFO_R_W: u8 = 0x74;
const REG_WHO_AM_I: u8 = 0x75;

const WHO_AM_I_VALUE: u8 = 0x68;

const ACCEL_SENSITIVITY: [f32; 4] = [16384.0, 8192.0, 4096.0, 2048.0];
const GYRO_SENSITIVITY: [f32; 4] = [131.0, 65.5, 32.8, 16.4];

/// MPU-6050 minimal driver — 3-axis acceleration and 3-axis angular rate.
///
/// Performs device reset, WHO_AM_I check, and configures defaults at construction.
pub struct Mpu6050Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    accel_fs: u8,
    gyro_fs: u8,
}

impl<I2C: I2c> Mpu6050Minimal<I2C> {
    /// Create a new `Mpu6050Minimal`, reset the device, and configure defaults.
    ///
    /// # Arguments
    /// * `i2c`   — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr`  — 7-bit device address (typically `0x68` or `0x69`).
    /// * `delay` — Delay provider for init timing (100 ms reset, 35 ms gyro startup).
    pub fn new<D: DelayNs>(mut i2c: I2C, addr: u8, delay: &mut D) -> Result<Self, I2C::Error> {
        write_reg(&mut i2c, addr, REG_PWR_MGMT_1, 0x80)?;
        delay.delay_ms(100);
        write_reg(&mut i2c, addr, REG_PWR_MGMT_1, 0x01)?;
        let who = read_reg8(&mut i2c, addr, REG_WHO_AM_I)?;
        if who != WHO_AM_I_VALUE {
            return Err(read_reg8(&mut i2c, addr, REG_WHO_AM_I).unwrap_err());
        }
        write_reg(&mut i2c, addr, REG_GYRO_CONFIG, 0x00)?;
        write_reg(&mut i2c, addr, REG_ACCEL_CONFIG, 0x00)?;
        write_reg(&mut i2c, addr, REG_CONFIG, 0x03)?;
        write_reg(&mut i2c, addr, REG_SMPLRT_DIV, 0x04)?;
        delay.delay_ms(35);
        Ok(Self { i2c, addr, accel_fs: 0, gyro_fs: 0 })
    }

    /// Read 3-axis linear acceleration.
    ///
    /// Returns (x, y, z) in m/s².
    pub fn accel(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        let mut buf = [0u8; 6];
        self.i2c.write_read(self.addr, &[REG_ACCEL_XOUT_H], &mut buf)?;
        let ax = i16::from_be_bytes([buf[0], buf[1]]);
        let ay = i16::from_be_bytes([buf[2], buf[3]]);
        let az = i16::from_be_bytes([buf[4], buf[5]]);
        let sens = ACCEL_SENSITIVITY[self.accel_fs as usize];
        Ok((ax as f32 / sens * 9.80665,
            ay as f32 / sens * 9.80665,
            az as f32 / sens * 9.80665))
    }

    /// Read 3-axis angular rate.
    ///
    /// Returns (x, y, z) in rad/s.
    pub fn gyro(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        let mut buf = [0u8; 6];
        self.i2c.write_read(self.addr, &[REG_GYRO_XOUT_H], &mut buf)?;
        let gx = i16::from_be_bytes([buf[0], buf[1]]);
        let gy = i16::from_be_bytes([buf[2], buf[3]]);
        let gz = i16::from_be_bytes([buf[4], buf[5]]);
        let sens = GYRO_SENSITIVITY[self.gyro_fs as usize];
        let pi_over_180 = core::f32::consts::PI / 180.0;
        Ok((gx as f32 / sens * pi_over_180,
            gy as f32 / sens * pi_over_180,
            gz as f32 / sens * pi_over_180))
    }
}

/// MPU-6050 full driver — extends [`Mpu6050Minimal`] with configuration and FIFO support.
///
/// Provides access to full-scale range configuration, DLPF, sample rate,
/// temperature, raw data, data-ready polling, sleep/standby, and FIFO management.
pub struct Mpu6050Full<I2C> {
    inner: Mpu6050Minimal<I2C>,
}

impl<I2C: I2c> Mpu6050Full<I2C> {
    /// Create a new `Mpu6050Full`, reset the device, and configure defaults.
    ///
    /// Same arguments as [`Mpu6050Minimal::new`].
    pub fn new<D: DelayNs>(i2c: I2C, addr: u8, delay: &mut D) -> Result<Self, I2C::Error> {
        let inner = Mpu6050Minimal::new(i2c, addr, delay)?;
        Ok(Self { inner })
    }

    /// Read 3-axis acceleration. Delegates to the inner [`Mpu6050Minimal`].
    pub fn accel(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        self.inner.accel()
    }

    /// Read 3-axis angular rate. Delegates to the inner [`Mpu6050Minimal`].
    pub fn gyro(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        self.inner.gyro()
    }

    /// Set gyroscope full-scale range.
    ///
    /// # Arguments
    /// * `full_scale` — Range selector 0–3 (0=±250, 1=±500, 2=±1000, 3=±2000 dps).
    pub fn configure_gyro(&mut self, full_scale: u8) -> Result<(), I2C::Error> {
        self.inner.gyro_fs = full_scale & 0x03;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_GYRO_CONFIG, (full_scale & 0x03) << 3)
    }

    /// Set accelerometer full-scale range.
    ///
    /// # Arguments
    /// * `full_scale` — Range selector 0–3 (0=±2g, 1=±4g, 2=±8g, 3=±16g).
    pub fn configure_accel(&mut self, full_scale: u8) -> Result<(), I2C::Error> {
        self.inner.accel_fs = full_scale & 0x03;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ACCEL_CONFIG, (full_scale & 0x03) << 3)
    }

    /// Set digital low-pass filter bandwidth.
    ///
    /// # Arguments
    /// * `dlpf` — Filter setting 0–6 (0=260/256 Hz, 1=184/188 Hz, 2=94/98 Hz,
    ///            3=44/42 Hz, 4=21/20 Hz, 5=10/10 Hz, 6=5/5 Hz; gyro/accel BW).
    pub fn configure_dlpf(&mut self, dlpf: u8) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, dlpf & 0x07)
    }

    /// Set sample rate divider.
    ///
    /// # Arguments
    /// * `divider` — SMPLRT_DIV value 0–255; output rate = 1 kHz / (1 + divider).
    pub fn configure_sample_rate(&mut self, divider: u8) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_SMPLRT_DIV, divider)
    }

    /// Read die temperature.
    ///
    /// Returns temperature in °C.
    pub fn temperature(&mut self) -> Result<f32, I2C::Error> {
        let raw = read_reg16_signed(&mut self.inner.i2c, self.inner.addr, REG_TEMP_OUT_H)?;
        Ok(raw as f32 / 340.0 + 36.53)
    }

    /// Read raw 3-axis accelerometer values.
    ///
    /// Returns (x, y, z) as raw 16-bit signed values.
    pub fn accel_raw(&mut self) -> Result<(i16, i16, i16), I2C::Error> {
        let mut buf = [0u8; 6];
        self.inner.i2c.write_read(self.inner.addr, &[REG_ACCEL_XOUT_H], &mut buf)?;
        Ok((i16::from_be_bytes([buf[0], buf[1]]),
            i16::from_be_bytes([buf[2], buf[3]]),
            i16::from_be_bytes([buf[4], buf[5]])))
    }

    /// Read raw 3-axis gyroscope values.
    ///
    /// Returns (x, y, z) as raw 16-bit signed values.
    pub fn gyro_raw(&mut self) -> Result<(i16, i16, i16), I2C::Error> {
        let mut buf = [0u8; 6];
        self.inner.i2c.write_read(self.inner.addr, &[REG_GYRO_XOUT_H], &mut buf)?;
        Ok((i16::from_be_bytes([buf[0], buf[1]]),
            i16::from_be_bytes([buf[2], buf[3]]),
            i16::from_be_bytes([buf[4], buf[5]])))
    }

    /// Check if new sensor data is available.
    ///
    /// Returns `true` when DATA_RDY_INT is set in INT_STATUS.
    pub fn data_ready(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_reg8(&mut self.inner.i2c, self.inner.addr, REG_INT_STATUS)? & 0x01 != 0)
    }

    /// Set or clear the SLEEP bit in PWR_MGMT_1.
    ///
    /// # Arguments
    /// * `sleep` — `true` to enter sleep mode, `false` to wake.
    pub fn set_sleep(&mut self, sleep: bool) -> Result<(), I2C::Error> {
        let mut val = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_PWR_MGMT_1)?;
        if sleep {
            val |= 0x40;
        } else {
            val &= !0x40;
        }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PWR_MGMT_1, val)
    }

    /// Put individual axes into standby mode.
    ///
    /// # Arguments
    /// * `xa` — X accelerometer standby.
    /// * `ya` — Y accelerometer standby.
    /// * `za` — Z accelerometer standby.
    /// * `xg` — X gyroscope standby.
    /// * `yg` — Y gyroscope standby.
    /// * `zg` — Z gyroscope standby.
    pub fn set_standby(&mut self, xa: bool, ya: bool, za: bool, xg: bool, yg: bool, zg: bool) -> Result<(), I2C::Error> {
        let val = ((xa as u8) << 5) | ((ya as u8) << 4) | ((za as u8) << 3) |
                  ((xg as u8) << 2) | ((yg as u8) << 1) | (zg as u8);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PWR_MGMT_2, val)
    }

    /// Read the number of bytes in the FIFO buffer.
    ///
    /// Returns FIFO byte count (0–1024).
    pub fn fifo_count(&mut self) -> Result<u16, I2C::Error> {
        let mut buf = [0u8; 2];
        self.inner.i2c.write_read(self.inner.addr, &[REG_FIFO_COUNTH], &mut buf)?;
        Ok((((buf[0] as u16) & 0x1F) << 8) | buf[1] as u16)
    }

    /// Read all available data from the FIFO buffer.
    ///
    /// # Arguments
    /// * `buf` — Buffer to receive FIFO data.
    ///
    /// Returns the number of bytes actually read.
    pub fn read_fifo(&mut self, buf: &mut [u8]) -> Result<u16, I2C::Error> {
        let count = self.fifo_count()?;
        if count == 0 {
            return Ok(0);
        }
        let to_read = if (count as usize) < buf.len() { count as usize } else { buf.len() };
        self.inner.i2c.write_read(self.inner.addr, &[REG_FIFO_R_W], &mut buf[..to_read])?;
        Ok(to_read as u16)
    }

    /// Configure and enable FIFO sources.
    ///
    /// # Arguments
    /// * `gyro`  — Enable gyroscope data in FIFO.
    /// * `accel` — Enable accelerometer data in FIFO.
    /// * `temp`  — Enable temperature data in FIFO.
    pub fn enable_fifo(&mut self, gyro: bool, accel: bool, temp: bool) -> Result<(), I2C::Error> {
        let fifo_en = ((accel as u8) << 3) | ((temp as u8) << 2) | ((gyro as u8) << 4);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_FIFO_EN, fifo_en)?;
        let user_ctrl = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_USER_CTRL)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_USER_CTRL, user_ctrl | 0x40)
    }

    /// Reset the FIFO buffer by setting FIFO_RST in USER_CTRL.
    pub fn reset_fifo(&mut self) -> Result<(), I2C::Error> {
        let user_ctrl = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_USER_CTRL)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_USER_CTRL, user_ctrl | 0x04)
    }
}

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8) -> Result<(), I2C::Error> {
    i2c.write(addr, &[reg, value])
}

fn read_reg8<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u8, I2C::Error> {
    let mut buf = [0u8; 1];
    i2c.write_read(addr, &[reg], &mut buf)?;
    Ok(buf[0])
}

fn read_reg16_signed<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<i16, I2C::Error> {
    let mut buf = [0u8; 2];
    i2c.write_read(addr, &[reg], &mut buf)?;
    Ok(i16::from_be_bytes([buf[0], buf[1]]))
}
