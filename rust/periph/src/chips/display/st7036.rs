//! ST7036 — character LCD controller (EA DOGM081 / DOGM162 / DOGM163).
//!
//! Drives the DOG modules over 4-wire serial: SI, SCL, CSB, RS (C/D) and RESET. The
//! HD44780-compatible instruction set has an extension table (selected by Function Set
//! `IS[2:1]`) for bias, booster, follower and contrast; the DOG presets from the data sheet
//! (page 7) are sent at initialisation.
//!
//! The driver is generic over [`DisplayBus`], implemented by
//! [`DisplayConnection`](crate::connection::display::DisplayConnection) with no BUSY pin
//! (pass `None::<NoPin>`). The serial mode is write-only and has no busy flag, so every
//! instruction is followed by a fixed wait (30 us, 2 ms after Clear / Return Home). Every
//! instruction is a single byte sent with RS (D/C#) low; every data byte gets its own CS
//! window, followed by the 30 us gap (`delay_us`). SPI mode 0 at about 1 MHz or less
//! (SCL cycle >= 200 ns at <= 4.5 V).
//!
//! See `specs/display/st7036.md`.

use crate::connection::display::DisplayBus;

/// Characters per line of the DOGM162.
pub const COLUMNS: u8 = 16;
/// Lines of the DOGM162.
pub const ROWS: u8 = 2;

const CMD_CLEAR: u8 = 0x01;
const CMD_HOME: u8 = 0x02;
const CMD_ENTRY: u8 = 0x04;
const CMD_DISPLAY: u8 = 0x08;
const CMD_SHIFT: u8 = 0x10;
const CMD_FUNCTION: u8 = 0x20;
const CMD_CGRAM: u8 = 0x40;
const CMD_DDRAM: u8 = 0x80;
const CMD_BIAS: u8 = 0x14; // table 1: 0x14 | BS << 3 | FX
const CMD_ICONADDR: u8 = 0x40; // table 1
const CMD_POWER: u8 = 0x50; // table 1: | Ion << 3 | Bon << 2 | C5 << 1 | C4
const CMD_FOLLOWER: u8 = 0x60; // table 1: | Fon << 3 | Rab2..0
const CMD_CONTRAST: u8 = 0x70; // table 1: | C3..C0
const CMD_DHPOS: u8 = 0x10; // table 2: | UD << 3

/// Supply voltage of the module (selects the booster preset).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Supply {
    /// 3.3 V, booster on (needs the two booster capacitors).
    V3_3,
    /// 5 V, booster off.
    V5,
}

/// Error type of the ST7036 driver.
#[derive(Debug)]
pub enum Error<E> {
    /// The display connection reported an error.
    Bus(E),
    /// `lines` was not 1, 2 or 3.
    InvalidLines,
}

impl<E> From<E> for Error<E> {
    fn from(e: E) -> Self {
        Error::Bus(e)
    }
}

/// `(function, bias, power, follower, contrast low)` per module (1, 2, 3 lines) and supply.
const PRESETS: [[[u8; 5]; 2]; 3] = [
    [[0x31, 0x14, 0x55, 0x6D, 0x7C], [0x31, 0x1C, 0x51, 0x6A, 0x74]], // DOGM081
    [[0x39, 0x14, 0x55, 0x6D, 0x78], [0x39, 0x1C, 0x52, 0x69, 0x74]], // DOGM162
    [[0x39, 0x15, 0x55, 0x6E, 0x72], [0x39, 0x1D, 0x50, 0x6C, 0x7C]], // DOGM163
];

/// ST7036 character LCD controller — minimal interface (DOGM162, 3.3 V).
///
/// See the [module documentation](self). `B` is the display connection ([`DisplayBus`]).
pub struct St7036Minimal<B: DisplayBus> {
    bus: B,
    lines: u8,
    columns: u8,
    n: u8,        // Function Set N
    dh: u8,       // Function Set DH
    bon: u8,      // booster on
    ion: u8,      // ICON display on
    contrast: u8, // C5..C0
    fon: u8,      // follower on
    rab: u8,      // Rab2..0
    quarter: bool, // bias 1/4
    fx: u8,       // bias FX bit
    display: u8,  // Display ON/OFF byte
    entry: u8,    // Entry Mode byte
    addr: u8,     // cached DDRAM address
}

impl<B: DisplayBus> St7036Minimal<B> {
    /// Create the driver and run the DOGM162 3.3 V initialisation sequence.
    ///
    /// Hardware reset, 40 ms, Function Set `0x39`, Bias `0x14`, Contrast `0x78`, Power `0x55`
    /// (booster on), Follower `0x6D`, 200 ms, Function Set `0x38`, Display on (`0x0C`), Clear,
    /// Entry Mode `0x06`. The display is cleared and the cursor is at the top left.
    ///
    /// # Arguments
    ///
    /// * `bus` – Display connection (CS#, D/C# = RS, RES#; no BUSY).
    ///
    /// # Errors
    ///
    /// [`Error::Bus`] on a bus error.
    pub fn new(bus: B) -> Result<Self, Error<B::Error>> {
        let mut d = Self::blank(bus, ROWS, COLUMNS);
        d.init_sequence([0x39, 0x14, 0x55, 0x6D, 0x78])?;
        Ok(d)
    }

    fn blank(bus: B, lines: u8, columns: u8) -> Self {
        Self {
            bus,
            lines,
            columns,
            n: 1,
            dh: 0,
            bon: 1,
            ion: 0,
            contrast: 0x18,
            fon: 1,
            rab: 5,
            quarter: false,
            fx: 0,
            display: 0x0C,
            entry: 0x06,
            addr: 0,
        }
    }

    /// Characters per line.
    pub fn columns(&self) -> u8 {
        self.columns
    }

    /// Number of lines.
    pub fn rows(&self) -> u8 {
        self.lines
    }

    /// Consume the driver and return the display connection.
    pub fn release(self) -> B {
        self.bus
    }

    // ---- internals ---------------------------------------------------------------------

    /// Send one instruction byte (RS low) and wait `wait_us` microseconds.
    fn instr(&mut self, byte: u8, wait_us: u32) -> Result<(), Error<B::Error>> {
        self.bus.send_command(byte, &[])?;
        if wait_us >= 1000 {
            self.bus.delay_ms(wait_us.div_ceil(1000))?;
        } else {
            self.bus.delay_us(wait_us)?;
        }
        Ok(())
    }

    fn instr30(&mut self, byte: u8) -> Result<(), Error<B::Error>> {
        self.instr(byte, 30)
    }

    /// Send one data byte (RS high) in its own CS window, then wait 30 us.
    fn data(&mut self, byte: u8) -> Result<(), Error<B::Error>> {
        self.bus.send_data(&[byte])?;
        self.bus.delay_us(30)?;
        Ok(())
    }

    fn row_start(&self, row: u8) -> u8 {
        if self.lines == 3 {
            row * 0x10
        } else {
            row * 0x40
        }
    }

    fn set_ddram(&mut self, addr: u8) -> Result<(), Error<B::Error>> {
        self.addr = addr & 0x7F;
        self.instr30(CMD_DDRAM | self.addr)
    }

    /// Function Set byte for the cached N / DH and instruction table `table` (0..2).
    fn function(&self, table: u8) -> u8 {
        CMD_FUNCTION | 0x10 | (self.n << 3) | (self.dh << 2) | (table & 3)
    }

    /// Power/ICON/Contrast high byte from the cached Ion, Bon and contrast.
    fn power_byte(&self) -> u8 {
        CMD_POWER | (self.ion << 3) | (self.bon << 2) | ((self.contrast >> 4) & 3)
    }

    /// Hardware reset, power-up wait and the instruction sequence of a preset
    /// `[function, bias, power, follower, contrast low]`.
    fn init_sequence(&mut self, seq: [u8; 5]) -> Result<(), Error<B::Error>> {
        let [func, bias, power, follower, contrast] = seq;
        self.bus.reset()?;
        self.bus.delay_ms(40)?;
        self.instr30(func)?;
        self.instr30(bias)?;
        self.instr30(contrast)?;
        self.instr30(power)?;
        self.instr30(follower)?;
        self.bus.delay_ms(200)?; // booster / V0 settle
        self.instr30(func & !0x01)?; // back to instruction table 0
        self.instr30(self.display)?;
        self.instr(CMD_CLEAR, 2000)?;
        self.instr30(self.entry)?;
        self.addr = 0;
        Ok(())
    }

    // ---- public API --------------------------------------------------------------------

    /// Clear the display (Clear Display, 2 ms); the cursor goes to the top left.
    pub fn clear(&mut self) -> Result<(), Error<B::Error>> {
        self.instr(CMD_CLEAR, 2000)?;
        self.addr = 0;
        Ok(())
    }

    /// Return the cursor (and a shifted display) to the home position (Return Home, 2 ms).
    pub fn home(&mut self) -> Result<(), Error<B::Error>> {
        self.instr(CMD_HOME, 2000)?;
        self.addr = 0;
        Ok(())
    }

    /// Move the cursor to (`col`, `row`). Out-of-range positions are ignored.
    pub fn set_cursor(&mut self, col: u8, row: u8) -> Result<(), Error<B::Error>> {
        if col >= self.columns || row >= self.lines {
            return Ok(());
        }
        self.set_ddram(self.row_start(row) + col)
    }

    /// Write text at the cursor, one data byte per transfer with a 30 us gap.
    ///
    /// ASCII `0x20 ..= 0x7D` is written as is; any other character becomes `?`. The cursor
    /// does not wrap to the next line.
    pub fn write(&mut self, text: &str) -> Result<(), Error<B::Error>> {
        for ch in text.chars() {
            let c = ch as u32;
            self.write_char(if (0x20..=0x7D).contains(&c) { c as u8 } else { b'?' })?;
        }
        Ok(())
    }

    /// Write one raw character code (including the CGRAM codes 0..7) at the cursor.
    pub fn write_char(&mut self, code: u8) -> Result<(), Error<B::Error>> {
        self.data(code)?;
        self.addr = (self.addr + 1) & 0x7F;
        Ok(())
    }

    /// Move the cursor to (`col`, `row`) and write `text`.
    pub fn print_at(&mut self, col: u8, row: u8, text: &str) -> Result<(), Error<B::Error>> {
        self.set_cursor(col, row)?;
        self.write(text)
    }
}

/// ST7036 character LCD controller — full interface.
///
/// Extends [`St7036Minimal`] (reachable through `Deref`) with the DOGM081 / DOGM162 / DOGM163
/// modules at 3.3 V or 5 V, display control (on/off, cursor, blink, entry mode, shift),
/// contrast, booster, follower and bias, custom characters (CGRAM), double height, ICON RAM
/// and raw instructions.
pub struct St7036Full<B: DisplayBus> {
    inner: St7036Minimal<B>,
}

impl<B: DisplayBus> core::ops::Deref for St7036Full<B> {
    type Target = St7036Minimal<B>;
    fn deref(&self) -> &Self::Target {
        &self.inner
    }
}

impl<B: DisplayBus> core::ops::DerefMut for St7036Full<B> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.inner
    }
}

impl<B: DisplayBus> St7036Full<B> {
    /// Create the driver for a DOG module and run its initialisation sequence.
    ///
    /// # Arguments
    ///
    /// * `bus`      – Display connection (no BUSY).
    /// * `lines`    – 1 (DOGM081, 1 x 8), 2 (DOGM162, 2 x 16) or 3 (DOGM163, 3 x 16).
    /// * `columns`  – Characters per line, or `None` for the module default (8 or 16).
    /// * `supply`   – [`Supply::V3_3`] (booster on) or [`Supply::V5`] (booster off).
    /// * `contrast` – Contrast `C5..C0` 0..63, or `None` for the data sheet preset.
    ///
    /// # Errors
    ///
    /// [`Error::InvalidLines`] when `lines` is not 1..=3, [`Error::Bus`] on a bus error.
    pub fn new(
        bus: B,
        lines: u8,
        columns: Option<u8>,
        supply: Supply,
        contrast: Option<u8>,
    ) -> Result<Self, Error<B::Error>> {
        if !(1..=3).contains(&lines) {
            return Err(Error::InvalidLines);
        }
        let [func, bias, mut power, follower, mut low] =
            PRESETS[lines as usize - 1][if supply == Supply::V3_3 { 0 } else { 1 }];
        let mut d = St7036Minimal::blank(bus, lines, columns.unwrap_or(if lines == 1 { 8 } else { 16 }));
        d.n = (func >> 3) & 1;
        d.bon = (power >> 2) & 1;
        d.contrast = ((power & 3) << 4) | (low & 0x0F);
        if let Some(c) = contrast {
            d.contrast = c & 0x3F;
            power = (power & !3) | ((d.contrast >> 4) & 3);
            low = CMD_CONTRAST | (d.contrast & 0x0F);
        }
        d.rab = follower & 7;
        d.quarter = (bias >> 3) & 1 == 1;
        d.fx = bias & 1;
        d.init_sequence([func, bias, power, follower, low])?;
        Ok(Self { inner: d })
    }

    /// Consume the driver and return the display connection.
    pub fn release(self) -> B {
        self.inner.bus
    }

    // ---- display control ---------------------------------------------------------------

    fn set_display_bit(&mut self, mask: u8, on: bool) -> Result<(), Error<B::Error>> {
        let d = &mut self.inner;
        d.display = if on { d.display | mask } else { d.display & !mask };
        d.display = (d.display & 0x07) | CMD_DISPLAY;
        d.instr30(d.display)
    }

    /// Turn the display on or off (the DDRAM content is kept).
    pub fn display(&mut self, on: bool) -> Result<(), Error<B::Error>> {
        self.set_display_bit(0x04, on)
    }

    /// Show or hide the cursor (`on`) and its blinking block (`blink`).
    pub fn cursor(&mut self, on: bool, blink: bool) -> Result<(), Error<B::Error>> {
        self.set_display_bit(0x02, on)?;
        self.set_display_bit(0x01, blink)
    }

    /// Entry mode: `increment` = cursor moves right (address + 1), `shift` = shift the display
    /// on every write.
    pub fn set_entry_mode(&mut self, increment: bool, shift: bool) -> Result<(), Error<B::Error>> {
        let d = &mut self.inner;
        d.entry = CMD_ENTRY | if increment { 2 } else { 0 } | if shift { 1 } else { 0 };
        d.instr30(d.entry)
    }

    /// Shift the cursor one position.
    pub fn shift_cursor(&mut self, right: bool) -> Result<(), Error<B::Error>> {
        self.inner.instr30(CMD_SHIFT | if right { 0x04 } else { 0 })
    }

    /// Shift the whole display one position (the cursor follows).
    pub fn shift_display(&mut self, right: bool) -> Result<(), Error<B::Error>> {
        self.inner.instr30(CMD_SHIFT | 0x08 | if right { 0x04 } else { 0 })
    }

    /// Return the cursor and a shifted display to the home position (Return Home, 2 ms).
    pub fn return_home(&mut self) -> Result<(), Error<B::Error>> {
        self.inner.home()
    }

    /// Set a raw DDRAM address (for use with display shift).
    pub fn set_ddram_address(&mut self, address: u8) -> Result<(), Error<B::Error>> {
        self.inner.set_ddram(address)
    }

    // ---- contrast and supply -----------------------------------------------------------

    fn ext_begin(&mut self) -> Result<(), Error<B::Error>> {
        let f = self.inner.function(1);
        self.inner.instr30(f)
    }

    fn ext_end(&mut self) -> Result<(), Error<B::Error>> {
        let f = self.inner.function(0);
        self.inner.instr30(f)
    }

    /// Set the contrast `C5..C0` (0..63).
    pub fn set_contrast(&mut self, value: u8) -> Result<(), Error<B::Error>> {
        self.inner.contrast = value & 0x3F;
        self.ext_begin()?;
        let p = self.inner.power_byte();
        self.inner.instr30(p)?;
        self.inner.instr30(CMD_CONTRAST | (self.inner.contrast & 0x0F))?;
        self.ext_end()
    }

    /// Switch the voltage booster on or off (needed at 3.3 V).
    pub fn set_booster(&mut self, on: bool) -> Result<(), Error<B::Error>> {
        self.inner.bon = on as u8;
        self.ext_begin()?;
        let p = self.inner.power_byte();
        self.inner.instr30(p)?;
        self.ext_end()
    }

    /// Voltage follower on/off and V0 amplification `ratio` (`Rab2..Rab0` 0..7:
    /// 1 + Rb/Ra = 1, 1.25, 1.5, 1.8, 2, 2.5, 3, 3.75).
    pub fn set_follower(&mut self, on: bool, ratio: u8) -> Result<(), Error<B::Error>> {
        self.inner.fon = on as u8;
        self.inner.rab = ratio & 7;
        self.ext_begin()?;
        self.inner.instr30(CMD_FOLLOWER | (self.inner.fon << 3) | self.inner.rab)?;
        self.ext_end()
    }

    /// LCD bias: 1/5 (default) or 1/4 (`quarter`); `FX` follows the line count.
    pub fn set_bias(&mut self, quarter: bool) -> Result<(), Error<B::Error>> {
        self.inner.quarter = quarter;
        self.ext_begin()?;
        self.inner.instr30(CMD_BIAS | if quarter { 8 } else { 0 } | self.inner.fx)?;
        self.ext_end()
    }

    // ---- custom characters and special modes -------------------------------------------

    /// Define a user character (CGRAM) `index` 0..7 from 8 row patterns of 5 bits (bit 4 =
    /// leftmost pixel) and restore the DDRAM address.
    pub fn create_char(&mut self, index: u8, rows: &[u8; 8]) -> Result<(), Error<B::Error>> {
        let saved = self.inner.addr;
        self.inner.instr30(CMD_CGRAM | ((index & 7) << 3))?;
        for r in rows {
            self.inner.data(r & 0x1F)?;
        }
        self.inner.set_ddram(saved)
    }

    /// Double-height font (1-line module: 5 x 16 font; 3-line module: top or bottom pair of
    /// lines; `upper` = COM1..16, otherwise COM9..24). Ignored on the 2-line module (the
    /// combination is forbidden by the data sheet).
    pub fn set_double_height(&mut self, on: bool, upper: bool) -> Result<(), Error<B::Error>> {
        if self.inner.lines == 2 {
            return Ok(());
        }
        self.inner.dh = on as u8;
        let f = self.inner.function(if self.inner.lines == 1 { 0 } else { 2 });
        self.inner.instr30(f)?;
        if self.inner.lines == 3 {
            self.inner.instr30(CMD_DHPOS | if upper { 8 } else { 0 })?;
        }
        let f = self.inner.function(0);
        self.inner.instr30(f)
    }

    /// ICON display on/off (no icons are wired on the DOG modules).
    pub fn icon(&mut self, on: bool) -> Result<(), Error<B::Error>> {
        self.inner.ion = on as u8;
        self.ext_begin()?;
        let p = self.inner.power_byte();
        self.inner.instr30(p)?;
        self.ext_end()
    }

    /// Write one ICON RAM byte (`address` 0..15).
    pub fn write_icon(&mut self, address: u8, value: u8) -> Result<(), Error<B::Error>> {
        let saved = self.inner.addr;
        self.ext_begin()?;
        self.inner.instr30(CMD_ICONADDR | (address & 0x0F))?;
        self.inner.data(value)?;
        self.ext_end()?;
        self.inner.set_ddram(saved)
    }

    /// Send any instruction byte (RS low) and wait 30 us.
    pub fn send_command(&mut self, instruction: u8) -> Result<(), Error<B::Error>> {
        self.inner.instr30(instruction)
    }

    /// Send any data byte (RS high) and wait 30 us.
    pub fn send_data(&mut self, value: u8) -> Result<(), Error<B::Error>> {
        self.inner.write_char(value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::connection::display::DisplayError;

    #[derive(Debug, PartialEq)]
    enum Ev {
        Reset,
        Delay(u32),
        DelayUs(u32),
        Cmd(u8, Vec<u8>),
        Data(Vec<u8>),
    }

    struct MockBus {
        events: Vec<Ev>,
    }

    impl DisplayBus for MockBus {
        type Error = DisplayError<(), (), (), ()>;
        fn reset(&mut self) -> Result<(), Self::Error> {
            self.events.push(Ev::Reset);
            Ok(())
        }
        fn delay_ms(&mut self, ms: u32) -> Result<(), Self::Error> {
            self.events.push(Ev::Delay(ms));
            Ok(())
        }
        fn delay_us(&mut self, us: u32) -> Result<(), Self::Error> {
            self.events.push(Ev::DelayUs(us));
            Ok(())
        }
        fn wait_busy(&mut self, _: bool, _: Option<u32>) -> Result<bool, Self::Error> {
            Ok(true)
        }
        fn send_command(&mut self, cmd: u8, data: &[u8]) -> Result<(), Self::Error> {
            self.events.push(Ev::Cmd(cmd, data.to_vec()));
            Ok(())
        }
        fn send_data(&mut self, data: &[u8]) -> Result<(), Self::Error> {
            self.events.push(Ev::Data(data.to_vec()));
            Ok(())
        }
        fn read_data(&mut self, _: &mut [u8]) -> Result<(), Self::Error> {
            Err(DisplayError::ReadUnavailable)
        }
    }

    /// R reset, I<hh> instruction, D<hh> data byte, d<ms> delay, u<us> microsecond delay.
    fn trace(bus: &MockBus, from: usize) -> String {
        bus.events[from..]
            .iter()
            .map(|e| match e {
                Ev::Reset => "R".to_string(),
                Ev::Cmd(c, d) => {
                    assert!(d.is_empty(), "instructions are single bytes");
                    format!("I{:02X}", c)
                }
                Ev::Data(d) => format!("D{:02X}{}", d[0], if d.len() != 1 { "!" } else { "" }),
                Ev::Delay(ms) => format!("d{}", ms),
                Ev::DelayUs(us) => format!("u{}", us),
            })
            .collect::<Vec<_>>()
            .join(" ")
    }

    fn full(lines: u8, supply: Supply) -> St7036Full<MockBus> {
        St7036Full::new(MockBus { events: vec![] }, lines, None, supply, None).unwrap()
    }

    fn mark<B: DisplayBus>(d: &St7036Full<B>) -> usize
    where
        B: AsMock,
    {
        d.inner.bus.len()
    }

    trait AsMock {
        fn len(&self) -> usize;
    }
    impl AsMock for MockBus {
        fn len(&self) -> usize {
            self.events.len()
        }
    }

    const INIT_162: &str = "R d40 I39 u30 I14 u30 I78 u30 I55 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30";

    #[test]
    fn init_sequence_dogm162_3v3() {
        let d = St7036Minimal::new(MockBus { events: vec![] }).unwrap();
        assert_eq!((d.columns(), d.rows()), (16, 2));
        assert_eq!(trace(&d.release(), 0), INIT_162);
    }

    #[test]
    fn minimal_operations() {
        let mut d = St7036Minimal::new(MockBus { events: vec![] }).unwrap();
        let t = |d: &St7036Minimal<MockBus>, n: usize| trace(&d.bus, n);
        let mut n = d.bus.events.len();
        d.clear().unwrap();
        assert_eq!(t(&d, n), "I01 d2");
        n = d.bus.events.len();
        d.home().unwrap();
        assert_eq!(t(&d, n), "I02 d2");
        n = d.bus.events.len();
        d.set_cursor(3, 1).unwrap();
        assert_eq!(t(&d, n), "IC3 u30");
        n = d.bus.events.len();
        d.set_cursor(0, 0).unwrap();
        assert_eq!(t(&d, n), "I80 u30");
        n = d.bus.events.len();
        d.set_cursor(16, 0).unwrap();
        d.set_cursor(0, 2).unwrap();
        assert_eq!(d.bus.events.len(), n);
        d.write("Hi").unwrap();
        assert_eq!(t(&d, n), "D48 u30 D69 u30");
        n = d.bus.events.len();
        d.write("a\u{e4}z~").unwrap();
        assert_eq!(t(&d, n), "D61 u30 D3F u30 D7A u30 D3F u30");
        n = d.bus.events.len();
        d.write_char(0x05).unwrap();
        assert_eq!(t(&d, n), "D05 u30");
        n = d.bus.events.len();
        d.print_at(2, 1, "x").unwrap();
        assert_eq!(t(&d, n), "IC2 u30 D78 u30");
    }

    #[test]
    fn presets() {
        let cases: [(u8, Supply, &str); 6] = [
            (1, Supply::V3_3, "R d40 I31 u30 I14 u30 I7C u30 I55 u30 I6D u30 d200 I30 u30 I0C u30 I01 d2 I06 u30"),
            (1, Supply::V5, "R d40 I31 u30 I1C u30 I74 u30 I51 u30 I6A u30 d200 I30 u30 I0C u30 I01 d2 I06 u30"),
            (2, Supply::V3_3, INIT_162),
            (2, Supply::V5, "R d40 I39 u30 I1C u30 I74 u30 I52 u30 I69 u30 d200 I38 u30 I0C u30 I01 d2 I06 u30"),
            (3, Supply::V3_3, "R d40 I39 u30 I15 u30 I72 u30 I55 u30 I6E u30 d200 I38 u30 I0C u30 I01 d2 I06 u30"),
            (3, Supply::V5, "R d40 I39 u30 I1D u30 I7C u30 I50 u30 I6C u30 d200 I38 u30 I0C u30 I01 d2 I06 u30"),
        ];
        for (lines, supply, want) in cases {
            let d = full(lines, supply);
            assert_eq!(trace(&d.inner.bus, 0), want, "lines {} {:?}", lines, supply);
        }
    }

    #[test]
    fn geometry_and_overrides() {
        let mut d = full(3, Supply::V3_3);
        assert_eq!((d.columns(), d.rows()), (16, 3));
        let n = mark(&d);
        d.set_cursor(0, 2).unwrap();
        assert_eq!(trace(&d.inner.bus, n), "IA0 u30");
        let one = full(1, Supply::V3_3);
        assert_eq!((one.columns(), one.rows()), (8, 1));
        let ov = St7036Full::new(MockBus { events: vec![] }, 2, None, Supply::V3_3, Some(0x2A)).unwrap();
        assert_eq!(
            trace(&ov.inner.bus, 0),
            "R d40 I39 u30 I14 u30 I7A u30 I56 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30"
        );
        let wide = St7036Full::new(MockBus { events: vec![] }, 2, Some(20), Supply::V3_3, None).unwrap();
        assert_eq!(wide.columns(), 20);
        assert!(matches!(
            St7036Full::new(MockBus { events: vec![] }, 9, None, Supply::V3_3, None),
            Err(Error::InvalidLines)
        ));
    }

    #[test]
    fn control_instructions() {
        let mut d = full(2, Supply::V3_3);
        let mut check = |d: &mut St7036Full<MockBus>, want: &str, f: &dyn Fn(&mut St7036Full<MockBus>)| {
            let n = mark(d);
            f(d);
            assert_eq!(trace(&d.inner.bus, n), want);
        };
        check(&mut d, "I08 u30", &|d| d.display(false).unwrap());
        check(&mut d, "I0C u30", &|d| d.display(true).unwrap());
        check(&mut d, "I0E u30 I0F u30", &|d| d.cursor(true, true).unwrap());
        check(&mut d, "I0D u30 I0C u30", &|d| d.cursor(false, false).unwrap());
        check(&mut d, "I05 u30", &|d| d.set_entry_mode(false, true).unwrap());
        check(&mut d, "I06 u30", &|d| d.set_entry_mode(true, false).unwrap());
        check(&mut d, "I14 u30 I10 u30", &|d| {
            d.shift_cursor(true).unwrap();
            d.shift_cursor(false).unwrap()
        });
        check(&mut d, "I1C u30 I18 u30", &|d| {
            d.shift_display(true).unwrap();
            d.shift_display(false).unwrap()
        });
        check(&mut d, "I02 d2", &|d| d.return_home().unwrap());
        check(&mut d, "IC5 u30", &|d| d.set_ddram_address(0x45).unwrap());
        check(&mut d, "I1F u30 D41 u30", &|d| {
            d.send_command(0x1F).unwrap();
            d.send_data(0x41).unwrap()
        });
    }

    #[test]
    fn extension_instructions() {
        let mut d = full(2, Supply::V3_3);
        let mut check = |d: &mut St7036Full<MockBus>, want: &str, f: &dyn Fn(&mut St7036Full<MockBus>)| {
            let n = mark(d);
            f(d);
            assert_eq!(trace(&d.inner.bus, n), want);
        };
        check(&mut d, "I39 u30 I56 u30 I7D u30 I38 u30", &|d| d.set_contrast(0x2D).unwrap());
        check(&mut d, "I39 u30 I52 u30 I38 u30", &|d| d.set_booster(false).unwrap());
        check(&mut d, "I39 u30 I56 u30 I38 u30", &|d| d.set_booster(true).unwrap());
        check(&mut d, "I39 u30 I6B u30 I38 u30", &|d| d.set_follower(true, 3).unwrap());
        check(&mut d, "I39 u30 I67 u30 I38 u30", &|d| d.set_follower(false, 7).unwrap());
        check(&mut d, "I39 u30 I1C u30 I38 u30", &|d| d.set_bias(true).unwrap());
        check(&mut d, "I39 u30 I14 u30 I38 u30", &|d| d.set_bias(false).unwrap());
        check(&mut d, "I39 u30 I5E u30 I38 u30", &|d| d.icon(true).unwrap());
        check(&mut d, "I39 u30 I43 u30 D15 u30 I38 u30 I80 u30", &|d| d.write_icon(3, 0x15).unwrap());
        let mut three = full(3, Supply::V3_3);
        let n = mark(&three);
        three.set_bias(false).unwrap();
        assert_eq!(trace(&three.inner.bus, n), "I39 u30 I15 u30 I38 u30");
    }

    #[test]
    fn custom_chars_and_double_height() {
        let mut d = full(2, Supply::V3_3);
        d.set_cursor(5, 1).unwrap();
        let mut n = mark(&d);
        let glyph = [0x1F, 0x00, 0xFF, 0x01, 0x02, 0x04, 0x08, 0x10];
        d.create_char(2, &glyph).unwrap();
        assert_eq!(
            trace(&d.inner.bus, n),
            "I50 u30 D1F u30 D00 u30 D1F u30 D01 u30 D02 u30 D04 u30 D08 u30 D10 u30 IC5 u30"
        );
        n = mark(&d);
        d.create_char(9, &glyph).unwrap();
        assert!(trace(&d.inner.bus, n).starts_with("I48 u30"));
        n = mark(&d);
        d.set_double_height(true, true).unwrap();
        assert_eq!(mark(&d), n);
        let mut one = full(1, Supply::V3_3);
        n = mark(&one);
        one.set_double_height(true, true).unwrap();
        assert_eq!(trace(&one.inner.bus, n), "I34 u30 I34 u30");
        n = mark(&one);
        one.set_double_height(false, true).unwrap();
        assert_eq!(trace(&one.inner.bus, n), "I30 u30 I30 u30");
        let mut three = full(3, Supply::V3_3);
        n = mark(&three);
        three.set_double_height(true, true).unwrap();
        assert_eq!(trace(&three.inner.bus, n), "I3E u30 I18 u30 I3C u30");
        n = mark(&three);
        three.set_double_height(true, false).unwrap();
        assert_eq!(trace(&three.inner.bus, n), "I3E u30 I10 u30 I3C u30");
    }
}
