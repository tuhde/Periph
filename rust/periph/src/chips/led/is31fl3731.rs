//! IS31FL3731 — 144-LED matrix driver, CharliePlex (Lumissil / ISSI).
//!
//! Communicates over I²C (address `0x74`–`0x77`, selected by the AD pin). The
//! 9 × 16 matrix has an 8-bit PWM value per LED, eight frames kept in on-chip
//! SRAM, Auto Frame Play, a breath function and audio modulation. The register
//! file is paged: the command register `0xFD` selects frame page 0–7 or the
//! function page `0x0B`; the driver caches the selected page.
//!
//! The frame registers can only be read in software shutdown, so the driver
//! keeps a shadow copy of every frame (PWM, LED-control and blink bytes).
//!
//! ## Constants
//!
//! [`ROWS`], [`COLS`], [`ADDR_DEFAULT`], [`GAMMA_32`], [`GAMMA_64`]

use core::ops::{Deref, DerefMut};
use embedded_hal::i2c::I2c;

/// Default I²C address (AD = GND).
pub const ADDR_DEFAULT: u8 = 0x74;
/// Number of common lines (rows).
pub const ROWS: u8 = 9;
/// Number of sink lines (columns).
pub const COLS: u8 = 16;

/// 32-step gamma table for 256 PWM steps (datasheet Table 17).
pub const GAMMA_32: [u8; 32] = [
    0, 1, 2, 4, 6, 10, 13, 18, 22, 28, 33, 39, 46, 53, 61, 69,
    78, 86, 96, 106, 116, 126, 138, 149, 161, 173, 186, 199, 212, 226, 240, 255,
];
/// 64-step gamma table for 256 PWM steps (datasheet Table 18, recommended).
pub const GAMMA_64: [u8; 64] = [
    0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 18, 20, 22,
    24, 26, 29, 32, 35, 38, 41, 44, 47, 50, 53, 57, 61, 65, 69, 73,
    77, 81, 85, 89, 94, 99, 104, 109, 114, 119, 124, 129, 134, 140, 146, 152,
    158, 164, 170, 176, 182, 188, 195, 202, 209, 216, 223, 230, 237, 244, 251, 255,
];

const REG_COMMAND: u8 = 0xFD;
const PAGE_FUNCTION: u8 = 0x0B;
const REG_CTRL: u8 = 0x00;
const REG_BLINK: u8 = 0x12;
const REG_PWM: u8 = 0x24;

const FN_CONFIG: u8 = 0x00;
const FN_AUTOPLAY1: u8 = 0x02;
const FN_DISPLAY: u8 = 0x05;
const FN_AUDIO_SYNC: u8 = 0x06;
const FN_FRAME_STATE: u8 = 0x07;
const FN_BREATH1: u8 = 0x08;
const FN_SHUTDOWN: u8 = 0x0A;
const FN_AGC: u8 = 0x0B;
const FN_AUDIO_RATE: u8 = 0x0C;

/// IS31FL3731 minimal driver — light individual LEDs of the 9 × 16 matrix with PWM in Picture mode.
///
/// Defaults at construction: software shutdown while configuring, Picture mode
/// showing frame 1, frame 1 initialised (all LED-control bits on, PWM and blink
/// cleared), then normal operation. LED current is set by R_EXT (3.2 mA at 20 kΩ).
pub struct Is31fl3731Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    page: Option<u8>,
    frame: u8,
    ic: u8,
    be: u8,
    bpt: u8,
    pwm: [[u8; 144]; 8],
    ctrl: [[u8; 18]; 8],
    blink: [[u8; 18]; 8],
}

impl<I2C: I2c> Is31fl3731Minimal<I2C> {
    /// Create a new `Is31fl3731Minimal` and run the initialization sequence.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x74–0x77).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let mut s = Self {
            i2c, addr, page: None, frame: 0, ic: 0, be: 0, bpt: 0,
            pwm: [[0; 144]; 8], ctrl: [[0xFF; 18]; 8], blink: [[0; 18]; 8],
        };
        s.func_write(FN_SHUTDOWN, &[0x00])?;
        s.func_write(FN_CONFIG, &[0x00, 0x00])?;
        s.func_write(FN_DISPLAY, &[0x00])?;
        s.init_frame(0)?;
        s.func_write(FN_SHUTDOWN, &[0x01])?;
        Ok(s)
    }

    /// Release the I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    fn write_reg(&mut self, reg: u8, data: &[u8]) -> Result<(), I2C::Error> {
        let mut buf = [0u8; 145];
        buf[0] = reg;
        buf[1..=data.len()].copy_from_slice(data);
        self.i2c.write(self.addr, &buf[..=data.len()])
    }

    fn select(&mut self, page: u8) -> Result<(), I2C::Error> {
        if self.page != Some(page) {
            self.write_reg(REG_COMMAND, &[page])?;
            self.page = Some(page);
        }
        Ok(())
    }

    fn func_write(&mut self, reg: u8, data: &[u8]) -> Result<(), I2C::Error> {
        self.select(PAGE_FUNCTION)?;
        self.write_reg(reg, data)
    }

    fn func_read(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        self.select(PAGE_FUNCTION)?;
        let mut b = [0u8; 1];
        self.i2c.write_read(self.addr, &[reg], &mut b)?;
        Ok(b[0])
    }

    fn init_frame(&mut self, frame: u8) -> Result<(), I2C::Error> {
        let f = frame as usize;
        self.ctrl[f] = [0xFF; 18];
        self.blink[f] = [0; 18];
        self.pwm[f] = [0; 144];
        self.select(frame)?;
        let ctrl = self.ctrl[f];
        self.write_reg(REG_CTRL, &ctrl)?;
        self.write_reg(REG_BLINK, &[0u8; 18])?;
        self.write_reg(REG_PWM, &[0u8; 144])
    }

    /// Set the PWM brightness of one LED in the frame being drawn.
    ///
    /// `row` 0–8, `col` 0–15, `brightness` 0–255. Returns `Ok(false)` without
    /// bus access if `row` or `col` is out of range.
    pub fn set_pixel(&mut self, row: u8, col: u8, brightness: u8) -> Result<bool, I2C::Error> {
        if row > 8 || col > 15 { return Ok(false); }
        let idx = 16 * row as usize + col as usize;
        self.pwm[self.frame as usize][idx] = brightness;
        self.select(self.frame)?;
        self.write_reg(REG_PWM + idx as u8, &[brightness])?;
        Ok(true)
    }

    /// Set all 144 PWM values of the frame being drawn to 0 (one block write).
    pub fn clear(&mut self) -> Result<(), I2C::Error> {
        self.fill(0)
    }

    /// Set all 144 PWM values of the frame being drawn (one block write).
    pub fn fill(&mut self, brightness: u8) -> Result<(), I2C::Error> {
        self.pwm[self.frame as usize] = [brightness; 144];
        self.select(self.frame)?;
        self.write_reg(REG_PWM, &[brightness; 144])
    }
}

/// IS31FL3731 full driver — frames, auto-play, blink, breath, audio, shutdown and INTB.
///
/// All [`Is31fl3731Minimal`] methods are available through `Deref`.
pub struct Is31fl3731Full<I2C> {
    inner: Is31fl3731Minimal<I2C>,
}

impl<I2C> Deref for Is31fl3731Full<I2C> {
    type Target = Is31fl3731Minimal<I2C>;
    fn deref(&self) -> &Self::Target { &self.inner }
}

impl<I2C> DerefMut for Is31fl3731Full<I2C> {
    fn deref_mut(&mut self) -> &mut Self::Target { &mut self.inner }
}

impl<I2C: I2c> Is31fl3731Full<I2C> {
    /// Create a new `Is31fl3731Full`.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x74–0x77).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Ok(Self { inner: Is31fl3731Minimal::new(i2c, addr)? })
    }

    /// Release the I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Read the PWM value of one LED from the shadow copy of the frame being drawn (0 if out of range).
    pub fn get_pixel(&self, row: u8, col: u8) -> u8 {
        if row > 8 || col > 15 { return 0; }
        self.inner.pwm[self.inner.frame as usize][16 * row as usize + col as usize]
    }

    /// Write a whole frame of 144 PWM values, row-major (one block write).
    ///
    /// Returns `Ok(false)` without bus access if `data.len() != 144`.
    pub fn set_pixels(&mut self, data: &[u8]) -> Result<bool, I2C::Error> {
        if data.len() != 144 { return Ok(false); }
        let f = self.inner.frame;
        self.inner.pwm[f as usize].copy_from_slice(data);
        self.inner.select(f)?;
        self.inner.write_reg(REG_PWM, data)?;
        Ok(true)
    }

    /// Set the LED-control (on/off) bit of one LED in the frame being drawn.
    pub fn set_led_enabled(&mut self, row: u8, col: u8, on: bool) -> Result<bool, I2C::Error> {
        if row > 8 || col > 15 { return Ok(false); }
        let f = self.inner.frame as usize;
        let idx = 2 * row as usize + (col >> 3) as usize;
        let mask = 1u8 << (col & 7);
        if on { self.inner.ctrl[f][idx] |= mask; } else { self.inner.ctrl[f][idx] &= !mask; }
        let v = self.inner.ctrl[f][idx];
        self.inner.select(f as u8)?;
        self.inner.write_reg(REG_CTRL + idx as u8, &[v])?;
        Ok(true)
    }

    /// Set the blink-enable bit of one LED in the frame being drawn (needs [`enable_blink`](Self::enable_blink)).
    pub fn set_blink(&mut self, row: u8, col: u8, on: bool) -> Result<bool, I2C::Error> {
        if row > 8 || col > 15 { return Ok(false); }
        let f = self.inner.frame as usize;
        let idx = 2 * row as usize + (col >> 3) as usize;
        let mask = 1u8 << (col & 7);
        if on { self.inner.blink[f][idx] |= mask; } else { self.inner.blink[f][idx] &= !mask; }
        let v = self.inner.blink[f][idx];
        self.inner.select(f as u8)?;
        self.inner.write_reg(REG_BLINK + idx as u8, &[v])?;
        Ok(true)
    }

    /// Set all 18 blink-control bytes of the frame being drawn.
    pub fn set_blink_all(&mut self, on: bool) -> Result<(), I2C::Error> {
        let f = self.inner.frame;
        let v = if on { 0xFF } else { 0x00 };
        self.inner.blink[f as usize] = [v; 18];
        self.inner.select(f)?;
        self.inner.write_reg(REG_BLINK, &[v; 18])
    }

    fn write_display(&mut self) -> Result<(), I2C::Error> {
        let v = (self.inner.ic << 5) | (self.inner.be << 3) | self.inner.bpt;
        self.inner.func_write(FN_DISPLAY, &[v])
    }

    /// Enable or disable blinking (50 % duty); `period` 0–7 in units of 0.27 s.
    ///
    /// Returns `Ok(false)` without bus access if `period` > 7.
    pub fn enable_blink(&mut self, enabled: bool, period: u8) -> Result<bool, I2C::Error> {
        if period > 7 { return Ok(false); }
        self.inner.be = enabled as u8;
        self.inner.bpt = period;
        self.write_display()?;
        Ok(true)
    }

    /// Select the frame (0–7) subsequent drawing calls write to; the display is unchanged.
    pub fn select_frame(&mut self, frame: u8) -> Result<bool, I2C::Error> {
        if frame > 7 { return Ok(false); }
        self.inner.frame = frame;
        self.inner.select(frame)?;
        Ok(true)
    }

    /// Show a frame (0–7) in Picture mode.
    pub fn show_frame(&mut self, frame: u8) -> Result<bool, I2C::Error> {
        if frame > 7 { return Ok(false); }
        self.inner.func_write(FN_CONFIG, &[0x00, frame])?;
        Ok(true)
    }

    /// Initialise a frame: LED-control `0xFF`, PWM and blink 0.
    pub fn clear_frame(&mut self, frame: u8) -> Result<bool, I2C::Error> {
        if frame > 7 { return Ok(false); }
        self.inner.init_frame(frame)?;
        Ok(true)
    }

    /// Initialise all eight frames.
    pub fn clear_all_frames(&mut self) -> Result<(), I2C::Error> {
        for f in 0..8 { self.inner.init_frame(f)?; }
        Ok(())
    }

    /// Copy the PWM values of frame `src` to frame `dst`.
    pub fn copy_frame(&mut self, src: u8, dst: u8) -> Result<bool, I2C::Error> {
        if src > 7 || dst > 7 { return Ok(false); }
        let data = self.inner.pwm[src as usize];
        self.inner.pwm[dst as usize] = data;
        self.inner.select(dst)?;
        self.inner.write_reg(REG_PWM, &data)?;
        Ok(true)
    }

    /// Switch to Picture mode (shows the frame selected by [`show_frame`](Self::show_frame)).
    pub fn set_picture_mode(&mut self) -> Result<(), I2C::Error> {
        self.inner.func_write(FN_CONFIG, &[0x00])
    }

    /// Start Auto Frame Play.
    ///
    /// `frames` 1–7 (0 = all eight), `loops` 1–7 (0 = endless), `delay_ms`
    /// rounded to a multiple of 11 ms (11–704), `start_frame` 0–7. Returns
    /// `Ok(false)` without bus access if an argument is out of range.
    pub fn start_auto_play(&mut self, frames: u8, loops: u8, delay_ms: u16, start_frame: u8) -> Result<bool, I2C::Error> {
        let a = (delay_ms + 5) / 11;
        if frames > 7 || loops > 7 || start_frame > 7 || a < 1 || a > 64 { return Ok(false); }
        self.inner.func_write(FN_AUTOPLAY1, &[(loops << 4) | frames, (a & 0x3F) as u8])?;
        self.inner.func_write(FN_CONFIG, &[0x08 | start_frame])?;
        Ok(true)
    }

    /// Stop Auto Frame Play and keep showing the current frame (Picture mode).
    ///
    /// Reading the current frame clears the movie-finished flag.
    pub fn stop_auto_play(&mut self) -> Result<(), I2C::Error> {
        let frame = self.inner.func_read(FN_FRAME_STATE)? & 0x07;
        self.inner.func_write(FN_CONFIG, &[0x00, frame])
    }

    /// Switch Audio Frame Play on, or back to Picture mode.
    pub fn set_audio_mode(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        self.inner.func_write(FN_CONFIG, &[if enabled { 0x10 } else { 0x00 }])
    }

    /// Let the audio input modulate the matrix intensity.
    pub fn set_audio_sync(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        self.inner.func_write(FN_AUDIO_SYNC, &[enabled as u8])
    }

    /// Configure the audio gain: `gain_db` 0, 3, … 21, optional AGC (slow or `fast`).
    ///
    /// Returns `Ok(false)` without bus access if `gain_db` is not a multiple of 3 in 0–21.
    pub fn set_audio_gain(&mut self, gain_db: u8, agc: bool, fast: bool) -> Result<bool, I2C::Error> {
        if gain_db > 21 || gain_db % 3 != 0 { return Ok(false); }
        self.inner.func_write(FN_AGC, &[((fast as u8) << 4) | ((agc as u8) << 3) | (gain_db / 3)])?;
        Ok(true)
    }

    /// Set the audio ADC sample interval (46 µs × `a`, 0 = 46 µs × 256).
    pub fn set_audio_rate(&mut self, a: u8) -> Result<(), I2C::Error> {
        self.inner.func_write(FN_AUDIO_RATE, &[a])
    }

    /// Configure the breath function; exponents 0–7 (fade 26 ms × 2ⁿ, extinguish 3.5 ms × 2ⁿ).
    ///
    /// Returns `Ok(false)` without bus access if an exponent is out of range.
    pub fn set_breath(&mut self, enabled: bool, fade_in: u8, fade_out: u8, extinguish: u8) -> Result<bool, I2C::Error> {
        if fade_in > 7 || fade_out > 7 || extinguish > 7 { return Ok(false); }
        self.inner.func_write(FN_BREATH1, &[(fade_out << 4) | fade_in, ((enabled as u8) << 4) | extinguish])?;
        Ok(true)
    }

    /// Choose whether frame 1's PWM values apply to all frames.
    pub fn set_intensity_control(&mut self, use_frame1: bool) -> Result<(), I2C::Error> {
        self.inner.ic = use_frame1 as u8;
        self.write_display()
    }

    /// Enter (`true`) or leave (`false`) software shutdown; frame registers are kept.
    pub fn shutdown(&mut self, on: bool) -> Result<(), I2C::Error> {
        self.inner.func_write(FN_SHUTDOWN, &[if on { 0 } else { 1 }])
    }

    /// Read `FRAME_STATE` (bit 4 = movie finished, bits 2:0 = current frame); clears the flag.
    pub fn frame_state(&mut self) -> Result<u8, I2C::Error> {
        self.inner.func_read(FN_FRAME_STATE)
    }

    /// Frame being displayed, 0–7.
    pub fn current_frame(&mut self) -> Result<u8, I2C::Error> {
        Ok(self.frame_state()? & 0x07)
    }

    /// `true` if an Auto Frame Play movie has finished (clears the flag).
    pub fn movie_finished(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.frame_state()? & 0x10 != 0)
    }

    fn restore(&mut self, prev: Option<u8>) -> Result<(), I2C::Error> {
        match prev {
            Some(p) if self.inner.page != Some(p) => self.inner.select(p),
            _ => Ok(()),
        }
    }

    /// Read a function-page register (0–0x0C) and restore the selected page.
    ///
    /// Returns `Ok(None)` without bus access if `reg` > 0x0C.
    pub fn read_function_register(&mut self, reg: u8) -> Result<Option<u8>, I2C::Error> {
        if reg > 0x0C { return Ok(None); }
        let prev = self.inner.page;
        let v = self.inner.func_read(reg)?;
        self.restore(prev)?;
        Ok(Some(v))
    }

    /// Write a function-page register (0–0x0C) and restore the selected page.
    ///
    /// Returns `Ok(false)` without bus access if `reg` > 0x0C.
    pub fn write_function_register(&mut self, reg: u8, value: u8) -> Result<bool, I2C::Error> {
        if reg > 0x0C { return Ok(false); }
        let prev = self.inner.page;
        self.inner.func_write(reg, &[value])?;
        self.restore(prev)?;
        Ok(true)
    }

    /// Read `FRAME_STATE` (clears `INT`) and restore the selected page.
    ///
    /// Wire INTB (falling edge) to your own GPIO and call this from its
    /// handler, or call it periodically (about every 50 ms) and test bit 4.
    pub fn poll_interrupt(&mut self) -> Result<u8, I2C::Error> {
        let prev = self.inner.page;
        let v = self.inner.func_read(FN_FRAME_STATE)?;
        self.restore(prev)?;
        Ok(v)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = ADDR_DEFAULT;

    fn w(reg: u8, data: &[u8]) -> T {
        let mut v = vec![reg];
        v.extend_from_slice(data);
        T::write(ADDR, v)
    }

    fn r(reg: u8, value: u8) -> T {
        T::write_read(ADDR, vec![reg], vec![value])
    }

    #[test]
    fn full_api() {
        let ramp: Vec<u8> = (0..144).map(|i| i as u8).collect();
        let t = vec![
            // init
            w(0xFD, &[0x0B]), w(0x0A, &[0x00]), w(0x00, &[0x00, 0x00]), w(0x05, &[0x00]),
            w(0xFD, &[0x00]), w(0x00, &[0xFF; 18]), w(0x12, &[0; 18]), w(0x24, &[0; 144]),
            w(0xFD, &[0x0B]), w(0x0A, &[0x01]),
            // set_pixel(2, 5, 200): page select, then PWM; set_pixel(8, 15, 1) cached page
            w(0xFD, &[0x00]), w(0x24 + 37, &[200]), w(0x24 + 143, &[1]),
            // fill(0x40), clear()
            w(0x24, &[0x40; 144]), w(0x24, &[0; 144]),
            // set_pixels(ramp)
            w(0x24, &ramp),
            // LED control and blink bits
            w(0x03, &[0xFD]), w(0x03, &[0xFF]), w(0x15, &[0x02]), w(0x12, &[0xFF; 18]), w(0x12, &[0; 18]),
            // select_frame(3), draw, copy_frame(3, 5)
            w(0xFD, &[0x03]), w(0x24, &[9]),
            w(0xFD, &[0x05]), w(0x24, &{ let mut d = [0u8; 144]; d[0] = 9; d }),
            // clear_frame(5)
            w(0x00, &[0xFF; 18]), w(0x12, &[0; 18]), w(0x24, &[0; 144]),
            // show_frame(4): function page, then CONFIG + PICTURE; set_picture_mode
            w(0xFD, &[0x0B]), w(0x00, &[0x00, 0x04]), w(0x00, &[0x00]),
            // start_auto_play(3, 2, 121, 1)
            w(0x02, &[0x23, 11]), w(0x00, &[0x09]),
            // stop_auto_play(): reads FRAME_STATE = 0x15
            r(0x07, 0x15), w(0x00, &[0x00, 0x05]),
            // audio
            w(0x00, &[0x10]), w(0x06, &[0x01]), w(0x0B, &[0x1B]), w(0x0C, &[100]),
            // enable_blink(true, 3), set_intensity_control(true), enable_blink(false, 1)
            w(0x05, &[0x0B]), w(0x05, &[0x2B]), w(0x05, &[0x21]),
            // set_breath(true, 2, 5, 3)
            w(0x08, &[0x52, 0x13]),
            // shutdown(true), shutdown(false)
            w(0x0A, &[0x00]), w(0x0A, &[0x01]),
            // frame_state, current_frame, movie_finished
            r(0x07, 0x13), r(0x07, 0x13), r(0x07, 0x13),
            // read_function_register: restores page 5 after reading
            w(0xFD, &[0x05]), w(0xFD, &[0x0B]), r(0x03, 0x2A), w(0xFD, &[0x05]),
            // write_function_register
            w(0xFD, &[0x0B]), w(0x03, &[5]), w(0xFD, &[0x05]),
            // poll_interrupt
            w(0xFD, &[0x0B]), r(0x07, 0x14), w(0xFD, &[0x05]),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut c = Is31fl3731Full::new(i2c.clone(), ADDR).expect("init");
        assert!(c.set_pixel(2, 5, 200).unwrap());
        assert!(c.set_pixel(8, 15, 1).unwrap());
        assert!(!c.set_pixel(9, 0, 0).unwrap());
        assert!(!c.set_pixel(0, 16, 0).unwrap());
        assert_eq!(c.get_pixel(2, 5), 200);
        assert_eq!(c.get_pixel(0, 0), 0);
        c.fill(0x40).unwrap();
        c.clear().unwrap();
        assert_eq!(c.get_pixel(2, 5), 0);
        assert!(c.set_pixels(&ramp).unwrap());
        assert!(!c.set_pixels(&ramp[..143]).unwrap());
        assert_eq!(c.get_pixel(1, 1), 17);
        assert!(c.set_led_enabled(1, 9, false).unwrap());
        assert!(c.set_led_enabled(1, 9, true).unwrap());
        assert!(c.set_blink(1, 9, true).unwrap());
        c.set_blink_all(true).unwrap();
        c.set_blink_all(false).unwrap();
        assert!(c.select_frame(3).unwrap());
        assert!(!c.select_frame(8).unwrap());
        c.set_pixel(0, 0, 9).unwrap();
        assert!(c.copy_frame(3, 5).unwrap());
        assert!(!c.copy_frame(0, 8).unwrap());
        assert!(c.clear_frame(5).unwrap());
        assert!(c.show_frame(4).unwrap());
        c.set_picture_mode().unwrap();
        assert!(c.start_auto_play(3, 2, 121, 1).unwrap());
        assert!(!c.start_auto_play(0, 0, 2000, 0).unwrap());
        assert!(!c.start_auto_play(0, 8, 121, 0).unwrap());
        c.stop_auto_play().unwrap();
        c.set_audio_mode(true).unwrap();
        c.set_audio_sync(true).unwrap();
        assert!(c.set_audio_gain(9, true, true).unwrap());
        assert!(!c.set_audio_gain(4, false, false).unwrap());
        c.set_audio_rate(100).unwrap();
        assert!(c.enable_blink(true, 3).unwrap());
        c.set_intensity_control(true).unwrap();
        assert!(c.enable_blink(false, 1).unwrap());
        assert!(!c.enable_blink(true, 8).unwrap());
        assert!(c.set_breath(true, 2, 5, 3).unwrap());
        assert!(!c.set_breath(true, 8, 0, 0).unwrap());
        c.shutdown(true).unwrap();
        c.shutdown(false).unwrap();
        assert_eq!(c.frame_state().unwrap(), 0x13);
        assert_eq!(c.current_frame().unwrap(), 3);
        assert!(c.movie_finished().unwrap());
        c.select_frame(5).unwrap();
        assert_eq!(c.read_function_register(0x03).unwrap(), Some(0x2A));
        assert_eq!(c.read_function_register(0x0D).unwrap(), None);
        assert!(c.write_function_register(0x03, 5).unwrap());
        assert!(!c.write_function_register(0x0D, 0).unwrap());
        assert_eq!(c.poll_interrupt().unwrap(), 0x14);
        assert_eq!(GAMMA_32[31], 255);
        assert_eq!(GAMMA_64[63], 255);
        i2c.done();
    }
}
