//! PCF8576 — 40x4 universal LCD segment driver (NXP).
//!
//! Communicates over I²C (address 0x38 when SA0 = VSS, 0x39 when SA0 = VDD).
//! The chip is write-only — it never returns data to the host. The protocol is
//! a command stream: one or more command bytes (continuation bit C set on all
//! but the last) followed by zero or more display data bytes.
//!
//! ## Constants
//!
//! Drive mode: [`MODE_1_4`], [`MODE_STATIC`], [`MODE_1_2`], [`MODE_1_3`]
//! Bias: [`BIAS_1_3`], [`BIAS_1_2`]
//! Blink: [`BLINK_OFF`], [`BLINK_2_HZ`], [`BLINK_1_HZ`], [`BLINK_0_5_HZ`]
//! 7-segment lookup: [`SEVEN_SEG`]

use embedded_hal::i2c::I2c;

pub const ADDR_SA0_LOW: u8 = 0x38;
pub const ADDR_SA0_HIGH: u8 = 0x39;

const CMD_MODE_SET: u8 = 0x40;
const CMD_LOAD_PTR: u8 = 0x00;
const CMD_DEVICE_SELECT: u8 = 0x60;
const CMD_BANK_SELECT: u8 = 0x78;
const CMD_BLINK_SELECT: u8 = 0x70;

pub const MODE_1_4: u8 = 0x00;
pub const MODE_STATIC: u8 = 0x01;
pub const MODE_1_2: u8 = 0x02;
pub const MODE_1_3: u8 = 0x03;

pub const BIAS_1_3: u8 = 0x00;
pub const BIAS_1_2: u8 = 0x04;

pub const DISPLAY_OFF: u8 = 0x00;
pub const DISPLAY_ON: u8 = 0x08;

pub const BLINK_OFF: u8 = 0;
pub const BLINK_2_HZ: u8 = 1;
pub const BLINK_1_HZ: u8 = 2;
pub const BLINK_0_5_HZ: u8 = 3;

pub const BACKPLANES_1: u8 = 1;
pub const BACKPLANES_2: u8 = 2;
pub const BACKPLANES_3: u8 = 3;
pub const BACKPLANES_4: u8 = 4;

pub const BANK_0: u8 = 0;
pub const BANK_1: u8 = 1;

/// 7-segment lookup table for 1:4 multiplex mode (a/c/b/DP/f/e/g/d packed MSB-first).
pub const SEVEN_SEG: [u8; 10] = [
    0xED, 0x60, 0xA7, 0xE3, 0x6A,
    0xCB, 0xCF, 0xE0, 0xEF, 0xEB,
];

fn send_commands<I2C: I2c>(i2c: &mut I2C, addr: u8, cmds: &[u8]) -> Result<(), I2C::Error> {
    if cmds.is_empty() { return Ok(()); }
    let mut buf = [0u8; 8];
    for (i, c) in cmds.iter().enumerate() {
        if i + 1 < cmds.len() {
            buf[i] = 0x80 | (c & 0x7F);
        } else {
            buf[i] = c & 0x7F;
        }
    }
    i2c.write(addr, &buf[..cmds.len()])
}

fn send_commands_with_data<I2C: I2c>(
    i2c: &mut I2C,
    addr: u8,
    cmd: u8,
    data: &[u8],
) -> Result<(), I2C::Error> {
    let mut buf = [0u8; 64];
    buf[0] = cmd & 0x7F;
    let n = data.len().min(63);
    buf[1..=n].copy_from_slice(&data[..n]);
    i2c.write(addr, &buf[..=n])
}

fn cmd_mode(enable: bool, bias: u8, mode: u8) -> u8 {
    CMD_MODE_SET | (if enable { DISPLAY_ON } else { DISPLAY_OFF }) | bias | mode
}

/// PCF8576 minimal driver — drives a single 7-segment LCD display.
///
/// Default: 1:4 multiplex drive mode, 1/3 bias, display enabled, and a
/// 7-segment digit lookup table for the default multiplex mode.
pub struct Pcf8576Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    backplanes: u8,
}

impl<I2C: I2c> Pcf8576Minimal<I2C> {
    /// Create a new `Pcf8576Minimal` and initialise the chip with defaults.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x38 or 0x39).
    pub fn new(mut i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let mut s = Self { i2c, addr, backplanes: MODE_1_4 };
        s.do_clear()?;
        Ok(s)
    }

    /// Release the I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    fn do_clear(&mut self) -> Result<(), I2C::Error> {
        send_commands(&mut self.i2c, self.addr, &[cmd_mode(true, BIAS_1_3, MODE_1_4)])?;
        let zeros = [0u8; 20];
        send_commands_with_data(&mut self.i2c, self.addr, CMD_LOAD_PTR, &zeros)?;
        Ok(())
    }

    /// Zero all 40 columns of display RAM; all segments off.
    pub fn clear(&mut self) -> Result<(), I2C::Error> {
        self.do_clear()
    }

    /// Set the data pointer to `address` and write raw data bytes.
    ///
    /// # Arguments
    /// * `address` — RAM column address, 0-39.
    /// * `data` — Bytes to write to display RAM; one byte covers two
    ///             adjacent columns in 1:4 multiplex mode.
    pub fn write_raw(&mut self, address: u8, data: &[u8]) -> Result<(), I2C::Error> {
        if data.is_empty() { return Ok(()); }
        send_commands_with_data(&mut self.i2c, self.addr, CMD_LOAD_PTR | (address & 0x3F), data)
    }

    /// Write one 7-segment byte at column `position * 2`.
    ///
    /// # Arguments
    /// * `position` — Digit index, 0-19. Maps to RAM address `position * 2`.
    /// * `segments` — 7-segment byte (a/c/b/DP/f/e/g/d packed, MSB-first).
    ///                 Add 0x10 to set the decimal point.
    pub fn set_digit_7seg(&mut self, position: u8, segments: u8) -> Result<(), I2C::Error> {
        self.write_raw(position * 2, &[segments])
    }
}

/// PCF8576 full driver — extends minimal with drive mode, bias, and blink control.
pub struct Pcf8576Full<I2C> {
    inner: Pcf8576Minimal<I2C>,
    enabled: bool,
    bias: u8,
}

impl<I2C: I2c> Pcf8576Full<I2C> {
    /// Create a new `Pcf8576Full`.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x38 or 0x39).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let inner = Pcf8576Minimal::new(i2c, addr)?;
        Ok(Self { inner, enabled: true, bias: BIAS_1_3 })
    }

    /// Release the I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    fn mode_code(backplanes: u8) -> u8 {
        match backplanes {
            BACKPLANES_1 => MODE_STATIC,
            BACKPLANES_2 => MODE_1_2,
            BACKPLANES_3 => MODE_1_3,
            _            => MODE_1_4,
        }
    }

    fn apply_mode(&mut self) -> Result<(), I2C::Error> {
        let bias = if self.bias == BIAS_1_2 { BIAS_1_2 } else { BIAS_1_3 };
        send_commands(
            &mut self.inner.i2c,
            self.inner.addr,
            &[cmd_mode(self.enabled, bias, Self::mode_code(self.inner.backplanes))],
        )
    }

    /// Turn the display on (E = 1). RAM contents are preserved.
    pub fn enable(&mut self) -> Result<(), I2C::Error> {
        self.enabled = true;
        self.apply_mode()
    }

    /// Blank the display output (E = 0). RAM contents are preserved.
    pub fn disable(&mut self) -> Result<(), I2C::Error> {
        self.enabled = false;
        self.apply_mode()
    }

    /// Reconfigure drive mode and bias at runtime.
    ///
    /// # Arguments
    /// * `backplanes` — Number of backplanes: 1 (static), 2 (1:2), 3 (1:3), 4 (1:4).
    /// * `bias`       — 0 = 1/3 bias, 1 = 1/2 bias.
    pub fn set_mode(&mut self, backplanes: u8, bias: u8) -> Result<(), I2C::Error> {
        self.inner.backplanes = backplanes;
        self.bias = bias;
        self.apply_mode()
    }

    /// Set the blink frequency.
    ///
    /// # Arguments
    /// * `frequency`     — 0 = off, 1 = ~2 Hz, 2 = ~1 Hz, 3 = ~0.5 Hz.
    /// * `alternate_bank` — `true` enables alternate-RAM-bank blinking (static/1:2 only).
    pub fn set_blink(&mut self, frequency: u8, alternate_bank: bool) -> Result<(), I2C::Error> {
        let ab = if alternate_bank { 0x04 } else { 0x00 };
        send_commands(&mut self.inner.i2c, self.inner.addr, &[CMD_BLINK_SELECT | ab | (frequency & 0x03)])
    }

    /// Select the active RAM bank.
    ///
    /// # Arguments
    /// * `input_bank`  — 0 (rows 0-1) or 1 (rows 2-3).
    /// * `output_bank` — 0 (rows 0-1) or 1 (rows 2-3).
    /// Only meaningful in static and 1:2 multiplex modes.
    pub fn set_bank(&mut self, input_bank: u8, output_bank: u8) -> Result<(), I2C::Error> {
        send_commands(
            &mut self.inner.i2c,
            self.inner.addr,
            &[CMD_BANK_SELECT | ((input_bank & 1) << 1) | (output_bank & 1)],
        )
    }

    /// Change the subaddress counter for cascaded displays.
    ///
    /// # Arguments
    /// * `subaddress` — 0-7; must match the A0/A1/A2 pin state of the target device on the bus.
    pub fn device_select(&mut self, subaddress: u8) -> Result<(), I2C::Error> {
        send_commands(&mut self.inner.i2c, self.inner.addr, &[CMD_DEVICE_SELECT | (subaddress & 0x07)])
    }

    /// Zero all 40 columns of display RAM; all segments off.
    pub fn clear(&mut self) -> Result<(), I2C::Error> {
        self.inner.clear()
    }

    /// Set the data pointer to `address` and write raw data bytes.
    pub fn write_raw(&mut self, address: u8, data: &[u8]) -> Result<(), I2C::Error> {
        self.inner.write_raw(address, data)
    }

    /// Write one 7-segment byte at column `position * 2`.
    pub fn set_digit_7seg(&mut self, position: u8, segments: u8) -> Result<(), I2C::Error> {
        self.inner.set_digit_7seg(position, segments)
    }
}
