//! RFM9x (RFM95/96/97/98W) — LoRa transceiver modules (HopeRF).
//!
//! All four modules share identical pins, register maps, SPI protocol, and
//! LoRa modem logic. They differ only in supported frequency bands and the
//! maximum spreading factor for RFM97W.
//!
//! The driver is built around an internal `_Rfm9xBase` that owns all register
//! logic. Four thin variant subclasses — [`Rfm95Minimal`], [`Rfm96Minimal`],
//! [`Rfm97Minimal`], [`Rfm98Minimal`] — supply the variant-specific frequency
//! limits, maximum SF, and band flag.
//!
//! Generic over [`SpiDevice`] from `embedded-hal` 1.0; chip drivers never
//! assert or deassert CS themselves — the `SpiDevice` implementation owns CS.
//!
//! `_Rfm9xBase` holds every register-level operation, but only `send` and
//! `receive` are `pub` there — everything Full-only (`configure`,
//! `set_frequency`, `set_tx_power`, `standby`, `sleep`, `version`,
//! `receive_continuous`, `read_packet`, `stop_receive`, `rssi`,
//! `last_packet_rssi`, `last_packet_snr`) is `pub(crate)` so it is invisible
//! outside this module. `Rfm95Minimal` etc. wrap `_Rfm9xBase` and expose only
//! `send`/`receive` publicly; `Rfm95Full` etc. wrap the corresponding
//! `*Minimal` and reach the base's `pub(crate)` methods through a
//! `pub(crate) fn base(&mut self)` accessor on `*Minimal`, adding thin
//! one-line delegating `pub fn`s for everything else. No register-level logic
//! is duplicated between Minimal and Full.

use embedded_hal::spi::{Operation, SpiDevice};

const REG_FIFO: u8            = 0x00;
const REG_OP_MODE: u8         = 0x01;
const REG_FRF_MSB: u8         = 0x06;
const REG_FRF_MID: u8         = 0x07;
const REG_FRF_LSB: u8         = 0x08;
const REG_PA_CONFIG: u8       = 0x09;
const REG_OCP: u8             = 0x0B;
const REG_LNA: u8             = 0x0C;
const REG_FIFO_ADDR_PTR: u8   = 0x0D;
const REG_FIFO_TX_BASE: u8    = 0x0E;
const REG_FIFO_RX_BASE: u8    = 0x0F;
const REG_FIFO_RX_CURRENT: u8 = 0x10;
const REG_IRQ_FLAGS: u8       = 0x12;
const REG_RX_NB_BYTES: u8     = 0x13;
const REG_PKT_SNR: u8         = 0x19;
const REG_PKT_RSSI: u8        = 0x1A;
const REG_RSSI: u8            = 0x1B;
const REG_MODEM_CONFIG_1: u8  = 0x1D;
const REG_MODEM_CONFIG_2: u8  = 0x1E;
const REG_PREAMBLE_LSB: u8    = 0x21;
const REG_PAYLOAD_LENGTH: u8  = 0x22;
const REG_MODEM_CONFIG_3: u8  = 0x26;
const REG_DETECTION_OPT: u8   = 0x31;
const REG_DETECTION_THR: u8   = 0x37;
const REG_DIO_MAPPING_1: u8   = 0x40;
const REG_VERSION: u8         = 0x42;
const REG_PA_DAC: u8          = 0x4D;

const MODE_LONG_RANGE: u8 = 0x80;
const MODE_SLEEP: u8      = 0x00;
const MODE_STANDBY: u8    = 0x01;
const MODE_TX: u8         = 0x03;
const MODE_RX_CONT: u8    = 0x05;
const MODE_RX_SINGLE: u8  = 0x06;

const IRQ_TX_DONE: u8    = 0x08;
const IRQ_RX_DONE: u8    = 0x40;
const IRQ_RX_TIMEOUT: u8 = 0x80;

const PA_BOOST: u8          = 0x80;
const PA_DAC_HIGH_POWER: u8 = 0x87;
const PA_DAC_DEFAULT: u8    = 0x84;
const OCP_240MA: u8         = 0x3B;
const OCP_DEFAULT: u8       = 0x2B;

const DIO0_RX_DONE: u8 = 0x00;
const DIO0_TX_DONE: u8 = 0x40;

const FXOSC: u64 = 32_000_000;
const EXPECTED_VERSION: u8 = 0x12;

fn delay_ms(ms: u32) {
    #[cfg(feature = "std")]
    std::thread::sleep(std::time::Duration::from_millis(ms as u64));
    #[cfg(not(feature = "std"))]
    let _ = ms;
}

fn write_reg<SPI: SpiDevice>(spi: &mut SPI, reg: u8, value: u8) -> Result<(), SPI::Error> {
    spi.write(&[reg | 0x80, value])
}

fn read_reg<SPI: SpiDevice>(spi: &mut SPI, reg: u8) -> Result<u8, SPI::Error> {
    let mut buf = [0u8; 1];
    spi.transaction(&mut [
        Operation::Write(&[reg & 0x7F]),
        Operation::Read(&mut buf),
    ])?;
    Ok(buf[0])
}

fn burst_write<SPI: SpiDevice>(spi: &mut SPI, reg: u8, data: &[u8]) -> Result<(), SPI::Error> {
    let mut buf = [reg | 0x80];
    let mut payload = [0u8; 256];
    let len = data.len().min(255);
    payload[0] = buf[0];
    payload[1..=len].copy_from_slice(&data[..len]);
    spi.write(&payload[..=len])
}

fn burst_read<SPI: SpiDevice>(spi: &mut SPI, reg: u8, len: usize) -> Result<[u8; 256], SPI::Error> {
    let mut buf = [0u8; 256];
    let cmd = [reg & 0x7F];
    // `Operation::Transfer` is full-duplex: it clocks `zero` out on MOSI
    // while simultaneously clocking the received bytes into `buf` on MISO.
    // The write side must be padding (0x00) since only the read matters here.
    let zero = [0u8; 256];
    spi.transaction(&mut [
        Operation::Write(&cmd),
        Operation::Transfer(&mut buf[..len], &zero[..len]),
    ])?;
    Ok(buf)
}

fn band_flag(lf_band: bool) -> u8 { if lf_band { 0x08 } else { 0x00 } }

/// RFM9x minimal driver — send and receive LoRa packets at a fixed frequency.
pub struct _Rfm9xBase<SPI: SpiDevice> {
    spi: SPI,
    frequency_hz: u32,
    freq_min_hz: u32,
    freq_max_hz: u32,
    max_sf: u8,
    lf_band: bool,
}

impl<SPI: SpiDevice> _Rfm9xBase<SPI> {
    /// Create a new driver and run the LoRa init sequence.
    pub fn new(spi: SPI, frequency_hz: u32, freq_min: u32, freq_max: u32, max_sf: u8, lf_band: bool) -> Result<Self, SPI::Error> {
        let mut s = Self { spi, frequency_hz, freq_min_hz: freq_min, freq_max_hz: freq_max, max_sf, lf_band };
        s.init()?;
        Ok(s)
    }

    /// Run the LoRa init/reset register sequence (also used by `Rfm95Full::reset`
    /// and friends as a software-only reset — this driver does not accept a
    /// NRESET GPIO pin, so `reset()` re-applies this sequence instead of
    /// toggling hardware reset, mirroring the JVM driver's `_Rfm9xFull.reset()`).
    pub(crate) fn init(&mut self) -> Result<(), SPI::Error> {
        delay_ms(10);
        write_reg(&mut self.spi, REG_OP_MODE, 0x00)?;
        delay_ms(1);
        write_reg(&mut self.spi, REG_OP_MODE, MODE_LONG_RANGE | MODE_SLEEP)?;
        delay_ms(1);

        let lna = read_reg(&mut self.spi, REG_LNA)?;
        if self.lf_band {
            write_reg(&mut self.spi, REG_LNA, lna & 0x3F)?;
        } else {
            write_reg(&mut self.spi, REG_LNA, 0x23)?;
        }
        let m3 = read_reg(&mut self.spi, REG_MODEM_CONFIG_3)?;
        write_reg(&mut self.spi, REG_MODEM_CONFIG_3, m3 | 0x04)?;

        write_reg(&mut self.spi, REG_FIFO_TX_BASE, 0x80)?;
        write_reg(&mut self.spi, REG_FIFO_RX_BASE, 0x00)?;

        self.set_frequency(self.frequency_hz)?;

        write_reg(&mut self.spi, REG_MODEM_CONFIG_1, (0x07 << 4) | (0x01 << 1) | 0x00)?;
        write_reg(&mut self.spi, REG_MODEM_CONFIG_2, (0x07 << 4) | (0x01 << 2) | 0x03)?;
        write_reg(&mut self.spi, REG_PREAMBLE_LSB, 0x08)?;

        self.set_tx_power(17, true)?;
        self.standby()?;
        Ok(())
    }

    /// Set the carrier frequency. Full-only; called internally by `init`.
    pub(crate) fn set_frequency(&mut self, frequency_hz: u32) -> Result<(), SPI::Error> {
        if frequency_hz < self.freq_min_hz || frequency_hz > self.freq_max_hz { return Ok(()); }
        let frf = ((frequency_hz as u64) << 19) / FXOSC;
        write_reg(&mut self.spi, REG_FRF_MSB, ((frf >> 16) & 0xFF) as u8)?;
        write_reg(&mut self.spi, REG_FRF_MID, ((frf >>  8) & 0xFF) as u8)?;
        write_reg(&mut self.spi, REG_FRF_LSB, ( frf        & 0xFF) as u8)?;
        self.frequency_hz = frequency_hz;
        Ok(())
    }

    /// Set TX output power. Full-only; called internally by `init`.
    pub(crate) fn set_tx_power(&mut self, mut power_dbm: i8, use_pa_boost: bool) -> Result<(), SPI::Error> {
        if use_pa_boost {
            if power_dbm > 17 {
                if power_dbm > 20 { power_dbm = 20; }
                write_reg(&mut self.spi, REG_PA_DAC, PA_DAC_HIGH_POWER)?;
                write_reg(&mut self.spi, REG_OCP, OCP_240MA)?;
                write_reg(&mut self.spi, REG_PA_CONFIG, PA_BOOST | 0x0F)?;
            } else {
                if power_dbm < 2 { power_dbm = 2; }
                write_reg(&mut self.spi, REG_PA_DAC, PA_DAC_DEFAULT)?;
                write_reg(&mut self.spi, REG_OCP, OCP_DEFAULT)?;
                write_reg(&mut self.spi, REG_PA_CONFIG, PA_BOOST | (power_dbm as u8 - 2))?;
            }
        } else {
            write_reg(&mut self.spi, REG_PA_DAC, PA_DAC_DEFAULT)?;
            write_reg(&mut self.spi, REG_OCP, OCP_DEFAULT)?;
            let max_power: u8 = 7;
            let pmax = 10.8f32 + 0.6f32 * max_power as f32;
            let mut op = (power_dbm as f32 - pmax + 15.0f32) as i32;
            if op < 0  { op = 0; }
            if op > 15 { op = 15; }
            write_reg(&mut self.spi, REG_PA_CONFIG, (max_power << 4) | op as u8)?;
        }
        Ok(())
    }

    /// Configure LoRa modulation parameters. Full-only.
    pub(crate) fn configure(&mut self, mut sf: u8, bandwidth_khz: f32, coding_rate: u8, crc: bool) -> Result<(), SPI::Error> {
        let bw_table = [7.8f32, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125.0, 250.0, 500.0];
        let mut bw_code: u8 = 0x07;
        for (i, b) in bw_table.iter().enumerate() {
            if (bandwidth_khz - b).abs() < 0.01 { bw_code = i as u8; break; }
        }
        if sf < 6 || sf > self.max_sf { sf = sf.clamp(6, self.max_sf); }

        if sf == 6 {
            write_reg(&mut self.spi, REG_DETECTION_OPT, 0x05)?;
            write_reg(&mut self.spi, REG_DETECTION_THR, 0x0C)?;
        } else {
            write_reg(&mut self.spi, REG_DETECTION_OPT, 0x03)?;
            write_reg(&mut self.spi, REG_DETECTION_THR, 0x0A)?;
        }

        let implicit_header = sf == 6;
        let cr = if coding_rate >= 5 && coding_rate <= 8 { coding_rate - 4 } else { 0x01 };
        write_reg(&mut self.spi, REG_MODEM_CONFIG_1, (bw_code << 4) | (cr << 1) | if implicit_header { 1 } else { 0 })?;
        write_reg(&mut self.spi, REG_MODEM_CONFIG_2, (sf << 4) | ((if crc { 1u8 } else { 0u8 }) << 2) | 0x03)?;
        Ok(())
    }

    /// Enter STDBY mode. Full-only in the public API; used internally by
    /// `send`/`receive`, which stay `pub` since Minimal needs them directly.
    pub(crate) fn standby(&mut self) -> Result<(), SPI::Error> {
        write_reg(&mut self.spi, REG_OP_MODE, MODE_LONG_RANGE | band_flag(self.lf_band) | MODE_STANDBY)
    }

    /// Enter SLEEP mode. Full-only.
    pub(crate) fn sleep(&mut self) -> Result<(), SPI::Error> {
        write_reg(&mut self.spi, REG_OP_MODE, MODE_LONG_RANGE | band_flag(self.lf_band) | MODE_SLEEP)
    }

    /// Read `RegVersion`. Expect 0x12 (SX1276). Full-only.
    pub(crate) fn version(&mut self) -> Result<u8, SPI::Error> {
        read_reg(&mut self.spi, REG_VERSION)
    }

    /// Send a packet.
    pub fn send(&mut self, data: &[u8]) -> Result<(), SPI::Error> {
        let len = data.len().min(255);
        self.standby()?;
        write_reg(&mut self.spi, REG_FIFO_ADDR_PTR, 0x80)?;
        burst_write(&mut self.spi, REG_FIFO, &data[..len])?;
        write_reg(&mut self.spi, REG_PAYLOAD_LENGTH, len as u8)?;
        write_reg(&mut self.spi, REG_DIO_MAPPING_1, DIO0_TX_DONE)?;
        write_reg(&mut self.spi, REG_OP_MODE, MODE_LONG_RANGE | band_flag(self.lf_band) | MODE_TX)?;
        loop {
            let irq = read_reg(&mut self.spi, REG_IRQ_FLAGS)?;
            if irq & IRQ_TX_DONE != 0 { break; }
            delay_ms(2);
        }
        write_reg(&mut self.spi, REG_IRQ_FLAGS, IRQ_TX_DONE)?;
        self.standby()?;
        Ok(())
    }

    /// Receive a single packet. Returns `Some(buf)` on success, `None` on timeout.
    pub fn receive(&mut self, timeout_ms: u32) -> Result<Option<[u8; 256]>, SPI::Error> {
        self.standby()?;
        write_reg(&mut self.spi, REG_DIO_MAPPING_1, DIO0_RX_DONE)?;
        write_reg(&mut self.spi, REG_OP_MODE, MODE_LONG_RANGE | band_flag(self.lf_band) | MODE_RX_SINGLE)?;

        let mut elapsed: u32 = 0;
        while elapsed < timeout_ms {
            let irq = read_reg(&mut self.spi, REG_IRQ_FLAGS)?;
            if irq & IRQ_RX_DONE != 0 {
                write_reg(&mut self.spi, REG_IRQ_FLAGS, IRQ_RX_DONE)?;
                return Ok(Some(self.read_payload()?));
            }
            if irq & IRQ_RX_TIMEOUT != 0 {
                write_reg(&mut self.spi, REG_IRQ_FLAGS, IRQ_RX_TIMEOUT)?;
                return Ok(None);
            }
            delay_ms(5);
            elapsed += 5;
        }
        write_reg(&mut self.spi, REG_OP_MODE, MODE_LONG_RANGE | band_flag(self.lf_band) | MODE_STANDBY)?;
        Ok(None)
    }

    fn read_payload(&mut self) -> Result<[u8; 256], SPI::Error> {
        let current = read_reg(&mut self.spi, REG_FIFO_RX_CURRENT)?;
        write_reg(&mut self.spi, REG_FIFO_ADDR_PTR, current)?;
        let n = read_reg(&mut self.spi, REG_RX_NB_BYTES)? as usize;
        burst_read(&mut self.spi, REG_FIFO, n)
    }

    /// Enter continuous receive mode. Full-only.
    pub(crate) fn receive_continuous(&mut self) -> Result<(), SPI::Error> {
        self.standby()?;
        write_reg(&mut self.spi, REG_DIO_MAPPING_1, DIO0_RX_DONE)?;
        write_reg(&mut self.spi, REG_OP_MODE, MODE_LONG_RANGE | band_flag(self.lf_band) | MODE_RX_CONT)?;
        Ok(())
    }

    /// Read one packet from the FIFO while in continuous receive mode.
    /// Returns `None` if no packet is waiting. Full-only.
    pub(crate) fn read_packet(&mut self) -> Result<Option<[u8; 256]>, SPI::Error> {
        let irq = read_reg(&mut self.spi, REG_IRQ_FLAGS)?;
        if irq & IRQ_RX_DONE == 0 {
            return Ok(None);
        }
        write_reg(&mut self.spi, REG_IRQ_FLAGS, IRQ_RX_DONE)?;
        Ok(Some(self.read_payload()?))
    }

    /// Return to STDBY from continuous receive mode. Full-only.
    pub(crate) fn stop_receive(&mut self) -> Result<(), SPI::Error> {
        self.standby()
    }

    /// Current channel RSSI in dBm. Full-only.
    pub(crate) fn rssi(&mut self) -> Result<f32, SPI::Error> {
        Ok(-137.0 + read_reg(&mut self.spi, REG_RSSI)? as f32)
    }

    /// RSSI of last received packet in dBm. Full-only.
    pub(crate) fn last_packet_rssi(&mut self) -> Result<f32, SPI::Error> {
        Ok(-137.0 + read_reg(&mut self.spi, REG_PKT_RSSI)? as f32)
    }

    /// SNR of last received packet in dB. Full-only.
    pub(crate) fn last_packet_snr(&mut self) -> Result<f32, SPI::Error> {
        let raw = read_reg(&mut self.spi, REG_PKT_SNR)? as i8;
        Ok(raw as f32 / 4.0)
    }
}

/// RFM95W minimal driver — 868/915 MHz HF band, max SF=12.
pub struct Rfm95Minimal<SPI: SpiDevice> { inner: _Rfm9xBase<SPI> }

impl<SPI: SpiDevice> Rfm95Minimal<SPI> {
    /// Construct an RFM95W driver at the given carrier frequency.
    pub fn new(spi: SPI, frequency_hz: u32) -> Result<Self, SPI::Error> {
        Ok(Self { inner: _Rfm9xBase::new(spi, frequency_hz, 862_000_000, 1_020_000_000, 12, false)? })
    }

    /// Send a packet.
    pub fn send(&mut self, data: &[u8]) -> Result<(), SPI::Error> {
        self.inner.send(data)
    }

    /// Receive a single packet. Returns `Some(buf)` on success, `None` on timeout.
    pub fn receive(&mut self, timeout_ms: u32) -> Result<Option<[u8; 256]>, SPI::Error> {
        self.inner.receive(timeout_ms)
    }

    /// Access the shared register-level base. `pub(crate)` so only `Rfm95Full`
    /// (in this module) can reach the Full-only `pub(crate)` methods on it.
    pub(crate) fn base(&mut self) -> &mut _Rfm9xBase<SPI> {
        &mut self.inner
    }
}

/// RFM96W minimal driver — 433/470 MHz LF band, max SF=12.
pub struct Rfm96Minimal<SPI: SpiDevice> { inner: _Rfm9xBase<SPI> }

impl<SPI: SpiDevice> Rfm96Minimal<SPI> {
    /// Construct an RFM96W driver at the given carrier frequency.
    pub fn new(spi: SPI, frequency_hz: u32) -> Result<Self, SPI::Error> {
        Ok(Self { inner: _Rfm9xBase::new(spi, frequency_hz, 410_000_000, 525_000_000, 12, true)? })
    }

    /// Send a packet.
    pub fn send(&mut self, data: &[u8]) -> Result<(), SPI::Error> {
        self.inner.send(data)
    }

    /// Receive a single packet. Returns `Some(buf)` on success, `None` on timeout.
    pub fn receive(&mut self, timeout_ms: u32) -> Result<Option<[u8; 256]>, SPI::Error> {
        self.inner.receive(timeout_ms)
    }

    /// Access the shared register-level base. `pub(crate)` so only `Rfm96Full`
    /// (in this module) can reach the Full-only `pub(crate)` methods on it.
    pub(crate) fn base(&mut self) -> &mut _Rfm9xBase<SPI> {
        &mut self.inner
    }
}

/// RFM97W minimal driver — 868/915 MHz HF band, max SF=9.
pub struct Rfm97Minimal<SPI: SpiDevice> { inner: _Rfm9xBase<SPI> }

impl<SPI: SpiDevice> Rfm97Minimal<SPI> {
    /// Construct an RFM97W driver at the given carrier frequency.
    pub fn new(spi: SPI, frequency_hz: u32) -> Result<Self, SPI::Error> {
        Ok(Self { inner: _Rfm9xBase::new(spi, frequency_hz, 862_000_000, 1_020_000_000, 9, false)? })
    }

    /// Send a packet.
    pub fn send(&mut self, data: &[u8]) -> Result<(), SPI::Error> {
        self.inner.send(data)
    }

    /// Receive a single packet. Returns `Some(buf)` on success, `None` on timeout.
    pub fn receive(&mut self, timeout_ms: u32) -> Result<Option<[u8; 256]>, SPI::Error> {
        self.inner.receive(timeout_ms)
    }

    /// Access the shared register-level base. `pub(crate)` so only `Rfm97Full`
    /// (in this module) can reach the Full-only `pub(crate)` methods on it.
    pub(crate) fn base(&mut self) -> &mut _Rfm9xBase<SPI> {
        &mut self.inner
    }
}

/// RFM98W minimal driver — 433/470 MHz LF band, max SF=12.
pub struct Rfm98Minimal<SPI: SpiDevice> { inner: _Rfm9xBase<SPI> }

impl<SPI: SpiDevice> Rfm98Minimal<SPI> {
    /// Construct an RFM98W driver at the given carrier frequency.
    pub fn new(spi: SPI, frequency_hz: u32) -> Result<Self, SPI::Error> {
        Ok(Self { inner: _Rfm9xBase::new(spi, frequency_hz, 410_000_000, 525_000_000, 12, true)? })
    }

    /// Send a packet.
    pub fn send(&mut self, data: &[u8]) -> Result<(), SPI::Error> {
        self.inner.send(data)
    }

    /// Receive a single packet. Returns `Some(buf)` on success, `None` on timeout.
    pub fn receive(&mut self, timeout_ms: u32) -> Result<Option<[u8; 256]>, SPI::Error> {
        self.inner.receive(timeout_ms)
    }

    /// Access the shared register-level base. `pub(crate)` so only `Rfm98Full`
    /// (in this module) can reach the Full-only `pub(crate)` methods on it.
    pub(crate) fn base(&mut self) -> &mut _Rfm9xBase<SPI> {
        &mut self.inner
    }
}

/// RFM95W full driver — extends Rfm95Minimal with full configuration API.
pub struct Rfm95Full<SPI: SpiDevice> { inner: Rfm95Minimal<SPI> }

impl<SPI: SpiDevice> Rfm95Full<SPI> {
    /// Construct an RFM95W full driver at the given carrier frequency.
    pub fn new(spi: SPI, frequency_hz: u32) -> Result<Self, SPI::Error> {
        Ok(Self { inner: Rfm95Minimal::new(spi, frequency_hz)? })
    }

    /// Send a packet.
    pub fn send(&mut self, data: &[u8]) -> Result<(), SPI::Error> {
        self.inner.send(data)
    }

    /// Re-run the register-level init sequence as a software reset. This
    /// driver does not accept a NRESET GPIO pin (see module docs), so unlike
    /// a real hardware reset this simply re-applies the SLEEP→LoRa→STANDBY
    /// sequence used by the constructor, mirroring the JVM driver's
    /// `_Rfm9xFull.reset()`.
    pub fn reset(&mut self) -> Result<(), SPI::Error> {
        self.inner.base().init()
    }

    /// Configure LoRa modulation parameters.
    pub fn configure(&mut self, sf: u8, bandwidth_khz: f32, coding_rate: u8, crc: bool) -> Result<(), SPI::Error> {
        self.inner.base().configure(sf, bandwidth_khz, coding_rate, crc)
    }

    /// Set the carrier frequency.
    pub fn set_frequency(&mut self, frequency_hz: u32) -> Result<(), SPI::Error> {
        self.inner.base().set_frequency(frequency_hz)
    }

    /// Set TX output power.
    pub fn set_tx_power(&mut self, power_dbm: i8, use_pa_boost: bool) -> Result<(), SPI::Error> {
        self.inner.base().set_tx_power(power_dbm, use_pa_boost)
    }

    /// Receive a single packet. `use_interrupt` is accepted for API parity
    /// with the spec, but this driver does not accept a DIO0 pin, so both
    /// modes currently poll `RegIrqFlags` over SPI.
    pub fn receive(&mut self, timeout_ms: u32, _use_interrupt: bool) -> Result<Option<[u8; 256]>, SPI::Error> {
        self.inner.receive(timeout_ms)
    }

    /// Enter continuous receive mode.
    pub fn receive_continuous(&mut self) -> Result<(), SPI::Error> {
        self.inner.base().receive_continuous()
    }

    /// Read one packet from the FIFO in continuous receive mode. `None` if
    /// nothing is ready.
    pub fn read_packet(&mut self) -> Result<Option<[u8; 256]>, SPI::Error> {
        self.inner.base().read_packet()
    }

    /// Return to STDBY from continuous receive mode.
    pub fn stop_receive(&mut self) -> Result<(), SPI::Error> {
        self.inner.base().stop_receive()
    }

    /// Current channel RSSI in dBm.
    pub fn rssi(&mut self) -> Result<f32, SPI::Error> {
        self.inner.base().rssi()
    }

    /// RSSI of last received packet in dBm.
    pub fn last_packet_rssi(&mut self) -> Result<f32, SPI::Error> {
        self.inner.base().last_packet_rssi()
    }

    /// SNR of last received packet in dB.
    pub fn last_packet_snr(&mut self) -> Result<f32, SPI::Error> {
        self.inner.base().last_packet_snr()
    }

    /// Enter SLEEP mode.
    pub fn sleep(&mut self) -> Result<(), SPI::Error> {
        self.inner.base().sleep()
    }

    /// Enter STDBY mode.
    pub fn standby(&mut self) -> Result<(), SPI::Error> {
        self.inner.base().standby()
    }

    /// Read `RegVersion`. Expect 0x12 (SX1276).
    pub fn version(&mut self) -> Result<u8, SPI::Error> {
        self.inner.base().version()
    }
}

/// RFM96W full driver.
pub struct Rfm96Full<SPI: SpiDevice> { inner: Rfm96Minimal<SPI> }

impl<SPI: SpiDevice> Rfm96Full<SPI> {
    /// Construct an RFM96W full driver at the given carrier frequency.
    pub fn new(spi: SPI, frequency_hz: u32) -> Result<Self, SPI::Error> {
        Ok(Self { inner: Rfm96Minimal::new(spi, frequency_hz)? })
    }

    /// Send a packet.
    pub fn send(&mut self, data: &[u8]) -> Result<(), SPI::Error> {
        self.inner.send(data)
    }

    /// Re-run the register-level init sequence as a software reset. This
    /// driver does not accept a NRESET GPIO pin (see module docs), so unlike
    /// a real hardware reset this simply re-applies the SLEEP→LoRa→STANDBY
    /// sequence used by the constructor, mirroring the JVM driver's
    /// `_Rfm9xFull.reset()`.
    pub fn reset(&mut self) -> Result<(), SPI::Error> {
        self.inner.base().init()
    }

    /// Configure LoRa modulation parameters.
    pub fn configure(&mut self, sf: u8, bandwidth_khz: f32, coding_rate: u8, crc: bool) -> Result<(), SPI::Error> {
        self.inner.base().configure(sf, bandwidth_khz, coding_rate, crc)
    }

    /// Set the carrier frequency.
    pub fn set_frequency(&mut self, frequency_hz: u32) -> Result<(), SPI::Error> {
        self.inner.base().set_frequency(frequency_hz)
    }

    /// Set TX output power.
    pub fn set_tx_power(&mut self, power_dbm: i8, use_pa_boost: bool) -> Result<(), SPI::Error> {
        self.inner.base().set_tx_power(power_dbm, use_pa_boost)
    }

    /// Receive a single packet. `use_interrupt` is accepted for API parity
    /// with the spec, but this driver does not accept a DIO0 pin, so both
    /// modes currently poll `RegIrqFlags` over SPI.
    pub fn receive(&mut self, timeout_ms: u32, _use_interrupt: bool) -> Result<Option<[u8; 256]>, SPI::Error> {
        self.inner.receive(timeout_ms)
    }

    /// Enter continuous receive mode.
    pub fn receive_continuous(&mut self) -> Result<(), SPI::Error> {
        self.inner.base().receive_continuous()
    }

    /// Read one packet from the FIFO in continuous receive mode. `None` if
    /// nothing is ready.
    pub fn read_packet(&mut self) -> Result<Option<[u8; 256]>, SPI::Error> {
        self.inner.base().read_packet()
    }

    /// Return to STDBY from continuous receive mode.
    pub fn stop_receive(&mut self) -> Result<(), SPI::Error> {
        self.inner.base().stop_receive()
    }

    /// Current channel RSSI in dBm.
    pub fn rssi(&mut self) -> Result<f32, SPI::Error> {
        self.inner.base().rssi()
    }

    /// RSSI of last received packet in dBm.
    pub fn last_packet_rssi(&mut self) -> Result<f32, SPI::Error> {
        self.inner.base().last_packet_rssi()
    }

    /// SNR of last received packet in dB.
    pub fn last_packet_snr(&mut self) -> Result<f32, SPI::Error> {
        self.inner.base().last_packet_snr()
    }

    /// Enter SLEEP mode.
    pub fn sleep(&mut self) -> Result<(), SPI::Error> {
        self.inner.base().sleep()
    }

    /// Enter STDBY mode.
    pub fn standby(&mut self) -> Result<(), SPI::Error> {
        self.inner.base().standby()
    }

    /// Read `RegVersion`. Expect 0x12 (SX1276).
    pub fn version(&mut self) -> Result<u8, SPI::Error> {
        self.inner.base().version()
    }
}

/// RFM97W full driver.
pub struct Rfm97Full<SPI: SpiDevice> { inner: Rfm97Minimal<SPI> }

impl<SPI: SpiDevice> Rfm97Full<SPI> {
    /// Construct an RFM97W full driver at the given carrier frequency.
    pub fn new(spi: SPI, frequency_hz: u32) -> Result<Self, SPI::Error> {
        Ok(Self { inner: Rfm97Minimal::new(spi, frequency_hz)? })
    }

    /// Send a packet.
    pub fn send(&mut self, data: &[u8]) -> Result<(), SPI::Error> {
        self.inner.send(data)
    }

    /// Re-run the register-level init sequence as a software reset. This
    /// driver does not accept a NRESET GPIO pin (see module docs), so unlike
    /// a real hardware reset this simply re-applies the SLEEP→LoRa→STANDBY
    /// sequence used by the constructor, mirroring the JVM driver's
    /// `_Rfm9xFull.reset()`.
    pub fn reset(&mut self) -> Result<(), SPI::Error> {
        self.inner.base().init()
    }

    /// Configure LoRa modulation parameters.
    pub fn configure(&mut self, sf: u8, bandwidth_khz: f32, coding_rate: u8, crc: bool) -> Result<(), SPI::Error> {
        self.inner.base().configure(sf, bandwidth_khz, coding_rate, crc)
    }

    /// Set the carrier frequency.
    pub fn set_frequency(&mut self, frequency_hz: u32) -> Result<(), SPI::Error> {
        self.inner.base().set_frequency(frequency_hz)
    }

    /// Set TX output power.
    pub fn set_tx_power(&mut self, power_dbm: i8, use_pa_boost: bool) -> Result<(), SPI::Error> {
        self.inner.base().set_tx_power(power_dbm, use_pa_boost)
    }

    /// Receive a single packet. `use_interrupt` is accepted for API parity
    /// with the spec, but this driver does not accept a DIO0 pin, so both
    /// modes currently poll `RegIrqFlags` over SPI.
    pub fn receive(&mut self, timeout_ms: u32, _use_interrupt: bool) -> Result<Option<[u8; 256]>, SPI::Error> {
        self.inner.receive(timeout_ms)
    }

    /// Enter continuous receive mode.
    pub fn receive_continuous(&mut self) -> Result<(), SPI::Error> {
        self.inner.base().receive_continuous()
    }

    /// Read one packet from the FIFO in continuous receive mode. `None` if
    /// nothing is ready.
    pub fn read_packet(&mut self) -> Result<Option<[u8; 256]>, SPI::Error> {
        self.inner.base().read_packet()
    }

    /// Return to STDBY from continuous receive mode.
    pub fn stop_receive(&mut self) -> Result<(), SPI::Error> {
        self.inner.base().stop_receive()
    }

    /// Current channel RSSI in dBm.
    pub fn rssi(&mut self) -> Result<f32, SPI::Error> {
        self.inner.base().rssi()
    }

    /// RSSI of last received packet in dBm.
    pub fn last_packet_rssi(&mut self) -> Result<f32, SPI::Error> {
        self.inner.base().last_packet_rssi()
    }

    /// SNR of last received packet in dB.
    pub fn last_packet_snr(&mut self) -> Result<f32, SPI::Error> {
        self.inner.base().last_packet_snr()
    }

    /// Enter SLEEP mode.
    pub fn sleep(&mut self) -> Result<(), SPI::Error> {
        self.inner.base().sleep()
    }

    /// Enter STDBY mode.
    pub fn standby(&mut self) -> Result<(), SPI::Error> {
        self.inner.base().standby()
    }

    /// Read `RegVersion`. Expect 0x12 (SX1276).
    pub fn version(&mut self) -> Result<u8, SPI::Error> {
        self.inner.base().version()
    }
}

/// RFM98W full driver.
pub struct Rfm98Full<SPI: SpiDevice> { inner: Rfm98Minimal<SPI> }

impl<SPI: SpiDevice> Rfm98Full<SPI> {
    /// Construct an RFM98W full driver at the given carrier frequency.
    pub fn new(spi: SPI, frequency_hz: u32) -> Result<Self, SPI::Error> {
        Ok(Self { inner: Rfm98Minimal::new(spi, frequency_hz)? })
    }

    /// Send a packet.
    pub fn send(&mut self, data: &[u8]) -> Result<(), SPI::Error> {
        self.inner.send(data)
    }

    /// Re-run the register-level init sequence as a software reset. This
    /// driver does not accept a NRESET GPIO pin (see module docs), so unlike
    /// a real hardware reset this simply re-applies the SLEEP→LoRa→STANDBY
    /// sequence used by the constructor, mirroring the JVM driver's
    /// `_Rfm9xFull.reset()`.
    pub fn reset(&mut self) -> Result<(), SPI::Error> {
        self.inner.base().init()
    }

    /// Configure LoRa modulation parameters.
    pub fn configure(&mut self, sf: u8, bandwidth_khz: f32, coding_rate: u8, crc: bool) -> Result<(), SPI::Error> {
        self.inner.base().configure(sf, bandwidth_khz, coding_rate, crc)
    }

    /// Set the carrier frequency.
    pub fn set_frequency(&mut self, frequency_hz: u32) -> Result<(), SPI::Error> {
        self.inner.base().set_frequency(frequency_hz)
    }

    /// Set TX output power.
    pub fn set_tx_power(&mut self, power_dbm: i8, use_pa_boost: bool) -> Result<(), SPI::Error> {
        self.inner.base().set_tx_power(power_dbm, use_pa_boost)
    }

    /// Receive a single packet. `use_interrupt` is accepted for API parity
    /// with the spec, but this driver does not accept a DIO0 pin, so both
    /// modes currently poll `RegIrqFlags` over SPI.
    pub fn receive(&mut self, timeout_ms: u32, _use_interrupt: bool) -> Result<Option<[u8; 256]>, SPI::Error> {
        self.inner.receive(timeout_ms)
    }

    /// Enter continuous receive mode.
    pub fn receive_continuous(&mut self) -> Result<(), SPI::Error> {
        self.inner.base().receive_continuous()
    }

    /// Read one packet from the FIFO in continuous receive mode. `None` if
    /// nothing is ready.
    pub fn read_packet(&mut self) -> Result<Option<[u8; 256]>, SPI::Error> {
        self.inner.base().read_packet()
    }

    /// Return to STDBY from continuous receive mode.
    pub fn stop_receive(&mut self) -> Result<(), SPI::Error> {
        self.inner.base().stop_receive()
    }

    /// Current channel RSSI in dBm.
    pub fn rssi(&mut self) -> Result<f32, SPI::Error> {
        self.inner.base().rssi()
    }

    /// RSSI of last received packet in dBm.
    pub fn last_packet_rssi(&mut self) -> Result<f32, SPI::Error> {
        self.inner.base().last_packet_rssi()
    }

    /// SNR of last received packet in dB.
    pub fn last_packet_snr(&mut self) -> Result<f32, SPI::Error> {
        self.inner.base().last_packet_snr()
    }

    /// Enter SLEEP mode.
    pub fn sleep(&mut self) -> Result<(), SPI::Error> {
        self.inner.base().sleep()
    }

    /// Enter STDBY mode.
    pub fn standby(&mut self) -> Result<(), SPI::Error> {
        self.inner.base().standby()
    }

    /// Read `RegVersion`. Expect 0x12 (SX1276).
    pub fn version(&mut self) -> Result<u8, SPI::Error> {
        self.inner.base().version()
    }
}
