//! PCF8591 8-bit quad ADC + DAC (NXP).
//!
//! Communicates over I²C (standard mode, up to 100 kHz). Provides single-ended
//! reads of the four analog inputs, plus differential modes and a single
//! 8-bit DAC output.

use embedded_hal::i2c::I2c;

const NUM_CHANNELS: usize = 4;
const CONTROL_DEFAULT: u8 = 0x00;

pub const MODE_4_SINGLE_ENDED: u8  = 0;
pub const MODE_3_DIFFERENTIAL: u8 = 1;
pub const MODE_MIXED: u8          = 2;
pub const MODE_2_DIFFERENTIAL: u8 = 3;

/// PCF8591 minimal driver — read raw 8-bit values from the four analog inputs.
///
/// Operates in 4 single-ended mode (AIP=00). Each read transaction returns
/// 5 bytes: the first is the previous conversion result and must be discarded;
/// the next four are fresh channel samples.
pub struct Pcf8591Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Pcf8591Minimal<I2C> {
    /// Create a new `Pcf8591Minimal`.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (typically `0x48`–`0x4F`).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Ok(Self { i2c, addr })
    }

    /// Read a single channel as an unsigned 8-bit value.
    ///
    /// Uses single-shot conversion: writes the control byte selecting the
    /// channel, then reads 2 bytes (discarding the stale first byte).
    ///
    /// # Arguments
    /// * `channel` — Channel number 0–3. Clamped to the valid range.
    pub fn read_channel(&mut self, channel: u8) -> Result<u8, I2C::Error> {
        let ch = if (channel as usize) < NUM_CHANNELS { channel & 0x03 } else { 0 };
        let ctrl = CONTROL_DEFAULT | (ch & 0x03);
        self.i2c.write(self.addr, &[ctrl])?;
        let mut buf = [0u8; 2];
        self.i2c.read(self.addr, &mut buf)?;
        Ok(buf[1])
    }

    /// Read all four channels as unsigned 8-bit values.
    ///
    /// Uses auto-increment (AI=1) to read all four channels in one
    /// transaction. Returns `[ch0, ch1, ch2, ch3]`.
    pub fn read_all(&mut self) -> Result<[u8; 4], I2C::Error> {
        let ctrl = CONTROL_DEFAULT | 0x04;  // AI=1
        self.i2c.write(self.addr, &[ctrl])?;
        let mut buf = [0u8; NUM_CHANNELS + 1];
        self.i2c.read(self.addr, &mut buf)?;
        Ok([buf[1], buf[2], buf[3], buf[4]])
    }
}

/// PCF8591 full driver — extends [`Pcf8591Minimal`] with differential, voltage, and DAC output.
pub struct Pcf8591Full<I2C> {
    inner: Pcf8591Minimal<I2C>,
    control: u8,
    input_mode: u8,
    dac_enabled: bool,
    auto_increment: bool,
    last_channel: u8,
}

impl<I2C: I2c> Pcf8591Full<I2C> {
    /// Create a new `Pcf8591Full`.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (typically `0x48`–`0x4F`).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Ok(Self {
            inner: Pcf8591Minimal::new(i2c, addr)?,
            control: CONTROL_DEFAULT,
            input_mode: MODE_4_SINGLE_ENDED,
            dac_enabled: false,
            auto_increment: false,
            last_channel: 0,
        })
    }

    /// Re-export of [`Pcf8591Minimal::read_channel`].
    pub fn read_channel(&mut self, channel: u8) -> Result<u8, I2C::Error> {
        self.inner.read_channel(channel)
    }

    /// Re-export of [`Pcf8591Minimal::read_all`].
    pub fn read_all(&mut self) -> Result<[u8; 4], I2C::Error> {
        self.inner.read_all()
    }

    /// Set the analog input mode, auto-increment, and DAC enable.
    ///
    /// # Arguments
    /// * `input_mode`     — Analog input programming 0–3 (see `MODE_*` constants).
    /// * `auto_increment` — If true, AI=1 — channel increments after each conversion.
    /// * `dac_enabled`    — If true, AOE=1 — AOUT is active; AOUT returns to
    ///                      high-impedance when false.
    pub fn configure(&mut self, input_mode: u8, auto_increment: bool, dac_enabled: bool) -> Result<(), I2C::Error> {
        let aip = input_mode & 0x03;
        let ai  = if auto_increment { 0x04 } else { 0x00 };
        let aoe = if dac_enabled     { 0x40 } else { 0x00 };
        self.control = (aip << 4) | aoe | ai | (self.last_channel & 0x03);
        self.input_mode     = aip;
        self.auto_increment = auto_increment;
        self.dac_enabled    = dac_enabled;
        self.inner.i2c.write(self.inner.addr, &[self.control])
    }

    /// Read a single channel and convert to voltage.
    ///
    /// # Arguments
    /// * `channel` — Channel number 0–3.
    /// * `vref`    — Reference voltage in volts.
    /// * `vagnd`   — Analog ground voltage in volts.
    pub fn read_channel_voltage(&mut self, channel: u8, vref: f32, vagnd: f32) -> Result<f32, I2C::Error> {
        let raw = self.read_channel(channel)?;
        Ok(vagnd + (raw as f32) * (vref - vagnd) / 256.0)
    }

    /// Read all four channels and convert each to voltage.
    ///
    /// Returns four voltages `[ch0, ch1, ch2, ch3]`.
    pub fn read_all_voltage(&mut self, vref: f32, vagnd: f32) -> Result<[f32; 4], I2C::Error> {
        let raws = self.read_all()?;
        let vfs = vref - vagnd;
        let mut out = [0.0f32; 4];
        for i in 0..4 {
            out[i] = vagnd + (raws[i] as f32) * vfs / 256.0;
        }
        Ok(out)
    }

    /// Read a differential channel as a signed value.
    ///
    /// The chip must be configured in a differential mode (`input_mode` 1, 2,
    /// or 3). The result is interpreted as a signed 8-bit two's complement
    /// number.
    pub fn read_differential(&mut self, channel: u8) -> Result<i8, I2C::Error> {
        let ch = channel & 0x03;
        self.last_channel = ch;
        let ctrl = self.control | (ch & 0x03);
        self.inner.i2c.write(self.inner.addr, &[ctrl])?;
        let mut buf = [0u8; 2];
        self.inner.i2c.read(self.inner.addr, &mut buf)?;
        Ok(buf[1] as i8)
    }

    /// Enable the DAC and write a raw 8-bit value.
    ///
    /// Sets the AOE bit so AOUT becomes active, then writes the DAC value
    /// in the byte following the control byte.
    pub fn set_dac(&mut self, value: u8) -> Result<(), I2C::Error> {
        let ctrl = (self.control | 0x40) & !0x04;  // AOE=1, AI=0
        self.control = ctrl;
        self.dac_enabled = true;
        self.inner.i2c.write(self.inner.addr, &[ctrl, value])
    }

    /// Enable the DAC and set the output as a fraction of (VREF−VAGND).
    pub fn set_dac_voltage(&mut self, voltage_fraction: f32) -> Result<(), I2C::Error> {
        let f = voltage_fraction.max(0.0).min(1.0);
        let value = (f * 255.0) as u8;
        self.set_dac(value)
    }

    /// Disable the DAC output; AOUT returns to high-impedance.
    pub fn disable_dac(&mut self) -> Result<(), I2C::Error> {
        let ctrl = self.control & !0x40;  // AOE=0
        self.control = ctrl;
        self.dac_enabled = false;
        self.inner.i2c.write(self.inner.addr, &[ctrl])
    }
}
