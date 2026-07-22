//! MFRC522 — 13.56 MHz contactless reader/writer (NXP).
//!
//! Communicates over SPI (the most common wiring for this chip). The MFRC522
//! has a per-register address framing: bit 7 of the address byte indicates
//! read vs write, and bits 6:1 hold the 6-bit register number. This is
//! handled in [`Mfrc522Minimal::write_reg`] / [`Mfrc522Minimal::read_reg`]
//! transparently to the caller.
//!
//! [`Mfrc522Full`] wraps a [`Mfrc522Minimal`] via composition (Rust has no
//! inheritance) and adds antenna/gain control, the digital self test, the
//! MIFARE Classic authenticated read/write/value operations, and MIFARE
//! Ultralight / NTAG page read/write.
//!
//! ## Constants
//!
//! Antenna gain: [`RX_GAIN_18_DB`], [`RX_GAIN_23_DB`], [`RX_GAIN_33_DB`],
//! [`RX_GAIN_38_DB`], [`RX_GAIN_43_DB`], [`RX_GAIN_48_DB`]
//! MIFARE key type: [`KEY_A`], [`KEY_B`]

use embedded_hal::spi::SpiDevice;

const REG_COMMAND: u8        = 0x01;
const REG_COM_IRQ: u8        = 0x04;
const REG_DIV_IRQ: u8        = 0x05;
const REG_ERROR: u8          = 0x06;
const REG_STATUS_2: u8       = 0x08;
const REG_FIFO_DATA: u8      = 0x09;
const REG_FIFO_LEVEL: u8     = 0x0A;
const REG_BIT_FRAMING: u8    = 0x0D;
const REG_TX_MODE: u8        = 0x12;
const REG_RX_MODE: u8        = 0x13;
const REG_TX_CONTROL: u8     = 0x14;
const REG_TX_ASK: u8         = 0x15;
const REG_MODE: u8           = 0x11;
const REG_CRC_RESULT_H: u8   = 0x21;
const REG_CRC_RESULT_L: u8   = 0x22;
const REG_RF_CFG: u8         = 0x26;
const REG_T_MODE: u8         = 0x2A;
const REG_T_PRESCALER: u8    = 0x2B;
const REG_T_RELOAD_H: u8     = 0x2C;
const REG_T_RELOAD_L: u8     = 0x2D;
const REG_AUTO_TEST: u8      = 0x36;
const REG_VERSION: u8        = 0x37;

const CMD_IDLE: u8           = 0x00;
const CMD_CALC_CRC: u8       = 0x03;
const CMD_TRANSCEIVE: u8     = 0x0C;
const CMD_MFAUTHENT: u8      = 0x0E;
const CMD_RANDOM_ID: u8      = 0x02;
const CMD_SOFT_RESET: u8     = 0x0F;

const IRQ_RX: u8             = 0x30;
const IRQ_IDLE: u8           = 0x10;
const IRQ_ALL: u8            = 0x7F;

const STATUS_2_CRYPTO1ON: u8 = 0x08;
const FIFO_FLUSH: u8         = 0x80;

const PICC_REQA: u8          = 0x26;
const PICC_WUPA: u8          = 0x52;
const PICC_HLTA: u8          = 0x50;
const PICC_CT: u8            = 0x88;
const PICC_SEL_BIT: u8       = 0x70;
const PICC_SAK_NOT_COMPLETE: u8 = 0x04;

fn addr_for(reg: u8, read: bool) -> u8 {
    ((reg << 1) & 0x7E) | if read { 0x80 } else { 0x00 }
}

fn delay_ms(ms: u32) {
    #[cfg(feature = "std")]
    std::thread::sleep(std::time::Duration::from_millis(ms as u64));
    #[cfg(not(feature = "std"))]
    let _ = ms;
}

/// MIFARE Crypto1 key type A (0x60).
pub const KEY_A: u8 = 0x60;
/// MIFARE Crypto1 key type B (0x61).
pub const KEY_B: u8 = 0x61;

/// Receiver gain: 18 dB.
pub const RX_GAIN_18_DB: u8 = 0x00;
/// Receiver gain: 23 dB.
pub const RX_GAIN_23_DB: u8 = 0x10;
/// Receiver gain: 33 dB.
pub const RX_GAIN_33_DB: u8 = 0x40;
/// Receiver gain: 38 dB.
pub const RX_GAIN_38_DB: u8 = 0x50;
/// Receiver gain: 43 dB.
pub const RX_GAIN_43_DB: u8 = 0x60;
/// Receiver gain: 48 dB.
pub const RX_GAIN_48_DB: u8 = 0x70;

/// MFRC522 minimal driver — detect cards and read their UID.
///
/// Default: 25 ms receive timeout, `Force100ASK`, CRC_A preset, antenna on,
/// 106 kBd, 33 dB RX gain (reset default).
pub struct Mfrc522Minimal<SPI: SpiDevice> {
    spi: SPI,
}

impl<SPI: SpiDevice> Mfrc522Minimal<SPI> {
    /// Create a new `Mfrc522Minimal` and run the initialization sequence.
    ///
    /// # Arguments
    /// * `spi` — Configured SPI device (CS handled by the device).
    pub fn new(spi: SPI) -> Result<Self, SPI::Error> {
        let mut s = Self { spi };
        s.init_chip()?;
        Ok(s)
    }

    fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), SPI::Error> {
        let buf = [addr_for(reg, false), value];
        self.spi.write(&buf)
    }

    fn read_reg(&mut self, reg: u8) -> Result<u8, SPI::Error> {
        let cmd = [addr_for(reg, true)];
        let mut buf = [0u8];
        self.spi.transfer(&mut buf, &cmd)?;
        Ok(buf[0])
    }

    fn set_bits(&mut self, reg: u8, mask: u8) -> Result<(), SPI::Error> {
        let cur = self.read_reg(reg)?;
        self.write_reg(reg, cur | mask)
    }

    fn clear_bits(&mut self, reg: u8, mask: u8) -> Result<(), SPI::Error> {
        let cur = self.read_reg(reg)?;
        self.write_reg(reg, cur & !mask)
    }

    fn init_chip(&mut self) -> Result<(), SPI::Error> {
        self.write_reg(REG_COMMAND, CMD_SOFT_RESET)?;
        for _ in 0..50 {
            if (self.read_reg(REG_COMMAND)? & 0x10) == 0 { break; }
            delay_ms(1);
        }
        delay_ms(50);
        self.write_reg(REG_T_MODE, 0x80)?;
        self.write_reg(REG_T_PRESCALER, 0xA9)?;
        self.write_reg(REG_T_RELOAD_H, 0x03)?;
        self.write_reg(REG_T_RELOAD_L, 0xE8)?;
        self.write_reg(REG_TX_ASK, 0x40)?;
        self.write_reg(REG_MODE, 0x3D)?;
        self.set_bits(REG_TX_CONTROL, 0x03)
    }

    fn read_fifo(&mut self, n: usize) -> Result<[u8; 64], SPI::Error> {
        let mut out = [0u8; 64];
        for i in 0..n {
            out[i] = self.read_reg(REG_FIFO_DATA)?;
        }
        Ok(out)
    }

    fn write_fifo(&mut self, data: &[u8]) -> Result<(), SPI::Error> {
        for &b in data {
            self.write_reg(REG_FIFO_DATA, b)?;
        }
        Ok(())
    }

    fn flush_fifo(&mut self) -> Result<(), SPI::Error> {
        self.write_reg(REG_FIFO_LEVEL, FIFO_FLUSH)
    }

    fn card_command(&mut self, command: u8, wait_irq: u8, send: &[u8]) -> Result<bool, SPI::Error> {
        self.write_reg(REG_COMMAND, CMD_IDLE)?;
        self.write_reg(REG_COM_IRQ, 0x7F)?;
        self.flush_fifo()?;
        if !send.is_empty() {
            self.write_fifo(send)?;
        }
        self.write_reg(REG_COMMAND, command)?;
        if command == CMD_TRANSCEIVE {
            self.set_bits(REG_BIT_FRAMING, 0x80)?;
        }
        for _ in 0..200 {
            let n = self.read_reg(REG_COM_IRQ)?;
            if n & wait_irq != 0 { return Ok(true); }
            if n & 0x01 != 0 { return Ok(false); }
        }
        Ok(false)
    }

    fn transceive(&mut self, send: &[u8]) -> Result<Option<[u8; 64]>, SPI::Error> {
        let len = self.card_command(CMD_TRANSCEIVE, IRQ_RX | IRQ_IDLE, send)?;
        if !len { return Ok(None); }
        let err = self.read_reg(REG_ERROR)?;
        if err & 0x13 != 0 { return Ok(None); }
        let fifo_level = self.read_reg(REG_FIFO_LEVEL)? as usize;
        if fifo_level == 0 { return Ok(None); }
        let out = self.read_fifo(fifo_level)?;
        Ok(Some(out))
    }

    fn calc_crc(&mut self, data: &[u8]) -> Result<[u8; 2], SPI::Error> {
        self.write_reg(REG_COMMAND, CMD_IDLE)?;
        self.write_reg(REG_DIV_IRQ, 0x04)?;
        self.flush_fifo()?;
        self.write_fifo(data)?;
        self.write_reg(REG_COMMAND, CMD_CALC_CRC)?;
        for _ in 0..100 {
            if self.read_reg(REG_DIV_IRQ)? & 0x04 != 0 { break; }
            delay_ms(1);
        }
        self.write_reg(REG_COMMAND, CMD_IDLE)?;
        Ok([self.read_reg(REG_CRC_RESULT_H)?, self.read_reg(REG_CRC_RESULT_L)?])
    }

    fn anticollision(&mut self, cmd: u8) -> Result<Option<[u8; 4]>, SPI::Error> {
        self.write_reg(REG_TX_MODE, 0x00)?;
        self.write_reg(REG_RX_MODE, 0x00)?;
        self.write_reg(REG_BIT_FRAMING, 0x00)?;
        let mut send = [0u8; 7];
        send[0] = cmd;
        send[1] = 0x20;
        delay_ms(1);
        let back = self.transceive(&send)?;
        let back = match back { Some(b) => b, None => return Ok(None) };
        if back[4] == 0 { return Ok(None); }
        let mut bcc = 0;
        for i in 0..4 { bcc ^= back[i]; }
        if bcc != back[4] { return Ok(None); }
        Ok(Some([back[0], back[1], back[2], back[3]]))
    }

    fn select(&mut self, cmd: u8, uid_part: &[u8; 4]) -> Result<Option<u8>, SPI::Error> {
        let mut buf = [0u8; 9];
        buf[0] = cmd;
        buf[1] = PICC_SEL_BIT;
        buf[2..6].copy_from_slice(uid_part);
        let mut bcc = 0;
        for &b in uid_part { bcc ^= b; }
        buf[6] = bcc;
        let crc = self.calc_crc(&buf[..7])?;
        buf[7] = crc[0];
        buf[8] = crc[1];
        self.write_reg(REG_TX_MODE, 0x80)?;
        self.write_reg(REG_RX_MODE, 0x80)?;
        delay_ms(1);
        let back = self.transceive(&buf)?;
        self.write_reg(REG_TX_MODE, 0x00)?;
        self.write_reg(REG_RX_MODE, 0x00)?;
        let back = match back { Some(b) => b, None => return Ok(None) };
        if back[0] == 0 { return Ok(None); }
        Ok(Some(back[0]))
    }

    fn select_card(&mut self) -> Result<Option<([u8; 10], usize)>, SPI::Error> {
        let mut uid = [0u8; 10];
        let mut len = 0usize;
        let cascade = [(0x93u8, 0x93u8), (0x95, 0x95), (0x97, 0x97)];
        for &(anti, sel) in &cascade {
            let part = match self.anticollision(anti)? { Some(p) => p, None => return Ok(None) };
            let sak = match self.select(sel, &part)? { Some(s) => s, None => return Ok(None) };
            if sak & PICC_SAK_NOT_COMPLETE == 0 {
                if part[0] == PICC_CT {
                    uid[len..len + 3].copy_from_slice(&part[1..4]);
                    len += 3;
                } else {
                    uid[len..len + 4].copy_from_slice(&part);
                    len += 4;
                }
                return Ok(Some((uid, len)));
            } else {
                uid[len..len + 3].copy_from_slice(&part[1..4]);
                len += 3;
            }
        }
        Ok(None)
    }

    fn halt_card(&mut self) -> Result<(), SPI::Error> {
        let mut buf = [0u8; 4];
        buf[0] = PICC_HLTA;
        buf[1] = 0x00;
        self.write_reg(REG_TX_MODE, 0x80)?;
        self.write_reg(REG_RX_MODE, 0x80)?;
        let crc = self.calc_crc(&buf[..2])?;
        buf[2] = crc[0];
        buf[3] = crc[1];
        delay_ms(1);
        let _ = self.card_command(CMD_TRANSCEIVE, IRQ_RX | IRQ_IDLE, &buf);
        self.write_reg(REG_TX_MODE, 0x00)?;
        self.write_reg(REG_RX_MODE, 0x00)?;
        Ok(())
    }

    /// Detect a card in the RF field.
    ///
    /// Sends a REQA short frame. Returns `true` if a card answered with a
    /// valid 2-byte ATQA.
    pub fn is_card_present(&mut self) -> Result<bool, SPI::Error> {
        self.write_reg(REG_BIT_FRAMING, 0x07)?;
        self.write_reg(REG_TX_MODE, 0x00)?;
        self.write_reg(REG_RX_MODE, 0x00)?;
        let back = self.transceive(&[PICC_REQA])?;
        Ok(matches!(back, Some(b) if b[1] == 2))
    }

    /// Detect a card, run anticollision/Select (all cascade levels), and HLTA.
    ///
    /// Returns the reassembled UID (4, 7, or 10 bytes). A card read this way
    /// is immediately halted, so the next call re-detects it from scratch.
    pub fn read_uid(&mut self) -> Result<Option<([u8; 10], usize)>, SPI::Error> {
        if !self.is_card_present()? {
            return Ok(None);
        }
        let res = self.select_card();
        self.halt_card()?;
        res
    }
}

/// MFRC522 full driver — extends minimal with configuration, antenna
/// control, self test, MIFARE Classic authenticated operations, and MIFARE
/// Ultralight / NTAG page read/write.
pub struct Mfrc522Full<SPI: SpiDevice> {
    inner: Mfrc522Minimal<SPI>,
}

impl<SPI: SpiDevice> Mfrc522Full<SPI> {
    /// Create a new `Mfrc522Full`.
    ///
    /// # Arguments
    /// * `spi` — Configured SPI device.
    pub fn new(spi: SPI) -> Result<Self, SPI::Error> {
        Ok(Self { inner: Mfrc522Minimal::new(spi)? })
    }

    /// Re-run SoftReset and the full initialization sequence.
    pub fn reset(&mut self) -> Result<(), SPI::Error> { self.inner.init_chip() }

    /// Enable the antenna driver (TX1 + TX2).
    pub fn antenna_on(&mut self) -> Result<(), SPI::Error> { self.inner.set_bits(REG_TX_CONTROL, 0x03) }

    /// Disable the antenna driver (TX1 + TX2).
    pub fn antenna_off(&mut self) -> Result<(), SPI::Error> { self.inner.clear_bits(REG_TX_CONTROL, 0x03) }

    /// Set the receiver gain.
    ///
    /// # Arguments
    /// * `db` — One of [`RX_GAIN_18_DB`], [`RX_GAIN_23_DB`], [`RX_GAIN_33_DB`],
    ///          [`RX_GAIN_38_DB`], [`RX_GAIN_43_DB`], [`RX_GAIN_48_DB`].
    pub fn set_antenna_gain(&mut self, db: u8) -> Result<(), SPI::Error> {
        let cur = self.inner.read_reg(REG_RF_CFG)? & 0x8F;
        self.inner.write_reg(REG_RF_CFG, cur | db)
    }

    /// Read the currently configured receiver gain (raw register bits 4-6).
    pub fn antenna_gain(&mut self) -> Result<u8, SPI::Error> {
        Ok(self.inner.read_reg(REG_RF_CFG)? & 0x70)
    }

    /// Read the version register and decode it.
    ///
    /// Returns `(chip_type, version)`. For MFRC522, chip_type = 0x09.
    pub fn version(&mut self) -> Result<(u8, u8), SPI::Error> {
        let raw = self.inner.read_reg(REG_VERSION)?;
        Ok(((raw >> 4) & 0x0F, raw & 0x0F))
    }

    /// Run the datasheet-defined digital self test.
    ///
    /// Returns `true` if all 64 FIFO bytes match the version-specific reference.
    pub fn self_test(&mut self) -> Result<bool, SPI::Error> {
        const REF_V10: [u8; 64] = [
            0x00, 0x87, 0x98, 0x0F, 0x49, 0xFF, 0x07, 0x19,
            0xBF, 0x22, 0x30, 0x49, 0x59, 0x63, 0xAD, 0xCA,
            0x7F, 0xE3, 0x4E, 0x03, 0x5C, 0x4E, 0x49, 0x50,
            0x47, 0x9A, 0x37, 0x61, 0xE7, 0xE2, 0xC6, 0x2E,
            0x75, 0x5A, 0xED, 0x04, 0x3D, 0x02, 0x4B, 0x78,
            0x32, 0xFF, 0x58, 0x3B, 0x7C, 0xE9, 0x00, 0x94,
            0xB4, 0x4A, 0x59, 0x5B, 0xFD, 0xC9, 0x29, 0xDF,
            0x35, 0x96, 0x98, 0x9E, 0x4F, 0x30, 0x32, 0x8D,
        ];
        const REF_V20: [u8; 64] = [
            0x00, 0xEB, 0x66, 0xBA, 0x57, 0xBF, 0x23, 0x95,
            0xD0, 0xE3, 0x0D, 0x3D, 0x27, 0x89, 0x5C, 0xDE,
            0x9D, 0x3B, 0xA7, 0x00, 0x21, 0x5B, 0x89, 0x82,
            0x51, 0x3A, 0xEB, 0x02, 0x0C, 0xA5, 0x00, 0x49,
            0x7C, 0x84, 0x4D, 0xB3, 0xCC, 0xD2, 0x1B, 0x81,
            0x5D, 0x48, 0x76, 0xD5, 0x71, 0x61, 0x21, 0xA9,
            0x86, 0x96, 0x83, 0x38, 0xCF, 0x9D, 0x5B, 0x6D,
            0xDC, 0x15, 0xBA, 0x3E, 0x7D, 0x95, 0x3B, 0x2F,
        ];
        let (_chip, ver) = self.version()?;
        let ref_: &[u8; 64] = if ver == 1 { &REF_V10 } else { &REF_V20 };
        self.inner.write_reg(REG_AUTO_TEST, 0x09)?;
        self.inner.write_reg(REG_FIFO_LEVEL, FIFO_FLUSH)?;
        self.inner.write_reg(REG_COMMAND, CMD_IDLE)?;
        for _ in 0..255 {
            if self.inner.read_reg(REG_FIFO_LEVEL)? >= 64 { break; }
            self.inner.write_reg(REG_COMMAND, CMD_CALC_CRC)?;
            delay_ms(1);
        }
        self.inner.write_reg(REG_AUTO_TEST, 0x00)?;
        self.inner.write_reg(REG_COMMAND, CMD_SOFT_RESET)?;
        delay_ms(50);
        self.inner.init_chip()?;
        let got = self.inner.read_fifo(64)?;
        Ok(&got[..64] == ref_)
    }

    /// WUPA — wake a HALTed card. Same as [`is_card_present`](Mfrc522Minimal::is_card_present) but with WUPA.
    pub fn wakeup_card(&mut self) -> Result<bool, SPI::Error> {
        self.inner.write_reg(REG_BIT_FRAMING, 0x07)?;
        self.inner.write_reg(REG_TX_MODE, 0x00)?;
        self.inner.write_reg(REG_RX_MODE, 0x00)?;
        let back = self.inner.transceive(&[PICC_WUPA])?;
        Ok(matches!(back, Some(b) if b[1] == 2))
    }

    /// Run anticollision / Select only — leaves the card active for further ops.
    pub fn select_card(&mut self) -> Result<Option<([u8; 10], usize)>, SPI::Error> {
        if !self.wakeup_card()? { return Ok(None); }
        self.inner.select_card()
    }

    /// Send HLTA — put the currently selected card into HALT state.
    pub fn halt_card(&mut self) -> Result<(), SPI::Error> { self.inner.halt_card() }

    /// Run MIFARE Classic Crypto1 authentication.
    ///
    /// # Arguments
    /// * `block_address` — Block number to authenticate against.
    /// * `key_type` — [`KEY_A`] (0x60) or [`KEY_B`] (0x61).
    /// * `key` — 6-byte key.
    /// * `uid` — 4-byte UID of the card.
    pub fn authenticate(&mut self, block_address: u8, key_type: u8, key: &[u8; 6], uid: &[u8; 4]) -> Result<bool, SPI::Error> {
        let mut buf = [0u8; 12];
        buf[0] = key_type;
        buf[1] = block_address;
        buf[2..8].copy_from_slice(key);
        buf[8..12].copy_from_slice(uid);
        self.inner.write_reg(REG_COM_IRQ, IRQ_ALL)?;
        self.inner.write_reg(REG_STATUS_2, 0x00)?;
        self.inner.flush_fifo()?;
        self.inner.write_fifo(&buf)?;
        self.inner.write_reg(REG_COMMAND, CMD_MFAUTHENT)?;
        for _ in 0..200 {
            if self.inner.read_reg(REG_STATUS_2)? & STATUS_2_CRYPTO1ON != 0 { return Ok(true); }
            delay_ms(1);
        }
        Ok(false)
    }

    /// Clear `Status2Reg.MFCrypto1On`.
    pub fn stop_crypto(&mut self) -> Result<(), SPI::Error> { self.inner.clear_bits(REG_STATUS_2, STATUS_2_CRYPTO1ON) }

    /// Read a 16-byte MIFARE Classic block.
    pub fn read_block(&mut self, block_address: u8) -> Result<Option<[u8; 16]>, SPI::Error> {
        let cmd = [0x30u8, block_address];
        self.inner.write_reg(REG_TX_MODE, 0x80)?;
        self.inner.write_reg(REG_RX_MODE, 0x80)?;
        let crc = self.inner.calc_crc(&cmd)?;
        let mut full = [0u8; 4];
        full[..2].copy_from_slice(&cmd);
        full[2..].copy_from_slice(&crc);
        let back = self.inner.transceive(&full)?;
        self.inner.write_reg(REG_TX_MODE, 0x00)?;
        self.inner.write_reg(REG_RX_MODE, 0x00)?;
        let back = match back { Some(b) => b, None => return Ok(None) };
        if back[0] != 16 { return Ok(None); }
        let mut out = [0u8; 16];
        out.copy_from_slice(&back[..16]);
        Ok(Some(out))
    }

    /// Write a 16-byte MIFARE Classic block.
    pub fn write_block(&mut self, block_address: u8, data: &[u8; 16]) -> Result<bool, SPI::Error> {
        // Phase 1: 0xA0 + block_address, expect 4-bit ACK
        let c = [0xA0u8, block_address];
        self.inner.write_reg(REG_TX_MODE, 0x80)?;
        self.inner.write_reg(REG_RX_MODE, 0x80)?;
        let crc = self.inner.calc_crc(&c)?;
        let mut full = [0u8; 4];
        full[..2].copy_from_slice(&c);
        full[2..].copy_from_slice(&crc);
        let back = self.inner.transceive(&full)?;
        if !matches!(&back, Some(b) if b[0] & 0x0F == 0x0A) {
            self.inner.write_reg(REG_TX_MODE, 0x00)?;
            self.inner.write_reg(REG_RX_MODE, 0x00)?;
            return Ok(false);
        }
        // Phase 2: 16 data bytes, expect 4-bit ACK
        let crc2 = self.inner.calc_crc(data)?;
        let mut buf = [0u8; 18];
        buf[..16].copy_from_slice(data);
        buf[16..].copy_from_slice(&crc2);
        let back2 = self.inner.transceive(&buf)?;
        self.inner.write_reg(REG_TX_MODE, 0x00)?;
        self.inner.write_reg(REG_RX_MODE, 0x00)?;
        Ok(matches!(back2, Some(b) if b[0] & 0x0F == 0x0A))
    }

    /// Increment the value block at `block_address` by `delta` and transfer it back.
    pub fn increment_value(&mut self, block_address: u8, delta: u32) -> Result<bool, SPI::Error> {
        if !self.value_op(0xC1, block_address, delta, false)? { return Ok(false); }
        self.transfer(block_address)
    }

    /// Decrement the value block at `block_address` by `delta` and transfer it back.
    pub fn decrement_value(&mut self, block_address: u8, delta: u32) -> Result<bool, SPI::Error> {
        if !self.value_op(0xC0, block_address, delta, false)? { return Ok(false); }
        self.transfer(block_address)
    }

    /// Restore (re-read) the value block at `block_address` into the internal data register.
    pub fn restore_value(&mut self, block_address: u8) -> Result<bool, SPI::Error> {
        if !self.value_op(0xC2, block_address, 0, true)? { return Ok(false); }
        self.transfer(block_address)
    }

    /// Commit the internal data register to `destination_block`.
    pub fn transfer_value(&mut self, destination_block: u8) -> Result<bool, SPI::Error> {
        self.transfer(destination_block)
    }

    fn value_op(&mut self, cmd: u8, block_address: u8, delta: u32, dummy: bool) -> Result<bool, SPI::Error> {
        let c = [cmd, block_address];
        self.inner.write_reg(REG_TX_MODE, 0x80)?;
        self.inner.write_reg(REG_RX_MODE, 0x80)?;
        let crc = self.inner.calc_crc(&c)?;
        let mut full = [0u8; 4];
        full[..2].copy_from_slice(&c);
        full[2..].copy_from_slice(&crc);
        let back = self.inner.transceive(&full)?;
        if !matches!(&back, Some(b) if b[0] & 0x0F == 0x0A) {
            self.inner.write_reg(REG_TX_MODE, 0x00)?;
            self.inner.write_reg(REG_RX_MODE, 0x00)?;
            return Ok(false);
        }
        let mut data = [0u8; 6];
        if dummy {
            data[0..4].copy_from_slice(&[0u8; 4]);
        } else {
            data[0] = delta as u8;
            data[1] = (delta >> 8) as u8;
            data[2] = (delta >> 16) as u8;
            data[3] = (delta >> 24) as u8;
        }
        let crc2 = self.inner.calc_crc(&data[..4])?;
        data[4..].copy_from_slice(&crc2);
        let back2 = self.inner.transceive(&data)?;
        self.inner.write_reg(REG_TX_MODE, 0x00)?;
        self.inner.write_reg(REG_RX_MODE, 0x00)?;
        Ok(matches!(back2, Some(b) if b[0] & 0x0F == 0x0A))
    }

    fn transfer(&mut self, block_address: u8) -> Result<bool, SPI::Error> {
        let c = [0xB0u8, block_address];
        self.inner.write_reg(REG_TX_MODE, 0x80)?;
        self.inner.write_reg(REG_RX_MODE, 0x80)?;
        let crc = self.inner.calc_crc(&c)?;
        let mut full = [0u8; 4];
        full[..2].copy_from_slice(&c);
        full[2..].copy_from_slice(&crc);
        let back = self.inner.transceive(&full)?;
        self.inner.write_reg(REG_TX_MODE, 0x00)?;
        self.inner.write_reg(REG_RX_MODE, 0x00)?;
        Ok(matches!(back, Some(b) if b[0] & 0x0F == 0x0A))
    }

    /// Read 4 consecutive pages (16 bytes) starting at `page_address`.
    pub fn read_ultralight_page(&mut self, page_address: u8) -> Result<Option<[u8; 16]>, SPI::Error> {
        let cmd = [0x30u8, page_address];
        self.inner.write_reg(REG_TX_MODE, 0x80)?;
        self.inner.write_reg(REG_RX_MODE, 0x80)?;
        let crc = self.inner.calc_crc(&cmd)?;
        let mut full = [0u8; 4];
        full[..2].copy_from_slice(&cmd);
        full[2..].copy_from_slice(&crc);
        let back = self.inner.transceive(&full)?;
        self.inner.write_reg(REG_TX_MODE, 0x00)?;
        self.inner.write_reg(REG_RX_MODE, 0x00)?;
        let back = match back { Some(b) => b, None => return Ok(None) };
        if back[0] != 16 { return Ok(None); }
        let mut out = [0u8; 16];
        out.copy_from_slice(&back[..16]);
        Ok(Some(out))
    }

    /// Write a 4-byte page (MIFARE Ultralight / NTAG).
    pub fn write_ultralight_page(&mut self, page_address: u8, data: &[u8; 4]) -> Result<bool, SPI::Error> {
        let mut buf = [0u8; 8];
        buf[0] = 0xA2;
        buf[1] = page_address;
        buf[2..6].copy_from_slice(data);
        self.inner.write_reg(REG_TX_MODE, 0x80)?;
        self.inner.write_reg(REG_RX_MODE, 0x80)?;
        let crc = self.inner.calc_crc(&buf[..6])?;
        buf[6..].copy_from_slice(&crc);
        let back = self.inner.transceive(&buf)?;
        self.inner.write_reg(REG_TX_MODE, 0x00)?;
        self.inner.write_reg(REG_RX_MODE, 0x00)?;
        Ok(matches!(back, Some(b) if b[0] & 0x0F == 0x0A))
    }

    /// Run the Generate RandomID command and return the 10-byte ID.
    pub fn generate_random_id(&mut self) -> Result<[u8; 10], SPI::Error> {
        self.inner.write_reg(REG_COMMAND, CMD_IDLE)?;
        self.inner.write_reg(REG_COM_IRQ, IRQ_ALL)?;
        self.inner.write_reg(REG_DIV_IRQ, 0x14)?;
        self.inner.write_reg(REG_COMMAND, CMD_RANDOM_ID)?;
        for _ in 0..50 {
            if self.inner.read_reg(REG_COM_IRQ)? & 0x10 != 0 { break; }
            delay_ms(1);
        }
        self.inner.write_reg(REG_COMMAND, CMD_IDLE)?;
        let mut out = [0u8; 10];
        let buf = self.inner.read_fifo(10)?;
        out.copy_from_slice(&buf[..10]);
        Ok(out)
    }

    /// Detect a card in the RF field. See [`Mfrc522Minimal::is_card_present`].
    pub fn is_card_present(&mut self) -> Result<bool, SPI::Error> {
        self.inner.is_card_present()
    }

    /// Detect a card, run anticollision/Select (all cascade levels), and HLTA.
    pub fn read_uid(&mut self) -> Result<Option<([u8; 10], usize)>, SPI::Error> {
        self.inner.read_uid()
    }
}
