//! NEO-6 — u-blox 6 GNSS/GPS receiver.
//!
//! Transport-agnostic NMEA driver: a byte-at-a-time state machine scans for
//! `$`, assembles a sentence to the CR/LF terminator, then validates the
//! `*XX` checksum before parsing. A corrupted sentence (e.g. a stray
//! idle-filler byte landing mid-sentence on I2C/SPI) just fails the
//! checksum and is silently discarded — no bus-specific filtering of the
//! `0xFF` idle byte is needed anywhere.
//!
//! [`Neo6Minimal`] is generic over anything implementing the internal
//! [`ByteSource`] trait, with three provided wrapper types so the same
//! driver works over UART ([`UartBus`]), I2C/DDC ([`I2cBus`]), or SPI
//! ([`SpiBus`]) without dynamic dispatch:
//!
//! ```rust,ignore
//! use periph::chips::gnss::{Neo6Minimal, UartBus};
//!
//! let mut gps = Neo6Minimal::new(UartBus(uart));
//! if gps.update()? {
//!     println!("{:?} {:?}", gps.latitude(), gps.longitude());
//! }
//! ```
//!
//! [`Neo6Full`] wraps a [`Neo6Minimal`] via composition (Rust has no
//! inheritance) and adds RMC/VTG parsing, UBX framing, and CFG helpers.

use embedded_hal::i2c::I2c;
use embedded_hal::spi::SpiDevice;
use embedded_io::{Error as _, ErrorKind, Read, Write};
use heapless::String as HString;

const SENTENCE_START: u8 = 0x24; // '$'
const CR: u8 = 0x0D;
const LF: u8 = 0x0A;
const MAX_SENTENCE: usize = 96;
const MAX_FIELDS: usize = 16;
const MAX_UBX_PAYLOAD: usize = 40;

const UBX_SYNC1: u8 = 0xB5;
const UBX_SYNC2: u8 = 0x62;
const CLASS_ACK: u8 = 0x05;
const ID_ACK_NAK: u8 = 0x00;

/// Internal: fetch one byte from whichever bus this driver was constructed
/// over. `Ok(None)` means "nothing arrived this call, try again" — not an
/// error; only genuine bus faults are propagated as `Err`.
pub trait ByteSource {
    /// The underlying bus's error type.
    type Error;

    /// Fetch one byte if available, or `Ok(None)` if none is ready yet.
    fn read_byte(&mut self) -> Result<Option<u8>, Self::Error>;

    /// Write bytes to the bus (used for UBX writes; ignored for framing
    /// reads).
    fn write_bytes(&mut self, data: &[u8]) -> Result<(), Self::Error>;
}

/// Wraps a UART peripheral (anything implementing `embedded_io::Read +
/// embedded_io::Write`, e.g. [`crate::transport::uart_linux::LinuxUart`] or
/// an `esp-hal` UART). A read timeout is treated as "no byte yet", not an
/// error.
pub struct UartBus<U>(pub U);

impl<U> ByteSource for UartBus<U>
where
    U: Read + Write,
{
    type Error = U::Error;

    fn read_byte(&mut self) -> Result<Option<u8>, Self::Error> {
        let mut buf = [0u8; 1];
        match self.0.read(&mut buf) {
            Ok(1) => Ok(Some(buf[0])),
            Ok(_) => Ok(None),
            Err(e) if e.kind() == ErrorKind::TimedOut => Ok(None),
            Err(e) => Err(e),
        }
    }

    fn write_bytes(&mut self, data: &[u8]) -> Result<(), Self::Error> {
        self.0.write_all(data)
    }
}

/// Wraps an I2C bus for the DDC (Display Data Channel) transport. Each byte
/// read performs a random-read to register `0xFF` (the module's address
/// counter saturates there once set, so re-sending it on every byte is
/// redundant but harmless).
pub struct I2cBus<I> {
    /// The underlying I2C bus.
    pub i2c: I,
    /// The module's DDC address (`0x42` by default).
    pub addr: u8,
}

impl<I: I2c> ByteSource for I2cBus<I> {
    type Error = I::Error;

    fn read_byte(&mut self) -> Result<Option<u8>, Self::Error> {
        let mut buf = [0u8; 1];
        self.i2c.write_read(self.addr, &[0xFF], &mut buf)?;
        Ok(Some(buf[0]))
    }

    fn write_bytes(&mut self, data: &[u8]) -> Result<(), Self::Error> {
        self.i2c.write(self.addr, data)
    }
}

/// Wraps a SPI device. Each byte read is a true full-duplex
/// `transfer_in_place` with `0xFF` filler on MOSI, per the spec's raw
/// UBX/NMEA byte-stream protocol (no register-address concept on SPI).
pub struct SpiBus<S>(pub S);

impl<S: SpiDevice> ByteSource for SpiBus<S> {
    type Error = S::Error;

    fn read_byte(&mut self) -> Result<Option<u8>, Self::Error> {
        let mut buf = [0xFFu8; 1];
        self.0.transfer_in_place(&mut buf)?;
        Ok(Some(buf[0]))
    }

    fn write_bytes(&mut self, data: &[u8]) -> Result<(), Self::Error> {
        self.0.write(data)
    }
}

/// Validate the `*XX` checksum of a `$...*XX\r\n` NMEA sentence.
fn nmea_checksum_ok(sentence: &[u8]) -> bool {
    let star = match sentence.iter().position(|&b| b == b'*') {
        Some(i) => i,
        None => return false,
    };
    if star < 1 || star + 4 > sentence.len() {
        return false;
    }
    let mut checksum: u8 = 0;
    for &b in &sentence[1..star] {
        checksum ^= b;
    }
    let hex = match core::str::from_utf8(&sentence[star + 1..star + 3]) {
        Ok(h) => h,
        Err(_) => return false,
    };
    match u8::from_str_radix(hex, 16) {
        Ok(expected) => checksum == expected,
        Err(_) => false,
    }
}

/// Convert NMEA `ddmm.mmmm` / `dddmm.mmmm` to signed decimal degrees.
fn nmea_to_degrees(raw: &str, hemisphere: &str) -> Option<f32> {
    let value: f32 = raw.parse().ok()?;
    let deg = (value / 100.0) as i32;
    let minutes = value - (deg as f32) * 100.0;
    let mut decimal = deg as f32 + minutes / 60.0;
    if hemisphere == "S" || hemisphere == "W" {
        decimal = -decimal;
    }
    Some(decimal)
}

/// 8-bit Fletcher checksum over class, id, length, and payload bytes.
fn ubx_checksum(data: &[u8]) -> (u8, u8) {
    let mut ck_a: u8 = 0;
    let mut ck_b: u8 = 0;
    for &b in data {
        ck_a = ck_a.wrapping_add(b);
        ck_b = ck_b.wrapping_add(ck_a);
    }
    (ck_a, ck_b)
}

/// Split `body` on `,` into `out`, returning the number of fields written
/// (capped at `MAX_FIELDS`, no heap allocation).
fn split_fields<'a>(body: &'a str, out: &mut [&'a str; MAX_FIELDS]) -> usize {
    let mut n = 0;
    for part in body.split(',') {
        if n >= MAX_FIELDS {
            break;
        }
        out[n] = part;
        n += 1;
    }
    n
}

/// u-blox NEO-6 GNSS receiver: NMEA position, altitude, and fix status.
///
/// Works out of the box with the module's factory defaults (NMEA output at
/// 9600 baud, 1 Hz, all standard sentences enabled) — no chip-side
/// configuration is sent.
pub struct Neo6Minimal<B: ByteSource> {
    bus: B,
    buf: [u8; MAX_SENTENCE],
    buf_len: usize,
    in_sentence: bool,
    lat: Option<f32>,
    lon: Option<f32>,
    alt: Option<f32>,
    fix: u8,
    satellites: u8,
}

impl<B: ByteSource> Neo6Minimal<B> {
    /// Construct a driver over the given bus wrapper ([`UartBus`],
    /// [`I2cBus`], or [`SpiBus`]).
    pub fn new(bus: B) -> Self {
        Self {
            bus,
            buf: [0u8; MAX_SENTENCE],
            buf_len: 0,
            in_sentence: false,
            lat: None,
            lon: None,
            alt: None,
            fix: 0,
            satellites: 0,
        }
    }

    /// Read available bytes and parse at most one complete NMEA sentence.
    ///
    /// Returns `Ok(true)` if a GGA sentence with a valid fix (fix status >
    /// 0) was parsed during this call.
    pub fn update(&mut self) -> Result<bool, B::Error> {
        self.update_with_hook(|_, _, _| {})
    }

    /// Same behavior as [`update`](Self::update), but also invokes `hook`
    /// for every checksum-valid sentence (not just GGA) with its 3-letter
    /// id and parsed fields. This lets [`Neo6Full`] add RMC/VTG parsing and
    /// read GGA's own utc_time/hdop fields without duplicating GGA parsing.
    pub(crate) fn update_with_hook<F>(&mut self, mut hook: F) -> Result<bool, B::Error>
    where
        F: FnMut(&str, &[&str; MAX_FIELDS], usize),
    {
        let byte = match self.bus.read_byte()? {
            Some(b) => b,
            None => return Ok(false),
        };
        if byte == SENTENCE_START {
            self.buf[0] = byte;
            self.buf_len = 1;
            self.in_sentence = true;
            return Ok(false);
        }
        if !self.in_sentence {
            return Ok(false);
        }
        if self.buf_len >= MAX_SENTENCE {
            self.buf_len = 0;
            self.in_sentence = false;
            return Ok(false);
        }
        self.buf[self.buf_len] = byte;
        self.buf_len += 1;
        if byte == LF && self.buf_len >= 2 && self.buf[self.buf_len - 2] == CR {
            let len = self.buf_len;
            self.buf_len = 0;
            self.in_sentence = false;
            return Ok(self.on_sentence(len, &mut hook));
        }
        Ok(false)
    }

    fn on_sentence<F>(&mut self, len: usize, hook: &mut F) -> bool
    where
        F: FnMut(&str, &[&str; MAX_FIELDS], usize),
    {
        let sentence_copy = self.buf; // Copy: [u8; MAX_SENTENCE] is Copy
        let sentence = &sentence_copy[..len];
        if !nmea_checksum_ok(sentence) {
            return false;
        }
        let star = match sentence.iter().position(|&b| b == b'*') {
            Some(i) => i,
            None => return false,
        };
        let body = match core::str::from_utf8(&sentence[1..star]) {
            Ok(s) => s,
            Err(_) => return false,
        };
        let mut fields: [&str; MAX_FIELDS] = [""; MAX_FIELDS];
        let field_count = split_fields(body, &mut fields);
        if field_count == 0 || fields[0].len() < 5 {
            return false;
        }
        let id = &fields[0][2..5];
        let mut result = false;
        if id == "GGA" {
            result = self.parse_gga(&fields, field_count);
        }
        hook(id, &fields, field_count);
        result
    }

    fn parse_gga(&mut self, fields: &[&str; MAX_FIELDS], field_count: usize) -> bool {
        if field_count < 15 {
            return false;
        }
        let fix = fields[6].parse::<u8>().unwrap_or(0);
        self.fix = fix;
        self.satellites = fields[7].parse::<u8>().unwrap_or(0);
        if fix > 0
            && !fields[2].is_empty()
            && !fields[3].is_empty()
            && !fields[4].is_empty()
            && !fields[5].is_empty()
        {
            if let (Some(lat), Some(lon)) = (
                nmea_to_degrees(fields[2], fields[3]),
                nmea_to_degrees(fields[4], fields[5]),
            ) {
                self.lat = Some(lat);
                self.lon = Some(lon);
                self.alt = fields[9].parse::<f32>().ok();
            }
        }
        fix > 0
    }

    /// Latitude of the last valid fix, decimal degrees (positive north).
    /// `None` until the first valid GGA fix.
    pub fn latitude(&self) -> Option<f32> {
        self.lat
    }

    /// Longitude of the last valid fix, decimal degrees (positive east).
    /// `None` until the first valid GGA fix.
    pub fn longitude(&self) -> Option<f32> {
        self.lon
    }

    /// Height above mean sea level of the last valid fix, in meters. `None`
    /// until the first valid GGA fix.
    pub fn altitude(&self) -> Option<f32> {
        self.alt
    }

    /// GGA fix quality of the last parsed GGA sentence: 0 = no fix, 1 =
    /// GPS, 2 = DGPS.
    pub fn fix(&self) -> u8 {
        self.fix
    }

    /// Number of satellites used in the last GGA fix.
    pub fn satellites(&self) -> u8 {
        self.satellites
    }
}

/// Error type for [`Neo6Full`] UBX operations: wraps the underlying bus
/// error plus NEO-6-specific failure modes.
#[derive(Debug)]
pub enum Neo6Error<E> {
    /// The underlying bus (UART/I2C/SPI) returned an error.
    Bus(E),
    /// No matching UBX response arrived before the internal idle budget was
    /// spent.
    Timeout,
    /// The module answered with UBX ACK-NAK.
    Nak,
    /// The requested `send_ubx` payload exceeds the internal fixed-size
    /// frame buffer (`MAX_UBX_PAYLOAD` bytes).
    PayloadTooLarge,
}

impl<E> From<E> for Neo6Error<E> {
    fn from(e: E) -> Self {
        Neo6Error::Bus(e)
    }
}

/// A UBX response payload returned by [`Neo6Full::poll_ubx`] — fixed
/// capacity, no heap allocation.
pub struct UbxPayload {
    buf: [u8; MAX_UBX_PAYLOAD],
    len: usize,
}

impl UbxPayload {
    /// The payload bytes.
    pub fn as_slice(&self) -> &[u8] {
        &self.buf[..self.len]
    }
}

/// NEO-6 with UBX binary messaging, rate/platform configuration, and richer
/// NMEA fields (speed, course, UTC time/date, HDOP).
///
/// Wraps a [`Neo6Minimal`] via composition (Rust has no inheritance); all
/// Minimal methods are re-exposed as one-line delegates.
pub struct Neo6Full<B: ByteSource> {
    inner: Neo6Minimal<B>,
    speed: Option<f32>,
    course: Option<f32>,
    utc_time: Option<HString<11>>,
    utc_date: Option<HString<6>>,
    hdop: Option<f32>,
}

impl<B: ByteSource> Neo6Full<B> {
    /// Construct a driver over the given bus wrapper ([`UartBus`],
    /// [`I2cBus`], or [`SpiBus`]).
    pub fn new(bus: B) -> Self {
        Self {
            inner: Neo6Minimal::new(bus),
            speed: None,
            course: None,
            utc_time: None,
            utc_date: None,
            hdop: None,
        }
    }

    /// Latitude of the last valid fix. See [`Neo6Minimal::latitude`].
    pub fn latitude(&self) -> Option<f32> {
        self.inner.latitude()
    }
    /// Longitude of the last valid fix. See [`Neo6Minimal::longitude`].
    pub fn longitude(&self) -> Option<f32> {
        self.inner.longitude()
    }
    /// Altitude of the last valid fix. See [`Neo6Minimal::altitude`].
    pub fn altitude(&self) -> Option<f32> {
        self.inner.altitude()
    }
    /// Fix quality of the last GGA sentence. See [`Neo6Minimal::fix`].
    pub fn fix(&self) -> u8 {
        self.inner.fix()
    }
    /// Satellites used in the last GGA fix. See [`Neo6Minimal::satellites`].
    pub fn satellites(&self) -> u8 {
        self.inner.satellites()
    }

    /// Read available bytes and parse at most one complete NMEA sentence;
    /// same return contract as [`Neo6Minimal::update`], plus RMC/VTG
    /// parsing and GGA's own utc_time/hdop fields.
    pub fn update(&mut self) -> Result<bool, B::Error> {
        let Self {
            inner,
            speed,
            course,
            utc_time,
            utc_date,
            hdop,
        } = self;
        inner.update_with_hook(|id, fields, field_count| match id {
            "GGA" => {
                if field_count > 1 && !fields[1].is_empty() {
                    *utc_time = HString::try_from(fields[1]).ok();
                }
                if field_count > 8 && !fields[8].is_empty() {
                    if let Ok(v) = fields[8].parse() {
                        *hdop = Some(v);
                    }
                }
            }
            "RMC" => {
                if field_count >= 10 {
                    if !fields[1].is_empty() {
                        *utc_time = HString::try_from(fields[1]).ok();
                    }
                    if !fields[7].is_empty() {
                        if let Ok(v) = fields[7].parse::<f32>() {
                            *speed = Some(v * 0.514444);
                        }
                    }
                    if !fields[8].is_empty() {
                        if let Ok(v) = fields[8].parse::<f32>() {
                            *course = Some(v);
                        }
                    }
                    if !fields[9].is_empty() {
                        *utc_date = HString::try_from(fields[9]).ok();
                    }
                }
            }
            "VTG" => {
                if field_count > 1 && !fields[1].is_empty() {
                    if let Ok(v) = fields[1].parse::<f32>() {
                        *course = Some(v);
                    }
                }
                if field_count > 7 && !fields[7].is_empty() {
                    if let Ok(v) = fields[7].parse::<f32>() {
                        *speed = Some(v / 3.6);
                    }
                }
            }
            _ => {}
        })
    }

    /// Speed over ground in m/s, converted from RMC (knots × 0.514444) or
    /// VTG (km/h ÷ 3.6). `None` until the first speed field is parsed.
    pub fn speed(&self) -> Option<f32> {
        self.speed
    }

    /// Course over ground in degrees (0-360), from RMC or VTG. `None`
    /// until the first course field is parsed.
    pub fn course(&self) -> Option<f32> {
        self.course
    }

    /// UTC time of the last GGA or RMC sentence, `hhmmss.ss`. `None`
    /// until the first sentence with a time field is parsed.
    pub fn utc_time(&self) -> Option<&str> {
        self.utc_time.as_deref()
    }

    /// UTC date of the last RMC sentence, `ddmmyy`. `None` until the
    /// first RMC sentence is parsed.
    pub fn utc_date(&self) -> Option<&str> {
        self.utc_date.as_deref()
    }

    /// Horizontal dilution of precision from the last GGA sentence with a
    /// populated HDOP field. `None` until then.
    pub fn hdop(&self) -> Option<f32> {
        self.hdop
    }

    /// Frame and write a UBX message (adds sync bytes, length, checksum).
    ///
    /// `payload` must be at most `MAX_UBX_PAYLOAD` (40) bytes.
    pub fn send_ubx(
        &mut self,
        msg_class: u8,
        msg_id: u8,
        payload: &[u8],
    ) -> Result<(), Neo6Error<B::Error>> {
        let length = payload.len();
        if length > MAX_UBX_PAYLOAD {
            return Err(Neo6Error::PayloadTooLarge);
        }
        let mut frame = [0u8; 2 + 4 + MAX_UBX_PAYLOAD + 2];
        frame[0] = UBX_SYNC1;
        frame[1] = UBX_SYNC2;
        frame[2] = msg_class;
        frame[3] = msg_id;
        frame[4] = (length & 0xFF) as u8;
        frame[5] = ((length >> 8) & 0xFF) as u8;
        frame[6..6 + length].copy_from_slice(payload);
        let (ck_a, ck_b) = ubx_checksum(&frame[2..6 + length]);
        frame[6 + length] = ck_a;
        frame[7 + length] = ck_b;
        self.inner.bus.write_bytes(&frame[..8 + length])?;
        Ok(())
    }

    /// Send a poll request and return the response payload.
    ///
    /// # Errors
    ///
    /// Returns [`Neo6Error::Nak`] if the module answers with UBX ACK-NAK, or
    /// [`Neo6Error::Timeout`] if no matching response arrives before the
    /// internal idle budget is spent.
    pub fn poll_ubx(
        &mut self,
        msg_class: u8,
        msg_id: u8,
    ) -> Result<UbxPayload, Neo6Error<B::Error>> {
        self.send_ubx(msg_class, msg_id, &[])?;
        self.read_ubx_response(msg_class, msg_id)
    }

    fn read_ubx_response(
        &mut self,
        want_class: u8,
        want_id: u8,
    ) -> Result<UbxPayload, Neo6Error<B::Error>> {
        const MAX_FRAMES: u32 = 400;
        const MAX_IDLE: u32 = 4000;
        let mut idle: u32 = 0;
        let mut frames: u32 = 0;

        while frames < MAX_FRAMES {
            let byte = match self.inner.bus.read_byte()? {
                Some(b) => b,
                None => {
                    idle += 1;
                    if idle > MAX_IDLE {
                        return Err(Neo6Error::Timeout);
                    }
                    continue;
                }
            };
            idle = 0;
            if byte != UBX_SYNC1 {
                continue;
            }
            match self.inner.bus.read_byte()? {
                Some(b) if b == UBX_SYNC2 => {}
                _ => continue,
            }
            let cls = match self.inner.bus.read_byte()? {
                Some(b) => b,
                None => continue,
            };
            let mid = match self.inner.bus.read_byte()? {
                Some(b) => b,
                None => continue,
            };
            let len_lo = match self.inner.bus.read_byte()? {
                Some(b) => b,
                None => continue,
            };
            let len_hi = match self.inner.bus.read_byte()? {
                Some(b) => b,
                None => continue,
            };
            let length = (len_lo as usize) | ((len_hi as usize) << 8);
            if length > MAX_UBX_PAYLOAD {
                frames += 1;
                continue;
            }
            let mut payload = [0u8; MAX_UBX_PAYLOAD];
            let mut got = 0usize;
            for slot in payload.iter_mut().take(length) {
                match self.inner.bus.read_byte()? {
                    Some(b) => {
                        *slot = b;
                        got += 1;
                    }
                    None => break,
                }
            }
            if got != length {
                frames += 1;
                continue;
            }
            let ck_a = match self.inner.bus.read_byte()? {
                Some(b) => b,
                None => continue,
            };
            let ck_b = match self.inner.bus.read_byte()? {
                Some(b) => b,
                None => continue,
            };
            let mut header_and_payload = [0u8; 4 + MAX_UBX_PAYLOAD];
            header_and_payload[0] = cls;
            header_and_payload[1] = mid;
            header_and_payload[2] = len_lo;
            header_and_payload[3] = len_hi;
            header_and_payload[4..4 + length].copy_from_slice(&payload[..length]);
            let (exp_a, exp_b) = ubx_checksum(&header_and_payload[..4 + length]);
            frames += 1;
            if ck_a != exp_a || ck_b != exp_b {
                continue;
            }
            if cls == CLASS_ACK && mid == ID_ACK_NAK {
                return Err(Neo6Error::Nak);
            }
            if cls == want_class && mid == want_id {
                return Ok(UbxPayload { buf: payload, len: length });
            }
        }
        Err(Neo6Error::Timeout)
    }

    /// Set the navigation update rate via CFG-RATE.
    ///
    /// `hz` — update rate in Hz (1-5 Hz for standard NEO-6 models).
    pub fn set_rate(&mut self, hz: u16) -> Result<(), Neo6Error<B::Error>> {
        let meas_rate_ms: u16 = 1000 / hz;
        let payload = [
            (meas_rate_ms & 0xFF) as u8,
            (meas_rate_ms >> 8) as u8,
            1,
            0, // navRate = 1
            0,
            0, // timeRef = 0 (UTC)
        ];
        self.send_ubx(0x06, 0x08, &payload)
    }

    /// Set the dynamic platform model via CFG-NAV5.
    ///
    /// `model` — 0=portable, 2=stationary, 3=pedestrian, 4=automotive,
    /// 5=sea, 6=airborne<1g, 7=airborne<2g, 8=airborne<4g.
    pub fn set_platform(&mut self, model: u8) -> Result<(), Neo6Error<B::Error>> {
        let mut payload = [0u8; 36];
        payload[0] = 0x01; // mask: apply dynModel only
        payload[2] = model;
        self.send_ubx(0x06, 0x24, &payload)
    }

    /// Force a cold start via CFG-RST (clears almanac, ephemeris, and last
    /// known position).
    pub fn cold_start(&mut self) -> Result<(), Neo6Error<B::Error>> {
        let payload = [0xFF, 0xFF, 0x02, 0x00];
        self.send_ubx(0x06, 0x04, &payload)
    }

    /// Persist the current configuration via CFG-CFG (saves to
    /// battery-backed RAM and flash, where available).
    pub fn save_config(&mut self) -> Result<(), Neo6Error<B::Error>> {
        let payload = [
            0x00, 0x00, 0x00, 0x00, // clearMask = 0
            0xFF, 0xFF, 0xFF, 0xFF, // saveMask = all
            0x00, 0x00, 0x00, 0x00, // loadMask = 0
            0x07, // deviceMask: BBR|Flash|EEPROM
        ];
        self.send_ubx(0x06, 0x09, &payload)
    }
}
