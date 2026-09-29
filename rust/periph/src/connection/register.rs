//! Register-addressed read/write helpers, free functions generic over
//! `embedded-hal` traits — no periph-owned wrapper type required.
//!
//! Rust chip drivers stay generic directly over `embedded_hal::i2c::I2c` /
//! `embedded_hal::spi::SpiDevice` (see `specs/feature_connection_design.md`
//! §4.5), so register access is a set of free functions rather than methods
//! on a mandatory wrapper. [`Connection`](super::connection::Connection)'s
//! own `read`/`write` are implemented in terms of these.

use embedded_hal::i2c::I2c;
use embedded_hal::spi::{Operation, SpiDevice};

/// Interpret the low `bits` bits of `value` as two's-complement signed.
pub fn to_signed(value: u32, bits: u32) -> i32 {
    let sign_bit = 1u32 << (bits - 1);
    ((value & (sign_bit - 1)).wrapping_sub(value & sign_bit)) as i32
}

/// Build a big-endian register address of `reg_bytes` bytes (1-4) from `reg`.
fn reg_addr_bytes(reg: u32, reg_bytes: u8) -> [u8; 4] {
    let mut addr = [0u8; 4];
    for i in 0..reg_bytes as usize {
        addr[i] = (reg >> (8 * (reg_bytes as usize - 1 - i))) as u8;
    }
    addr
}

/// Write a `reg_bytes`-byte big-endian `reg` then read back `buf.len()` bytes
/// over I²C (repeated start). `reg_bytes` is 1 for every chip in this
/// codebase except ADE7953 and VL53L1X (2) — see
/// specs/feature_register_access_design.md §11.
pub fn read_register<I2C: I2c>(
    i2c: &mut I2C, addr: u8, reg: u32, reg_bytes: u8, buf: &mut [u8],
) -> Result<(), I2C::Error> {
    let reg_addr = reg_addr_bytes(reg, reg_bytes);
    i2c.write_read(addr, &reg_addr[..reg_bytes as usize], buf)
}

/// Write a `reg_bytes`-byte big-endian `reg` followed by `data` over I²C in
/// one transaction.
pub fn write_register<I2C: I2c>(
    i2c: &mut I2C, addr: u8, reg: u32, reg_bytes: u8, data: &[u8],
) -> Result<(), I2C::Error> {
    let reg_addr = reg_addr_bytes(reg, reg_bytes);
    let n = reg_bytes as usize;
    let mut buf = [0u8; 20];  // up to 4 addr bytes + up to 16 data bytes
    buf[..n].copy_from_slice(&reg_addr[..n]);
    buf[n..n + data.len()].copy_from_slice(data);
    i2c.write(addr, &buf[..n + data.len()])
}

/// SPI register convention: which bits the command byte sets for a read / burst transfer.
pub struct SpiRegisterConvention {
    pub read_bit: u8,
    pub multi_byte_bit: Option<u8>,
}

/// Read `buf.len()` bytes starting at register `reg` over SPI, building the command byte from `conv`.
pub fn spi_read_register<SPI: SpiDevice>(
    spi: &mut SPI, conv: &SpiRegisterConvention, reg: u8, buf: &mut [u8],
) -> Result<(), SPI::Error> {
    let mut cmd = reg | conv.read_bit;
    if buf.len() > 1 { if let Some(mb) = conv.multi_byte_bit { cmd |= mb; } }
    spi.transaction(&mut [Operation::Write(&[cmd]), Operation::Read(buf)])
}

/// Write `data` to register `reg` over SPI, building the command byte from `conv`.
pub fn spi_write_register<SPI: SpiDevice>(
    spi: &mut SPI, conv: &SpiRegisterConvention, reg: u8, data: &[u8],
) -> Result<(), SPI::Error> {
    let mut cmd = reg;
    if data.len() > 1 { if let Some(mb) = conv.multi_byte_bit { cmd |= mb; } }
    spi.transaction(&mut [Operation::Write(&[cmd]), Operation::Write(data)])
}
