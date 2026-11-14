//! TMP102 — 12-bit digital temperature sensor with ALERT output (Texas Instruments).
//!
//! Two-wire temperature sensor with 0.0625 °C resolution, ±0.5 °C typical
//! accuracy and a thermostat output driven by programmable `T_LOW`/`T_HIGH`
//! limits. Register-compatible with the TMP75/LM75 family. Four selectable
//! addresses (`0x48`–`0x4B`) via the `ADD0` pin.
//!
//! Registers are 16-bit, big-endian, addressed through a persistent pointer —
//! see `specs/temperature/tmp102.md`.
//!
//! ## Interrupts
//!
//! Rust exposes only [`Tmp102Full::poll_interrupt`] (no callback
//! subscription — polling is always caller-managed in this crate's `no_std`
//! Rust drivers). Wire it into an ISR on the `ALERT` pin or a polling loop.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register;

const REG_TEMPERATURE: u8 = 0x00;
const REG_CONFIG: u8 = 0x01;
const REG_T_LOW: u8 = 0x02;
const REG_T_HIGH: u8 = 0x03;

// CONFIGURATION (0x01) bits.
const CFG_OS: u16 = 0x8000;
const CFG_F_SHIFT: u16 = 11;
const CFG_F_MASK: u16 = 0x1800;
const CFG_POL: u16 = 0x0400;
const CFG_TM: u16 = 0x0200;
const CFG_SD: u16 = 0x0100;
const CFG_CR_SHIFT: u16 = 6;
const CFG_CR_MASK: u16 = 0x00C0;
const CFG_AL: u16 = 0x0020;
const CFG_EM: u16 = 0x0010;
const CFG_WRITABLE: u16 = 0x9FD0;
const CFG_RESERVED: u16 = 0x6000; // R1:R0, read-only, always 11

const LSB_C: f32 = 0.0625;
const LIMIT_MIN: f32 = -55.0;
const LIMIT_MAX: f32 = 150.0;
const CONVERSION_RATES: [f32; 4] = [0.25, 1.0, 4.0, 8.0];
const FAULT_QUEUES: [u8; 4] = [1, 2, 4, 6];

/// Default 7-bit I²C address (`ADD0` = GND). Valid range `0x48`–`0x4B`.
pub const TMP102_I2C_ADDRESS: u8 = 0x48;

/// Errors from the TMP102 driver's checked operations.
#[derive(Debug)]
pub enum Tmp102Error<E> {
    /// The underlying I²C bus returned an error.
    Bus(E),
    /// A conversion rate or fault-queue count outside the supported set.
    InvalidArgument,
    /// The one-shot conversion did not finish within 30 ms.
    Timeout,
}

impl<E> From<E> for Tmp102Error<E> {
    fn from(e: E) -> Self {
        Tmp102Error::Bus(e)
    }
}

fn signed(value: u16, bits: u32) -> i16 {
    let sign = 1u16 << (bits - 1);
    ((value ^ sign) as i32 - sign as i32) as i16
}

/// Raw signed count of a TEMPERATURE register; bit 0 = 1 marks the 13-bit format.
fn decode_raw(raw16: u16) -> i16 {
    if raw16 & 0x0001 != 0 { signed(raw16 >> 3, 13) } else { signed(raw16 >> 4, 12) }
}

fn encode_limit(celsius: f32, extended: bool) -> u16 {
    let c = celsius.clamp(LIMIT_MIN, LIMIT_MAX);
    let steps = c / LSB_C;
    let mut counts = if steps >= 0.0 { (steps + 0.5) as i32 } else { -((-steps + 0.5) as i32) };
    if extended {
        ((counts << 3) & 0xFFF8) as u16
    } else {
        if counts > 2047 { counts = 2047; }
        ((counts << 4) & 0xFFF0) as u16
    }
}

/// TMP102 — minimal interface: read the temperature.
pub struct Tmp102Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Tmp102Minimal<I2C> {
    /// Construct the driver. Makes no bus access — the power-on default
    /// (continuous conversion at 4 Hz, comparator mode, 12-bit) already
    /// serves the primary use case. `addr` is `0x48`–`0x4B`.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr }
    }

    fn read_reg(&mut self, reg: u8) -> Result<u16, I2C::Error> {
        let mut buf = [0u8; 2];
        register::read_register(&mut self.i2c, self.addr, reg.into(), 1, &mut buf)?;
        Ok(((buf[0] as u16) << 8) | buf[1] as u16)
    }

    fn write_reg(&mut self, reg: u8, value: u16) -> Result<(), I2C::Error> {
        register::write_register(&mut self.i2c, self.addr, reg.into(), 1, &[(value >> 8) as u8, value as u8])
    }

    /// Read the temperature in °C.
    ///
    /// Decodes the 12-bit (0.0625 °C per LSB) or, in extended mode, 13-bit
    /// two's-complement value; the format is taken from bit 0 of the result.
    pub fn read_temperature(&mut self) -> Result<f32, I2C::Error> {
        Ok(decode_raw(self.read_reg(REG_TEMPERATURE)?) as f32 * LSB_C)
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }
}

/// TMP102 — full interface: extends [`Tmp102Minimal`] with conversion rate,
/// extended mode, the `T_LOW`/`T_HIGH` limits, fault queue, polarity,
/// thermostat mode, shutdown/one-shot, alert status and general-call reset.
pub struct Tmp102Full<I2C> {
    inner: Tmp102Minimal<I2C>,
}

impl<I2C: I2c> Tmp102Full<I2C> {
    /// Construct the driver; makes no bus access.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Tmp102Minimal::new(i2c, addr) }
    }

    /// Read the temperature in °C. Delegates to [`Tmp102Minimal::read_temperature`].
    pub fn read_temperature(&mut self) -> Result<f32, I2C::Error> {
        self.inner.read_temperature()
    }

    /// Read the raw signed temperature count (`count * 0.0625` °C).
    pub fn read_raw(&mut self) -> Result<i16, I2C::Error> {
        Ok(decode_raw(self.inner.read_reg(REG_TEMPERATURE)?))
    }

    /// Read the 16-bit `CONFIGURATION` register (reset value `0x60A0`).
    pub fn read_config(&mut self) -> Result<u16, I2C::Error> {
        self.inner.read_reg(REG_CONFIG)
    }

    /// Write the 16-bit `CONFIGURATION` register; read-only bits are masked out.
    pub fn write_config(&mut self, value: u16) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_CONFIG, (value & CFG_WRITABLE) | CFG_RESERVED)
    }

    fn update_config(&mut self, clear: u16, set: u16) -> Result<(), I2C::Error> {
        let config = (self.read_config()? & !clear) | set;
        self.write_config(config)
    }

    /// Set the conversion rate: 0.25, 1, 4 or 8 Hz.
    pub fn set_conversion_rate(&mut self, hz: f32) -> Result<(), Tmp102Error<I2C::Error>> {
        let code = CONVERSION_RATES.iter().position(|&r| r == hz).ok_or(Tmp102Error::InvalidArgument)? as u16;
        self.update_config(CFG_CR_MASK, code << CFG_CR_SHIFT)?;
        Ok(())
    }

    /// Enable or disable the 13-bit extended mode (`EM` bit).
    pub fn set_extended_mode(&mut self, on: bool) -> Result<(), I2C::Error> {
        self.update_config(CFG_EM, if on { CFG_EM } else { 0 })
    }

    fn set_limit(&mut self, reg: u8, celsius: f32) -> Result<(), I2C::Error> {
        let extended = self.read_config()? & CFG_EM != 0;
        self.inner.write_reg(reg, encode_limit(celsius, extended))
    }

    fn get_limit(&mut self, reg: u8) -> Result<f32, I2C::Error> {
        let extended = self.read_config()? & CFG_EM != 0;
        let raw16 = self.inner.read_reg(reg)?;
        let counts = if extended { signed(raw16 >> 3, 13) } else { signed(raw16 >> 4, 12) };
        Ok(counts as f32 * LSB_C)
    }

    /// Set the `T_HIGH` limit (reset 80 °C), rounded to 0.0625 °C and clamped
    /// to −55…+150 °C (12-bit mode: up to +127.9375 °C).
    pub fn set_t_high(&mut self, celsius: f32) -> Result<(), I2C::Error> {
        self.set_limit(REG_T_HIGH, celsius)
    }

    /// Read the `T_HIGH` limit in °C.
    pub fn get_t_high(&mut self) -> Result<f32, I2C::Error> {
        self.get_limit(REG_T_HIGH)
    }

    /// Set the `T_LOW` limit (reset 75 °C); same coding as [`Self::set_t_high`].
    pub fn set_t_low(&mut self, celsius: f32) -> Result<(), I2C::Error> {
        self.set_limit(REG_T_LOW, celsius)
    }

    /// Read the `T_LOW` limit in °C.
    pub fn get_t_low(&mut self) -> Result<f32, I2C::Error> {
        self.get_limit(REG_T_LOW)
    }

    /// Set the number of consecutive faults that trigger `ALERT`: 1, 2, 4 or 6.
    pub fn set_fault_queue(&mut self, count: u8) -> Result<(), Tmp102Error<I2C::Error>> {
        let code = FAULT_QUEUES.iter().position(|&c| c == count).ok_or(Tmp102Error::InvalidArgument)? as u16;
        self.update_config(CFG_F_MASK, code << CFG_F_SHIFT)?;
        Ok(())
    }

    /// Set the `ALERT` polarity (`POL`): `true` = active high.
    pub fn set_polarity(&mut self, active_high: bool) -> Result<(), I2C::Error> {
        self.update_config(CFG_POL, if active_high { CFG_POL } else { 0 })
    }

    /// Select the thermostat mode (`TM`): `true` = interrupt, `false` = comparator.
    pub fn set_thermostat_mode(&mut self, interrupt: bool) -> Result<(), I2C::Error> {
        self.update_config(CFG_TM, if interrupt { CFG_TM } else { 0 })
    }

    /// Shut the device down after the current conversion (`SD` = 1).
    pub fn shutdown(&mut self) -> Result<(), I2C::Error> {
        self.update_config(0, CFG_SD)
    }

    /// Resume continuous conversion (`SD` = 0).
    pub fn wake(&mut self) -> Result<(), I2C::Error> {
        self.update_config(CFG_SD, 0)
    }

    /// Take a single conversion and return the temperature in °C.
    ///
    /// Puts the device in shutdown if needed, sets `OS` = 1, polls `OS` until
    /// the conversion is done (30 ms timeout) and restores the previous mode.
    pub fn one_shot<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Tmp102Error<I2C::Error>> {
        let mut config = self.read_config()?;
        let was_running = config & CFG_SD == 0;
        if was_running {
            config |= CFG_SD;
            self.write_config(config)?;
        }
        self.write_config(config | CFG_OS)?;
        let mut done = false;
        for _ in 0..30 {
            delay.delay_ms(1);
            if self.read_config()? & CFG_OS != 0 {
                done = true;
                break;
            }
        }
        let temperature = self.read_temperature()?;
        if was_running {
            self.wake()?;
        }
        if done { Ok(temperature) } else { Err(Tmp102Error::Timeout) }
    }

    /// `true` while `ALERT` is active (the `AL` bit corrected for `POL`).
    pub fn alert_state(&mut self) -> Result<bool, I2C::Error> {
        let config = self.read_config()?;
        let al = config & CFG_AL != 0;
        // AL reads 1 while inactive with POL = 0 (inverted); POL = 1 flips it.
        Ok(if config & CFG_POL != 0 { al } else { !al })
    }

    /// Read the alert state and clear an interrupt-mode alert (reads
    /// `CONFIGURATION`, then `TEMPERATURE`). `true` while alerting.
    pub fn poll_interrupt(&mut self) -> Result<bool, I2C::Error> {
        let alerting = self.alert_state()?;
        self.inner.read_reg(REG_TEMPERATURE)?;
        Ok(alerting)
    }

    /// Send the general-call reset (`0x00 0x06`): resets **every** general-call
    /// device on the bus to power-up values.
    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        self.inner.i2c.write(0x00, &[0x06])
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x48;

    fn rd(reg: u8, value: u16) -> I2cTransaction {
        I2cTransaction::write_read(ADDR, vec![reg], vec![(value >> 8) as u8, value as u8])
    }

    fn wr(reg: u8, value: u16) -> I2cTransaction {
        I2cTransaction::write(ADDR, vec![reg, (value >> 8) as u8, value as u8])
    }

    #[test]
    fn minimal_temperature_decoding() {
        let t = [
            rd(REG_TEMPERATURE, 0x1900), rd(REG_TEMPERATURE, 0xE700), rd(REG_TEMPERATURE, 0x7FF0),
            rd(REG_TEMPERATURE, 0xC900),
            rd(REG_TEMPERATURE, (2400 << 3) | 1),                       // extended 150 °C
            rd(REG_TEMPERATURE, (((-1600i32) << 3) as u16) | 1),        // extended -100 °C
        ];
        let mut i2c = I2cMock::new(&t);
        let mut s = Tmp102Minimal::new(i2c.clone(), ADDR);
        assert_eq!(s.read_temperature().unwrap(), 25.0);
        assert_eq!(s.read_temperature().unwrap(), -25.0);
        assert_eq!(s.read_temperature().unwrap(), 127.9375);
        assert_eq!(s.read_temperature().unwrap(), -55.0);
        assert_eq!(s.read_temperature().unwrap(), 150.0);
        assert_eq!(s.read_temperature().unwrap(), -100.0);
        i2c.done();
    }

    #[test]
    fn config_read_modify_write() {
        let t = [
            rd(REG_CONFIG, 0x6080), wr(REG_CONFIG, 0x60C0),   // 8 Hz
            rd(REG_CONFIG, 0x60C0), wr(REG_CONFIG, 0x68C0),   // fault queue 2
            rd(REG_CONFIG, 0x68C0), wr(REG_CONFIG, 0x6CC0),   // polarity high
            rd(REG_CONFIG, 0x6CC0), wr(REG_CONFIG, 0x6EC0),   // interrupt mode
            rd(REG_CONFIG, 0x6EC0), wr(REG_CONFIG, 0x6FC0),   // shutdown
            rd(REG_CONFIG, 0x6FC0), wr(REG_CONFIG, 0x6EC0),   // wake
        ];
        let mut i2c = I2cMock::new(&t);
        let mut s = Tmp102Full::new(i2c.clone(), ADDR);
        s.set_conversion_rate(8.0).unwrap_or_else(|_| panic!());
        s.set_fault_queue(2).unwrap_or_else(|_| panic!());
        s.set_polarity(true).unwrap();
        s.set_thermostat_mode(true).unwrap();
        s.shutdown().unwrap();
        s.wake().unwrap();
        i2c.done();
    }

    #[test]
    fn invalid_arguments_make_no_bus_access() {
        let mut i2c = I2cMock::new(&[]);
        let mut s = Tmp102Full::new(i2c.clone(), ADDR);
        assert!(matches!(s.set_conversion_rate(2.0), Err(Tmp102Error::InvalidArgument)));
        assert!(matches!(s.set_fault_queue(3), Err(Tmp102Error::InvalidArgument)));
        i2c.done();
    }

    #[test]
    fn limits_normal_and_extended() {
        let t = [
            rd(REG_CONFIG, 0x60A0), wr(REG_T_HIGH, 0x1E00),   // 30 °C
            rd(REG_CONFIG, 0x60A0), rd(REG_T_HIGH, 0x1E00),
            rd(REG_CONFIG, 0x60B0), wr(REG_T_HIGH, 2240 << 3), // extended 140 °C
            rd(REG_CONFIG, 0x60B0), rd(REG_T_HIGH, 2240 << 3),
            rd(REG_CONFIG, 0x60A0), wr(REG_T_LOW, 0x7FF0),    // clamps to 127.9375
        ];
        let mut i2c = I2cMock::new(&t);
        let mut s = Tmp102Full::new(i2c.clone(), ADDR);
        s.set_t_high(30.0).unwrap();
        assert_eq!(s.get_t_high().unwrap(), 30.0);
        s.set_t_high(140.0).unwrap();
        assert_eq!(s.get_t_high().unwrap(), 140.0);
        s.set_t_low(500.0).unwrap();
        i2c.done();
    }

    #[test]
    fn one_shot_restores_running_mode() {
        let t = [
            rd(REG_CONFIG, 0x6080), wr(REG_CONFIG, 0x6180),            // shutdown
            wr(REG_CONFIG, 0xE180),                                    // OS = 1
            rd(REG_CONFIG, 0xE180),                                    // done
            rd(REG_TEMPERATURE, 0x1900),
            rd(REG_CONFIG, 0xE180), wr(REG_CONFIG, 0xE080),            // wake
        ];
        let mut i2c = I2cMock::new(&t);
        let mut s = Tmp102Full::new(i2c.clone(), ADDR);
        assert_eq!(s.one_shot(&mut NoopDelay::new()).unwrap_or(0.0), 25.0);
        i2c.done();
    }

    #[test]
    fn alert_state_and_poll() {
        let t = [
            rd(REG_CONFIG, 0x6020), rd(REG_CONFIG, 0x6000), rd(REG_CONFIG, 0x6420), rd(REG_CONFIG, 0x6400),
            rd(REG_CONFIG, 0x6000), rd(REG_TEMPERATURE, 0x1900),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut s = Tmp102Full::new(i2c.clone(), ADDR);
        assert!(!s.alert_state().unwrap());
        assert!(s.alert_state().unwrap());
        assert!(s.alert_state().unwrap());
        assert!(!s.alert_state().unwrap());
        assert!(s.poll_interrupt().unwrap());
        i2c.done();
    }

    #[test]
    fn general_call_reset() {
        let mut i2c = I2cMock::new(&[I2cTransaction::write(0x00, vec![0x06])]);
        let mut s = Tmp102Full::new(i2c.clone(), ADDR);
        s.reset().unwrap();
        i2c.done();
    }
}
