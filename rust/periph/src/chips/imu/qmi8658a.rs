//! QMI8658A — 6-axis inertial measurement unit (QST): 3-axis gyroscope, 3-axis accelerometer and
//! die-temperature sensor with FIFO, two interrupt pins and a motion co-processor (any / no /
//! significant motion, tap, pedometer, wake-on-motion).
//!
//! Communicates over I²C (up to 400 kHz) at `0x6B` (SA0 = GND) or `0x6A` (SA0 = VDDIO). The
//! SPI interface of the chip is covered by the C++, Python, Node.js, Go and JVM drivers; this
//! Rust driver is generic over [`embedded_hal::i2c::I2c`] only, like the other IMU drivers.
//! I3C and 3-wire SPI are out of scope. Configuration registers `CTRL1`–`CTRL9` and the `CAL`
//! registers are always written one register per transaction.
//!
//! ## Default configuration (Minimal)
//!
//! - Accelerometer ±2 g, gyroscope ±256 dps
//! - Both output data rates code 5 (224.2 Hz in 6DOF mode)
//! - Both low-pass filters on, mode 3 (13.37 % of ODR)
//! - FIFO bypass (data-ready active), all INT pins high-Z, engines off
//!
//! Rust drivers expose [`Qmi8658aFull::poll_interrupt`] only — the caller wires the INT pin into
//! an ISR or polling loop of their own and calls it when the pin asserts.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register;

const REG_WHO_AM_I: u8 = 0x00;
const REG_REVISION_ID: u8 = 0x01;
const REG_CTRL1: u8 = 0x02;
const REG_CTRL2: u8 = 0x03;
const REG_CTRL3: u8 = 0x04;
const REG_CTRL5: u8 = 0x06;
const REG_CTRL7: u8 = 0x08;
const REG_CTRL8: u8 = 0x09;
const REG_CTRL9: u8 = 0x0A;
const REG_CAL1_L: u8 = 0x0B;
const REG_FIFO_WTM_TH: u8 = 0x13;
const REG_FIFO_CTRL: u8 = 0x14;
const REG_FIFO_SMPL_CNT: u8 = 0x15;
const REG_FIFO_STATUS: u8 = 0x16;
const REG_FIFO_DATA: u8 = 0x17;
const REG_STATUSINT: u8 = 0x2D;
const REG_STATUS0: u8 = 0x2E;
const REG_TIMESTAMP: u8 = 0x30;
const REG_TEMP_L: u8 = 0x33;
const REG_AX_L: u8 = 0x35;
const REG_GX_L: u8 = 0x3B;
const REG_COD_STATUS: u8 = 0x46;
const REG_FW_VERSION: u8 = 0x49;
const REG_RESET_STATUS: u8 = 0x4D;
const REG_DVX_L: u8 = 0x51;
const REG_TAP_STATUS: u8 = 0x59;
const REG_STEP_CNT: u8 = 0x5A;
const REG_RESET: u8 = 0x60;

const WHO_AM_I_VALUE: u8 = 0x05;
const RESET_CMD: u8 = 0xB0;

const CMD_ACK: u8 = 0x00;
const CMD_RST_FIFO: u8 = 0x04;
const CMD_REQ_FIFO: u8 = 0x05;
const CMD_WRITE_WOM: u8 = 0x08;
const CMD_ACCEL_OFFSET: u8 = 0x09;
const CMD_GYRO_OFFSET: u8 = 0x0A;
const CMD_CONFIG_TAP: u8 = 0x0C;
const CMD_CONFIG_PEDOMETER: u8 = 0x0D;
const CMD_CONFIG_MOTION: u8 = 0x0E;
const CMD_RESET_PEDOMETER: u8 = 0x0F;
const CMD_COPY_USID: u8 = 0x10;
const CMD_SET_RPU: u8 = 0x11;
const CMD_AHB_CLOCK_GATING: u8 = 0x12;
const CMD_COD: u8 = 0xA2;
const CMD_APPLY_GYRO_GAINS: u8 = 0xAA;

const G: f32 = 9.80665;
const DEG_TO_RAD: f32 = core::f32::consts::PI / 180.0;

/// Interrupt source: new sample (`STATUS0` aDA / gDA); only while the FIFO is in bypass; INT2.
pub const SOURCE_DATA_READY: u16 = 0x0001;
/// Interrupt source: FIFO watermark (`FIFO_STATUS.FIFO_WTM`).
pub const SOURCE_FIFO_WATERMARK: u16 = 0x0002;
/// Interrupt source: FIFO full (`FIFO_STATUS.FIFO_FULL`).
pub const SOURCE_FIFO_FULL: u16 = 0x0004;
/// Interrupt source: any-motion event (`STATUS1` bit 5).
pub const SOURCE_ANY_MOTION: u16 = 0x0008;
/// Interrupt source: no-motion event (`STATUS1` bit 6).
pub const SOURCE_NO_MOTION: u16 = 0x0010;
/// Interrupt source: significant-motion event (`STATUS1` bit 7).
pub const SOURCE_SIGNIFICANT_MOTION: u16 = 0x0020;
/// Interrupt source: pedometer step event (`STATUS1` bit 4).
pub const SOURCE_STEP: u16 = 0x0040;
/// Interrupt source: tap event (`STATUS1` bit 1).
pub const SOURCE_TAP: u16 = 0x0080;
/// Interrupt source: wake-on-motion event (`STATUS1` bit 2).
pub const SOURCE_WAKE_ON_MOTION: u16 = 0x0100;

/// Errors returned by the QMI8658A drivers.
#[derive(Debug, PartialEq)]
pub enum Qmi8658aError<E> {
    /// The underlying I²C bus failed.
    I2c(E),
    /// `WHO_AM_I` did not read `0x05` (wrong chip, address or wiring); carries the value read.
    BadWhoAmI(u8),
    /// A CTRL9 command, SyncSample lock or self-test did not complete in time.
    Timeout,
    /// An argument is outside the range the chip accepts.
    InvalidArgument,
}

type Res<T, I2C> = Result<T, Qmi8658aError<<I2C as embedded_hal::i2c::ErrorType>::Error>>;

fn le16(b: &[u8], i: usize) -> i16 {
    i16::from_le_bytes([b[i], b[i + 1]])
}

fn round(v: f32) -> i32 {
    if v >= 0.0 { (v + 0.5) as i32 } else { (v - 0.5) as i32 }
}

fn engine_bit(source: u16) -> u8 {
    match source {
        SOURCE_ANY_MOTION => 0x02,
        SOURCE_NO_MOTION => 0x04,
        SOURCE_SIGNIFICANT_MOTION => 0x08,
        SOURCE_STEP => 0x10,
        SOURCE_TAP => 0x01,
        _ => 0x00,
    }
}

/// QMI8658A minimal driver — 3-axis acceleration and 3-axis angular rate.
///
/// Performs the datasheet initialisation sequence at construction: soft reset, `WHO_AM_I` check,
/// address auto-increment on, little-endian reads, both sensors on.
pub struct Qmi8658aMinimal<I2C> {
    i2c: I2C,
    addr: u8,
    ctrl1: u8,
    ctrl7: u8,
    ctrl8: u8,
    accel_fs: u8,
    gyro_fs: u8,
    accel_odr: u8,
    gyro_odr: u8,
    fifo_ctrl: u8,
    fifo_wtm: u8,
    sync: bool,
}

impl<I2C: I2c> Qmi8658aMinimal<I2C> {
    /// Create a new `Qmi8658aMinimal`, reset the device, verify `WHO_AM_I` and configure defaults.
    ///
    /// # Arguments
    /// * `i2c`   — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr`  — 7-bit device address (`0x6B` with SA0 = GND, `0x6A` with SA0 = VDDIO).
    /// * `delay` — Delay provider for init timing (15 ms turn-on/reset, 165 ms gyroscope start-up).
    ///
    /// # Errors
    /// [`Qmi8658aError::BadWhoAmI`] if `WHO_AM_I` is not `0x05`.
    pub fn new<D: DelayNs>(i2c: I2C, addr: u8, delay: &mut D) -> Res<Self, I2C> {
        let mut s = Self {
            i2c, addr, ctrl1: 0x40, ctrl7: 0x03, ctrl8: 0x80, accel_fs: 0, gyro_fs: 4,
            accel_odr: 5, gyro_odr: 5, fifo_ctrl: 0, fifo_wtm: 0, sync: false,
        };
        delay.delay_ms(15);
        s.w(REG_RESET, RESET_CMD)?;
        delay.delay_ms(15);
        s.r1(REG_RESET_STATUS)?; // 0x80 after reset, informational
        let who = s.r1(REG_WHO_AM_I)?;
        if who != WHO_AM_I_VALUE {
            return Err(Qmi8658aError::BadWhoAmI(who));
        }
        s.w(REG_CTRL1, s.ctrl1)?; // ADDR_AI = 1, BE = 0
        s.w(REG_CTRL8, s.ctrl8)?; // CTRL9 handshake by polling
        s.w(REG_CTRL2, 0x05)?;
        s.w(REG_CTRL3, 0x45)?;
        s.w(REG_CTRL5, 0x77)?;
        s.w(REG_CTRL7, s.ctrl7)?;
        delay.delay_ms(165); // gyroscope turn-on
        Ok(s)
    }

    fn w(&mut self, reg: u8, value: u8) -> Res<(), I2C> {
        register::write_register(&mut self.i2c, self.addr, reg.into(), 1, &[value]).map_err(Qmi8658aError::I2c)
    }

    fn rd(&mut self, reg: u8, buf: &mut [u8]) -> Res<(), I2C> {
        register::read_register(&mut self.i2c, self.addr, reg.into(), 1, buf).map_err(Qmi8658aError::I2c)
    }

    fn r1(&mut self, reg: u8) -> Res<u8, I2C> {
        let mut b = [0u8; 1];
        self.rd(reg, &mut b)?;
        Ok(b[0])
    }

    fn accel_sens(&self) -> f32 {
        (16384u32 >> self.accel_fs) as f32
    }

    fn gyro_sens(&self) -> f32 {
        (2048u32 >> self.gyro_fs) as f32
    }

    /// Wait (bounded, no delay available) for the SyncSample `Avail` and `Locked` flags.
    fn lock_wait(&mut self) -> Res<(), I2C> {
        for mask in [0x01u8, 0x02u8] {
            let mut tries = 0u32;
            while self.r1(REG_STATUSINT)? & mask == 0 {
                tries += 1;
                if tries > 20000 {
                    return Err(Qmi8658aError::Timeout);
                }
            }
        }
        Ok(())
    }

    fn rd_data(&mut self, reg: u8, buf: &mut [u8]) -> Res<(), I2C> {
        if self.sync {
            self.lock_wait()?;
        }
        self.rd(reg, buf)
    }

    /// Read 3-axis linear acceleration.
    ///
    /// Returns (x, y, z) in m/s².
    pub fn accel(&mut self) -> Res<(f32, f32, f32), I2C> {
        let mut b = [0u8; 6];
        self.rd_data(REG_AX_L, &mut b)?;
        let s = self.accel_sens();
        Ok((le16(&b, 0) as f32 / s * G, le16(&b, 2) as f32 / s * G, le16(&b, 4) as f32 / s * G))
    }

    /// Read 3-axis angular rate.
    ///
    /// Returns (x, y, z) in rad/s.
    pub fn gyro(&mut self) -> Res<(f32, f32, f32), I2C> {
        let mut b = [0u8; 6];
        self.rd_data(REG_GX_L, &mut b)?;
        let s = self.gyro_sens();
        Ok((le16(&b, 0) as f32 / s * DEG_TO_RAD, le16(&b, 2) as f32 / s * DEG_TO_RAD,
            le16(&b, 4) as f32 / s * DEG_TO_RAD))
    }
}

/// QMI8658A full driver — extends [`Qmi8658aMinimal`] with complete functionality.
///
/// Adds raw and burst reads, temperature, sample counter, full-scale / ODR / filter configuration,
/// power control, SyncSample data locking, the FIFO, the motion engines (any / no / significant
/// motion, tap, pedometer, wake-on-motion), offsets, calibration-on-demand, self-test, device info,
/// pull-up control and interrupt status. Methods that run a CTRL9 command or restore the sensors
/// take a [`DelayNs`] provider.
pub struct Qmi8658aFull<I2C> {
    inner: Qmi8658aMinimal<I2C>,
    any_thr: f32, any_win: u8, any_axes: u8, any_and: bool,
    no_thr: f32, no_win: u8, no_axes: u8, no_and: bool,
    sig_wait: u16, sig_confirm: u16,
    int_sources: u16,
    fifo_pin: u8, activity_pin: u8, wom_pin: u8,
}

impl<I2C: I2c> Qmi8658aFull<I2C> {
    /// Create a new `Qmi8658aFull`, reset the device and configure defaults.
    ///
    /// Same arguments as [`Qmi8658aMinimal::new`].
    pub fn new<D: DelayNs>(i2c: I2C, addr: u8, delay: &mut D) -> Res<Self, I2C> {
        Ok(Self {
            inner: Qmi8658aMinimal::new(i2c, addr, delay)?,
            any_thr: 0.25, any_win: 3, any_axes: 0x07, any_and: false,
            no_thr: 0.125, no_win: 30, no_axes: 0x07, no_and: true,
            sig_wait: 150, sig_confirm: 600,
            int_sources: 0, fifo_pin: 2, activity_pin: 2, wom_pin: 1,
        })
    }

    /// Read 3-axis acceleration in m/s². Delegates to the inner [`Qmi8658aMinimal`].
    pub fn accel(&mut self) -> Res<(f32, f32, f32), I2C> {
        self.inner.accel()
    }

    /// Read 3-axis angular rate in rad/s. Delegates to the inner [`Qmi8658aMinimal`].
    pub fn gyro(&mut self) -> Res<(f32, f32, f32), I2C> {
        self.inner.gyro()
    }

    fn set_ctrl1(&mut self, v: u8) -> Res<(), I2C> {
        self.inner.ctrl1 = v;
        self.inner.w(REG_CTRL1, v)
    }

    fn set_ctrl7(&mut self, v: u8) -> Res<(), I2C> {
        self.inner.ctrl7 = v;
        self.inner.w(REG_CTRL7, v)
    }

    fn set_ctrl8(&mut self, v: u8) -> Res<(), I2C> {
        self.inner.ctrl8 = v;
        self.inner.w(REG_CTRL8, v)
    }

    fn wait_status<D: DelayNs>(&mut self, mask: u8, want: bool, timeout_ms: u32, delay: &mut D) -> Res<(), I2C> {
        for _ in 0..=timeout_ms {
            if (self.inner.r1(REG_STATUSINT)? & mask != 0) == want {
                return Ok(());
            }
            delay.delay_ms(1);
        }
        Err(Qmi8658aError::Timeout)
    }

    fn ctrl9<D: DelayNs>(&mut self, cmd: u8, timeout_ms: u32, delay: &mut D) -> Res<(), I2C> {
        self.inner.w(REG_CTRL9, cmd)?;
        let done = self.wait_status(0x80, true, timeout_ms, delay);
        self.inner.w(REG_CTRL9, CMD_ACK)?;
        done?;
        self.wait_status(0x80, false, timeout_ms, delay)
    }

    fn cal_cmd<D: DelayNs>(&mut self, cmd: u8, params: &[u8], timeout_ms: u32, delay: &mut D) -> Res<(), I2C> {
        for i in 0..8 {
            let v = params.get(i).copied().unwrap_or(0);
            self.inner.w(REG_CAL1_L + i as u8, v)?;
        }
        self.ctrl9(cmd, timeout_ms, delay)
    }

    fn two_sets<D: DelayNs>(&mut self, cmd: u8, set1: &[u8], set2: &[u8], delay: &mut D) -> Res<(), I2C> {
        let mut p = [0u8; 8];
        p[..set1.len()].copy_from_slice(set1);
        p[7] = 0x10;
        self.cal_cmd(cmd, &p, 100, delay)?;
        let mut p = [0u8; 8];
        p[..set2.len()].copy_from_slice(set2);
        p[7] = 0x20;
        self.cal_cmd(cmd, &p, 100, delay)
    }

    fn sensors_off<D: DelayNs>(&mut self, delay: &mut D) -> Res<u8, I2C> {
        let prev = self.inner.ctrl7 & 0x03;
        if prev != 0 {
            let v = self.inner.ctrl7 & !0x03;
            self.set_ctrl7(v)?;
            delay.delay_ms(5);
        }
        Ok(prev)
    }

    fn sensors_restore<D: DelayNs>(&mut self, prev: u8, delay: &mut D) -> Res<(), I2C> {
        if prev != 0 {
            let v = self.inner.ctrl7 | prev;
            self.set_ctrl7(v)?;
            delay.delay_ms(if prev & 0x02 != 0 { 165 } else { 10 });
        }
        Ok(())
    }

    /// Read raw 3-axis accelerometer values (signed 16-bit counts).
    pub fn accel_raw(&mut self) -> Res<(i16, i16, i16), I2C> {
        let mut b = [0u8; 6];
        self.inner.rd_data(REG_AX_L, &mut b)?;
        Ok((le16(&b, 0), le16(&b, 2), le16(&b, 4)))
    }

    /// Read raw 3-axis gyroscope values (signed 16-bit counts).
    pub fn gyro_raw(&mut self) -> Res<(i16, i16, i16), I2C> {
        let mut b = [0u8; 6];
        self.inner.rd_data(REG_GX_L, &mut b)?;
        Ok((le16(&b, 0), le16(&b, 2), le16(&b, 4)))
    }

    /// Burst-read temperature, acceleration and angular rate so all three belong to one sample.
    ///
    /// Returns `((ax, ay, az), (gx, gy, gz), temp_c)` in m/s², rad/s and °C.
    pub fn read_all(&mut self) -> Res<((f32, f32, f32), (f32, f32, f32), f32), I2C> {
        let mut b = [0u8; 14];
        self.inner.rd_data(REG_TEMP_L, &mut b)?;
        let a = self.inner.accel_sens();
        let g = self.inner.gyro_sens();
        let v = |i: usize| le16(&b, i) as f32;
        Ok(((v(2) / a * G, v(4) / a * G, v(6) / a * G),
            (v(8) / g * DEG_TO_RAD, v(10) / g * DEG_TO_RAD, v(12) / g * DEG_TO_RAD),
            v(0) / 256.0))
    }

    /// Read the die temperature in °C.
    pub fn temperature(&mut self) -> Res<f32, I2C> {
        let mut b = [0u8; 2];
        self.inner.rd_data(REG_TEMP_L, &mut b)?;
        Ok(le16(&b, 0) as f32 / 256.0)
    }

    /// Read the 24-bit sample counter (wraps at `0xFFFFFF`).
    pub fn timestamp(&mut self) -> Res<u32, I2C> {
        let mut b = [0u8; 3];
        self.inner.rd(REG_TIMESTAMP, &mut b)?;
        Ok(b[0] as u32 | (b[1] as u32) << 8 | (b[2] as u32) << 16)
    }

    /// `true` when an enabled sensor has new data (`STATUSINT.Avail` while SyncSample is on).
    pub fn data_ready(&mut self) -> Res<bool, I2C> {
        if self.inner.sync {
            return Ok(self.inner.r1(REG_STATUSINT)? & 0x01 != 0);
        }
        Ok(self.inner.r1(REG_STATUS0)? & 0x03 != 0)
    }

    /// Configure the accelerometer (`CTRL2`).
    ///
    /// * `full_scale` — 0 = ±2 g, 1 = ±4 g, 2 = ±8 g, 3 = ±16 g.
    /// * `odr` — ODR code 0–8 or 12–15 (12–15 only with the gyroscope off).
    pub fn configure_accel(&mut self, full_scale: u8, odr: u8) -> Res<(), I2C> {
        if full_scale > 3 || !(odr <= 8 || (12..=15).contains(&odr)) {
            return Err(Qmi8658aError::InvalidArgument);
        }
        self.inner.accel_fs = full_scale;
        self.inner.accel_odr = odr;
        self.inner.w(REG_CTRL2, (full_scale << 4) | odr)
    }

    /// Configure the gyroscope (`CTRL3`).
    ///
    /// * `full_scale` — 0 = ±16 dps … 7 = ±2048 dps (doubling).
    /// * `odr` — ODR code 0–8.
    pub fn configure_gyro(&mut self, full_scale: u8, odr: u8) -> Res<(), I2C> {
        if full_scale > 7 || odr > 8 {
            return Err(Qmi8658aError::InvalidArgument);
        }
        self.inner.gyro_fs = full_scale;
        self.inner.gyro_odr = odr;
        self.inner.w(REG_CTRL3, (full_scale << 4) | odr)
    }

    /// Configure the low-pass filters (`CTRL5`); modes 0–3 = 2.66 / 3.63 / 5.39 / 13.37 % of ODR.
    pub fn configure_filters(&mut self, accel_filter: bool, accel_mode: u8, gyro_filter: bool,
                             gyro_mode: u8) -> Res<(), I2C> {
        if accel_mode > 3 || gyro_mode > 3 {
            return Err(Qmi8658aError::InvalidArgument);
        }
        let v = (gyro_mode << 5) | (u8::from(gyro_filter) << 4) | (accel_mode << 1) | u8::from(accel_filter);
        self.inner.w(REG_CTRL5, v)
    }

    /// Enable or disable the sensors (`CTRL7` aEN / gEN / gSN).
    ///
    /// The gyroscope needs 150 ms + 3/ODR, the accelerometer 3 ms + 3/ODR to become valid.
    pub fn set_sensors(&mut self, accel: bool, gyro: bool, gyro_snooze: bool) -> Res<(), I2C> {
        let v = (self.inner.ctrl7 & !0x13) | u8::from(accel) | (u8::from(gyro) << 1) | (u8::from(gyro_snooze) << 4);
        self.set_ctrl7(v)
    }

    /// Power the chip down (`CTRL1.SensorDisable`, sensors disabled first) or wake it.
    pub fn set_power_down<D: DelayNs>(&mut self, down: bool, delay: &mut D) -> Res<(), I2C> {
        if down {
            let v7 = self.inner.ctrl7 & !0x03;
            self.set_ctrl7(v7)?;
            let v1 = self.inner.ctrl1 | 0x01;
            self.set_ctrl1(v1)
        } else {
            let v1 = self.inner.ctrl1 & !0x01;
            self.set_ctrl1(v1)?;
            delay.delay_ms(15);
            Ok(())
        }
    }

    /// Enable or disable SyncSample (data-locking) mode; incompatible with the FIFO and motion engines.
    pub fn set_sync_sample<D: DelayNs>(&mut self, enabled: bool, delay: &mut D) -> Res<(), I2C> {
        if enabled {
            self.cal_cmd(CMD_AHB_CLOCK_GATING, &[1], 100, delay)?;
            let v = self.inner.ctrl7 | 0x80;
            self.set_ctrl7(v)?;
        } else {
            let v = self.inner.ctrl7 & !0x80;
            self.set_ctrl7(v)?;
            self.cal_cmd(CMD_AHB_CLOCK_GATING, &[0], 100, delay)?;
        }
        self.inner.sync = enabled;
        Ok(())
    }

    /// Configure the FIFO.
    ///
    /// * `mode` — 0 = bypass, 1 = FIFO (stops when full), 2 = stream.
    /// * `size` — 0–3 = 16 / 32 / 64 / 128 samples.
    /// * `watermark` — watermark in samples (0 disables).
    pub fn configure_fifo(&mut self, mode: u8, size: u8, watermark: u8) -> Res<(), I2C> {
        if mode > 2 || size > 3 {
            return Err(Qmi8658aError::InvalidArgument);
        }
        self.inner.fifo_wtm = watermark;
        self.inner.w(REG_FIFO_WTM_TH, watermark)?;
        self.inner.fifo_ctrl = (size << 2) | mode;
        self.inner.w(REG_FIFO_CTRL, self.inner.fifo_ctrl)?;
        if mode == 0 {
            let v = self.inner.ctrl7 & !0x20; // DRDY back on
            self.set_ctrl7(v)?;
        }
        Ok(())
    }

    /// Raw `FIFO_STATUS` byte (full 7, watermark 6, overflow 5, not-empty 4).
    pub fn fifo_status(&mut self) -> Res<u8, I2C> {
        self.inner.r1(REG_FIFO_STATUS)
    }

    /// Bytes in the FIFO (2 × the sample-word count).
    pub fn fifo_count(&mut self) -> Res<u16, I2C> {
        let st = self.inner.r1(REG_FIFO_STATUS)?;
        let lo = self.inner.r1(REG_FIFO_SMPL_CNT)?;
        Ok(2 * ((((st & 0x03) as u16) << 8) | lo as u16))
    }

    /// Drain the FIFO with the read-mode procedure into `buf` (little-endian 6-byte frames,
    /// accelerometer first when both sensors are on). Returns the number of bytes read.
    pub fn read_fifo<D: DelayNs>(&mut self, buf: &mut [u8], delay: &mut D) -> Res<usize, I2C> {
        let total = self.fifo_count()? as usize;
        let n = total.min(buf.len());
        if n == 0 {
            return Ok(0);
        }
        self.ctrl9(CMD_REQ_FIFO, 100, delay)?;
        let c1 = self.inner.ctrl1 & !0x40; // ADDR_AI off: FIFO_DATA repeats
        self.inner.w(REG_CTRL1, c1)?;
        let mut got = 0;
        let mut result = Ok(());
        while got < n {
            let chunk = (n - got).min(32);
            if let Err(e) = self.inner.rd(REG_FIFO_DATA, &mut buf[got..got + chunk]) {
                result = Err(e);
                break;
            }
            got += chunk;
        }
        self.inner.w(REG_CTRL1, self.inner.ctrl1)?;
        self.inner.w(REG_FIFO_CTRL, self.inner.fifo_ctrl & 0x7F)?;
        result?;
        Ok(got)
    }

    /// Reset the FIFO (CTRL9 `RST_FIFO`).
    pub fn reset_fifo<D: DelayNs>(&mut self, delay: &mut D) -> Res<(), I2C> {
        self.ctrl9(CMD_RST_FIFO, 100, delay)
    }

    fn send_motion<D: DelayNs>(&mut self, delay: &mut D) -> Res<(), I2C> {
        let prev = self.sensors_off(delay)?;
        let a = round(self.any_thr * 32.0).clamp(0, 255) as u8;
        let n = round(self.no_thr * 32.0).clamp(0, 255) as u8;
        let mode = (u8::from(self.no_and) << 7) | ((self.no_axes & 7) << 4) | (u8::from(self.any_and) << 3) | (self.any_axes & 7);
        let (sw, cw) = (self.sig_wait, self.sig_confirm);
        let r = self.two_sets(CMD_CONFIG_MOTION, &[a, a, a, n, n, n, mode],
            &[self.any_win, self.no_win, sw as u8, (sw >> 8) as u8, cw as u8, (cw >> 8) as u8], delay);
        self.sensors_restore(prev, delay)?;
        r
    }

    /// Configure the any-motion engine: slope `threshold_g` in g (1/32 g steps, 0–7.97), `window`
    /// in samples, `axes` mask (bit 0 X, 1 Y, 2 Z), `logic_and` = AND instead of OR over the axes.
    pub fn configure_any_motion<D: DelayNs>(&mut self, threshold_g: f32, window: u8, axes: u8,
                                            logic_and: bool, delay: &mut D) -> Res<(), I2C> {
        self.any_thr = threshold_g;
        self.any_win = window;
        self.any_axes = axes;
        self.any_and = logic_and;
        self.send_motion(delay)
    }

    /// Configure the no-motion engine (arguments as for [`Self::configure_any_motion`]).
    pub fn configure_no_motion<D: DelayNs>(&mut self, threshold_g: f32, window: u8, axes: u8,
                                           logic_and: bool, delay: &mut D) -> Res<(), I2C> {
        self.no_thr = threshold_g;
        self.no_win = window;
        self.no_axes = axes;
        self.no_and = logic_and;
        self.send_motion(delay)
    }

    /// Configure the significant-motion engine (16-bit windows in samples); needs any- and
    /// no-motion configured and enabled.
    pub fn configure_significant_motion<D: DelayNs>(&mut self, wait_window: u16, confirm_window: u16,
                                                    delay: &mut D) -> Res<(), I2C> {
        self.sig_wait = wait_window;
        self.sig_confirm = confirm_window;
        self.send_motion(delay)
    }

    /// Enable the motion engines (`CTRL8` bits 1–3).
    pub fn enable_motion(&mut self, any_motion: bool, no_motion: bool, significant: bool) -> Res<(), I2C> {
        let v = (self.inner.ctrl8 & !0x0E) | (u8::from(any_motion) << 1) | (u8::from(no_motion) << 2)
            | (u8::from(significant) << 3);
        self.set_ctrl8(v)
    }

    /// Configure the tap engine (two CTRL9 `CONFIGURE_TAP` commands).
    ///
    /// `priority` 0–5 axis order; windows in samples; `alpha` / `gamma` in 1/128 steps;
    /// thresholds in g² (1/1024 steps).
    #[allow(clippy::too_many_arguments)]
    pub fn configure_tap<D: DelayNs>(&mut self, priority: u8, peak_window: u8, tap_window: u16,
                                     double_tap_window: u16, alpha: f32, gamma: f32,
                                     peak_threshold: f32, quiet_threshold: f32, delay: &mut D) -> Res<(), I2C> {
        let peak = round(peak_threshold * 1024.0) as u16;
        let quiet = round(quiet_threshold * 1024.0) as u16;
        let prev = self.sensors_off(delay)?;
        let r = self.two_sets(CMD_CONFIG_TAP,
            &[peak_window, priority, tap_window as u8, (tap_window >> 8) as u8, double_tap_window as u8,
              (double_tap_window >> 8) as u8],
            &[(round(alpha * 128.0) & 0x7F) as u8, (round(gamma * 128.0) & 0x7F) as u8, peak as u8,
              (peak >> 8) as u8, quiet as u8, (quiet >> 8) as u8], delay);
        self.sensors_restore(prev, delay)?;
        r
    }

    /// Enable or disable the tap engine (`CTRL8.Tap_EN`). Use an accelerometer ODR above 200 Hz.
    pub fn enable_tap(&mut self, enabled: bool) -> Res<(), I2C> {
        let v = if enabled { self.inner.ctrl8 | 0x01 } else { self.inner.ctrl8 & !0x01 };
        self.set_ctrl8(v)
    }

    /// Read the last tap event: `(count, axis, negative)` — count 0/1/2, axis 0 none / 1 X / 2 Y / 3 Z.
    pub fn tap_status(&mut self) -> Res<(u8, u8, bool), I2C> {
        let v = self.inner.r1(REG_TAP_STATUS)?;
        Ok((v & 0x03, (v >> 4) & 0x03, v & 0x80 != 0))
    }

    /// Configure the pedometer (two CTRL9 `CONFIGURE_PEDOMETER` commands; defaults are for a 50 Hz ODR).
    #[allow(clippy::too_many_arguments)]
    pub fn configure_pedometer<D: DelayNs>(&mut self, sample_count: u16, peak_to_peak_mg: f32, peak_mg: f32,
                                           time_up: u16, time_low: u8, entry_count: u8, precision: u8,
                                           report_every: u8, delay: &mut D) -> Res<(), I2C> {
        let p2p = round(peak_to_peak_mg / 1000.0 * 1024.0) as u16;
        let pk = round(peak_mg / 1000.0 * 1024.0) as u16;
        let prev = self.sensors_off(delay)?;
        let r = self.two_sets(CMD_CONFIG_PEDOMETER,
            &[sample_count as u8, (sample_count >> 8) as u8, p2p as u8, (p2p >> 8) as u8, pk as u8, (pk >> 8) as u8],
            &[time_up as u8, (time_up >> 8) as u8, time_low, entry_count, precision, report_every], delay);
        self.sensors_restore(prev, delay)?;
        r
    }

    /// Enable or disable the pedometer (`CTRL8.Pedo_EN`); a 0→1 transition resets the count.
    pub fn enable_pedometer(&mut self, enabled: bool) -> Res<(), I2C> {
        let v = if enabled { self.inner.ctrl8 | 0x10 } else { self.inner.ctrl8 & !0x10 };
        self.set_ctrl8(v)
    }

    /// Read the 24-bit step count.
    pub fn step_count(&mut self) -> Res<u32, I2C> {
        let mut b = [0u8; 3];
        self.inner.rd(REG_STEP_CNT, &mut b)?;
        Ok(b[0] as u32 | (b[1] as u32) << 8 | (b[2] as u32) << 16)
    }

    /// Clear the step count (CTRL9 `RESET_PEDOMETER`).
    pub fn reset_step_count<D: DelayNs>(&mut self, delay: &mut D) -> Res<(), I2C> {
        self.ctrl9(CMD_RESET_PEDOMETER, 100, delay)
    }

    /// Enter (`threshold_mg` 1–255) or leave (`0`) wake-on-motion mode.
    ///
    /// Uses accelerometer ±2 g at ODR code 12 (128 Hz). `interrupt_pin` is 1 or 2,
    /// `initial_level` the pin level after enabling, `blanking_samples` 0–63.
    pub fn set_wake_on_motion<D: DelayNs>(&mut self, threshold_mg: u8, interrupt_pin: u8, initial_level: u8,
                                          blanking_samples: u8, delay: &mut D) -> Res<(), I2C> {
        self.sensors_off(delay)?;
        if threshold_mg == 0 {
            self.cal_cmd(CMD_WRITE_WOM, &[0, 0], 100, delay)?;
            self.int_sources &= !SOURCE_WAKE_ON_MOTION;
            return self.update_int_pins();
        }
        self.inner.accel_fs = 0;
        self.inner.accel_odr = 12;
        self.inner.w(REG_CTRL2, 0x0C)?;
        self.wom_pin = if interrupt_pin == 2 { 2 } else { 1 };
        let sel = (if self.wom_pin == 2 { 0x40 } else { 0 }) | (if initial_level != 0 { 0x80 } else { 0 });
        self.cal_cmd(CMD_WRITE_WOM, &[threshold_mg, sel | (blanking_samples & 0x3F)], 100, delay)?;
        self.int_sources |= SOURCE_WAKE_ON_MOTION;
        self.update_int_pins()?;
        let v = (self.inner.ctrl7 & !0x03) | 0x01;
        self.set_ctrl7(v)?;
        delay.delay_ms(10);
        Ok(())
    }

    fn offset_cmd<D: DelayNs>(&mut self, cmd: u8, codes: [i16; 3], delay: &mut D) -> Res<(), I2C> {
        let prev = self.sensors_off(delay)?;
        let mut p = [0u8; 6];
        for (i, c) in codes.iter().enumerate() {
            p[2 * i] = *c as u8;
            p[2 * i + 1] = (*c >> 8) as u8;
        }
        let r = self.cal_cmd(cmd, &p, 100, delay);
        self.sensors_restore(prev, delay)?;
        r
    }

    /// Apply an accelerometer offset in m/s² (signed 4.12 g; volatile).
    pub fn set_accel_offset<D: DelayNs>(&mut self, x: f32, y: f32, z: f32, delay: &mut D) -> Res<(), I2C> {
        let c = |v: f32| round(v / G * 4096.0) as i16;
        self.offset_cmd(CMD_ACCEL_OFFSET, [c(x), c(y), c(z)], delay)
    }

    /// Apply a gyroscope offset in rad/s (signed 11.5 dps; volatile).
    pub fn set_gyro_offset<D: DelayNs>(&mut self, x: f32, y: f32, z: f32, delay: &mut D) -> Res<(), I2C> {
        let c = |v: f32| round(v / DEG_TO_RAD * 32.0) as i16;
        self.offset_cmd(CMD_GYRO_OFFSET, [c(x), c(y), c(z)], delay)
    }

    /// Run gyroscope calibration-on-demand (about 1.5 s; keep the device still).
    ///
    /// Returns `(success, cod_status)`.
    pub fn calibrate_gyro<D: DelayNs>(&mut self, delay: &mut D) -> Res<(bool, u8), I2C> {
        let prev = self.sensors_off(delay)?;
        let r = self.ctrl9(CMD_COD, 3000, delay);
        let status = self.inner.r1(REG_COD_STATUS);
        self.sensors_restore(prev, delay)?;
        r?;
        let status = status?;
        Ok((status == 0, status))
    }

    /// Read the raw X/Y/Z gyroscope gains, valid right after [`Self::calibrate_gyro`].
    pub fn get_gyro_gains(&mut self) -> Res<(u16, u16, u16), I2C> {
        let mut b = [0u8; 6];
        self.inner.rd(REG_DVX_L, &mut b)?;
        Ok((u16::from_le_bytes([b[0], b[1]]), u16::from_le_bytes([b[2], b[3]]), u16::from_le_bytes([b[4], b[5]])))
    }

    /// Restore previously saved gyroscope gains.
    pub fn set_gyro_gains<D: DelayNs>(&mut self, x: u16, y: u16, z: u16, delay: &mut D) -> Res<(), I2C> {
        let prev = self.sensors_off(delay)?;
        let r = self.cal_cmd(CMD_APPLY_GYRO_GAINS,
            &[x as u8, (x >> 8) as u8, y as u8, (y >> 8) as u8, z as u8, (z >> 8) as u8], 100, delay);
        self.sensors_restore(prev, delay)?;
        r
    }

    fn self_test<D: DelayNs>(&mut self, ctrl_reg: u8, saved: u8, scale: f32, delay: &mut D) -> Res<[f32; 3], I2C> {
        let prev = self.sensors_off(delay)?;
        let r = (|| -> Res<[u8; 6], I2C> {
            self.inner.w(ctrl_reg, saved | 0x80)?;
            self.wait_status(0x01, true, 3000, delay)?;
            let mut b = [0u8; 6];
            self.inner.rd(REG_DVX_L, &mut b)?;
            self.inner.w(ctrl_reg, saved)?;
            self.wait_status(0x01, false, 3000, delay)?;
            Ok(b)
        })();
        self.sensors_restore(prev, delay)?;
        let b = r?;
        Ok([le16(&b, 0) as f32 / scale, le16(&b, 2) as f32 / scale, le16(&b, 4) as f32 / scale])
    }

    /// Run the accelerometer self-test. Returns `(passed, [x, y, z])` in m/s²; passes when every
    /// axis exceeds 200 mg. The sensors must be at rest.
    pub fn self_test_accel<D: DelayNs>(&mut self, delay: &mut D) -> Res<(bool, [f32; 3]), I2C> {
        let saved = (self.inner.accel_fs << 4) | self.inner.accel_odr;
        let g = self.self_test(REG_CTRL2, saved, 2048.0, delay)?;
        Ok((g.iter().all(|v| v.abs() > 0.2), [g[0] * G, g[1] * G, g[2] * G]))
    }

    /// Run the gyroscope self-test. Returns `(passed, [x, y, z])` in rad/s; passes when every axis
    /// exceeds 300 dps.
    pub fn self_test_gyro<D: DelayNs>(&mut self, delay: &mut D) -> Res<(bool, [f32; 3]), I2C> {
        let saved = (self.inner.gyro_fs << 4) | self.inner.gyro_odr;
        let d = self.self_test(REG_CTRL3, saved, 16.0, delay)?;
        Ok((d.iter().all(|v| v.abs() > 300.0), [d[0] * DEG_TO_RAD, d[1] * DEG_TO_RAD, d[2] * DEG_TO_RAD]))
    }

    /// Read revision, firmware version and USID (CTRL9 `COPY_USID`): `(revision, firmware, usid)`.
    pub fn device_info<D: DelayNs>(&mut self, delay: &mut D) -> Res<(u8, [u8; 3], [u8; 6]), I2C> {
        self.ctrl9(CMD_COPY_USID, 100, delay)?;
        let revision = self.inner.r1(REG_REVISION_ID)?;
        let mut fw = [0u8; 3];
        self.inner.rd(REG_FW_VERSION, &mut fw[..2])?;
        fw[2] = self.inner.r1(REG_FW_VERSION + 2)?;
        let mut usid = [0u8; 6];
        self.inner.rd(REG_DVX_L, &mut usid)?;
        Ok((revision, fw, usid))
    }

    /// Control the internal 200 kΩ pull-ups (CTRL9 `SET_RPU`); `false` disables a group.
    /// Disabling `i2c` on a bus without external pull-ups breaks the bus.
    pub fn set_pullups<D: DelayNs>(&mut self, aux: bool, sdx: bool, cs: bool, i2c: bool,
                                   delay: &mut D) -> Res<(), I2C> {
        let v = u8::from(!aux) | (u8::from(!sdx) << 1) | (u8::from(!cs) << 2) | (u8::from(!i2c) << 3);
        self.cal_cmd(CMD_SET_RPU, &[v], 100, delay)
    }

    /// Soft reset and wait 15 ms; the sensors are off afterwards.
    pub fn reset<D: DelayNs>(&mut self, delay: &mut D) -> Res<(), I2C> {
        self.inner.w(REG_RESET, RESET_CMD)?;
        delay.delay_ms(15);
        self.inner.ctrl1 = 0x40;
        self.inner.ctrl7 = 0x00;
        self.inner.ctrl8 = 0x80;
        self.inner.w(REG_CTRL1, 0x40)?;
        self.inner.w(REG_CTRL8, 0x80)?;
        self.inner.sync = false;
        self.int_sources = 0;
        Ok(())
    }

    fn source_pin(&self, source: u16) -> u8 {
        match source {
            SOURCE_DATA_READY => 2,
            SOURCE_FIFO_WATERMARK | SOURCE_FIFO_FULL => self.fifo_pin,
            SOURCE_WAKE_ON_MOTION => self.wom_pin,
            _ => self.activity_pin,
        }
    }

    fn update_int_pins(&mut self) -> Res<(), I2C> {
        let mut pins = 0u8;
        for bit in 0..9 {
            let s = 1u16 << bit;
            if self.int_sources & s != 0 {
                pins |= 1 << (self.source_pin(s) - 1);
            }
        }
        let mut v = self.inner.ctrl1 & !0x18;
        v |= (if pins & 1 != 0 { 0x08 } else { 0 }) | (if pins & 2 != 0 { 0x10 } else { 0 });
        v = if self.fifo_pin == 1 { v | 0x04 } else { v & !0x04 };
        if v != self.inner.ctrl1 {
            self.set_ctrl1(v)?;
        }
        Ok(())
    }

    /// Route the FIFO interrupt (`CTRL1.FIFO_INT_SEL`) and the motion-engine group
    /// (`CTRL8.ACTIVITY_INT_SEL`) to INT1 (`1`) or INT2 (`2`).
    pub fn set_interrupt_pins(&mut self, fifo_pin: u8, activity_pin: u8) -> Res<(), I2C> {
        self.fifo_pin = if fifo_pin == 1 { 1 } else { 2 };
        self.activity_pin = if activity_pin == 1 { 1 } else { 2 };
        let v = if self.activity_pin == 1 { self.inner.ctrl8 | 0x40 } else { self.inner.ctrl8 & !0x40 };
        self.set_ctrl8(v)?;
        self.update_int_pins()
    }

    /// Enable one interrupt source (a `SOURCE_*` constant).
    pub fn enable_interrupt(&mut self, source: u16) -> Res<(), I2C> {
        if source == SOURCE_DATA_READY {
            let v = self.inner.ctrl7 & !0x20;
            self.set_ctrl7(v)?;
        } else if source == SOURCE_FIFO_WATERMARK {
            self.inner.w(REG_FIFO_WTM_TH, self.inner.fifo_wtm)?;
        } else if engine_bit(source) != 0 {
            let v = self.inner.ctrl8 | engine_bit(source);
            self.set_ctrl8(v)?;
        }
        self.int_sources |= source;
        self.update_int_pins()
    }

    /// Disable one interrupt source (a `SOURCE_*` constant).
    pub fn disable_interrupt(&mut self, source: u16) -> Res<(), I2C> {
        if source == SOURCE_DATA_READY {
            let v = self.inner.ctrl7 | 0x20;
            self.set_ctrl7(v)?;
        } else if source == SOURCE_FIFO_WATERMARK {
            self.inner.w(REG_FIFO_WTM_TH, 0)?;
        } else if engine_bit(source) != 0 {
            let v = self.inner.ctrl8 & !engine_bit(source);
            self.set_ctrl8(v)?;
        }
        self.int_sources &= !source;
        self.update_int_pins()
    }

    /// Read `STATUS0`, `STATUS1` and `FIFO_STATUS` (clearing what clears on read) and return the
    /// combined `SOURCE_*` bits.
    pub fn poll_interrupt(&mut self) -> Res<u16, I2C> {
        let mut s = [0u8; 2];
        self.inner.rd(REG_STATUS0, &mut s)?;
        let fs = self.inner.r1(REG_FIFO_STATUS)?;
        let mut st = 0u16;
        if s[0] & 0x03 != 0 { st |= SOURCE_DATA_READY; }
        if fs & 0x40 != 0 { st |= SOURCE_FIFO_WATERMARK; }
        if fs & 0x80 != 0 { st |= SOURCE_FIFO_FULL; }
        if s[1] & 0x20 != 0 { st |= SOURCE_ANY_MOTION; }
        if s[1] & 0x40 != 0 { st |= SOURCE_NO_MOTION; }
        if s[1] & 0x80 != 0 { st |= SOURCE_SIGNIFICANT_MOTION; }
        if s[1] & 0x10 != 0 { st |= SOURCE_STEP; }
        if s[1] & 0x02 != 0 { st |= SOURCE_TAP; }
        if s[1] & 0x04 != 0 { st |= SOURCE_WAKE_ON_MOTION; }
        Ok(st)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::{ErrorKind, ErrorType, Operation};
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use std::collections::{HashMap, VecDeque};

    const ADDR: u8 = 0x6B;

    /// I²C fake that models the CTRL9 handshake, the self-test `Avail` flag and the `FIFO_DATA` port.
    struct QmiI2c {
        regs: HashMap<u8, u8>,
        log: Vec<Vec<u8>>,
        cmds: Vec<(u8, [u8; 8])>,
        fifo: VecDeque<u8>,
    }

    impl QmiI2c {
        fn new() -> Self {
            let mut s = Self { regs: HashMap::new(), log: Vec::new(), cmds: Vec::new(), fifo: VecDeque::new() };
            s.set(REG_WHO_AM_I, &[WHO_AM_I_VALUE]);
            s
        }
        fn set(&mut self, reg: u8, values: &[u8]) {
            for (i, v) in values.iter().enumerate() {
                self.regs.insert(reg + i as u8, *v);
            }
        }
        fn wrote(&self, bytes: &[u8]) -> bool {
            self.log.iter().any(|d| d.as_slice() == bytes)
        }
        fn last(&self, reg: u8) -> i32 {
            self.log.iter().filter(|d| d.len() == 2 && d[0] == reg).last().map_or(-1, |d| d[1] as i32)
        }
    }

    impl ErrorType for QmiI2c {
        type Error = ErrorKind;
    }

    impl I2c for QmiI2c {
        fn transaction(&mut self, _address: u8, operations: &mut [Operation<'_>]) -> Result<(), ErrorKind> {
            let mut reg = 0u8;
            for op in operations.iter_mut() {
                match op {
                    Operation::Write(data) => {
                        self.log.push(data.to_vec());
                        reg = data[0];
                        for (i, v) in data[1..].iter().enumerate() {
                            self.regs.insert(reg + i as u8, *v);
                        }
                        if data.len() == 2 && (reg == 0x03 || reg == 0x04) {
                            let st = *self.regs.get(&0x2D).unwrap_or(&0);
                            self.regs.insert(0x2D, if data[1] & 0x80 != 0 { st | 0x01 } else { st & !0x01 });
                        }
                        if data.len() == 2 && reg == 0x0A {
                            let st = *self.regs.get(&0x2D).unwrap_or(&0);
                            if data[1] != 0 {
                                let mut cal = [0u8; 8];
                                for (i, c) in cal.iter_mut().enumerate() {
                                    *c = *self.regs.get(&(0x0B + i as u8)).unwrap_or(&0);
                                }
                                self.cmds.push((data[1], cal));
                                self.regs.insert(0x2D, st | 0x80);
                            } else {
                                self.regs.insert(0x2D, st & !0x80);
                            }
                        }
                    }
                    Operation::Read(buf) => {
                        for (i, b) in buf.iter_mut().enumerate() {
                            *b = if reg == REG_FIFO_DATA {
                                self.fifo.pop_front().unwrap_or(0)
                            } else {
                                *self.regs.get(&reg.wrapping_add(i as u8)).unwrap_or(&0)
                            };
                        }
                    }
                }
            }
            Ok(())
        }
    }

    fn le(vals: &[i16]) -> Vec<u8> {
        vals.iter().flat_map(|v| v.to_le_bytes()).collect()
    }

    fn make() -> Qmi8658aFull<QmiI2c> {
        Qmi8658aFull::new(QmiI2c::new(), ADDR, &mut NoopDelay::new()).expect("init")
    }

    fn near(a: f32, b: f32, tol: f32) -> bool {
        (a - b).abs() < tol
    }

    #[test]
    fn init_sequence() {
        let s = make();
        let log = &s.inner.i2c.log;
        assert_eq!(log[0], vec![REG_RESET, 0xB0]);
        for w in [[0x02u8, 0x40], [0x09, 0x80], [0x03, 0x05], [0x04, 0x45], [0x06, 0x77]] {
            assert!(s.inner.i2c.wrote(&w), "init write {:?}", w);
        }
        assert_eq!(log.iter().filter(|d| d.len() == 2).last().unwrap(), &vec![0x08, 0x03]);
    }

    #[test]
    fn who_am_i_mismatch() {
        let mut bus = QmiI2c::new();
        bus.set(REG_WHO_AM_I, &[0x00]);
        let r = Qmi8658aFull::new(bus, ADDR, &mut NoopDelay::new());
        assert!(matches!(r, Err(Qmi8658aError::BadWhoAmI(0x00))));
    }

    #[test]
    fn accel_gyro_conversion() {
        let mut s = make();
        s.inner.i2c.set(REG_AX_L, &le(&[16384, -8192, 0]));
        let (x, y, z) = s.accel().unwrap();
        assert!(near(x, G, 1e-3) && near(y, -G / 2.0, 1e-3) && near(z, 0.0, 1e-3));
        s.inner.i2c.set(REG_GX_L, &le(&[128, -256, 64]));
        let (x, y, z) = s.gyro().unwrap();
        assert!(near(x, DEG_TO_RAD, 1e-5) && near(y, -2.0 * DEG_TO_RAD, 1e-5) && near(z, 0.5 * DEG_TO_RAD, 1e-5));
    }

    #[test]
    fn raw_read_all_temperature() {
        let mut s = make();
        s.inner.i2c.set(REG_TEMP_L, &le(&[0x1980, 2048, 0, -2048, 16, 32, -64]));
        assert_eq!(s.accel_raw().unwrap(), (2048, 0, -2048));
        assert_eq!(s.gyro_raw().unwrap(), (16, 32, -64));
        let (a, g, t) = s.read_all().unwrap();
        assert!(near(t, 25.5, 1e-3));
        assert!(near(a.0, 2048.0 / 16384.0 * G, 1e-3) && near(g.2, -64.0 / 128.0 * DEG_TO_RAD, 1e-5));
        assert!(near(s.temperature().unwrap(), 25.5, 1e-3));
    }

    #[test]
    fn timestamp_and_data_ready() {
        let mut s = make();
        s.inner.i2c.set(REG_TIMESTAMP, &[1, 2, 3]);
        assert_eq!(s.timestamp().unwrap(), 0x030201);
        s.inner.i2c.set(REG_STATUS0, &[0x00]);
        assert!(!s.data_ready().unwrap());
        s.inner.i2c.set(REG_STATUS0, &[0x02]);
        assert!(s.data_ready().unwrap());
    }

    #[test]
    fn configure_updates_sensitivity() {
        let mut s = make();
        s.configure_accel(2, 4).unwrap();
        assert_eq!(s.inner.i2c.last(REG_CTRL2), 0x24);
        s.inner.i2c.set(REG_AX_L, &le(&[4096, 0, 0]));
        assert!(near(s.accel().unwrap().0, G, 1e-3));
        s.configure_gyro(7, 3).unwrap();
        assert_eq!(s.inner.i2c.last(REG_CTRL3), 0x73);
        s.inner.i2c.set(REG_GX_L, &le(&[16, 0, 0]));
        assert!(near(s.gyro().unwrap().0, DEG_TO_RAD, 1e-5));
        assert_eq!(s.configure_accel(4, 5), Err(Qmi8658aError::InvalidArgument));
        s.configure_filters(true, 3, false, 1).unwrap();
        assert_eq!(s.inner.i2c.last(REG_CTRL5), 0x27);
    }

    #[test]
    fn sensors_and_power() {
        let mut s = make();
        let mut d = NoopDelay::new();
        s.set_sensors(true, false, false).unwrap();
        assert_eq!(s.inner.i2c.last(REG_CTRL7), 0x01);
        s.set_sensors(true, true, true).unwrap();
        assert_eq!(s.inner.i2c.last(REG_CTRL7), 0x13);
        s.set_sensors(true, true, false).unwrap();
        s.set_power_down(true, &mut d).unwrap();
        assert_eq!(s.inner.i2c.last(REG_CTRL1), 0x41);
        assert_eq!(s.inner.i2c.last(REG_CTRL7), 0x00);
        s.set_power_down(false, &mut d).unwrap();
        assert_eq!(s.inner.i2c.last(REG_CTRL1), 0x40);
    }

    #[test]
    fn sync_sample() {
        let mut s = make();
        let mut d = NoopDelay::new();
        s.set_sync_sample(true, &mut d).unwrap();
        assert!(s.inner.i2c.cmds.last().map_or(false, |c| c.0 == 0x12 && c.1[0] == 1));
        assert!(s.inner.i2c.wrote(&[0x08, 0x83]));
        s.inner.i2c.regs.insert(0x2D, 0x03);
        s.inner.i2c.set(REG_AX_L, &le(&[16384, 0, 0]));
        assert!(near(s.accel().unwrap().0, G, 1e-3));
        s.set_sync_sample(false, &mut d).unwrap();
        assert_eq!(s.inner.i2c.last(REG_CTRL7), 0x03);
        assert!(s.inner.i2c.cmds.last().map_or(false, |c| c.0 == 0x12 && c.1[0] == 0));
    }

    #[test]
    fn fifo() {
        let mut s = make();
        let mut d = NoopDelay::new();
        s.configure_fifo(2, 1, 16).unwrap();
        assert!(s.inner.i2c.wrote(&[0x14, 0x06]) && s.inner.i2c.wrote(&[0x13, 0x10]));
        s.inner.i2c.set(REG_FIFO_STATUS, &[0x00]);
        s.inner.i2c.set(REG_FIFO_SMPL_CNT, &[12]);
        assert_eq!(s.fifo_count().unwrap(), 24);
        assert_eq!(s.fifo_status().unwrap(), 0);
        s.inner.i2c.fifo = (0u8..24).collect();
        s.inner.i2c.cmds.clear();
        let mut buf = [0u8; 64];
        let n = s.read_fifo(&mut buf, &mut d).unwrap();
        assert_eq!(n, 24);
        assert!(buf[..24].iter().enumerate().all(|(i, v)| *v as usize == i));
        assert_eq!(s.inner.i2c.cmds.len(), 1);
        assert_eq!(s.inner.i2c.cmds[0].0, 0x05);
        assert!(s.inner.i2c.wrote(&[0x02, 0x00]));
        assert_eq!(s.inner.i2c.last(REG_CTRL1), 0x40);
        assert_eq!(s.inner.i2c.last(REG_FIFO_CTRL), 0x06);
        s.inner.i2c.fifo = (0u8..24).collect();
        let mut small = [0u8; 10];
        assert_eq!(s.read_fifo(&mut small, &mut d).unwrap(), 10);
        s.reset_fifo(&mut d).unwrap();
        assert_eq!(s.inner.i2c.cmds.last().unwrap().0, 0x04);
        s.configure_fifo(0, 3, 0).unwrap();
        assert_eq!(s.inner.i2c.last(REG_CTRL7), 0x03);
    }

    #[test]
    fn motion_engines() {
        let mut s = make();
        let mut d = NoopDelay::new();
        s.configure_any_motion(0.25, 3, 0x07, false, &mut d).unwrap();
        let c = &s.inner.i2c.cmds;
        assert_eq!(c.len(), 2);
        assert!(c[0].0 == 0x0E && c[1].0 == 0x0E);
        assert!(c[0].1[0] == 8 && c[0].1[3] == 4 && c[0].1[6] == 0xF7 && c[0].1[7] == 0x10);
        assert!(c[1].1[0] == 3 && c[1].1[1] == 30 && c[1].1[2] == 150 && c[1].1[4] == (600 & 0xFF) as u8
            && c[1].1[5] == (600 >> 8) as u8 && c[1].1[7] == 0x20);
        assert!(s.inner.i2c.wrote(&[0x08, 0x00]));
        assert_eq!(s.inner.i2c.last(REG_CTRL7), 0x03);
        s.configure_significant_motion(100, 300, &mut d).unwrap();
        let last = s.inner.i2c.cmds.last().unwrap();
        assert!(last.1[2] == 100 && last.1[4] == (300 & 0xFF) as u8 && last.1[5] == 1);
        s.enable_motion(true, true, false).unwrap();
        assert_eq!(s.inner.i2c.last(REG_CTRL8), 0x86);
        s.enable_motion(false, false, false).unwrap();
        assert_eq!(s.inner.i2c.last(REG_CTRL8), 0x80);
    }

    #[test]
    fn tap_and_pedometer() {
        let mut s = make();
        let mut d = NoopDelay::new();
        s.configure_tap(0, 20, 50, 250, 0.0625, 0.25, 0.8, 0.4, &mut d).unwrap();
        let c = &s.inner.i2c.cmds;
        assert!(c.len() == 2 && c[0].0 == 0x0C);
        assert!(c[0].1[0] == 20 && c[0].1[1] == 0 && c[0].1[2] == 50 && c[0].1[4] == 250 && c[0].1[7] == 0x10);
        assert!(c[1].1[0] == 8 && c[1].1[1] == 32 && c[1].1[2] == 0x33 && c[1].1[3] == 0x03
            && c[1].1[4] == 0x9A && c[1].1[5] == 0x01 && c[1].1[7] == 0x20);
        s.enable_tap(true).unwrap();
        assert_eq!(s.inner.i2c.last(REG_CTRL8), 0x81);
        s.inner.i2c.set(REG_TAP_STATUS, &[0xA2]);
        assert_eq!(s.tap_status().unwrap(), (2, 2, true));
        s.enable_tap(false).unwrap();

        s.inner.i2c.cmds.clear();
        s.configure_pedometer(50, 200.0, 100.0, 200, 20, 10, 0, 4, &mut d).unwrap();
        let c = &s.inner.i2c.cmds;
        assert!(c.len() == 2 && c[0].0 == 0x0D);
        assert!(c[0].1[0] == 50 && c[0].1[2] == 205 && c[0].1[4] == 102 && c[0].1[7] == 0x10);
        assert!(c[1].1[0] == 200 && c[1].1[2] == 20 && c[1].1[3] == 10 && c[1].1[5] == 4 && c[1].1[7] == 0x20);
        s.enable_pedometer(true).unwrap();
        assert_eq!(s.inner.i2c.last(REG_CTRL8), 0x90);
        s.inner.i2c.set(REG_STEP_CNT, &[0x34, 0x12, 0x00]);
        assert_eq!(s.step_count().unwrap(), 0x1234);
        s.reset_step_count(&mut d).unwrap();
        assert_eq!(s.inner.i2c.cmds.last().unwrap().0, 0x0F);
    }

    #[test]
    fn wake_on_motion() {
        let mut s = make();
        let mut d = NoopDelay::new();
        s.set_wake_on_motion(100, 2, 1, 5, &mut d).unwrap();
        assert!(s.inner.i2c.wrote(&[0x03, 0x0C]));
        let c = s.inner.i2c.cmds.last().unwrap();
        assert!(c.0 == 0x08 && c.1[0] == 100 && c.1[1] == 0xC5);
        assert!(s.inner.i2c.wrote(&[0x08, 0x01]) && s.inner.i2c.last(REG_CTRL1) & 0x10 != 0);
        s.set_wake_on_motion(0, 1, 0, 0, &mut d).unwrap();
        let c = s.inner.i2c.cmds.last().unwrap();
        assert!(c.0 == 0x08 && c.1[0] == 0 && s.inner.i2c.last(REG_CTRL1) & 0x18 == 0);
    }

    #[test]
    fn offsets_calibration_selftest() {
        let mut s = make();
        let mut d = NoopDelay::new();
        s.set_accel_offset(G, 0.0, -G, &mut d).unwrap();
        let c = s.inner.i2c.cmds.last().unwrap();
        assert!(c.0 == 0x09 && c.1[0] == 0x00 && c.1[1] == 0x10 && c.1[4] == 0x00 && c.1[5] == 0xF0);
        s.set_gyro_offset(DEG_TO_RAD, 0.0, 0.0, &mut d).unwrap();
        let c = s.inner.i2c.cmds.last().unwrap();
        assert!(c.0 == 0x0A && c.1[0] == 32 && c.1[1] == 0);

        s.inner.i2c.set(REG_COD_STATUS, &[0x00]);
        assert_eq!(s.calibrate_gyro(&mut d).unwrap(), (true, 0));
        assert_eq!(s.inner.i2c.cmds.last().unwrap().0, 0xA2);
        s.inner.i2c.set(REG_COD_STATUS, &[0x01]);
        assert_eq!(s.calibrate_gyro(&mut d).unwrap(), (false, 1));
        s.inner.i2c.set(REG_DVX_L, &le(&[1000, 2000, 3000]));
        assert_eq!(s.get_gyro_gains().unwrap(), (1000, 2000, 3000));
        s.set_gyro_gains(1000, 2000, 3000, &mut d).unwrap();
        let c = s.inner.i2c.cmds.last().unwrap();
        assert!(c.0 == 0xAA && c.1[0] == 0xE8 && c.1[1] == 0x03 && c.1[4] == 0xB8 && c.1[5] == 0x0B);

        s.inner.i2c.set(REG_DVX_L, &le(&[2048, 2048, -2048]));
        let (ok, v) = s.self_test_accel(&mut d).unwrap();
        assert!(ok && near(v[0], G, 1e-3));
        s.inner.i2c.set(REG_DVX_L, &le(&[16 * 400, 16 * 400, 16 * 400]));
        let (ok, v) = s.self_test_gyro(&mut d).unwrap();
        assert!(ok && near(v[0], 400.0 * DEG_TO_RAD, 1e-3));
        s.inner.i2c.set(REG_DVX_L, &le(&[16 * 100, 16 * 400, 16 * 400]));
        assert!(!s.self_test_gyro(&mut d).unwrap().0);
    }

    #[test]
    fn device_info_pullups_reset() {
        let mut s = make();
        let mut d = NoopDelay::new();
        s.inner.i2c.set(REG_REVISION_ID, &[0x7C]);
        s.inner.i2c.set(REG_FW_VERSION, &[1, 2, 0, 3]);
        s.inner.i2c.set(0x4B, &[3]);
        s.inner.i2c.set(REG_DVX_L, &[1, 2, 3, 4, 5, 6]);
        assert_eq!(s.device_info(&mut d).unwrap(), (0x7C, [1, 2, 3], [1, 2, 3, 4, 5, 6]));
        assert_eq!(s.inner.i2c.cmds.last().unwrap().0, 0x10);
        s.set_pullups(false, true, true, false, &mut d).unwrap();
        let c = s.inner.i2c.cmds.last().unwrap();
        assert!(c.0 == 0x11 && c.1[0] == 0x09);
        s.reset(&mut d).unwrap();
        assert!(s.inner.i2c.wrote(&[REG_RESET, 0xB0]));
    }

    #[test]
    fn interrupts() {
        let mut s = make();
        s.set_interrupt_pins(1, 1).unwrap();
        assert!(s.inner.i2c.last(REG_CTRL8) & 0x40 != 0);
        s.enable_interrupt(SOURCE_TAP).unwrap();
        assert!(s.inner.i2c.last(REG_CTRL8) & 0x01 != 0 && s.inner.i2c.last(REG_CTRL1) & 0x08 != 0);
        s.enable_interrupt(SOURCE_DATA_READY).unwrap();
        assert!(s.inner.i2c.last(REG_CTRL1) & 0x10 != 0);
        s.disable_interrupt(SOURCE_DATA_READY).unwrap();
        assert!(s.inner.i2c.last(REG_CTRL1) & 0x10 == 0 && s.inner.i2c.last(REG_CTRL7) & 0x20 != 0);
        s.disable_interrupt(SOURCE_TAP).unwrap();
        assert!(s.inner.i2c.last(REG_CTRL1) & 0x08 == 0);
        s.set_interrupt_pins(2, 2).unwrap();
        s.inner.i2c.set(REG_STATUS0, &[0x01, 0xA2]);
        s.inner.i2c.set(REG_FIFO_STATUS, &[0xC0]);
        let expect = SOURCE_DATA_READY | SOURCE_FIFO_WATERMARK | SOURCE_FIFO_FULL | SOURCE_ANY_MOTION
            | SOURCE_SIGNIFICANT_MOTION | SOURCE_TAP;
        assert_eq!(s.poll_interrupt().unwrap(), expect);
    }

    #[test]
    fn minimal_has_core_api() {
        let mut m = Qmi8658aMinimal::new(QmiI2c::new(), ADDR, &mut NoopDelay::new()).unwrap();
        m.i2c.set(REG_AX_L, &le(&[16384, 0, 0]));
        assert!(near(m.accel().unwrap().0, G, 1e-3));
    }
}
