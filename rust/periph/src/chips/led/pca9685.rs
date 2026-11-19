//! PCA9685 — 16-channel, 12-bit PWM LED/servo controller (NXP Semiconductors).
//!
//! Communicates over I²C (address `0x40`–`0x6F` or `0x71`–`0x77`, six address pins; `0x70` is the
//! LED All Call address). Each output has its own 12-bit ON and OFF counter compare value, so both
//! duty cycle and phase delay are programmable; one prescaler sets the common PWM frequency
//! (24 Hz to 1526 Hz with the internal 25 MHz oscillator).
//!
//! `new` makes no bus access; `init` puts the chip to sleep, sets 1 kHz, wakes it with auto-increment on
//! and ALLCALL off, and waits for the oscillator. Methods that need a settling time take a `delay`.
//!
//! ## Constants
//!
//! [`ADDR_DEFAULT`], [`CHANNELS`], [`OFF_LOW`], [`OFF_HIGH_OR_HIGHZ`], [`OFF_HIGHZ`]

use core::ops::{Deref, DerefMut};
use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

/// Default I²C address (all address pins low).
pub const ADDR_DEFAULT: u8 = 0x40;
/// Number of PWM channels.
pub const CHANNELS: u8 = 16;
/// `set_output_mode` off state: LEDn = 0 while OE = 1.
pub const OFF_LOW: u8 = 0;
/// `set_output_mode` off state: LEDn = 1 (totem-pole) or high-Z (open-drain) while OE = 1.
pub const OFF_HIGH_OR_HIGHZ: u8 = 1;
/// `set_output_mode` off state: LEDn high-Z while OE = 1.
pub const OFF_HIGHZ: u8 = 2;

const REG_MODE1: u8 = 0x00;
const REG_MODE2: u8 = 0x01;
const REG_SUBADR1: u8 = 0x02;
const REG_ALLCALLADR: u8 = 0x05;
const REG_LED0: u8 = 0x06;
const REG_ALL_LED: u8 = 0xFA;
const REG_PRE_SCALE: u8 = 0xFE;

const RESTART: u8 = 0x80;
const EXTCLK: u8 = 0x40;
const AI: u8 = 0x20;
const SLEEP: u8 = 0x10;

/// Driver error.
#[derive(Debug, PartialEq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// An argument is out of range (channel, duty, counts, frequency, address, index, off state).
    InvalidArgument,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

fn duty_bytes<E>(duty: f32) -> Result<[u8; 4], Error<E>> {
    if !(0.0..=1.0).contains(&duty) {
        return Err(Error::InvalidArgument);
    }
    let off = (duty * 4096.0 + 0.5) as u32;
    Ok(if off == 0 {
        [0, 0, 0, 0x10]
    } else if off >= 4096 {
        [0, 0x10, 0, 0]
    } else {
        [0, 0, (off & 0xFF) as u8, (off >> 8) as u8]
    })
}

fn pwm_bytes<E>(on: u16, off: u16) -> Result<[u8; 4], Error<E>> {
    if on > 4095 || off > 4095 || on == off {
        return Err(Error::InvalidArgument);
    }
    Ok([(on & 0xFF) as u8, (on >> 8) as u8, (off & 0xFF) as u8, (off >> 8) as u8])
}

/// PCA9685 minimal driver — dim LEDs / drive servos on 16 channels with one call each.
///
/// Defaults after [`init`](Self::init): 1 kHz PWM (PRE_SCALE = 5), totem-pole outputs, not
/// inverted, outputs change on STOP, auto-increment on, ALLCALL off; all channels off.
pub struct PCA9685Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> PCA9685Minimal<I2C> {
    /// Create a new `PCA9685Minimal`; no bus access is made.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x40–0x6F or 0x71–0x77).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr }
    }

    /// Release the I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    fn write_reg(&mut self, reg: u8, data: &[u8]) -> Result<(), Error<I2C::Error>> {
        let mut buf = [0u8; 5];
        buf[0] = reg;
        buf[1..=data.len()].copy_from_slice(data);
        self.i2c.write(self.addr, &buf[..=data.len()])?;
        Ok(())
    }

    fn read_reg(&mut self, reg: u8, out: &mut [u8]) -> Result<(), Error<I2C::Error>> {
        self.i2c.write_read(self.addr, &[reg], out)?;
        Ok(())
    }

    /// Put the chip to sleep, set PRE_SCALE for 1 kHz, wake it (auto-increment on, ALLCALL off) and wait 1 ms.
    ///
    /// # Arguments
    /// * `delay` — Delay provider for the oscillator start.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.write_reg(REG_MODE1, &[AI | SLEEP])?;
        self.write_reg(REG_PRE_SCALE, &[5])?;
        self.write_reg(REG_MODE1, &[AI])?;
        delay.delay_ms(1);
        Ok(())
    }

    /// Set the duty cycle of one channel (ON = 0, OFF = round(duty × 4096)).
    ///
    /// 0.0 writes full OFF and 1.0 writes full ON, so no glitch pulses occur.
    ///
    /// # Arguments
    /// * `channel` — 0–15.
    /// * `duty` — 0.0–1.0.
    pub fn set_duty(&mut self, channel: u8, duty: f32) -> Result<(), Error<I2C::Error>> {
        if channel > 15 {
            return Err(Error::InvalidArgument);
        }
        let b = duty_bytes(duty)?;
        self.write_reg(REG_LED0 + 4 * channel, &b)
    }
}

/// PCA9685 full driver — phase-shifted PWM, frequency, servo pulses, modes and addresses.
pub struct PCA9685Full<I2C> {
    base: PCA9685Minimal<I2C>,
}

impl<I2C> Deref for PCA9685Full<I2C> {
    type Target = PCA9685Minimal<I2C>;
    fn deref(&self) -> &Self::Target {
        &self.base
    }
}

impl<I2C> DerefMut for PCA9685Full<I2C> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.base
    }
}

impl<I2C: I2c> PCA9685Full<I2C> {
    /// Create a new `PCA9685Full`; no bus access is made.
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { base: PCA9685Minimal::new(i2c, addr) }
    }

    fn mode1(&mut self) -> Result<u8, Error<I2C::Error>> {
        let mut b = [0u8; 1];
        self.read_reg(REG_MODE1, &mut b)?;
        Ok(b[0])
    }

    /// Write the ON and OFF counts (0–4095, must differ) of one channel in one transaction (phase-shifted PWM).
    pub fn set_pwm(&mut self, channel: u8, on: u16, off: u16) -> Result<(), Error<I2C::Error>> {
        if channel > 15 {
            return Err(Error::InvalidArgument);
        }
        let b = pwm_bytes(on, off)?;
        self.write_reg(REG_LED0 + 4 * channel, &b)
    }

    /// Read the ON and OFF counts of one channel (without the full-ON/OFF flags) as `(on, off)`.
    pub fn get_pwm(&mut self, channel: u8) -> Result<(u16, u16), Error<I2C::Error>> {
        if channel > 15 {
            return Err(Error::InvalidArgument);
        }
        let mut d = [0u8; 4];
        self.read_reg(REG_LED0 + 4 * channel, &mut d)?;
        Ok((((d[1] & 0x0F) as u16) << 8 | d[0] as u16, ((d[3] & 0x0F) as u16) << 8 | d[2] as u16))
    }

    /// Force a channel permanently high (full-ON bit set, full-OFF bit cleared).
    pub fn set_full_on(&mut self, channel: u8) -> Result<(), Error<I2C::Error>> {
        if channel > 15 {
            return Err(Error::InvalidArgument);
        }
        self.write_reg(REG_LED0 + 4 * channel, &[0, 0x10, 0, 0])
    }

    /// Force a channel permanently low (full-OFF bit set; it wins over full ON).
    pub fn set_full_off(&mut self, channel: u8) -> Result<(), Error<I2C::Error>> {
        if channel > 15 {
            return Err(Error::InvalidArgument);
        }
        self.write_reg(REG_LED0 + 4 * channel, &[0, 0, 0, 0x10])
    }

    /// Set the same duty cycle (0.0–1.0) on all 16 channels (ALL_LED registers).
    pub fn set_all_duty(&mut self, duty: f32) -> Result<(), Error<I2C::Error>> {
        let b = duty_bytes(duty)?;
        self.write_reg(REG_ALL_LED, &b)
    }

    /// Set the same ON and OFF counts (0–4095, must differ) on all 16 channels.
    pub fn set_all_pwm(&mut self, on: u16, off: u16) -> Result<(), Error<I2C::Error>> {
        let b = pwm_bytes(on, off)?;
        self.write_reg(REG_ALL_LED, &b)
    }

    /// Set the PWM frequency: PRE_SCALE = round(osc_hz / (4096 × hz)) − 1, clamped 3–255.
    ///
    /// Puts the chip to sleep, writes PRE_SCALE, wakes it, waits 500 µs and restarts running channels.
    ///
    /// # Arguments
    /// * `hz` — Wanted frequency in Hz (24–1526 at 25 MHz).
    /// * `osc_hz` — Oscillator frequency in Hz (25 000 000 internal, or the EXTCLK frequency).
    /// * `delay` — Delay provider for the oscillator start.
    pub fn set_frequency<D: DelayNs>(&mut self, hz: f32, osc_hz: f32, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        if !(hz > 0.0) || !(osc_hz > 0.0) {
            return Err(Error::InvalidArgument);
        }
        let p = (osc_hz / (4096.0 * hz) + 0.5) as i64 - 1;
        let prescale = p.clamp(3, 255) as u8;
        let old = self.mode1()?;
        let awake = old & !(RESTART | SLEEP);
        self.write_reg(REG_MODE1, &[(old & 0x7F) | SLEEP])?;
        self.write_reg(REG_PRE_SCALE, &[prescale])?;
        self.write_reg(REG_MODE1, &[awake])?;
        delay.delay_us(500);
        if old & RESTART != 0 {
            self.write_reg(REG_MODE1, &[awake | RESTART])?;
        }
        Ok(())
    }

    /// Read PRE_SCALE and return the PWM frequency `osc_hz / (4096 × (PRE_SCALE + 1))` in Hz.
    pub fn get_frequency(&mut self, osc_hz: f32) -> Result<f32, Error<I2C::Error>> {
        let mut b = [0u8; 1];
        self.read_reg(REG_PRE_SCALE, &mut b)?;
        Ok(osc_hz / (4096.0 * (b[0] as f32 + 1.0)))
    }

    /// Set a pulse width in seconds (servo/ESC mode): OFF = round(seconds × frequency × 4096).
    ///
    /// Uses the frequency programmed in PRE_SCALE (25 MHz oscillator).
    pub fn set_pulse_width(&mut self, channel: u8, seconds: f32) -> Result<(), Error<I2C::Error>> {
        if channel > 15 || !(seconds >= 0.0) {
            return Err(Error::InvalidArgument);
        }
        let hz = self.get_frequency(25_000_000.0)?;
        let duty = seconds * hz;
        self.set_duty(channel, if duty > 1.0 { 1.0 } else { duty })
    }

    /// Enter low-power mode (MODE1.SLEEP = 1, oscillator off; outputs stop).
    pub fn sleep(&mut self) -> Result<(), Error<I2C::Error>> {
        let old = self.mode1()?;
        self.write_reg(REG_MODE1, &[(old & 0x7F) | SLEEP])
    }

    /// Leave low-power mode, wait 500 µs and restart channels that were running.
    pub fn wake<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let old = self.mode1()?;
        self.write_reg(REG_MODE1, &[old & !(RESTART | SLEEP)])?;
        delay.delay_us(500);
        if old & RESTART != 0 {
            self.write_reg(REG_MODE1, &[(old & !SLEEP) | RESTART])?;
        }
        Ok(())
    }

    /// Run the datasheet restart sequence (read MODE1, clear SLEEP, wait 500 µs, write RESTART).
    pub fn restart<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let old = self.mode1()?;
        self.write_reg(REG_MODE1, &[old & !(RESTART | SLEEP)])?;
        delay.delay_us(500);
        self.write_reg(REG_MODE1, &[(old & !SLEEP) | RESTART])
    }

    /// Configure MODE2: output driver, inversion and the output state while OE = 1
    /// ([`OFF_LOW`], [`OFF_HIGH_OR_HIGHZ`], [`OFF_HIGHZ`]). The update-on-ACK bit is preserved.
    pub fn set_output_mode(&mut self, open_drain: bool, invert: bool, off_state: u8) -> Result<(), Error<I2C::Error>> {
        if off_state > 2 {
            return Err(Error::InvalidArgument);
        }
        let mode2 = off_state | if invert { 0x10 } else { 0 } | if open_drain { 0 } else { 0x04 };
        let mut b = [0u8; 1];
        self.read_reg(REG_MODE2, &mut b)?;
        self.write_reg(REG_MODE2, &[(b[0] & 0x08) | mode2])
    }

    /// Select when outputs change: on ACK (`true`, MODE2.OCH = 1) or on STOP (`false`, default).
    pub fn set_update_on_ack(&mut self, enabled: bool) -> Result<(), Error<I2C::Error>> {
        let mut b = [0u8; 1];
        self.read_reg(REG_MODE2, &mut b)?;
        self.write_reg(REG_MODE2, &[if enabled { b[0] | 0x08 } else { b[0] & !0x08 }])
    }

    /// Switch to the EXTCLK pin as clock. Sticky until power cycle / software reset; afterwards pass
    /// the external clock frequency as `osc_hz` to `set_frequency` / `get_frequency`.
    pub fn use_external_clock<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        let sleeping = (self.mode1()? & 0x7F) | SLEEP;
        self.write_reg(REG_MODE1, &[sleeping])?;
        self.write_reg(REG_MODE1, &[sleeping | EXTCLK])?;
        self.write_reg(REG_MODE1, &[(sleeping | EXTCLK) & !SLEEP])?;
        delay.delay_us(500);
        Ok(())
    }

    /// Enable or disable the LED All Call address and set its 7-bit value (default 0x70).
    pub fn set_allcall(&mut self, enabled: bool, address: u8) -> Result<(), Error<I2C::Error>> {
        if address > 0x7F {
            return Err(Error::InvalidArgument);
        }
        self.write_reg(REG_ALLCALLADR, &[address << 1])?;
        let old = self.mode1()?;
        self.write_reg(REG_MODE1, &[(old & 0x7E) | enabled as u8])
    }

    /// Program and enable/disable one of the three LED Sub Call addresses (`index` 1–3, 7-bit `address`).
    pub fn set_subaddress(&mut self, index: u8, address: u8, enabled: bool) -> Result<(), Error<I2C::Error>> {
        if !(1..=3).contains(&index) || address > 0x7F {
            return Err(Error::InvalidArgument);
        }
        self.write_reg(REG_SUBADR1 + index - 1, &[address << 1])?;
        let mask = 1u8 << (4 - index);
        let old = self.mode1()? & 0x7F;
        self.write_reg(REG_MODE1, &[if enabled { old | mask } else { old & !mask }])
    }

    /// Return the chip registers to their power-on values by writing the documented defaults
    /// (MODE1 = 0x11, MODE2 = 0x04, PRE_SCALE = 0x1E, outputs off). Does not send the general-call software reset.
    pub fn reset(&mut self) -> Result<(), Error<I2C::Error>> {
        self.write_reg(REG_MODE1, &[0x10])?;
        self.write_reg(REG_PRE_SCALE, &[0x1E])?;
        self.write_reg(REG_MODE2, &[0x04])?;
        self.write_reg(REG_ALL_LED, &[0, 0, 0, 0x10])?;
        self.write_reg(REG_SUBADR1, &[0xE2, 0xE4, 0xE8, 0xE0])?;
        self.write_reg(REG_MODE1, &[0x11])
    }

    /// Read the raw mode registers as `(mode1, mode2)`.
    pub fn read_mode(&mut self) -> Result<(u8, u8), Error<I2C::Error>> {
        let mut d = [0u8; 2];
        self.read_reg(REG_MODE1, &mut d)?;
        Ok((d[0], d[1]))
    }
}

/// Send the Software Reset call (data byte `0x06`) to the general call address `0x00`.
///
/// Resets every PCA9685 on the bus; wait at least 500 µs before using the chips again.
pub fn software_reset<I2C: I2c>(i2c: &mut I2C) -> Result<(), I2C::Error> {
    i2c.write(0x00, &[0x06])
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = ADDR_DEFAULT;

    fn w(reg: u8, data: &[u8]) -> T {
        let mut v = vec![reg];
        v.extend_from_slice(data);
        T::write(ADDR, v)
    }

    fn r(reg: u8, data: &[u8]) -> T {
        T::write_read(ADDR, vec![reg], data.to_vec())
    }

    #[test]
    fn minimal_init_and_duty() {
        let t = [
            w(0x00, &[0x30]), w(0xFE, &[5]), w(0x00, &[0x20]),
            w(0x06, &[0, 0, 0x00, 0x08]),
            w(0x42, &[0, 0, 0, 0x10]),
            w(0x0A, &[0, 0x10, 0, 0]),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut d = PCA9685Minimal::new(i2c.clone(), ADDR);
        d.init(&mut NoopDelay::new()).unwrap();
        d.set_duty(0, 0.5).unwrap();
        d.set_duty(15, 0.0).unwrap();
        d.set_duty(1, 1.0).unwrap();
        assert_eq!(d.set_duty(16, 0.5), Err(Error::InvalidArgument));
        assert_eq!(d.set_duty(0, 1.1), Err(Error::InvalidArgument));
        assert_eq!(d.set_duty(0, -0.1), Err(Error::InvalidArgument));
        i2c.done();
    }

    #[test]
    fn full_api() {
        let mut delay = NoopDelay::new();
        let t = [
            // set_pwm / get_pwm
            w(0x0E, &[0x99, 0x01, 0xCC, 0x04]),
            r(0x0E, &[0x99, 0x01, 0xCC, 0x04]),
            // full on/off, all duty/pwm
            w(0x12, &[0, 0x10, 0, 0]), w(0x12, &[0, 0, 0, 0x10]),
            w(0xFA, &[0, 0, 0x00, 0x04]), w(0xFA, &[0, 0, 100, 0]),
            // set_frequency(50): mode1 = 0x20
            r(0x00, &[0x20]), w(0x00, &[0x30]), w(0xFE, &[121]), w(0x00, &[0x20]),
            // set_frequency(200) with RESTART set
            r(0x00, &[0xA0]), w(0x00, &[0x30]), w(0xFE, &[30]), w(0x00, &[0x20]), w(0x00, &[0xA0]),
            // get_frequency, set_pulse_width(15, 1.5 ms) at PRE_SCALE 121
            r(0xFE, &[121]),
            r(0xFE, &[121]), w(0x42, &[0, 0, 0x33, 0x01]),
            // sleep, wake with restart, restart
            r(0x00, &[0x20]), w(0x00, &[0x30]),
            r(0x00, &[0xB0]), w(0x00, &[0x20]), w(0x00, &[0xA0]),
            r(0x00, &[0x30]), w(0x00, &[0x20]), w(0x00, &[0xA0]),
            // output mode, update on ack
            r(0x01, &[0x04]), w(0x01, &[0x12]),
            r(0x01, &[0x12]), w(0x01, &[0x1A]),
            // external clock
            r(0x00, &[0x20]), w(0x00, &[0x30]), w(0x00, &[0x70]), w(0x00, &[0x60]),
            // allcall, subaddress
            w(0x05, &[0xE0]), r(0x00, &[0x20]), w(0x00, &[0x21]),
            w(0x03, &[0xE2]), r(0x00, &[0x21]), w(0x00, &[0x25]),
            // reset, read_mode
            w(0x00, &[0x10]), w(0xFE, &[0x1E]), w(0x01, &[0x04]), w(0xFA, &[0, 0, 0, 0x10]),
            w(0x02, &[0xE2, 0xE4, 0xE8, 0xE0]), w(0x00, &[0x11]),
            r(0x00, &[0x11, 0x04]),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut d = PCA9685Full::new(i2c.clone(), ADDR);
        d.set_pwm(2, 409, 1228).unwrap();
        assert_eq!(d.get_pwm(2).unwrap(), (409, 1228));
        assert_eq!(d.set_pwm(0, 4096, 1), Err(Error::InvalidArgument));
        assert_eq!(d.set_pwm(0, 5, 5), Err(Error::InvalidArgument));
        d.set_full_on(3).unwrap();
        d.set_full_off(3).unwrap();
        d.set_all_duty(0.25).unwrap();
        d.set_all_pwm(0, 100).unwrap();
        d.set_frequency(50.0, 25_000_000.0, &mut delay).unwrap();
        d.set_frequency(200.0, 25_000_000.0, &mut delay).unwrap();
        assert_eq!(d.set_frequency(0.0, 25_000_000.0, &mut delay), Err(Error::InvalidArgument));
        let hz = d.get_frequency(25_000_000.0).unwrap();
        assert!((hz - 25e6 / (4096.0 * 122.0)).abs() < 0.01);
        d.set_pulse_width(15, 0.0015).unwrap();
        d.sleep().unwrap();
        d.wake(&mut delay).unwrap();
        d.restart(&mut delay).unwrap();
        d.set_output_mode(true, true, OFF_HIGHZ).unwrap();
        assert_eq!(d.set_output_mode(false, false, 3), Err(Error::InvalidArgument));
        d.set_update_on_ack(true).unwrap();
        d.use_external_clock(&mut delay).unwrap();
        d.set_allcall(true, 0x70).unwrap();
        d.set_subaddress(2, 0x71, true).unwrap();
        assert_eq!(d.set_subaddress(4, 0x71, true), Err(Error::InvalidArgument));
        assert_eq!(d.set_allcall(true, 0x80), Err(Error::InvalidArgument));
        d.reset().unwrap();
        assert_eq!(d.read_mode().unwrap(), (0x11, 0x04));
        i2c.done();
    }

    #[test]
    fn software_reset_writes_general_call() {
        let mut i2c = I2cMock::new(&[T::write(0x00, vec![0x06])]);
        software_reset(&mut i2c.clone()).unwrap();
        i2c.done();
    }
}
