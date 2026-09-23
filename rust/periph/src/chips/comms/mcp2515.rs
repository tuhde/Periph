//! MCP2515 stand-alone CAN 2.0B controller (Microchip).
//!
//! Communicates over SPI (Mode 0 or Mode 3, up to 10 MHz, MSB first). Supports
//! both standard (11-bit) and extended (29-bit) identifiers, data frames of
//! 0–8 bytes, and CAN bus speeds up to 1 Mbit/s. The chip contains three TX
//! buffers, two RX buffers, six acceptance filters, and two acceptance masks.
//!
//! The driver is structured around [`MCP2515Minimal`], which owns the
//! [`SpiDevice`] and exposes the primary send/recv API plus the chip's full
//! init sequence. [`MCP2515Full`] wraps [`MCP2515Minimal`] via composition
//! (Rust has no inheritance) and adds operating-mode control, error-counter
//! access, acceptance filter/mask configuration, abort and one-shot support,
//! and explicit per-buffer TX selection. No register-level logic is
//! duplicated between Minimal and Full — Full reaches Minimal's `pub(crate)`
//! register helpers through a `pub(crate) fn base(&mut self)` accessor.
//!
//! Default configuration baked into Minimal:
//! - Accept-all filters (`RXM[1:0]=11` in `RXB0CTRL` and `RXB1CTRL`)
//! - `BUKT=1` in `RXB0CTRL` (RXB0 → RXB1 rollover)
//! - `CANINTE=0x00` (polled operation, INT pin not used)
//! - `OSM=0` (retransmit on error or loss of arbitration)
//! - TXB0/1/2 priority 3
//! - Bit timing: 125 kbit/s with 8 MHz oscillator (`CNF1=0x01`, `CNF2=0xBA`,
//!   `CNF3=0x03`); overridable via [`MCP2515Minimal::init`]
//!
//! Generic over [`SpiDevice`] from `embedded-hal` 1.0; chip drivers never
//! assert or deassert CS themselves — the `SpiDevice` implementation owns CS.

use embedded_hal::spi::{Operation, SpiDevice};

// SPI instruction set
const INSTR_RESET: u8        = 0xC0;
const INSTR_READ: u8         = 0x03;
const INSTR_READ_RX_BUF: u8  = 0x90;
const INSTR_WRITE: u8        = 0x02;
const INSTR_LOAD_TX_BUF: u8  = 0x40;
const INSTR_RTS: u8          = 0x80;
const INSTR_READ_STATUS: u8  = 0xA0;
const INSTR_RX_STATUS: u8    = 0xB0;
const INSTR_BIT_MODIFY: u8   = 0x05;

// Register addresses
const REG_CANSTAT:  u8 = 0x0E;
const REG_CANCTRL:  u8 = 0x0F;
const REG_CNF3:     u8 = 0x28;
const REG_CNF2:     u8 = 0x29;
const REG_CNF1:     u8 = 0x2A;
const REG_CANINTE:  u8 = 0x2B;
const REG_CANINTF:  u8 = 0x2C;
const REG_EFLG:     u8 = 0x2D;
const REG_TEC:      u8 = 0x1C;
const REG_REC:      u8 = 0x1D;
const REG_RXB0CTRL: u8 = 0x60;
const REG_RXB1CTRL: u8 = 0x70;
const REG_TXB0CTRL: u8 = 0x30;
const REG_TXB1CTRL: u8 = 0x40;
const REG_TXB2CTRL: u8 = 0x50;
const REG_RXM0SIDH: u8 = 0x20;
const REG_RXM1SIDH: u8 = 0x24;

// Bit masks
const CANSTAT_OPMOD_MASK:  u8 = 0xE0;
const CANCTRL_REQOP_MASK:  u8 = 0xE0;
const CANCTRL_ABAT:        u8 = 0x10;
const CANCTRL_OSM:         u8 = 0x08;
const TXBnCTRL_TXREQ:      u8 = 0x08;
const TXBnCTRL_TXP_MASK:   u8 = 0x03;
const RXB0CTRL_RXM_MASK:   u8 = 0x60;
const RXB0CTRL_RXM_ANY:    u8 = 0x60;
const RXB0CTRL_BUKT:       u8 = 0x04;
const CANINTF_RX0IF:       u8 = 0x01;
const CANINTF_RX1IF:       u8 = 0x02;
const EFLG_RX0OVR:         u8 = 0x40;
const EFLG_RX1OVR:         u8 = 0x80;

/// `OPMOD` value for Normal mode (`REQOP=000`).
pub const OPMOD_NORMAL: u8 = 0x00;
/// `OPMOD` value for Sleep mode (`REQOP=001`).
pub const OPMOD_SLEEP: u8 = 0x20;
/// `OPMOD` value for Loopback mode (`REQOP=010`).
pub const OPMOD_LOOPBACK: u8 = 0x40;
/// `OPMOD` value for Listen-Only mode (`REQOP=011`).
pub const OPMOD_LISTEN_ONLY: u8 = 0x60;
/// `OPMOD` value for Configuration mode (`REQOP=100`).
pub const OPMOD_CONFIG: u8 = 0x80;

/// A CAN 2.0B data or remote frame.
///
/// Fields:
/// - `id` — 11-bit (standard) or 29-bit (extended) identifier
/// - `data` — payload bytes, 0–8 bytes. Backing storage is a per-instance
///   8-byte buffer owned by the driver; copy out if you need to keep the
///   frame beyond the next call.
/// - `dlc` — data length code, 0–8
/// - `extended` — `true` for 29-bit extended-ID frames
/// - `rtr` — `true` for remote transmission request frames
pub struct CanFrame {
    pub id: u32,
    pub data: &'static [u8],
    pub dlc: u8,
    pub extended: bool,
    pub rtr: bool,
}

/// Shared static backing storage for received frame payloads.
///
/// Single-threaded drivers only — concurrent calls into `recv()` on the same
/// chip will overwrite each other's payload bytes. Matches the C++ driver's
/// `static uint8_t _rx_data_buf[8]` and Python's `self._rx_data_buf`.
static mut RX_DATA_BUF: [u8; 8] = [0u8; 8];

fn now_ms() -> u32 {
    #[cfg(feature = "std")]
    {
        use std::time::{SystemTime, UNIX_EPOCH};
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as u32)
            .unwrap_or(0)
    }
    #[cfg(not(feature = "std"))]
    0
}

fn delay_ms(ms: u32) {
    #[cfg(feature = "std")]
    std::thread::sleep(std::time::Duration::from_millis(ms as u64));
    #[cfg(not(feature = "std"))]
    let _ = ms;
}

/// MCP2515 minimal driver — send/recv with default configuration.
///
/// Runs the chip's full init sequence at construction: software reset,
/// Configuration mode, accept-all filters, RXB0→RXB1 rollover, polled
/// operation (no interrupts), TXB0/1/2 priority 3, Normal mode.
pub struct MCP2515Minimal<SPI: SpiDevice> {
    spi: SPI,
    bitrate_kbps: u16,
    osc_mhz: u8,
}

impl<SPI: SpiDevice> MCP2515Minimal<SPI> {
    /// Construct and initialise the MCP2515.
    ///
    /// # Arguments
    /// * `spi` — fully configured `SpiDevice` bound to the chip's CS pin.
    /// * `bitrate_kbps` — bus bitrate in kbit/s. Accepted: `125`, `250`, `500`,
    ///   `1000`. Default `125`.
    /// * `osc_mhz` — oscillator frequency in MHz. Accepted: `8`, `16`. Default `8`.
    pub fn new(spi: SPI, bitrate_kbps: u16, osc_mhz: u8) -> Result<Self, SPI::Error> {
        let mut s = Self {
            spi,
            bitrate_kbps,
            osc_mhz,
        };
        s.init(bitrate_kbps, osc_mhz)?;
        Ok(s)
    }

    /// Re-run the full init sequence with new bitrate/oscillator values.
    ///
    /// # Arguments
    /// * `bitrate_kbps` — bus bitrate in kbit/s (`125`/`250`/`500`/`1000`).
    /// * `osc_mhz` — oscillator frequency in MHz (`8`/`16`).
    pub fn init(&mut self, bitrate_kbps: u16, osc_mhz: u8) -> Result<(), SPI::Error> {
        self.bitrate_kbps = bitrate_kbps;
        self.osc_mhz = osc_mhz;

        self._reset()?;
        delay_ms(5);
        self._wait_op_mode(OPMOD_CONFIG, 100)?;

        let (cnf1, cnf2, cnf3) = Self::cnf_for(bitrate_kbps, osc_mhz);
        self._write_reg(REG_CNF1, cnf1)?;
        self._write_reg(REG_CNF2, cnf2)?;
        self._write_reg(REG_CNF3, cnf3)?;

        self._write_reg(REG_RXB0CTRL, RXB0CTRL_RXM_ANY | RXB0CTRL_BUKT)?;
        self._write_reg(REG_RXB1CTRL, RXB0CTRL_RXM_ANY)?;
        self._write_reg(REG_CANINTE, 0x00)?;

        self._write_reg(REG_TXB0CTRL, TXBnCTRL_TXP_MASK)?;
        self._write_reg(REG_TXB1CTRL, TXBnCTRL_TXP_MASK)?;
        self._write_reg(REG_TXB2CTRL, TXBnCTRL_TXP_MASK)?;

        self._modify_reg(REG_CANCTRL, CANCTRL_REQOP_MASK, OPMOD_NORMAL)?;
        self._wait_op_mode(OPMOD_NORMAL, 100)?;
        Ok(())
    }

    /// Send a CAN frame.
    ///
    /// Loads the next free TX buffer (TXB0 first, then TXB1, TXB2), issues
    /// RTS, and waits up to 1 s for `TXREQ` to clear. Always uses TXB0 if
    /// available; `MCP2515Full::send_buffered` allows explicit buffer selection.
    ///
    /// # Arguments
    /// * `id` — 11-bit (standard) or 29-bit (extended) identifier.
    /// * `data` — payload bytes, 0–8 bytes.
    /// * `extended` — `true` for 29-bit extended-ID frame.
    ///
    /// Returns the TX buffer index used (`0`/`1`/`2`), or `0xFF` if all
    /// buffers were busy within the 10 ms free-buf timeout.
    pub fn send(&mut self, id: u32, data: &[u8], extended: bool) -> Result<u8, SPI::Error> {
        self._send(id, data, extended, false, 0xFF)
    }

    /// Poll READ STATUS for a received frame.
    ///
    /// # Arguments
    /// * `timeout_ms` — `0` = non-blocking poll; otherwise block up to N ms.
    ///
    /// Returns `Some(CanFrame)` if a frame is available, `None` on timeout.
    /// The frame's `data` field points at a static 8-byte buffer owned by the
    /// driver; copy out if you need to keep it beyond the next call.
    pub fn recv(&mut self, timeout_ms: u32) -> Result<Option<CanFrame>, SPI::Error> {
        let start = now_ms();
        loop {
            let status = self._read_status()?;
            let offset: u8 = if status & CANINTF_RX0IF != 0 {
                0
            } else if status & CANINTF_RX1IF != 0 {
                4
            } else {
                0xFF
            };
            if offset != 0xFF {
                let cmd = [INSTR_READ_RX_BUF | offset];
                let mut buf = [0u8; 13];
                self.spi.transaction(&mut [
                    Operation::Write(&cmd),
                    Operation::Read(&mut buf),
                ])?;
                let sidh = buf[0];
                let sidl = buf[1];
                let eid8 = buf[2];
                let eid0 = buf[3];
                let dlc_byte = buf[4];
                let ide = (sidl & 0x08) != 0;
                let rtr = if ide { (sidl & 0x10) != 0 } else { (dlc_byte & 0x40) != 0 };
                let id = Self::_unpack_id(sidh, sidl, eid8, eid0, ide);
                let dlc = dlc_byte & 0x0F;
                let data_len = (dlc as usize).min(8);
                // SAFETY: single-threaded driver; copy out before next recv().
                let data_ptr = unsafe { RX_DATA_BUF.as_mut_ptr() };
                unsafe {
                    for i in 0..data_len {
                        *data_ptr.add(i) = buf[5 + i];
                    }
                }
                let data_slice = unsafe { core::slice::from_raw_parts(data_ptr, data_len) };
                let frame = CanFrame {
                    id,
                    data: data_slice,
                    dlc,
                    extended: ide,
                    rtr,
                };
                return Ok(Some(frame));
            }
            if timeout_ms == 0 {
                return Ok(None);
            }
            let elapsed = now_ms().wrapping_sub(start);
            if elapsed >= timeout_ms {
                return Ok(None);
            }
            delay_ms(1);
        }
    }

    // ---- Full-only helpers, reachable from MCP2515Full via `base()` ---------

    /// Pre-computed CNF1/CNF2/CNF3 register triplet for a given bitrate and
    /// oscillator. All values have `BTLMODE=1`, `SAM=0`, `SJW=1` TQ.
    pub(crate) fn cnf_for(bitrate_kbps: u16, osc_mhz: u8) -> (u8, u8, u8) {
        if osc_mhz == 8 {
            match bitrate_kbps {
                125  => (0x01, 0xBA, 0x03),
                250  => (0x00, 0xBA, 0x03),
                500  => (0x00, 0x91, 0x01),
                1000 => (0x00, 0x80, 0x00),
                _    => (0x01, 0xBA, 0x03),
            }
        } else {
            match bitrate_kbps {
                125  => (0x03, 0xBA, 0x03),
                250  => (0x01, 0xBA, 0x03),
                500  => (0x00, 0xBA, 0x03),
                1000 => (0x00, 0x91, 0x01),
                _    => (0x03, 0xBA, 0x03),
            }
        }
    }

    /// Pack a CAN ID into SIDH/SIDL/EID8/EID0 register bytes.
    pub(crate) fn _pack_id(id: u32, extended: bool) -> (u8, u8, u8, u8) {
        if extended {
            let sidh = ((id >> 21) & 0xFF) as u8;
            let sidl = (((id >> 18) & 0x07) << 5) as u8 | 0x08 | (((id >> 16) & 0x03) as u8);
            let eid8 = ((id >> 8) & 0xFF) as u8;
            let eid0 = (id & 0xFF) as u8;
            (sidh, sidl, eid8, eid0)
        } else {
            let sidh = ((id >> 3) & 0xFF) as u8;
            let sidl = ((id & 0x07) << 5) as u8;
            (sidh, sidl, 0, 0)
        }
    }

    /// Unpack a CAN ID from SIDH/SIDL/EID8/EID0 register bytes.
    pub(crate) fn _unpack_id(sidh: u8, sidl: u8, eid8: u8, eid0: u8, ide: bool) -> u32 {
        if ide {
            ((sidh as u32) << 21)
                | (((sidl >> 5) as u32) << 18)
                | (((sidl & 0x03) as u32) << 16)
                | ((eid8 as u32) << 8)
                | (eid0 as u32)
        } else {
            ((sidh as u32) << 3) | ((sidl >> 5) as u32)
        }
    }

    fn _send(&mut self, id: u32, data: &[u8], extended: bool, rtr: bool, buf_index: u8) -> Result<u8, SPI::Error> {
        let len = data.len().min(8);

        let buf_index = if buf_index == 0xFF {
            self._tx_free_buf(10)?
        } else {
            buf_index.min(2)
        };

        let (sidh, sidl, eid8, eid0) = Self::_pack_id(id, extended);
        let dlc_byte = (len as u8) | if rtr { 0x40 } else { 0x00 };
        let offset = match buf_index {
            0 => 0u8,
            1 => 2,
            _ => 4,
        };
        let instr = INSTR_LOAD_TX_BUF | offset;

        let mut payload = [0u8; 14];
        payload[0] = instr;
        payload[1] = sidh;
        payload[2] = sidl;
        payload[3] = eid8;
        payload[4] = eid0;
        payload[5] = dlc_byte;
        for i in 0..8 {
            payload[6 + i] = if i < len { data[i] } else { 0 };
        }
        self.spi.write(&payload)?;

        self._rts(1 << buf_index)?;

        let tx_ctrl_reg = match buf_index {
            0 => REG_TXB0CTRL,
            1 => REG_TXB1CTRL,
            _ => REG_TXB2CTRL,
        };
        let start = now_ms();
        while now_ms().wrapping_sub(start) < 1000 {
            let ctrl = self._read_reg(tx_ctrl_reg)?;
            if ctrl & TXBnCTRL_TXREQ == 0 {
                return Ok(buf_index);
            }
            delay_ms(1);
        }
        Ok(buf_index)
    }

    fn _tx_free_buf(&mut self, timeout_ms: u32) -> Result<u8, SPI::Error> {
        let start = now_ms();
        loop {
            let status = self._read_status()?;
            if status & 0x04 == 0 {
                return Ok(0);
            }
            if status & 0x10 == 0 {
                return Ok(1);
            }
            if status & 0x40 == 0 {
                return Ok(2);
            }
            if now_ms().wrapping_sub(start) >= timeout_ms {
                return Ok(0xFF);
            }
            delay_ms(1);
        }
    }

    pub(crate) fn _reset(&mut self) -> Result<(), SPI::Error> {
        self.spi.write(&[INSTR_RESET])
    }

    pub(crate) fn _write_reg(&mut self, reg: u8, value: u8) -> Result<(), SPI::Error> {
        self.spi.write(&[INSTR_WRITE, reg, value])
    }

    pub(crate) fn _read_reg(&mut self, reg: u8) -> Result<u8, SPI::Error> {
        let cmd = [INSTR_READ, reg];
        let mut val = [0u8; 1];
        self.spi.transaction(&mut [
            Operation::Write(&cmd),
            Operation::Read(&mut val),
        ])?;
        Ok(val[0])
    }

    pub(crate) fn _modify_reg(&mut self, reg: u8, mask: u8, value: u8) -> Result<(), SPI::Error> {
        self.spi.write(&[INSTR_BIT_MODIFY, reg, mask, value])
    }

    pub(crate) fn _read_status(&mut self) -> Result<u8, SPI::Error> {
        let cmd = [INSTR_READ_STATUS];
        let mut val = [0u8; 1];
        self.spi.transaction(&mut [
            Operation::Write(&cmd),
            Operation::Read(&mut val),
        ])?;
        Ok(val[0])
    }

    pub(crate) fn _rts(&mut self, mask: u8) -> Result<(), SPI::Error> {
        self.spi.write(&[INSTR_RTS | (mask & 0x07)])
    }

    pub(crate) fn _wait_op_mode(&mut self, target: u8, timeout_ms: u32) -> Result<(), SPI::Error> {
        let start = now_ms();
        while now_ms().wrapping_sub(start) < timeout_ms {
            if self._read_reg(REG_CANSTAT)? & CANSTAT_OPMOD_MASK == target & CANSTAT_OPMOD_MASK {
                return Ok(());
            }
            delay_ms(1);
        }
        Ok(())
    }

    pub(crate) fn _set_mode(&mut self, mode: u8) -> Result<(), SPI::Error> {
        self._modify_reg(REG_CANCTRL, CANCTRL_REQOP_MASK, mode & CANCTRL_REQOP_MASK)?;
        self._wait_op_mode(mode, 100)?;
        Ok(())
    }

    pub(crate) fn _get_mode(&mut self) -> Result<u8, SPI::Error> {
        Ok(self._read_reg(REG_CANSTAT)? & CANSTAT_OPMOD_MASK)
    }

    pub(crate) fn _set_filter(&mut self, filter_num: u8, id: u32, extended: bool) -> Result<(), SPI::Error> {
        if filter_num > 5 {
            return Ok(());
        }
        let (sidh, sidl, eid8, eid0) = Self::_pack_id(id, extended);
        let base = filter_num * 4;
        self._write_reg(base,     sidh)?;
        self._write_reg(base + 1, sidl)?;
        self._write_reg(base + 2, eid8)?;
        self._write_reg(base + 3, eid0)?;
        Ok(())
    }

    pub(crate) fn _set_mask(&mut self, mask_num: u8, mask: u32, extended: bool) -> Result<(), SPI::Error> {
        if mask_num > 1 {
            return Ok(());
        }
        let (sidh, sidl, eid8, eid0) = Self::_pack_id(mask, extended);
        let base = if mask_num == 0 { REG_RXM0SIDH } else { REG_RXM1SIDH };
        self._write_reg(base,     sidh)?;
        self._write_reg(base + 1, sidl)?;
        self._write_reg(base + 2, eid8)?;
        self._write_reg(base + 3, eid0)?;
        Ok(())
    }

    pub(crate) fn _set_rx_mode(&mut self, buf: u8, mode: u8) -> Result<(), SPI::Error> {
        let reg = if buf == 0 { REG_RXB0CTRL } else { REG_RXB1CTRL };
        self._modify_reg(reg, RXB0CTRL_RXM_MASK, (mode & 0x03) << 5)
    }

    pub(crate) fn _abort_tx(&mut self) -> Result<(), SPI::Error> {
        self._modify_reg(REG_CANCTRL, CANCTRL_ABAT, CANCTRL_ABAT)?;
        let start = now_ms();
        while now_ms().wrapping_sub(start) < 500 {
            if self._read_reg(REG_CANCTRL)? & CANCTRL_ABAT == 0 {
                return Ok(());
            }
            delay_ms(5);
        }
        Ok(())
    }

    pub(crate) fn _set_one_shot(&mut self, enable: bool) -> Result<(), SPI::Error> {
        self._modify_reg(REG_CANCTRL, CANCTRL_OSM, if enable { CANCTRL_OSM } else { 0x00 })
    }

    pub(crate) fn _clear_overflow(&mut self, buf: u8) -> Result<(), SPI::Error> {
        let bit = if buf == 0 { EFLG_RX0OVR } else { EFLG_RX1OVR };
        self._modify_reg(REG_EFLG, bit, 0x00)
    }

    /// Access the shared register-level base. `pub(crate)` so only
    /// [`MCP2515Full`] (in this module) can reach the Full-only
    /// `pub(crate)` methods on it.
    pub(crate) fn base(&mut self) -> &mut Self {
        self
    }
}

/// MCP2515 full driver — adds mode/filter/error-counter methods.
///
/// Wraps [`MCP2515Minimal`] via composition and re-exposes its register-level
/// helpers (made `pub(crate)`) through a `base()` accessor. No register logic
/// is duplicated.
pub struct MCP2515Full<SPI: SpiDevice> {
    inner: MCP2515Minimal<SPI>,
}

impl<SPI: SpiDevice> MCP2515Full<SPI> {
    /// Construct and initialise the MCP2515.
    ///
    /// # Arguments
    /// * `spi` — fully configured `SpiDevice` bound to the chip's CS pin.
    /// * `bitrate_kbps` — bus bitrate in kbit/s (`125`/`250`/`500`/`1000`).
    /// * `osc_mhz` — oscillator frequency in MHz (`8`/`16`).
    pub fn new(spi: SPI, bitrate_kbps: u16, osc_mhz: u8) -> Result<Self, SPI::Error> {
        Ok(Self { inner: MCP2515Minimal::new(spi, bitrate_kbps, osc_mhz)? })
    }

    /// Re-run the full init sequence with new bitrate/oscillator values.
    pub fn init(&mut self, bitrate_kbps: u16, osc_mhz: u8) -> Result<(), SPI::Error> {
        self.inner.init(bitrate_kbps, osc_mhz)
    }

    /// Send a CAN frame on the next free TX buffer.
    pub fn send(&mut self, id: u32, data: &[u8], extended: bool) -> Result<u8, SPI::Error> {
        self.inner.send(id, data, extended)
    }

    /// Poll READ STATUS for a received frame.
    pub fn recv(&mut self, timeout_ms: u32) -> Result<Option<CanFrame>, SPI::Error> {
        self.inner.recv(timeout_ms)
    }

    /// Send a CAN frame on a specific TX buffer (0/1/2).
    ///
    /// # Arguments
    /// * `id` — 11-bit (standard) or 29-bit (extended) identifier.
    /// * `data` — payload bytes, 0–8 bytes.
    /// * `extended` — `true` for 29-bit extended-ID frame.
    /// * `buf` — TX buffer index `0`/`1`/`2`.
    pub fn send_buffered(&mut self, id: u32, data: &[u8], extended: bool, buf: u8) -> Result<u8, SPI::Error> {
        self.inner.base()._send(id, data, extended, false, buf.min(2))
    }

    /// Configure an acceptance filter.
    ///
    /// # Arguments
    /// * `filter_num` — filter index `0`–`5`.
    /// * `id` — identifier value to match.
    /// * `extended` — `true` for 29-bit extended-ID filter.
    pub fn set_filter(&mut self, filter_num: u8, id: u32, extended: bool) -> Result<(), SPI::Error> {
        let prev = self.inner._get_mode()?;
        if prev != OPMOD_CONFIG {
            self.inner._set_mode(OPMOD_CONFIG)?;
            self.inner.base()._set_filter(filter_num, id, extended)?;
            if prev != OPMOD_CONFIG {
                self.inner._set_mode(prev)?;
            }
        } else {
            self.inner.base()._set_filter(filter_num, id, extended)?;
        }
        Ok(())
    }

    /// Configure an acceptance mask.
    ///
    /// # Arguments
    /// * `mask_num` — `0` (RXB0; filters 0–1) or `1` (RXB1; filters 2–5).
    /// * `mask` — mask value; mask bit = 1 means the filter bit must match.
    /// * `extended` — `true` for 29-bit extended-ID mask.
    pub fn set_mask(&mut self, mask_num: u8, mask: u32, extended: bool) -> Result<(), SPI::Error> {
        let prev = self.inner._get_mode()?;
        if prev != OPMOD_CONFIG {
            self.inner._set_mode(OPMOD_CONFIG)?;
            self.inner.base()._set_mask(mask_num, mask, extended)?;
            if prev != OPMOD_CONFIG {
                self.inner._set_mode(prev)?;
            }
        } else {
            self.inner.base()._set_mask(mask_num, mask, extended)?;
        }
        Ok(())
    }

    /// Set `RXM[1:0]` for the given RX buffer.
    ///
    /// # Arguments
    /// * `buf` — RX buffer index `0` or `1`.
    /// * `mode` — `0` = standard filter, `1` = extended filter, `3` = accept all.
    pub fn set_rx_mode(&mut self, buf: u8, mode: u8) -> Result<(), SPI::Error> {
        self.inner._set_rx_mode(buf, mode)
    }

    /// Switch operating mode and wait until `CANSTAT.OPMOD` confirms.
    ///
    /// # Arguments
    /// * `mode` — one of [`OPMOD_NORMAL`], [`OPMOD_SLEEP`], [`OPMOD_LOOPBACK`],
    ///   [`OPMOD_LISTEN_ONLY`], [`OPMOD_CONFIG`].
    pub fn set_mode(&mut self, mode: u8) -> Result<(), SPI::Error> {
        self.inner._set_mode(mode)
    }

    /// Read the current operating mode from `CANSTAT.OPMOD`.
    ///
    /// Returns one of [`OPMOD_NORMAL`], [`OPMOD_SLEEP`], [`OPMOD_LOOPBACK`],
    /// [`OPMOD_LISTEN_ONLY`], [`OPMOD_CONFIG`].
    pub fn get_mode(&mut self) -> Result<u8, SPI::Error> {
        self.inner._get_mode()
    }

    /// Issue a SPI RESET command; chip returns to Configuration mode.
    ///
    /// After a reset, all configuration (bit timing, filters, masks, operating
    /// mode) is back to POR defaults. Re-run [`Self::init`] to set the
    /// configuration again.
    pub fn reset(&mut self) -> Result<(), SPI::Error> {
        self.inner._reset()
    }

    /// Read TEC, REC, and EFLG register values.
    ///
    /// Returns `(tec, rec, eflg)`.
    pub fn read_errors(&mut self) -> Result<(u8, u8, u8), SPI::Error> {
        let tec = self.inner._read_reg(REG_TEC)?;
        let rec = self.inner._read_reg(REG_REC)?;
        let eflg = self.inner._read_reg(REG_EFLG)?;
        Ok((tec, rec, eflg))
    }

    /// Clear the `RX0OVR` or `RX1OVR` flag in `EFLG`.
    ///
    /// # Arguments
    /// * `buf` — RX buffer `0` or `1`.
    pub fn clear_overflow(&mut self, buf: u8) -> Result<(), SPI::Error> {
        self.inner._clear_overflow(buf)
    }

    /// Set `ABAT` in `CANCTRL`; poll until cleared by hardware.
    pub fn abort_tx(&mut self) -> Result<(), SPI::Error> {
        self.inner._abort_tx()
    }

    /// Enable or disable one-shot mode (`OSM` in `CANCTRL`).
    ///
    /// In one-shot mode the chip does not retransmit on error or loss of
    /// arbitration.
    pub fn set_one_shot(&mut self, enable: bool) -> Result<(), SPI::Error> {
        self.inner._set_one_shot(enable)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::spi::{Mock as SpiMock, Transaction as SpiTransaction};

    // The mock distinguishes:
    //   spi.write(&buf)        -> matches [transaction_start, write_vec, transaction_end]
    //                             (SpiDevice::write is a one-operation transaction)
    //   spi.transaction(&[..]) -> matches [transaction_start, write_vec|read_vec|..., transaction_end]
    //
    // MCP2515 helpers that use spi.write (RESET, _write_reg, _modify_reg, _rts,
    // LOAD TX BUFFER) lower to a [start, write, end] triple. MCP2515 helpers that
    // use spi.transaction with Op::Write + Op::Read (_read_reg, _read_status,
    // READ RX BUFFER) lower to a triple: [start, write, read, end].

    fn wr(write: Vec<u8>, read: Vec<u8>) -> Vec<SpiTransaction<u8>> {
        vec![
            SpiTransaction::transaction_start(),
            SpiTransaction::write_vec(write),
            SpiTransaction::read_vec(read),
            SpiTransaction::transaction_end(),
        ]
    }

    fn w_only(bytes: Vec<u8>) -> Vec<SpiTransaction<u8>> {
        vec![
            SpiTransaction::transaction_start(),
            SpiTransaction::write_vec(bytes),
            SpiTransaction::transaction_end(),
        ]
    }

    // Init sequence that `new()` issues: RESET + wait(CANSTAT=CONFIG) +
    // CNF1/2/3 + RXB0/1CTRL + CANINTE + TXBnCTRL + BIT MODIFY REQOP=NORMAL
    // + wait(CANSTAT=NORMAL). `_wait_op_mode` returns on the first poll that
    // matches, so each wait only does one CANSTAT read.
    fn init_seq() -> Vec<SpiTransaction<u8>> {
        let mut v = Vec::new();
        v.extend(w_only(vec![INSTR_RESET]));
        v.extend(wr(vec![INSTR_READ, REG_CANSTAT], vec![OPMOD_CONFIG]));
        v.extend(w_only(vec![INSTR_WRITE, REG_CNF1, 0x01]));
        v.extend(w_only(vec![INSTR_WRITE, REG_CNF2, 0xBA]));
        v.extend(w_only(vec![INSTR_WRITE, REG_CNF3, 0x03]));
        v.extend(w_only(vec![INSTR_WRITE, REG_RXB0CTRL, RXB0CTRL_RXM_ANY | RXB0CTRL_BUKT]));
        v.extend(w_only(vec![INSTR_WRITE, REG_RXB1CTRL, RXB0CTRL_RXM_ANY]));
        v.extend(w_only(vec![INSTR_WRITE, REG_CANINTE, 0x00]));
        v.extend(w_only(vec![INSTR_WRITE, REG_TXB0CTRL, TXBnCTRL_TXP_MASK]));
        v.extend(w_only(vec![INSTR_WRITE, REG_TXB1CTRL, TXBnCTRL_TXP_MASK]));
        v.extend(w_only(vec![INSTR_WRITE, REG_TXB2CTRL, TXBnCTRL_TXP_MASK]));
        v.extend(w_only(vec![INSTR_BIT_MODIFY, REG_CANCTRL, CANCTRL_REQOP_MASK, OPMOD_NORMAL]));
        v.extend(wr(vec![INSTR_READ, REG_CANSTAT], vec![OPMOD_NORMAL]));
        v
    }

    #[test]
    fn new_runs_init_sequence() {
        let spi = SpiMock::new(&init_seq());
        let mut chip = MCP2515Minimal::new(spi, 125, 8).expect("init");
        chip.spi.done();
    }

    #[test]
    fn send_standard_uses_txb0_load_and_rts() {
        let mut v = init_seq();
        // _tx_free_buf: _read_status -> 0x00 (TXB0 free: status bit 2 = 0)
        v.extend(wr(vec![INSTR_READ_STATUS], vec![0x00]));
        // LOAD TX BUFFER TXB0: id=0x123 (std), len=4, data=[0xDE,0xAD,0xBE,0xEF]
        //   sidh = 0x24, sidl = 0x60, eid8/eid0 = 0, dlc = 0x04
        v.extend(w_only(vec![
            INSTR_LOAD_TX_BUF | 0,
            0x24, 0x60, 0x00, 0x00, 0x04,
            0xDE, 0xAD, 0xBE, 0xEF,
            0x00, 0x00, 0x00, 0x00,
        ]));
        // RTS: write [INSTR_RTS | 0x01]
        v.extend(w_only(vec![INSTR_RTS | 0x01]));
        // wait TXREQ clear: _read_reg(TXB0CTRL) -> 0x00
        v.extend(wr(vec![INSTR_READ, REG_TXB0CTRL], vec![0x00]));
        let spi = SpiMock::new(&v);
        let mut chip = MCP2515Minimal::new(spi, 125, 8).expect("init");
        let buf = chip.send(0x123, &[0xDE, 0xAD, 0xBE, 0xEF], false).expect("send");
        assert_eq!(buf, 0);
        chip.spi.done();
    }

    #[test]
    fn send_extended_sets_exide() {
        let mut v = init_seq();
        v.extend(wr(vec![INSTR_READ_STATUS], vec![0x00]));
        // id=0x1ABCDEF0 extended, len=0:
        //   sidh = 0xD5, sidl = 0xE8 (SID[2:0]=111, EXIDE bit 3 set), eid8=0xDE, eid0=0xF0, dlc=0x00
        v.extend(w_only(vec![
            INSTR_LOAD_TX_BUF | 0,
            0xD5, 0xE8, 0xDE, 0xF0, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        ]));
        v.extend(w_only(vec![INSTR_RTS | 0x01]));
        v.extend(wr(vec![INSTR_READ, REG_TXB0CTRL], vec![0x00]));
        let spi = SpiMock::new(&v);
        let mut chip = MCP2515Minimal::new(spi, 125, 8).expect("init");
        let buf = chip.send(0x1ABCDEF0, &[], true).expect("send");
        assert_eq!(buf, 0);
        chip.spi.done();
    }

    #[test]
    fn recv_returns_none_when_no_frame() {
        let mut v = init_seq();
        // _read_status -> 0x00 (no RX0IF, no RX1IF)
        v.extend(wr(vec![INSTR_READ_STATUS], vec![0x00]));
        let spi = SpiMock::new(&v);
        let mut chip = MCP2515Minimal::new(spi, 125, 8).expect("init");
        let frame = chip.recv(0).expect("recv");
        assert!(frame.is_none());
        chip.spi.done();
    }

    #[test]
    fn recv_parses_standard_frame_from_rxb0() {
        let mut v = init_seq();
        // _read_status -> RX0IF set (status bit 0)
        v.extend(wr(vec![INSTR_READ_STATUS], vec![0x01]));
        // READ RX BUFFER RXB0 (offset 0): id=0x42 std, dlc=3, data=[0x11,0x22,0x33]
        //   sidh=0x08, sidl=0x40, eid8=0, eid0=0, dlc=0x03
        v.extend(wr(
            vec![INSTR_READ_RX_BUF | 0],
            vec![0x08, 0x40, 0x00, 0x00, 0x03, 0x11, 0x22, 0x33, 0x00, 0x00, 0x00, 0x00, 0x00],
        ));
        let spi = SpiMock::new(&v);
        let mut chip = MCP2515Minimal::new(spi, 125, 8).expect("init");
        let frame = chip.recv(0).expect("recv").expect("frame");
        assert_eq!(frame.id, 0x42);
        assert_eq!(frame.dlc, 3);
        assert!(!frame.extended);
        assert!(!frame.rtr);
        assert_eq!(&frame.data[..3], &[0x11, 0x22, 0x33]);
        chip.spi.done();
    }

    #[test]
    fn recv_parses_extended_frame_from_rxb1() {
        let mut v = init_seq();
        // _read_status -> RX1IF set (status bit 1)
        v.extend(wr(vec![INSTR_READ_STATUS], vec![0x02]));
        // READ RX BUFFER RXB1 (offset 4): id=0x1FFFFFFF ext, dlc=2, data=[0xAA,0xBB]
        //   sidh=0xFF, sidl=0xEB (EXIDE bit set), eid8=0xFF, eid0=0xFF, dlc=0x02
        v.extend(wr(
            vec![INSTR_READ_RX_BUF | 4],
            vec![0xFF, 0xEB, 0xFF, 0xFF, 0x02, 0xAA, 0xBB, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00],
        ));
        let spi = SpiMock::new(&v);
        let mut chip = MCP2515Minimal::new(spi, 125, 8).expect("init");
        let frame = chip.recv(0).expect("recv").expect("frame");
        assert_eq!(frame.id, 0x1FFFFFFF);
        assert_eq!(frame.dlc, 2);
        assert!(frame.extended);
        assert!(!frame.rtr);
        assert_eq!(&frame.data[..2], &[0xAA, 0xBB]);
        chip.spi.done();
    }

    #[test]
    fn set_filter_writes_four_registers() {
        let mut v = init_seq();
        // Full::set_filter: _get_mode -> _set_mode(CONFIG) -> _set_filter -> _set_mode(prev)
        // _get_mode: read CANSTAT, response OPMOD=NORMAL
        v.extend(wr(vec![INSTR_READ, REG_CANSTAT], vec![OPMOD_NORMAL]));
        // _set_mode(CONFIG): BIT MODIFY REQOP=0x80
        v.extend(w_only(vec![INSTR_BIT_MODIFY, REG_CANCTRL, CANCTRL_REQOP_MASK, OPMOD_CONFIG]));
        // _wait_op_mode(CONFIG): read CANSTAT -> 0x80
        v.extend(wr(vec![INSTR_READ, REG_CANSTAT], vec![OPMOD_CONFIG]));
        // _set_filter(2, 0x100, std): 4 writes to base = 8
        //   sidh = 0x100 >> 3 = 0x20, sidl = 0x00
        v.extend(w_only(vec![INSTR_WRITE, 8, 0x20]));
        v.extend(w_only(vec![INSTR_WRITE, 9, 0x00]));
        v.extend(w_only(vec![INSTR_WRITE, 10, 0x00]));
        v.extend(w_only(vec![INSTR_WRITE, 11, 0x00]));
        // restore mode (NORMAL): BIT MODIFY + wait CANSTAT=NORMAL
        v.extend(w_only(vec![INSTR_BIT_MODIFY, REG_CANCTRL, CANCTRL_REQOP_MASK, OPMOD_NORMAL]));
        v.extend(wr(vec![INSTR_READ, REG_CANSTAT], vec![OPMOD_NORMAL]));
        let spi = SpiMock::new(&v);
        let mut chip = MCP2515Full::new(spi, 125, 8).expect("init");
        chip.set_filter(2, 0x100, false).expect("set_filter");
        chip.inner.spi.done();
    }

    #[test]
    fn set_mask_writes_four_registers_at_rxm_base() {
        let mut v = init_seq();
        v.extend(wr(vec![INSTR_READ, REG_CANSTAT], vec![OPMOD_NORMAL]));
        v.extend(w_only(vec![INSTR_BIT_MODIFY, REG_CANCTRL, CANCTRL_REQOP_MASK, OPMOD_CONFIG]));
        v.extend(wr(vec![INSTR_READ, REG_CANSTAT], vec![OPMOD_CONFIG]));
        // _set_mask(1, 0x7FF, std): base = REG_RXM1SIDH = 0x24
        //   sidh = 0xFF, sidl = 0xE0
        v.extend(w_only(vec![INSTR_WRITE, 0x24, 0xFF]));
        v.extend(w_only(vec![INSTR_WRITE, 0x25, 0xE0]));
        v.extend(w_only(vec![INSTR_WRITE, 0x26, 0x00]));
        v.extend(w_only(vec![INSTR_WRITE, 0x27, 0x00]));
        v.extend(w_only(vec![INSTR_BIT_MODIFY, REG_CANCTRL, CANCTRL_REQOP_MASK, OPMOD_NORMAL]));
        v.extend(wr(vec![INSTR_READ, REG_CANSTAT], vec![OPMOD_NORMAL]));
        let spi = SpiMock::new(&v);
        let mut chip = MCP2515Full::new(spi, 125, 8).expect("init");
        chip.set_mask(1, 0x7FF, false).expect("set_mask");
        chip.inner.spi.done();
    }

    #[test]
    fn set_rx_mode_uses_bit_modify_on_rxb0ctrl() {
        let mut v = init_seq();
        v.extend(w_only(vec![INSTR_BIT_MODIFY, REG_RXB0CTRL, RXB0CTRL_RXM_MASK, 0x00]));
        let spi = SpiMock::new(&v);
        let mut chip = MCP2515Full::new(spi, 125, 8).expect("init");
        chip.inner._set_rx_mode(0, 0).expect("set_rx_mode");
        chip.inner.spi.done();
    }

    #[test]
    fn set_one_shot_toggles_osm() {
        let mut v = init_seq();
        v.extend(w_only(vec![INSTR_BIT_MODIFY, REG_CANCTRL, CANCTRL_OSM, CANCTRL_OSM]));
        v.extend(w_only(vec![INSTR_BIT_MODIFY, REG_CANCTRL, CANCTRL_OSM, 0x00]));
        let spi = SpiMock::new(&v);
        let mut chip = MCP2515Full::new(spi, 125, 8).expect("init");
        chip.set_one_shot(true).expect("set_one_shot(true)");
        chip.set_one_shot(false).expect("set_one_shot(false)");
        chip.inner.spi.done();
    }

    #[test]
    fn clear_overflow_writes_modify_eflg() {
        let mut v = init_seq();
        v.extend(w_only(vec![INSTR_BIT_MODIFY, REG_EFLG, EFLG_RX0OVR, 0x00]));
        v.extend(w_only(vec![INSTR_BIT_MODIFY, REG_EFLG, EFLG_RX1OVR, 0x00]));
        let spi = SpiMock::new(&v);
        let mut chip = MCP2515Full::new(spi, 125, 8).expect("init");
        chip.clear_overflow(0).expect("clear_overflow(0)");
        chip.clear_overflow(1).expect("clear_overflow(1)");
        chip.inner.spi.done();
    }

    #[test]
    fn pack_unpack_id_roundtrip_standard() {
        let (sidh, sidl, eid8, eid0) = MCP2515Minimal::<SpiMock<u8>>::_pack_id(0x123, false);
        assert_eq!(sidh, 0x24);
        assert_eq!(sidl, 0x60);
        assert_eq!(eid8, 0);
        assert_eq!(eid0, 0);
        let id = MCP2515Minimal::<SpiMock<u8>>::_unpack_id(sidh, sidl, eid8, eid0, false);
        assert_eq!(id, 0x123);
    }

    #[test]
    fn pack_unpack_id_roundtrip_extended() {
        let (sidh, sidl, eid8, eid0) = MCP2515Minimal::<SpiMock<u8>>::_pack_id(0x1ABCDEF0, true);
        assert_eq!(sidh, 0xD5);
        assert_eq!(sidl, 0xE8); // SID[2:0]=111 <<5 | EXIDE | EID[17:16]=00
        assert_eq!(eid8, 0xDE);
        assert_eq!(eid0, 0xF0);
        let id = MCP2515Minimal::<SpiMock<u8>>::_unpack_id(sidh, sidl, eid8, eid0, true);
        assert_eq!(id, 0x1ABCDEF0);
    }
}
