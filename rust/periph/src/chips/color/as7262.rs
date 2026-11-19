//! AS7262 — 6-channel visible spectral sensor (ams OSRAM), I²C address `0x49`.
//!
//! Violet 450 nm, blue 500 nm, green 550 nm, yellow 570 nm, orange 600 nm and red 650 nm, a 16-bit
//! ADC per channel, programmable gain (1×, 3.7×, 16×, 64×), an electronic shutter (integration time
//! 2.8 ms × 1…255), two LED drivers, an on-chip temperature sensor and calibrated single-precision
//! float outputs.
//!
//! Every sensor register is a *virtual register* reached through three hardware registers (STATUS
//! `0x00`, WRITE `0x01`, READ `0x02`); this driver implements the handshake. All methods that touch
//! a virtual register take a `delay` so the handshake and conversion polls can time out.
//!
//! Rust exposes [`AS7262Full::poll_interrupt`] (no callback subscription): the caller wires the INT
//! pin itself and calls `poll_interrupt` when it fires.
//!
//! Construction does no bus access; call [`AS7262Minimal::init`] (or [`AS7262Full::init`]) once.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register;

const HW_STATUS: u32 = 0x00;
const HW_WRITE: u32 = 0x01;
const HW_READ: u32 = 0x02;
const TX_VALID: u8 = 0x02;
const RX_VALID: u8 = 0x01;

const REG_HW_VERSION: u8 = 0x00;
const REG_FW_VERSION: u8 = 0x02;
const REG_CONTROL: u8 = 0x04;
const REG_INT_T: u8 = 0x05;
const REG_TEMP: u8 = 0x06;
const REG_LED: u8 = 0x07;
const REG_RAW: u8 = 0x08;
const REG_CAL: u8 = 0x14;

const CTRL_RST: u8 = 0x80;
const CTRL_INT: u8 = 0x40;
const CTRL_DATA_RDY: u8 = 0x02;

const INT_T_DEFAULT: u8 = 0x3B;
const GAIN_DEFAULT: u8 = 2;
const STEP_MS: f32 = 2.8;

const GAINS: [f32; 4] = [1.0, 3.7, 16.0, 64.0];
const DRV_CURRENTS: [f32; 4] = [12.5, 25.0, 50.0, 100.0];
const IND_CURRENTS: [f32; 4] = [1.0, 2.0, 4.0, 8.0];

/// Handshake poll budget: 2000 × 0.5 ms = 1 s.
const POLL_ITERATIONS: u32 = 2000;
const POLL_STEP_US: u32 = 500;

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// A handshake, boot, reset or conversion poll timed out.
    Timeout,
    /// `HW_VERSION` did not read `0x40` (no AS7262 found).
    NotFound,
    /// Unsupported argument (gain, integration time, mode, channel index or LED current).
    InvalidArgument,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// One value per spectral channel.
#[derive(Debug, Default, Clone, Copy, PartialEq)]
pub struct Channels<T> {
    /// Violet, 450 nm.
    pub violet: T,
    /// Blue, 500 nm.
    pub blue: T,
    /// Green, 550 nm.
    pub green: T,
    /// Yellow, 570 nm.
    pub yellow: T,
    /// Orange, 600 nm.
    pub orange: T,
    /// Red, 650 nm.
    pub red: T,
}

impl<T: Copy> Channels<T> {
    fn from_array(v: [T; 6]) -> Self {
        Self { violet: v[0], blue: v[1], green: v[2], yellow: v[3], orange: v[4], red: v[5] }
    }

    /// The channels as an array in order V, B, G, Y, O, R.
    pub fn to_array(&self) -> [T; 6] {
        [self.violet, self.blue, self.green, self.yellow, self.orange, self.red]
    }
}

fn nearest(options: &[f32; 4], value: f32) -> Option<u8> {
    if value > options[3] {
        return None;
    }
    let mut best = 0usize;
    for i in 0..4 {
        if (options[i] - value).abs() < (options[best] - value).abs() {
            best = i;
        }
    }
    Some(best as u8)
}

/// AS7262 minimal driver — one one-shot six-channel conversion per `read()`.
///
/// Defaults written by [`init`](Self::init): gain 16×, `INT_T = 0x3B` (165.2 ms), one-shot bank mode
/// 3, both LED outputs off, INT disabled.
pub struct AS7262Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> AS7262Minimal<I2C> {
    /// Create a new `AS7262Minimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x49`, fixed).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    fn hw_read(&mut self, reg: u32) -> Result<u8, Error<I2C::Error>> {
        let mut b = [0u8; 1];
        register::read_register(&mut self.i2c, self.addr, reg, 1, &mut b)?;
        Ok(b[0])
    }

    fn hw_write(&mut self, value: u8) -> Result<(), Error<I2C::Error>> {
        register::write_register(&mut self.i2c, self.addr, HW_WRITE, 1, &[value])?;
        Ok(())
    }

    fn wait_status<D: DelayNs>(&mut self, mask: u8, want: u8, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        for _ in 0..POLL_ITERATIONS {
            if self.hw_read(HW_STATUS)? & mask == want {
                return Ok(());
            }
            delay.delay_us(POLL_STEP_US);
        }
        Err(Error::Timeout)
    }

    /// Read one virtual register through the STATUS/WRITE/READ handshake.
    ///
    /// # Arguments
    /// * `reg` — Virtual register address (`0x00`–`0x2B`).
    /// * `delay` — Delay provider for the handshake time-out.
    pub fn read_virtual<D: DelayNs>(&mut self, reg: u8, delay: &mut D) -> Result<u8, Error<I2C::Error>> {
        self.wait_status(TX_VALID, 0, delay)?;
        if self.hw_read(HW_STATUS)? & RX_VALID != 0 {
            self.hw_read(HW_READ)?; // drain a stale byte
        }
        self.hw_write(reg & 0x7F)?;
        self.wait_status(RX_VALID, RX_VALID, delay)?;
        self.hw_read(HW_READ)
    }

    /// Write one virtual register through the STATUS/WRITE handshake.
    ///
    /// # Arguments
    /// * `reg` — Virtual register address (`0x04`, `0x05` or `0x07` are writable).
    /// * `value` — Byte to write.
    /// * `delay` — Delay provider for the handshake time-out.
    pub fn write_virtual<D: DelayNs>(&mut self, reg: u8, value: u8, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.wait_status(TX_VALID, 0, delay)?;
        self.hw_write((reg & 0x7F) | 0x80)?;
        self.wait_status(TX_VALID, 0, delay)?;
        self.hw_write(value)
    }

    fn wait_boot<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        for _ in 0..200 {
            if let Ok(0x40) = self.read_virtual(REG_HW_VERSION, delay) {
                return Ok(());
            }
            delay.delay_ms(10);
        }
        Err(Error::NotFound)
    }

    fn reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.write_virtual(REG_CONTROL, CTRL_RST, delay)?;
        for _ in 0..100 {
            delay.delay_ms(10);
            if let Ok(v) = self.read_virtual(REG_CONTROL, delay) {
                if v & CTRL_RST == 0 {
                    return Ok(());
                }
            }
        }
        Err(Error::Timeout)
    }

    /// Wait for the chip to boot, soft-reset it and write the Minimal defaults.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.wait_boot(delay)?;
        self.reset(delay)?;
        self.write_virtual(REG_INT_T, INT_T_DEFAULT, delay)?;
        self.write_virtual(REG_LED, 0x00, delay)?;
        self.write_virtual(REG_CONTROL, GAIN_DEFAULT << 4, delay)
    }

    fn start_one_shot<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let ctrl = self.read_virtual(REG_CONTROL, delay)?;
        self.write_virtual(REG_CONTROL, (ctrl & 0x70) | (3 << 2), delay)
    }

    fn wait_data<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let int_ms = self.read_virtual(REG_INT_T, delay)? as u32 * 28 / 10;
        let budget_ms = 2 * int_ms + 1000;
        let mut waited = 0u32;
        loop {
            if self.read_virtual(REG_CONTROL, delay)? & CTRL_DATA_RDY != 0 {
                return Ok(());
            }
            if waited >= budget_ms {
                return Err(Error::Timeout);
            }
            delay.delay_ms(5);
            waited += 5;
        }
    }

    pub(crate) fn read_calibrated_regs<D: DelayNs>(&mut self, delay: &mut D) -> Result<Channels<f32>, Error<I2C::Error>> {
        let mut out = [0f32; 6];
        for ch in 0..6u8 {
            let mut b = [0u8; 4];
            for i in 0..4u8 {
                b[i as usize] = self.read_virtual(REG_CAL + 4 * ch + i, delay)?;
            }
            out[ch as usize] = f32::from_be_bytes(b);
        }
        Ok(Channels::from_array(out))
    }

    /// Run one one-shot conversion and return the six calibrated channels.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<Channels<f32>, Error<I2C::Error>> {
        self.start_one_shot(delay)?;
        self.wait_data(delay)?;
        self.read_calibrated_regs(delay)
    }

    /// Read the device temperature in °C.
    pub fn temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(self.read_virtual(REG_TEMP, delay)? as f32)
    }
}

/// AS7262 full driver — raw counts, gain, integration time, bank modes, LEDs, interrupt, versions.
///
/// Wraps an [`AS7262Minimal`] and re-exports its methods.
pub struct AS7262Full<I2C> {
    inner: AS7262Minimal<I2C>,
}

impl<I2C: I2c> AS7262Full<I2C> {
    /// Create a new `AS7262Full`; the constructor does no bus access.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: AS7262Minimal::new(i2c, addr) }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Wait for the chip to boot, soft-reset it and write the Minimal defaults.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.init(delay)
    }

    /// Run one one-shot conversion and return the six calibrated channels.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<Channels<f32>, Error<I2C::Error>> {
        self.inner.read(delay)
    }

    /// Read the device temperature in °C.
    pub fn temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        self.inner.temperature(delay)
    }

    /// Read one virtual register (see [`AS7262Minimal::read_virtual`]).
    pub fn read_virtual<D: DelayNs>(&mut self, reg: u8, delay: &mut D) -> Result<u8, Error<I2C::Error>> {
        self.inner.read_virtual(reg, delay)
    }

    /// Write one virtual register (see [`AS7262Minimal::write_virtual`]).
    pub fn write_virtual<D: DelayNs>(&mut self, reg: u8, value: u8, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.write_virtual(reg, value, delay)
    }

    fn read_raw_regs<D: DelayNs>(&mut self, delay: &mut D) -> Result<Channels<u16>, Error<I2C::Error>> {
        let mut out = [0u16; 6];
        for ch in 0..6u8 {
            let hi = self.inner.read_virtual(REG_RAW + 2 * ch, delay)?;
            let lo = self.inner.read_virtual(REG_RAW + 2 * ch + 1, delay)?;
            out[ch as usize] = ((hi as u16) << 8) | lo as u16;
        }
        Ok(Channels::from_array(out))
    }

    /// Run one one-shot conversion and return the raw 16-bit counts.
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<Channels<u16>, Error<I2C::Error>> {
        self.inner.start_one_shot(delay)?;
        self.inner.wait_data(delay)?;
        self.read_raw_regs(delay)
    }

    /// Start a one-shot conversion without waiting for it (`BANK` = 3).
    pub fn measure<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.start_one_shot(delay)
    }

    /// `true` if a conversion result is waiting (`CONTROL_SETUP.DATA_RDY`).
    pub fn data_ready<D: DelayNs>(&mut self, delay: &mut D) -> Result<bool, Error<I2C::Error>> {
        Ok(self.inner.read_virtual(REG_CONTROL, delay)? & CTRL_DATA_RDY != 0)
    }

    /// Read the last calibrated results without starting a conversion.
    pub fn read_calibrated<D: DelayNs>(&mut self, delay: &mut D) -> Result<Channels<f32>, Error<I2C::Error>> {
        self.inner.read_calibrated_regs(delay)
    }

    /// Read the last raw counts without starting a conversion.
    pub fn read_raw_last<D: DelayNs>(&mut self, delay: &mut D) -> Result<Channels<u16>, Error<I2C::Error>> {
        self.read_raw_regs(delay)
    }

    /// Read one calibrated channel (0–5 = V, B, G, Y, O, R) of the last conversion.
    pub fn read_channel<D: DelayNs>(&mut self, index: u8, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        if index > 5 {
            return Err(Error::InvalidArgument);
        }
        let mut b = [0u8; 4];
        for i in 0..4u8 {
            b[i as usize] = self.inner.read_virtual(REG_CAL + 4 * index + i, delay)?;
        }
        Ok(f32::from_be_bytes(b))
    }

    /// Read one raw channel (0–5) of the last conversion.
    pub fn read_channel_raw<D: DelayNs>(&mut self, index: u8, delay: &mut D) -> Result<u16, Error<I2C::Error>> {
        if index > 5 {
            return Err(Error::InvalidArgument);
        }
        let hi = self.inner.read_virtual(REG_RAW + 2 * index, delay)?;
        let lo = self.inner.read_virtual(REG_RAW + 2 * index + 1, delay)?;
        Ok(((hi as u16) << 8) | lo as u16)
    }

    fn update_control<D: DelayNs>(&mut self, mask: u8, bits: u8, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let ctrl = self.inner.read_virtual(REG_CONTROL, delay)?;
        self.inner.write_virtual(REG_CONTROL, (ctrl & 0x7C & !mask) | bits, delay)
    }

    /// Set the analog gain: index 0–3, or the gain 1, 3.7, 16 or 64 (1 means 1×).
    pub fn set_gain<D: DelayNs>(&mut self, gain: f32, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let index = if let Some(i) = GAINS.iter().position(|&g| g == gain) {
            i as u8
        } else if gain == 0.0 || gain == 2.0 || gain == 3.0 {
            gain as u8
        } else {
            return Err(Error::InvalidArgument);
        };
        self.update_control(0x30, index << 4, delay)
    }

    /// The analog gain multiplier (1, 3.7, 16 or 64).
    pub fn get_gain<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(GAINS[((self.inner.read_virtual(REG_CONTROL, delay)? >> 4) & 0x03) as usize])
    }

    /// Set the integration time per bank in milliseconds (2.8–714, rounded to a multiple of 2.8 ms).
    pub fn set_integration_time<D: DelayNs>(&mut self, ms: f32, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let n = (ms / STEP_MS + 0.5) as i32;
        if !(1..=255).contains(&n) {
            return Err(Error::InvalidArgument);
        }
        self.inner.write_virtual(REG_INT_T, n as u8, delay)
    }

    /// The integration time per bank in milliseconds.
    pub fn get_integration_time<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Error<I2C::Error>> {
        Ok(self.inner.read_virtual(REG_INT_T, delay)? as f32 * STEP_MS)
    }

    /// Set the bank mode: 0 (V,B,G,Y continuous), 1 (G,Y,O,R continuous), 2 (all six continuous), 3 (one-shot).
    pub fn set_mode<D: DelayNs>(&mut self, mode: u8, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        if mode > 3 {
            return Err(Error::InvalidArgument);
        }
        self.update_control(0x0C, mode << 2, delay)
    }

    /// The bank mode (0–3).
    pub fn get_mode<D: DelayNs>(&mut self, delay: &mut D) -> Result<u8, Error<I2C::Error>> {
        Ok((self.inner.read_virtual(REG_CONTROL, delay)? >> 2) & 0x03)
    }

    /// Switch to continuous conversions (bank mode 0, 1 or 2).
    pub fn set_continuous<D: DelayNs>(&mut self, mode: u8, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        if mode > 2 {
            return Err(Error::InvalidArgument);
        }
        self.set_mode(mode, delay)
    }

    /// Enable or disable the INT pin output (`CONTROL_SETUP.INT`).
    pub fn set_interrupt<D: DelayNs>(&mut self, enabled: bool, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.update_control(CTRL_INT, if enabled { CTRL_INT } else { 0 }, delay)
    }

    /// Control the driver LED output; `current_ma` is rounded to 12.5, 25, 50 or 100 mA.
    pub fn set_led<D: DelayNs>(&mut self, on: bool, current_ma: f32, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let index = nearest(&DRV_CURRENTS, current_ma).ok_or(Error::InvalidArgument)?;
        let led = self.inner.read_virtual(REG_LED, delay)? & 0x07;
        self.inner.write_virtual(REG_LED, led | (index << 4) | if on { 0x08 } else { 0 }, delay)
    }

    /// Control the indicator LED output; `current_ma` is rounded to 1, 2, 4 or 8 mA.
    pub fn set_indicator_led<D: DelayNs>(&mut self, on: bool, current_ma: f32, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let index = nearest(&IND_CURRENTS, current_ma).ok_or(Error::InvalidArgument)?;
        let led = self.inner.read_virtual(REG_LED, delay)? & 0x38;
        self.inner.write_virtual(REG_LED, led | (index << 1) | if on { 0x01 } else { 0 }, delay)
    }

    /// Soft-reset the chip (all registers return to their defaults).
    pub fn soft_reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.reset(delay)
    }

    /// `(device_type, hardware_version)` — expect `(0x40, 0x3E)`.
    pub fn hardware_version<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u8, u8), Error<I2C::Error>> {
        Ok((self.inner.read_virtual(REG_HW_VERSION, delay)?, self.inner.read_virtual(REG_HW_VERSION + 1, delay)?))
    }

    /// The firmware version as `(major, minor, sub)`.
    pub fn firmware_version<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u8, u8, u8), Error<I2C::Error>> {
        let r2 = self.inner.read_virtual(REG_FW_VERSION, delay)?;
        let r3 = self.inner.read_virtual(REG_FW_VERSION + 1, delay)?;
        Ok((r3 >> 4, ((r3 & 0x0F) << 2) | (r2 >> 6), r2 & 0x3F))
    }

    /// The device type byte (virtual register `0x00`, expect `0x40`).
    pub fn device_type<D: DelayNs>(&mut self, delay: &mut D) -> Result<u8, Error<I2C::Error>> {
        self.inner.read_virtual(REG_HW_VERSION, delay)
    }

    /// Read `CONTROL_SETUP` (releases INT) and return `DATA_RDY` (0 or 1).
    pub fn poll_interrupt<D: DelayNs>(&mut self, delay: &mut D) -> Result<u8, Error<I2C::Error>> {
        Ok(((self.inner.read_virtual(REG_CONTROL, delay)? & CTRL_DATA_RDY) != 0) as u8)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = 0x49;

    fn status(v: u8) -> T {
        T::write_read(ADDR, vec![0x00], vec![v])
    }

    /// Transactions of one idle virtual read returning `val`.
    fn vread(reg: u8, val: u8) -> Vec<T> {
        vec![
            status(0x00),
            status(0x00),
            T::write(ADDR, vec![0x01, reg]),
            status(0x01),
            T::write_read(ADDR, vec![0x02], vec![val]),
        ]
    }

    /// Transactions of one idle virtual write.
    fn vwrite(reg: u8, val: u8) -> Vec<T> {
        vec![
            status(0x00),
            T::write(ADDR, vec![0x01, reg | 0x80]),
            status(0x00),
            T::write(ADDR, vec![0x01, val]),
        ]
    }

    fn cat(parts: Vec<Vec<T>>) -> Vec<T> {
        parts.into_iter().flatten().collect()
    }

    #[test]
    fn constructor_no_bus_access() {
        let t: Vec<T> = vec![];
        let mut i2c = I2cMock::new(&t);
        let s = AS7262Full::new(i2c.clone(), ADDR);
        drop(s);
        i2c.done();
    }

    #[test]
    fn virtual_read_handshake() {
        let t = vread(0x06, 25);
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Minimal::new(i2c.clone(), ADDR);
        assert_eq!(s.temperature(&mut NoopDelay).unwrap(), 25.0);
        i2c.done();
    }

    #[test]
    fn virtual_read_drains_stale_byte() {
        let t = cat(vec![
            vec![status(0x00), status(0x01), T::write_read(ADDR, vec![0x02], vec![0xAA])],
            vec![T::write(ADDR, vec![0x01, 0x06]), status(0x01), T::write_read(ADDR, vec![0x02], vec![30])],
        ]);
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Minimal::new(i2c.clone(), ADDR);
        assert_eq!(s.temperature(&mut NoopDelay).unwrap(), 30.0);
        i2c.done();
    }

    #[test]
    fn virtual_write_handshake() {
        let t = vwrite(0x05, 0x10);
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Full::new(i2c.clone(), ADDR);
        s.write_virtual(0x05, 0x10, &mut NoopDelay).unwrap();
        i2c.done();
    }

    #[test]
    fn handshake_times_out() {
        let t: Vec<T> = (0..POLL_ITERATIONS).map(|_| status(0x02)).collect();
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Minimal::new(i2c.clone(), ADDR);
        assert_eq!(s.read_virtual(0x00, &mut NoopDelay), Err(Error::Timeout));
        i2c.done();
    }

    #[test]
    fn init_sequence() {
        let t = cat(vec![
            vread(0x00, 0x40),
            vwrite(0x04, 0x80),
            vread(0x04, 0x08),
            vwrite(0x05, 0x3B),
            vwrite(0x07, 0x00),
            vwrite(0x04, 0x20),
        ]);
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Minimal::new(i2c.clone(), ADDR);
        s.init(&mut NoopDelay).unwrap();
        i2c.done();
    }

    #[test]
    fn init_not_found() {
        let mut t = Vec::new();
        for _ in 0..200 {
            t.extend(vread(0x00, 0x00));
        }
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Minimal::new(i2c.clone(), ADDR);
        assert_eq!(s.init(&mut NoopDelay), Err(Error::NotFound));
        i2c.done();
    }

    #[test]
    fn read_one_shot_returns_calibrated() {
        let vals = [1.0f32, 2.5, 3.0, 4.0, 5.0, 6.5];
        let mut parts = vec![
            vread(0x04, 0x20),
            vwrite(0x04, 0x2C),
            vread(0x05, 0x3B),
            vread(0x04, 0x2E),
        ];
        for (ch, v) in vals.iter().enumerate() {
            for (i, b) in v.to_be_bytes().iter().enumerate() {
                parts.push(vread(0x14 + 4 * ch as u8 + i as u8, *b));
            }
        }
        let t = cat(parts);
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Minimal::new(i2c.clone(), ADDR);
        let c = s.read(&mut NoopDelay).unwrap();
        assert_eq!(c.to_array(), vals);
        i2c.done();
    }

    #[test]
    fn read_raw_composes_words() {
        let mut parts = vec![vread(0x04, 0x20), vwrite(0x04, 0x2C), vread(0x05, 0x3B), vread(0x04, 0x2E)];
        for ch in 0..6u8 {
            parts.push(vread(0x08 + 2 * ch, ch + 1));
            parts.push(vread(0x09 + 2 * ch, 0xFF));
        }
        let t = cat(parts);
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Full::new(i2c.clone(), ADDR);
        let c = s.read_raw(&mut NoopDelay).unwrap();
        assert_eq!(c.violet, 0x01FF);
        assert_eq!(c.red, 0x06FF);
        i2c.done();
    }

    #[test]
    fn conversion_times_out() {
        let mut t = vec![vread(0x04, 0x20), vwrite(0x04, 0x2C), vread(0x05, 0x01)].into_iter().flatten().collect::<Vec<_>>();
        // budget = 2 * 2 ms + 1000 ms, polled every 5 ms
        for _ in 0..=((2 * 2 + 1000) / 5 + 1) {
            t.extend(vread(0x04, 0x2C));
        }
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Minimal::new(i2c.clone(), ADDR);
        assert_eq!(s.read(&mut NoopDelay), Err(Error::Timeout));
        i2c.done();
    }

    #[test]
    fn set_gain_values() {
        for (arg, bits) in [(1.0f32, 0u8), (3.7, 1), (16.0, 2), (64.0, 3), (0.0, 0), (2.0, 2), (3.0, 3)] {
            let t = cat(vec![vread(0x04, 0x0C), vwrite(0x04, 0x0C | (bits << 4))]);
            let mut i2c = I2cMock::new(&t);
            let mut s = AS7262Full::new(i2c.clone(), ADDR);
            s.set_gain(arg, &mut NoopDelay).unwrap();
            i2c.done();
        }
        let t: Vec<T> = vec![];
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Full::new(i2c.clone(), ADDR);
        assert_eq!(s.set_gain(5.0, &mut NoopDelay), Err(Error::InvalidArgument));
        i2c.done();
    }

    #[test]
    fn get_gain_reads_bits() {
        let t = vread(0x04, 0x30);
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Full::new(i2c.clone(), ADDR);
        assert_eq!(s.get_gain(&mut NoopDelay).unwrap(), 64.0);
        i2c.done();
    }

    #[test]
    fn integration_time_conversion_and_range() {
        let t = vwrite(0x05, 18);
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Full::new(i2c.clone(), ADDR);
        s.set_integration_time(50.0, &mut NoopDelay).unwrap();
        i2c.done();
        let none: Vec<T> = vec![];
        let mut i2c = I2cMock::new(&none);
        let mut s = AS7262Full::new(i2c.clone(), ADDR);
        assert_eq!(s.set_integration_time(1.0, &mut NoopDelay), Err(Error::InvalidArgument));
        assert_eq!(s.set_integration_time(800.0, &mut NoopDelay), Err(Error::InvalidArgument));
        i2c.done();
        let t = vread(0x05, 18);
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Full::new(i2c.clone(), ADDR);
        assert!((s.get_integration_time(&mut NoopDelay).unwrap() - 50.4).abs() < 1e-3);
        i2c.done();
    }

    #[test]
    fn mode_and_continuous() {
        let t = cat(vec![vread(0x04, 0x20), vwrite(0x04, 0x28)]);
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Full::new(i2c.clone(), ADDR);
        s.set_mode(2, &mut NoopDelay).unwrap();
        i2c.done();
        let none: Vec<T> = vec![];
        let mut i2c = I2cMock::new(&none);
        let mut s = AS7262Full::new(i2c.clone(), ADDR);
        assert_eq!(s.set_continuous(3, &mut NoopDelay), Err(Error::InvalidArgument));
        assert_eq!(s.set_mode(4, &mut NoopDelay), Err(Error::InvalidArgument));
        i2c.done();
    }

    #[test]
    fn interrupt_enable_preserves_other_bits() {
        let t = cat(vec![vread(0x04, 0x2E), vwrite(0x04, 0x6C)]);
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Full::new(i2c.clone(), ADDR);
        s.set_interrupt(true, &mut NoopDelay).unwrap();
        i2c.done();
    }

    #[test]
    fn leds_keep_each_other() {
        let t = cat(vec![vread(0x07, 0x05), vwrite(0x07, 0x05 | 0x18)]);
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Full::new(i2c.clone(), ADDR);
        s.set_led(true, 25.0, &mut NoopDelay).unwrap();
        i2c.done();
        let t = cat(vec![vread(0x07, 0x18), vwrite(0x07, 0x18 | 0x05)]);
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Full::new(i2c.clone(), ADDR);
        s.set_indicator_led(true, 4.0, &mut NoopDelay).unwrap();
        i2c.done();
        let none: Vec<T> = vec![];
        let mut i2c = I2cMock::new(&none);
        let mut s = AS7262Full::new(i2c.clone(), ADDR);
        assert_eq!(s.set_led(true, 200.0, &mut NoopDelay), Err(Error::InvalidArgument));
        assert_eq!(s.set_indicator_led(true, 9.0, &mut NoopDelay), Err(Error::InvalidArgument));
        i2c.done();
    }

    #[test]
    fn versions() {
        let t = cat(vec![vread(0x00, 0x40), vread(0x01, 0x3E)]);
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Full::new(i2c.clone(), ADDR);
        assert_eq!(s.hardware_version(&mut NoopDelay).unwrap(), (0x40, 0x3E));
        i2c.done();
        let t = cat(vec![vread(0x02, 0x41), vread(0x03, 0x21)]);
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Full::new(i2c.clone(), ADDR);
        assert_eq!(s.firmware_version(&mut NoopDelay).unwrap(), (2, 5, 1));
        i2c.done();
    }

    #[test]
    fn poll_interrupt_and_data_ready() {
        let t = cat(vec![vread(0x04, 0x02), vread(0x04, 0x00)]);
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Full::new(i2c.clone(), ADDR);
        assert_eq!(s.poll_interrupt(&mut NoopDelay).unwrap(), 1);
        assert!(!s.data_ready(&mut NoopDelay).unwrap());
        i2c.done();
    }

    #[test]
    fn read_channel_range_and_value() {
        let none: Vec<T> = vec![];
        let mut i2c = I2cMock::new(&none);
        let mut s = AS7262Full::new(i2c.clone(), ADDR);
        assert_eq!(s.read_channel(6, &mut NoopDelay), Err(Error::InvalidArgument));
        i2c.done();
        let b = 7.25f32.to_be_bytes();
        let t = cat((0..4).map(|i| vread(0x14 + 20 + i as u8, b[i])).collect());
        let mut i2c = I2cMock::new(&t);
        let mut s = AS7262Full::new(i2c.clone(), ADDR);
        assert_eq!(s.read_channel(5, &mut NoopDelay).unwrap(), 7.25);
        i2c.done();
    }
}
