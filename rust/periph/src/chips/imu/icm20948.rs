//! ICM-20948 — 9-axis MotionTracking device (TDK InvenSense): gyroscope, accelerometer and
//! AK09916 magnetometer.
//!
//! Communicates over I²C (up to 400 kHz) at `0x68` (AD0 = GND) or `0x69` (AD0 = VDDIO). VDDIO is
//! 1.71–1.95 V only. SPI and the DMP are out of scope.
//!
//! The registers are organised in four *user banks* selected through `REG_BANK_SEL` (`0x7F`);
//! the driver caches the selected bank and always returns to bank 0 after a bank 1–3 access.
//! The magnetometer sits on the chip's auxiliary I²C bus and is read through the chip's own I²C
//! master, so a single I²C connection is enough.
//!
//! ## Default configuration (Minimal)
//!
//! - Gyroscope ±250 dps, low-pass filter 51.2 Hz (`GYRO_DLPFCFG` = 3)
//! - Accelerometer ±2 g, low-pass filter 50.4 Hz (`ACCEL_DLPFCFG` = 3)
//! - Both output data rates 102.3 Hz (divider 10)
//! - Clock auto (`CLKSEL` = 1), all six axes and the temperature sensor on
//! - FIFO, DMP, I²C master and all interrupts off
//!
//! Rust drivers expose [`Icm20948Full::poll_interrupt`] only — the caller wires the INT1 pin
//! into an ISR or polling loop of their own and calls it when the pin asserts.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register;

const REG_WHO_AM_I: u8 = 0x00;
const REG_USER_CTRL: u8 = 0x03;
const REG_LP_CONFIG: u8 = 0x05;
const REG_PWR_MGMT_1: u8 = 0x06;
const REG_PWR_MGMT_2: u8 = 0x07;
const REG_INT_PIN_CFG: u8 = 0x0F;
const REG_INT_ENABLE: u8 = 0x10;
const REG_INT_ENABLE_1: u8 = 0x11;
const REG_INT_ENABLE_2: u8 = 0x12;
const REG_I2C_MST_STATUS: u8 = 0x17;
const REG_INT_STATUS: u8 = 0x19;
const REG_INT_STATUS_1: u8 = 0x1A;
const REG_ACCEL_XOUT_H: u8 = 0x2D;
const REG_GYRO_XOUT_H: u8 = 0x33;
const REG_TEMP_OUT_H: u8 = 0x39;
const REG_EXT_SLV_SENS_DATA_00: u8 = 0x3B;
const REG_FIFO_EN_2: u8 = 0x67;
const REG_FIFO_RST: u8 = 0x68;
const REG_FIFO_MODE: u8 = 0x69;
const REG_FIFO_COUNTH: u8 = 0x70;
const REG_FIFO_R_W: u8 = 0x72;
const REG_BANK_SEL: u8 = 0x7F;

// Bank 1
const REG_XA_OFFS_H: u8 = 0x14;
const REG_TIMEBASE_CORRECTION_PLL: u8 = 0x28;

// Bank 2
const REG_GYRO_SMPLRT_DIV: u8 = 0x00;
const REG_GYRO_CONFIG_1: u8 = 0x01;
const REG_GYRO_CONFIG_2: u8 = 0x02;
const REG_XG_OFFS_USRH: u8 = 0x03;
const REG_ACCEL_SMPLRT_DIV_1: u8 = 0x10;
const REG_ACCEL_SMPLRT_DIV_2: u8 = 0x11;
const REG_ACCEL_INTEL_CTRL: u8 = 0x12;
const REG_ACCEL_WOM_THR: u8 = 0x13;
const REG_ACCEL_CONFIG: u8 = 0x14;
const REG_ACCEL_CONFIG_2: u8 = 0x15;

// Bank 3
const REG_I2C_MST_CTRL: u8 = 0x01;
const REG_I2C_SLV0_ADDR: u8 = 0x03;
const REG_I2C_SLV0_REG: u8 = 0x04;
const REG_I2C_SLV0_CTRL: u8 = 0x05;
const REG_I2C_SLV4_ADDR: u8 = 0x13;
const REG_I2C_SLV4_REG: u8 = 0x14;
const REG_I2C_SLV4_CTRL: u8 = 0x15;
const REG_I2C_SLV4_DO: u8 = 0x16;
const REG_I2C_SLV4_DI: u8 = 0x17;

const WHO_AM_I_VALUE: u8 = 0xEA;

const AK_ADDR: u8 = 0x0C;
const AK_WIA2: u8 = 0x01;
const AK_ST1: u8 = 0x10;
const AK_CNTL2: u8 = 0x31;
const AK_CNTL3: u8 = 0x32;
const AK_WIA2_VALUE: u8 = 0x09;
const MAG_SENSITIVITY: f32 = 0.15;

const ACCEL_SENSITIVITY: [f32; 4] = [16384.0, 8192.0, 4096.0, 2048.0];
const GYRO_SENSITIVITY: [f32; 4] = [131.0, 65.5, 32.8, 16.4];
const G: f32 = 9.80665;
const DEG_TO_RAD: f32 = core::f32::consts::PI / 180.0;

/// Interrupt source: new raw data (`INT_STATUS_1.RAW_DATA_0_RDY_INT`).
pub const SOURCE_DATA_READY: u8 = 0x01;
/// Interrupt source: wake on motion (`INT_STATUS.WOM_INT`).
pub const SOURCE_WAKE_ON_MOTION: u8 = 0x02;
/// Interrupt source: I²C master transfer done (`INT_STATUS.I2C_MST_INT`).
pub const SOURCE_I2C_MASTER: u8 = 0x04;
/// Interrupt source: PLL running (`INT_STATUS.PLL_RDY_INT`).
pub const SOURCE_PLL_READY: u8 = 0x08;
/// Interrupt source: FIFO overflow (`INT_STATUS_2` ≠ 0).
pub const SOURCE_FIFO_OVERFLOW: u8 = 0x10;

/// Errors returned by the ICM-20948 drivers.
#[derive(Debug, PartialEq)]
pub enum Icm20948Error<E> {
    /// The underlying I²C bus failed.
    I2c(E),
    /// `WHO_AM_I` did not read `0xEA` (wrong chip, address or wiring); carries the value read.
    BadWhoAmI(u8),
    /// The magnetometer `WIA2` did not read `0x09`; carries the value read.
    BadMagId(u8),
    /// An auxiliary-bus transfer was not acknowledged.
    AuxNack,
    /// An auxiliary-bus transfer did not complete in time.
    AuxTimeout,
    /// [`Icm20948Full::enable_mag`] has not been called.
    MagNotEnabled,
    /// The magnetic sensor overflowed (`HOFL`); the sample is invalid.
    MagOverflow,
    /// The interrupt source is not one of the `SOURCE_*` constants.
    InvalidSource,
}

type Res<T, I2C> = Result<T, Icm20948Error<<I2C as embedded_hal::i2c::ErrorType>::Error>>;

/// ICM-20948 minimal driver — 3-axis acceleration and 3-axis angular rate.
///
/// Performs the `WHO_AM_I` check, device reset and default configuration at construction.
pub struct Icm20948Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    bank: u8,
    accel_fs: u8,
    gyro_fs: u8,
    accel_dlpf: u8,
    gyro_dlpf: u8,
    accel_filter: bool,
    gyro_filter: bool,
    lp_en: bool,
    lp_suspended: bool,
}

impl<I2C: I2c> Icm20948Minimal<I2C> {
    /// Create a new `Icm20948Minimal`, verify `WHO_AM_I`, reset the device and configure defaults.
    ///
    /// # Arguments
    /// * `i2c`   — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr`  — 7-bit device address (`0x68` with AD0 = GND, `0x69` with AD0 = VDDIO).
    /// * `delay` — Delay provider for init timing (100 ms reset, 35 ms gyroscope start-up).
    ///
    /// # Errors
    /// [`Icm20948Error::BadWhoAmI`] if `WHO_AM_I` is not `0xEA`.
    pub fn new<D: DelayNs>(i2c: I2C, addr: u8, delay: &mut D) -> Res<Self, I2C> {
        let mut s = Self {
            i2c, addr, bank: 0, accel_fs: 0, gyro_fs: 0, accel_dlpf: 3, gyro_dlpf: 3,
            accel_filter: true, gyro_filter: true, lp_en: false, lp_suspended: false,
        };
        s.select_bank(0, true)?;
        let who = s.rd1(0, REG_WHO_AM_I)?;
        if who != WHO_AM_I_VALUE {
            return Err(Icm20948Error::BadWhoAmI(who));
        }
        s.wr1(0, REG_PWR_MGMT_1, 0x80)?; // DEVICE_RESET
        delay.delay_ms(100);
        s.bank = 0; // reset returns to bank 0
        s.wr1(0, REG_PWR_MGMT_1, 0x01)?; // SLEEP = 0, CLKSEL = 1
        s.wr1(0, REG_PWR_MGMT_2, 0x00)?; // all axes on
        s.write_gyro_config()?;
        s.write_accel_config()?;
        s.wr1(2, REG_GYRO_SMPLRT_DIV, 0x0A)?;
        s.wr1(2, REG_ACCEL_SMPLRT_DIV_1, 0x00)?;
        s.wr1(2, REG_ACCEL_SMPLRT_DIV_2, 0x0A)?;
        delay.delay_ms(35);
        Ok(s)
    }

    fn select_bank(&mut self, bank: u8, force: bool) -> Res<(), I2C> {
        if force || bank != self.bank {
            register::write_register(&mut self.i2c, self.addr, REG_BANK_SEL.into(), 1, &[bank << 4])
                .map_err(Icm20948Error::I2c)?;
            self.bank = bank;
        }
        Ok(())
    }

    fn rd(&mut self, bank: u8, reg: u8, buf: &mut [u8]) -> Res<(), I2C> {
        self.select_bank(bank, false)?;
        register::read_register(&mut self.i2c, self.addr, reg.into(), 1, buf).map_err(Icm20948Error::I2c)?;
        if bank != 0 {
            self.select_bank(0, false)?;
        }
        Ok(())
    }

    fn wr(&mut self, bank: u8, reg: u8, data: &[u8]) -> Res<(), I2C> {
        self.select_bank(bank, false)?;
        register::write_register(&mut self.i2c, self.addr, reg.into(), 1, data).map_err(Icm20948Error::I2c)?;
        if bank != 0 {
            self.select_bank(0, false)?;
        }
        Ok(())
    }

    fn rd1(&mut self, bank: u8, reg: u8) -> Res<u8, I2C> {
        let mut b = [0u8; 1];
        self.rd(bank, reg, &mut b)?;
        Ok(b[0])
    }

    fn wr1(&mut self, bank: u8, reg: u8, value: u8) -> Res<(), I2C> {
        self.wr(bank, reg, &[value])
    }

    fn write_gyro_config(&mut self) -> Res<(), I2C> {
        let v = (self.gyro_dlpf << 3) | (self.gyro_fs << 1) | u8::from(self.gyro_filter);
        self.wr1(2, REG_GYRO_CONFIG_1, v)
    }

    fn write_accel_config(&mut self) -> Res<(), I2C> {
        let v = (self.accel_dlpf << 3) | (self.accel_fs << 1) | u8::from(self.accel_filter);
        self.wr1(2, REG_ACCEL_CONFIG, v)
    }

    /// Read 3-axis linear acceleration.
    ///
    /// Returns (x, y, z) in m/s².
    pub fn accel(&mut self) -> Res<(f32, f32, f32), I2C> {
        let mut buf = [0u8; 6];
        self.rd(0, REG_ACCEL_XOUT_H, &mut buf)?;
        let sens = ACCEL_SENSITIVITY[self.accel_fs as usize];
        Ok((i16::from_be_bytes([buf[0], buf[1]]) as f32 / sens * G,
            i16::from_be_bytes([buf[2], buf[3]]) as f32 / sens * G,
            i16::from_be_bytes([buf[4], buf[5]]) as f32 / sens * G))
    }

    /// Read 3-axis angular rate.
    ///
    /// Returns (x, y, z) in rad/s.
    pub fn gyro(&mut self) -> Res<(f32, f32, f32), I2C> {
        let mut buf = [0u8; 6];
        self.rd(0, REG_GYRO_XOUT_H, &mut buf)?;
        let sens = GYRO_SENSITIVITY[self.gyro_fs as usize];
        Ok((i16::from_be_bytes([buf[0], buf[1]]) as f32 / sens * DEG_TO_RAD,
            i16::from_be_bytes([buf[2], buf[3]]) as f32 / sens * DEG_TO_RAD,
            i16::from_be_bytes([buf[4], buf[5]]) as f32 / sens * DEG_TO_RAD))
    }
}

/// ICM-20948 full driver — extends [`Icm20948Minimal`] with complete functionality.
///
/// Adds raw and burst reads, temperature, full-scale / filter / sample-rate configuration, sensor
/// power control, low-power modes, gyro and accelerometer offsets, the AK09916 magnetometer (read
/// through the chip's own I²C master), single-byte auxiliary-bus access, the 4 KiB FIFO,
/// wake-on-motion, interrupt status, self-test and the PLL time-base correction.
pub struct Icm20948Full<I2C> {
    inner: Icm20948Minimal<I2C>,
    mag_enabled: bool,
}

impl<I2C: I2c> Icm20948Full<I2C> {
    /// Create a new `Icm20948Full`, reset the device and configure defaults.
    ///
    /// Same arguments as [`Icm20948Minimal::new`].
    pub fn new<D: DelayNs>(i2c: I2C, addr: u8, delay: &mut D) -> Res<Self, I2C> {
        Ok(Self { inner: Icm20948Minimal::new(i2c, addr, delay)?, mag_enabled: false })
    }

    /// Read 3-axis acceleration in m/s². Delegates to the inner [`Icm20948Minimal`].
    pub fn accel(&mut self) -> Res<(f32, f32, f32), I2C> {
        self.inner.accel()
    }

    /// Read 3-axis angular rate in rad/s. Delegates to the inner [`Icm20948Minimal`].
    pub fn gyro(&mut self) -> Res<(f32, f32, f32), I2C> {
        self.inner.gyro()
    }

    fn lp_suspend(&mut self) -> Res<(), I2C> {
        if self.inner.lp_en && !self.inner.lp_suspended {
            let v = self.inner.rd1(0, REG_PWR_MGMT_1)?;
            self.inner.wr1(0, REG_PWR_MGMT_1, v & !0x20)?;
            self.inner.lp_suspended = true;
        }
        Ok(())
    }

    fn lp_resume(&mut self) -> Res<(), I2C> {
        if self.inner.lp_suspended {
            let v = self.inner.rd1(0, REG_PWR_MGMT_1)?;
            self.inner.wr1(0, REG_PWR_MGMT_1, v | 0x20)?;
            self.inner.lp_suspended = false;
        }
        Ok(())
    }

    /// Read raw 3-axis accelerometer values (signed 16-bit counts).
    pub fn accel_raw(&mut self) -> Res<(i16, i16, i16), I2C> {
        let mut buf = [0u8; 6];
        self.inner.rd(0, REG_ACCEL_XOUT_H, &mut buf)?;
        Ok((i16::from_be_bytes([buf[0], buf[1]]), i16::from_be_bytes([buf[2], buf[3]]),
            i16::from_be_bytes([buf[4], buf[5]])))
    }

    /// Read raw 3-axis gyroscope values (signed 16-bit counts).
    pub fn gyro_raw(&mut self) -> Res<(i16, i16, i16), I2C> {
        let mut buf = [0u8; 6];
        self.inner.rd(0, REG_GYRO_XOUT_H, &mut buf)?;
        Ok((i16::from_be_bytes([buf[0], buf[1]]), i16::from_be_bytes([buf[2], buf[3]]),
            i16::from_be_bytes([buf[4], buf[5]])))
    }

    /// Burst-read acceleration, angular rate and temperature so all three belong to one sample.
    ///
    /// Returns `((ax, ay, az), (gx, gy, gz), temp_c)` in m/s², rad/s and °C.
    pub fn read_all(&mut self) -> Res<((f32, f32, f32), (f32, f32, f32), f32), I2C> {
        let mut b = [0u8; 14];
        self.inner.rd(0, REG_ACCEL_XOUT_H, &mut b)?;
        let a = ACCEL_SENSITIVITY[self.inner.accel_fs as usize];
        let g = GYRO_SENSITIVITY[self.inner.gyro_fs as usize];
        let v = |i: usize| i16::from_be_bytes([b[i], b[i + 1]]) as f32;
        Ok(((v(0) / a * G, v(2) / a * G, v(4) / a * G),
            (v(6) / g * DEG_TO_RAD, v(8) / g * DEG_TO_RAD, v(10) / g * DEG_TO_RAD),
            v(12) / 333.87 + 21.0))
    }

    /// Read the die temperature in °C.
    pub fn temperature(&mut self) -> Res<f32, I2C> {
        let mut b = [0u8; 2];
        self.inner.rd(0, REG_TEMP_OUT_H, &mut b)?;
        Ok(i16::from_be_bytes(b) as f32 / 333.87 + 21.0)
    }

    /// Set the gyroscope full-scale range: 0 = ±250, 1 = ±500, 2 = ±1000, 3 = ±2000 dps.
    pub fn configure_gyro(&mut self, full_scale: u8) -> Res<(), I2C> {
        self.lp_suspend()?;
        self.inner.gyro_fs = full_scale & 0x03;
        self.inner.write_gyro_config()?;
        self.lp_resume()
    }

    /// Set the accelerometer full-scale range: 0 = ±2, 1 = ±4, 2 = ±8, 3 = ±16 g.
    pub fn configure_accel(&mut self, full_scale: u8) -> Res<(), I2C> {
        self.lp_suspend()?;
        self.inner.accel_fs = full_scale & 0x03;
        self.inner.write_accel_config()?;
        self.lp_resume()
    }

    /// Set the digital low-pass filters.
    ///
    /// # Arguments
    /// * `gyro_dlpf` / `accel_dlpf` — `*_DLPFCFG` 0–7 (3 = 51.2 Hz gyro / 50.4 Hz accel).
    /// * `gyro_filter` / `accel_filter` — `false` bypasses the filter (higher output rate).
    pub fn configure_filters(&mut self, gyro_dlpf: u8, accel_dlpf: u8, gyro_filter: bool,
                             accel_filter: bool) -> Res<(), I2C> {
        self.lp_suspend()?;
        self.inner.gyro_dlpf = gyro_dlpf & 0x07;
        self.inner.accel_dlpf = accel_dlpf & 0x07;
        self.inner.gyro_filter = gyro_filter;
        self.inner.accel_filter = accel_filter;
        self.inner.write_gyro_config()?;
        self.inner.write_accel_config()?;
        self.lp_resume()
    }

    /// Set the sample-rate dividers; ODR = 1125 Hz / (1 + div).
    ///
    /// # Arguments
    /// * `gyro_div`  — `GYRO_SMPLRT_DIV` 0–255.
    /// * `accel_div` — `ACCEL_SMPLRT_DIV` 0–4095.
    pub fn configure_sample_rate(&mut self, gyro_div: u8, accel_div: u16) -> Res<(), I2C> {
        self.lp_suspend()?;
        self.inner.wr1(2, REG_GYRO_SMPLRT_DIV, gyro_div)?;
        self.inner.wr1(2, REG_ACCEL_SMPLRT_DIV_1, ((accel_div >> 8) & 0x0F) as u8)?;
        self.inner.wr1(2, REG_ACCEL_SMPLRT_DIV_2, (accel_div & 0xFF) as u8)?;
        self.lp_resume()
    }

    /// Switch the accelerometer and gyroscope on or off (`PWR_MGMT_2`).
    ///
    /// Turning a sensor on again needs its start-up time (accel 20 ms, gyro 35 ms).
    pub fn set_sensors(&mut self, accel: bool, gyro: bool) -> Res<(), I2C> {
        let v = (if accel { 0 } else { 0x38 }) | (if gyro { 0 } else { 0x07 });
        self.inner.wr1(0, REG_PWR_MGMT_2, v)
    }

    /// Set or clear the `SLEEP` bit in `PWR_MGMT_1`.
    pub fn set_sleep(&mut self, sleep: bool) -> Res<(), I2C> {
        let v = self.inner.rd1(0, REG_PWR_MGMT_1)?;
        self.inner.wr1(0, REG_PWR_MGMT_1, if sleep { v | 0x40 } else { v & !0x40 })
    }

    /// Configure duty-cycled low-power operation; `LP_EN` is set last because it blocks most
    /// register writes.
    ///
    /// # Arguments
    /// * `accel` / `gyro` — duty-cycle the accelerometer / gyroscope.
    /// * `accel_avg` — `DEC3_CFG` averaging code 0–3.
    /// * `gyro_avg`  — `GYRO_AVGCFG` averaging code 0–7.
    pub fn set_low_power(&mut self, accel: bool, gyro: bool, accel_avg: u8, gyro_avg: u8) -> Res<(), I2C> {
        self.inner.lp_suspended = false;
        let v = self.inner.rd1(0, REG_PWR_MGMT_1)? & !0x20;
        self.inner.wr1(0, REG_PWR_MGMT_1, v)?;
        self.inner.wr1(2, REG_ACCEL_CONFIG_2, accel_avg & 0x03)?;
        self.inner.wr1(2, REG_GYRO_CONFIG_2, gyro_avg & 0x07)?;
        self.inner.wr1(0, REG_LP_CONFIG, 0x40 | (if accel { 0x20 } else { 0 }) | (if gyro { 0x10 } else { 0 }))?;
        self.inner.lp_en = accel || gyro;
        if self.inner.lp_en {
            self.inner.wr1(0, REG_PWR_MGMT_1, v | 0x20)?;
        }
        Ok(())
    }

    /// Write the gyroscope offset-cancellation registers (rad/s; 0.0305 dps/LSB, signed 16-bit).
    pub fn set_gyro_offset(&mut self, x: f32, y: f32, z: f32) -> Res<(), I2C> {
        let mut data = [0u8; 6];
        for (i, v) in [x, y, z].iter().enumerate() {
            let code = libm::roundf(v / DEG_TO_RAD / 0.0305).clamp(-32768.0, 32767.0) as i16;
            data[2 * i..2 * i + 2].copy_from_slice(&code.to_be_bytes());
        }
        self.lp_suspend()?;
        self.inner.wr(2, REG_XG_OFFS_USRH, &data)?;
        self.lp_resume()
    }

    /// Read the 15-bit accelerometer offset codes (0.98 mg/LSB).
    ///
    /// The registers hold a per-part factory trim: read, adjust, write back.
    pub fn get_accel_offset(&mut self) -> Res<(i16, i16, i16), I2C> {
        let mut raw = [0u8; 8];
        self.inner.rd(1, REG_XA_OFFS_H, &mut raw)?;
        let code = |i: usize| -> i16 {
            let c = (i32::from(raw[i]) << 7) | (i32::from(raw[i + 1]) >> 1);
            (if c & 0x4000 != 0 { c - 0x8000 } else { c }) as i16
        };
        Ok((code(0), code(3), code(6)))
    }

    /// Write the 15-bit accelerometer offset codes.
    pub fn set_accel_offset(&mut self, x: i16, y: i16, z: i16) -> Res<(), I2C> {
        self.lp_suspend()?;
        for (reg, v) in [(0x14u8, x), (0x17, y), (0x1A, z)] {
            let code = (v as u16) & 0x7FFF;
            self.inner.wr(1, reg, &[((code >> 7) & 0xFF) as u8, ((code << 1) & 0xFE) as u8])?;
        }
        self.lp_resume()
    }

    fn slv4_wait<D: DelayNs>(&mut self, delay: &mut D) -> Res<(), I2C> {
        for _ in 0..100 {
            let status = self.inner.rd1(0, REG_I2C_MST_STATUS)?;
            if status & 0x10 != 0 {
                return Err(Icm20948Error::AuxNack);
            }
            if status & 0x40 != 0 {
                return Ok(());
            }
            delay.delay_ms(1);
        }
        Err(Icm20948Error::AuxTimeout)
    }

    /// Read one byte from an auxiliary-bus device through I²C-master slave 4.
    ///
    /// # Errors
    /// [`Icm20948Error::AuxNack`] or [`Icm20948Error::AuxTimeout`] if the transfer fails.
    pub fn aux_read_byte<D: DelayNs>(&mut self, address: u8, register: u8, delay: &mut D) -> Res<u8, I2C> {
        self.inner.select_bank(3, false)?;
        for (reg, v) in [(REG_I2C_SLV4_ADDR, 0x80 | (address & 0x7F)), (REG_I2C_SLV4_REG, register),
                         (REG_I2C_SLV4_CTRL, 0x80)] {
            register::write_register(&mut self.inner.i2c, self.inner.addr, reg.into(), 1, &[v])
                .map_err(Icm20948Error::I2c)?;
        }
        self.inner.select_bank(0, false)?;
        self.slv4_wait(delay)?;
        self.inner.rd1(3, REG_I2C_SLV4_DI)
    }

    /// Write one byte to an auxiliary-bus device through I²C-master slave 4.
    ///
    /// # Errors
    /// [`Icm20948Error::AuxNack`] or [`Icm20948Error::AuxTimeout`] if the transfer fails.
    pub fn aux_write_byte<D: DelayNs>(&mut self, address: u8, register: u8, value: u8,
                                      delay: &mut D) -> Res<(), I2C> {
        self.inner.select_bank(3, false)?;
        for (reg, v) in [(REG_I2C_SLV4_ADDR, address & 0x7F), (REG_I2C_SLV4_REG, register),
                         (REG_I2C_SLV4_DO, value), (REG_I2C_SLV4_CTRL, 0x80)] {
            register::write_register(&mut self.inner.i2c, self.inner.addr, reg.into(), 1, &[v])
                .map_err(Icm20948Error::I2c)?;
        }
        self.inner.select_bank(0, false)?;
        self.slv4_wait(delay)
    }

    /// Enable the AK09916 magnetometer via the I²C master.
    ///
    /// Resets the magnetometer, verifies `WIA2` == `0x09`, selects continuous measurement 4
    /// (100 Hz) and configures slave 0 to copy the 9-byte `ST1..ST2` block to
    /// `EXT_SLV_SENS_DATA_00..08` continuously.
    ///
    /// # Errors
    /// [`Icm20948Error::BadMagId`] if `WIA2` mismatches; aux errors if a transfer fails.
    pub fn enable_mag<D: DelayNs>(&mut self, delay: &mut D) -> Res<(), I2C> {
        self.lp_suspend()?;
        let ctrl = self.inner.rd1(0, REG_USER_CTRL)?;
        self.inner.wr1(0, REG_USER_CTRL, ctrl | 0x20)?; // I2C_MST_EN
        self.inner.wr1(3, REG_I2C_MST_CTRL, 0x07)?; // 345.6 kHz
        self.aux_write_byte(AK_ADDR, AK_CNTL3, 0x01, delay)?; // soft reset
        delay.delay_ms(2);
        let wia = self.aux_read_byte(AK_ADDR, AK_WIA2, delay)?;
        if wia != AK_WIA2_VALUE {
            return Err(Icm20948Error::BadMagId(wia));
        }
        self.aux_write_byte(AK_ADDR, AK_CNTL2, 0x08, delay)?; // continuous 4
        self.inner.select_bank(3, false)?;
        for (reg, v) in [(REG_I2C_SLV0_ADDR, 0x80 | AK_ADDR), (REG_I2C_SLV0_REG, AK_ST1),
                         (REG_I2C_SLV0_CTRL, 0x89)] {
            register::write_register(&mut self.inner.i2c, self.inner.addr, reg.into(), 1, &[v])
                .map_err(Icm20948Error::I2c)?;
        }
        self.inner.select_bank(0, false)?;
        self.mag_enabled = true;
        self.lp_resume()
    }

    /// Write the magnetometer `CNTL2` mode through slave 4:
    /// 0 power-down, 1 single, 2/4/6 continuous 1–3, 8 continuous 4.
    pub fn mag_set_mode<D: DelayNs>(&mut self, mode: u8, delay: &mut D) -> Res<(), I2C> {
        self.aux_write_byte(AK_ADDR, AK_CNTL2, mode & 0x1F, delay)
    }

    fn mag_block(&mut self) -> Res<[u8; 9], I2C> {
        if !self.mag_enabled {
            return Err(Icm20948Error::MagNotEnabled);
        }
        let mut b = [0u8; 9];
        self.inner.rd(0, REG_EXT_SLV_SENS_DATA_00, &mut b)?;
        Ok(b)
    }

    /// Read the magnetic field in µT, as the chip reports it.
    ///
    /// # Errors
    /// [`Icm20948Error::MagNotEnabled`] before [`enable_mag`](Self::enable_mag);
    /// [`Icm20948Error::MagOverflow`] when `HOFL` is set.
    pub fn mag(&mut self) -> Res<(f32, f32, f32), I2C> {
        let b = self.mag_block()?;
        if b[8] & 0x08 != 0 {
            return Err(Icm20948Error::MagOverflow);
        }
        Ok((i16::from_le_bytes([b[1], b[2]]) as f32 * MAG_SENSITIVITY,
            i16::from_le_bytes([b[3], b[4]]) as f32 * MAG_SENSITIVITY,
            i16::from_le_bytes([b[5], b[6]]) as f32 * MAG_SENSITIVITY))
    }

    /// Read the raw magnetometer values (signed 16-bit counts).
    pub fn mag_raw(&mut self) -> Res<(i16, i16, i16), I2C> {
        let b = self.mag_block()?;
        Ok((i16::from_le_bytes([b[1], b[2]]), i16::from_le_bytes([b[3], b[4]]),
            i16::from_le_bytes([b[5], b[6]])))
    }

    /// Check the magnetometer `DRDY` flag from the shadowed block.
    pub fn mag_data_ready(&mut self) -> Res<bool, I2C> {
        Ok(self.mag_block()?[0] & 0x01 != 0)
    }

    /// Check whether new raw data is available (`INT_STATUS_1.RAW_DATA_0_RDY_INT`).
    pub fn data_ready(&mut self) -> Res<bool, I2C> {
        Ok(self.inner.rd1(0, REG_INT_STATUS_1)? & 0x01 != 0)
    }

    /// Select FIFO sources, enable the FIFO and reset it.
    pub fn enable_fifo(&mut self, accel: bool, gyro: bool, temp: bool) -> Res<(), I2C> {
        self.lp_suspend()?;
        let sources = (if accel { 0x10 } else { 0 }) | (if gyro { 0x0E } else { 0 }) | u8::from(temp);
        self.inner.wr1(0, REG_FIFO_EN_2, sources)?;
        let ctrl = self.inner.rd1(0, REG_USER_CTRL)?;
        self.inner.wr1(0, REG_USER_CTRL, ctrl | 0x40)?;
        self.reset_fifo()?;
        self.lp_resume()
    }

    /// Select FIFO mode: `false` = stream (overwrite oldest), `true` = snapshot (stop when full).
    pub fn set_fifo_mode(&mut self, snapshot: bool) -> Res<(), I2C> {
        self.lp_suspend()?;
        self.inner.wr1(0, REG_FIFO_MODE, if snapshot { 0x1F } else { 0x00 })?;
        self.lp_resume()
    }

    /// Number of bytes in the FIFO (0–4096).
    pub fn fifo_count(&mut self) -> Res<u16, I2C> {
        let mut b = [0u8; 2];
        self.inner.rd(0, REG_FIFO_COUNTH, &mut b)?;
        Ok((((b[0] & 0x1F) as u16) << 8) | b[1] as u16)
    }

    /// Read bytes from the FIFO into `buf` (frame layout is not documented in the datasheet).
    ///
    /// Returns the number of bytes read, limited by the FIFO count and `buf.len()`.
    pub fn read_fifo(&mut self, buf: &mut [u8]) -> Res<usize, I2C> {
        let count = (self.fifo_count()? as usize).min(buf.len());
        let mut done = 0;
        while done < count {
            let n = (count - done).min(24);
            self.inner.rd(0, REG_FIFO_R_W, &mut buf[done..done + n])?;
            done += n;
        }
        Ok(done)
    }

    /// Reset the FIFO by asserting and de-asserting `FIFO_RST`.
    pub fn reset_fifo(&mut self) -> Res<(), I2C> {
        self.inner.wr1(0, REG_FIFO_RST, 0x1F)?;
        self.inner.wr1(0, REG_FIFO_RST, 0x00)
    }

    /// Configure wake-on-motion (needs the accelerometer on with its filter enabled).
    ///
    /// `threshold_mg` is in mg (4 mg/LSB, 0–1020).
    pub fn set_wake_on_motion(&mut self, threshold_mg: f32, enabled: bool) -> Res<(), I2C> {
        self.lp_suspend()?;
        let thr = libm::roundf(threshold_mg / 4.0).clamp(0.0, 255.0) as u8;
        self.inner.wr1(2, REG_ACCEL_WOM_THR, thr)?;
        self.inner.wr1(2, REG_ACCEL_INTEL_CTRL, if enabled { 0x03 } else { 0x00 })?;
        if enabled {
            self.enable_interrupt(SOURCE_WAKE_ON_MOTION)?;
        } else {
            self.disable_interrupt(SOURCE_WAKE_ON_MOTION)?;
        }
        self.lp_resume()
    }

    /// Configure the INT1 pin (`INT_PIN_CFG`).
    pub fn set_interrupt_pin(&mut self, active_low: bool, open_drain: bool, latch: bool,
                             any_read_clears: bool) -> Res<(), I2C> {
        let v = (if active_low { 0x80 } else { 0 }) | (if open_drain { 0x40 } else { 0 })
            | (if latch { 0x20 } else { 0 }) | (if any_read_clears { 0x10 } else { 0 });
        self.inner.wr1(0, REG_INT_PIN_CFG, v)
    }

    fn int_enable(&mut self, source: u8, on: bool) -> Res<(), I2C> {
        let (reg, mask) = match source {
            SOURCE_DATA_READY => (REG_INT_ENABLE_1, 0x01),
            SOURCE_WAKE_ON_MOTION => (REG_INT_ENABLE, 0x08),
            SOURCE_I2C_MASTER => (REG_INT_ENABLE, 0x01),
            SOURCE_PLL_READY => (REG_INT_ENABLE, 0x04),
            SOURCE_FIFO_OVERFLOW => (REG_INT_ENABLE_2, 0x1F),
            _ => return Err(Icm20948Error::InvalidSource),
        };
        let v = self.inner.rd1(0, reg)?;
        self.inner.wr1(0, reg, if on { v | mask } else { v & !mask })
    }

    /// Enable one interrupt source (a `SOURCE_*` constant).
    pub fn enable_interrupt(&mut self, source: u8) -> Res<(), I2C> {
        self.int_enable(source, true)
    }

    /// Disable one interrupt source (a `SOURCE_*` constant).
    pub fn disable_interrupt(&mut self, source: u8) -> Res<(), I2C> {
        self.int_enable(source, false)
    }

    /// Read (and clear) `INT_STATUS`, `INT_STATUS_1` and `INT_STATUS_2`.
    ///
    /// Returns the combined status using the `SOURCE_*` bit layout.
    pub fn poll_interrupt(&mut self) -> Res<u8, I2C> {
        let mut s = [0u8; 3];
        self.inner.rd(0, REG_INT_STATUS, &mut s)?;
        let mut status = 0;
        if s[1] & 0x01 != 0 { status |= SOURCE_DATA_READY; }
        if s[0] & 0x08 != 0 { status |= SOURCE_WAKE_ON_MOTION; }
        if s[0] & 0x01 != 0 { status |= SOURCE_I2C_MASTER; }
        if s[0] & 0x04 != 0 { status |= SOURCE_PLL_READY; }
        if s[2] & 0x1F != 0 { status |= SOURCE_FIFO_OVERFLOW; }
        Ok(status)
    }

    fn average_raw<D: DelayNs>(&mut self, delay: &mut D) -> Res<([f32; 3], [f32; 3]), I2C> {
        const SAMPLES: i32 = 5;
        let (mut sa, mut sg) = ([0i32; 3], [0i32; 3]);
        for _ in 0..SAMPLES {
            let a = self.accel_raw()?;
            let g = self.gyro_raw()?;
            for (i, (av, gv)) in [(a.0, g.0), (a.1, g.1), (a.2, g.2)].iter().enumerate() {
                sa[i] += i32::from(*av);
                sg[i] += i32::from(*gv);
            }
            delay.delay_ms(11);
        }
        let f = |v: [i32; 3]| [v[0] as f32 / SAMPLES as f32, v[1] as f32 / SAMPLES as f32, v[2] as f32 / SAMPLES as f32];
        Ok((f(sa), f(sg)))
    }

    /// Run the self-test enable procedure and return the raw response.
    ///
    /// Configures ±2 g / ±250 dps, averages samples with and without the self-test actuation and
    /// restores the configuration. The datasheet gives no pass/fail limits, so none is evaluated.
    ///
    /// Returns `([ax, ay, az], [gx, gy, gz])` — the raw response (enabled minus disabled).
    pub fn self_test<D: DelayNs>(&mut self, delay: &mut D) -> Res<([f32; 3], [f32; 3]), I2C> {
        let (saved_a, saved_g) = (self.inner.accel_fs, self.inner.gyro_fs);
        self.configure_accel(0)?;
        self.configure_gyro(0)?;
        delay.delay_ms(50);
        let (a0, g0) = self.average_raw(delay)?;
        self.lp_suspend()?;
        self.inner.wr1(2, REG_GYRO_CONFIG_2, 0x38)?;
        self.inner.wr1(2, REG_ACCEL_CONFIG_2, 0x1C)?;
        delay.delay_ms(50);
        let (a1, g1) = self.average_raw(delay)?;
        self.inner.wr1(2, REG_GYRO_CONFIG_2, 0x00)?;
        self.inner.wr1(2, REG_ACCEL_CONFIG_2, 0x00)?;
        self.lp_resume()?;
        self.configure_accel(saved_a)?;
        self.configure_gyro(saved_g)?;
        Ok(([a1[0] - a0[0], a1[1] - a0[1], a1[2] - a0[2]], [g1[0] - g0[0], g1[1] - g0[1], g1[2] - g0[2]]))
    }

    /// Read the per-part PLL time-base correction in percent (0.079 %/LSB, signed).
    pub fn read_timebase_correction(&mut self) -> Res<f32, I2C> {
        let raw = self.inner.rd1(1, REG_TIMEBASE_CORRECTION_PLL)?;
        Ok(f32::from(raw as i8) * 0.079)
    }

    /// Issue `DEVICE_RESET` and wait. The chip is left in sleep with default registers; create a
    /// new driver afterwards to run the initialization again.
    pub fn reset<D: DelayNs>(&mut self, delay: &mut D) -> Res<(), I2C> {
        self.inner.wr1(0, REG_PWR_MGMT_1, 0x80)?;
        delay.delay_ms(100);
        self.inner.bank = 0;
        self.inner.lp_en = false;
        self.inner.lp_suspended = false;
        self.mag_enabled = false;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::{ErrorKind, ErrorType, Operation};
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use std::collections::HashMap;

    const ADDR: u8 = 0x68;

    /// I²C fake that models the ICM-20948 user banks (`REG_BANK_SEL` = 0x7F). Registers are keyed
    /// `(bank << 8) | reg`; every write is logged with the bank it landed in.
    struct BankedI2c {
        bank: u8,
        regs: HashMap<u16, u8>,
        log: Vec<(u8, Vec<u8>)>,
    }

    impl BankedI2c {
        fn new() -> Self {
            let mut s = Self { bank: 0, regs: HashMap::new(), log: Vec::new() };
            s.set(0, REG_WHO_AM_I, &[WHO_AM_I_VALUE]);
            s.set(0, REG_I2C_MST_STATUS, &[0x40]); // SLV4_DONE
            s
        }

        fn set(&mut self, bank: u8, reg: u8, values: &[u8]) {
            for (i, v) in values.iter().enumerate() {
                self.regs.insert(((bank as u16) << 8) | (reg as usize + i) as u16, *v);
            }
        }

        fn get(&self, bank: u8, reg: u8) -> u8 {
            *self.regs.get(&(((bank as u16) << 8) | reg as u16)).unwrap_or(&0)
        }

        fn writes_in(&self, bank: u8) -> Vec<Vec<u8>> {
            self.log.iter().filter(|(b, d)| *b == bank && d.len() > 1 && d[0] != REG_BANK_SEL)
                .map(|(_, d)| d.clone()).collect()
        }

        fn wrote(&self, bank: u8, bytes: &[u8]) -> bool {
            self.writes_in(bank).iter().any(|d| d.as_slice() == bytes)
        }
    }

    impl ErrorType for BankedI2c {
        type Error = ErrorKind;
    }

    impl I2c for BankedI2c {
        fn transaction(&mut self, _address: u8, operations: &mut [Operation<'_>]) -> Result<(), ErrorKind> {
            let mut reg = 0u8;
            for op in operations.iter_mut() {
                match op {
                    Operation::Write(data) => {
                        self.log.push((self.bank, data.to_vec()));
                        reg = data[0];
                        if reg == REG_BANK_SEL && data.len() == 2 {
                            self.bank = data[1] >> 4;
                        } else {
                            for (i, v) in data[1..].iter().enumerate() {
                                self.regs.insert(((self.bank as u16) << 8) | (reg as usize + i) as u16, *v);
                            }
                        }
                    }
                    Operation::Read(buf) => {
                        for (i, b) in buf.iter_mut().enumerate() {
                            *b = self.get(self.bank, reg.wrapping_add(i as u8));
                        }
                    }
                }
            }
            Ok(())
        }
    }

    fn s16(value: i16) -> [u8; 2] {
        value.to_be_bytes()
    }

    fn make() -> Icm20948Full<BankedI2c> {
        Icm20948Full::new(BankedI2c::new(), ADDR, &mut NoopDelay::new()).expect("init")
    }

    fn near(a: f32, b: f32, tol: f32) -> bool {
        (a - b).abs() < tol
    }

    #[test]
    fn init_sequence() {
        let s = make();
        let log: Vec<Vec<u8>> = s.inner.i2c.log.iter().map(|(_, d)| d.clone()).collect();
        let expected: Vec<Vec<u8>> = vec![
            vec![0x7F, 0x00], vec![0x00], vec![0x06, 0x80], vec![0x06, 0x01], vec![0x07, 0x00],
            vec![0x7F, 0x20], vec![0x01, 0x19], vec![0x7F, 0x00],
            vec![0x7F, 0x20], vec![0x14, 0x19], vec![0x7F, 0x00],
            vec![0x7F, 0x20], vec![0x00, 0x0A], vec![0x7F, 0x00],
            vec![0x7F, 0x20], vec![0x10, 0x00], vec![0x7F, 0x00],
            vec![0x7F, 0x20], vec![0x11, 0x0A], vec![0x7F, 0x00],
        ];
        assert_eq!(log, expected);
        assert_eq!(s.inner.i2c.bank, 0);
    }

    #[test]
    fn who_am_i_mismatch_errors() {
        let mut i2c = BankedI2c::new();
        i2c.set(0, REG_WHO_AM_I, &[0x00]);
        let r = Icm20948Minimal::new(i2c, ADDR, &mut NoopDelay::new());
        assert!(matches!(r, Err(Icm20948Error::BadWhoAmI(0x00))));
    }

    #[test]
    fn accel_gyro_and_configuration() {
        let mut s = make();
        s.inner.i2c.set(0, REG_ACCEL_XOUT_H, &[s16(16384), s16(-8192), s16(4096)].concat());
        let (ax, ay, az) = s.accel().unwrap();
        assert!(near(ax, 9.80665, 1e-3) && near(ay, -4.903325, 1e-3) && near(az, 2.4516625, 1e-3));

        s.inner.i2c.set(0, REG_GYRO_XOUT_H, &[s16(131), s16(-131), s16(262)].concat());
        let (gx, gy, gz) = s.gyro().unwrap();
        let d2r = core::f32::consts::PI / 180.0;
        assert!(near(gx, d2r, 1e-4) && near(gy, -d2r, 1e-4) && near(gz, 2.0 * d2r, 1e-4));

        s.configure_gyro(2).unwrap();
        assert_eq!(s.inner.i2c.writes_in(2).last().unwrap(), &vec![0x01, 0x1D]);
        s.inner.i2c.set(0, REG_GYRO_XOUT_H, &[s16(328), s16(0), s16(0)].concat());
        assert!(near(s.gyro().unwrap().0, 10.0 * d2r, 1e-3));

        s.configure_accel(1).unwrap();
        assert_eq!(s.inner.i2c.writes_in(2).last().unwrap(), &vec![0x14, 0x1B]);
        s.inner.i2c.set(0, REG_ACCEL_XOUT_H, &[s16(8192), s16(0), s16(0)].concat());
        assert!(near(s.accel().unwrap().0, 9.80665, 1e-3));

        s.configure_filters(5, 2, false, true).unwrap();
        let w2 = s.inner.i2c.writes_in(2);
        assert_eq!(w2[w2.len() - 2], vec![0x01, (5 << 3) | (2 << 1)]);
        assert_eq!(w2[w2.len() - 1], vec![0x14, (2 << 3) | (1 << 1) | 1]);
        s.configure_filters(3, 3, true, true).unwrap();

        s.configure_sample_rate(9, 0x123).unwrap();
        let w2 = s.inner.i2c.writes_in(2);
        assert_eq!(w2[w2.len() - 3], vec![0x00, 9]);
        assert_eq!(w2[w2.len() - 2], vec![0x10, 0x01]);
        assert_eq!(w2[w2.len() - 1], vec![0x11, 0x23]);
        assert_eq!(s.inner.i2c.bank, 0);
    }

    #[test]
    fn burst_raw_and_temperature() {
        let mut s = make();
        s.configure_accel(1).unwrap();
        s.configure_gyro(2).unwrap();
        s.inner.i2c.set(0, REG_ACCEL_XOUT_H,
            &[s16(8192), s16(0), s16(0), s16(262), s16(0), s16(0), s16(340)].concat());
        let (a, g, t) = s.read_all().unwrap();
        assert!(near(a.0, 9.80665, 1e-3));
        assert!(near(g.0, (262.0 / 32.8) * core::f32::consts::PI / 180.0, 1e-4));
        assert!(near(t, 340.0 / 333.87 + 21.0, 1e-3));

        s.inner.i2c.set(0, REG_TEMP_OUT_H, &s16(340));
        assert!(near(s.temperature().unwrap(), 340.0 / 333.87 + 21.0, 1e-3));
        s.inner.i2c.set(0, REG_ACCEL_XOUT_H, &[s16(100), s16(-200), s16(300)].concat());
        assert_eq!(s.accel_raw().unwrap(), (100, -200, 300));
        s.inner.i2c.set(0, REG_GYRO_XOUT_H, &[s16(-50), s16(60), s16(-70)].concat());
        assert_eq!(s.gyro_raw().unwrap(), (-50, 60, -70));

        s.inner.i2c.set(0, REG_INT_STATUS_1, &[0x01]);
        assert!(s.data_ready().unwrap());
        s.inner.i2c.set(0, REG_INT_STATUS_1, &[0x00]);
        assert!(!s.data_ready().unwrap());
    }

    #[test]
    fn power_and_offsets() {
        let mut s = make();
        s.set_sensors(false, true).unwrap();
        assert_eq!(s.inner.i2c.writes_in(0).last().unwrap(), &vec![0x07, 0x38]);
        s.set_sensors(true, false).unwrap();
        assert_eq!(s.inner.i2c.writes_in(0).last().unwrap(), &vec![0x07, 0x07]);
        s.set_sensors(true, true).unwrap();
        assert_eq!(s.inner.i2c.writes_in(0).last().unwrap(), &vec![0x07, 0x00]);

        s.set_sleep(true).unwrap();
        assert_eq!(s.inner.i2c.writes_in(0).last().unwrap(), &vec![0x06, 0x41]);
        s.set_sleep(false).unwrap();
        assert_eq!(s.inner.i2c.writes_in(0).last().unwrap(), &vec![0x06, 0x01]);

        let d2r = core::f32::consts::PI / 180.0;
        s.set_gyro_offset(d2r, 0.0, -d2r).unwrap();
        assert_eq!(s.inner.i2c.writes_in(2).last().unwrap(), &vec![0x03, 0x00, 0x21, 0x00, 0x00, 0xFF, 0xDF]);

        s.set_accel_offset(0x1234, -1, 0).unwrap();
        let w1 = s.inner.i2c.writes_in(1);
        assert_eq!(w1[w1.len() - 3], vec![0x14, 0x24, 0x68]);
        assert_eq!(w1[w1.len() - 2], vec![0x17, 0xFF, 0xFE]);
        s.inner.i2c.set(1, 0x14, &[0x24, 0x68, 0, 0xFF, 0xFE, 0, 0x00, 0x02]);
        assert_eq!(s.get_accel_offset().unwrap(), (0x1234, -1, 1));
        assert_eq!(s.inner.i2c.bank, 0);

        s.inner.i2c.set(1, REG_TIMEBASE_CORRECTION_PLL, &[0xF6]);
        assert!(near(s.read_timebase_correction().unwrap(), -10.0 * 0.079, 1e-4));
    }

    #[test]
    fn fifo() {
        let mut s = make();
        s.inner.i2c.set(0, REG_FIFO_COUNTH, &[0x03, 0x45]);
        assert_eq!(s.fifo_count().unwrap(), 0x345);
        s.inner.i2c.set(0, REG_FIFO_COUNTH, &[0x00, 0x02]);
        s.inner.i2c.set(0, REG_FIFO_R_W, &[0xAA, 0xBB]);
        let mut buf = [0u8; 8];
        assert_eq!(s.read_fifo(&mut buf).unwrap(), 2);
        assert_eq!(&buf[..2], &[0xAA, 0xBB]);
        assert_eq!(s.read_fifo(&mut buf[..1]).unwrap(), 1);
        s.inner.i2c.set(0, REG_FIFO_COUNTH, &[0x00, 0x00]);
        assert_eq!(s.read_fifo(&mut buf).unwrap(), 0);

        s.enable_fifo(true, true, false).unwrap();
        assert!(s.inner.i2c.wrote(0, &[0x67, 0x1E]));
        assert!(s.inner.i2c.wrote(0, &[0x03, 0x40]));
        let w0 = s.inner.i2c.writes_in(0);
        assert_eq!(w0[w0.len() - 2], vec![0x68, 0x1F]);
        assert_eq!(w0[w0.len() - 1], vec![0x68, 0x00]);
        s.set_fifo_mode(true).unwrap();
        assert_eq!(s.inner.i2c.writes_in(0).last().unwrap(), &vec![0x69, 0x1F]);
        s.set_fifo_mode(false).unwrap();
        assert_eq!(s.inner.i2c.writes_in(0).last().unwrap(), &vec![0x69, 0x00]);
    }

    #[test]
    fn interrupts_and_wake_on_motion() {
        let mut s = make();
        s.set_interrupt_pin(true, false, true, true).unwrap();
        assert_eq!(s.inner.i2c.writes_in(0).last().unwrap(), &vec![0x0F, 0xB0]);
        s.enable_interrupt(SOURCE_DATA_READY).unwrap();
        assert_eq!(s.inner.i2c.writes_in(0).last().unwrap(), &vec![0x11, 0x01]);
        s.enable_interrupt(SOURCE_PLL_READY).unwrap();
        assert_eq!(s.inner.i2c.writes_in(0).last().unwrap(), &vec![0x10, 0x04]);
        s.disable_interrupt(SOURCE_PLL_READY).unwrap();
        assert_eq!(s.inner.i2c.writes_in(0).last().unwrap(), &vec![0x10, 0x00]);
        s.enable_interrupt(SOURCE_FIFO_OVERFLOW).unwrap();
        assert_eq!(s.inner.i2c.writes_in(0).last().unwrap(), &vec![0x12, 0x1F]);
        assert!(matches!(s.enable_interrupt(0x80), Err(Icm20948Error::InvalidSource)));

        s.inner.i2c.set(0, REG_INT_STATUS, &[0x09, 0x01, 0x02]);
        assert_eq!(s.poll_interrupt().unwrap(), 0x01 | 0x02 | 0x04 | 0x10);
        s.inner.i2c.set(0, REG_INT_STATUS, &[0x04, 0x00, 0x00]);
        assert_eq!(s.poll_interrupt().unwrap(), 0x08);

        s.set_wake_on_motion(200.0, true).unwrap();
        assert!(s.inner.i2c.wrote(2, &[0x13, 50]));
        assert!(s.inner.i2c.wrote(2, &[0x12, 0x03]));
        assert_eq!(s.inner.i2c.writes_in(0).last().unwrap(), &vec![0x10, 0x08]);
    }

    #[test]
    fn low_power_guard() {
        let mut s = make();
        s.inner.i2c.set(0, REG_PWR_MGMT_1, &[0x01]);
        s.set_low_power(true, true, 2, 3).unwrap();
        assert!(s.inner.i2c.wrote(0, &[0x05, 0x70]));
        assert_eq!(s.inner.i2c.writes_in(0).last().unwrap(), &vec![0x06, 0x21]);
        assert!(s.inner.i2c.wrote(2, &[0x15, 0x02]) && s.inner.i2c.wrote(2, &[0x02, 0x03]));
        s.inner.i2c.log.clear();
        s.configure_gyro(0).unwrap();
        let w0 = s.inner.i2c.writes_in(0);
        assert_eq!(w0.first().unwrap(), &vec![0x06, 0x01]);
        assert_eq!(w0.last().unwrap(), &vec![0x06, 0x21]);
        s.set_low_power(false, false, 0, 0).unwrap();
        assert_eq!(s.inner.i2c.get(0, REG_PWR_MGMT_1) & 0x20, 0);
    }

    #[test]
    fn magnetometer() {
        let mut s = make();
        let mut d = NoopDelay::new();
        assert!(matches!(s.mag(), Err(Icm20948Error::MagNotEnabled)));
        assert!(matches!(s.mag_raw(), Err(Icm20948Error::MagNotEnabled)));
        assert!(matches!(s.mag_data_ready(), Err(Icm20948Error::MagNotEnabled)));

        s.inner.i2c.set(3, REG_I2C_SLV4_DI, &[0x09]); // WIA2
        s.enable_mag(&mut d).unwrap();
        assert_eq!(s.inner.i2c.bank, 0);
        assert!(s.inner.i2c.wrote(3, &[0x01, 0x07]));
        assert!(s.inner.i2c.wrote(3, &[0x16, 0x01]) && s.inner.i2c.wrote(3, &[0x14, 0x32]));
        assert!(s.inner.i2c.wrote(3, &[0x16, 0x08]) && s.inner.i2c.wrote(3, &[0x14, 0x31]));
        let w3 = s.inner.i2c.writes_in(3);
        assert_eq!(w3[w3.len() - 3], vec![0x03, 0x8C]);
        assert_eq!(w3[w3.len() - 2], vec![0x04, 0x10]);
        assert_eq!(w3[w3.len() - 1], vec![0x05, 0x89]);
        assert_eq!(s.inner.i2c.get(0, REG_USER_CTRL) & 0x20, 0x20);

        let le = |v: i16| v.to_le_bytes();
        s.inner.i2c.set(0, REG_EXT_SLV_SENS_DATA_00,
            &[&[0x01][..], &le(1000), &le(-500), &le(250), &[0x00, 0x00]].concat());
        let (mx, my, mz) = s.mag().unwrap();
        assert!(near(mx, 150.0, 1e-3) && near(my, -75.0, 1e-3) && near(mz, 37.5, 1e-3));
        assert_eq!(s.mag_raw().unwrap(), (1000, -500, 250));
        assert!(s.mag_data_ready().unwrap());
        s.inner.i2c.set(0, REG_EXT_SLV_SENS_DATA_00, &[0x00]);
        assert!(!s.mag_data_ready().unwrap());
        s.inner.i2c.set(0, REG_EXT_SLV_SENS_DATA_00,
            &[&[0x01][..], &le(1), &le(2), &le(3), &[0x00, 0x08]].concat());
        assert!(matches!(s.mag(), Err(Icm20948Error::MagOverflow)));
        assert_eq!(s.mag_raw().unwrap(), (1, 2, 3));

        s.mag_set_mode(2, &mut d).unwrap();
        assert!(s.inner.i2c.wrote(3, &[0x16, 0x02]));

        s.inner.i2c.set(3, REG_I2C_SLV4_DI, &[0x5A]);
        assert_eq!(s.aux_read_byte(0x1E, 0x0A, &mut d).unwrap(), 0x5A);
        assert!(s.inner.i2c.wrote(3, &[0x13, 0x9E]));
        s.aux_write_byte(0x1E, 0x0B, 0xA5, &mut d).unwrap();
        assert!(s.inner.i2c.wrote(3, &[0x13, 0x1E]) && s.inner.i2c.wrote(3, &[0x16, 0xA5]));
        assert_eq!(s.inner.i2c.bank, 0);
    }

    #[test]
    fn magnetometer_errors() {
        let mut d = NoopDelay::new();
        let mut s = make(); // WIA2 reads 0 -> mismatch
        assert!(matches!(s.enable_mag(&mut d), Err(Icm20948Error::BadMagId(0x00))));

        let mut s = make();
        s.inner.i2c.set(0, REG_I2C_MST_STATUS, &[0x10]);
        assert!(matches!(s.aux_read_byte(0x0C, 0x01, &mut d), Err(Icm20948Error::AuxNack)));

        let mut s = make();
        s.inner.i2c.set(0, REG_I2C_MST_STATUS, &[0x00]);
        assert!(matches!(s.aux_read_byte(0x0C, 0x01, &mut d), Err(Icm20948Error::AuxTimeout)));
    }

    #[test]
    fn self_test_and_reset() {
        let mut s = make();
        let mut d = NoopDelay::new();
        let (a, g) = s.self_test(&mut d).unwrap();
        assert_eq!((a, g), ([0.0; 3], [0.0; 3]));
        assert_eq!(s.inner.i2c.bank, 0);
        s.reset(&mut d).unwrap();
        assert_eq!(s.inner.i2c.writes_in(0).last().unwrap(), &vec![0x06, 0x80]);
    }
}
