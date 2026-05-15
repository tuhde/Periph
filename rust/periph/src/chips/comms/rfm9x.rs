//! RFM9x (RFM95/96/97/98W) LoRa transceiver driver.
//!
//! Generic over embedded-hal 1.0 `SpiDevice` trait.

use embedded_hal::spi::SpiDevice;
use core::fmt::Write;

/// RFM9x base driver — all register logic and LoRa mode initialization.
pub struct RFM9xBase<SPI> {
    spi: SPI,
    frequency_hz: u32,
    sf: u8,
    crc: bool,
    lf_band: bool,
}

impl<SPI: SpiDevice> RFM9xBase<SPI> {
    const REG_FIFO: u8 = 0x00;
    const REG_OP_MODE: u8 = 0x01;
    const REG_FRF_MSB: u8 = 0x06;
    const REG_FRF_MID: u8 = 0x07;
    const REG_FRF_LSB: u8 = 0x08;
    const REG_PA_CONFIG: u8 = 0x09;
    const REG_OCP: u8 = 0x0B;
    const REG_LNA: u8 = 0x0C;
    const REG_FIFO_ADDR_PTR: u8 = 0x0D;
    const REG_FIFO_TX_BASE_ADDR: u8 = 0x0E;
    const REG_FIFO_RX_BASE_ADDR: u8 = 0x0F;
    const REG_FIFO_RX_CURRENT_ADDR: u8 = 0x10;
    const REG_IRQ_FLAGS: u8 = 0x12;
    const REG_RX_NB_BYTES: u8 = 0x13;
    const REG_PKT_SNR_VALUE: u8 = 0x19;
    const REG_PKT_RSSI_VALUE: u8 = 0x1A;
    const REG_RSSI_VALUE: u8 = 0x1B;
    const REG_MODEM_CONFIG1: u8 = 0x1D;
    const REG_MODEM_CONFIG2: u8 = 0x1E;
    const REG_MODEM_CONFIG3: u8 = 0x26;
    const REG_VERSION: u8 = 0x42;
    const REG_PA_DAC: u8 = 0x4D;

    const FXOSC: u32 = 32_000_000;

    const MODE_SLEEP: u8 = 0;
    const MODE_STDBY: u8 = 1;
    const MODE_TX: u8 = 3;
    const MODE_RXSINGLE: u8 = 6;
    const MODE_RXCONTINUOUS: u8 = 5;

    const IRQ_TX_DONE: u8 = 0x08;
    const IRQ_RX_DONE: u8 = 0x40;
    const IRQ_RX_TIMEOUT: u8 = 0x80;

    /// Construct and initialise the RFM9x.
    ///
    /// # Arguments
    /// * `spi` — SPI bus (takes ownership).
    /// * `frequency_hz` — Carrier frequency in Hz.
    /// * `lf_band` — true for LF band (RFM96/98), false for HF band (RFM95/97).
    pub fn new(spi: SPI, frequency_hz: u32, lf_band: bool) -> Result<Self, SPI::Error> {
        let mut this = Self { spi, frequency_hz, sf: 7, crc: true, lf_band };
        this._init()?;
        Ok(this)
    }

    fn _write_reg(&mut self, reg: u8, value: u8) -> Result<(), SPI::Error> {
        let cmd = [reg | 0x80, value];
        self.spi.write(&cmd)
    }

    fn _read_reg(&mut self, reg: u8) -> Result<u8, SPI::Error> {
        let mut buf = [0u8; 2];
        self.spi.write_read(&[reg & 0x7F], &mut buf)?;
        Ok((buf[0] << 8) | buf[1])
    }

    fn _set_mode(&mut self, mode: u8) -> Result<(), SPI::Error> {
        let current = self._read_reg(Self::REG_OP_MODE)?;
        let new_val = (current & 0xF8) | (mode & 0x07);
        self._write_reg(Self::REG_OP_MODE, new_val)
    }

    fn _init(&mut self) -> Result<(), SPI::Error> {
        self.spi.write(&[Self::REG_OP_MODE, 0x00])?;
        self.spi.write(&[Self::REG_OP_MODE, 0x80])?;
        self._set_mode(Self::MODE_SLEEP)?;
        let op_mode = 0x80 | if self.lf_band { 0x01 } else { 0x00 };
        self._write_reg(Self::REG_OP_MODE, op_mode)?;
        self._write_reg(Self::REG_FIFO_TX_BASE_ADDR, 0x80)?;
        self._write_reg(Self::REG_FIFO_RX_BASE_ADDR, 0x00)?;
        if !self.lf_band {
            self._write_reg(Self::REG_LNA, 0x23)?;
        }
        self._write_reg(Self::REG_MODEM_CONFIG3, 0x04)?;
        self._set_frequency(self.frequency_hz)?;
        self._write_reg(Self::REG_MODEM_CONFIG1, 0x72)?;
        self._write_reg(Self::REG_MODEM_CONFIG2, (7 << 4) | 0x04)?;
        self._set_mode(Self::MODE_STDBY)?;
        Ok(())
    }

    fn _set_frequency(&mut self, frequency_hz: u32) -> Result<(), SPI::Error> {
        let frf = (frequency_hz as u64 * 524_288 / Self::FXOSC as u64) as u32;
        self._write_reg(Self::REG_FRF_MSB, (frf >> 16) as u8)?;
        self._write_reg(Self::REG_FRF_MID, (frf >> 8) as u8)?;
        self._write_reg(Self::REG_FRF_LSB, frf as u8)
    }

    fn _poll_irq(&mut self, irq_mask: u8, timeout_ms: u32) -> Result<bool, SPI::Error> {
        for _ in 0..timeout_ms {
            let flags = self._read_reg(Self::REG_IRQ_FLAGS)?;
            if flags & irq_mask != 0 {
                return Ok(true);
            }
        }
        Ok(false)
    }

    /// Transmit a packet.
    ///
    /// # Arguments
    /// * `data` — Bytes to transmit (max 255 bytes).
    pub fn send(&mut self, data: &[u8]) -> Result<(), SPI::Error> {
        if data.len() > 255 {
            return Ok(());
        }
        self._set_mode(Self::MODE_STDBY)?;
        self._write_reg(Self::REG_IRQ_FLAGS, 0xFF)?;
        self._write_reg(Self::REG_FIFO_ADDR_PTR, 0x80)?;
        let mut fifo_data = Vec::with_capacity(data.len() + 1);
        fifo_data.push(data.len() as u8);
        fifo_data.extend_from_slice(data);
        self.spi.write(&fifo_data)?;
        self._set_mode(Self::MODE_TX)?;
        let _ = self._poll_irq(Self::IRQ_TX_DONE, 10_000);
        self._write_reg(Self::REG_IRQ_FLAGS, Self::IRQ_TX_DONE)?;
        self._set_mode(Self::MODE_STDBY)?;
        Ok(())
    }

    /// Receive a packet (single shot).
    ///
    /// Returns received bytes or None on timeout.
    ///
    /// # Arguments
    /// * `timeout_ms` — Timeout in milliseconds (default 2000).
    pub fn receive(&mut self, timeout_ms: u32) -> Result<Option<Vec<u8>>, SPI::Error> {
        self._set_mode(Self::MODE_STDBY)?;
        self._write_reg(Self::REG_IRQ_FLAGS, 0xFF)?;
        self._write_reg(Self::REG_FIFO_RX_CURRENT_ADDR, 0x00)?;
        self._set_mode(Self::MODE_RXSINGLE)?;
        if !self._poll_irq(Self::IRQ_TX_DONE | Self::IRQ_RX_TIMEOUT, timeout_ms)? {
            self._set_mode(Self::MODE_STDBY)?;
            return Ok(None);
        }
        let flags = self._read_reg(Self::REG_IRQ_FLAGS)?;
        self._write_reg(Self::REG_IRQ_FLAGS, 0xFF)?;
        if flags & Self::IRQ_RX_TIMEOUT != 0 {
            self._set_mode(Self::MODE_STDBY)?;
            return Ok(None);
        }
        if flags & Self::IRQ_RX_DONE == 0 {
            return Ok(None);
        }
        let nb_bytes = self._read_reg(Self::REG_RX_NB_BYTES)?;
        self._write_reg(Self::REG_FIFO_ADDR_PTR, self._read_reg(Self::REG_FIFO_RX_CURRENT_ADDR)?)?;
        let mut fifo_buf = vec![0u8; nb_bytes as usize];
        self.spi.write_read(&[Self::REG_FIFO], &mut fifo_buf)?;
        self._set_mode(Self::MODE_STDBY)?;
        Ok(Some(fifo_buf))
    }

    /// Read silicon revision (expect 0x12 for SX1276).
    pub fn version(&mut self) -> Result<u8, SPI::Error> {
        Ok(self._read_reg(Self::REG_VERSION)?)
    }

    /// Enter STANDBY mode.
    pub fn standby(&mut self) -> Result<(), SPI::Error> {
        self._set_mode(Self::MODE_STDBY)
    }

    /// Enter SLEEP mode.
    pub fn sleep(&mut self) -> Result<(), SPI::Error> {
        self._set_mode(Self::MODE_SLEEP)
    }
}

/// RFM95W — 868 / 915 MHz HF band, max SF 12.
pub struct RFM95<SPI> { inner: RFM9xBase<SPI> }

impl<SPI: SpiDevice> RFM95<SPI> {
    const FREQ_MIN_HZ: u32 = 862_000_000;
    const FREQ_MAX_HZ: u32 = 1_020_000_000;
    const MAX_SF: u8 = 12;
    const LF_BAND: bool = false;

    /// Construct and initialise the RFM95W at the given frequency.
    ///
    /// # Arguments
    /// * `spi` — SPI bus (takes ownership).
    /// * `frequency_hz` — Carrier frequency in Hz (862 MHz – 1.02 GHz).
    pub fn new(spi: SPI, frequency_hz: u32) -> Result<Self, SPI::Error> {
        let inner = RFM9xBase::new(spi, frequency_hz, Self::LF_BAND)?;
        Ok(Self { inner })
    }

    pub fn send(&mut self, data: &[u8]) -> Result<(), SPI::Error> { self.inner.send(data) }
    pub fn receive(&mut self, timeout_ms: u32) -> Result<Option<Vec<u8>>, SPI::Error> { self.inner.receive(timeout_ms) }
    pub fn version(&mut self) -> Result<u8, SPI::Error> { self.inner.version() }
    pub fn standby(&mut self) -> Result<(), SPI::Error> { self.inner.standby() }
    pub fn sleep(&mut self) -> Result<(), SPI::Error> { self.inner.sleep() }
}

/// RFM96W — 433 / 470 MHz LF band, max SF 12.
pub struct RFM96<SPI> { inner: RFM9xBase<SPI> }

impl<SPI: SpiDevice> RFM96<SPI> {
    const FREQ_MIN_HZ: u32 = 410_000_000;
    const FREQ_MAX_HZ: u32 = 525_000_000;
    const MAX_SF: u8 = 12;
    const LF_BAND: bool = true;

    /// Construct and initialise the RFM96W at the given frequency.
    ///
    /// # Arguments
    /// * `spi` — SPI bus (takes ownership).
    /// * `frequency_hz` — Carrier frequency in Hz (410 – 525 MHz).
    pub fn new(spi: SPI, frequency_hz: u32) -> Result<Self, SPI::Error> {
        let inner = RFM9xBase::new(spi, frequency_hz, Self::LF_BAND)?;
        Ok(Self { inner })
    }

    pub fn send(&mut self, data: &[u8]) -> Result<(), SPI::Error> { self.inner.send(data) }
    pub fn receive(&mut self, timeout_ms: u32) -> Result<Option<Vec<u8>>, SPI::Error> { self.inner.receive(timeout_ms) }
    pub fn version(&mut self) -> Result<u8, SPI::Error> { self.inner.version() }
    pub fn standby(&mut self) -> Result<(), SPI::Error> { self.inner.standby() }
    pub fn sleep(&mut self) -> Result<(), SPI::Error> { self.inner.sleep() }
}

/// RFM97W — 868 / 915 MHz HF band, max SF 9.
pub struct RFM97<SPI> { inner: RFM9xBase<SPI> }

impl<SPI: SpiDevice> RFM97<SPI> {
    const FREQ_MIN_HZ: u32 = 862_000_000;
    const FREQ_MAX_HZ: u32 = 1_020_000_000;
    const MAX_SF: u8 = 9;
    const LF_BAND: bool = false;

    /// Construct and initialise the RFM97W at the given frequency.
    ///
    /// # Arguments
    /// * `spi` — SPI bus (takes ownership).
    /// * `frequency_hz` — Carrier frequency in Hz (862 MHz – 1.02 GHz).
    pub fn new(spi: SPI, frequency_hz: u32) -> Result<Self, SPI::Error> {
        let inner = RFM9xBase::new(spi, frequency_hz, Self::LF_BAND)?;
        Ok(Self { inner })
    }

    pub fn send(&mut self, data: &[u8]) -> Result<(), SPI::Error> { self.inner.send(data) }
    pub fn receive(&mut self, timeout_ms: u32) -> Result<Option<Vec<u8>>, SPI::Error> { self.inner.receive(timeout_ms) }
    pub fn version(&mut self) -> Result<u8, SPI::Error> { self.inner.version() }
    pub fn standby(&mut self) -> Result<(), SPI::Error> { self.inner.standby() }
    pub fn sleep(&mut self) -> Result<(), SPI::Error> { self.inner.sleep() }
}

/// RFM98W — 433 / 470 MHz LF band, max SF 12.
pub struct RFM98<SPI> { inner: RFM9xBase<SPI> }

impl<SPI: SpiDevice> RFM98<SPI> {
    const FREQ_MIN_HZ: u32 = 410_000_000;
    const FREQ_MAX_HZ: u32 = 525_000_000;
    const MAX_SF: u8 = 12;
    const LF_BAND: bool = true;

    /// Construct and initialise the RFM98W at the given frequency.
    ///
    /// # Arguments
    /// * `spi` — SPI bus (takes ownership).
    /// * `frequency_hz` — Carrier frequency in Hz (410 – 525 MHz).
    pub fn new(spi: SPI, frequency_hz: u32) -> Result<Self, SPI::Error> {
        let inner = RFM9xBase::new(spi, frequency_hz, Self::LF_BAND)?;
        Ok(Self { inner })
    }

    pub fn send(&mut self, data: &[u8]) -> Result<(), SPI::Error> { self.inner.send(data) }
    pub fn receive(&mut self, timeout_ms: u32) -> Result<Option<Vec<u8>>, SPI::Error> { self.inner.receive(timeout_ms) }
    pub fn version(&mut self) -> Result<u8, SPI::Error> { self.inner.version() }
    pub fn standby(&mut self) -> Result<(), SPI::Error> { self.inner.standby() }
    pub fn sleep(&mut self) -> Result<(), SPI::Error> { self.inner.sleep() }
}

/// RFM95W with full configuration support.
pub struct RFM95Full<SPI> {
    inner: RFM9xBase<SPI>,
}

impl<SPI: SpiDevice> RFM95Full<SPI> {
    /// Construct and initialise the RFM95W full interface.
    ///
    /// # Arguments
    /// * `spi` — SPI bus (takes ownership).
    /// * `frequency_hz` — Carrier frequency in Hz.
    pub fn new(spi: SPI, frequency_hz: u32) -> Result<Self, SPI::Error> {
        let inner = RFM9xBase::new(spi, frequency_hz, false)?;
        Ok(Self { inner })
    }

    fn _map_bw(bw: u8) -> u8 {
        match bw {
            125 => 0x07,
            250 => 0x08,
            500 => 0x09,
            _ => 0x07,
        }
    }

    fn _map_cr(cr: u8) -> u8 {
        match cr {
            5 => 1,
            6 => 2,
            7 => 3,
            8 => 4,
            _ => 1,
        }
    }

    /// Configure modem parameters.
    ///
    /// # Arguments
    /// * `sf` — Spreading factor 6–12.
    /// * `bandwidth_khz` — Signal bandwidth in kHz.
    /// * `coding_rate` — Coding rate denominator 5–8.
    /// * `crc` — Enable CRC generation and verification.
    pub fn configure(&mut self, sf: u8, bandwidth_khz: u8, coding_rate: u8, crc: bool) -> Result<(), SPI::Error> {
        let bw_bits = Self::_map_bw(bandwidth_khz);
        let cr_bits = Self::_map_cr(coding_rate);
        self.inner._write_reg(0x1D, (bw_bits << 4) | (cr_bits << 1))?;
        let implicit = if sf == 6 { 1 } else { 0 };
        self.inner._write_reg(0x1E, (sf << 4) | if crc { 0x04 } else { 0x00 } | implicit)?;
        if sf == 6 {
            self.inner._write_reg(0x31, 0x05)?;
            self.inner._write_reg(0x37, 0x0C)?;
        }
        Ok(())
    }

    /// Set TX output power.
    ///
    /// # Arguments
    /// * `power_dbm` — Output power in dBm (2–20 for PA_BOOST, -1–14 for RFO).
    /// * `use_pa_boost` — Use PA_BOOST pin (max +20 dBm) if true, RFO pin (max +14 dBm) if false.
    pub fn set_tx_power(&mut self, power_dbm: i8, use_pa_boost: bool) -> Result<(), SPI::Error> {
        if use_pa_boost {
            if power_dbm < 2 || power_dbm > 20 {
                return Ok(());
            }
            if power_dbm > 17 {
                self.inner._write_reg(0x4D, 0x87)?;
                self.inner._write_reg(0x0B, 0x3B)?;
            } else {
                self.inner._write_reg(0x4D, 0x84)?;
                self.inner._write_reg(0x0B, 0x2B)?;
            }
            self.inner._write_reg(0x09, 0x80 | ((power_dbm as u8 - 2) & 0x0F))?;
        } else {
            if power_dbm < -1 || power_dbm > 14 {
                return Ok(());
            }
            self.inner._write_reg(0x4D, 0x84)?;
            self.inner._write_reg(0x0B, 0x2B)?;
            let max_power: i8 = 7;
            let pmax = 10.8 + 0.6 * max_power as f32;
            let mut out_pwr = (power_dbm as f32 - pmax + 15.0).round() as i8;
            if out_pwr < 0 { out_pwr = 0; }
            if out_pwr > 15 { out_pwr = 15; }
            self.inner._write_reg(0x09, ((max_power as u8) << 4) | (out_pwr as u8 & 0x0F))?;
        }
        Ok(())
    }

    /// Read current channel RSSI.
    ///
    /// Returns RSSI in dBm.
    pub fn rssi(&mut self) -> Result<f32, SPI::Error> {
        let r = self.inner._read_reg(0x1B)?;
        Ok(-137.0 + r as f32 * 0.5)
    }

    /// Read RSSI of last received packet.
    ///
    /// Returns packet RSSI in dBm.
    pub fn last_packet_rssi(&mut self) -> Result<f32, SPI::Error> {
        let r = self.inner._read_reg(0x1A)?;
        Ok(-137.0 + r as f32 * 0.5)
    }

    /// Read SNR of last received packet.
    ///
    /// Returns packet SNR in dB.
    pub fn last_packet_snr(&mut self) -> Result<f32, SPI::Error> {
        let s = self.inner._read_reg(0x19)?;
        let snr = if s >= 128 { s as i16 - 256 } else { s as i16 };
        Ok(snr as f32 / 4.0)
    }

    /// Enter continuous receive mode.
    pub fn receive_continuous(&mut self) -> Result<(), SPI::Error> {
        self.inner._set_mode(Self::MODE_STDBY)?;
        self.inner._write_reg(Self::REG_IRQ_FLAGS, 0xFF)?;
        self.inner._set_mode(Self::MODE_RXCONTINUOUS)?;
        Ok(())
    }

    /// Read one packet from FIFO in continuous mode.
    ///
    /// Returns received bytes or None if no packet available.
    pub fn read_packet(&mut self) -> Result<Option<Vec<u8>>, SPI::Error> {
        if self.inner._read_reg(Self::REG_IRQ_FLAGS)? & Self::IRQ_RX_DONE == 0 {
            return Ok(None);
        }
        self.inner._write_reg(Self::REG_IRQ_FLAGS, Self::IRQ_RX_DONE)?;
        let nb_bytes = self.inner._read_reg(Self::REG_RX_NB_BYTES)?;
        self.inner._write_reg(Self::REG_FIFO_ADDR_PTR, self.inner._read_reg(Self::REG_FIFO_RX_CURRENT_ADDR)?)?;
        let mut fifo_buf = vec![0u8; nb_bytes as usize];
        self.inner.spi.write_read(&[Self::REG_FIFO], &mut fifo_buf)?;
        Ok(Some(fifo_buf))
    }

    /// Return to STANDBY from RXCONTINUOUS.
    pub fn stop_receive(&mut self) -> Result<(), SPI::Error> {
        self.inner._set_mode(Self::MODE_STDBY)
    }

    /// Change carrier frequency.
    ///
    /// # Arguments
    /// * `frequency_hz` — New carrier frequency in Hz.
    pub fn set_frequency(&mut self, frequency_hz: u32) -> Result<(), SPI::Error> {
        self.inner.frequency_hz = frequency_hz;
        self.inner._set_frequency(frequency_hz)
    }

    /// Read silicon revision (expect 0x12 for SX1276).
    pub fn version(&mut self) -> Result<u8, SPI::Error> { self.inner.version() }

    /// Transmit a packet.
    pub fn send(&mut self, data: &[u8]) -> Result<(), SPI::Error> { self.inner.send(data) }

    /// Receive a packet (single shot).
    pub fn receive(&mut self, timeout_ms: u32) -> Result<Option<Vec<u8>>, SPI::Error> { self.inner.receive(timeout_ms) }

    /// Enter STANDBY mode.
    pub fn standby(&mut self) -> Result<(), SPI::Error> { self.inner.standby() }

    /// Enter SLEEP mode.
    pub fn sleep(&mut self) -> Result<(), SPI::Error> { self.inner.sleep() }
}