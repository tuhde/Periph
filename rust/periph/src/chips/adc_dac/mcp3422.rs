//! MCP3422 — 2-channel, 18-bit delta-sigma ADC with PGA and 2.048 V reference (Microchip).
//!
//! Two differential inputs, an on-board 2.048 V reference, a programmable-gain
//! amplifier (x1, x2, x4, x8) and an on-board oscillator behind an I²C
//! interface. The resolution is selected together with the data rate: 12 bit /
//! 240 SPS, 14 bit / 60 SPS, 16 bit / 15 SPS, 18 bit / 3.75 SPS. The
//! full-scale differential input range is ±2.048 V / gain. The 7-bit I²C
//! address is `0x68` for the standard part (`0x68`–`0x6F` order-code variants).
//!
//! The chip has no register map: a write is one configuration byte
//! (`RDY C1 C0 O/C S1 S0 G1 G0`) and a read returns the 2 (12/14/16 bit) or 3
//! (18 bit) result bytes followed by the configuration byte, whose `RDY` bit
//! (0 = fresh result) says whether the conversion has finished. The driver
//! therefore uses plain `embedded-hal` I²C `write` / `read` calls. A delay
//! provider is needed for the conversion waits.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

/// 7-bit I²C address of the standard part (order code A0).
pub const MCP3422_I2C_ADDRESS: u8 = 0x68;

const CFG_RDY: u8 = 0x80;
const CFG_CONT: u8 = 0x10;
const VREF: f32 = 2.048;

/// Errors returned by the MCP3422 driver.
#[derive(Debug)]
pub enum Mcp3422Error<E> {
    /// I²C bus error.
    I2c(E),
    /// The conversion did not finish within twice its nominal time plus 10 ms.
    Timeout,
    /// An argument was outside the supported set (resolution, gain).
    InvalidArgument,
    /// A continuous read was requested while not in continuous mode.
    NotContinuous,
}

impl<E> From<E> for Mcp3422Error<E> {
    fn from(e: E) -> Self {
        Mcp3422Error::I2c(e)
    }
}

fn bits_to_s(bits: u8) -> u8 {
    match bits {
        12 => 0,
        14 => 1,
        16 => 2,
        _ => 3,
    }
}

fn gain_to_g(gain: u8) -> u8 {
    match gain {
        1 => 0,
        2 => 1,
        4 => 2,
        _ => 3,
    }
}

/// Nominal conversion time in microseconds.
fn conv_us(bits: u8) -> u32 {
    match bits {
        12 => 4_167,
        14 => 16_667,
        16 => 66_667,
        _ => 266_667,
    }
}

/// MCP3422 — minimal interface: read both differential inputs with one one-shot
/// conversion per call (one-shot, 16 bit / 15 SPS, gain x1 by default).
pub struct MCP3422Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    bits: u8,
    gain: u8,
    channel: u8,
    continuous: bool,
    discard: bool,
}

impl<I2C: I2c> MCP3422Minimal<I2C> {
    /// Construct the driver; performs no I²C traffic (call [`Self::init`] once).
    /// `addr` is `0x68`–`0x6F` ([`MCP3422_I2C_ADDRESS`]).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, bits: 16, gain: 1, channel: 0, continuous: false, discard: false }
    }

    /// Write the default configuration (one-shot, 16 bit / 15 SPS, gain x1, CH1,
    /// `RDY` = 0); nothing is started and the chip goes to standby.
    pub fn init(&mut self) -> Result<(), I2C::Error> {
        let cfg = self.config(false);
        self.i2c.write(self.addr, &[cfg])
    }

    pub(crate) fn config(&self, rdy: bool) -> u8 {
        let mut c = (self.channel << 5) | (bits_to_s(self.bits) << 2) | gain_to_g(self.gain);
        if rdy {
            c |= CFG_RDY;
        }
        if self.continuous {
            c |= CFG_CONT;
        }
        c
    }

    fn nbytes(&self) -> usize {
        if self.bits == 18 { 3 } else { 2 }
    }

    fn decode(&self, data: &[u8]) -> i32 {
        let mut raw: i64 = 0;
        for b in &data[..self.nbytes()] {
            raw = (raw << 8) | *b as i64;
        }
        let span = 1i64 << self.bits;
        raw &= span - 1;
        if raw >= span / 2 {
            raw -= span;
        }
        raw as i32
    }

    pub(crate) fn wait_ready<D: DelayNs>(&mut self, delay: &mut D) -> Result<i32, Mcp3422Error<I2C::Error>> {
        let polls = (2 * conv_us(self.bits) + 10_000) / 5_000 + 1;
        let n = self.nbytes();
        let mut buf = [0u8; 4];
        for _ in 0..polls {
            self.i2c.read(self.addr, &mut buf[..n + 1])?;
            if buf[n] & CFG_RDY == 0 {
                return Ok(self.decode(&buf));
            }
            delay.delay_ms(5);
        }
        Err(Mcp3422Error::Timeout)
    }

    fn convert<D: DelayNs>(&mut self, delay: &mut D, channel: u8) -> Result<i32, Mcp3422Error<I2C::Error>> {
        self.channel = if channel < 1 { 0 } else { 1 };
        self.continuous = false;
        self.discard = false;
        let cfg = self.config(true);
        self.i2c.write(self.addr, &[cfg])?;
        delay.delay_us(conv_us(self.bits) / 10 * 11);
        self.wait_ready(delay)
    }

    /// Size of one code before the gain, `2 · 2.048 / 2^bits` in volts.
    pub fn lsb(&self) -> f32 {
        2.0 * VREF / (1u32 << self.bits) as f32
    }

    /// Run a one-shot conversion on a channel (0 = CH1, 1 = CH2; larger values
    /// are clamped to 1) and return the signed code.
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D, channel: u8) -> Result<i32, Mcp3422Error<I2C::Error>> {
        self.convert(delay, channel)
    }

    /// Run a one-shot conversion on a channel and return the differential voltage
    /// `CHn+ − CHn−` in volts (`raw · lsb / gain`).
    pub fn read_voltage<D: DelayNs>(&mut self, delay: &mut D, channel: u8) -> Result<f32, Mcp3422Error<I2C::Error>> {
        let raw = self.convert(delay, channel)?;
        Ok(raw as f32 * self.lsb() / self.gain as f32)
    }

    /// Convert both channels, one conversion each: `[CH1, CH2]` voltages.
    pub fn read_all<D: DelayNs>(&mut self, delay: &mut D) -> Result<[f32; 2], Mcp3422Error<I2C::Error>> {
        Ok([self.read_voltage(delay, 0)?, self.read_voltage(delay, 1)?])
    }
}

/// MCP3422 — full interface: resolution, gain and channel selection, continuous
/// conversion, status reads, scans and general calls.
pub struct MCP3422Full<I2C> {
    inner: MCP3422Minimal<I2C>,
}

impl<I2C: I2c> MCP3422Full<I2C> {
    /// Construct the driver; performs no I²C traffic (call [`Self::init`] once).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: MCP3422Minimal::new(i2c, addr) }
    }

    /// Write the default configuration. See [`MCP3422Minimal::init`].
    pub fn init(&mut self) -> Result<(), I2C::Error> {
        self.inner.init()
    }

    /// See [`MCP3422Minimal::read_raw`].
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D, channel: u8) -> Result<i32, Mcp3422Error<I2C::Error>> {
        self.inner.read_raw(delay, channel)
    }

    /// See [`MCP3422Minimal::read_voltage`].
    pub fn read_voltage<D: DelayNs>(&mut self, delay: &mut D, channel: u8) -> Result<f32, Mcp3422Error<I2C::Error>> {
        self.inner.read_voltage(delay, channel)
    }

    /// See [`MCP3422Minimal::read_all`].
    pub fn read_all<D: DelayNs>(&mut self, delay: &mut D) -> Result<[f32; 2], Mcp3422Error<I2C::Error>> {
        self.inner.read_all(delay)
    }

    /// See [`MCP3422Minimal::lsb`].
    pub fn lsb(&self) -> f32 {
        self.inner.lsb()
    }

    fn apply(&mut self) -> Result<(), I2C::Error> {
        if self.inner.continuous {
            let cfg = self.inner.config(false);
            self.inner.i2c.write(self.inner.addr, &[cfg])?;
            self.inner.discard = true;
        }
        Ok(())
    }

    /// Select the resolution (and with it the data rate): 12 (240 SPS), 14
    /// (60 SPS), 16 (15 SPS) or 18 (3.75 SPS) bit; anything else returns
    /// [`Mcp3422Error::InvalidArgument`].
    pub fn set_resolution(&mut self, bits: u8) -> Result<(), Mcp3422Error<I2C::Error>> {
        if !matches!(bits, 12 | 14 | 16 | 18) {
            return Err(Mcp3422Error::InvalidArgument);
        }
        self.inner.bits = bits;
        Ok(self.apply()?)
    }

    /// The selected resolution: 12, 14, 16 or 18.
    pub fn get_resolution(&self) -> u8 {
        self.inner.bits
    }

    /// Select the PGA gain (1, 2, 4 or 8); anything else returns
    /// [`Mcp3422Error::InvalidArgument`].
    pub fn set_gain(&mut self, gain: u8) -> Result<(), Mcp3422Error<I2C::Error>> {
        if !matches!(gain, 1 | 2 | 4 | 8) {
            return Err(Mcp3422Error::InvalidArgument);
        }
        self.inner.gain = gain;
        Ok(self.apply()?)
    }

    /// The selected PGA gain: 1, 2, 4 or 8.
    pub fn get_gain(&self) -> u8 {
        self.inner.gain
    }

    /// Select the channel used by continuous conversion (0 or 1).
    pub fn set_channel(&mut self, channel: u8) -> Result<(), I2C::Error> {
        self.inner.channel = if channel < 1 { 0 } else { 1 };
        self.apply()
    }

    /// Switch to continuous conversion at the current rate and gain, optionally
    /// selecting a channel first.
    pub fn start_continuous(&mut self, channel: Option<u8>) -> Result<(), I2C::Error> {
        if let Some(c) = channel {
            self.inner.channel = if c < 1 { 0 } else { 1 };
        }
        self.inner.continuous = true;
        self.inner.discard = true;
        let cfg = self.inner.config(false);
        self.inner.i2c.write(self.inner.addr, &[cfg])
    }

    /// Return to one-shot mode / standby (writes `O/C` = 0, `RDY` = 0).
    pub fn stop_continuous(&mut self) -> Result<(), I2C::Error> {
        self.inner.continuous = false;
        self.inner.discard = false;
        let cfg = self.inner.config(false);
        self.inner.i2c.write(self.inner.addr, &[cfg])
    }

    /// Whether the driver is in continuous mode.
    pub fn is_continuous(&self) -> bool {
        self.inner.continuous
    }

    /// Wait for a fresh result in continuous mode and return the signed code. The
    /// first sample after a configuration change is discarded.
    pub fn read_continuous_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<i32, Mcp3422Error<I2C::Error>> {
        if !self.inner.continuous {
            return Err(Mcp3422Error::NotContinuous);
        }
        if self.inner.discard {
            self.inner.wait_ready(delay)?;
            self.inner.discard = false;
        }
        self.inner.wait_ready(delay)
    }

    /// Voltage form of [`Self::read_continuous_raw`], in volts.
    pub fn read_continuous<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Mcp3422Error<I2C::Error>> {
        let raw = self.read_continuous_raw(delay)?;
        Ok(raw as f32 * self.inner.lsb() / self.inner.gain as f32)
    }

    /// Read the configuration byte (the last byte of a read); bit 7 is the live
    /// `RDY` flag.
    pub fn read_config(&mut self) -> Result<u8, I2C::Error> {
        let n = self.inner.nbytes();
        let mut buf = [0u8; 4];
        self.inner.i2c.read(self.inner.addr, &mut buf[..n + 1])?;
        Ok(buf[n])
    }

    /// Whether a fresh result is available (`RDY` = 0).
    pub fn is_ready(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.read_config()? & CFG_RDY == 0)
    }

    /// Run one one-shot conversion per entry, in order; the voltages are written
    /// to `out` (which must be at least as long as `channels`).
    pub fn read_scan<D: DelayNs>(&mut self, delay: &mut D, channels: &[u8], out: &mut [f32]) -> Result<(), Mcp3422Error<I2C::Error>> {
        for (i, &ch) in channels.iter().enumerate() {
            out[i] = self.inner.read_voltage(delay, ch)?;
        }
        Ok(())
    }

    /// Send the general call reset (`0x00 0x06`): power-on defaults on the chip
    /// (continuous, 12 bit, gain x1, CH1); the driver state follows. Needs a bus
    /// that transmits to the general-call address.
    pub fn general_call_reset(&mut self) -> Result<(), I2C::Error> {
        self.inner.i2c.write(self.inner.addr, &[0x00, 0x06])?;
        self.inner.bits = 12;
        self.inner.gain = 1;
        self.inner.channel = 0;
        self.inner.continuous = true;
        self.inner.discard = true;
        Ok(())
    }

    /// Send the general call conversion (`0x00 0x08`): a one-shot conversion on
    /// every device on the bus; read the result with [`Self::read_pending_raw`].
    pub fn general_call_convert(&mut self) -> Result<(), I2C::Error> {
        self.inner.i2c.write(self.inner.addr, &[0x00, 0x08])?;
        self.inner.continuous = false;
        Ok(())
    }

    /// Wait for `RDY` = 0 and return the code of a conversion started elsewhere.
    pub fn read_pending_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<i32, Mcp3422Error<I2C::Error>> {
        self.inner.wait_ready(delay)
    }

    /// Full-scale differential input range at the current gain, `2.048 / gain` in volts.
    pub fn full_scale(&self) -> f32 {
        VREF / self.inner.gain as f32
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = MCP3422_I2C_ADDRESS;

    fn w(b: u8) -> T {
        T::write(ADDR, vec![b])
    }

    fn rd(bytes: Vec<u8>) -> T {
        T::read(ADDR, bytes)
    }

    fn f16(raw: i16, rdy: bool) -> T {
        let b = raw.to_be_bytes();
        rd(vec![b[0], b[1], if rdy { 0x80 } else { 0x00 }])
    }

    fn f12(raw: i16, rdy: bool) -> T {
        f16(raw & 0x0FFF, rdy)
    }

    fn f18(raw: i32, rdy: bool) -> T {
        let b = (raw & 0x3FFFF).to_be_bytes();
        rd(vec![b[1], b[2], b[3], if rdy { 0x80 } else { 0x00 }])
    }

    fn full(extra: Vec<T>) -> (I2cMock, MCP3422Full<I2cMock>) {
        let mut t = vec![w(0x08)];
        t.extend(extra);
        let i2c = I2cMock::new(&t);
        let mut chip = MCP3422Full::new(i2c.clone(), ADDR);
        chip.init().unwrap();
        (i2c, chip)
    }

    fn near(a: f32, b: f32) -> bool {
        (a - b).abs() < 1e-5
    }

    #[test]
    fn one_shot_config_bytes_and_channel_clamp() {
        let (mut i2c, mut chip) = full(vec![
            w(0x88), f16(0, false), w(0xA8), f16(0, false), w(0xA8), f16(0, false),
        ]);
        let mut d = NoopDelay::new();
        chip.read_raw(&mut d, 0).unwrap();
        chip.read_raw(&mut d, 1).unwrap();
        chip.read_raw(&mut d, 7).unwrap();
        i2c.done();
    }

    #[test]
    fn decoding_and_sign_extension() {
        let (mut i2c, mut chip) = full(vec![
            w(0x88), f16(12345, false),
            w(0x88), f16(-12345, false),
            w(0x88), f16(-32768, false),
            w(0x80), f12(-100, false),
            w(0x80), f12(2047, false),
        ]);
        let mut d = NoopDelay::new();
        assert_eq!(chip.read_raw(&mut d, 0).unwrap(), 12345);
        assert_eq!(chip.read_raw(&mut d, 0).unwrap(), -12345);
        assert_eq!(chip.read_raw(&mut d, 0).unwrap(), -32768);
        chip.set_resolution(12).unwrap();
        assert_eq!(chip.read_raw(&mut d, 0).unwrap(), -100);
        assert_eq!(chip.read_raw(&mut d, 0).unwrap(), 2047);
        i2c.done();
    }

    #[test]
    fn eighteen_bit_decoding_and_masking() {
        let (mut i2c, mut chip) = full(vec![
            w(0x8C), f18(131071, false),
            w(0x8C), f18(-131072, false),
            w(0x8C), f18(-1, false),
            w(0x8C), rd(vec![0xFC, 0x00, 0x05, 0x00]),
        ]);
        let mut d = NoopDelay::new();
        chip.set_resolution(18).unwrap();
        assert_eq!(chip.read_raw(&mut d, 0).unwrap(), 131071);
        assert_eq!(chip.read_raw(&mut d, 0).unwrap(), -131072);
        assert_eq!(chip.read_raw(&mut d, 0).unwrap(), -1);
        assert_eq!(chip.read_raw(&mut d, 0).unwrap(), 5);
        i2c.done();
    }

    #[test]
    fn voltages_lsb_and_full_scale() {
        let (mut i2c, mut chip) = full(vec![w(0x88), f16(16384, false), w(0x8B), f16(-32768, false)]);
        let mut d = NoopDelay::new();
        assert!(near(chip.read_voltage(&mut d, 0).unwrap(), 16384.0 * 2.0 * 2.048 / 65536.0));
        chip.set_gain(8).unwrap();
        assert!(near(chip.read_voltage(&mut d, 0).unwrap(), -0.256));
        assert!(near(chip.full_scale(), 0.256));
        assert!(near(chip.lsb(), 62.5e-6));
        chip.set_resolution(12).unwrap();
        assert!(near(chip.lsb(), 1e-3));
        chip.set_resolution(18).unwrap();
        assert!(near(chip.lsb(), 15.625e-6));
        i2c.done();
    }

    #[test]
    fn read_all_and_scan_order() {
        let (mut i2c, mut chip) = full(vec![
            w(0x88), f16(100, false), w(0xA8), f16(200, false),
            w(0xA8), f16(10, false), w(0x88), f16(20, false),
        ]);
        let mut d = NoopDelay::new();
        let v = chip.read_all(&mut d).unwrap();
        assert!(near(v[0], 100.0 * 2.0 * 2.048 / 65536.0) && near(v[1], 200.0 * 2.0 * 2.048 / 65536.0));
        let mut out = [0.0f32; 2];
        chip.read_scan(&mut d, &[1, 0], &mut out).unwrap();
        i2c.done();
    }

    #[test]
    fn polls_until_ready_and_times_out() {
        let (mut i2c, mut chip) = full(vec![w(0x88), f16(1, true), f16(1, true), f16(777, false)]);
        let mut d = NoopDelay::new();
        assert_eq!(chip.read_raw(&mut d, 0).unwrap(), 777);
        i2c.done();

        let mut stale = vec![w(0x88)];
        for _ in 0..29 {
            stale.push(f16(1, true));
        }
        let (mut i2c, mut chip) = full(stale);
        assert!(matches!(chip.read_raw(&mut d, 0), Err(Mcp3422Error::Timeout)));
        i2c.done();
    }

    #[test]
    fn selection_validation() {
        let (mut i2c, mut chip) = full(vec![]);
        assert!(matches!(chip.set_resolution(10), Err(Mcp3422Error::InvalidArgument)));
        assert!(matches!(chip.set_gain(3), Err(Mcp3422Error::InvalidArgument)));
        chip.set_resolution(18).unwrap();
        chip.set_gain(8).unwrap();
        assert_eq!((chip.get_resolution(), chip.get_gain()), (18, 8));
        i2c.done();
    }

    #[test]
    fn continuous_mode() {
        let (mut i2c, mut chip) = full(vec![
            w(0x10), f12(5, false), f12(300, false), f12(301, false),
            w(0x30), w(0x20),
            w(0x10), f12(1, false), f12(2, false), w(0x11), f12(3, false), f12(4, false),
        ]);
        let mut d = NoopDelay::new();
        chip.set_resolution(12).unwrap();
        chip.start_continuous(None).unwrap();
        assert!(chip.is_continuous());
        assert_eq!(chip.read_continuous_raw(&mut d).unwrap(), 300);
        assert_eq!(chip.read_continuous_raw(&mut d).unwrap(), 301);
        chip.start_continuous(Some(1)).unwrap();
        chip.stop_continuous().unwrap();
        assert!(!chip.is_continuous());
        assert!(matches!(chip.read_continuous_raw(&mut d), Err(Mcp3422Error::NotContinuous)));
        chip.start_continuous(Some(0)).unwrap();
        assert_eq!(chip.read_continuous_raw(&mut d).unwrap(), 2);
        chip.set_gain(2).unwrap();
        assert_eq!(chip.read_continuous_raw(&mut d).unwrap(), 4);
        i2c.done();
    }

    #[test]
    fn config_ready_and_general_calls() {
        let (mut i2c, mut chip) = full(vec![
            f16(0, true), f16(0, false),
            T::write(ADDR, vec![0x00, 0x06]),
            T::write(ADDR, vec![0x00, 0x08]), f12(321, false),
        ]);
        let mut d = NoopDelay::new();
        assert_eq!(chip.read_config().unwrap(), 0x80);
        assert!(chip.is_ready().unwrap());
        chip.set_resolution(18).unwrap();
        chip.general_call_reset().unwrap();
        assert_eq!((chip.get_resolution(), chip.get_gain(), chip.is_continuous()), (12, 1, true));
        chip.general_call_convert().unwrap();
        assert_eq!(chip.read_pending_raw(&mut d).unwrap(), 321);
        i2c.done();
    }
}
