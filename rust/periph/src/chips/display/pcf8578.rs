//! PCF8578 — row/column LCD dot-matrix driver (NXP).
//!
//! Communicates over I²C (address 0x3C when SA0 = VSS, 0x3D when SA0 = VDD,
//! 100 kHz maximum). The chip has no register map: a write transaction is a
//! command stream (continuation bit C set on every command but the last)
//! followed by display data, and a read transaction returns RAM data from the
//! current data pointer. This driver uses the stand-alone *mixed mode*, where
//! the chip drives both rows and columns and its 4 × 40 byte display RAM is
//! accessible. With `rows` rows the panel is `40 - rows` columns wide.
//!
//! One RAM byte is 8 vertical pixels of one column; bit 0 is the top row of
//! the bank. A shadow copy of the RAM is kept so single-pixel updates need
//! only one byte on the bus.
//!
//! ## Constants
//!
//! Display status: [`DISPLAY_BLANK`], [`DISPLAY_NORMAL`], [`DISPLAY_ALL_ON`], [`DISPLAY_INVERSE`]
//! RAM access mode: [`ACCESS_CHARACTER`], [`ACCESS_HALF_GRAPHIC`], [`ACCESS_FULL_GRAPHIC`]
//!
//! The constants are not re-exported from `chips::display` (the sibling
//! PCF8576 driver uses overlapping names); reach them as
//! `chips::display::pcf8578::...`.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

/// I²C address with SA0 = VSS.
pub const ADDR_SA0_LOW: u8 = 0x3C;
/// I²C address with SA0 = VDD.
pub const ADDR_SA0_HIGH: u8 = 0x3D;

const CMD_SET_MODE: u8 = 0x40;
const CMD_DEVICE_SELECT: u8 = 0x60;
const CMD_RAM_ACCESS: u8 = 0x70;
const CMD_SET_START_BANK: u8 = 0x7C;
const CONT: u8 = 0x80;

/// Display status: all segments off.
pub const DISPLAY_BLANK: u8 = 0;
/// Display status: normal video.
pub const DISPLAY_NORMAL: u8 = 1;
/// Display status: all segments on.
pub const DISPLAY_ALL_ON: u8 = 2;
/// Display status: inverse video.
pub const DISPLAY_INVERSE: u8 = 3;

/// RAM access order: one bank, consecutive columns.
pub const ACCESS_CHARACTER: u8 = 0;
/// RAM access order: two banks per column.
pub const ACCESS_HALF_GRAPHIC: u8 = 1;
/// RAM access order: four banks per column.
pub const ACCESS_FULL_GRAPHIC: u8 = 2;

const RAM_COLUMNS: usize = 40;
const RAM_BANKS: usize = 4;
const BUF_LEN: usize = 3 + RAM_COLUMNS * RAM_BANKS;
const PER_COL: [usize; 3] = [1, 2, 4];

/// Error type of the PCF8578 driver.
#[derive(Debug)]
pub enum Error<E> {
    /// The I²C bus reported an error.
    Bus(E),
    /// An argument was out of range (rows, bank, column, access mode, …).
    InvalidArgument,
    /// The RAM is only accessible in mixed mode, but row mode is selected.
    RowMode,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

fn mux_code(rows: u8) -> Option<u8> {
    match rows {
        8 => Some(1),
        16 => Some(2),
        24 => Some(3),
        32 => Some(0),
        _ => None,
    }
}

/// PCF8578 minimal driver — clear the display and light pixels.
///
/// Default: mixed mode, 1:`rows` multiplex (16 rows × 24 columns), normal
/// video, start bank 0, subaddress 0, RAM cleared.
pub struct Pcf8578Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    rows: u8,
    mixed: bool,
    status: u8,
    start_bank: u8,
    shadow: [[u8; RAM_COLUMNS]; RAM_BANKS],
}

impl<I2C: I2c> Pcf8578Minimal<I2C> {
    /// Create a new `Pcf8578Minimal` and initialise the chip.
    ///
    /// Waits 2 ms for the internal power-on reset, clears the whole RAM with
    /// one full-graphic write and then switches the display on (normal).
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus (100 kHz maximum).
    /// * `addr` — 7-bit I²C address (0x3C or 0x3D).
    /// * `rows` — Display rows: 8, 16, 24 or 32.
    /// * `delay` — Delay provider for the power-on wait.
    pub fn new<D: DelayNs>(i2c: I2C, addr: u8, rows: u8, delay: &mut D) -> Result<Self, Error<I2C::Error>> {
        if mux_code(rows).is_none() {
            return Err(Error::InvalidArgument);
        }
        let mut s = Self {
            i2c,
            addr,
            rows,
            mixed: true,
            status: DISPLAY_NORMAL,
            start_bank: 0,
            shadow: [[0; RAM_COLUMNS]; RAM_BANKS],
        };
        delay.delay_ms(2); // >= 1 ms for the internal power-on reset
        s.do_init()?;
        Ok(s)
    }

    /// Release the I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }

    fn mode_byte(&self, status: u8, cont: bool) -> u8 {
        (if cont { CONT } else { 0 })
            | CMD_SET_MODE
            | ((self.mixed as u8) << 4)
            | (status << 2)
            | mux_code(self.rows).unwrap_or(0)
    }

    fn ram_access_byte(access: u8, bank: u8) -> u8 {
        CONT | CMD_RAM_ACCESS | (access << 2) | bank
    }

    fn do_init(&mut self) -> Result<(), Error<I2C::Error>> {
        self.mixed = true;
        self.start_bank = 0;
        let mut buf = [0u8; BUF_LEN + 2];
        buf[0] = CONT | CMD_DEVICE_SELECT;
        buf[1] = self.mode_byte(DISPLAY_BLANK, true);
        buf[2] = CONT | CMD_SET_START_BANK;
        buf[3] = Self::ram_access_byte(ACCESS_FULL_GRAPHIC, 0);
        buf[4] = 0x00; // load-X 0 (C = 0); the 160 data bytes stay zero
        self.i2c.write(self.addr, &buf[..5 + RAM_COLUMNS * RAM_BANKS])?;
        self.shadow = [[0; RAM_COLUMNS]; RAM_BANKS];
        self.status = DISPLAY_NORMAL;
        let on = [self.mode_byte(DISPLAY_NORMAL, false)];
        self.i2c.write(self.addr, &on)?;
        Ok(())
    }

    fn require_mixed(&self) -> Result<(), Error<I2C::Error>> {
        if self.mixed { Ok(()) } else { Err(Error::RowMode) }
    }

    // Write `data` at RAM column `x` / bank `bank` and mirror it in the shadow.
    fn ram_write(&mut self, x: usize, bank: usize, data: &[u8], access: u8) -> Result<(), Error<I2C::Error>> {
        self.require_mixed()?;
        let mut buf = [0u8; BUF_LEN];
        buf[0] = CONT | CMD_DEVICE_SELECT;
        buf[1] = Self::ram_access_byte(access, bank as u8);
        buf[2] = (x & 0x3F) as u8;
        buf[3..3 + data.len()].copy_from_slice(data);
        self.i2c.write(self.addr, &buf[..3 + data.len()])?;
        let per_col = PER_COL[access as usize];
        for (i, b) in data.iter().enumerate() {
            self.shadow[bank + (i % per_col)][x + i / per_col] = *b;
        }
        Ok(())
    }

    /// Zero the whole display RAM (4 banks × 40 columns) and the shadow.
    pub fn clear(&mut self) -> Result<(), Error<I2C::Error>> {
        self.require_mixed()?;
        let mut buf = [0u8; BUF_LEN];
        buf[0] = CONT | CMD_DEVICE_SELECT;
        buf[1] = Self::ram_access_byte(ACCESS_FULL_GRAPHIC, 0);
        buf[2] = 0x00;
        self.i2c.write(self.addr, &buf)?;
        self.shadow = [[0; RAM_COLUMNS]; RAM_BANKS];
        Ok(())
    }

    /// Set or clear one pixel. Out-of-range coordinates are ignored.
    ///
    /// # Arguments
    /// * `x` — Display column, 0 .. (40 − rows − 1).
    /// * `y` — Display row, 0 .. rows − 1.
    /// * `on` — `true` lights the pixel, `false` clears it.
    pub fn set_pixel(&mut self, x: i32, y: i32, on: bool) -> Result<(), Error<I2C::Error>> {
        self.require_mixed()?;
        let columns = (RAM_COLUMNS - self.rows as usize) as i32;
        if x < 0 || x >= columns || y < 0 || y >= self.rows as i32 {
            return Ok(());
        }
        let ram_x = self.rows as usize + x as usize;
        let bank = (y / 8) as usize;
        let bit = 1u8 << (y % 8);
        let v = self.shadow[bank][ram_x];
        let v = if on { v | bit } else { v & !bit };
        self.ram_write(ram_x, bank, &[v], ACCESS_CHARACTER)
    }

    /// Write consecutive column bytes in character access mode.
    ///
    /// # Arguments
    /// * `x` — First display column, 0 .. (40 − rows − 1).
    /// * `bank` — Display bank (8-row band), 0 .. rows/8 − 1.
    /// * `data` — Column bytes (bit 0 = top row of the bank); truncated at the
    ///   last display column.
    pub fn write_columns(&mut self, x: usize, bank: usize, data: &[u8]) -> Result<(), Error<I2C::Error>> {
        if bank >= (self.rows / 8) as usize {
            return Err(Error::InvalidArgument);
        }
        let columns = RAM_COLUMNS - self.rows as usize;
        if x >= columns {
            return Ok(());
        }
        let n = data.len().min(columns - x);
        if n == 0 {
            return Ok(());
        }
        self.ram_write(self.rows as usize + x, bank, &data[..n], ACCESS_CHARACTER)
    }
}

/// PCF8578 full driver — extends [`Pcf8578Minimal`].
///
/// Adds pixel read-back from the shadow, fill, line/rectangle/bitmap drawing,
/// display status, multiplex rate and row mode, hardware vertical scrolling,
/// raw RAM write/read in all three access modes and the device-select command
/// for PCF8579 cascades.
pub struct Pcf8578Full<I2C> {
    inner: Pcf8578Minimal<I2C>,
}

// Per-bank bitmask of changed RAM columns (bit n = column n).
type Dirty = [u64; RAM_BANKS];

impl<I2C: I2c> Pcf8578Full<I2C> {
    /// Create a new `Pcf8578Full` and initialise the chip (see [`Pcf8578Minimal::new`]).
    pub fn new<D: DelayNs>(i2c: I2C, addr: u8, rows: u8, delay: &mut D) -> Result<Self, Error<I2C::Error>> {
        Ok(Self { inner: Pcf8578Minimal::new(i2c, addr, rows, delay)? })
    }

    /// Release the I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Zero the whole display RAM and the shadow.
    pub fn clear(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.clear()
    }

    /// Set or clear one pixel (out-of-range coordinates are ignored).
    pub fn set_pixel(&mut self, x: i32, y: i32, on: bool) -> Result<(), Error<I2C::Error>> {
        self.inner.set_pixel(x, y, on)
    }

    /// Write consecutive column bytes in character access mode.
    pub fn write_columns(&mut self, x: usize, bank: usize, data: &[u8]) -> Result<(), Error<I2C::Error>> {
        self.inner.write_columns(x, bank, data)
    }

    fn plot(&mut self, x: i32, y: i32, on: bool, dirty: &mut Dirty) {
        let s = &mut self.inner;
        let columns = (RAM_COLUMNS - s.rows as usize) as i32;
        if x < 0 || x >= columns || y < 0 || y >= s.rows as i32 {
            return;
        }
        let ram_x = s.rows as usize + x as usize;
        let bank = (y / 8) as usize;
        let bit = 1u8 << (y % 8);
        if on {
            s.shadow[bank][ram_x] |= bit;
        } else {
            s.shadow[bank][ram_x] &= !bit;
        }
        dirty[bank] |= 1u64 << ram_x;
    }

    // One transaction per run of consecutive changed columns in a bank.
    fn flush(&mut self, dirty: &Dirty) -> Result<(), Error<I2C::Error>> {
        for bank in 0..RAM_BANKS {
            let mut x = 0;
            while x < RAM_COLUMNS {
                if dirty[bank] & (1u64 << x) == 0 {
                    x += 1;
                    continue;
                }
                let start = x;
                while x < RAM_COLUMNS && dirty[bank] & (1u64 << x) != 0 {
                    x += 1;
                }
                let mut tmp = [0u8; RAM_COLUMNS];
                let run = x - start;
                tmp[..run].copy_from_slice(&self.inner.shadow[bank][start..x]);
                self.inner.ram_write(start, bank, &tmp[..run], ACCESS_CHARACTER)?;
            }
        }
        Ok(())
    }

    /// Return the state of one pixel from the shadow (no bus traffic).
    pub fn get_pixel(&self, x: i32, y: i32) -> bool {
        let s = &self.inner;
        let columns = (RAM_COLUMNS - s.rows as usize) as i32;
        if x < 0 || x >= columns || y < 0 || y >= s.rows as i32 {
            return false;
        }
        s.shadow[(y / 8) as usize][s.rows as usize + x as usize] & (1 << (y % 8)) != 0
    }

    /// Set every displayed pixel on or off.
    pub fn fill(&mut self, on: bool) -> Result<(), Error<I2C::Error>> {
        let columns = RAM_COLUMNS - self.inner.rows as usize;
        let buf = [if on { 0xFF } else { 0x00 }; RAM_COLUMNS];
        for bank in 0..(self.inner.rows / 8) as usize {
            let rows = self.inner.rows as usize;
            self.inner.ram_write(rows, bank, &buf[..columns], ACCESS_CHARACTER)?;
        }
        Ok(())
    }

    /// Draw a Bresenham line, written as one batch of changed bytes.
    pub fn draw_line(&mut self, x0: i32, y0: i32, x1: i32, y1: i32, on: bool) -> Result<(), Error<I2C::Error>> {
        self.inner.require_mixed()?;
        let mut dirty: Dirty = [0; RAM_BANKS];
        let (mut x0, mut y0) = (x0, y0);
        let dx = (x1 - x0).abs();
        let dy = -(y1 - y0).abs();
        let sx = if x0 < x1 { 1 } else { -1 };
        let sy = if y0 < y1 { 1 } else { -1 };
        let mut err = dx + dy;
        loop {
            self.plot(x0, y0, on, &mut dirty);
            if x0 == x1 && y0 == y1 {
                break;
            }
            let e2 = 2 * err;
            if e2 >= dy {
                err += dy;
                x0 += sx;
            }
            if e2 <= dx {
                err += dx;
                y0 += sy;
            }
        }
        self.flush(&dirty)
    }

    /// Draw a rectangle outline or a filled rectangle.
    pub fn draw_rect(&mut self, x: i32, y: i32, w: i32, h: i32, on: bool, fill: bool) -> Result<(), Error<I2C::Error>> {
        self.inner.require_mixed()?;
        if w <= 0 || h <= 0 {
            return Ok(());
        }
        let mut dirty: Dirty = [0; RAM_BANKS];
        for yy in y..y + h {
            for xx in x..x + w {
                if fill || yy == y || yy == y + h - 1 || xx == x || xx == x + w - 1 {
                    self.plot(xx, yy, on, &mut dirty);
                }
            }
        }
        self.flush(&dirty)
    }

    /// Blit a column-major bitmap aligned to a bank boundary.
    ///
    /// # Arguments
    /// * `x` — Left display column.
    /// * `y` — Top display row; must be a multiple of 8.
    /// * `bands` — One slice per 8-pixel-high band from the top, each holding
    ///   one byte per column (bit 0 = top row). Content outside the display is
    ///   clipped.
    pub fn show_bitmap(&mut self, x: usize, y: usize, bands: &[&[u8]]) -> Result<(), Error<I2C::Error>> {
        if y % 8 != 0 {
            return Err(Error::InvalidArgument);
        }
        for (i, band) in bands.iter().enumerate() {
            let bank = y / 8 + i;
            if bank >= (self.inner.rows / 8) as usize {
                break;
            }
            self.inner.write_columns(x, bank, band)?;
        }
        Ok(())
    }

    /// Set the display status ([`DISPLAY_BLANK`] … [`DISPLAY_INVERSE`]); RAM is preserved.
    pub fn set_display(&mut self, status: u8) -> Result<(), Error<I2C::Error>> {
        if status > 3 {
            return Err(Error::InvalidArgument);
        }
        self.inner.status = status;
        let b = [self.inner.mode_byte(status, false)];
        self.inner.i2c.write(self.inner.addr, &b)?;
        Ok(())
    }

    /// Change the multiplex rate and the row/mixed mode. In row mode
    /// (`mixed = false`) the RAM cannot be accessed; pixel and RAM calls return
    /// [`Error::RowMode`] until mixed mode is restored.
    pub fn set_mode(&mut self, rows: u8, mixed: bool) -> Result<(), Error<I2C::Error>> {
        if mux_code(rows).is_none() {
            return Err(Error::InvalidArgument);
        }
        self.inner.rows = rows;
        self.inner.mixed = mixed;
        let b = [self.inner.mode_byte(self.inner.status, false)];
        self.inner.i2c.write(self.inner.addr, &b)?;
        Ok(())
    }

    /// Select the RAM bank shown at the top of the display (vertical scroll), 0 … 3.
    pub fn set_start_bank(&mut self, bank: u8) -> Result<(), Error<I2C::Error>> {
        if bank > 3 {
            return Err(Error::InvalidArgument);
        }
        self.inner.start_bank = bank;
        self.inner.i2c.write(self.inner.addr, &[CMD_SET_START_BANK | bank])?;
        Ok(())
    }

    /// Add `banks` (may be negative) to the start bank, modulo the banks in use.
    pub fn scroll(&mut self, banks: i32) -> Result<(), Error<I2C::Error>> {
        let n = (self.inner.rows / 8) as i32;
        let b = (self.inner.start_bank as i32 + banks).rem_euclid(n);
        self.set_start_bank(b as u8)
    }

    fn check_ram_args(x: usize, bank: usize, count: usize, access: u8) -> Result<(), Error<I2C::Error>> {
        if access > 2 || x > 39 || bank > 3 {
            return Err(Error::InvalidArgument);
        }
        if (access == ACCESS_HALF_GRAPHIC && bank % 2 != 0) || (access == ACCESS_FULL_GRAPHIC && bank != 0) {
            return Err(Error::InvalidArgument);
        }
        let per_col = PER_COL[access as usize];
        if x + (count + per_col - 1) / per_col > RAM_COLUMNS {
            return Err(Error::InvalidArgument);
        }
        Ok(())
    }

    /// Raw RAM write in character, half-graphic or full-graphic order.
    ///
    /// # Arguments
    /// * `x` — RAM column, 0 … 39.
    /// * `bank` — RAM bank, 0 … 3 (even for half-graphic, 0 for full-graphic).
    /// * `data` — Bytes to write (at most 160).
    /// * `mode` — [`ACCESS_CHARACTER`], [`ACCESS_HALF_GRAPHIC`] or [`ACCESS_FULL_GRAPHIC`].
    pub fn write_ram(&mut self, x: usize, bank: usize, data: &[u8], mode: u8) -> Result<(), Error<I2C::Error>> {
        Self::check_ram_args(x, bank, data.len(), mode)?;
        if data.is_empty() {
            return Ok(());
        }
        self.inner.ram_write(x, bank, data, mode)
    }

    /// Raw RAM read: address-set commands, repeated start, then `buf.len()` bytes.
    pub fn read_ram(&mut self, x: usize, bank: usize, buf: &mut [u8], mode: u8) -> Result<(), Error<I2C::Error>> {
        self.inner.require_mixed()?;
        Self::check_ram_args(x, bank, buf.len(), mode)?;
        let cmds = [
            CONT | CMD_DEVICE_SELECT,
            Pcf8578Minimal::<I2C>::ram_access_byte(mode, bank as u8),
            (x & 0x3F) as u8,
        ];
        self.inner.i2c.write_read(self.inner.addr, &cmds, buf)?;
        Ok(())
    }

    /// Load the subaddress counter, 0 … 15 (for PCF8579 cascades). Subaddress
    /// 0 addresses the PCF8578 itself; pixel and RAM calls restore it first.
    pub fn device_select(&mut self, subaddress: u8) -> Result<(), Error<I2C::Error>> {
        if subaddress > 15 {
            return Err(Error::InvalidArgument);
        }
        self.inner.i2c.write(self.inner.addr, &[CMD_DEVICE_SELECT | subaddress])?;
        Ok(())
    }

    /// Re-run initialisation at the current row count (clears RAM, mixed mode, normal video).
    pub fn reset(&mut self) -> Result<(), Error<I2C::Error>> {
        self.inner.do_init()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = ADDR_SA0_LOW;

    fn init_txns() -> Vec<I2cTransaction> {
        let mut clear = vec![0xE0, 0xD2, 0xFC, 0xF8, 0x00];
        clear.extend(std::iter::repeat(0u8).take(160));
        vec![I2cTransaction::write(ADDR, clear), I2cTransaction::write(ADDR, vec![0x56])]
    }

    fn clear_bytes() -> Vec<u8> {
        let mut v = vec![0xE0, 0xF8, 0x00];
        v.extend(std::iter::repeat(0u8).take(160));
        v
    }

    #[test]
    fn full_api() {
        let mut t = init_txns();
        t.push(I2cTransaction::write(ADDR, clear_bytes())); // clear()
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF0, 16, 0x01])); // set_pixel(0, 0)
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF1, 19, 0x02])); // set_pixel(3, 9)
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF1, 19, 0x06])); // set_pixel(3, 10)
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF1, 19, 0x04])); // set_pixel(3, 9, off)
        // set_pixel out of range and get_pixel produce no traffic
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF0, 38, 1, 2])); // write_columns(22, 0, 4 bytes) truncated
        let mut f0 = vec![0xE0, 0xF0, 16];
        f0.extend(std::iter::repeat(0xFFu8).take(24));
        let mut f1 = vec![0xE0, 0xF1, 16];
        f1.extend(std::iter::repeat(0xFFu8).take(24));
        t.push(I2cTransaction::write(ADDR, f0));
        t.push(I2cTransaction::write(ADDR, f1));
        t.push(I2cTransaction::write(ADDR, clear_bytes()));
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF0, 16, 1, 1, 1, 1])); // draw_line batch
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF1, 18, 0xAA, 0xBB])); // show_bitmap
        t.push(I2cTransaction::write(ADDR, vec![0x52])); // DISPLAY_BLANK
        t.push(I2cTransaction::write(ADDR, vec![0x5E])); // DISPLAY_INVERSE
        t.push(I2cTransaction::write(ADDR, vec![0x5A])); // DISPLAY_ALL_ON
        t.push(I2cTransaction::write(ADDR, vec![0x56])); // DISPLAY_NORMAL
        t.push(I2cTransaction::write(ADDR, vec![0x7D])); // set_start_bank(1)
        t.push(I2cTransaction::write(ADDR, vec![0x7C])); // scroll(1) wraps to 0
        t.push(I2cTransaction::write(ADDR, vec![0x7D])); // scroll(-1)
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF0, 10, 1, 2])); // write_ram character
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF6, 10, 1, 2, 3, 4])); // half-graphic bank 2
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF8, 10, 1, 2, 3, 4, 5, 6, 7, 8])); // full-graphic
        t.push(I2cTransaction::write_read(ADDR, vec![0xE0, 0xF0, 16], vec![9, 8, 7, 6])); // read_ram
        t.push(I2cTransaction::write(ADDR, vec![0x65])); // device_select(5)
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF0, 16, 0x01])); // pixel restores subaddress 0
        t.push(I2cTransaction::write(ADDR, vec![0x57])); // set_mode(24, mixed)
        t.push(I2cTransaction::write(ADDR, vec![0x46])); // set_mode(16, row mode)
        t.push(I2cTransaction::write(ADDR, vec![0x56])); // set_mode(16, mixed)
        t.extend(init_txns()); // reset()

        let mut lcd = Pcf8578Full::new(I2cMock::new(&t), ADDR, 16, &mut NoopDelay::new()).expect("init");
        lcd.clear().unwrap();
        lcd.set_pixel(0, 0, true).unwrap();
        lcd.set_pixel(3, 9, true).unwrap();
        lcd.set_pixel(3, 10, true).unwrap();
        lcd.set_pixel(3, 9, false).unwrap();
        lcd.set_pixel(24, 0, true).unwrap();
        lcd.set_pixel(0, 16, true).unwrap();
        lcd.set_pixel(-1, 0, true).unwrap();
        assert!(lcd.get_pixel(0, 0));
        assert!(!lcd.get_pixel(3, 9));
        lcd.write_columns(22, 0, &[1, 2, 3, 4]).unwrap();
        assert!(matches!(lcd.write_columns(0, 2, &[1]), Err(Error::InvalidArgument)));
        lcd.fill(true).unwrap();
        lcd.clear().unwrap();
        lcd.draw_line(0, 0, 3, 0, true).unwrap();
        lcd.show_bitmap(2, 8, &[&[0xAA, 0xBB]]).unwrap();
        assert!(matches!(lcd.show_bitmap(0, 3, &[&[1]]), Err(Error::InvalidArgument)));
        lcd.set_display(DISPLAY_BLANK).unwrap();
        lcd.set_display(DISPLAY_INVERSE).unwrap();
        lcd.set_display(DISPLAY_ALL_ON).unwrap();
        lcd.set_display(DISPLAY_NORMAL).unwrap();
        lcd.set_start_bank(1).unwrap();
        lcd.scroll(1).unwrap();
        lcd.scroll(-1).unwrap();
        assert!(matches!(lcd.set_start_bank(4), Err(Error::InvalidArgument)));
        lcd.write_ram(10, 0, &[1, 2], ACCESS_CHARACTER).unwrap();
        lcd.write_ram(10, 2, &[1, 2, 3, 4], ACCESS_HALF_GRAPHIC).unwrap();
        lcd.write_ram(10, 0, &[1, 2, 3, 4, 5, 6, 7, 8], ACCESS_FULL_GRAPHIC).unwrap();
        assert_eq!(lcd.inner.shadow[3][10], 4);
        assert_eq!(lcd.inner.shadow[0][11], 5);
        assert!(matches!(lcd.write_ram(39, 0, &[0, 0], ACCESS_CHARACTER), Err(Error::InvalidArgument)));
        assert!(matches!(lcd.write_ram(0, 4, &[1], ACCESS_CHARACTER), Err(Error::InvalidArgument)));
        assert!(matches!(lcd.write_ram(0, 1, &[1], ACCESS_HALF_GRAPHIC), Err(Error::InvalidArgument)));
        let mut buf = [0u8; 4];
        lcd.read_ram(16, 0, &mut buf, ACCESS_CHARACTER).unwrap();
        assert_eq!(buf, [9, 8, 7, 6]);
        lcd.device_select(5).unwrap();
        assert!(matches!(lcd.device_select(16), Err(Error::InvalidArgument)));
        lcd.set_pixel(0, 0, true).unwrap();
        lcd.set_mode(24, true).unwrap();
        lcd.set_mode(16, false).unwrap();
        assert!(matches!(lcd.set_pixel(0, 0, true), Err(Error::RowMode)));
        assert!(matches!(lcd.write_ram(0, 0, &[1], ACCESS_CHARACTER), Err(Error::RowMode)));
        lcd.set_mode(16, true).unwrap();
        lcd.reset().unwrap();
        lcd.release().done();
    }

    #[test]
    fn init_rows() {
        for (rows, byte) in [(8u8, 0x55u8), (24, 0x57), (32, 0x54)] {
            let mut clear = vec![0xE0, 0x80 | 0x40 | 0x10 | (rows_code(rows) ), 0xFC, 0xF8, 0x00];
            clear.extend(std::iter::repeat(0u8).take(160));
            let t = vec![I2cTransaction::write(ADDR, clear), I2cTransaction::write(ADDR, vec![byte])];
            let lcd = Pcf8578Minimal::new(I2cMock::new(&t), ADDR, rows, &mut NoopDelay::new()).expect("init");
            lcd.release().done();
        }
    }

    fn rows_code(rows: u8) -> u8 {
        match rows { 8 => 1, 16 => 2, 24 => 3, _ => 0 }
    }

    #[test]
    fn invalid_rows_rejected() {
        let mut i2c = I2cMock::new(&[]);
        let r = Pcf8578Minimal::new(i2c.clone(), ADDR, 12, &mut NoopDelay::new());
        assert!(matches!(r, Err(Error::InvalidArgument)));
        i2c.done();
    }
}
