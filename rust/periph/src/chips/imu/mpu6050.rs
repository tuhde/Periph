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

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8) -> Result<(), I2C::Error> {
    i2c.write(addr, &[reg, value])
}

fn read_reg8<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u8, I2C::Error> {
    let mut buf = [0u8; 1];
    i2c.write_read(addr, &[reg], &mut buf)?;
    Ok(buf[0])
}
