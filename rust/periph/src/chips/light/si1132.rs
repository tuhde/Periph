//! Si1132 — UV index and ambient light sensor (Silicon Labs).
//!
//! Fixed I²C address `0x60`. The chip is a small sequencer: configuration lives partly in
//! registers and partly in a parameter RAM written with `PARAM_WR` plus a `PARAM_SET` command.
//! Construction does no bus access; `init()` waits 25 ms, verifies `PART_ID = 0x32`, writes
//! `HW_KEY`, the default UV coefficients and enables the UV, infrared and visible channels in
//! forced mode (gain 0, normal range). `read()` runs a forced measurement and returns the UV index
//! (`AUX_DATA / 100`) plus the raw 16-bit visible and infrared counts.
//!
//! The Full driver exposes `poll_interrupt()`, `enable_interrupt()` and `disable_interrupt()` for
//! the INT pin: the caller is responsible for wiring it into an ISR or a polling loop.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

/// Fixed 7-bit I²C address.
pub const I2C_ADDRESS: u8 = 0x60;

const REG_PART_ID: u8 = 0x00;
const REG_SEQ_ID: u8 = 0x02;
const REG_INT_CFG: u8 = 0x03;
const REG_IRQ_ENABLE: u8 = 0x04;
const REG_HW_KEY: u8 = 0x07;
const REG_MEAS_RATE0: u8 = 0x08;
const REG_MEAS_RATE1: u8 = 0x09;
const REG_MEAS_RATE_SWAPPED: u8 = 0x0A;
const REG_UCOEF: u8 = 0x13;
const REG_PARAM_WR: u8 = 0x17;
const REG_COMMAND: u8 = 0x18;
const REG_RESPONSE: u8 = 0x20;
const REG_IRQ_STATUS: u8 = 0x21;
const REG_DATA: u8 = 0x22;

const PART_ID_VALUE: u8 = 0x32;
const HW_KEY_VALUE: u8 = 0x17;
const UCOEF_DEFAULT: [u8; 4] = [0x7B, 0x6B, 0x01, 0x00];

const CMD_PARAM_SET: u8 = 0xA0;
const CMD_NOP: u8 = 0x00;
const CMD_RESET: u8 = 0x01;
const CMD_BUSADDR: u8 = 0x02;
const CMD_ALS_FORCE: u8 = 0x06;
const CMD_ALS_PAUSE: u8 = 0x0A;
const CMD_ALS_AUTO: u8 = 0x0E;
const CMD_GET_CAL: u8 = 0x12;

const PARAM_I2C_ADDR: u8 = 0x00;
const PARAM_CHLIST: u8 = 0x01;
const PARAM_ALS_ENCODING: u8 = 0x06;
const PARAM_AUX_ADCMUX: u8 = 0x0F;
const PARAM_VIS_COUNTER: u8 = 0x10;
const PARAM_VIS_GAIN: u8 = 0x11;
const PARAM_VIS_MISC: u8 = 0x12;
const PARAM_IR_COUNTER: u8 = 0x1D;
const PARAM_IR_GAIN: u8 = 0x1E;
const PARAM_IR_MISC: u8 = 0x1F;

const CHLIST_ALL: u8 = 0xB0;
const CHLIST_AUX: u8 = 0x40;
const IRQ_ALS_INT: u8 = 0x01;
const IRQ_CLEAR: u8 = 0x21;
const RESP_ERROR: u8 = 0x80;
const TIMEOUT_MS: u32 = 500;

/// `IRQ_STATUS` flag: ALS/UV measurement complete.
pub const STATUS_ALS_INT: u8 = 0x01;

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// A measurement or command did not complete in time.
    Timeout,
    /// `PART_ID` is not `0x32`.
    DeviceNotFound,
    /// The chip reported a `RESPONSE` error code (raw value).
    Command(u8),
    /// Unsupported argument (gain, channel selection, interval, coefficient count).
    InvalidArgument,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// One forced measurement.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Reading {
    /// UV index (`AUX_DATA / 100`).
    pub uv_index: f32,
    /// Raw 16-bit visible ambient-light count.
    pub visible: u16,
    /// Raw 16-bit infrared ambient-light count.
    pub infrared: u16,
}

/// Si1132 minimal driver — one forced UV / visible / infrared measurement per `read()`.
pub struct Si1132Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    chlist: u8,
    seq_id: u8,
}

impl<I2C: I2c> Si1132Minimal<I2C> {
    /// Create a new `Si1132Minimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x60`).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, chlist: CHLIST_ALL, seq_id: 0 }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    /// Verify `PART_ID` and configure forced UV + visible + infrared measurements.
    ///
    /// [`Error::DeviceNotFound`] if `PART_ID` is not `0x32`.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        delay.delay_ms(25);
        if self.read_reg(REG_PART_ID)? != PART_ID_VALUE {
            return Err(Error::DeviceNotFound);
        }
        self.seq_id = self.read_reg(REG_SEQ_ID)?;
        self.write_reg(REG_HW_KEY, HW_KEY_VALUE)?;
        self.write_regs(REG_UCOEF, &UCOEF_DEFAULT)?;
        // ALS_IE lets IRQ_STATUS.ALS_INT flag completed measurements; INT stays released (INT_OE = 0).
        self.write_reg(REG_IRQ_ENABLE, IRQ_ALS_INT)?;
        self.chlist = CHLIST_ALL;
        self.param_set(delay, PARAM_CHLIST, self.chlist)
    }

    fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), Error<I2C::Error>> {
        self.i2c.write(self.addr, &[reg, value])?;
        Ok(())
    }

    fn write_regs(&mut self, reg: u8, data: &[u8]) -> Result<(), Error<I2C::Error>> {
        let mut buf = [0u8; 5];
        buf[0] = reg;
        buf[1..=data.len()].copy_from_slice(data);
        self.i2c.write(self.addr, &buf[..=data.len()])?;
        Ok(())
    }

    fn read_reg(&mut self, reg: u8) -> Result<u8, Error<I2C::Error>> {
        let mut d = [0u8; 1];
        self.i2c.write_read(self.addr, &[reg], &mut d)?;
        Ok(d[0])
    }

    fn wait_response<D: DelayNs>(&mut self, delay: &mut D, before: u8) -> Result<(), Error<I2C::Error>> {
        for _ in 0..20 {
            let resp = self.read_reg(REG_RESPONSE)?;
            if resp & RESP_ERROR != 0 {
                self.write_reg(REG_COMMAND, CMD_NOP)?;
                return Err(Error::Command(resp));
            }
            if resp & 0x0F != before {
                return Ok(());
            }
            delay.delay_ms(1);
        }
        Err(Error::Timeout)
    }

    fn command<D: DelayNs>(&mut self, delay: &mut D, cmd: u8) -> Result<(), Error<I2C::Error>> {
        let before = self.read_reg(REG_RESPONSE)? & 0x0F;
        self.write_reg(REG_COMMAND, cmd)?;
        self.wait_response(delay, before)
    }

    fn param_set<D: DelayNs>(&mut self, delay: &mut D, offset: u8, value: u8) -> Result<(), Error<I2C::Error>> {
        let before = self.read_reg(REG_RESPONSE)? & 0x0F;
        self.write_reg(REG_PARAM_WR, value)?;
        self.write_reg(REG_COMMAND, CMD_PARAM_SET | (offset & 0x1F))?;
        self.wait_response(delay, before)
    }

    fn start_measurement<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.write_reg(REG_IRQ_STATUS, IRQ_CLEAR)?;
        self.command(delay, CMD_ALS_FORCE)
    }

    fn wait_ready<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        for _ in 0..TIMEOUT_MS {
            if self.read_reg(REG_IRQ_STATUS)? & IRQ_ALS_INT != 0 {
                return self.write_reg(REG_IRQ_STATUS, IRQ_CLEAR);
            }
            delay.delay_ms(1);
        }
        Err(Error::Timeout)
    }

    /// Read the data block `0x22`–`0x2D` and return `(visible, infrared, aux)`.
    fn read_data(&mut self) -> Result<(u16, u16, u16), Error<I2C::Error>> {
        let mut d = [0u8; 12];
        self.i2c.write_read(self.addr, &[REG_DATA], &mut d)?;
        Ok((
            u16::from_le_bytes([d[0], d[1]]),
            u16::from_le_bytes([d[2], d[3]]),
            u16::from_le_bytes([d[10], d[11]]),
        ))
    }

    fn measure<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u16, u16, u16), Error<I2C::Error>> {
        self.start_measurement(delay)?;
        self.wait_ready(delay)?;
        self.read_data()
    }

    /// Run one forced measurement of the enabled channels.
    ///
    /// [`Error::Timeout`] if no measurement completes within 500 ms.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<Reading, Error<I2C::Error>> {
        let (visible, infrared, aux) = self.measure(delay)?;
        Ok(Reading { uv_index: aux as f32 / 100.0, visible, infrared })
    }
}

/// Si1132 full driver — single channels, gain/range, autonomous mode, calibration, auxiliary
/// channels and the INT pin.
///
/// Owns a [`Si1132Minimal`] and delegates `init` and `read`.
pub struct Si1132Full<I2C> {
    inner: Si1132Minimal<I2C>,
}

impl<I2C: I2c> Si1132Full<I2C> {
    /// Create a new `Si1132Full`; same arguments as [`Si1132Minimal::new`]. No bus access.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Si1132Minimal::new(i2c, addr) }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// See [`Si1132Minimal::init`].
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.init(delay)
    }

    /// See [`Si1132Minimal::read`].
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<Reading, Error<I2C::Error>> {
        self.inner.read(delay)
    }

    /// Forced UV measurement; UV index (`AUX_DATA / 100`).
    pub fn read_uv_index<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(self.inner.measure(delay)?.2 as f32 / 100.0)
    }

    /// Forced measurement; raw 16-bit visible count.
    pub fn read_visible<D: DelayNs>(&mut self, delay: &mut D) -> Result<u16, Error<I2C::Error>> {
        Ok(self.inner.measure(delay)?.0)
    }

    /// Forced measurement; raw 16-bit infrared count.
    pub fn read_infrared<D: DelayNs>(&mut self, delay: &mut D) -> Result<u16, Error<I2C::Error>> {
        Ok(self.inner.measure(delay)?.1)
    }

    /// Read the latest data registers without starting a measurement: `(visible, infrared, aux)`.
    pub fn read_raw(&mut self) -> Result<(u16, u16, u16), Error<I2C::Error>> {
        self.inner.read_data()
    }

    fn read_aux<D: DelayNs>(&mut self, delay: &mut D, mux: u8) -> Result<u16, Error<I2C::Error>> {
        self.inner.param_set(delay, PARAM_AUX_ADCMUX, mux)?;
        self.inner.param_set(delay, PARAM_CHLIST, CHLIST_AUX)?;
        let measured = self.inner.measure(delay);
        let chlist = self.inner.chlist;
        let restored = self.inner.param_set(delay, PARAM_CHLIST, chlist);
        let aux = measured?.2;
        restored?;
        Ok(aux)
    }

    /// Auxiliary channel as die temperature (`AUX_ADCMUX = 0x65`, relative only).
    pub fn read_temperature_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<u16, Error<I2C::Error>> {
        self.read_aux(delay, 0x65)
    }

    /// Auxiliary channel as supply voltage (`AUX_ADCMUX = 0x75`).
    pub fn read_vdd_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<u16, Error<I2C::Error>> {
        self.read_aux(delay, 0x75)
    }

    /// Select the measured channels (`CHLIST`).
    ///
    /// [`Error::InvalidArgument`] if no channel is selected or both `uv` and `aux` are set.
    pub fn set_channels<D: DelayNs>(
        &mut self,
        delay: &mut D,
        uv: bool,
        visible: bool,
        infrared: bool,
        aux: bool,
    ) -> Result<(), Error<I2C::Error>> {
        if !(uv || visible || infrared || aux) || (uv && aux) {
            return Err(Error::InvalidArgument);
        }
        let chlist = (if uv { 0x80 } else { 0 })
            | (if aux { 0x40 } else { 0 })
            | (if infrared { 0x20 } else { 0 })
            | (if visible { 0x10 } else { 0 });
        self.inner.param_set(delay, PARAM_CHLIST, chlist)?;
        self.inner.chlist = chlist;
        Ok(())
    }

    /// Set the ADC gain (integration time x 2^n, 0–7 per channel) and the recommended recovery count.
    pub fn set_gain<D: DelayNs>(&mut self, delay: &mut D, visible: u8, infrared: u8) -> Result<(), Error<I2C::Error>> {
        if visible > 7 || infrared > 7 {
            return Err(Error::InvalidArgument);
        }
        self.inner.param_set(delay, PARAM_VIS_GAIN, visible)?;
        self.inner.param_set(delay, PARAM_VIS_COUNTER, (!visible & 0x07) << 4)?;
        self.inner.param_set(delay, PARAM_IR_GAIN, infrared)?;
        self.inner.param_set(delay, PARAM_IR_COUNTER, (!infrared & 0x07) << 4)
    }

    /// Select the high signal range (sensitivity ÷ 14.5, for direct sunlight).
    pub fn set_high_range<D: DelayNs>(&mut self, delay: &mut D, visible: bool, infrared: bool) -> Result<(), Error<I2C::Error>> {
        self.inner.param_set(delay, PARAM_VIS_MISC, if visible { 0x20 } else { 0x00 })?;
        self.inner.param_set(delay, PARAM_IR_MISC, if infrared { 0x20 } else { 0x00 })
    }

    /// Select which 16 bits of the 17-bit ADC value are reported (`true` = least significant 16 bits).
    pub fn set_alignment<D: DelayNs>(&mut self, delay: &mut D, visible_low: bool, infrared_low: bool) -> Result<(), Error<I2C::Error>> {
        let v = (if visible_low { 0x10 } else { 0 }) | (if infrared_low { 0x20 } else { 0 });
        self.inner.param_set(delay, PARAM_ALS_ENCODING, v)
    }

    /// Write the four UV index coefficients `UCOEF0..3`.
    pub fn set_uv_coefficients(&mut self, ucoef: [u8; 4]) -> Result<(), Error<I2C::Error>> {
        self.inner.write_regs(REG_UCOEF, &ucoef)
    }

    /// `GET_CAL`, then read the 12 factory calibration bytes from `0x22`–`0x2D`.
    pub fn read_calibration<D: DelayNs>(&mut self, delay: &mut D) -> Result<[u8; 12], Error<I2C::Error>> {
        self.inner.command(delay, CMD_GET_CAL)?;
        let mut d = [0u8; 12];
        self.inner.i2c.write_read(self.inner.addr, &[REG_DATA], &mut d)?;
        Ok(d)
    }

    /// Start autonomous measurements; `interval_s` is the wake-up interval in seconds (multiples
    /// of 31.25 µs, at most about 2.05 s); `0.0` returns to forced mode.
    pub fn start_autonomous<D: DelayNs>(&mut self, delay: &mut D, interval_s: f32) -> Result<(), Error<I2C::Error>> {
        if !(0.0..=2.05).contains(&interval_s) {
            return Err(Error::InvalidArgument);
        }
        let counts = (interval_s / 31.25e-6 + 0.5) as u32;
        if counts > 0xFFFF {
            return Err(Error::InvalidArgument);
        }
        if self.inner.seq_id == 0x01 {
            // erratum: MEAS_RATE bytes swapped
            self.inner.write_reg(REG_MEAS_RATE_SWAPPED, (counts & 0xFF) as u8)?;
            self.inner.write_reg(REG_MEAS_RATE0, (counts >> 8) as u8)?;
        } else {
            self.inner.write_reg(REG_MEAS_RATE0, (counts & 0xFF) as u8)?;
            self.inner.write_reg(REG_MEAS_RATE1, (counts >> 8) as u8)?;
        }
        self.inner.command(delay, if counts != 0 { CMD_ALS_AUTO } else { CMD_ALS_PAUSE })
    }

    /// Pause autonomous measurements (`ALS_PAUSE`).
    pub fn pause<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.command(delay, CMD_ALS_PAUSE)
    }

    /// Start one forced measurement without waiting for it (`ALS_FORCE`).
    pub fn force<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.start_measurement(delay)
    }

    /// `true` when `IRQ_STATUS.ALS_INT` is set.
    pub fn measurement_ready(&mut self) -> Result<bool, Error<I2C::Error>> {
        Ok(self.inner.read_reg(REG_IRQ_STATUS)? & IRQ_ALS_INT != 0)
    }

    /// Decode `RESPONSE`: `(counter, error)`, error `0` = none.
    pub fn read_response(&mut self) -> Result<(u8, u8), Error<I2C::Error>> {
        let resp = self.inner.read_reg(REG_RESPONSE)?;
        Ok(if resp & RESP_ERROR != 0 { (0, resp) } else { (resp & 0x0F, 0) })
    }

    /// Change the I²C address (volatile): `I2C_ADDR` parameter, then `BUSADDR`.
    pub fn set_i2c_address<D: DelayNs>(&mut self, delay: &mut D, address: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.param_set(delay, PARAM_I2C_ADDR, address)?;
        self.inner.command(delay, CMD_BUSADDR)
    }

    /// `RESET` command, wait 2 ms, then write `HW_KEY` again.
    pub fn reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.write_reg(REG_COMMAND, CMD_RESET)?;
        delay.delay_ms(2);
        self.inner.write_reg(REG_HW_KEY, HW_KEY_VALUE)
    }

    /// Drive INT low on completed measurements (`INT_OE` and `ALS_IE`).
    pub fn enable_interrupt(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.write_reg(REG_IRQ_ENABLE, IRQ_ALS_INT)?;
        self.inner.write_reg(REG_INT_CFG, 0x01)
    }

    /// Release INT (`INT_OE = 0`); `ALS_IE` stays set because `read()` polls `ALS_INT`.
    pub fn disable_interrupt(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.write_reg(REG_INT_CFG, 0x00)
    }

    /// Read `IRQ_STATUS`, write it back to clear it and return it (`0` = nothing pending).
    pub fn poll_interrupt(&mut self) -> Result<u8, Error<I2C::Error>> {
        let status = self.inner.read_reg(REG_IRQ_STATUS)?;
        if status != 0 {
            self.inner.write_reg(REG_IRQ_STATUS, status)?;
        }
        Ok(status)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::ErrorKind;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x60;

    fn w(bytes: &[u8]) -> I2cTransaction {
        I2cTransaction::write(ADDR, bytes.to_vec())
    }

    fn wr(reg: &[u8], data: &[u8]) -> I2cTransaction {
        I2cTransaction::write_read(ADDR, reg.to_vec(), data.to_vec())
    }

    /// Transactions of one parameter write when RESPONSE goes `before` -> `after`.
    fn param(offset: u8, value: u8, before: u8, after: u8) -> Vec<I2cTransaction> {
        vec![wr(&[0x20], &[before]), w(&[0x17, value]), w(&[0x18, 0xA0 | offset]), wr(&[0x20], &[after])]
    }

    /// Transactions of one command when RESPONSE goes `before` -> `after`.
    fn cmd(c: u8, before: u8, after: u8) -> Vec<I2cTransaction> {
        vec![wr(&[0x20], &[before]), w(&[0x18, c]), wr(&[0x20], &[after])]
    }

    fn init_t() -> Vec<I2cTransaction> {
        let mut t = vec![
            wr(&[0x00], &[0x32]),
            wr(&[0x02], &[0x08]),
            w(&[0x07, 0x17]),
            w(&[0x13, 0x7B, 0x6B, 0x01, 0x00]),
            w(&[0x04, 0x01]),
        ];
        t.extend(param(0x01, 0xB0, 0, 1));
        t
    }

    /// Transactions of one forced measurement that returns `data` (visible 1234, infrared 567, aux 250).
    fn measure_t(before: u8, after: u8) -> Vec<I2cTransaction> {
        let mut t = vec![w(&[0x21, 0x21])];
        t.extend(cmd(0x06, before, after));
        t.push(wr(&[0x21], &[0x01]));
        t.push(w(&[0x21, 0x21]));
        t.push(wr(&[0x22], &DATA));
        t
    }

    const DATA: [u8; 12] = [0xD2, 0x04, 0x37, 0x02, 0, 0, 0, 0, 0, 0, 0xFA, 0x00];

    fn close(a: f32, b: f32) -> bool {
        (a - b).abs() <= 1e-4
    }

    #[test]
    fn ctor_no_bus_access() {
        let s = Si1132Minimal::new(I2cMock::new(&[]), ADDR);
        let mut i2c = s.release();
        i2c.done();
    }

    #[test]
    fn init_configures_chip() {
        let mut s = Si1132Minimal::new(I2cMock::new(&init_t()), ADDR);
        s.init(&mut NoopDelay).unwrap();
        s.i2c.done();
    }

    #[test]
    fn init_wrong_part_id() {
        let mut s = Si1132Minimal::new(I2cMock::new(&[wr(&[0x00], &[0x45])]), ADDR);
        assert_eq!(s.init(&mut NoopDelay), Err(Error::DeviceNotFound));
        s.i2c.done();
    }

    #[test]
    fn read_returns_uv_and_counts() {
        let mut t = init_t();
        t.extend(measure_t(1, 2));
        let mut s = Si1132Minimal::new(I2cMock::new(&t), ADDR);
        s.init(&mut NoopDelay).unwrap();
        let r = s.read(&mut NoopDelay).unwrap();
        assert!(close(r.uv_index, 2.5));
        assert_eq!(r.visible, 1234);
        assert_eq!(r.infrared, 567);
        s.i2c.done();
    }

    #[test]
    fn read_times_out() {
        let mut t = vec![w(&[0x21, 0x21])];
        t.extend(cmd(0x06, 0, 1));
        for _ in 0..TIMEOUT_MS {
            t.push(wr(&[0x21], &[0x00]));
        }
        let mut s = Si1132Minimal::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.read(&mut NoopDelay), Err(Error::Timeout));
        s.i2c.done();
    }

    #[test]
    fn command_error_sends_nop() {
        let t = vec![wr(&[0x20], &[0x00]), w(&[0x18, 0x0A]), wr(&[0x20], &[0x80]), w(&[0x18, 0x00])];
        let mut s = Si1132Full::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.pause(&mut NoopDelay), Err(Error::Command(0x80)));
        s.release().done();
    }

    #[test]
    fn bus_error_is_propagated() {
        let t = vec![wr(&[0x00], &[0x32]).with_error(ErrorKind::Other)];
        let mut s = Si1132Minimal::new(I2cMock::new(&t), ADDR);
        assert_eq!(s.init(&mut NoopDelay), Err(Error::Bus(ErrorKind::Other)));
        s.i2c.done();
    }

    #[test]
    fn full_single_channels() {
        let mut t = measure_t(0, 1);
        t.extend(measure_t(1, 2));
        t.extend(measure_t(2, 3));
        let mut f = Si1132Full::new(I2cMock::new(&t), ADDR);
        assert!(close(f.read_uv_index(&mut NoopDelay).unwrap(), 2.5));
        assert_eq!(f.read_visible(&mut NoopDelay).unwrap(), 1234);
        assert_eq!(f.read_infrared(&mut NoopDelay).unwrap(), 567);
        f.release().done();
    }

    #[test]
    fn full_read_raw() {
        let mut f = Si1132Full::new(I2cMock::new(&[wr(&[0x22], &DATA)]), ADDR);
        assert_eq!(f.read_raw().unwrap(), (1234, 567, 250));
        f.release().done();
    }

    #[test]
    fn full_read_temperature_restores_chlist() {
        let mut t = param(0x0F, 0x65, 0, 1);
        t.extend(param(0x01, 0x40, 1, 2));
        t.extend(measure_t(2, 3));
        t.extend(param(0x01, 0xB0, 3, 4));
        let mut f = Si1132Full::new(I2cMock::new(&t), ADDR);
        assert_eq!(f.read_temperature_raw(&mut NoopDelay).unwrap(), 250);
        f.release().done();
    }

    #[test]
    fn full_read_vdd_uses_mux_0x75() {
        let mut t = param(0x0F, 0x75, 0, 1);
        t.extend(param(0x01, 0x40, 1, 2));
        t.extend(measure_t(2, 3));
        t.extend(param(0x01, 0xB0, 3, 4));
        let mut f = Si1132Full::new(I2cMock::new(&t), ADDR);
        assert_eq!(f.read_vdd_raw(&mut NoopDelay).unwrap(), 250);
        f.release().done();
    }

    #[test]
    fn full_set_channels() {
        let mut f = Si1132Full::new(I2cMock::new(&param(0x01, 0xA0, 0, 1)), ADDR);
        f.set_channels(&mut NoopDelay, true, false, true, false).unwrap();
        assert_eq!(f.inner.chlist, 0xA0);
        f.release().done();
    }

    #[test]
    fn full_set_channels_invalid() {
        let mut f = Si1132Full::new(I2cMock::new(&[]), ADDR);
        assert_eq!(f.set_channels(&mut NoopDelay, false, false, false, false), Err(Error::InvalidArgument));
        assert_eq!(f.set_channels(&mut NoopDelay, true, true, true, true), Err(Error::InvalidArgument));
        f.release().done();
    }

    #[test]
    fn full_set_gain() {
        let mut t = param(0x11, 3, 0, 1);
        t.extend(param(0x10, 0x40, 1, 2));
        t.extend(param(0x1E, 5, 2, 3));
        t.extend(param(0x1D, 0x20, 3, 4));
        let mut f = Si1132Full::new(I2cMock::new(&t), ADDR);
        f.set_gain(&mut NoopDelay, 3, 5).unwrap();
        f.release().done();
    }

    #[test]
    fn full_set_gain_invalid() {
        let mut f = Si1132Full::new(I2cMock::new(&[]), ADDR);
        assert_eq!(f.set_gain(&mut NoopDelay, 8, 0), Err(Error::InvalidArgument));
        f.release().done();
    }

    #[test]
    fn full_set_high_range_and_alignment() {
        let mut t = param(0x12, 0x20, 0, 1);
        t.extend(param(0x1F, 0x00, 1, 2));
        t.extend(param(0x06, 0x30, 2, 3));
        let mut f = Si1132Full::new(I2cMock::new(&t), ADDR);
        f.set_high_range(&mut NoopDelay, true, false).unwrap();
        f.set_alignment(&mut NoopDelay, true, true).unwrap();
        f.release().done();
    }

    #[test]
    fn full_set_uv_coefficients() {
        let mut f = Si1132Full::new(I2cMock::new(&[w(&[0x13, 1, 2, 3, 4])]), ADDR);
        f.set_uv_coefficients([1, 2, 3, 4]).unwrap();
        f.release().done();
    }

    #[test]
    fn full_read_calibration() {
        let cal: [u8; 12] = [0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B];
        let mut t = cmd(0x12, 0, 1);
        t.push(wr(&[0x22], &cal));
        let mut f = Si1132Full::new(I2cMock::new(&t), ADDR);
        assert_eq!(f.read_calibration(&mut NoopDelay).unwrap(), cal);
        f.release().done();
    }

    #[test]
    fn full_start_autonomous() {
        let mut t = vec![w(&[0x08, 0x80]), w(&[0x09, 0x3E])];
        t.extend(cmd(0x0E, 0, 1));
        t.push(w(&[0x08, 0x00]));
        t.push(w(&[0x09, 0x00]));
        t.extend(cmd(0x0A, 1, 2));
        let mut f = Si1132Full::new(I2cMock::new(&t), ADDR);
        f.start_autonomous(&mut NoopDelay, 0.5).unwrap();
        f.start_autonomous(&mut NoopDelay, 0.0).unwrap();
        f.release().done();
    }

    #[test]
    fn full_start_autonomous_invalid() {
        let mut f = Si1132Full::new(I2cMock::new(&[]), ADDR);
        assert_eq!(f.start_autonomous(&mut NoopDelay, 3.0), Err(Error::InvalidArgument));
        assert_eq!(f.start_autonomous(&mut NoopDelay, -1.0), Err(Error::InvalidArgument));
        f.release().done();
    }

    #[test]
    fn full_start_autonomous_erratum_swaps_bytes() {
        let mut t = vec![w(&[0x0A, 0x80]), w(&[0x08, 0x3E])];
        t.extend(cmd(0x0E, 0, 1));
        let mut f = Si1132Full::new(I2cMock::new(&t), ADDR);
        f.inner.seq_id = 0x01;
        f.start_autonomous(&mut NoopDelay, 0.5).unwrap();
        f.release().done();
    }

    #[test]
    fn full_pause_force_ready_response() {
        let mut t = cmd(0x0A, 0, 1);
        t.extend(vec![w(&[0x21, 0x21])]);
        t.extend(cmd(0x06, 1, 2));
        t.push(wr(&[0x21], &[0x01]));
        t.push(wr(&[0x21], &[0x00]));
        t.push(wr(&[0x20], &[0x05]));
        t.push(wr(&[0x20], &[0x8C]));
        let mut f = Si1132Full::new(I2cMock::new(&t), ADDR);
        f.pause(&mut NoopDelay).unwrap();
        f.force(&mut NoopDelay).unwrap();
        assert!(f.measurement_ready().unwrap());
        assert!(!f.measurement_ready().unwrap());
        assert_eq!(f.read_response().unwrap(), (5, 0));
        assert_eq!(f.read_response().unwrap(), (0, 0x8C));
        f.release().done();
    }

    #[test]
    fn full_set_i2c_address_and_reset() {
        let mut t = param(0x00, 0x61, 0, 1);
        t.extend(cmd(0x02, 1, 2));
        t.push(w(&[0x18, 0x01]));
        t.push(w(&[0x07, 0x17]));
        let mut f = Si1132Full::new(I2cMock::new(&t), ADDR);
        f.set_i2c_address(&mut NoopDelay, 0x61).unwrap();
        f.reset(&mut NoopDelay).unwrap();
        f.release().done();
    }

    #[test]
    fn full_interrupt_pin() {
        let t = vec![
            w(&[0x04, 0x01]),
            w(&[0x03, 0x01]),
            w(&[0x03, 0x00]),
            wr(&[0x21], &[0x01]),
            w(&[0x21, 0x01]),
            wr(&[0x21], &[0x00]),
        ];
        let mut f = Si1132Full::new(I2cMock::new(&t), ADDR);
        f.enable_interrupt().unwrap();
        f.disable_interrupt().unwrap();
        assert_eq!(f.poll_interrupt().unwrap(), STATUS_ALS_INT);
        assert_eq!(f.poll_interrupt().unwrap(), 0);
        f.release().done();
    }
}
