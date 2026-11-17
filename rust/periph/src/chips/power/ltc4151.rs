//! LTC4151 — 7–80 V high-side current and voltage monitor (Linear Technology / Analog Devices).
//!
//! Communicates over I²C (up to 400 kHz). A 12-bit ΔΣ ADC measures the sense voltage
//! (SENSE+ − SENSE−, 20 µV/LSB), the supply voltage at VIN (25 mV/LSB) and the ADIN pin
//! (0.5 mV/LSB) in a continuous scan that starts at power-up, so the Minimal driver needs no
//! configuration beyond the sense resistor and makes no bus access when constructed.
//!
//! Constants: the channel selectors [`CH_SENSE`], [`CH_VIN`] and [`CH_ADIN`] are passed to
//! [`Ltc4151Full::read_raw`], [`Ltc4151Full::snapshot`] and [`Ltc4151Full::is_busy`].

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register;

const REG_SENSE: u8 = 0x00;
const REG_VIN: u8 = 0x02;
const REG_ADIN: u8 = 0x04;
const REG_CONTROL: u8 = 0x06;

const LSB_SENSE: f32 = 20e-6;
const LSB_VIN: f32 = 0.025;
const LSB_ADIN: f32 = 0.0005;

const CTRL_SNAPSHOT: u8 = 0x80;
const CTRL_TEST: u8 = 0x10;
const CTRL_PAGE_RW: u8 = 0x08;
const CTRL_STUCK_BUS: u8 = 0x04;

/// Snapshot poll interval in milliseconds.
const SNAPSHOT_POLL_MS: u32 = 2;
/// Maximum number of polls (50 × 2 ms = 100 ms timeout).
const SNAPSHOT_POLLS: u32 = 50;

/// Channel selector: sense voltage (SENSE+ − SENSE−).
pub const CH_SENSE: u8 = 0;
/// Channel selector: supply voltage at VIN.
pub const CH_VIN: u8 = 1;
/// Channel selector: ADIN pin.
pub const CH_ADIN: u8 = 2;

/// Errors returned by the LTC4151 Full driver.
#[derive(Debug, PartialEq)]
pub enum Ltc4151Error<E> {
    /// I²C bus error.
    I2c(E),
    /// A channel selector other than 0, 1 or 2 was passed.
    InvalidChannel,
    /// The sense resistor value was not greater than zero.
    InvalidResistor,
    /// A snapshot conversion did not finish within 100 ms.
    Timeout,
}

impl<E> From<E> for Ltc4151Error<E> {
    fn from(e: E) -> Self {
        Ltc4151Error::I2c(e)
    }
}

/// Readings returned by [`Ltc4151Full::read_all`].
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Ltc4151Readings {
    /// Supply voltage at VIN in volts.
    pub vin: f32,
    /// Voltage across the sense resistor in volts.
    pub sense_v: f32,
    /// Load current in amperes.
    pub current: f32,
    /// Load power in watts.
    pub power: f32,
    /// ADIN pin voltage in volts.
    pub adin: f32,
}

fn code(high: u8, low: u8) -> u16 {
    ((high as u16) << 4) | ((low >> 4) as u16)
}

/// LTC4151 minimal driver — supply voltage, load current and power.
///
/// Relies on the chip's power-on state: continuous scan, `CONTROL = 0x0C`. Nothing is written
/// at construction.
pub struct Ltc4151Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    r_sense: f32,
}

impl<I2C: I2c> Ltc4151Minimal<I2C> {
    /// Create a new `Ltc4151Minimal`. Makes no bus access.
    ///
    /// # Arguments
    /// * `i2c`     — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr`    — 7-bit device address (`0x67`–`0x6F`).
    /// * `r_sense` — Sense resistor in ohms (typically `0.02`); a value ≤ 0 falls back to `0.02`.
    pub fn new(i2c: I2C, addr: u8, r_sense: f32) -> Result<Self, I2C::Error> {
        let r_sense = if r_sense > 0.0 { r_sense } else { 0.02 };
        Ok(Self { i2c, addr, r_sense })
    }

    /// Read the supply voltage at VIN in volts (code × 25 mV).
    pub fn voltage(&mut self) -> Result<f32, I2C::Error> {
        let mut d = [0u8; 2];
        register::read_register(&mut self.i2c, self.addr, REG_VIN.into(), 1, &mut d)?;
        Ok(code(d[0], d[1]) as f32 * LSB_VIN)
    }

    /// Read the load current in amperes (sense voltage / `r_sense`).
    pub fn current(&mut self) -> Result<f32, I2C::Error> {
        let mut d = [0u8; 2];
        register::read_register(&mut self.i2c, self.addr, REG_SENSE.into(), 1, &mut d)?;
        Ok(code(d[0], d[1]) as f32 * LSB_SENSE / self.r_sense)
    }

    /// Read the load power in watts (VIN × current) from one consistent block read.
    pub fn power(&mut self) -> Result<f32, I2C::Error> {
        let mut d = [0u8; 4];
        register::read_register(&mut self.i2c, self.addr, REG_SENSE.into(), 1, &mut d)?;
        Ok(code(d[2], d[3]) as f32 * LSB_VIN * (code(d[0], d[1]) as f32 * LSB_SENSE / self.r_sense))
    }
}

/// LTC4151 full driver — extends [`Ltc4151Minimal`] with all channels, snapshot mode and
/// `CONTROL` access.
pub struct Ltc4151Full<I2C> {
    inner: Ltc4151Minimal<I2C>,
    page_rw: bool,
}

impl<I2C: I2c> Ltc4151Full<I2C> {
    /// Create a new `Ltc4151Full`. Makes no bus access.
    ///
    /// Same arguments as [`Ltc4151Minimal::new`].
    pub fn new(i2c: I2C, addr: u8, r_sense: f32) -> Result<Self, I2C::Error> {
        Ok(Self { inner: Ltc4151Minimal::new(i2c, addr, r_sense)?, page_rw: true })
    }

    fn read_block(&mut self, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
        if self.page_rw || buf.len() == 1 {
            return register::read_register(&mut self.inner.i2c, self.inner.addr, reg.into(), 1, buf);
        }
        for (i, b) in buf.iter_mut().enumerate() {
            register::read_register(
                &mut self.inner.i2c, self.inner.addr, (reg + i as u8).into(), 1, core::slice::from_mut(b),
            )?;
        }
        Ok(())
    }

    fn read_control_raw(&mut self) -> Result<u8, I2C::Error> {
        let mut b = [0u8; 1];
        register::read_register(&mut self.inner.i2c, self.inner.addr, REG_CONTROL.into(), 1, &mut b)?;
        Ok(b[0])
    }

    fn write_control_raw(&mut self, value: u8) -> Result<(), I2C::Error> {
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_CONTROL.into(), 1, &[value])
    }

    /// Read the voltage across the sense resistor in volts (code × 20 µV).
    pub fn sense_voltage(&mut self) -> Result<f32, I2C::Error> {
        let mut d = [0u8; 2];
        self.read_block(REG_SENSE, &mut d)?;
        Ok(code(d[0], d[1]) as f32 * LSB_SENSE)
    }

    /// Read the ADIN pin voltage in volts (code × 0.5 mV).
    pub fn adin_voltage(&mut self) -> Result<f32, I2C::Error> {
        let mut d = [0u8; 2];
        self.read_block(REG_ADIN, &mut d)?;
        Ok(code(d[0], d[1]) as f32 * LSB_ADIN)
    }

    /// Read the supply voltage at VIN in volts (code × 25 mV).
    pub fn voltage(&mut self) -> Result<f32, I2C::Error> {
        let mut d = [0u8; 2];
        self.read_block(REG_VIN, &mut d)?;
        Ok(code(d[0], d[1]) as f32 * LSB_VIN)
    }

    /// Read the load current in amperes (sense voltage / `r_sense`).
    pub fn current(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.sense_voltage()? / self.inner.r_sense)
    }

    /// Read the load power in watts (VIN × current) from one consistent block read.
    pub fn power(&mut self) -> Result<f32, I2C::Error> {
        let mut d = [0u8; 4];
        self.read_block(REG_SENSE, &mut d)?;
        Ok(code(d[2], d[3]) as f32 * LSB_VIN * (code(d[0], d[1]) as f32 * LSB_SENSE / self.inner.r_sense))
    }

    /// Read all three channels from one block read of registers `0x00`–`0x05`.
    pub fn read_all(&mut self) -> Result<Ltc4151Readings, I2C::Error> {
        let mut d = [0u8; 6];
        self.read_block(REG_SENSE, &mut d)?;
        let sense_v = code(d[0], d[1]) as f32 * LSB_SENSE;
        let vin = code(d[2], d[3]) as f32 * LSB_VIN;
        let adin = code(d[4], d[5]) as f32 * LSB_ADIN;
        let current = sense_v / self.inner.r_sense;
        Ok(Ltc4151Readings { vin, sense_v, current, power: vin * current, adin })
    }

    /// Read the 12-bit code of a channel ([`CH_SENSE`], [`CH_VIN`] or [`CH_ADIN`]), 0–4095.
    pub fn read_raw(&mut self, channel: u8) -> Result<u16, Ltc4151Error<I2C::Error>> {
        if channel > CH_ADIN {
            return Err(Ltc4151Error::InvalidChannel);
        }
        let mut d = [0u8; 2];
        self.read_block(channel * 2, &mut d)?;
        Ok(code(d[0], d[1]))
    }

    /// Set the sense resistor in ohms; must be greater than 0.
    pub fn set_sense_resistor(&mut self, ohms: f32) -> Result<(), Ltc4151Error<I2C::Error>> {
        if !(ohms > 0.0) {
            return Err(Ltc4151Error::InvalidResistor);
        }
        self.inner.r_sense = ohms;
        Ok(())
    }

    /// Get the sense resistor in ohms.
    pub fn get_sense_resistor(&self) -> f32 {
        self.inner.r_sense
    }

    /// Run one snapshot conversion of a channel, then return to continuous scan.
    ///
    /// Clears the snapshot bit first, then writes the channel with the snapshot bit set, polls
    /// the channel's `BUSY` bit (timeout 100 ms), reads the result and restores continuous scan.
    /// Returns the value in volts (sense voltage, VIN or ADIN).
    ///
    /// # Arguments
    /// * `channel` — [`CH_SENSE`], [`CH_VIN`] or [`CH_ADIN`].
    /// * `delay`   — Delay provider used between `BUSY` polls.
    pub fn snapshot<D: DelayNs>(&mut self, channel: u8, delay: &mut D) -> Result<f32, Ltc4151Error<I2C::Error>> {
        if channel > CH_ADIN {
            return Err(Ltc4151Error::InvalidChannel);
        }
        let base = self.read_control_raw()? & (CTRL_PAGE_RW | CTRL_STUCK_BUS);
        self.write_control_raw(base)?;
        self.write_control_raw(base | CTRL_SNAPSHOT | (channel << 5))?;
        let mut polls = 0;
        while self.is_busy(channel)? {
            polls += 1;
            if polls > SNAPSHOT_POLLS {
                self.write_control_raw(base)?;
                return Err(Ltc4151Error::Timeout);
            }
            delay.delay_ms(SNAPSHOT_POLL_MS);
        }
        let raw = self.read_raw(channel)?;
        self.write_control_raw(base)?;
        let lsb = [LSB_SENSE, LSB_VIN, LSB_ADIN][channel as usize];
        Ok(raw as f32 * lsb)
    }

    /// Switch to continuous scan mode (snapshot bit cleared).
    pub fn set_continuous(&mut self) -> Result<(), I2C::Error> {
        let v = self.read_control_raw()? & !(CTRL_SNAPSHOT | CTRL_TEST);
        self.write_control_raw(v)
    }

    /// Read the snapshot bit of `CONTROL`.
    pub fn is_snapshot_mode(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.read_control_raw()? & CTRL_SNAPSHOT != 0)
    }

    /// Read the `BUSY` bit of a channel (snapshot mode).
    pub fn is_busy(&mut self, channel: u8) -> Result<bool, Ltc4151Error<I2C::Error>> {
        if channel > CH_ADIN {
            return Err(Ltc4151Error::InvalidChannel);
        }
        let mut b = [0u8; 1];
        register::read_register(&mut self.inner.i2c, self.inner.addr, (channel * 2 + 1).into(), 1, &mut b)?;
        Ok(b[0] & 0x08 != 0)
    }

    /// Enable or disable the stuck-bus reset timer (`CONTROL.G2`, read-modify-write).
    pub fn set_stuck_bus_timer(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let mut v = self.read_control_raw()? & !CTRL_TEST;
        if enabled { v |= CTRL_STUCK_BUS } else { v &= !CTRL_STUCK_BUS }
        self.write_control_raw(v)
    }

    /// Enable or disable I²C page read/write (`CONTROL.G3`, read-modify-write).
    ///
    /// When disabled the chip only supports byte/word transfers, so the driver then reads each
    /// register separately.
    pub fn set_page_rw(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        let mut v = self.read_control_raw()? & !CTRL_TEST;
        if enabled { v |= CTRL_PAGE_RW } else { v &= !CTRL_PAGE_RW }
        self.write_control_raw(v)?;
        self.page_rw = enabled;
        Ok(())
    }

    /// Read the `CONTROL` register (power-on default `0x0C`).
    pub fn read_control(&mut self) -> Result<u8, I2C::Error> {
        self.read_control_raw()
    }

    /// Write the `CONTROL` register. Test mode (bit 4) is always masked to 0.
    pub fn write_control(&mut self, value: u8) -> Result<(), I2C::Error> {
        let v = value & !CTRL_TEST;
        self.write_control_raw(v)?;
        self.page_rw = v & CTRL_PAGE_RW != 0;
        Ok(())
    }

    /// Convert an ADIN/VIN code pair to temperature in °C (datasheet NTC example circuit,
    /// `58.82 × (adin_code / vin_code − 0.1066)`, valid for roughly 20–60 °C only).
    pub fn temperature_from_adin(adin_code: u16, vin_code: u16) -> f32 {
        if vin_code == 0 {
            return 0.0;
        }
        58.82 * (adin_code as f32 / vin_code as f32 - 0.1066)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = 0x6A;

    fn near(a: f32, b: f32, tol: f32) -> bool {
        (a - b).abs() < tol
    }

    #[test]
    fn full_api() {
        let transactions = vec![
            // voltage(): VIN code 0x800 = 2048 -> 51.2 V
            T::write_read(ADDR, vec![REG_VIN], vec![0x80, 0x00]),
            // sense_voltage(): code 0x400 = 1024 -> 20.48 mV; current() reads it again
            T::write_read(ADDR, vec![REG_SENSE], vec![0x40, 0x00]),
            T::write_read(ADDR, vec![REG_SENSE], vec![0x40, 0x00]),
            // power(): one block read of SENSE and VIN
            T::write_read(ADDR, vec![REG_SENSE], vec![0x40, 0x00, 0x80, 0x00]),
            // voltage(): the four LSBs are in bits 7:4; bits 3:0 (BUSY / reserved) are ignored
            T::write_read(ADDR, vec![REG_VIN], vec![0x00, 0xF8]),
            // adin_voltage(): code 0x7D0 = 2000 -> 1.0 V
            T::write_read(ADDR, vec![REG_ADIN], vec![0x7D, 0x00]),
            // read_all(): one block read of six bytes
            T::write_read(ADDR, vec![REG_SENSE], vec![0x40, 0x00, 0x80, 0x00, 0x7D, 0x00]),
            // read_raw(SENSE / VIN / ADIN)
            T::write_read(ADDR, vec![0x00], vec![0x40, 0x00]),
            T::write_read(ADDR, vec![0x02], vec![0x80, 0x00]),
            T::write_read(ADDR, vec![0x04], vec![0x7D, 0x00]),
            // set_sense_resistor(0.01) -> current() = 2.048 A
            T::write_read(ADDR, vec![REG_SENSE], vec![0x40, 0x00]),
            // set_stuck_bus_timer(false): CONTROL 0x0C -> 0x08
            T::write_read(ADDR, vec![REG_CONTROL], vec![0x0C]),
            T::write(ADDR, vec![REG_CONTROL, 0x08]),
            // set_stuck_bus_timer(true): 0x08 -> 0x0C
            T::write_read(ADDR, vec![REG_CONTROL], vec![0x08]),
            T::write(ADDR, vec![REG_CONTROL, 0x0C]),
            // set_page_rw(false): 0x0C -> 0x04
            T::write_read(ADDR, vec![REG_CONTROL], vec![0x0C]),
            T::write(ADDR, vec![REG_CONTROL, 0x04]),
            // page read/write off: read_all() uses six single-register reads
            T::write_read(ADDR, vec![0x00], vec![0x40]),
            T::write_read(ADDR, vec![0x01], vec![0x00]),
            T::write_read(ADDR, vec![0x02], vec![0x80]),
            T::write_read(ADDR, vec![0x03], vec![0x00]),
            T::write_read(ADDR, vec![0x04], vec![0x7D]),
            T::write_read(ADDR, vec![0x05], vec![0x00]),
            // set_page_rw(true): 0x04 -> 0x0C
            T::write_read(ADDR, vec![REG_CONTROL], vec![0x04]),
            T::write(ADDR, vec![REG_CONTROL, 0x0C]),
            // write_control(0xFF): test mode masked -> 0xEF
            T::write(ADDR, vec![REG_CONTROL, 0xEF]),
            // write_control(0x0C)
            T::write(ADDR, vec![REG_CONTROL, 0x0C]),
            // read_control()
            T::write_read(ADDR, vec![REG_CONTROL], vec![0x0C]),
            // is_snapshot_mode(): 0xA8 -> true; set_continuous() clears bit 7 -> 0x28
            T::write_read(ADDR, vec![REG_CONTROL], vec![0xA8]),
            T::write_read(ADDR, vec![REG_CONTROL], vec![0xA8]),
            T::write(ADDR, vec![REG_CONTROL, 0x28]),
            // is_busy(SENSE) false, is_busy(VIN) true
            T::write_read(ADDR, vec![0x01], vec![0x00]),
            T::write_read(ADDR, vec![0x03], vec![0x08]),
            // snapshot(VIN): clear first (0x0C), start 0xAC, BUSY clear, read result, restore
            T::write_read(ADDR, vec![REG_CONTROL], vec![0x0C]),
            T::write(ADDR, vec![REG_CONTROL, 0x0C]),
            T::write(ADDR, vec![REG_CONTROL, 0xAC]),
            T::write_read(ADDR, vec![0x03], vec![0x00]),
            T::write_read(ADDR, vec![0x02], vec![0x80, 0x00]),
            T::write(ADDR, vec![REG_CONTROL, 0x0C]),
        ];
        let i2c = I2cMock::new(&transactions);

        // Construction makes no bus access: the mock has no init transactions.
        let mut sensor = Ltc4151Full::new(i2c, ADDR, 0.02).expect("init");

        assert!(near(sensor.voltage().unwrap(), 51.2, 1e-3));
        assert!(near(sensor.sense_voltage().unwrap(), 0.02048, 1e-7));
        assert!(near(sensor.current().unwrap(), 1.024, 1e-4));
        assert!(near(sensor.power().unwrap(), 51.2 * 1.024, 1e-2));
        assert!(near(sensor.voltage().unwrap(), 15.0 * 0.025, 1e-4));
        assert!(near(sensor.adin_voltage().unwrap(), 1.0, 1e-4));

        let r = sensor.read_all().unwrap();
        assert!(near(r.vin, 51.2, 1e-3) && near(r.sense_v, 0.02048, 1e-7) && near(r.current, 1.024, 1e-4));
        assert!(near(r.power, 51.2 * 1.024, 1e-2) && near(r.adin, 1.0, 1e-4));

        assert_eq!(sensor.read_raw(CH_SENSE).unwrap(), 0x400);
        assert_eq!(sensor.read_raw(CH_VIN).unwrap(), 0x800);
        assert_eq!(sensor.read_raw(CH_ADIN).unwrap(), 0x7D0);
        assert_eq!(sensor.read_raw(3), Err(Ltc4151Error::InvalidChannel));

        sensor.set_sense_resistor(0.01).unwrap();
        assert!(near(sensor.get_sense_resistor(), 0.01, 1e-9));
        assert!(near(sensor.current().unwrap(), 2.048, 1e-3));
        assert_eq!(sensor.set_sense_resistor(0.0), Err(Ltc4151Error::InvalidResistor));
        sensor.set_sense_resistor(0.02).unwrap();

        sensor.set_stuck_bus_timer(false).unwrap();
        sensor.set_stuck_bus_timer(true).unwrap();
        sensor.set_page_rw(false).unwrap();
        let r = sensor.read_all().unwrap();
        assert!(near(r.power, 51.2 * 1.024, 1e-2));
        sensor.set_page_rw(true).unwrap();

        sensor.write_control(0xFF).unwrap();
        sensor.write_control(0x0C).unwrap();
        assert_eq!(sensor.read_control().unwrap(), 0x0C);

        assert!(sensor.is_snapshot_mode().unwrap());
        sensor.set_continuous().unwrap();

        assert!(!sensor.is_busy(CH_SENSE).unwrap());
        assert!(sensor.is_busy(CH_VIN).unwrap());
        assert_eq!(sensor.is_busy(5), Err(Ltc4151Error::InvalidChannel));

        let v = sensor.snapshot(CH_VIN, &mut NoopDelay::new()).unwrap();
        assert!(near(v, 51.2, 1e-3));
        assert_eq!(sensor.snapshot(7, &mut NoopDelay::new()), Err(Ltc4151Error::InvalidChannel));

        assert!(near(Ltc4151Full::<I2cMock>::temperature_from_adin(900, 1200), 58.82 * (0.75 - 0.1066), 1e-3));

        sensor.inner.i2c.done();
    }

    #[test]
    fn minimal_api() {
        // r_sense = 0.005: SENSE code 0x400 -> 20.48 mV / 5 mOhm = 4.096 A
        let transactions = vec![
            T::write_read(ADDR, vec![REG_VIN], vec![0x80, 0x00]),
            T::write_read(ADDR, vec![REG_SENSE], vec![0x40, 0x00]),
            T::write_read(ADDR, vec![REG_SENSE], vec![0x40, 0x00, 0x80, 0x00]),
        ];
        let i2c = I2cMock::new(&transactions);
        let mut sensor = Ltc4151Minimal::new(i2c, ADDR, 0.005).expect("init");
        assert!(near(sensor.voltage().unwrap(), 51.2, 1e-3));
        assert!(near(sensor.current().unwrap(), 4.096, 1e-3));
        assert!(near(sensor.power().unwrap(), 51.2 * 4.096, 5e-2));
        sensor.i2c.done();
    }

    #[test]
    fn snapshot_timeout() {
        // BUSY never clears: 51 polls, then CONTROL is restored and Timeout returned.
        let mut transactions = vec![
            T::write_read(ADDR, vec![REG_CONTROL], vec![0x0C]),
            T::write(ADDR, vec![REG_CONTROL, 0x0C]),
            T::write(ADDR, vec![REG_CONTROL, 0x8C]),
        ];
        for _ in 0..51 {
            transactions.push(T::write_read(ADDR, vec![0x01], vec![0x08]));
        }
        transactions.push(T::write(ADDR, vec![REG_CONTROL, 0x0C]));
        let i2c = I2cMock::new(&transactions);
        let mut sensor = Ltc4151Full::new(i2c, ADDR, 0.02).expect("init");
        assert_eq!(sensor.snapshot(CH_SENSE, &mut NoopDelay::new()), Err(Ltc4151Error::Timeout));
        sensor.inner.i2c.done();
    }
}
