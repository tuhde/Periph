//! MPU-9255 — 9-axis MotionTracking device (accelerometer + gyroscope + magnetometer).
//!
//! Communicates over I²C (up to 400 kHz). The driver performs device reset,
//! WHO_AM_I verification, and configures sensible defaults at construction.
//!
//! ## Default configuration (Minimal)
//!
//! - Gyroscope full-scale: ±250 dps (GYRO_FS_SEL=0)
//! - Accelerometer full-scale: ±2 g (ACCEL_FS_SEL=0)
//! - Gyroscope DLPF: 41 Hz bandwidth (CONFIG DLPF_CFG=3)
//! - Accelerometer DLPF: 44.8 Hz bandwidth (ACCEL_CONFIG2 A_DLPFCFG=3)
//! - Sample rate: 200 Hz (SMPLRT_DIV=4)
//! - Clock: auto PLL (CLKSEL=1)
//! - All six axes enabled
//!
//! The Full driver additionally exposes wake-on-motion (`configure_wake_on_motion`,
//! `motion_detected`), which puts the chip into accelerometer-only low-power mode
//! and arms the hardware motion-detection logic. The MPU-9255 WHO_AM_I register
//! reads `0x73` (the MPU-9250 reads `0x71` at the same address).

use embedded_hal::i2c::I2c;
use embedded_hal::delay::DelayNs;

const REG_SMPLRT_DIV: u8 = 0x19;
const REG_CONFIG: u8 = 0x1A;
const REG_GYRO_CONFIG: u8 = 0x1B;
const REG_ACCEL_CONFIG: u8 = 0x1C;
const REG_ACCEL_CONFIG2: u8 = 0x1D;
const REG_LP_ACCEL_ODR: u8 = 0x1E;
const REG_WOM_THR: u8 = 0x1F;
const REG_FIFO_EN: u8 = 0x23;
const REG_INT_PIN_CFG: u8 = 0x37;
const REG_INT_ENABLE: u8 = 0x38;
const REG_INT_STATUS: u8 = 0x3A;
const REG_ACCEL_XOUT_H: u8 = 0x3B;
const REG_TEMP_OUT_H: u8 = 0x41;
const REG_GYRO_XOUT_H: u8 = 0x43;
const REG_MOT_DETECT_CTRL: u8 = 0x69;
const REG_USER_CTRL: u8 = 0x6A;
const REG_PWR_MGMT_1: u8 = 0x6B;
const REG_PWR_MGMT_2: u8 = 0x6C;
const REG_FIFO_COUNTH: u8 = 0x72;
const REG_FIFO_COUNTL: u8 = 0x73;
const REG_FIFO_R_W: u8 = 0x74;
const REG_WHO_AM_I: u8 = 0x75;

const WHO_AM_I_VALUE: u8 = 0x73;

const AK8963_ADDR: u8 = 0x0C;
const AK8963_REG_WIA: u8 = 0x00;
const AK8963_REG_ST1: u8 = 0x02;
const AK8963_REG_HXL: u8 = 0x03;
const AK8963_REG_ST2: u8 = 0x09;
const AK8963_REG_CNTL1: u8 = 0x0A;
const AK8963_REG_CNTL2: u8 = 0x0B;
const AK8963_REG_ASAX: u8 = 0x10;
const AK8963_REG_ASAY: u8 = 0x11;
const AK8963_REG_ASAZ: u8 = 0x12;
const AK8963_WIA_VALUE: u8 = 0x48;

const ACCEL_SENSITIVITY: [f32; 4] = [16384.0, 8192.0, 4096.0, 2048.0];
const GYRO_SENSITIVITY: [f32; 4] = [131.0, 65.5, 32.8, 16.4];
const MAG_SENSITIVITY_14BIT: f32 = 0.6;
const MAG_SENSITIVITY_16BIT: f32 = 0.15;

/// MPU-9255 minimal driver — 3-axis acceleration and 3-axis angular rate.
///
/// Performs device reset, WHO_AM_I check, and configures defaults at construction.
/// Magnetometer and wake-on-motion are not included in Minimal — both require
/// non-trivial secondary initialization paths.
pub struct Mpu9255Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    accel_fs: u8,
    gyro_fs: u8,
}

impl<I2C: I2c> Mpu9255Minimal<I2C> {
    /// Create a new `Mpu9255Minimal`, reset the device, and configure defaults.
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
            panic!("MPU9255 WHO_AM_I: expected 0x{:02X}, got 0x{:02X}", WHO_AM_I_VALUE, who);
        }
        write_reg(&mut i2c, addr, REG_GYRO_CONFIG, 0x00)?;
        write_reg(&mut i2c, addr, REG_ACCEL_CONFIG, 0x00)?;
        write_reg(&mut i2c, addr, REG_ACCEL_CONFIG2, 0x03)?;
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

/// MPU-9255 full driver — extends [`Mpu9255Minimal`] with complete functionality.
///
/// Provides access to full-scale range configuration, DLPF, sample rate,
/// temperature, magnetometer (AK8963), wake-on-motion, raw data, data-ready
/// polling, sleep/standby, and FIFO management.
pub struct Mpu9255Full<I2C> {
    inner: Mpu9255Minimal<I2C>,
    mag_enabled: bool,
    mag_bits: u8,
    mag_scale_x: f32,
    mag_scale_y: f32,
    mag_scale_z: f32,
}

impl<I2C: I2c> Mpu9255Full<I2C> {
    /// Create a new `Mpu9255Full`, reset the device, and configure defaults.
    ///
    /// Same arguments as [`Mpu9255Minimal::new`].
    pub fn new<D: DelayNs>(i2c: I2C, addr: u8, delay: &mut D) -> Result<Self, I2C::Error> {
        let inner = Mpu9255Minimal::new(i2c, addr, delay)?;
        Ok(Self {
            inner,
            mag_enabled: false,
            mag_bits: 16,
            mag_scale_x: 1.0,
            mag_scale_y: 1.0,
            mag_scale_z: 1.0,
        })
    }

    /// Read 3-axis acceleration. Delegates to the inner [`Mpu9255Minimal`].
    pub fn accel(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        self.inner.accel()
    }

    /// Read 3-axis angular rate. Delegates to the inner [`Mpu9255Minimal`].
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
    /// * `gyro_dlpf`   — Gyro filter setting 0–7 (0=250 Hz, 1=184 Hz, 2=92 Hz, 3=41 Hz, 4=20 Hz, 5=10 Hz, 6=5 Hz, 7=3600 Hz).
    /// * `accel_dlpf`  — Accel filter setting 0–7 (0=218.1 Hz, 1=218.1 Hz, 2=99 Hz, 3=44.8 Hz, 4=21.2 Hz, 5=10.2 Hz, 6=5.05 Hz, 7=420 Hz).
    pub fn configure_dlpf(&mut self, gyro_dlpf: u8, accel_dlpf: u8) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, gyro_dlpf & 0x07)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ACCEL_CONFIG2, accel_dlpf & 0x07)
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
        Ok(raw as f32 / 333.87 + 21.0)
    }

    /// Initialize AK8963 magnetometer via I²C bypass mode.
    ///
    /// # Arguments
    /// * `bits` — Output resolution, 14 or 16.
    /// * `mode` — Operation mode (1=single, 2=8 Hz continuous, 6=100 Hz continuous).
    /// * `delay` — Delay provider for timing.
    pub fn enable_mag<D: DelayNs>(&mut self, bits: u8, mode: u8, delay: &mut D) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_INT_PIN_CFG, 0x22)?;
        delay.delay_ms(10);

        ak8963_write(&mut self.inner.i2c, AK8963_REG_CNTL1, 0x00)?;
        delay.delay_ms(10);

        ak8963_write(&mut self.inner.i2c, AK8963_REG_CNTL1, 0x0F)?;
        delay.delay_ms(10);

        let asax = ak8963_read(&mut self.inner.i2c, AK8963_REG_ASAX)?;
        let asay = ak8963_read(&mut self.inner.i2c, AK8963_REG_ASAY)?;
        let asaz = ak8963_read(&mut self.inner.i2c, AK8963_REG_ASAZ)?;

        self.mag_scale_x = (asax as f32 - 128.0) / 256.0 + 1.0;
        self.mag_scale_y = (asay as f32 - 128.0) / 256.0 + 1.0;
        self.mag_scale_z = (asaz as f32 - 128.0) / 256.0 + 1.0;

        ak8963_write(&mut self.inner.i2c, AK8963_REG_CNTL1, 0x00)?;
        delay.delay_ms(10);

        let mut cntl1_val = 0;
        if bits == 16 {
            cntl1_val |= 0x10;
        }
        cntl1_val |= (mode & 0x0F);
        ak8963_write(&mut self.inner.i2c, AK8963_REG_CNTL1, cntl1_val)?;
        delay.delay_ms(10);

        self.mag_enabled = true;
        self.mag_bits = bits;
        Ok(())
    }

    /// Read 3-axis magnetic field.
    ///
    /// Returns (x, y, z) in µT.
    ///
    /// # Note
    /// Magnetometer axes differ from accel/gyro axes; user must account for this in fusion.
    pub fn mag(&mut self) -> Result<(f32, f32, f32), I2C::Error> {
        if !self.mag_enabled {
            // Return error - but we can't create I2C::Error, so panic per convention
            panic!("Magnetometer not enabled. Call enable_mag() first.");
        }
        let mut buf = [0u8; 7];
        ak8963_read_burst(&mut self.inner.i2c, AK8963_REG_HXL, &mut buf)?;
        let mx = i16::from_le_bytes([buf[0], buf[1]]);
        let my = i16::from_le_bytes([buf[2], buf[3]]);
        let mz = i16::from_le_bytes([buf[4], buf[5]]);
        let _st2 = buf[6]; // Must read ST2 to unlock next measurement

        let sens = if self.mag_bits == 16 { MAG_SENSITIVITY_16BIT } else { MAG_SENSITIVITY_14BIT };
        Ok((mx as f32 * sens * self.mag_scale_x,
            my as f32 * sens * self.mag_scale_y,
            mz as f32 * sens * self.mag_scale_z))
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

    /// Read raw 3-axis magnetometer values.
    ///
    /// Returns (x, y, z) as raw 16-bit signed values.
    pub fn mag_raw(&mut self) -> Result<(i16, i16, i16), I2C::Error> {
        if !self.mag_enabled {
            panic!("Magnetometer not enabled. Call enable_mag() first.");
        }
        let mut buf = [0u8; 6];
        ak8963_read_burst(&mut self.inner.i2c, AK8963_REG_HXL, &mut buf)?;
        Ok((i16::from_le_bytes([buf[0], buf[1]]),
            i16::from_le_bytes([buf[2], buf[3]]),
            i16::from_le_bytes([buf[4], buf[5]])))
    }

    /// Check if new sensor data is available.
    ///
    /// Returns `true` when RAW_DATA_RDY_INT is set in INT_STATUS.
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

    /// Read the number of bytes in the FIFO buffer.
    ///
    /// Returns FIFO byte count (0–512).
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

    /// Configure wake-on-motion and enter accelerometer-only low-power mode.
    ///
    /// Disables the gyroscope, sets the accelerometer DLPF to ~184 Hz, arms the
    /// hardware motion-detection logic, and enables CYCLE mode in PWR_MGMT_1
    /// so the accelerometer samples at the configured wake-up ODR. Re-enable
    /// the gyroscope (via PWR_MGMT_2) and clear CYCLE when motion is detected
    /// if full 6-axis data is needed again.
    ///
    /// # Arguments
    /// * `threshold_mg` — Motion threshold in milligrams (4–1020 mg, quantized to 4 mg steps).
    /// * `odr_hz`       — Wake-up output data rate in Hz (0.24–500 Hz).
    pub fn configure_wake_on_motion<D: DelayNs>(
        &mut self,
        threshold_mg: u16,
        odr_hz: f32,
        _delay: &mut D,
    ) -> Result<(), I2C::Error> {
        let mut threshold_lsb = ((threshold_mg as u32 + 2) / 4) as i32;
        if threshold_lsb < 1 { threshold_lsb = 1; }
        if threshold_lsb > 255 { threshold_lsb = 255; }

        const LPOSC_TABLE: [f32; 16] = [
            0.24, 0.49, 0.98, 1.95, 3.91, 7.81, 15.63, 31.25,
            62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0,
        ];
        let mut best_sel: i32 = 0;
        let mut best_diff = (odr_hz - LPOSC_TABLE[0]).abs();
        for sel in 1..LPOSC_TABLE.len() {
            let diff = (odr_hz - LPOSC_TABLE[sel]).abs();
            if diff < best_diff {
                best_diff = diff;
                best_sel = sel as i32;
            }
        }

        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PWR_MGMT_1, 0x01)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PWR_MGMT_2, 0x07)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ACCEL_CONFIG2, 0x01)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_INT_ENABLE, 0x40)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_MOT_DETECT_CTRL, 0xC0)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_WOM_THR, threshold_lsb as u8)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_LP_ACCEL_ODR, best_sel as u8)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PWR_MGMT_1, 0x21)
    }

    /// Check if a wake-on-motion interrupt has fired.
    ///
    /// Returns `true` when WOM_INT (bit 6) is set in INT_STATUS. Reading
    /// INT_STATUS clears the interrupt.
    pub fn motion_detected(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_reg8(&mut self.inner.i2c, self.inner.addr, REG_INT_STATUS)? & 0x40 != 0)
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

fn ak8963_write<I2C: I2c>(i2c: &mut I2C, reg: u8, value: u8) -> Result<(), I2C::Error> {
    i2c.write(AK8963_ADDR, &[reg, value])
}

fn ak8963_read<I2C: I2c>(i2c: &mut I2C, reg: u8) -> Result<u8, I2C::Error> {
    let mut buf = [0u8; 1];
    i2c.write_read(AK8963_ADDR, &[reg], &mut buf)?;
    Ok(buf[0])
}

fn ak8963_read_burst<I2C: I2c>(i2c: &mut I2C, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
    i2c.write_read(AK8963_ADDR, &[reg], buf)
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x68;

    fn s16(value: i16) -> [u8; 2] {
        value.to_be_bytes()
    }

    #[test]
    fn full_api() {
        let init_transactions = vec![
            I2cTransaction::write(ADDR, vec![REG_PWR_MGMT_1, 0x80]),
            I2cTransaction::write(ADDR, vec![REG_PWR_MGMT_1, 0x01]),
            I2cTransaction::write_read(ADDR, vec![REG_WHO_AM_I], vec![WHO_AM_I_VALUE]),
            I2cTransaction::write(ADDR, vec![REG_GYRO_CONFIG, 0x00]),
            I2cTransaction::write(ADDR, vec![REG_ACCEL_CONFIG, 0x00]),
            I2cTransaction::write(ADDR, vec![REG_ACCEL_CONFIG2, 0x03]),
            I2cTransaction::write(ADDR, vec![REG_CONFIG, 0x03]),
            I2cTransaction::write(ADDR, vec![REG_SMPLRT_DIV, 0x04]),
        ];
        let mut transactions = init_transactions.clone();
        transactions.extend(vec![
            I2cTransaction::write_read(ADDR, vec![REG_ACCEL_XOUT_H],
                [s16(16384), s16(-8192), s16(4096)].concat()),
            I2cTransaction::write_read(ADDR, vec![REG_GYRO_XOUT_H],
                [s16(131), s16(-131), s16(262)].concat()),
            I2cTransaction::write(ADDR, vec![REG_GYRO_CONFIG, 2 << 3]),
            I2cTransaction::write_read(ADDR, vec![REG_GYRO_XOUT_H],
                [s16(328), s16(0), s16(0)].concat()),
            I2cTransaction::write(ADDR, vec![REG_ACCEL_CONFIG, 1 << 3]),
            I2cTransaction::write_read(ADDR, vec![REG_ACCEL_XOUT_H],
                [s16(8192), s16(0), s16(0)].concat()),
            I2cTransaction::write(ADDR, vec![REG_CONFIG, 5]),
            I2cTransaction::write(ADDR, vec![REG_ACCEL_CONFIG2, 5]),
            I2cTransaction::write(ADDR, vec![REG_SMPLRT_DIV, 9]),
            I2cTransaction::write_read(ADDR, vec![REG_TEMP_OUT_H], s16(340).to_vec()),
            I2cTransaction::write_read(ADDR, vec![REG_ACCEL_XOUT_H],
                [s16(100), s16(-200), s16(300)].concat()),
            I2cTransaction::write_read(ADDR, vec![REG_GYRO_XOUT_H],
                [s16(-50), s16(60), s16(-70)].concat()),
            I2cTransaction::write_read(ADDR, vec![REG_INT_STATUS], vec![0x01]),
            I2cTransaction::write_read(ADDR, vec![REG_INT_STATUS], vec![0x00]),
            I2cTransaction::write_read(ADDR, vec![REG_PWR_MGMT_1], vec![0x01]),
            I2cTransaction::write(ADDR, vec![REG_PWR_MGMT_1, 0x41]),
            I2cTransaction::write_read(ADDR, vec![REG_PWR_MGMT_1], vec![0x41]),
            I2cTransaction::write(ADDR, vec![REG_PWR_MGMT_1, 0x01]),
            I2cTransaction::write_read(ADDR, vec![REG_FIFO_COUNTH], vec![0x03, 0x45]),
            I2cTransaction::write_read(ADDR, vec![REG_FIFO_COUNTH], vec![0x00, 0x02]),
            I2cTransaction::write_read(ADDR, vec![REG_FIFO_R_W], vec![0xAA, 0xBB]),
            I2cTransaction::write_read(ADDR, vec![REG_FIFO_COUNTH], vec![0x00, 0x00]),
            I2cTransaction::write(ADDR, vec![REG_FIFO_EN, (1 << 3) | (1 << 4)]),
            I2cTransaction::write_read(ADDR, vec![REG_USER_CTRL], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_USER_CTRL, 0x40]),
            I2cTransaction::write_read(ADDR, vec![REG_USER_CTRL], vec![0x40]),
            I2cTransaction::write(ADDR, vec![REG_USER_CTRL, 0x44]),
            // configure_wake_on_motion(64 mg, 31.25 Hz) -> 8 writes
            I2cTransaction::write(ADDR, vec![REG_PWR_MGMT_1, 0x01]),
            I2cTransaction::write(ADDR, vec![REG_PWR_MGMT_2, 0x07]),
            I2cTransaction::write(ADDR, vec![REG_ACCEL_CONFIG2, 0x01]),
            I2cTransaction::write(ADDR, vec![REG_INT_ENABLE, 0x40]),
            I2cTransaction::write(ADDR, vec![REG_MOT_DETECT_CTRL, 0xC0]),
            I2cTransaction::write(ADDR, vec![REG_WOM_THR, 16]),
            I2cTransaction::write(ADDR, vec![REG_LP_ACCEL_ODR, 0x07]),
            I2cTransaction::write(ADDR, vec![REG_PWR_MGMT_1, 0x21]),
            // motion_detected true (INT_STATUS=0x40)
            I2cTransaction::write_read(ADDR, vec![REG_INT_STATUS], vec![0x40]),
            // motion_detected false (INT_STATUS=0x00)
            I2cTransaction::write_read(ADDR, vec![REG_INT_STATUS], vec![0x00]),
        ]);

        let i2c = I2cMock::new(&transactions);
        let mut delay = NoopDelay::new();
        let mut sensor = Mpu9255Full::new(i2c, ADDR, &mut delay).expect("init");

        let (ax, ay, az) = sensor.accel().unwrap();
        assert!((ax - 9.80665).abs() < 1e-3);
        assert!((ay - (-4.903325)).abs() < 1e-3);
        assert!((az - 2.4516625).abs() < 1e-3);

        let deg2rad = core::f32::consts::PI / 180.0;
        let (gx, gy, gz) = sensor.gyro().unwrap();
        assert!((gx - 1.0 * deg2rad).abs() < 1e-4);
        assert!((gy - (-1.0) * deg2rad).abs() < 1e-4);
        assert!((gz - 2.0 * deg2rad).abs() < 1e-4);

        sensor.configure_gyro(2).unwrap();
        let (gx2, _, _) = sensor.gyro().unwrap();
        assert!((gx2 - 10.0 * deg2rad).abs() < 1e-3);

        sensor.configure_accel(1).unwrap();
        let (ax2, _, _) = sensor.accel().unwrap();
        assert!((ax2 - 9.80665).abs() < 1e-3);

        sensor.configure_dlpf(5, 5).unwrap();
        sensor.configure_sample_rate(9).unwrap();

        let temp = sensor.temperature().unwrap();
        assert!((temp - (340.0 / 333.87 + 21.0)).abs() < 1e-3);

        assert_eq!(sensor.accel_raw().unwrap(), (100, -200, 300));
        assert_eq!(sensor.gyro_raw().unwrap(), (-50, 60, -70));

        assert!(sensor.data_ready().unwrap());
        assert!(!sensor.data_ready().unwrap());

        sensor.set_sleep(true).unwrap();
        sensor.set_sleep(false).unwrap();

        assert_eq!(sensor.fifo_count().unwrap(), ((0x03u16 & 0x1F) << 8) | 0x45);

        let mut fifo_buf = [0u8; 8];
        let n = sensor.read_fifo(&mut fifo_buf).unwrap();
        assert_eq!(n, 2);
        assert_eq!(&fifo_buf[..2], &[0xAA, 0xBB]);

        let n2 = sensor.read_fifo(&mut fifo_buf).unwrap();
        assert_eq!(n2, 0);

        sensor.enable_fifo(true, true, false).unwrap();
        sensor.reset_fifo().unwrap();

        sensor.configure_wake_on_motion(64, 31.25, &mut delay).unwrap();
        assert!(sensor.motion_detected().unwrap());
        assert!(!sensor.motion_detected().unwrap());

        sensor.inner.i2c.done();
    }

    #[test]
    #[should_panic(expected = "WHO_AM_I")]
    fn who_am_i_mismatch_panics() {
        let transactions = vec![
            I2cTransaction::write(ADDR, vec![REG_PWR_MGMT_1, 0x80]),
            I2cTransaction::write(ADDR, vec![REG_PWR_MGMT_1, 0x01]),
            I2cTransaction::write_read(ADDR, vec![REG_WHO_AM_I], vec![0x00]),
        ];
        let i2c = I2cMock::new(&transactions);
        let mut delay = NoopDelay::new();
        let _ = Mpu9255Minimal::new(i2c, ADDR, &mut delay);
    }
}