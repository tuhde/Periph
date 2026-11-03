//! HYCON HY3131 50,000-count DMM analog front end driver (SPI).
//!
//! Communicates over SPI Mode 0, MSB first. The framing is non-standard: the first
//! byte is `(addr << 1) | R/W`, reads carry one blank turnaround clock (so the
//! response is shifted left by one bit), and addresses auto-increment while CS
//! stays low. `INTF` is polled over SPI for data-ready — no IRQ pin is needed.
//!
//! [`Hy3131Minimal`] reads AD1 as a differential voltage; [`Hy3131Full`] adds
//! AD1/AD2/AD3 configuration, low-pass filter, RMS, peak hold, the frequency counter,
//! interrupt flags and raw register access. See `specs/adc_dac/hy3131.md`.
//!
//! Generic over [`SpiDevice`] and [`DelayNs`] from `embedded-hal` 1.0.

use embedded_hal::delay::DelayNs;
use embedded_hal::spi::{Operation, SpiDevice};

const REG_AD1: u8 = 0x00;
const REG_AD2: u8 = 0x03;
const REG_LPF: u8 = 0x06;
const REG_RMS: u8 = 0x09;
const REG_PKHMIN: u8 = 0x0E;
const REG_PKHMAX: u8 = 0x11;
const REG_CTSTA: u8 = 0x14;
const REG_CTC: u8 = 0x15;
const REG_CTB: u8 = 0x18;
const REG_CTA: u8 = 0x1B;
const REG_INTF: u8 = 0x1E;
const REG_INTE: u8 = 0x1F;
const REG_R20: u8 = 0x20;
const REG_R22: u8 = 0x22;
const REG_R23: u8 = 0x23;
const REG_R24: u8 = 0x24;
const REG_R25: u8 = 0x25;
const REG_R26: u8 = 0x26;
const REG_R27: u8 = 0x27;
const REG_R28: u8 = 0x28;
const REG_R29: u8 = 0x29;
const REG_R2A: u8 = 0x2A;
const REG_R2F: u8 = 0x2F;
const REG_R30: u8 = 0x30;
const REG_R31: u8 = 0x31;
const REG_R33: u8 = 0x33;
const REG_R34: u8 = 0x34;
const REG_R35: u8 = 0x35;
const REG_R37: u8 = 0x37;

/// `INTF` flag: frequency counter measurement done.
pub const FLAG_CTF: u8 = 0x01;
/// `INTF` flag: AD2/AD3 conversion done.
pub const FLAG_AD2F: u8 = 0x02;
/// `INTF` flag: AD1 conversion done.
pub const FLAG_AD1F: u8 = 0x04;
/// `INTF` flag: low-pass filter result updated.
pub const FLAG_LPFF: u8 = 0x08;
/// `INTF` flag: RMS result updated.
pub const FLAG_RMSF: u8 = 0x10;
/// `INTF` flag: brown-out (VDD fell below 1.9 V).
pub const FLAG_BORF: u8 = 0x80;

const AD1_GAINS: [f32; 4] = [0.9, 1.8, 2.7, 3.6];
const DCOS: [f32; 8] = [0.0, 0.25, 0.5, 0.75, 0.0, -0.25, -0.5, -0.75];
const TIMEOUT_MS: u32 = 2000;

/// Driver error.
#[derive(Debug, PartialEq)]
pub enum Hy3131Error<E> {
    /// SPI bus error.
    Spi(E),
    /// No conversion completed within 2 s.
    Timeout,
}

impl<E> From<E> for Hy3131Error<E> {
    fn from(e: E) -> Self {
        Hy3131Error::Spi(e)
    }
}

fn sext(value: i64, bits: u32) -> i64 {
    let sign = 1i64 << (bits - 1);
    if value & sign != 0 { value - (sign << 1) } else { value }
}

/// HY3131 minimal driver — AD1 differential voltage with sensible defaults.
pub struct Hy3131Minimal<SPI: SpiDevice, D: DelayNs> {
    spi: SPI,
    delay: D,
    vref: f32,
    flags: u8,
    ig: usize,
    rg: u8,
    dcos: f32,
}

impl<SPI: SpiDevice, D: DelayNs> Hy3131Minimal<SPI, D> {
    /// Construct and initialise the HY3131.
    ///
    /// # Arguments
    /// * `spi` — `SpiDevice` (Mode 0, MSB first) bound to the chip's CS pin.
    /// * `delay` — delay provider (init settling and conversion polling).
    /// * `vref` — voltage on REFO in V (1.2 V from the internal bandgap).
    pub fn new(spi: SPI, delay: D, vref: f32) -> Result<Self, Hy3131Error<SPI::Error>> {
        let mut s = Self { spi, delay, vref, flags: 0, ig: 0, rg: 0, dcos: 0.0 };
        s.init()?;
        Ok(s)
    }

    /// Release the SPI device and delay provider.
    pub fn release(self) -> (SPI, D) {
        (self.spi, self.delay)
    }

    fn init(&mut self) -> Result<(), Hy3131Error<SPI::Error>> {
        self.write_register(REG_R37, &[0x60])?;
        self.delay.delay_ms(5);
        self.write_register(REG_R33, &[0x28])?;
        self.delay.delay_ms(10);
        self.write_register(REG_R31, &[0xC0])?;
        self.write_register(REG_R30, &[0x00])?;
        self.delay.delay_ms(5);
        self.write_register(REG_R2F, &[0x80])?;
        self.write_register(REG_R2A, &[0; 5])?;
        for reg in [0x32, REG_R20, 0x21, REG_R34, REG_R35, 0x36, REG_R29, REG_R26, REG_R27, REG_INTE] {
            self.write_register(reg, &[0x00])?;
        }
        self.write_register(REG_R24, &[0xA3])?;
        self.write_register(REG_R28, &[0x11])?;
        self.ig = 0;
        self.rg = 0;
        self.dcos = 0.0;
        self.write_register(REG_R25, &[0x00])?;
        self.write_register(REG_R22, &[0x07])?;
        self.write_register(REG_R23, &[0x80])?;
        self.read_register(REG_INTF, &mut [0u8; 1])?;
        self.flags = 0;
        Ok(())
    }

    /// Read `buf.len()` consecutive registers starting at `reg` (auto-increment).
    pub fn read_register(&mut self, reg: u8, buf: &mut [u8]) -> Result<(), Hy3131Error<SPI::Error>> {
        let cmd = [((reg & 0x7F) << 1) | 1];
        let n = buf.len();
        let mut rx = [0u8; 8];
        self.spi.transaction(&mut [Operation::Write(&cmd), Operation::Read(&mut rx[..n + 1])])?;
        for i in 0..n {
            buf[i] = (rx[i] << 1) | (rx[i + 1] >> 7);
        }
        Ok(())
    }

    /// Write `data` to `reg` (auto-increment).
    pub fn write_register(&mut self, reg: u8, data: &[u8]) -> Result<(), Hy3131Error<SPI::Error>> {
        let mut buf = [0u8; 8];
        buf[0] = (reg & 0x7F) << 1;
        buf[1..1 + data.len()].copy_from_slice(data);
        self.spi.write(&buf[..1 + data.len()])?;
        Ok(())
    }

    fn wait_flag(&mut self, mask: u8) -> Result<(), Hy3131Error<SPI::Error>> {
        let mut waited = 0;
        loop {
            let mut b = [0u8; 1];
            self.read_register(REG_INTF, &mut b)?;
            self.flags |= b[0];
            if self.flags & mask != 0 {
                self.flags &= !mask;
                return Ok(());
            }
            if waited > TIMEOUT_MS {
                return Err(Hy3131Error::Timeout);
            }
            self.delay.delay_ms(1);
            waited += 1;
        }
    }

    fn read24(&mut self, reg: u8) -> Result<i64, Hy3131Error<SPI::Error>> {
        let mut b = [0u8; 3];
        self.read_register(reg, &mut b)?;
        Ok(b[0] as i64 | ((b[1] as i64) << 8) | ((b[2] as i64) << 16))
    }

    /// Returns `true` if a new AD1 conversion is pending (latches `INTF`).
    pub fn data_ready(&mut self) -> Result<bool, Hy3131Error<SPI::Error>> {
        let mut b = [0u8; 1];
        self.read_register(REG_INTF, &mut b)?;
        self.flags |= b[0];
        Ok(self.flags & FLAG_AD1F != 0)
    }

    /// Wait for a conversion and return the signed 24-bit AD1 code.
    pub fn read_raw(&mut self) -> Result<i32, Hy3131Error<SPI::Error>> {
        self.wait_flag(FLAG_AD1F)?;
        Ok(sext(self.read24(REG_AD1)?, 24) as i32)
    }

    /// Read AD1 as a differential input voltage in V.
    pub fn read_voltage(&mut self) -> Result<f32, Hy3131Error<SPI::Error>> {
        let code = self.read_raw()?;
        let rg = if self.rg == 0 { 1.0 } else { 0.333_333_33 };
        Ok((code as f32 / 8_388_608.0 * rg - self.dcos) * self.vref / AD1_GAINS[self.ig])
    }
}

/// HY3131 full driver — AD1/AD2/AD3, LPF, RMS, peak hold, counter, interrupts.
pub struct Hy3131Full<SPI: SpiDevice, D: DelayNs> {
    inner: Hy3131Minimal<SPI, D>,
}

impl<SPI: SpiDevice, D: DelayNs> Hy3131Full<SPI, D> {
    /// Construct and initialise the HY3131 with the Minimal defaults.
    pub fn new(spi: SPI, delay: D, vref: f32) -> Result<Self, Hy3131Error<SPI::Error>> {
        Ok(Self { inner: Hy3131Minimal::new(spi, delay, vref)? })
    }

    /// Release the SPI device and delay provider.
    pub fn release(self) -> (SPI, D) {
        self.inner.release()
    }

    /// Returns `true` if a new AD1 conversion is pending.
    pub fn data_ready(&mut self) -> Result<bool, Hy3131Error<SPI::Error>> {
        self.inner.data_ready()
    }

    /// Wait for a conversion and return the signed 24-bit AD1 code.
    pub fn read_raw(&mut self) -> Result<i32, Hy3131Error<SPI::Error>> {
        self.inner.read_raw()
    }

    /// Read AD1 as a differential input voltage in V.
    pub fn read_voltage(&mut self) -> Result<f32, Hy3131Error<SPI::Error>> {
        self.inner.read_voltage()
    }

    /// Read `buf.len()` registers starting at `reg`.
    pub fn read_register(&mut self, reg: u8, buf: &mut [u8]) -> Result<(), Hy3131Error<SPI::Error>> {
        self.inner.read_register(reg, buf)
    }

    /// Write `data` to `reg`.
    pub fn write_register(&mut self, reg: u8, data: &[u8]) -> Result<(), Hy3131Error<SPI::Error>> {
        self.inner.write_register(reg, data)
    }

    fn rd1(&mut self, reg: u8) -> Result<u8, Hy3131Error<SPI::Error>> {
        let mut b = [0u8; 1];
        self.inner.read_register(reg, &mut b)?;
        Ok(b[0])
    }

    fn clear_flags(&mut self) -> Result<(), Hy3131Error<SPI::Error>> {
        self.rd1(REG_INTF)?;
        self.inner.flags = 0;
        Ok(())
    }

    /// Software-reset the chip and re-apply the Minimal defaults.
    pub fn reset(&mut self) -> Result<(), Hy3131Error<SPI::Error>> {
        self.inner.init()
    }

    /// Configure AD1: `osr` 0–7, `chop` 0–3, `input_gain` 0–3 (0.9/1.8/2.7/3.6),
    /// `ref_gain` 0/1 (1.0/0.333), `offset` 0–7 (DCOS code), `buffers` 4-bit mask.
    pub fn configure_ad1(
        &mut self, osr: u8, chop: u8, input_gain: u8, ref_gain: u8, offset: u8, buffers: u8,
    ) -> Result<(), Hy3131Error<SPI::Error>> {
        let r25 = self.rd1(REG_R25)?;
        self.inner.write_register(REG_R25, &[(r25 & 0xCF) | ((input_gain & 3) << 4)])?;
        self.inner.write_register(REG_R22, &[((offset & 7) << 5) | ((chop & 3) << 3) | (osr & 7)])?;
        self.inner.write_register(REG_R23, &[0x80 | ((ref_gain & 1) << 4) | (buffers & 0x0F)])?;
        self.inner.ig = (input_gain & 3) as usize;
        self.inner.rg = ref_gain & 1;
        self.inner.dcos = DCOS[(offset & 7) as usize];
        self.clear_flags()
    }

    /// Select AD1 inputs: `pos` 0–15 (SAD1FP), `neg` 0–7 (SAD1FN), `filter_r` 0–3 (SFT1).
    pub fn set_ad1_input(&mut self, pos: u8, neg: u8, filter_r: u8) -> Result<(), Hy3131Error<SPI::Error>> {
        self.inner.write_register(REG_R24, &[((pos & 15) << 4) | (neg & 7)])?;
        let r33 = self.rd1(REG_R33)?;
        self.inner.write_register(REG_R33, &[(r33 & 0xF3) | ((filter_r & 3) << 2)])
    }

    /// Select AD1 reference: `high` 0–6 (SAD1RH), `low` 0–6 (SAD1RL).
    pub fn set_ad1_reference(&mut self, high: u8, low: u8) -> Result<(), Hy3131Error<SPI::Error>> {
        self.inner.write_register(REG_R28, &[((high & 7) << 4) | (low & 7)])
    }

    /// Return TCode = (AD1[SAD1I=11] − AD1[SAD1I=10]) / 2 from the on-chip sensor.
    pub fn read_temperature_code(&mut self) -> Result<i32, Hy3131Error<SPI::Error>> {
        let r33 = self.rd1(REG_R33)?;
        let mut codes = [0i32; 2];
        for (i, sel) in [2u8, 3u8].iter().enumerate() {
            self.inner.write_register(REG_R33, &[(r33 & 0xFC) | sel])?;
            self.clear_flags()?;
            self.inner.wait_flag(FLAG_AD1F)?; // discard settling sample
            codes[i] = self.inner.read_raw()?;
        }
        self.inner.write_register(REG_R33, &[r33])?;
        self.inner.flags = 0;
        Ok((codes[1] - codes[0]) / 2)
    }

    /// Return the die temperature in °C using a one-point calibration (`tcode_25` is
    /// [`read_temperature_code`](Self::read_temperature_code) measured at 25 °C).
    pub fn read_temperature(&mut self, tcode_25: i32) -> Result<f32, Hy3131Error<SPI::Error>> {
        let t_os = -3.15f32;
        let g = tcode_25 as f32 / (25.0 + 273.15 + t_os);
        Ok(self.read_temperature_code()? as f32 / g - 273.15 - t_os)
    }

    /// Enable AD2 (and AD3 when `use_ad3`).
    #[allow(clippy::too_many_arguments)]
    pub fn enable_ad2(
        &mut self, osr: u8, input_gain: u8, ref_gain: u8, chop: bool, clk_div4: bool, source: u8,
        use_ad3: bool,
    ) -> Result<(), Hy3131Error<SPI::Error>> {
        let r25 = self.rd1(REG_R25)?;
        self.inner.write_register(REG_R25, &[(r25 & 0x3F) | ((input_gain & 3) << 6)])?;
        self.inner.write_register(REG_R27, &[((source & 3) << 6) | 0x15])?;
        let r26 = 0x80 | if chop { 0x20 } else { 0 } | ((ref_gain & 1) << 4)
            | if clk_div4 { 0x08 } else { 0 } | (osr & 7);
        self.inner.write_register(REG_R26, &[r26])?;
        if use_ad3 {
            self.inner.write_register(REG_R35, &[((source & 3) << 6) | (1 << 4) | ((input_gain & 3) << 2)])?;
            self.inner.write_register(
                REG_R34, &[0x80 | if chop { 0x20 } else { 0 } | ((ref_gain & 1) << 4) | 0x04])?;
        } else {
            self.inner.write_register(REG_R34, &[0x00])?;
        }
        self.clear_flags()
    }

    /// Wait for AD2 and return the signed 19-bit code.
    pub fn read_ad2_raw(&mut self) -> Result<i32, Hy3131Error<SPI::Error>> {
        self.inner.wait_flag(FLAG_AD2F)?;
        Ok(sext(self.inner.read24(REG_AD2)?, 24) as i32)
    }

    /// Enable the low-pass filter; `bw` 0–7 → OSR4 128 … 16384.
    pub fn enable_lpf(&mut self, bw: u8) -> Result<(), Hy3131Error<SPI::Error>> {
        let r29 = self.rd1(REG_R29)?;
        self.inner.write_register(REG_R29, &[(r29 & 0x87) | 0x40 | ((bw & 7) << 3)])
    }

    /// Wait for the LPF and return the signed 19-bit code.
    pub fn read_lpf_raw(&mut self) -> Result<i32, Hy3131Error<SPI::Error>> {
        self.inner.wait_flag(FLAG_LPFF)?;
        Ok(sext(self.inner.read24(REG_LPF)?, 24) as i32)
    }

    /// Enable the RMS converter (requires AD2 and the LPF).
    pub fn enable_rms(&mut self) -> Result<(), Hy3131Error<SPI::Error>> {
        let r29 = self.rd1(REG_R29)?;
        self.inner.write_register(REG_R29, &[r29 | 0x80])
    }

    /// Wait for RMS and return `sqrt(RMS accumulator)` in AD2 LSB units.
    pub fn read_rms(&mut self) -> Result<f32, Hy3131Error<SPI::Error>> {
        self.inner.wait_flag(FLAG_RMSF)?;
        let mut b = [0u8; 5];
        self.inner.read_register(REG_RMS, &mut b)?;
        let raw = b[0] as i64 | ((b[1] as i64) << 8) | ((b[2] as i64) << 16)
            | ((b[3] as i64) << 24) | ((b[4] as i64) << 32);
        let v = sext(raw, 40).max(0);
        Ok(libm::sqrt(v as f64) as f32)
    }

    /// Enable peak hold; `source` 0–3 → AD2, AD1<23:5>, LPF, LPF. Re-enabling resets min/max.
    pub fn enable_peak_hold(&mut self, source: u8) -> Result<(), Hy3131Error<SPI::Error>> {
        let r29 = self.rd1(REG_R29)?;
        self.inner.write_register(REG_R29, &[r29 & !0x07])?;
        self.inner.write_register(REG_R29, &[(r29 & !0x07) | 0x04 | (source & 3)])
    }

    /// Return `(min, max)` signed 19-bit peak-hold codes.
    pub fn read_peak(&mut self) -> Result<(i32, i32), Hy3131Error<SPI::Error>> {
        let lo = sext(self.inner.read24(REG_PKHMIN)?, 24) as i32;
        let hi = sext(self.inner.read24(REG_PKHMAX)?, 24) as i32;
        Ok((lo, hi))
    }

    /// Start the frequency counter; `gate_preload` (0–0xFFFF) is written to CTA<23:8>.
    pub fn start_counter(&mut self, gate_preload: u16) -> Result<(), Hy3131Error<SPI::Error>> {
        let r20 = self.rd1(REG_R20)?;
        self.inner.write_register(REG_R20, &[r20 & !0x02])?;
        self.inner.write_register(REG_CTA + 1, &[(gate_preload & 0xFF) as u8, (gate_preload >> 8) as u8])?;
        self.clear_flags()?;
        self.inner.write_register(REG_R20, &[r20 | 0x0A])
    }

    /// Wait for the counter and return `(cta, ctb, ctc, overflow)`.
    pub fn read_counter(&mut self) -> Result<(u32, u32, u32, bool), Hy3131Error<SPI::Error>> {
        self.inner.wait_flag(FLAG_CTF)?;
        let overflow = self.rd1(REG_CTSTA)? & 0x01 != 0;
        Ok((
            self.inner.read24(REG_CTA)? as u32,
            self.inner.read24(REG_CTB)? as u32,
            self.inner.read24(REG_CTC)? as u32,
            overflow,
        ))
    }

    /// Return `INTF` (clears it on the chip); latched flags are included and cleared.
    pub fn read_flags(&mut self) -> Result<u8, Hy3131Error<SPI::Error>> {
        let f = self.inner.flags | self.rd1(REG_INTF)?;
        self.inner.flags = 0;
        Ok(f)
    }

    /// Write `INTE` (RMSIE 4, LPFIE 3, AD1IE 2, AD2IE 1, CTIE 0).
    pub fn enable_interrupts(&mut self, mask: u8) -> Result<(), Hy3131Error<SPI::Error>> {
        self.inner.write_register(REG_INTE, &[mask & 0x1F])
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use core::convert::Infallible;
    use embedded_hal::spi::ErrorType;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use std::collections::HashMap;

    /// Stateful HY3131 model: (addr<<1)|R/W command, one blank clock on reads,
    /// auto-increment. `INTF` reads back `intf` once, then clears.
    struct Chip {
        mem: HashMap<u8, u8>,
        intf: u8,
    }

    impl Chip {
        fn new() -> Self {
            Chip { mem: HashMap::new(), intf: 0x07 }
        }
        fn set(&mut self, reg: u8, vals: &[u8]) {
            for (i, v) in vals.iter().enumerate() {
                self.mem.insert(reg + i as u8, *v);
            }
        }
        fn get(&self, reg: u8) -> u8 {
            *self.mem.get(&reg).unwrap_or(&0)
        }
    }

    impl ErrorType for Chip {
        type Error = Infallible;
    }

    impl SpiDevice for Chip {
        fn transaction(&mut self, ops: &mut [Operation<'_, u8>]) -> Result<(), Infallible> {
            let mut addr = 0u8;
            let mut writing = false;
            let mut first = true;
            for op in ops.iter_mut() {
                match op {
                    Operation::Write(buf) => {
                        for (i, b) in buf.iter().enumerate() {
                            if first && i == 0 {
                                addr = *b >> 1;
                                writing = *b & 1 == 0;
                            } else if writing {
                                self.mem.insert(addr, *b);
                                addr += 1;
                            }
                        }
                        first = false;
                    }
                    Operation::Read(buf) => {
                        let n = buf.len();
                        let mut vals = vec![0u8; n];
                        for (i, v) in vals.iter_mut().enumerate() {
                            let a = addr + i as u8;
                            *v = if a == 0x1E {
                                let x = self.intf;
                                self.intf = 0;
                                x
                            } else {
                                self.get(a)
                            };
                        }
                        for i in 0..n {
                            buf[i] = if i == 0 { vals[0] >> 1 } else { ((vals[i - 1] & 1) << 7) | (vals[i] >> 1) };
                        }
                    }
                    _ => {}
                }
            }
            Ok(())
        }
    }

    fn full() -> Hy3131Full<Chip, NoopDelay> {
        Hy3131Full::new(Chip::new(), NoopDelay::new(), 1.2).unwrap()
    }

    fn chip<'a>(f: &'a mut Hy3131Full<Chip, NoopDelay>) -> &'a mut Chip {
        &mut f.inner.spi
    }

    #[test]
    fn init_defaults() {
        let mut f = full();
        let c = chip(&mut f);
        assert_eq!(c.get(0x33), 0x28);
        assert_eq!(c.get(0x31), 0xC0);
        assert_eq!(c.get(0x30), 0x00);
        assert_eq!(c.get(0x24), 0xA3);
        assert_eq!(c.get(0x28), 0x11);
        assert_eq!(c.get(0x22), 0x07);
        assert_eq!(c.get(0x23), 0x80);
    }

    #[test]
    fn register_shift_and_autoincrement() {
        let mut f = full();
        chip(&mut f).set(0x70, &[0xA5, 0x5A]);
        let mut b = [0u8; 2];
        f.read_register(0x70, &mut b).unwrap();
        assert_eq!(b, [0xA5, 0x5A]);
        f.write_register(0x60, &[1, 2, 3]).unwrap();
        let c = chip(&mut f);
        assert_eq!((c.get(0x60), c.get(0x61), c.get(0x62)), (1, 2, 3));
    }

    #[test]
    fn read_raw_and_voltage() {
        let mut f = full();
        chip(&mut f).set(0x00, &[0, 0, 0x40]);
        chip(&mut f).intf = 0x04;
        assert_eq!(f.read_raw().unwrap(), 0x400000);
        chip(&mut f).intf = 0x04;
        assert!((f.read_voltage().unwrap() - 0.5 * 1.2 / 0.9).abs() < 1e-5);
        chip(&mut f).set(0x00, &[0, 0, 0x80]);
        chip(&mut f).intf = 0x04;
        assert_eq!(f.read_raw().unwrap(), -8_388_608);
    }

    #[test]
    fn timeout_when_no_flag() {
        let mut f = full();
        chip(&mut f).intf = 0;
        assert_eq!(f.read_raw(), Err(Hy3131Error::Timeout));
    }

    #[test]
    fn latched_flag_survives_data_ready() {
        let mut f = full();
        chip(&mut f).intf = 0x04;
        assert!(f.data_ready().unwrap());
        chip(&mut f).set(0x00, &[1, 0, 0]);
        assert_eq!(f.read_raw().unwrap(), 1);
    }

    #[test]
    fn configure_ad1_and_voltage() {
        let mut f = full();
        f.configure_ad1(4, 2, 3, 1, 1, 3).unwrap();
        let c = chip(&mut f);
        assert_eq!(c.get(0x22), (1 << 5) | (2 << 3) | 4);
        assert_eq!(c.get(0x23), 0x80 | 0x10 | 0x03);
        assert_eq!((c.get(0x25) >> 4) & 3, 3);
        c.set(0x00, &[0, 0, 0x40]);
        c.intf = 0x04;
        let expect = (0.5 * 0.333_333_33 - 0.25) * 1.2 / 3.6;
        assert!((f.read_voltage().unwrap() - expect).abs() < 1e-5);
    }

    #[test]
    fn muxes() {
        let mut f = full();
        f.set_ad1_input(0x8, 0x5, 1).unwrap();
        f.set_ad1_reference(2, 6).unwrap();
        let c = chip(&mut f);
        assert_eq!(c.get(0x24), 0x85);
        assert_eq!((c.get(0x33) >> 2) & 3, 1);
        assert_eq!(c.get(0x28), 0x26);
    }

    #[test]
    fn ad2_lpf_rms_peak() {
        let mut f = full();
        f.enable_ad2(3, 2, 0, false, false, 1, true).unwrap();
        {
            let c = chip(&mut f);
            assert_eq!(c.get(0x26), 0x83);
            assert!(c.get(0x34) & 0x04 != 0 && c.get(0x34) & 0x80 != 0);
            assert_eq!((c.get(0x25) >> 6) & 3, 2);
            c.set(0x03, &[0xFF, 0xFF, 0xFF]);
            c.intf = 0x02;
        }
        assert_eq!(f.read_ad2_raw().unwrap(), -1);
        f.enable_lpf(3).unwrap();
        f.enable_rms().unwrap();
        assert_eq!(chip(&mut f).get(0x29), 0xC0 | (3 << 3));
        chip(&mut f).set(0x09, &[0x00, 0x90, 0x01, 0x00, 0x00]);
        chip(&mut f).intf = 0x10;
        assert!((f.read_rms().unwrap() - 320.0).abs() < 1e-3);
        f.enable_peak_hold(2).unwrap();
        assert_eq!(chip(&mut f).get(0x29) & 0x07, 0x06);
        chip(&mut f).set(0x0E, &[0x00, 0x00, 0xFE, 0x00, 0x00, 0x01]);
        assert_eq!(f.read_peak().unwrap(), (-131_072, 65_536));
    }

    #[test]
    fn counter_and_flags() {
        let mut f = full();
        f.start_counter(0x1234).unwrap();
        let c = chip(&mut f);
        assert_eq!((c.get(0x1C), c.get(0x1D)), (0x34, 0x12));
        assert_eq!(c.get(0x20) & 0x0A, 0x0A);
        c.set(0x14, &[0x01]);
        c.intf = 0x01;
        assert!(f.read_counter().unwrap().3);
        chip(&mut f).intf = 0x14;
        assert_eq!(f.read_flags().unwrap(), 0x14);
        f.enable_interrupts(0xFF).unwrap();
        assert_eq!(chip(&mut f).get(0x1F), 0x1F);
    }
}
