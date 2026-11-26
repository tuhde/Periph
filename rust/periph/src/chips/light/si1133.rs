//! Si1133 — UV index and ambient light sensor (Silicon Labs).
//!
//! Communicates over I²C at `0x55` (`AD` pulled up, default) or `0x52` (`AD` pulled down). Up to
//! six measurement channels each select a photodiode, integration time, accumulation and output
//! width; results are packed into `HOSTOUT0..25` in channel order. All configuration goes through
//! an indirect parameter table (`PARAM_SET` with the value in `HOSTIN0`, `PARAM_QUERY` into
//! `RESPONSE1`); every command is confirmed by the counter in `RESPONSE0`.
//!
//! Construction does no bus access. `init()` waits 25 ms, checks `PART_ID` (`0x33`), sends
//! `RESET_SW` and configures channel 0 = UV (`ADCMUX 0x18`, `HW_GAIN` 9, 24-bit output),
//! channel 1 = white (`ADCMUX 0x0B`, `HW_GAIN` 7, 24-bit), `CHAN_LIST = 0x03` and
//! `IRQ_ENABLE = 0x3F` (so `IRQ_STATUS` flags finished channels). Results are raw ADC counts —
//! the datasheet gives no lux or UV-index formula.
//!
//! `INT` is active low, open-drain, must be high at power-up and is released when `IRQ_STATUS`
//! is read. The Rust driver exposes [`Si1133Full::poll_interrupt`] (no callback subscription):
//! the caller wires the pin into an ISR or polling loop and calls `poll_interrupt`.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register::{read_register, write_register};

const REG_PART_ID: u8 = 0x00;
const REG_HOSTIN0: u8 = 0x0A;
const REG_COMMAND: u8 = 0x0B;
const REG_IRQ_ENABLE: u8 = 0x0F;
const REG_RESPONSE1: u8 = 0x10;
const REG_RESPONSE0: u8 = 0x11;
const REG_IRQ_STATUS: u8 = 0x12;
const REG_HOSTOUT0: u8 = 0x13;

const PART_ID: u8 = 0x33;

const CMD_RESET_CMD_CTR: u8 = 0x00;
const CMD_RESET_SW: u8 = 0x01;
const CMD_FORCE: u8 = 0x11;
const CMD_PAUSE: u8 = 0x12;
const CMD_START: u8 = 0x13;
const CMD_PARAM_QUERY: u8 = 0x40;
const CMD_PARAM_SET: u8 = 0x80;

const PARAM_I2C_ADDR: u8 = 0x00;
const PARAM_CHAN_LIST: u8 = 0x01;
const PARAM_ADCCONFIG0: u8 = 0x02;
const PARAM_ADCSENS0: u8 = 0x03;
const PARAM_ADCPOST0: u8 = 0x04;
const PARAM_MEASCONFIG0: u8 = 0x05;
const PARAM_MEASRATE_H: u8 = 0x1A;
const PARAM_MEASRATE_L: u8 = 0x1B;
const PARAM_MEASCOUNT0: u8 = 0x1C;
const PARAM_THRESHOLD0_H: u8 = 0x25;
const PARAM_BURST: u8 = 0x2B;

/// Command handshake polls (1 ms each).
const CMD_POLLS: u32 = 50;
/// Measurement polls (1 ms each); covers `HW_GAIN` 11 with `SW_GAIN` 128.
const MEASURE_POLLS: u32 = 15000;
const SCRATCH: u8 = 5;

/// `RESPONSE0`: sequencer running.
pub const RESP_RUNNING: u8 = 0x80;
/// `RESPONSE0`: suspended.
pub const RESP_SUSPEND: u8 = 0x40;
/// `RESPONSE0`: sleeping (idle).
pub const RESP_SLEEP: u8 = 0x20;
/// `RESPONSE0`: command error.
pub const RESP_CMD_ERR: u8 = 0x10;

/// `ADCMUX`: small IR photodiode.
pub const MUX_SMALL_IR: u8 = 0x00;
/// `ADCMUX`: medium IR photodiode.
pub const MUX_MEDIUM_IR: u8 = 0x01;
/// `ADCMUX`: large IR photodiode.
pub const MUX_LARGE_IR: u8 = 0x02;
/// `ADCMUX`: white photodiode.
pub const MUX_WHITE: u8 = 0x0B;
/// `ADCMUX`: large white photodiode.
pub const MUX_LARGE_WHITE: u8 = 0x0D;
/// `ADCMUX`: UV photodiode.
pub const MUX_UV: u8 = 0x18;
/// `ADCMUX`: deep UV photodiode.
pub const MUX_UV_DEEP: u8 = 0x19;

/// Interrupt source: channel 0 result ready.
pub const SOURCE_CHANNEL0: u8 = 0x01;
/// Interrupt source: channel 1 result ready.
pub const SOURCE_CHANNEL1: u8 = 0x02;
/// Interrupt source: channel 2 result ready.
pub const SOURCE_CHANNEL2: u8 = 0x04;
/// Interrupt source: channel 3 result ready.
pub const SOURCE_CHANNEL3: u8 = 0x08;
/// Interrupt source: channel 4 result ready.
pub const SOURCE_CHANNEL4: u8 = 0x10;
/// Interrupt source: channel 5 result ready.
pub const SOURCE_CHANNEL5: u8 = 0x20;

/// Driver error.
#[derive(Debug, PartialEq, Eq)]
pub enum Error<E> {
    /// Underlying I²C bus error.
    Bus(E),
    /// `PART_ID` did not return `0x33`.
    InvalidChip(u8),
    /// The chip set `CMD_ERR`; carries `RESPONSE0 & 0x1F` (`0x10` invalid command, `0x11` invalid
    /// parameter address, `0x12` ADC overflow, `0x13` output buffer overflow).
    Command(u8),
    /// A command, reset or measurement did not complete in time.
    Timeout,
    /// Channel 0 or 1 is disabled, so `read()` has no UV/white result.
    ChannelsDisabled,
    /// An argument is outside the supported range.
    InvalidArgument,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// UV and white-light counts of one forced measurement.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub struct Reading {
    /// Signed 24-bit raw count of channel 0 (UV).
    pub uv: i32,
    /// Signed 24-bit raw count of channel 1 (white).
    pub white: i32,
}

/// Decoded `RESPONSE0`.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub struct Response {
    /// State bits ([`RESP_RUNNING`], [`RESP_SUSPEND`], [`RESP_SLEEP`]).
    pub state: u8,
    /// `CMD_ERR` flag.
    pub error: bool,
    /// Command counter (the error code when `error` is set).
    pub counter: u8,
}

/// Results of the enabled channels in channel order.
#[derive(Debug, PartialEq, Eq, Clone, Copy, Default)]
pub struct Channels {
    /// Values (24-bit signed or 16-bit unsigned per channel); only `count` entries are valid.
    pub values: [i32; 6],
    /// Number of enabled channels.
    pub count: usize,
}

impl Channels {
    /// The valid values as a slice.
    pub fn as_slice(&self) -> &[i32] {
        &self.values[..self.count]
    }
}

/// Si1133 minimal driver — one call returns the UV and white counts.
pub struct Si1133Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    chan_list: u8,
    bits24: u8,
    irq_enable: u8,
}

impl<I2C: I2c> Si1133Minimal<I2C> {
    /// Create a new `Si1133Minimal`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x55` or `0x52`).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, chan_list: 0, bits24: 0, irq_enable: 0 }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    /// Verify the chip, reset it and configure the UV and white channels.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        delay.delay_ms(25);
        let part = self.read_reg(REG_PART_ID)?;
        if part != PART_ID {
            return Err(Error::InvalidChip(part));
        }
        self.software_reset(delay)?;
        self.apply_defaults(delay)
    }

    /// Run one forced measurement and return the UV and white counts of channels 0 and 1.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<Reading, Error<I2C::Error>> {
        if self.chan_list & 0x03 != 0x03 {
            return Err(Error::ChannelsDisabled);
        }
        let c = self.measure(delay)?;
        Ok(Reading { uv: c.values[0], white: c.values[1] })
    }

    fn read_reg(&mut self, reg: u8) -> Result<u8, Error<I2C::Error>> {
        let mut b = [0u8; 1];
        read_register(&mut self.i2c, self.addr, reg as u32, 1, &mut b)?;
        Ok(b[0])
    }

    fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), Error<I2C::Error>> {
        write_register(&mut self.i2c, self.addr, reg as u32, 1, &[value])?;
        Ok(())
    }

    fn software_reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.write_reg(REG_COMMAND, CMD_RESET_SW)?;
        delay.delay_ms(10);
        self.chan_list = 0;
        self.bits24 = 0;
        self.irq_enable = 0;
        for _ in 0..CMD_POLLS {
            if self.read_reg(REG_RESPONSE0)? & RESP_SLEEP != 0 {
                return Ok(());
            }
            delay.delay_ms(1);
        }
        Err(Error::Timeout)
    }

    fn apply_defaults<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.set_channel(delay, 0, MUX_UV, 9, 0, 0, false, true, 0)?;
        self.set_channel(delay, 1, MUX_WHITE, 7, 0, 0, false, true, 0)?;
        self.param_set(delay, PARAM_CHAN_LIST, 0x03)?;
        self.chan_list = 0x03;
        self.irq_enable = 0x3F;
        self.write_reg(REG_IRQ_ENABLE, 0x3F)
    }

    fn counter(&mut self) -> Result<u8, Error<I2C::Error>> {
        let mut r = self.read_reg(REG_RESPONSE0)?;
        if r & RESP_CMD_ERR != 0 {
            self.write_reg(REG_COMMAND, CMD_RESET_CMD_CTR)?;
            r = self.read_reg(REG_RESPONSE0)?;
        }
        Ok(r & 0x0F)
    }

    fn wait_counter<D: DelayNs>(&mut self, delay: &mut D, before: u8) -> Result<(), Error<I2C::Error>> {
        for _ in 0..CMD_POLLS {
            let r = self.read_reg(REG_RESPONSE0)?;
            if r & RESP_CMD_ERR != 0 {
                self.write_reg(REG_COMMAND, CMD_RESET_CMD_CTR)?;
                return Err(Error::Command(r & 0x1F));
            }
            if r & 0x0F != before {
                return Ok(());
            }
            delay.delay_ms(1);
        }
        Err(Error::Timeout)
    }

    fn command<D: DelayNs>(&mut self, delay: &mut D, cmd: u8) -> Result<(), Error<I2C::Error>> {
        let before = self.counter()?;
        self.write_reg(REG_COMMAND, cmd)?;
        self.wait_counter(delay, before)
    }

    fn param_set<D: DelayNs>(&mut self, delay: &mut D, address: u8, value: u8) -> Result<(), Error<I2C::Error>> {
        let before = self.counter()?;
        write_register(&mut self.i2c, self.addr, REG_HOSTIN0 as u32, 1, &[value, CMD_PARAM_SET | (address & 0x3F)])?;
        self.wait_counter(delay, before)
    }

    fn param_query<D: DelayNs>(&mut self, delay: &mut D, address: u8) -> Result<u8, Error<I2C::Error>> {
        self.command(delay, CMD_PARAM_QUERY | (address & 0x3F))?;
        self.read_reg(REG_RESPONSE1)
    }

    #[allow(clippy::too_many_arguments)]
    fn set_channel<D: DelayNs>(
        &mut self, delay: &mut D, channel: u8, adcmux: u8, hw_gain: u8, sw_gain: u8, decim_rate: u8,
        high_signal: bool, bits24: bool, postshift: u8,
    ) -> Result<(), Error<I2C::Error>> {
        let base = 4 * channel;
        self.param_set(delay, PARAM_ADCCONFIG0 + base, (decim_rate << 5) | adcmux)?;
        self.param_set(delay, PARAM_ADCSENS0 + base, if high_signal { 0x80 } else { 0 } | (sw_gain << 4) | hw_gain)?;
        self.param_set(delay, PARAM_ADCPOST0 + base, if bits24 { 0x40 } else { 0 } | (postshift << 3))?;
        if bits24 {
            self.bits24 |= 1 << channel;
        } else {
            self.bits24 &= !(1 << channel);
        }
        Ok(())
    }

    fn read_outputs(&mut self) -> Result<Channels, Error<I2C::Error>> {
        let mut n = 0usize;
        for ch in 0..6 {
            if self.chan_list & (1 << ch) != 0 {
                n += if self.bits24 & (1 << ch) != 0 { 3 } else { 2 };
            }
        }
        let mut out = Channels::default();
        if n == 0 {
            return Ok(out);
        }
        let mut d = [0u8; 18];
        read_register(&mut self.i2c, self.addr, REG_HOSTOUT0 as u32, 1, &mut d[..n])?;
        let mut pos = 0;
        for ch in 0..6 {
            if self.chan_list & (1 << ch) == 0 {
                continue;
            }
            let v = if self.bits24 & (1 << ch) != 0 {
                let u = ((d[pos] as u32) << 16) | ((d[pos + 1] as u32) << 8) | d[pos + 2] as u32;
                pos += 3;
                ((u << 8) as i32) >> 8
            } else {
                let u = ((d[pos] as i32) << 8) | d[pos + 1] as i32;
                pos += 2;
                u
            };
            out.values[out.count] = v;
            out.count += 1;
        }
        Ok(out)
    }

    fn measure<D: DelayNs>(&mut self, delay: &mut D) -> Result<Channels, Error<I2C::Error>> {
        self.read_reg(REG_IRQ_STATUS)?; // clear stale flags
        self.command(delay, CMD_FORCE)?;
        let wait = self.chan_list & self.irq_enable;
        if wait == 0 {
            delay.delay_ms(100);
        } else {
            let mut seen = 0u8;
            let mut i = 0;
            loop {
                seen |= self.read_reg(REG_IRQ_STATUS)?;
                if seen & wait == wait {
                    break;
                }
                i += 1;
                if i >= MEASURE_POLLS {
                    return Err(Error::Timeout);
                }
                delay.delay_ms(1);
            }
        }
        self.read_outputs()
    }
}

/// Si1133 full driver — per-channel configuration, single-photodiode reads, autonomous and burst
/// modes, thresholds, direct parameter access, the I²C address and the interrupt configuration.
///
/// Owns a [`Si1133Minimal`] and delegates `init` and `read`.
pub struct Si1133Full<I2C> {
    inner: Si1133Minimal<I2C>,
    counter_index: [u8; 6],
}

impl<I2C: I2c> Si1133Full<I2C> {
    /// Create a new `Si1133Full`; the constructor does no bus access.
    ///
    /// # Arguments
    /// * `i2c`  — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit device address (`0x55` or `0x52`).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Si1133Minimal::new(i2c, addr), counter_index: [0; 6] }
    }

    /// Release the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Verify the chip, reset it and configure the UV and white channels.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.counter_index = [0; 6];
        self.inner.init(delay)
    }

    /// Run one forced measurement and return the UV and white counts of channels 0 and 1.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<Reading, Error<I2C::Error>> {
        self.inner.read(delay)
    }

    /// Read `PART_ID`, `HW_ID` and `REV_ID`.
    pub fn read_id(&mut self) -> Result<(u8, u8, u8), Error<I2C::Error>> {
        let mut b = [0u8; 3];
        read_register(&mut self.inner.i2c, self.inner.addr, REG_PART_ID as u32, 1, &mut b)?;
        Ok((b[0], b[1], b[2]))
    }

    /// Configure one channel (`ADCCONFIG`, `ADCSENS`, `ADCPOST`); clears its threshold select.
    ///
    /// # Arguments
    /// * `channel` — 0–5. * `adcmux` — photodiode (`MUX_*`). * `hw_gain` — 0–11 (48.8 µs × 2^n).
    /// * `sw_gain` — accumulate 2^n samples, 0–7. * `decim_rate` — 0–3.
    /// * `high_signal` — sensitivity ÷ 14.5. * `bits24` — 24-bit signed (else 16-bit unsigned).
    /// * `postshift` — right shift 0–7.
    #[allow(clippy::too_many_arguments)]
    pub fn configure_channel<D: DelayNs>(
        &mut self, delay: &mut D, channel: u8, adcmux: u8, hw_gain: u8, sw_gain: u8, decim_rate: u8,
        high_signal: bool, bits24: bool, postshift: u8,
    ) -> Result<(), Error<I2C::Error>> {
        if channel > 5 || adcmux > 0x1F || hw_gain > 11 || sw_gain > 7 || decim_rate > 3 || postshift > 7 {
            return Err(Error::InvalidArgument);
        }
        self.inner.set_channel(delay, channel, adcmux, hw_gain, sw_gain, decim_rate, high_signal, bits24, postshift)
    }

    /// Select the channels that run (`CHAN_LIST`, bit c = channel c, 0x00–0x3F).
    pub fn enable_channels<D: DelayNs>(&mut self, delay: &mut D, mask: u8) -> Result<(), Error<I2C::Error>> {
        if mask > 0x3F {
            return Err(Error::InvalidArgument);
        }
        self.inner.param_set(delay, PARAM_CHAN_LIST, mask)?;
        self.inner.chan_list = mask;
        Ok(())
    }

    /// Read the results of all enabled channels in channel order; with `force` a forced
    /// measurement runs first, otherwise only `HOSTOUT` is decoded (autonomous mode).
    pub fn read_channels<D: DelayNs>(&mut self, delay: &mut D, force: bool) -> Result<Channels, Error<I2C::Error>> {
        if force { self.inner.measure(delay) } else { self.inner.read_outputs() }
    }

    fn scratch_read<D: DelayNs>(&mut self, delay: &mut D, adcmux: u8, hw_gain: u8) -> Result<i32, Error<I2C::Error>> {
        let saved = self.inner.chan_list;
        self.inner.set_channel(delay, SCRATCH, adcmux, hw_gain, 0, 0, false, true, 0)?;
        if self.counter_index[SCRATCH as usize] != 0 {
            self.inner.param_set(delay, PARAM_MEASCONFIG0 + 4 * SCRATCH, 0)?;
            self.counter_index[SCRATCH as usize] = 0;
        }
        self.inner.param_set(delay, PARAM_CHAN_LIST, 1 << SCRATCH)?;
        self.inner.chan_list = 1 << SCRATCH;
        let result = self.inner.measure(delay);
        let restored = self.inner.param_set(delay, PARAM_CHAN_LIST, saved);
        self.inner.chan_list = saved;
        let c = result?;
        restored?;
        Ok(c.values[0])
    }

    /// Measure the UV photodiode once (channel 5 used as scratch, `HW_GAIN` 9).
    pub fn read_uv<D: DelayNs>(&mut self, delay: &mut D) -> Result<i32, Error<I2C::Error>> {
        self.scratch_read(delay, MUX_UV, 9)
    }

    /// Measure the white photodiode once (channel 5 used as scratch, `HW_GAIN` 7).
    pub fn read_white<D: DelayNs>(&mut self, delay: &mut D) -> Result<i32, Error<I2C::Error>> {
        self.scratch_read(delay, MUX_WHITE, 7)
    }

    /// Measure an IR photodiode once ([`MUX_SMALL_IR`], [`MUX_MEDIUM_IR`] or [`MUX_LARGE_IR`];
    /// channel 5 used as scratch, `HW_GAIN` 7).
    pub fn read_ir<D: DelayNs>(&mut self, delay: &mut D, photodiode: u8) -> Result<i32, Error<I2C::Error>> {
        if photodiode > MUX_LARGE_IR {
            return Err(Error::InvalidArgument);
        }
        self.scratch_read(delay, photodiode, 7)
    }

    /// Send `FORCE` without waiting for the results.
    pub fn force<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.command(delay, CMD_FORCE)
    }

    /// Assign a rate counter to a channel (`MEASCONFIG.COUNTER_INDEX`: 0 = none, 1–3 =
    /// `MEASCOUNT0..2`).
    pub fn set_channel_counter<D: DelayNs>(&mut self, delay: &mut D, channel: u8, index: u8) -> Result<(), Error<I2C::Error>> {
        if channel > 5 || index > 3 {
            return Err(Error::InvalidArgument);
        }
        self.inner.param_set(delay, PARAM_MEASCONFIG0 + 4 * channel, index << 6)?;
        self.counter_index[channel as usize] = index;
        Ok(())
    }

    /// Start autonomous measurements: `MEASRATE` (800 µs units), `MEASCOUNT0..2` (1–255 each),
    /// counter 1 for every enabled channel without one, then `START`.
    ///
    /// # Arguments
    /// * `rate_s` — main period in seconds (800 µs … 52.4 s); channel period = rate × MEASCOUNTk.
    /// * `counters` — `MEASCOUNT0..2`.
    pub fn start_autonomous<D: DelayNs>(&mut self, delay: &mut D, rate_s: f32, counters: [u8; 3]) -> Result<(), Error<I2C::Error>> {
        let t = rate_s / 800e-6 + 0.5;
        if !(1.0..65536.0).contains(&t) || counters.contains(&0) {
            return Err(Error::InvalidArgument);
        }
        let ticks = t as u16;
        self.inner.param_set(delay, PARAM_MEASRATE_H, (ticks >> 8) as u8)?;
        self.inner.param_set(delay, PARAM_MEASRATE_L, (ticks & 0xFF) as u8)?;
        for (i, c) in counters.iter().enumerate() {
            self.inner.param_set(delay, PARAM_MEASCOUNT0 + i as u8, *c)?;
        }
        for ch in 0..6u8 {
            if self.inner.chan_list & (1 << ch) != 0 && self.counter_index[ch as usize] == 0 {
                self.set_channel_counter(delay, ch, 1)?;
            }
        }
        self.inner.command(delay, CMD_START)
    }

    /// Pause autonomous measurements (`PAUSE`).
    pub fn pause<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.command(delay, CMD_PAUSE)
    }

    /// Configure burst mode (`BURST`): 1–127 readings, 0 disables.
    pub fn set_burst<D: DelayNs>(&mut self, delay: &mut D, count: u8) -> Result<(), Error<I2C::Error>> {
        if count > 127 {
            return Err(Error::InvalidArgument);
        }
        self.inner.param_set(delay, PARAM_BURST, if count != 0 { 0x80 | count } else { 0 })
    }

    /// Set one of the three 16-bit thresholds (`index` 0–2).
    pub fn set_threshold<D: DelayNs>(&mut self, delay: &mut D, index: u8, value: u16) -> Result<(), Error<I2C::Error>> {
        if index > 2 {
            return Err(Error::InvalidArgument);
        }
        let a = PARAM_THRESHOLD0_H + 2 * index;
        self.inner.param_set(delay, a, (value >> 8) as u8)?;
        self.inner.param_set(delay, a + 1, (value & 0xFF) as u8)
    }

    /// Select the threshold a channel's interrupt compares against (`ADCPOST.THRESH_SEL`:
    /// 0 = off, 1–3 = `THRESHOLD0..2`).
    pub fn set_channel_threshold<D: DelayNs>(&mut self, delay: &mut D, channel: u8, index: u8) -> Result<(), Error<I2C::Error>> {
        if channel > 5 || index > 3 {
            return Err(Error::InvalidArgument);
        }
        let a = PARAM_ADCPOST0 + 4 * channel;
        let v = self.inner.param_query(delay, a)?;
        self.inner.param_set(delay, a, (v & 0xFC) | index)
    }

    /// Read a parameter-table byte (`PARAM_QUERY`, address 0x00–0x3F).
    pub fn read_parameter<D: DelayNs>(&mut self, delay: &mut D, address: u8) -> Result<u8, Error<I2C::Error>> {
        if address > 0x3F {
            return Err(Error::InvalidArgument);
        }
        self.inner.param_query(delay, address)
    }

    /// Write a parameter-table byte (`PARAM_SET`, address 0x00–0x3F). Writing channel parameters
    /// directly bypasses the driver's record of the channel layout; prefer `configure_channel`.
    pub fn write_parameter<D: DelayNs>(&mut self, delay: &mut D, address: u8, value: u8) -> Result<(), Error<I2C::Error>> {
        if address > 0x3F {
            return Err(Error::InvalidArgument);
        }
        self.inner.param_set(delay, address, value)
    }

    /// Decode `RESPONSE0`.
    pub fn read_response(&mut self) -> Result<Response, Error<I2C::Error>> {
        let r = self.inner.read_reg(REG_RESPONSE0)?;
        Ok(Response { state: r & 0xE0, error: r & RESP_CMD_ERR != 0, counter: r & 0x0F })
    }

    /// Read `IRQ_STATUS` (clears it): bit c set when channel c has a new result.
    pub fn data_ready(&mut self) -> Result<u8, Error<I2C::Error>> {
        Ok(self.inner.read_reg(REG_IRQ_STATUS)? & 0x3F)
    }

    /// Change the I²C address (volatile, 0x08–0x77). The chip answers on the new address at
    /// once, so no completion check is made; create a new driver with the new address.
    pub fn set_i2c_address(&mut self, address: u8) -> Result<(), Error<I2C::Error>> {
        if !(0x08..=0x77).contains(&address) {
            return Err(Error::InvalidArgument);
        }
        self.inner.read_reg(REG_RESPONSE0)?;
        write_register(&mut self.inner.i2c, self.inner.addr, REG_HOSTIN0 as u32, 1, &[address, CMD_PARAM_SET | PARAM_I2C_ADDR])?;
        Ok(())
    }

    /// Software reset (`RESET_SW`), wait for `SLEEP`, re-apply the Minimal defaults.
    pub fn reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Error<I2C::Error>> {
        self.inner.software_reset(delay)?;
        self.counter_index = [0; 6];
        self.inner.apply_defaults(delay)
    }

    /// Enable `INT` for channels (`IRQ_ENABLE`, `SOURCE_CHANNEL*` mask).
    pub fn enable_interrupt(&mut self, source: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.irq_enable = (self.inner.irq_enable | source) & 0x3F;
        self.inner.write_reg(REG_IRQ_ENABLE, self.inner.irq_enable)
    }

    /// Disable `INT` for channels. Forced reads only wait for channels whose interrupt is enabled
    /// (none: a fixed 100 ms wait).
    pub fn disable_interrupt(&mut self, source: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.irq_enable &= !source & 0x3F;
        self.inner.write_reg(REG_IRQ_ENABLE, self.inner.irq_enable)
    }

    /// Read `IRQ_STATUS` (clears it, releases `INT`); returns the channel mask, 0 = none.
    pub fn poll_interrupt(&mut self) -> Result<u8, Error<I2C::Error>> {
        self.data_ready()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const A: u8 = 0x55;

    fn w(reg: u8, data: &[u8]) -> T {
        let mut v = vec![reg];
        v.extend_from_slice(data);
        T::write(A, v)
    }
    fn wr(reg: u8, data: &[u8]) -> T {
        T::write_read(A, vec![reg], data.to_vec())
    }

    /// Builds the expected transaction list, tracking the RESPONSE0 counter.
    struct Seq {
        t: Vec<T>,
        ctr: u8,
    }
    impl Seq {
        fn new() -> Self {
            Seq { t: Vec::new(), ctr: 0x0F }
        }
        fn next(&mut self) -> u8 {
            self.ctr = (self.ctr + 1) & 0x0F;
            0x20 | self.ctr
        }
        fn pset(&mut self, addr: u8, val: u8) -> &mut Self {
            let before = 0x20 | self.ctr;
            let after = self.next();
            self.t.push(wr(0x11, &[before]));
            self.t.push(w(0x0A, &[val, 0x80 | addr]));
            self.t.push(wr(0x11, &[after]));
            self
        }
        fn cmd(&mut self, c: u8) -> &mut Self {
            let before = 0x20 | self.ctr;
            let after = self.next();
            self.t.push(wr(0x11, &[before]));
            self.t.push(w(0x0B, &[c]));
            self.t.push(wr(0x11, &[after]));
            self
        }
        fn query(&mut self, addr: u8, val: u8) -> &mut Self {
            self.cmd(0x40 | addr);
            self.t.push(wr(0x10, &[val]));
            self
        }
        fn reset(&mut self) -> &mut Self {
            self.t.push(w(0x0B, &[0x01]));
            self.ctr = 0x0F;
            self.t.push(wr(0x11, &[0x2F]));
            self
        }
        fn defaults(&mut self) -> &mut Self {
            self.pset(0x02, 0x18).pset(0x03, 0x09).pset(0x04, 0x40);
            self.pset(0x06, 0x0B).pset(0x07, 0x07).pset(0x08, 0x40);
            self.pset(0x01, 0x03);
            self.t.push(w(0x0F, &[0x3F]));
            self
        }
        fn init(&mut self) -> &mut Self {
            self.t.push(wr(0x00, &[0x33]));
            self.reset().defaults()
        }
        fn force(&mut self, irq: u8, out: &[u8]) -> &mut Self {
            self.t.push(wr(0x12, &[0x00]));
            self.cmd(0x11);
            self.t.push(wr(0x12, &[irq]));
            self.t.push(wr(0x13, out));
            self
        }
        fn push(&mut self, t: T) -> &mut Self {
            self.t.push(t);
            self
        }
    }

    const OUT2: [u8; 6] = [0x01, 0x23, 0x45, 0xFF, 0xFF, 0xFB];

    #[test]
    fn init_and_read() {
        let mut s = Seq::new();
        s.init().force(0x03, &OUT2);
        let mut i2c = I2cMock::new(&s.t);
        let mut d = NoopDelay::new();
        let mut m = Si1133Minimal::new(i2c.clone(), A);
        m.init(&mut d).unwrap();
        assert_eq!(m.read(&mut d).unwrap(), Reading { uv: 0x012345, white: -5 });
        i2c.done();
    }

    #[test]
    fn init_rejects_wrong_part() {
        let mut i2c = I2cMock::new(&[wr(0x00, &[0x32])]);
        let mut d = NoopDelay::new();
        let mut m = Si1133Minimal::new(i2c.clone(), A);
        assert_eq!(m.init(&mut d), Err(Error::InvalidChip(0x32)));
        i2c.done();
    }

    #[test]
    fn command_error_is_reported_and_cleared() {
        let mut s = Seq::new();
        s.init();
        let before = 0x20 | s.ctr;
        s.push(wr(0x11, &[before])).push(w(0x0A, &[1, 0xB0])).push(wr(0x11, &[0x31])).push(w(0x0B, &[0x00]));
        let mut i2c = I2cMock::new(&s.t);
        let mut d = NoopDelay::new();
        let mut f = Si1133Full::new(i2c.clone(), A);
        f.init(&mut d).unwrap();
        assert_eq!(f.write_parameter(&mut d, 0x30, 1), Err(Error::Command(0x11)));
        i2c.done();
    }

    #[test]
    fn channels_and_scratch_reads() {
        let mut s = Seq::new();
        s.init();
        s.pset(0x0A, 0x22).pset(0x0B, 0xA3).pset(0x0C, 0x08); // configure channel 2
        s.pset(0x01, 0x07);
        s.force(0x07, &[0x01, 0x23, 0x45, 0xFF, 0xFF, 0xFB, 0xAB, 0xCD]);
        s.push(wr(0x13, &[0x01, 0x23, 0x45, 0xFF, 0xFF, 0xFB, 0xAB, 0xCD]));
        // read_uv on scratch channel 5
        s.pset(0x16, 0x18).pset(0x17, 0x09).pset(0x18, 0x40).pset(0x01, 0x20);
        s.force(0x20, &[0x00, 0x04, 0xD2]);
        s.pset(0x01, 0x07);
        let mut i2c = I2cMock::new(&s.t);
        let mut d = NoopDelay::new();
        let mut f = Si1133Full::new(i2c.clone(), A);
        f.init(&mut d).unwrap();
        f.configure_channel(&mut d, 2, MUX_LARGE_IR, 3, 2, 1, true, false, 1).unwrap();
        f.enable_channels(&mut d, 0x07).unwrap();
        assert_eq!(f.read_channels(&mut d, true).unwrap().as_slice(), &[0x012345, -5, 0xABCD]);
        assert_eq!(f.read_channels(&mut d, false).unwrap().as_slice(), &[0x012345, -5, 0xABCD]);
        assert_eq!(f.read_uv(&mut d).unwrap(), 1234);
        assert_eq!(f.configure_channel(&mut d, 6, 0, 0, 0, 0, false, true, 0), Err(Error::InvalidArgument));
        assert_eq!(f.read_ir(&mut d, 0x0B), Err(Error::InvalidArgument));
        i2c.done();
    }

    #[test]
    fn autonomous_burst_thresholds_and_parameters() {
        let mut s = Seq::new();
        s.init();
        s.pset(0x1A, 0x04).pset(0x1B, 0xE2).pset(0x1C, 2).pset(0x1D, 3).pset(0x1E, 4);
        s.pset(0x05, 0x40).pset(0x09, 0x40).cmd(0x13);
        s.pset(0x0D, 0xC0).cmd(0x12);
        s.pset(0x2B, 0x84).pset(0x2B, 0x00);
        s.pset(0x27, 0x12).pset(0x28, 0x34);
        s.query(0x04, 0x40).pset(0x04, 0x42);
        s.pset(0x1C, 0x55).query(0x1C, 0x55);
        let ctr = s.ctr;
        s.push(wr(0x11, &[0x20 | ctr]));
        s.push(wr(0x12, &[0x05]));
        s.push(w(0x0F, &[0x3D])).push(w(0x0F, &[0x3F]));
        s.push(wr(0x12, &[0x02]));
        s.push(wr(0x00, &[0x33, 0x03, 0x10]));
        s.push(wr(0x11, &[0x20 | ctr])).push(w(0x0A, &[0x30, 0x80]));
        s.reset().defaults();
        let mut i2c = I2cMock::new(&s.t);
        let mut d = NoopDelay::new();
        let mut f = Si1133Full::new(i2c.clone(), A);
        f.init(&mut d).unwrap();
        f.start_autonomous(&mut d, 1.0, [2, 3, 4]).unwrap();
        f.set_channel_counter(&mut d, 2, 3).unwrap();
        f.pause(&mut d).unwrap();
        assert_eq!(f.start_autonomous(&mut d, 0.0, [1, 1, 1]), Err(Error::InvalidArgument));
        f.set_burst(&mut d, 4).unwrap();
        f.set_burst(&mut d, 0).unwrap();
        assert_eq!(f.set_burst(&mut d, 128), Err(Error::InvalidArgument));
        f.set_threshold(&mut d, 1, 0x1234).unwrap();
        f.set_channel_threshold(&mut d, 0, 2).unwrap();
        f.write_parameter(&mut d, 0x1C, 0x55).unwrap();
        assert_eq!(f.read_parameter(&mut d, 0x1C).unwrap(), 0x55);
        assert_eq!(f.read_response().unwrap(), Response { state: 0x20, error: false, counter: ctr });
        assert_eq!(f.data_ready().unwrap(), 0x05);
        f.disable_interrupt(SOURCE_CHANNEL1).unwrap();
        f.enable_interrupt(SOURCE_CHANNEL1).unwrap();
        assert_eq!(f.poll_interrupt().unwrap(), 0x02);
        assert_eq!(f.read_id().unwrap(), (0x33, 0x03, 0x10));
        f.set_i2c_address(0x30).unwrap();
        assert_eq!(f.set_i2c_address(0x80), Err(Error::InvalidArgument));
        f.reset(&mut d).unwrap();
        i2c.done();
    }

    #[test]
    fn read_needs_channels_0_and_1() {
        let mut s = Seq::new();
        s.init().pset(0x01, 0x04);
        let mut i2c = I2cMock::new(&s.t);
        let mut d = NoopDelay::new();
        let mut f = Si1133Full::new(i2c.clone(), A);
        f.init(&mut d).unwrap();
        f.enable_channels(&mut d, 0x04).unwrap();
        assert_eq!(f.read(&mut d), Err(Error::ChannelsDisabled));
        i2c.done();
    }

    #[test]
    fn command_timeout() {
        let mut s = Seq::new();
        s.init();
        let c = 0x20 | s.ctr;
        s.push(wr(0x12, &[0])).push(wr(0x11, &[c])).push(w(0x0B, &[0x11]));
        for _ in 0..CMD_POLLS {
            s.push(wr(0x11, &[c]));
        }
        let mut i2c = I2cMock::new(&s.t);
        let mut d = NoopDelay::new();
        let mut m = Si1133Minimal::new(i2c.clone(), A);
        m.init(&mut d).unwrap();
        assert_eq!(m.read(&mut d), Err(Error::Timeout));
        i2c.done();
    }
}
