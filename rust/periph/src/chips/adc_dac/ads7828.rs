//! ADS7828 — 12-bit, 8-channel sampling ADC with internal 2.5 V reference
//! (TI / Burr-Brown).
//!
//! The chip has no registers: one command byte selects the input mode
//! (single-ended or differential), the channel and the power-down state and
//! starts a conversion; the 12-bit result is read back as two bytes. The
//! driver sends `[command]` and reads two bytes in one `write_read` (repeated
//! START), exactly the datasheet's conversion sequence. The 7-bit I²C address
//! is `0x48`–`0x4B` (A1/A0 tied to GND or VDD).
//!
//! Generic over [`I2c`] and [`DelayNs`] from `embedded-hal` 1.0. The
//! constructor makes no bus access; [`Ads7828Minimal::init`] (or the first
//! conversion) enables the internal reference and waits 1 ms for it to
//! settle — see `specs/adc_dac/ads7828.md`.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

/// 7-bit I²C address with A1 = A0 = GND.
pub const ADS7828_I2C_ADDRESS: u8 = 0x48;

const INTERNAL_VREF: f32 = 2.5;
const PD_ALL_DOWN: u8 = 0;
const PD_REF_ON_ADC_ON: u8 = 3;
/// Lone command: SD = 0, C = 0, PD = 10 (reference on, ADC off).
const CMD_REF_ON: u8 = 0x08;
/// Lone command: SD = 0, C = 0, PD = 00 (full power-down).
const CMD_POWER_DOWN: u8 = 0x00;
const REF_SETTLE_MS: u32 = 1;

/// PD = 00: power down between conversions (internal reference off).
pub const ADS7828_POWER_DOWN_BETWEEN: u8 = 0;
/// PD = 01: internal reference off, ADC on.
pub const ADS7828_REF_OFF_ADC_ON: u8 = 1;
/// PD = 10: internal reference on, ADC off.
pub const ADS7828_REF_ON_ADC_OFF: u8 = 2;
/// PD = 11: internal reference on, ADC on.
pub const ADS7828_REF_ON_ADC_ON: u8 = 3;

/// Error returned by [`Ads7828Full::set_power_mode`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Ads7828Error {
    /// `pd` is outside 0–3, or the internal reference is selected and `pd`
    /// has PD1 = 0 (that would turn the reference off).
    InvalidPowerMode,
}

/// C2:C0 for single-ended channel `n` — the encoding is interleaved.
fn single_ended_code(channel: u8) -> u8 {
    ((channel & 1) << 2) | (channel >> 1)
}

/// ADS7828 — minimal interface: the eight single-ended inputs (CHn against
/// COM) as raw codes or voltages. Defaults to the internal 2.5 V reference
/// (LSB 610 µV), which is kept on between conversions.
pub struct Ads7828Minimal<I2C, D> {
    i2c: I2C,
    delay: D,
    addr: u8,
    internal: bool,
    ref_on: bool,
    vref: f32,
    pd: u8,
}

impl<I2C: I2c, D: DelayNs> Ads7828Minimal<I2C, D> {
    /// Construct the driver; performs no I²C traffic.
    ///
    /// `addr` is `0x48`–`0x4B` ([`ADS7828_I2C_ADDRESS`]); `vref` is `None`
    /// for the internal 2.5 V reference (REFIN/REFOUT decoupled with 0.1 µF,
    /// nothing else driving it) or `Some(volts)` for an external reference
    /// (0.05 V … VDD) with the internal reference off.
    pub fn new(i2c: I2C, delay: D, addr: u8, vref: Option<f32>) -> Self {
        let mut s = Self {
            i2c, delay, addr, internal: true, ref_on: false,
            vref: INTERNAL_VREF, pd: PD_REF_ON_ADC_ON,
        };
        s.select_reference(vref);
        s
    }

    fn select_reference(&mut self, vref: Option<f32>) {
        match vref {
            None => {
                self.internal = true;
                self.vref = INTERNAL_VREF;
                self.pd = PD_REF_ON_ADC_ON;
            }
            Some(v) => {
                self.internal = false;
                self.vref = v;
                self.pd = PD_ALL_DOWN;
            }
        }
        self.ref_on = false;
    }

    fn enable_reference(&mut self) -> Result<(), I2C::Error> {
        self.i2c.write(self.addr, &[CMD_REF_ON])?;
        self.delay.delay_ms(REF_SETTLE_MS);
        self.ref_on = true;
        Ok(())
    }

    /// Enable the internal reference now (no-op with an external one): sends
    /// the lone command `0x08` and waits 1 ms. Optional — the first
    /// conversion does this automatically.
    pub fn init(&mut self) -> Result<(), I2C::Error> {
        if self.internal {
            self.enable_reference()?;
        }
        Ok(())
    }

    fn convert(&mut self, single_ended: bool, code: u8) -> Result<u16, I2C::Error> {
        if self.internal && !self.ref_on {
            self.enable_reference()?;
        }
        let command = ((single_ended as u8) << 7) | (code << 4) | (self.pd << 2);
        let mut buf = [0u8; 2];
        self.i2c.write_read(self.addr, &[command], &mut buf)?;
        Ok((((buf[0] & 0x0F) as u16) << 8) | buf[1] as u16)
    }

    /// Read a single-ended channel (CHn against COM) as a 12-bit code.
    ///
    /// `channel` is clamped to 0–7. Returns 0–4095.
    pub fn read_raw(&mut self, channel: u8) -> Result<u16, I2C::Error> {
        self.convert(true, single_ended_code(channel.min(7)))
    }

    /// Read a single-ended channel as a voltage in volts (`raw / 4096 × vref`).
    pub fn read_voltage(&mut self, channel: u8) -> Result<f32, I2C::Error> {
        Ok(self.read_raw(channel)? as f32 / 4096.0 * self.vref)
    }

    /// Read all eight single-ended channels, one conversion each; returns
    /// the voltages of CH0…CH7 in volts.
    pub fn read_all(&mut self) -> Result<[f32; 8], I2C::Error> {
        let mut out = [0.0f32; 8];
        for (ch, slot) in out.iter_mut().enumerate() {
            *slot = self.read_voltage(ch as u8)?;
        }
        Ok(out)
    }

    /// Release the I²C bus and the delay provider.
    pub fn release(self) -> (I2C, D) {
        (self.i2c, self.delay)
    }
}

/// ADS7828 — full interface: extends [`Ads7828Minimal`] with differential
/// reads (both polarities), a signed differential read, run-time reference
/// switching, power-down control and multi-channel scans.
pub struct Ads7828Full<I2C, D> {
    inner: Ads7828Minimal<I2C, D>,
}

impl<I2C: I2c, D: DelayNs> Ads7828Full<I2C, D> {
    /// Construct the driver; performs no I²C traffic. See [`Ads7828Minimal::new`].
    pub fn new(i2c: I2C, delay: D, addr: u8, vref: Option<f32>) -> Self {
        Self { inner: Ads7828Minimal::new(i2c, delay, addr, vref) }
    }

    /// Enable the internal reference now. See [`Ads7828Minimal::init`].
    pub fn init(&mut self) -> Result<(), I2C::Error> {
        self.inner.init()
    }

    /// Read a single-ended channel as a 12-bit code (channel clamped to 0–7).
    pub fn read_raw(&mut self, channel: u8) -> Result<u16, I2C::Error> {
        self.inner.read_raw(channel)
    }

    /// Read a single-ended channel as a voltage in volts.
    pub fn read_voltage(&mut self, channel: u8) -> Result<f32, I2C::Error> {
        self.inner.read_voltage(channel)
    }

    /// Read all eight single-ended channels as voltages in volts.
    pub fn read_all(&mut self) -> Result<[f32; 8], I2C::Error> {
        self.inner.read_all()
    }

    /// Read a differential pair as a 12-bit code.
    ///
    /// `pair` is 0–3 (CH0/CH1 … CH6/CH7, clamped); when `inverted` the
    /// positive input is CH(2·pair+1). A negative differential voltage reads 0.
    pub fn read_differential_raw(&mut self, pair: u8, inverted: bool) -> Result<u16, I2C::Error> {
        let p = pair.min(3);
        self.inner.convert(false, if inverted { 4 | p } else { p })
    }

    /// Read a differential pair as a voltage in volts (≥ 0).
    pub fn read_differential(&mut self, pair: u8, inverted: bool) -> Result<f32, I2C::Error> {
        Ok(self.read_differential_raw(pair, inverted)? as f32 / 4096.0 * self.inner.vref)
    }

    /// Read V(CH2p) − V(CH2p+1) with sign, using two conversions (normal and
    /// inverted polarity). Range −vref … +vref.
    pub fn read_signed_differential(&mut self, pair: u8) -> Result<f32, I2C::Error> {
        let normal = self.read_differential_raw(pair, false)? as i32;
        let inverted = self.read_differential_raw(pair, true)? as i32;
        Ok((normal - inverted) as f32 / 4096.0 * self.inner.vref)
    }

    /// Switch between the internal (`None`, re-runs the turn-on and 1 ms
    /// settle wait) and an external (`Some(volts)`, PD1 = 0 from then on) reference.
    pub fn set_reference(&mut self, vref: Option<f32>) -> Result<(), I2C::Error> {
        self.inner.select_reference(vref);
        self.inner.init()
    }

    /// Reference voltage used for scaling (2.5 for the internal reference).
    pub fn get_reference(&self) -> f32 {
        self.inner.vref
    }

    /// `true` while the internal reference is selected.
    pub fn uses_internal_reference(&self) -> bool {
        self.inner.internal
    }

    /// Set the PD bits used in all following conversion commands:
    /// 0 = `POWER_DOWN_BETWEEN`, 1 = `REF_OFF_ADC_ON`, 2 = `REF_ON_ADC_OFF`,
    /// 3 = `REF_ON_ADC_ON`. With the internal reference selected, values with
    /// PD1 = 0 are rejected (they would turn the reference off).
    pub fn set_power_mode(&mut self, pd: u8) -> Result<(), Ads7828Error> {
        if pd > 3 || (self.inner.internal && pd & 2 == 0) {
            return Err(Ads7828Error::InvalidPowerMode);
        }
        self.inner.pd = pd;
        Ok(())
    }

    /// Power the chip down completely (≈ 400 nA) with a lone command (PD = 00).
    /// With the internal reference this turns it off; the next read re-runs
    /// the turn-on and settle wait automatically.
    pub fn power_down(&mut self) -> Result<(), I2C::Error> {
        self.inner.i2c.write(self.inner.addr, &[CMD_POWER_DOWN])?;
        self.inner.ref_on = false;
        Ok(())
    }

    /// Read several single-ended channels in order as raw codes, one
    /// command/read pair each.
    pub fn read_scan(&mut self, channels: &[u8], out: &mut [u16]) -> Result<(), I2C::Error> {
        for (slot, &ch) in out.iter_mut().zip(channels.iter()) {
            *slot = self.inner.read_raw(ch)?;
        }
        Ok(())
    }

    /// Size of one code step: `vref / 4096` in volts (610 µV at 2.5 V).
    pub fn lsb(&self) -> f32 {
        self.inner.vref / 4096.0
    }

    /// Release the I²C bus and the delay provider.
    pub fn release(self) -> (I2C, D) {
        self.inner.release()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = ADS7828_I2C_ADDRESS;

    fn close(a: f32, b: f32) -> bool {
        (a - b).abs() < 1e-5
    }

    #[test]
    fn init_and_single_ended_commands() {
        let t = vec![
            T::write(ADDR, vec![0x08]),
            T::write_read(ADDR, vec![0x8C], vec![0x0A, 0xBC]),
            T::write_read(ADDR, vec![0xCC], vec![0xFF, 0xFF]),
            T::write_read(ADDR, vec![0x9C], vec![0x00, 0x00]),
            T::write_read(ADDR, vec![0xFC], vec![0x00, 0x00]),
            // clamps: 99 -> CH7, so 0xFC again
            T::write_read(ADDR, vec![0xFC], vec![0x08, 0x00]),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut adc = Ads7828Minimal::new(i2c.clone(), NoopDelay::new(), ADDR, None);
        adc.init().expect("init");
        assert_eq!(adc.read_raw(0).unwrap(), 0xABC);
        // upper nibble of byte 0 is masked
        assert_eq!(adc.read_raw(1).unwrap(), 0xFFF);
        adc.read_raw(2).unwrap();
        adc.read_raw(7).unwrap();
        assert!(close(adc.read_voltage(99).unwrap(), 0.5 * 2.5));
        i2c.done();
    }

    #[test]
    fn lazy_reference_enable_and_read_all() {
        let mut t = vec![T::write(ADDR, vec![0x08])];
        for cmd in [0x8C, 0xCC, 0x9C, 0xDC, 0xAC, 0xEC, 0xBC, 0xFC] {
            t.push(T::write_read(ADDR, vec![cmd], vec![0x04, 0x00]));
        }
        let mut i2c = I2cMock::new(&t);
        let mut adc = Ads7828Minimal::new(i2c.clone(), NoopDelay::new(), ADDR, None);
        let all = adc.read_all().unwrap();
        assert!(all.iter().all(|&v| close(v, 0.25 * 2.5)));
        i2c.done();
    }

    #[test]
    fn differential_and_signed() {
        let t = vec![
            T::write(ADDR, vec![0x08]),
            T::write_read(ADDR, vec![0x0C], vec![0x04, 0x00]),
            T::write_read(ADDR, vec![0x7C], vec![0x00, 0x00]),
            T::write_read(ADDR, vec![0x0C], vec![0x04, 0x00]),
            // signed negative: normal 0, inverted 0x200
            T::write_read(ADDR, vec![0x0C], vec![0x00, 0x00]),
            T::write_read(ADDR, vec![0x4C], vec![0x02, 0x00]),
            // signed positive
            T::write_read(ADDR, vec![0x0C], vec![0x02, 0x00]),
            T::write_read(ADDR, vec![0x4C], vec![0x00, 0x00]),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut adc = Ads7828Full::new(i2c.clone(), NoopDelay::new(), ADDR, None);
        adc.init().unwrap();
        assert_eq!(adc.read_differential_raw(0, false).unwrap(), 0x400);
        adc.read_differential_raw(3, true).unwrap();
        assert!(close(adc.read_differential(0, false).unwrap(), 0x400 as f32 / 4096.0 * 2.5));
        assert!(close(adc.read_signed_differential(0).unwrap(), -(0x200 as f32) / 4096.0 * 2.5));
        assert!(close(adc.read_signed_differential(0).unwrap(), 0x200 as f32 / 4096.0 * 2.5));
        i2c.done();
    }

    #[test]
    fn reference_power_mode_and_scan() {
        let t = vec![
            // external reference: no init traffic, PD = 00
            T::write_read(ADDR, vec![0x80], vec![0x08, 0x00]),
            // switch to internal: reference-on command, then PD = 11
            T::write(ADDR, vec![0x08]),
            // REF_ON_ADC_OFF -> PD = 10
            T::write_read(ADDR, vec![0x88], vec![0x00, 0x01]),
            // power down, then the next read re-enables the reference
            T::write(ADDR, vec![0x00]),
            T::write(ADDR, vec![0x08]),
            T::write_read(ADDR, vec![0x8C], vec![0x00, 0x01]),
            T::write_read(ADDR, vec![0xCC], vec![0x00, 0x02]),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut adc = Ads7828Full::new(i2c.clone(), NoopDelay::new(), ADDR, Some(5.0));
        assert!(!adc.uses_internal_reference());
        assert!(close(adc.get_reference(), 5.0));
        adc.init().unwrap();
        assert!(close(adc.read_voltage(0).unwrap(), 2.5));
        adc.set_power_mode(ADS7828_POWER_DOWN_BETWEEN).unwrap();

        adc.set_reference(None).unwrap();
        assert!(adc.uses_internal_reference() && close(adc.get_reference(), 2.5));
        assert_eq!(adc.set_power_mode(1), Err(Ads7828Error::InvalidPowerMode));
        assert_eq!(adc.set_power_mode(7), Err(Ads7828Error::InvalidPowerMode));
        adc.set_power_mode(ADS7828_REF_ON_ADC_OFF).unwrap();
        adc.read_raw(0).unwrap();
        adc.set_power_mode(ADS7828_REF_ON_ADC_ON).unwrap();
        adc.power_down().unwrap();
        let mut out = [0u16; 2];
        adc.read_scan(&[0, 1], &mut out).unwrap();
        assert_eq!(out, [1, 2]);
        assert!(close(adc.lsb(), 2.5 / 4096.0));
        i2c.done();
    }
}
