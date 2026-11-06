//! PCF8579 — LCD column driver (NXP), companion of the PCF8578.
//!
//! Communicates over I²C (address 0x3C when SA0 = VSS, 0x3D when SA0 = VDD,
//! 100 kHz maximum). The chip has no register map: a write transaction is a
//! command stream (continuation bit C set on every command but the last)
//! followed by display data, and a read transaction returns RAM data from the
//! current data pointer. The PCF8579 cannot run alone: the clock, frame sync
//! and row waveforms come from a PCF8578 (or an equivalent source). This
//! driver writes the 4 × 40 byte display RAM of one PCF8579 (X = n drives
//! output Cn), selected by its hardware subaddress (pins A3:A0).
//!
//! `device-select`, `set-mode` and `set-start-bank` reach every device on the
//! I²C address, so all drivers on one bus must use the same `rows`/`mixed`
//! settings.
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
//! `chips::display::pcf8579::...`.

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
/// Largest transfer accepted by [`Pcf8579Full::write_ram_cascade`] (two devices of 160 bytes).
pub const MAX_CASCADE_BYTES: usize = 320;
const PER_COL: [usize; 3] = [1, 2, 4];

/// Error type of the PCF8579 driver.
#[derive(Debug)]
pub enum Error<E> {
    /// The I²C bus reported an error.
    Bus(E),
    /// An argument was out of range (rows, bank, column, access mode, …).
    InvalidArgument,
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

/// PCF8579 minimal driver — clear the display and light pixels.
///
/// Default: PCF8578 in row mode (`T` = 0), 1:`rows` multiplex (16 rows × 40
/// columns), normal video, start bank 0, subaddress 0, RAM cleared.
pub struct Pcf8579Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    subaddress: u8,
    rows: u8,
    mixed: bool,
    status: u8,
    start_bank: u8,
    shadow: [[u8; RAM_COLUMNS]; RAM_BANKS],
}

impl<I2C: I2c> Pcf8579Minimal<I2C> {
    /// Create a new `Pcf8579Minimal` and initialise the chip.
    ///
    /// Waits 2 ms for the internal power-on reset, clears this device's RAM
    /// with one full-graphic write and then switches the display on (normal).
    /// If the subaddress pins A3:A0 do not match `subaddress` the chip does not
    /// acknowledge the data bytes and the bus error is returned.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus (100 kHz maximum).
    /// * `addr` — 7-bit I²C address (0x3C or 0x3D).
    /// * `subaddress` — Hardware subaddress set on pins A3:A0, 0 … 15.
    /// * `rows` — Display rows: 8, 16, 24 or 32.
    /// * `mixed` — `true` if the PCF8578 in the system runs in mixed mode (`T` bit).
    /// * `delay` — Delay provider for the power-on wait.
    pub fn new<D: DelayNs>(
        i2c: I2C,
        addr: u8,
        subaddress: u8,
        rows: u8,
        mixed: bool,
        delay: &mut D,
    ) -> Result<Self, Error<I2C::Error>> {
        if mux_code(rows).is_none() || subaddress > 15 {
            return Err(Error::InvalidArgument);
        }
        let mut s = Self {
            i2c,
            addr,
            subaddress,
            rows,
            mixed,
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

    fn select_byte(&self) -> u8 {
        CONT | CMD_DEVICE_SELECT | self.subaddress
    }

    fn ram_access_byte(access: u8, bank: u8) -> u8 {
        CONT | CMD_RAM_ACCESS | (access << 2) | bank
    }

    fn do_init(&mut self) -> Result<(), Error<I2C::Error>> {
        self.start_bank = 0;
        let mut buf = [0u8; BUF_LEN + 2];
        buf[0] = self.select_byte();
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

    // Write `data` at RAM column `x` / bank `bank` and mirror it in the shadow.
    fn ram_write(&mut self, x: usize, bank: usize, data: &[u8], access: u8) -> Result<(), Error<I2C::Error>> {
        let mut buf = [0u8; BUF_LEN];
        buf[0] = self.select_byte();
        buf[1] = Self::ram_access_byte(access, bank as u8);
        buf[2] = (x & 0x3F) as u8;
        buf[3..3 + data.len()].copy_from_slice(data);
        self.i2c.write(self.addr, &buf[..3 + data.len()])?;
        let per_col = PER_COL[access as usize];
        for (i, b) in data.iter().enumerate() {
            let col = x + i / per_col;
            if col < RAM_COLUMNS {
                self.shadow[bank + (i % per_col)][col] = *b;
            }
        }
        Ok(())
    }

    /// Zero this device's whole display RAM (4 banks × 40 columns) and the shadow.
    pub fn clear(&mut self) -> Result<(), Error<I2C::Error>> {
        let mut buf = [0u8; BUF_LEN];
        buf[0] = self.select_byte();
        buf[1] = Self::ram_access_byte(ACCESS_FULL_GRAPHIC, 0);
        buf[2] = 0x00;
        self.i2c.write(self.addr, &buf)?;
        self.shadow = [[0; RAM_COLUMNS]; RAM_BANKS];
        Ok(())
    }

    /// Set or clear one pixel. Out-of-range coordinates are ignored.
    ///
    /// # Arguments
    /// * `x` — Column, 0 … 39.
    /// * `y` — Row, 0 .. rows − 1.
    /// * `on` — `true` lights the pixel, `false` clears it.
    pub fn set_pixel(&mut self, x: i32, y: i32, on: bool) -> Result<(), Error<I2C::Error>> {
        if x < 0 || x >= RAM_COLUMNS as i32 || y < 0 || y >= self.rows as i32 {
            return Ok(());
        }
        let bank = (y / 8) as usize;
        let bit = 1u8 << (y % 8);
        let v = self.shadow[bank][x as usize];
        let v = if on { v | bit } else { v & !bit };
        self.ram_write(x as usize, bank, &[v], ACCESS_CHARACTER)
    }

    /// Write consecutive column bytes in character access mode.
    ///
    /// # Arguments
    /// * `x` — First column, 0 … 39.
    /// * `bank` — Bank (8-row band), 0 .. rows/8 − 1.
    /// * `data` — Column bytes (bit 0 = top row of the bank); truncated at
    ///   column 39.
    pub fn write_columns(&mut self, x: usize, bank: usize, data: &[u8]) -> Result<(), Error<I2C::Error>> {
        if bank >= (self.rows / 8) as usize {
            return Err(Error::InvalidArgument);
        }
        if x >= RAM_COLUMNS {
            return Ok(());
        }
        let n = data.len().min(RAM_COLUMNS - x);
        if n == 0 {
            return Ok(());
        }
        self.ram_write(x, bank, &data[..n], ACCESS_CHARACTER)
    }
}

/// PCF8579 full driver — extends [`Pcf8579Minimal`].
///
/// Adds pixel read-back from the shadow, fill, line/rectangle/bitmap drawing,
/// display status, multiplex rate and PCF8578 mode bit, hardware vertical
/// scrolling, and raw RAM write/read in all three access modes including
/// transfers that run on into the following cascaded devices.
pub struct Pcf8579Full<I2C> {
    inner: Pcf8579Minimal<I2C>,
}

// Per-bank bitmask of changed RAM columns (bit n = column n).
type Dirty = [u64; RAM_BANKS];

impl<I2C: I2c> Pcf8579Full<I2C> {
    /// Create a new `Pcf8579Full` and initialise the chip (see [`Pcf8579Minimal::new`]).
    pub fn new<D: DelayNs>(
        i2c: I2C,
        addr: u8,
        subaddress: u8,
        rows: u8,
        mixed: bool,
        delay: &mut D,
    ) -> Result<Self, Error<I2C::Error>> {
        Ok(Self { inner: Pcf8579Minimal::new(i2c, addr, subaddress, rows, mixed, delay)? })
    }

    /// Release the I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }

    /// Zero this device's whole display RAM and the shadow.
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
        if x < 0 || x >= RAM_COLUMNS as i32 || y < 0 || y >= s.rows as i32 {
            return;
        }
        let ram_x = x as usize;
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
        if x < 0 || x >= RAM_COLUMNS as i32 || y < 0 || y >= s.rows as i32 {
            return false;
        }
        s.shadow[(y / 8) as usize][x as usize] & (1 << (y % 8)) != 0
    }

    /// Set every displayed pixel (rows × 40) on or off.
    pub fn fill(&mut self, on: bool) -> Result<(), Error<I2C::Error>> {
        let buf = [if on { 0xFF } else { 0x00 }; RAM_COLUMNS];
        for bank in 0..(self.inner.rows / 8) as usize {
            self.inner.ram_write(0, bank, &buf, ACCESS_CHARACTER)?;
        }
        Ok(())
    }

    /// Draw a Bresenham line, written as one batch of changed bytes.
    pub fn draw_line(&mut self, x0: i32, y0: i32, x1: i32, y1: i32, on: bool) -> Result<(), Error<I2C::Error>> {
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

    /// Blit 8-pixel-high column bytes aligned to a bank boundary.
    ///
    /// # Arguments
    /// * `x` — Left column.
    /// * `y` — Top row; must be a multiple of 8.
    /// * `columns` — One byte per column (bit 0 = top row); clipped at column 39.
    pub fn show_bitmap(&mut self, x: usize, y: usize, columns: &[u8]) -> Result<(), Error<I2C::Error>> {
        if y % 8 != 0 {
            return Err(Error::InvalidArgument);
        }
        self.inner.write_columns(x, y / 8, columns)
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

    /// Change the multiplex rate and the PCF8578 mode bit `T` (affects all
    /// devices on the address).
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

    fn check_ram_args(x: usize, bank: usize, count: usize, access: u8, cascade: bool) -> Result<(), Error<I2C::Error>> {
        if access > 2 || x > 39 || bank > 3 {
            return Err(Error::InvalidArgument);
        }
        if (access == ACCESS_HALF_GRAPHIC && bank % 2 != 0) || (access == ACCESS_FULL_GRAPHIC && bank != 0) {
            return Err(Error::InvalidArgument);
        }
        let per_col = PER_COL[access as usize];
        if !cascade && x + (count + per_col - 1) / per_col > RAM_COLUMNS {
            return Err(Error::InvalidArgument);
        }
        Ok(())
    }

    /// Raw RAM write of this device in character, half-graphic or full-graphic order.
    ///
    /// # Arguments
    /// * `x` — RAM column, 0 … 39.
    /// * `bank` — RAM bank, 0 … 3 (even for half-graphic, 0 for full-graphic).
    /// * `data` — Bytes to write (must not pass column 39).
    /// * `mode` — [`ACCESS_CHARACTER`], [`ACCESS_HALF_GRAPHIC`] or [`ACCESS_FULL_GRAPHIC`].
    pub fn write_ram(&mut self, x: usize, bank: usize, data: &[u8], mode: u8) -> Result<(), Error<I2C::Error>> {
        Self::check_ram_args(x, bank, data.len(), mode, false)?;
        if data.is_empty() {
            return Ok(());
        }
        self.inner.ram_write(x, bank, data, mode)
    }

    /// Write from column 0, bank 0 of this device and run on into the next
    /// subaddresses (the chip advances its subaddress counter after column 39).
    /// The shadow only tracks this device's first 40 columns.
    ///
    /// # Arguments
    /// * `data` — Bytes to write (at most [`MAX_CASCADE_BYTES`]).
    /// * `mode` — [`ACCESS_CHARACTER`], [`ACCESS_HALF_GRAPHIC`] or [`ACCESS_FULL_GRAPHIC`].
    pub fn write_ram_cascade(&mut self, data: &[u8], mode: u8) -> Result<(), Error<I2C::Error>> {
        if data.len() > MAX_CASCADE_BYTES {
            return Err(Error::InvalidArgument);
        }
        Self::check_ram_args(0, 0, data.len(), mode, true)?;
        if data.is_empty() {
            return Ok(());
        }
        let s = &mut self.inner;
        let mut buf = [0u8; 3 + MAX_CASCADE_BYTES];
        buf[0] = s.select_byte();
        buf[1] = Pcf8579Minimal::<I2C>::ram_access_byte(mode, 0);
        buf[2] = 0x00;
        buf[3..3 + data.len()].copy_from_slice(data);
        s.i2c.write(s.addr, &buf[..3 + data.len()])?;
        let per_col = PER_COL[mode as usize];
        for (i, b) in data.iter().enumerate() {
            let col = i / per_col;
            if col < RAM_COLUMNS {
                s.shadow[i % per_col][col] = *b;
            }
        }
        Ok(())
    }

    /// Raw RAM read: address-set commands, repeated start, then `buf.len()` bytes.
    pub fn read_ram(&mut self, x: usize, bank: usize, buf: &mut [u8], mode: u8) -> Result<(), Error<I2C::Error>> {
        Self::check_ram_args(x, bank, buf.len(), mode, false)?;
        let cmds = [
            self.inner.select_byte(),
            Pcf8579Minimal::<I2C>::ram_access_byte(mode, bank as u8),
            (x & 0x3F) as u8,
        ];
        self.inner.i2c.write_read(self.inner.addr, &cmds, buf)?;
        Ok(())
    }

    /// Re-run initialisation (clears this device's RAM, normal video, start bank 0).
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
        let mut clear = vec![0xE0, 0xC2, 0xFC, 0xF8, 0x00];
        clear.extend(std::iter::repeat(0u8).take(160));
        vec![I2cTransaction::write(ADDR, clear), I2cTransaction::write(ADDR, vec![0x46])]
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
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF0, 0, 0x01])); // set_pixel(0, 0)
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF1, 3, 0x02])); // set_pixel(3, 9)
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF1, 3, 0x06])); // set_pixel(3, 10)
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF1, 3, 0x04])); // set_pixel(3, 9, off)
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF1, 39, 0x80])); // set_pixel(39, 15)
        // set_pixel out of range and get_pixel produce no traffic
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF0, 38, 1, 2])); // write_columns(38, 0, 4 bytes) truncated
        let mut f0 = vec![0xE0, 0xF0, 0];
        f0.extend(std::iter::repeat(0xFFu8).take(40));
        let mut f1 = vec![0xE0, 0xF1, 0];
        f1.extend(std::iter::repeat(0xFFu8).take(40));
        t.push(I2cTransaction::write(ADDR, f0));
        t.push(I2cTransaction::write(ADDR, f1));
        t.push(I2cTransaction::write(ADDR, clear_bytes()));
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF0, 0, 1, 1, 1, 1])); // draw_line batch
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF1, 2, 0xAA, 0xBB])); // show_bitmap
        t.push(I2cTransaction::write(ADDR, vec![0x42])); // DISPLAY_BLANK
        t.push(I2cTransaction::write(ADDR, vec![0x4E])); // DISPLAY_INVERSE
        t.push(I2cTransaction::write(ADDR, vec![0x4A])); // DISPLAY_ALL_ON
        t.push(I2cTransaction::write(ADDR, vec![0x46])); // DISPLAY_NORMAL
        t.push(I2cTransaction::write(ADDR, vec![0x7D])); // set_start_bank(1)
        t.push(I2cTransaction::write(ADDR, vec![0x7C])); // scroll(1) wraps to 0
        t.push(I2cTransaction::write(ADDR, vec![0x7D])); // scroll(-1)
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF0, 10, 1, 2])); // write_ram character
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF6, 10, 1, 2, 3, 4])); // half-graphic bank 2
        t.push(I2cTransaction::write(ADDR, vec![0xE0, 0xF8, 10, 1, 2, 3, 4, 5, 6, 7, 8])); // full-graphic
        let mut cascade = vec![0xE0, 0xF8, 0];
        cascade.extend(std::iter::repeat(7u8).take(200));
        t.push(I2cTransaction::write(ADDR, cascade)); // write_ram_cascade
        t.push(I2cTransaction::write_read(ADDR, vec![0xE0, 0xF0, 16], vec![9, 8, 7, 6])); // read_ram
        t.push(I2cTransaction::write(ADDR, vec![0x57])); // set_mode(24, mixed)
        t.push(I2cTransaction::write(ADDR, vec![0x46])); // set_mode(16, row driver)
        t.extend(init_txns()); // reset()

        let mut lcd = Pcf8579Full::new(I2cMock::new(&t), ADDR, 0, 16, false, &mut NoopDelay::new()).expect("init");
        lcd.clear().unwrap();
        lcd.set_pixel(0, 0, true).unwrap();
        lcd.set_pixel(3, 9, true).unwrap();
        lcd.set_pixel(3, 10, true).unwrap();
        lcd.set_pixel(3, 9, false).unwrap();
        lcd.set_pixel(39, 15, true).unwrap();
        lcd.set_pixel(40, 0, true).unwrap();
        lcd.set_pixel(0, 16, true).unwrap();
        lcd.set_pixel(-1, 0, true).unwrap();
        assert!(lcd.get_pixel(0, 0));
        assert!(!lcd.get_pixel(3, 9));
        lcd.write_columns(38, 0, &[1, 2, 3, 4]).unwrap();
        assert!(matches!(lcd.write_columns(0, 2, &[1]), Err(Error::InvalidArgument)));
        lcd.fill(true).unwrap();
        lcd.clear().unwrap();
        lcd.draw_line(0, 0, 3, 0, true).unwrap();
        lcd.show_bitmap(2, 8, &[0xAA, 0xBB]).unwrap();
        assert!(matches!(lcd.show_bitmap(0, 3, &[1]), Err(Error::InvalidArgument)));
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
        lcd.write_ram_cascade(&[7u8; 200], ACCESS_FULL_GRAPHIC).unwrap();
        assert_eq!(lcd.inner.shadow[0][39], 7);
        assert!(matches!(
            lcd.write_ram_cascade(&[0u8; MAX_CASCADE_BYTES + 1], ACCESS_FULL_GRAPHIC),
            Err(Error::InvalidArgument)
        ));
        let mut buf = [0u8; 4];
        lcd.read_ram(16, 0, &mut buf, ACCESS_CHARACTER).unwrap();
        assert_eq!(buf, [9, 8, 7, 6]);
        lcd.set_mode(24, true).unwrap();
        lcd.set_mode(16, false).unwrap();
        assert!(matches!(lcd.set_mode(12, false), Err(Error::InvalidArgument)));
        lcd.reset().unwrap();
        lcd.release().done();
    }

    #[test]
    fn init_rows() {
        for (rows, byte) in [(8u8, 0x45u8), (24, 0x47), (32, 0x44)] {
            let mut clear = vec![0xE0, 0x80 | 0x40 | rows_code(rows), 0xFC, 0xF8, 0x00];
            clear.extend(std::iter::repeat(0u8).take(160));
            let t = vec![I2cTransaction::write(ADDR, clear), I2cTransaction::write(ADDR, vec![byte])];
            let lcd = Pcf8579Minimal::new(I2cMock::new(&t), ADDR, 0, rows, false, &mut NoopDelay::new()).expect("init");
            lcd.release().done();
        }
    }

    #[test]
    fn subaddress_and_mixed() {
        let mut clear = vec![0xE3, 0xD2, 0xFC, 0xF8, 0x00];
        clear.extend(std::iter::repeat(0u8).take(160));
        let t = vec![
            I2cTransaction::write(ADDR, clear),
            I2cTransaction::write(ADDR, vec![0x56]),
            I2cTransaction::write(ADDR, vec![0xE3, 0xF0, 0, 0x01]),
        ];
        let mut lcd = Pcf8579Minimal::new(I2cMock::new(&t), ADDR, 3, 16, true, &mut NoopDelay::new()).expect("init");
        lcd.set_pixel(0, 0, true).unwrap();
        lcd.release().done();
    }

    fn rows_code(rows: u8) -> u8 {
        match rows { 8 => 1, 16 => 2, 24 => 3, _ => 0 }
    }

    #[test]
    fn invalid_arguments_rejected() {
        let mut i2c = I2cMock::new(&[]);
        let r = Pcf8579Minimal::new(i2c.clone(), ADDR, 0, 12, false, &mut NoopDelay::new());
        assert!(matches!(r, Err(Error::InvalidArgument)));
        let r = Pcf8579Minimal::new(i2c.clone(), ADDR, 16, 16, false, &mut NoopDelay::new());
        assert!(matches!(r, Err(Error::InvalidArgument)));
        i2c.done();
    }
}
