//! MCP4725 single-channel 12-bit voltage-output DAC (Microchip).
//!
//! Communicates over I²C (up to 3.4 MHz high-speed, 400 kHz fast mode, 100 kHz standard).
//! Provides voltage output as a fraction of V_DD with no configuration beyond the transport.
//! The chip has on-chip EEPROM that automatically loads into the DAC register on power-up.

use embedded_hal::i2c::I2c;

const CMD_FAST_WRITE: u8 = 0x00;
const CMD_WRITE_DAC_EEPROM: u8 = 0x60;
const ADDR_GENERAL_CALL: u8 = 0x00;
const GC_RESET: u8 = 0x06;
const GC_WAKE: u8 = 0x09;

/// MCP4725 minimal driver — set voltage as fraction or raw 12-bit code.
///
/// Uses Fast Write (2-byte) for DAC register updates only. EEPROM is unaffected.
pub struct Mcp4725Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Mcp4725Minimal<I2C> {
    /// Create a new `Mcp4725Minimal`.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (typically `0x60`–`0x61`).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Ok(Self { i2c, addr })
    }

    /// Set the DAC output as a fraction of V_DD.
    ///
    /// Clamps to [0.0, 1.0] and uses Fast Write to update the DAC register only
    /// (EEPROM unchanged).
    pub fn set_voltage(&mut self, fraction: f32) -> Result<(), I2C::Error> {
        let f = fraction.max(0.0).min(1.0);
        let code = (f * 4095.0).round() as u16;
        self._fast_write(code, 0)
    }

    /// Set the raw 12-bit DAC code directly.
    ///
    /// Clamps to [0, 4095] and uses Fast Write to update the DAC register only
    /// (EEPROM unchanged).
    pub fn set_raw(&mut self, code: u16) -> Result<(), I2C::Error> {
        let code = code.min(4095);
        self._fast_write(code, 0)
    }

    fn _fast_write(&mut self, code: u16, pd_mode: u8) -> Result<(), I2C::Error> {
        let buf = [
            ((pd_mode & 0x03) << 4) as u8 | ((code >> 8) & 0x0F) as u8,
            (code & 0xFF) as u8,
        ];
        self.i2c.write(self.addr, &buf)
    }
}

/// MCP4725 full driver — extends [`Mcp4725Minimal`] with EEPROM, power-down, and read-back.
pub struct Mcp4725Full<I2C> {
    inner: Mcp4725Minimal<I2C>,
}

impl<I2C: I2c> Mcp4725Full<I2C> {
    /// Create a new `Mcp4725Full`.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (typically `0x60`–`0x61`).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Ok(Self { inner: Mcp4725Minimal::new(i2c, addr)? })
    }

    pub fn set_voltage(&mut self, fraction: f32) -> Result<(), I2C::Error> {
        self.inner.set_voltage(fraction)
    }

    pub fn set_raw(&mut self, code: u16) -> Result<(), I2C::Error> {
        self.inner.set_raw(code)
    }

    /// Set the DAC output and persist to EEPROM.
    ///
    /// Writes both the DAC register and EEPROM so the value survives power cycles.
    pub fn set_voltage_eeprom(&mut self, fraction: f32) -> Result<(), I2C::Error> {
        let f = fraction.max(0.0).min(1.0);
        let code = (f * 4095.0).round() as u16;
        self._write_dac_eeprom(code, 0)
    }

    /// Set the raw 12-bit DAC code and persist to EEPROM.
    ///
    /// Writes both the DAC register and EEPROM so the value survives power cycles.
    pub fn set_raw_eeprom(&mut self, code: u16) -> Result<(), I2C::Error> {
        let code = code.min(4095);
        self._write_dac_eeprom(code, 0)
    }

    /// Read the current DAC register and EEPROM contents.
    ///
    /// Returns a tuple of (code, voltage_fraction, power_down, eeprom_code,
    /// eeprom_power_down, eeprom_ready).
    pub fn read(&mut self) -> Result<(u16, f32, u8, u16, u8, bool), I2C::Error> {
        let mut buf = [0u8; 5];
        self.i2c.write_read(self.inner.addr, &[0x00], &mut buf)?;
        let code = (((buf[1] & 0x0F) as u16) << 8) | buf[2] as u16;
        let voltage_fraction = code as f32 / 4095.0;
        let power_down = (buf[0] >> 2) & 0x03;
        let eeprom_code = (((buf[3] & 0x0F) as u16) << 8) | buf[4] as u16;
        let eeprom_power_down = (buf[3] >> 6) & 0x03;
        let eeprom_ready = (buf[0] & 0x80) != 0;
        Ok((code, voltage_fraction, power_down, eeprom_code, eeprom_power_down, eeprom_ready))
    }

    /// Set the power-down mode and preserve the current DAC code.
    ///
    /// Mode: 0 = normal, 1 = 1 kΩ to GND, 2 = 100 kΩ to GND, 3 = 500 kΩ to GND.
    pub fn set_power_down(&mut self, mode: u8) -> Result<(), I2C::Error> {
        let mode = mode.min(3);
        let code = self._read_dac_code()?;
        self.inner._fast_write(code, mode)
    }

    /// Send General Call Wake-Up (0x00, 0x09) to clear power-down bits.
    pub fn wake_up(&mut self) -> Result<(), I2C::Error> {
        self.i2c.write(ADDR_GENERAL_CALL, &[GC_WAKE])
    }

    /// Send General Call Reset (0x00, 0x06) to trigger internal POR.
    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        self.i2c.write(ADDR_GENERAL_CALL, &[GC_RESET])
    }

    /// Check if the EEPROM write operation is complete.
    ///
    /// Returns true when a pending EEPROM write has finished.
    pub fn is_eeprom_ready(&mut self) -> Result<bool, I2C::Error> {
        let mut buf = [0u8; 1];
        self.i2c.write_read(self.inner.addr, &[0x00], &mut buf)?;
        Ok((buf[0] & 0x80) != 0)
    }

    fn _write_dac_eeprom(&mut self, code: u16, pd_mode: u8) -> Result<(), I2C::Error> {
        let buf = [
            CMD_WRITE_DAC_EEPROM | ((pd_mode & 0x03) << 1),
            (code >> 4) as u8,
            ((code & 0x0F) << 4) as u8,
        ];
        self.i2c.write(self.inner.addr, &buf)
    }

    fn _read_dac_code(&mut self) -> Result<u16, I2C::Error> {
        let mut buf = [0u8; 2];
        self.i2c.write_read(self.inner.addr, &[0x00], &mut buf)?;
        Ok(((buf[0] & 0x0F) as u16) << 8 | buf[1] as u16)
    }
}