//! MCP4728 quad-channel 12-bit voltage-output DAC (Microchip).
//!
//! Communicates over I²C (up to 3.4 MHz high-speed, 400 kHz fast mode, 100 kHz standard).
//! Provides voltage output as a fraction of V_DD for any of the four channels
//! (A–D) plus a convenience method to update all four channels simultaneously.
//! V_REF is fixed at external (V_DD), gain is fixed at ×1, and power-down is
//! off in the minimal interface. The chip has on-chip EEPROM that auto-loads
//! into the DAC registers on power-up.

use embedded_hal::i2c::I2c;

const CMD_MULTI_WRITE_BASE: u8 = 0x40; // [0 1 0 0 0 DAC1 DAC0 UDAC]
const CMD_SINGLE_WRITE: u8     = 0x58; // [0 1 0 1 1 DAC1 DAC0 UDAC]
const CMD_SEQUENTIAL_BASE: u8  = 0x50; // [0 1 0 1 0 DAC1 DAC0 UDAC]
const CMD_WRITE_VREF: u8       = 0x80; // [1 0 0 X Vref_A Vref_B Vref_C Vref_D]
const CMD_WRITE_GAIN: u8       = 0xC0; // [1 1 0 X Gx_A Gx_B Gx_C Gx_D]
const CMD_WRITE_POWERDOWN: u8  = 0xA0; // [1 0 1 X ...]
const ADDR_GENERAL_CALL: u8    = 0x00;
const GC_RESET: u8             = 0x06;
const GC_SOFTWARE_UPD: u8      = 0x08;
const GC_WAKE: u8              = 0x09;

/// MCP4728 minimal driver — set voltage as fraction or raw 12-bit code on any
/// of the four channels, or update all four simultaneously.
pub struct Mcp4728Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Mcp4728Minimal<I2C> {
    /// Create a new `Mcp4728Minimal`.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (typically `0x60`–`0x67`).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Ok(Self { i2c, addr })
    }

    /// Set one channel's DAC output as a fraction of V_DD.
    ///
    /// Clamps to [0.0, 1.0] and uses Multi-Write to update a single channel's
    /// volatile DAC register. V_REF=external, gain=×1, PD=00, UDAC=0.
    pub fn set_voltage(&mut self, channel: u8, fraction: f32) -> Result<(), I2C::Error> {
        let f = fraction.max(0.0).min(1.0);
        let code = (f * 4095.0) as u16;
        self.set_raw(channel, code)
    }

    /// Set one channel's raw 12-bit DAC code.
    ///
    /// Clamps to [0, 4095] and uses Multi-Write. EEPROM is not written.
    pub fn set_raw(&mut self, channel: u8, code: u16) -> Result<(), I2C::Error> {
        let ch = channel.min(3);
        let c = code.min(4095);
        self._multi_write(ch, c, 0, 0, 0, 0)
    }

    /// Update all four channels simultaneously using Fast Write.
    ///
    /// Issues a single 8-byte Fast Write transaction that updates channels
    /// A→D at once. PD=00, UDAC=0 for all four channels. EEPROM is not
    /// written.
    pub fn set_all(&mut self, fractions: [f32; 4]) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 8];
        for i in 0..4 {
            let f = fractions[i].max(0.0).min(1.0);
            let code = ((f * 4095.0) as u16).min(4095);
            buf[i * 2]     = ((code >> 8) & 0x0F) as u8;
            buf[i * 2 + 1] = (code & 0xFF) as u8;
        }
        self.i2c.write(self.addr, &buf)
    }

    fn _multi_write(&mut self, channel: u8, code: u16, vref: u8, pd: u8, gain: u8, udac: u8) -> Result<(), I2C::Error> {
        let buf = [
            CMD_MULTI_WRITE_BASE | ((channel & 0x03) << 1) | (udac & 0x01),
            ((vref & 0x01) << 7) | ((pd & 0x03) << 5) | ((gain & 0x01) << 4) | ((code >> 8) & 0x0F) as u8,
            (code & 0xFF) as u8,
        ];
        self.i2c.write(self.addr, &buf)
    }
}

/// MCP4728 full driver — extends [`Mcp4728Minimal`] with EEPROM, V_REF, gain, power-down, and read-back.
pub struct Mcp4728Full<I2C> {
    inner: Mcp4728Minimal<I2C>,
}

/// Per-channel state read from the chip.
#[derive(Clone, Copy, Debug)]
pub struct ChannelState {
    /// DAC input register code (0–4095).
    pub code: u16,
    /// V_REF selection: 0 = external (V_DD), 1 = internal (2.048 V).
    pub vref: u8,
    /// Gain: 1 = ×1, 2 = ×2.
    pub gain: u8,
    /// Power-down mode 0–3.
    pub power_down: u8,
    /// EEPROM-stored DAC code (0–4095).
    pub eeprom_code: u16,
    /// EEPROM-stored V_REF (0/1).
    pub eeprom_vref: u8,
    /// EEPROM-stored gain (1/2).
    pub eeprom_gain: u8,
    /// EEPROM-stored power-down mode (0–3).
    pub eeprom_power_down: u8,
}

/// All four channels plus the EEPROM-ready flag.
#[derive(Clone, Copy, Debug)]
pub struct ReadResult {
    pub channel: [ChannelState; 4],
    pub eeprom_ready: bool,
}

impl<I2C: I2c> Mcp4728Full<I2C> {
    /// Create a new `Mcp4728Full`.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (typically `0x60`–`0x67`).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Ok(Self { inner: Mcp4728Minimal::new(i2c, addr)? })
    }

    pub fn set_voltage(&mut self, channel: u8, fraction: f32) -> Result<(), I2C::Error> {
        self.inner.set_voltage(channel, fraction)
    }

    pub fn set_raw(&mut self, channel: u8, code: u16) -> Result<(), I2C::Error> {
        self.inner.set_raw(channel, code)
    }

    pub fn set_all(&mut self, fractions: [f32; 4]) -> Result<(), I2C::Error> {
        self.inner.set_all(fractions)
    }

    /// Set one channel's output and persist to EEPROM (Single Write).
    pub fn set_voltage_eeprom(&mut self, channel: u8, fraction: f32, vref: u8, gain: u8) -> Result<(), I2C::Error> {
        let f = fraction.max(0.0).min(1.0);
        let code = (f * 4095.0) as u16;
        self._single_write(channel, code, vref, 0, gain, 0)
    }

    /// Set one channel's raw 12-bit code and persist to EEPROM.
    pub fn set_raw_eeprom(&mut self, channel: u8, code: u16, vref: u8, gain: u8) -> Result<(), I2C::Error> {
        let ch = channel.min(3);
        let c = code.min(4095);
        self._single_write(ch, c, vref, 0, gain, 0)
    }

    /// Update all four channels and EEPROM (Sequential Write from A to D).
    pub fn set_all_eeprom(&mut self, fractions: [f32; 4], vrefs: [u8; 4], gains: [u8; 4]) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 9];
        buf[0] = CMD_SEQUENTIAL_BASE | 0x00;
        for i in 0..4 {
            let f = fractions[i].max(0.0).min(1.0);
            let code = ((f * 4095.0) as u16).min(4095);
            let v = vrefs[i] & 0x01;
            let g = if gains[i] == 2 { 1 } else { 0 };
            // Per-channel byte layout (Multi-Write format): [V_REF PD1 PD0 Gx D11-D8]
            // PD bits are always 0 here (no power-down is set in this command).
            buf[1 + i * 2]     = (v << 7) | (g << 4) | ((code >> 8) & 0x0F) as u8;
            buf[1 + i * 2 + 1] = (code & 0xFF) as u8;
        }
        self.inner.i2c.write(self.inner.addr, &buf)
    }

    /// Set V_REF for all four channels (volatile register only).
    pub fn set_vref(&mut self, vref_a: u8, vref_b: u8, vref_c: u8, vref_d: u8) -> Result<(), I2C::Error> {
        let byte1 = CMD_WRITE_VREF
            | ((vref_a & 0x01) << 3) | ((vref_b & 0x01) << 2)
            | ((vref_c & 0x01) << 1) |  (vref_d & 0x01);
        self.inner.i2c.write(self.inner.addr, &[byte1])
    }

    /// Set gain for all four channels (volatile register only).
    pub fn set_gain(&mut self, gain_a: u8, gain_b: u8, gain_c: u8, gain_d: u8) -> Result<(), I2C::Error> {
        let byte1 = CMD_WRITE_GAIN
            | ((if gain_a == 2 { 1 } else { 0 }) << 3)
            | ((if gain_b == 2 { 1 } else { 0 }) << 2)
            | ((if gain_c == 2 { 1 } else { 0 }) << 1)
            |  (if gain_d == 2 { 1 } else { 0 });
        self.inner.i2c.write(self.inner.addr, &[byte1])
    }

    /// Set power-down mode for all four channels (volatile register only).
    pub fn set_power_down(&mut self, pd_a: u8, pd_b: u8, pd_c: u8, pd_d: u8) -> Result<(), I2C::Error> {
        let byte1 = CMD_WRITE_POWERDOWN
            | (((pd_a >> 1) & 0x01) << 4) | ((pd_a & 0x01) << 3)
            | (((pd_b >> 1) & 0x01) << 2) | ((pd_b & 0x01) << 1);
        let byte2 = (((pd_c >> 1) & 0x01) << 6) | ((pd_c & 0x01) << 5)
                  | (((pd_d >> 1) & 0x01) << 4) | ((pd_d & 0x01) << 3);
        self.inner.i2c.write(self.inner.addr, &[byte1, byte2])
    }

    /// Read all four channels' DAC input registers and EEPROM contents.
    pub fn read(&mut self) -> Result<ReadResult, I2C::Error> {
        let mut buf = [0u8; 24];
        self.inner.i2c.read(self.inner.addr, &mut buf)?;
        let mut result = ReadResult {
            channel: [ChannelState { code: 0, vref: 0, gain: 1, power_down: 0,
                                    eeprom_code: 0, eeprom_vref: 0, eeprom_gain: 1, eeprom_power_down: 0 }; 4],
            eeprom_ready: (buf[0] & 0x80) != 0,
        };
        for i in 0..4 {
            let b = i * 3;
            result.channel[i].code       = (((buf[b + 1] & 0x0F) as u16) << 8) | buf[b + 2] as u16;
            result.channel[i].vref       = (buf[b + 1] >> 7) & 0x01;
            result.channel[i].power_down = (buf[b + 1] >> 5) & 0x03;
            result.channel[i].gain       = if (buf[b + 1] >> 4) & 0x01 != 0 { 2 } else { 1 };
        }
        for i in 0..4 {
            let b = 12 + i * 3;
            result.channel[i].eeprom_code       = (((buf[b + 1] & 0x0F) as u16) << 8) | buf[b + 2] as u16;
            result.channel[i].eeprom_vref       = (buf[b + 1] >> 7) & 0x01;
            result.channel[i].eeprom_power_down = (buf[b + 1] >> 5) & 0x03;
            result.channel[i].eeprom_gain       = if (buf[b + 1] >> 4) & 0x01 != 0 { 2 } else { 1 };
        }
        Ok(result)
    }

    /// Check if the EEPROM write is complete (RDY/BSY = 1).
    pub fn is_eeprom_ready(&mut self) -> Result<bool, I2C::Error> {
        let mut buf = [0u8; 1];
        self.inner.i2c.read(self.inner.addr, &mut buf)?;
        Ok((buf[0] & 0x80) != 0)
    }

    /// Send General Call Software Update (0x00, 0x08) to latch all V_OUT.
    pub fn software_update(&mut self) -> Result<(), I2C::Error> {
        self.inner.i2c.write(ADDR_GENERAL_CALL, &[GC_SOFTWARE_UPD])
    }

    /// Send General Call Wake-Up (0x00, 0x09) to clear all PD bits.
    pub fn wake_up(&mut self) -> Result<(), I2C::Error> {
        self.inner.i2c.write(ADDR_GENERAL_CALL, &[GC_WAKE])
    }

    /// Send General Call Reset (0x00, 0x06) to reload EEPROM into all DAC registers.
    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        self.inner.i2c.write(ADDR_GENERAL_CALL, &[GC_RESET])
    }

    fn _single_write(&mut self, channel: u8, code: u16, vref: u8, pd: u8, gain: u8, udac: u8) -> Result<(), I2C::Error> {
        let ch = channel.min(3);
        let c = code.min(4095);
        let buf = [
            CMD_SINGLE_WRITE | ((ch & 0x03) << 1) | (udac & 0x01),
            ((vref & 0x01) << 7) | ((pd & 0x03) << 5) | ((gain & 0x01) << 4) | ((c >> 8) & 0x0F) as u8,
            (c & 0xFF) as u8,
        ];
        self.inner.i2c.write(self.inner.addr, &buf)
    }
}
