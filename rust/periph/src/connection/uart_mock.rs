//! Scripted TMC2208-style UART chip simulator for unit tests (`std` only).
//!
//! [`UartMock`] implements [`embedded_io::Read`] + [`embedded_io::Write`] and
//! behaves like a Trinamic single-wire UART slave: 8-byte write datagrams
//! store the register and bump `IFCNT`; 4-byte read requests queue the reply
//! from `regs`. With `echo` every transmitted byte is also queued back, as on
//! the shared `PDN_UART` wire.

use std::collections::{BTreeMap, VecDeque};

use embedded_io::{ErrorType, Read, Write};

/// CRC-8 (polynomial 0x07, init 0, LSB first) over a datagram body.
pub fn crc8(data: &[u8]) -> u8 {
    let mut crc = 0u8;
    for &b in data {
        let mut byte = b;
        for _ in 0..8 {
            if ((crc >> 7) ^ (byte & 1)) != 0 {
                crc = (crc << 1) ^ 0x07;
            } else {
                crc <<= 1;
            }
            byte >>= 1;
        }
    }
    crc
}

/// In-memory UART chip simulator.
pub struct UartMock {
    /// Whether transmitted bytes are echoed back (shared-wire wiring).
    pub echo: bool,
    /// Register file returned on reads and updated by write datagrams.
    pub regs: BTreeMap<u8, u32>,
    /// Every datagram passed to `write`.
    pub datagrams: Vec<Vec<u8>>,
    /// Every register write `(register, value)`.
    pub writes: Vec<(u8, u32)>,
    /// When set, the next read reply carries a corrupted CRC.
    pub corrupt_next_reply: bool,
    rx: VecDeque<u8>,
}

impl UartMock {
    /// New mock with a TMC2208-like `IOIN` (SEL_A = 1, VERSION 0x21).
    pub fn new(echo: bool) -> Self {
        let mut regs = BTreeMap::new();
        regs.insert(0x06, 0x2100_0100);
        regs.insert(0x02, 0);
        Self { echo, regs, datagrams: Vec::new(), writes: Vec::new(), corrupt_next_reply: false, rx: VecDeque::new() }
    }

    /// Last value written to `reg`, if any.
    pub fn last_write(&self, reg: u8) -> Option<u32> {
        self.writes.iter().rev().find(|w| w.0 == reg).map(|w| w.1)
    }
}

impl ErrorType for UartMock {
    type Error = embedded_io::ErrorKind;
}

impl Write for UartMock {
    fn write(&mut self, data: &[u8]) -> Result<usize, Self::Error> {
        self.datagrams.push(data.to_vec());
        if self.echo {
            self.rx.extend(data.iter().copied());
        }
        if data.len() == 8 && data[2] & 0x80 != 0 {
            let reg = data[2] & 0x7F;
            let value = u32::from_be_bytes([data[3], data[4], data[5], data[6]]);
            self.regs.insert(reg, value);
            self.writes.push((reg, value));
            let c = self.regs[&0x02];
            self.regs.insert(0x02, (c + 1) & 0xFF);
        } else if data.len() == 4 {
            let reg = data[2] & 0x7F;
            let v = self.regs.get(&reg).copied().unwrap_or(0);
            let vb = v.to_be_bytes();
            let mut body = [0x05, 0xFF, reg, vb[0], vb[1], vb[2], vb[3], 0];
            body[7] = crc8(&body[..7]);
            if self.corrupt_next_reply {
                body[7] ^= 0xFF;
                self.corrupt_next_reply = false;
            }
            self.rx.extend(body.iter().copied());
        }
        Ok(data.len())
    }

    fn flush(&mut self) -> Result<(), Self::Error> {
        Ok(())
    }
}

impl Read for UartMock {
    fn read(&mut self, buf: &mut [u8]) -> Result<usize, Self::Error> {
        let mut n = 0;
        while n < buf.len() {
            match self.rx.pop_front() {
                Some(b) => {
                    buf[n] = b;
                    n += 1;
                }
                None => break,
            }
        }
        Ok(n)
    }
}
