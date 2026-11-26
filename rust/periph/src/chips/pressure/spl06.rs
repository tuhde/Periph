//! SPL06-007 — barometric pressure and temperature sensor (Goertek).
//!
//! Communicates over I²C at `0x77` (`SDO` high or open, default) or `0x76` (`SDO` low). The host
//! reads raw 24-bit two's-complement results and compensates them with the 18-byte factory
//! coefficient block (c0, c1, c00, c10, c01, c11, c20, c21, c30). Construction does no bus access;
//! `init()` checks `ID` (`0x10`), waits for `SENSOR_RDY` / `COEF_RDY`, reads the coefficients and
//! writes `TMP_CFG = 0x80` (external temperature sensor, 1×), `PRS_CFG = 0x04` (16×) and
//! `CFG_REG = 0x04` (`P_SHIFT`).
//!
//! The interrupt output shares the `SDO` pin; `INT_HL` stays at its reset value 0 (active low).
//! The Rust driver exposes [`Spl06Full::poll_interrupt`] (no callback subscription): the caller is
//! responsible for wiring the INT pin into an ISR or polling loop and calling `poll_interrupt`.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register::{read_register, to_signed, write_register};

const REG_PRS_B2: u8 = 0x00;
const REG_TMP_B2: u8 = 0x03;
const REG_PRS_CFG: u8 = 0x06;
const REG_TMP_CFG: u8 = 0x07;
const REG_MEAS_CFG: u8 = 0x08;
const REG_CFG_REG: u8 = 0x09;
const REG_INT_STS: u8 = 0x0A;
const REG_FIFO_STS: u8 = 0x0B;
const REG_RESET: u8 = 0x0C;
const REG_ID: u8 = 0x0D;
const REG_COEF: u8 = 0x10;

const CHIP_ID: u8 = 0x10;
const MEAS_COEF_RDY: u8 = 0x80;
const MEAS_SENSOR_RDY: u8 = 0x40;
const MEAS_TMP_RDY: u8 = 0x20;
const MEAS_PRS_RDY: u8 = 0x10;
const CMD_IDLE: u8 = 0x00;
const CMD_PRESSURE: u8 = 0x01;
const CMD_TEMPERATURE: u8 = 0x02;
const CFG_INT_HL: u8 = 0x80;
const CFG_INT_FIFO: u8 = 0x40;
const CFG_INT_PRS: u8 = 0x20;
const CFG_INT_TMP: u8 = 0x10;
const CFG_T_SHIFT: u8 = 0x08;
const CFG_P_SHIFT: u8 = 0x04;
const CFG_FIFO_EN: u8 = 0x02;
const TMP_EXT: u8 = 0x80;

/// Scale factors kP / kT indexed by log2(oversampling) (datasheet Table 4).
const SCALE: [f32; 8] = [524288.0, 1572864.0, 3670016.0, 7864320.0, 253952.0, 516096.0, 1040384.0, 2088960.0];
/// Typical measurement time in ms indexed by log2(oversampling), rounded up.
const MEAS_MS: [u32; 8] = [4, 6, 9, 15, 28, 54, 105, 207];

/// Interrupt source: a pressure result is ready (`INT_STS.INT_PRS`).
pub const SOURCE_PRESSURE: u8 = 0x01;
/// Interrupt source: a temperature result is ready (`INT_STS.INT_TMP`).
pub const SOURCE_TEMPERATURE: u8 = 0x02;
/// Interrupt source: the FIFO is full (`INT_STS.INT_FIFO_FULL`).
pub const SOURCE_FIFO_FULL: u8 = 0x04;

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// `ID` did not return `0x10`.
    InvalidChip(u8),
    /// The chip did not become ready or finish a measurement in time.
    Timeout,
    /// An argument is outside the supported range (oversampling or rate not a power of two 1-128).
    InvalidArgument,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// Compensated measurement.
#[derive(Debug, PartialEq, Clone, Copy)]
pub struct Spl06Measurement {
    /// Temperature in degrees Celsius.
    pub temperature_c: f32,
    /// Pressure in pascals.
    pub pressure_pa: f32,
}

/// Decoded calibration coefficients (two's complement, sign-extended).
#[derive(Debug, PartialEq, Eq, Clone, Copy, Default)]
pub struct Spl06Coefficients {
    /// 12-bit temperature offset.
    pub c0: i32,
    /// 12-bit temperature gain.
    pub c1: i32,
    /// 20-bit pressure offset.
    pub c00: i32,
    /// 20-bit pressure linear term.
    pub c10: i32,
    /// 16-bit temperature dependency.
    pub c01: i32,
    /// 16-bit cross term.
    pub c11: i32,
    /// 16-bit pressure quadratic term.
    pub c20: i32,
    /// 16-bit cross quadratic term.
    pub c21: i32,
    /// 16-bit pressure cubic term.
    pub c30: i32,
}

/// Kind of a FIFO entry (the LSB of the raw value).
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum Spl06Kind {
    /// Pressure entry; the value is in pascals.
    Pressure,
    /// Temperature entry; the value is in degrees Celsius.
    Temperature,
}

/// One FIFO entry.
#[derive(Debug, PartialEq, Clone, Copy)]
pub struct Spl06FifoEntry {
    /// Pressure or temperature.
    pub kind: Spl06Kind,
    /// Compensated value: pascals or degrees Celsius.
    pub value: f32,
}

impl Default for Spl06FifoEntry {
    fn default() -> Self {
        Self { kind: Spl06Kind::Temperature, value: 0.0 }
    }
}

/// SPL06-007 minimal driver — one call returns compensated temperature and pressure.
///
/// The chip stays in standby between readings; each `read` runs one temperature and one pressure
/// measurement in command mode (16× pressure, 1× temperature, external temperature sensor).
pub struct Spl06Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    coef: Spl06Coefficients,
    p_os: u8,
    t_os: u8,
    cfg: u8,
    last_traw: i32,
}

impl<I2C: I2c> Spl06Minimal<I2C> {
    /// Create a new `Spl06Minimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x77` or `0x76`).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, coef: Spl06Coefficients::default(), p_os: 4, t_os: 0, cfg: CFG_P_SHIFT, last_traw: 0 }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    /// Verify the chip, read the calibration coefficients and apply the defaults.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let id = read_reg(&mut self.i2c, self.addr, REG_ID)?;
        if id != CHIP_ID {
            return Err(Error::InvalidChip(id));
        }
        self.wait_ready(delay)?;
        self.read_coefficients()?;
        self.apply_defaults()
    }

    /// Measure temperature, then pressure (command mode), and compensate both.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<Spl06Measurement, Error<I2C::Error>> {
        let (praw, traw) = self.measure_raw(delay)?;
        Ok(Spl06Measurement { temperature_c: self.temperature(traw), pressure_pa: self.pressure(praw, traw) })
    }

    fn wait_ready<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let mask = MEAS_COEF_RDY | MEAS_SENSOR_RDY;
        for _ in 0..40 {
            if let Ok(m) = read_reg(&mut self.i2c, self.addr, REG_MEAS_CFG) {
                if m & mask == mask {
                    return Ok(());
                }
            }
            delay.delay_ms(5);
        }
        Err(Error::Timeout)
    }

    fn read_coefficients(&mut self) -> Result<(), Error<I2C::Error>> {
        let mut b = [0u8; 18];
        read_register(&mut self.i2c, self.addr, REG_COEF as u32, 1, &mut b)?;
        let u = |i: usize| b[i] as u32;
        self.coef = Spl06Coefficients {
            c0: to_signed((u(0) << 4) | (u(1) >> 4), 12),
            c1: to_signed(((u(1) & 0x0F) << 8) | u(2), 12),
            c00: to_signed((u(3) << 12) | (u(4) << 4) | (u(5) >> 4), 20),
            c10: to_signed(((u(5) & 0x0F) << 16) | (u(6) << 8) | u(7), 20),
            c01: to_signed((u(8) << 8) | u(9), 16),
            c11: to_signed((u(10) << 8) | u(11), 16),
            c20: to_signed((u(12) << 8) | u(13), 16),
            c21: to_signed((u(14) << 8) | u(15), 16),
            c30: to_signed((u(16) << 8) | u(17), 16),
        };
        Ok(())
    }

    fn apply_defaults(&mut self) -> Result<(), Error<I2C::Error>> {
        self.p_os = 4;
        self.t_os = 0;
        self.cfg = CFG_P_SHIFT;
        write_reg(&mut self.i2c, self.addr, REG_TMP_CFG, TMP_EXT)?;
        write_reg(&mut self.i2c, self.addr, REG_PRS_CFG, 0x04)?;
        write_reg(&mut self.i2c, self.addr, REG_CFG_REG, self.cfg)
    }

    fn measure<D: DelayNs>(&mut self, delay: &mut D, command: u8, flag: u8, os: u8) -> Result<i32, Error<I2C::Error>> {
        write_reg(&mut self.i2c, self.addr, REG_MEAS_CFG, command)?;
        let wait = MEAS_MS[os as usize];
        delay.delay_ms(wait);
        let limit = wait + 50;
        let mut waited = 0u32;
        while read_reg(&mut self.i2c, self.addr, REG_MEAS_CFG)? & flag == 0 {
            if waited > limit {
                return Err(Error::Timeout);
            }
            delay.delay_ms(2);
            waited += 2;
        }
        let mut b = [0u8; 3];
        let reg = if command == CMD_PRESSURE { REG_PRS_B2 } else { REG_TMP_B2 };
        read_register(&mut self.i2c, self.addr, reg as u32, 1, &mut b)?;
        Ok(raw24(&b))
    }

    fn measure_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<(i32, i32), Error<I2C::Error>> {
        let traw = self.measure(delay, CMD_TEMPERATURE, MEAS_TMP_RDY, self.t_os)?;
        let praw = self.measure(delay, CMD_PRESSURE, MEAS_PRS_RDY, self.p_os)?;
        self.last_traw = traw;
        Ok((praw, traw))
    }

    fn temperature(&self, traw: i32) -> f32 {
        compensate_t(&self.coef, traw as f32 / SCALE[self.t_os as usize])
    }

    fn pressure(&self, praw: i32, traw: i32) -> f32 {
        compensate_p(&self.coef, praw as f32 / SCALE[self.p_os as usize], traw as f32 / SCALE[self.t_os as usize])
    }
}

/// SPL06-007 full driver — oversampling and rate configuration, background mode, the 32-entry
/// FIFO, raw values and compensation, altitude, soft reset and the interrupt registers.
///
/// Owns a [`Spl06Minimal`] and delegates `init` and the command-mode measurement.
pub struct Spl06Full<I2C> {
    inner: Spl06Minimal<I2C>,
    p_rate: u8,
    t_rate: u8,
    continuous: u8,
}

impl<I2C: I2c> Spl06Full<I2C> {
    /// Create a new `Spl06Full`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x77` or `0x76`).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Spl06Minimal::new(i2c, addr), p_rate: 0, t_rate: 0, continuous: 0 }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Verify the chip, read the calibration coefficients and apply the defaults.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.init(delay)
    }

    /// Measure temperature, then pressure; in background mode return the latest results instead.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<Spl06Measurement, Error<I2C::Error>> {
        if self.continuous != 0 {
            return self.read_continuous(delay);
        }
        self.inner.read(delay)
    }

    /// Measure temperature and pressure and return the compensated pressure in pascals.
    pub fn read_pressure<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(self.read(delay)?.pressure_pa)
    }

    /// Measure and return the temperature in degrees Celsius.
    pub fn read_temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        if self.continuous != 0 {
            return Ok(self.read_continuous(delay)?.temperature_c);
        }
        let os = self.inner.t_os;
        let traw = self.inner.measure(delay, CMD_TEMPERATURE, MEAS_TMP_RDY, os)?;
        self.inner.last_traw = traw;
        Ok(self.inner.temperature(traw))
    }

    /// Raw 24-bit two's-complement results `(praw, traw)`. Command mode measures temperature, then
    /// pressure; background mode reads the latest results without starting a measurement.
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<(i32, i32), Error<I2C::Error>> {
        if self.continuous != 0 {
            let mut b = [0u8; 6];
            read_register(&mut self.inner.i2c, self.inner.addr, REG_PRS_B2 as u32, 1, &mut b)?;
            let (praw, traw) = (raw24(&b[0..3]), raw24(&b[3..6]));
            self.inner.last_traw = traw;
            return Ok((praw, traw));
        }
        self.inner.measure_raw(delay)
    }

    /// Write `PRS_CFG` and `P_SHIFT` (set above 8×).
    ///
    /// # Arguments
    /// * `oversampling` — 1, 2, 4, 8, 16, 32, 64 or 128 (default 16).
    /// * `rate` — background measurements per second: 1, 2, 4, ..., 128 (default 1).
    pub fn set_pressure_config(&mut self, oversampling: u8, rate: u8) -> Result<(), Error<I2C::Error>> {
        let os = log2(oversampling).ok_or(Error::InvalidArgument)?;
        let r = log2(rate).ok_or(Error::InvalidArgument)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PRS_CFG, (r << 4) | os)?;
        self.inner.p_os = os;
        self.p_rate = r;
        if oversampling > 8 {
            self.inner.cfg |= CFG_P_SHIFT;
        } else {
            self.inner.cfg &= !CFG_P_SHIFT;
        }
        self.write_cfg()
    }

    /// Write `TMP_CFG` (always `TMP_EXT = 1`) and `T_SHIFT` (set above 8×).
    ///
    /// # Arguments
    /// * `oversampling` — 1, 2, 4, 8, 16, 32, 64 or 128 (default 1).
    /// * `rate` — background measurements per second: 1, 2, 4, ..., 128 (default 1).
    pub fn set_temperature_config(&mut self, oversampling: u8, rate: u8) -> Result<(), Error<I2C::Error>> {
        let os = log2(oversampling).ok_or(Error::InvalidArgument)?;
        let r = log2(rate).ok_or(Error::InvalidArgument)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_TMP_CFG, TMP_EXT | (r << 4) | os)?;
        self.inner.t_os = os;
        self.t_rate = r;
        if oversampling > 8 {
            self.inner.cfg |= CFG_T_SHIFT;
        } else {
            self.inner.cfg &= !CFG_T_SHIFT;
        }
        self.write_cfg()
    }

    /// Start background mode (`MEAS_CTRL = 101 / 110 / 111`). Fails with `InvalidArgument` if both
    /// `pressure` and `temperature` are false.
    pub fn start_continuous(&mut self, pressure: bool, temperature: bool) -> Result<(), Error<I2C::Error>> {
        if !pressure && !temperature {
            return Err(Error::InvalidArgument);
        }
        let mode = 0x04 | if pressure { 0x01 } else { 0 } | if temperature { 0x02 } else { 0 };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_MEAS_CFG, mode)?;
        self.continuous = mode;
        Ok(())
    }

    /// Stop background mode and return to standby (`MEAS_CTRL = 000`).
    pub fn stop(&mut self) -> Result<(), Error<I2C::Error>> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_MEAS_CFG, CMD_IDLE)?;
        self.continuous = 0;
        Ok(())
    }

    /// Wait for the background ready flags and return the latest results (no new measurement).
    pub fn read_continuous<D: DelayNs>(&mut self, delay: &mut D) -> Result<Spl06Measurement, Error<I2C::Error>> {
        let mut flags = 0;
        if self.continuous & 0x01 != 0 {
            flags |= MEAS_PRS_RDY;
        }
        if self.continuous & 0x02 != 0 {
            flags |= MEAS_TMP_RDY;
        }
        if flags != 0 {
            let slow = self.p_rate.min(self.t_rate);
            let limit = 2000 / (1u32 << slow) + 500;
            let mut waited = 0u32;
            while read_reg(&mut self.inner.i2c, self.inner.addr, REG_MEAS_CFG)? & flags != flags {
                if waited > limit {
                    return Err(Error::Timeout);
                }
                delay.delay_ms(2);
                waited += 2;
            }
        }
        let mut b = [0u8; 6];
        read_register(&mut self.inner.i2c, self.inner.addr, REG_PRS_B2 as u32, 1, &mut b)?;
        let (praw, traw) = (raw24(&b[0..3]), raw24(&b[3..6]));
        self.inner.last_traw = traw;
        Ok(Spl06Measurement { temperature_c: self.inner.temperature(traw), pressure_pa: self.inner.pressure(praw, traw) })
    }

    /// Read the `MEAS_CFG` ready flags: `(pressure_ready, temperature_ready)`.
    pub fn data_ready(&mut self) -> Result<(bool, bool), Error<I2C::Error>> {
        let m = read_reg(&mut self.inner.i2c, self.inner.addr, REG_MEAS_CFG)?;
        Ok((m & MEAS_PRS_RDY != 0, m & MEAS_TMP_RDY != 0))
    }

    /// Enable the 32-entry result FIFO (`CFG_REG.FIFO_EN`).
    pub fn enable_fifo(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.cfg |= CFG_FIFO_EN;
        self.write_cfg()
    }

    /// Disable the result FIFO.
    pub fn disable_fifo(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.cfg &= !CFG_FIFO_EN;
        self.write_cfg()
    }

    /// Drain the FIFO into `entries` until `FIFO_EMPTY` (at most 32 entries); returns the count.
    ///
    /// The LSB of each raw entry flags its kind (1 = pressure, 0 = temperature). Pressure entries
    /// are compensated with the most recent temperature entry (or the last measured temperature).
    pub fn read_fifo(&mut self, entries: &mut [Spl06FifoEntry]) -> Result<usize, Error<I2C::Error>> {
        let mut n = 0;
        while n < entries.len() && n < 32 {
            if read_reg(&mut self.inner.i2c, self.inner.addr, REG_FIFO_STS)? & 0x01 != 0 {
                break;
            }
            let mut b = [0u8; 3];
            read_register(&mut self.inner.i2c, self.inner.addr, REG_PRS_B2 as u32, 1, &mut b)?;
            let raw = raw24(&b);
            if raw == -0x80_0000 {
                break;
            }
            entries[n] = if raw & 1 != 0 {
                Spl06FifoEntry { kind: Spl06Kind::Pressure, value: self.inner.pressure(raw & !1, self.inner.last_traw) }
            } else {
                self.inner.last_traw = raw;
                Spl06FifoEntry { kind: Spl06Kind::Temperature, value: self.inner.temperature(raw) }
            };
            n += 1;
        }
        Ok(n)
    }

    /// Discard the FIFO contents (`RESET.FIFO_FLUSH`).
    pub fn flush_fifo(&mut self) -> Result<(), Error<I2C::Error>> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_RESET, 0x80)
    }

    /// Read `FIFO_STS`: `(empty, full)`.
    pub fn fifo_status(&mut self) -> Result<(bool, bool), Error<I2C::Error>> {
        let s = read_reg(&mut self.inner.i2c, self.inner.addr, REG_FIFO_STS)?;
        Ok((s & 0x01 != 0, s & 0x02 != 0))
    }

    /// Soft reset (`RESET = 0x09`), wait for the ready flags, re-read the coefficients and restore
    /// the `init` defaults.
    pub fn reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let _ = write_reg(&mut self.inner.i2c, self.inner.addr, REG_RESET, 0x09); // may not ACK while resetting
        delay.delay_ms(12);
        self.inner.wait_ready(delay)?;
        self.inner.read_coefficients()?;
        self.inner.apply_defaults()?;
        self.p_rate = 0;
        self.t_rate = 0;
        self.continuous = 0;
        Ok(())
    }

    /// Decoded calibration coefficients.
    pub fn coefficients(&self) -> Spl06Coefficients {
        self.inner.coef
    }

    /// Compensate raw values with the stored coefficients (no bus access).
    ///
    /// # Arguments
    /// * `praw`, `traw` — signed raw pressure and temperature.
    /// * `p_oversampling`, `t_oversampling` — oversampling the raw values were measured with.
    pub fn compensate(&self, praw: i32, traw: i32, p_oversampling: u8, t_oversampling: u8)
        -> Result<Spl06Measurement, Error<I2C::Error>> {
        let po = log2(p_oversampling).ok_or(Error::InvalidArgument)?;
        let to = log2(t_oversampling).ok_or(Error::InvalidArgument)?;
        let p = praw as f32 / SCALE[po as usize];
        let t = traw as f32 / SCALE[to as usize];
        Ok(Spl06Measurement {
            temperature_c: compensate_t(&self.inner.coef, t),
            pressure_pa: compensate_p(&self.inner.coef, p, t),
        })
    }

    /// Measure the pressure and convert it to altitude in metres (international barometric formula).
    ///
    /// # Arguments
    /// * `sea_level_pa` — reference sea-level pressure in pascals (typically 101325).
    pub fn altitude<D: DelayNs>(&mut self, delay: &mut D, sea_level_pa: f32) -> Result<f32, Error<I2C::Error>> {
        let p = self.read_pressure(delay)?;
        Ok(44330.0 * (1.0 - libm::powf(p / sea_level_pa, 1.0 / 5.255)))
    }

    /// Enable interrupt sources on `SDO` (`SOURCE_*` bitwise OR).
    pub fn enable_interrupt(&mut self, source: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.cfg |= int_bits(source);
        self.write_cfg()
    }

    /// Disable interrupt sources (`SOURCE_*` bitwise OR).
    pub fn disable_interrupt(&mut self, source: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.cfg &= !int_bits(source);
        self.write_cfg()
    }

    /// Read `INT_STS` (cleared on read); returns the `SOURCE_*` bitmask (0 = none).
    pub fn poll_interrupt(&mut self) -> Result<u8, Error<I2C::Error>> {
        Ok(read_reg(&mut self.inner.i2c, self.inner.addr, REG_INT_STS)? & 0x07)
    }

    /// True if the interrupt is configured active high (`CFG_REG.INT_HL`); the driver keeps 0.
    pub fn interrupt_active_high(&self) -> bool {
        self.inner.cfg & CFG_INT_HL != 0
    }

    fn write_cfg(&mut self) -> Result<(), Error<I2C::Error>> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CFG_REG, self.inner.cfg)
    }
}

fn read_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u8, I2C::Error> {
    let mut b = [0u8; 1];
    read_register(i2c, addr, reg as u32, 1, &mut b)?;
    Ok(b[0])
}

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8) -> Result<(), Error<I2C::Error>> {
    write_register(i2c, addr, reg as u32, 1, &[value])?;
    Ok(())
}

fn raw24(b: &[u8]) -> i32 {
    to_signed(((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32, 24)
}

fn log2(value: u8) -> Option<u8> {
    if value.is_power_of_two() { Some(value.trailing_zeros() as u8) } else { None }
}

fn int_bits(source: u8) -> u8 {
    let mut bits = 0;
    if source & SOURCE_PRESSURE != 0 {
        bits |= CFG_INT_PRS;
    }
    if source & SOURCE_TEMPERATURE != 0 {
        bits |= CFG_INT_TMP;
    }
    if source & SOURCE_FIFO_FULL != 0 {
        bits |= CFG_INT_FIFO;
    }
    bits
}

fn compensate_t(c: &Spl06Coefficients, t: f32) -> f32 {
    c.c0 as f32 * 0.5 + c.c1 as f32 * t
}

fn compensate_p(c: &Spl06Coefficients, p: f32, t: f32) -> f32 {
    c.c00 as f32 + p * (c.c10 as f32 + p * (c.c20 as f32 + p * c.c30 as f32))
        + t * c.c01 as f32 + t * p * (c.c11 as f32 + p * c.c21 as f32)
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const A: u8 = 0x77;
    const COEF: [u8; 18] = [0x0C, 0xCE, 0xFB, 0x13, 0xA5, 0x5F, 0x2A, 0x0F, 0xF5,
                            0x9D, 0x04, 0xEA, 0xD6, 0xDF, 0x00, 0x5E, 0xFA, 0xBA];
    const TRAW: [u8; 3] = [0x02, 0x5C, 0x28]; // 154664
    const PRAW: [u8; 3] = [0xFE, 0x94, 0xB7]; // -93001
    const T_EXP: f32 = 25.005_478;
    const P_EXP: f32 = 98263.86;

    fn w(reg: u8, v: u8) -> T {
        T::write(A, vec![reg, v])
    }
    fn wr(reg: u8, data: Vec<u8>) -> T {
        T::write_read(A, vec![reg], data)
    }
    fn near(a: f32, b: f32, tol: f32) -> bool {
        (a - b).abs() < tol
    }
    fn init_seq() -> Vec<T> {
        vec![wr(0x0D, vec![0x10]), wr(0x08, vec![0xC0]), wr(0x10, COEF.to_vec()),
             w(0x07, 0x80), w(0x06, 0x04), w(0x09, 0x04)]
    }
    fn read_seq() -> Vec<T> {
        vec![w(0x08, 0x02), wr(0x08, vec![0xE2]), wr(0x03, TRAW.to_vec()),
             w(0x08, 0x01), wr(0x08, vec![0xD1]), wr(0x00, PRAW.to_vec())]
    }
    fn chain(parts: &[Vec<T>]) -> Vec<T> {
        parts.iter().flat_map(|p| p.iter().cloned()).collect()
    }

    #[test]
    fn init_decodes_coefficients_and_writes_defaults() {
        let mut i2c = I2cMock::new(&init_seq());
        let mut s = Spl06Full::new(i2c.clone(), A);
        s.init(&mut NoopDelay::new()).unwrap();
        let c = s.coefficients();
        assert_eq!((c.c0, c.c1, c.c00, c.c10), (204, -261, 80469, -54769));
        assert_eq!((c.c01, c.c11, c.c20, c.c21, c.c30), (-2659, 1258, -10529, 94, -1350));
        i2c.done();
    }

    #[test]
    fn init_rejects_wrong_id() {
        let mut i2c = I2cMock::new(&[wr(0x0D, vec![0x58])]);
        let mut s = Spl06Minimal::new(i2c.clone(), A);
        assert_eq!(s.init(&mut NoopDelay::new()), Err(Error::InvalidChip(0x58)));
        i2c.done();
    }

    #[test]
    fn init_times_out_without_coef_rdy() {
        let mut t = vec![wr(0x0D, vec![0x10])];
        for _ in 0..40 {
            t.push(wr(0x08, vec![0x40]));
        }
        let mut i2c = I2cMock::new(&t);
        let mut s = Spl06Minimal::new(i2c.clone(), A);
        assert_eq!(s.init(&mut NoopDelay::new()), Err(Error::Timeout));
        i2c.done();
    }

    #[test]
    fn minimal_read_measures_temperature_then_pressure() {
        let mut i2c = I2cMock::new(&chain(&[init_seq(), read_seq()]));
        let mut s = Spl06Minimal::new(i2c.clone(), A);
        let mut d = NoopDelay::new();
        s.init(&mut d).unwrap();
        let m = s.read(&mut d).unwrap();
        assert!(near(m.temperature_c, T_EXP, 1e-3));
        assert!(near(m.pressure_pa, P_EXP, 0.05));
        i2c.done();
    }

    #[test]
    fn full_reads_raw_temperature_and_altitude() {
        let t = chain(&[init_seq(), read_seq(),
                        vec![w(0x08, 0x02), wr(0x08, vec![0xE2]), wr(0x03, TRAW.to_vec())],
                        read_seq(), read_seq()]);
        let mut i2c = I2cMock::new(&t);
        let mut s = Spl06Full::new(i2c.clone(), A);
        let mut d = NoopDelay::new();
        s.init(&mut d).unwrap();
        assert!(near(s.read_pressure(&mut d).unwrap(), P_EXP, 0.05));
        assert!(near(s.read_temperature(&mut d).unwrap(), T_EXP, 1e-3));
        assert_eq!(s.read_raw(&mut d).unwrap(), (-93001, 154664));
        assert!(near(s.altitude(&mut d, 101325.0).unwrap(), 258.03, 0.05));
        i2c.done();
    }

    #[test]
    fn compensate_is_pure() {
        let mut i2c = I2cMock::new(&init_seq());
        let mut s = Spl06Full::new(i2c.clone(), A);
        s.init(&mut NoopDelay::new()).unwrap();
        let m = s.compensate(-93001, 154664, 16, 1).unwrap();
        assert!(near(m.pressure_pa, P_EXP, 0.05) && near(m.temperature_c, T_EXP, 1e-3));
        let m = s.compensate(-93001, 154664, 8, 2).unwrap();
        assert!(near(m.pressure_pa, 80852.28, 0.05) && near(m.temperature_c, 76.33516, 1e-3));
        assert_eq!(s.compensate(0, 0, 3, 1), Err(Error::InvalidArgument));
        i2c.done();
    }

    #[test]
    fn configuration_sets_registers_and_shift_bits() {
        let t = chain(&[init_seq(), vec![
            w(0x06, 0x37), w(0x09, 0x04),
            w(0x06, 0x03), w(0x09, 0x00),
            w(0x07, 0xA5), w(0x09, 0x08),
            w(0x07, 0x81), w(0x09, 0x00),
        ]]);
        let mut i2c = I2cMock::new(&t);
        let mut s = Spl06Full::new(i2c.clone(), A);
        s.init(&mut NoopDelay::new()).unwrap();
        s.set_pressure_config(128, 8).unwrap();
        s.set_pressure_config(8, 1).unwrap();
        s.set_temperature_config(32, 4).unwrap();
        s.set_temperature_config(2, 1).unwrap();
        assert_eq!(s.set_pressure_config(3, 1), Err(Error::InvalidArgument));
        assert_eq!(s.set_pressure_config(16, 3), Err(Error::InvalidArgument));
        assert_eq!(s.set_temperature_config(0, 1), Err(Error::InvalidArgument));
        i2c.done();
    }

    #[test]
    fn background_mode_reads_without_command() {
        let mut data = PRAW.to_vec();
        data.extend_from_slice(&TRAW);
        let t = chain(&[init_seq(), vec![
            w(0x08, 0x07), w(0x08, 0x05), w(0x08, 0x06), w(0x08, 0x07),
            wr(0x08, vec![0xF7]), wr(0x00, data.clone()),
            wr(0x08, vec![0xF7]), wr(0x00, data.clone()),
            wr(0x08, vec![0xD7]),
            w(0x08, 0x00),
        ]]);
        let mut i2c = I2cMock::new(&t);
        let mut s = Spl06Full::new(i2c.clone(), A);
        let mut d = NoopDelay::new();
        s.init(&mut d).unwrap();
        s.start_continuous(true, true).unwrap();
        s.start_continuous(true, false).unwrap();
        s.start_continuous(false, true).unwrap();
        assert_eq!(s.start_continuous(false, false), Err(Error::InvalidArgument));
        s.start_continuous(true, true).unwrap();
        let m = s.read_continuous(&mut d).unwrap();
        assert!(near(m.pressure_pa, P_EXP, 0.05) && near(m.temperature_c, T_EXP, 1e-3));
        assert!(near(s.read(&mut d).unwrap().pressure_pa, P_EXP, 0.05));
        assert_eq!(s.data_ready().unwrap(), (true, false));
        s.stop().unwrap();
        i2c.done();
    }

    #[test]
    fn fifo_drains_and_classifies_entries() {
        let t = chain(&[init_seq(), vec![
            w(0x09, 0x06),
            wr(0x0B, vec![0x00]), wr(0x00, TRAW.to_vec()),
            wr(0x0B, vec![0x00]), wr(0x00, PRAW.to_vec()),
            wr(0x0B, vec![0x01]),
            wr(0x0B, vec![0x02]),
            w(0x0C, 0x80),
            w(0x09, 0x04),
        ]]);
        let mut i2c = I2cMock::new(&t);
        let mut s = Spl06Full::new(i2c.clone(), A);
        s.init(&mut NoopDelay::new()).unwrap();
        s.enable_fifo().unwrap();
        let mut e = [Spl06FifoEntry::default(); 32];
        let n = s.read_fifo(&mut e).unwrap();
        assert_eq!(n, 2);
        assert_eq!(e[0].kind, Spl06Kind::Temperature);
        assert!(near(e[0].value, T_EXP, 1e-3));
        assert_eq!(e[1].kind, Spl06Kind::Pressure);
        assert!(near(e[1].value, 98264.05, 0.05));
        assert_eq!(s.fifo_status().unwrap(), (false, true));
        s.flush_fifo().unwrap();
        s.disable_fifo().unwrap();
        i2c.done();
    }

    #[test]
    fn interrupts_map_sources_to_cfg_bits() {
        let t = chain(&[init_seq(), vec![
            w(0x09, 0x24), w(0x09, 0x74), w(0x09, 0x14),
            wr(0x0A, vec![0xFA]),
        ]]);
        let mut i2c = I2cMock::new(&t);
        let mut s = Spl06Full::new(i2c.clone(), A);
        s.init(&mut NoopDelay::new()).unwrap();
        s.enable_interrupt(SOURCE_PRESSURE).unwrap();
        s.enable_interrupt(SOURCE_TEMPERATURE | SOURCE_FIFO_FULL).unwrap();
        s.disable_interrupt(SOURCE_PRESSURE | SOURCE_FIFO_FULL).unwrap();
        assert_eq!(s.poll_interrupt().unwrap(), 0x02);
        assert!(!s.interrupt_active_high());
        i2c.done();
    }

    #[test]
    fn reset_restores_defaults() {
        let t = chain(&[init_seq(), vec![w(0x06, 0x26), w(0x09, 0x04), w(0x0C, 0x09),
                                         wr(0x08, vec![0xC0]), wr(0x10, COEF.to_vec()),
                                         w(0x07, 0x80), w(0x06, 0x04), w(0x09, 0x04)]]);
        let mut i2c = I2cMock::new(&t);
        let mut s = Spl06Full::new(i2c.clone(), A);
        let mut d = NoopDelay::new();
        s.init(&mut d).unwrap();
        s.set_pressure_config(64, 4).unwrap();
        s.reset(&mut d).unwrap();
        i2c.done();
    }
}
