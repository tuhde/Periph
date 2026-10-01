//! I²C bus auto-discovery (host targets; needs the `std` feature).
//!
//! [`scan`] enumerates the addresses that respond on a bus; [`discover`] maps
//! them to the chips in the generated [`CHIPS`] registry (built from
//! `registry/chips.json`) and confirms a chip only when an identity-register
//! read matches exactly one candidate. Everything else is reported as
//! candidates. See `specs/feature_i2c_discovery.md`.
//!
//! Generic over `embedded-hal` 1.0 [`I2c`]. Kernel-bound addresses (`EBUSY`)
//! are recognised on Linux through [`default_classify`]; pass your own
//! classifier to [`scan_detailed_with`] for other HALs.

mod registry;

use std::collections::BTreeMap;
use std::fmt::Debug;

use embedded_hal::i2c::ErrorKind;

use crate::connection::i2c::I2c;

pub use registry::{ChipEntry, IdProbe, CHIPS};

/// First address probed by default.
pub const FIRST_ADDRESS: u8 = 0x08;
/// Last address probed by default.
pub const LAST_ADDRESS: u8 = 0x77;

// i2cdetect-compatible policy: EEPROM-class ranges are probed with a read byte.
const READ_BYTE_RANGES: [(u8, u8); 2] = [(0x30, 0x37), (0x50, 0x5F)];

/// What one probe of one address told us.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Probe {
    /// The device ACKed.
    Present,
    /// NACK: nothing at this address.
    Absent,
    /// A kernel driver owns the address (`EBUSY`); counts as present.
    KernelBound,
    /// Any other bus error.
    Failed,
}

/// Why `discover` did not run an identity probe on an address.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProbeSkipReason {
    /// The address is owned by a kernel driver.
    KernelBound,
    /// A candidate treats a stray write as data; pass `active = true` to probe anyway.
    WriteSensitiveCandidate,
}

/// One responding address (or merged alias block) and what it could be.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveredDevice {
    /// 7-bit address (lowest address of an alias block).
    pub address: u8,
    /// Registry chip ids that could be here; empty when unknown to the registry.
    pub candidates: Vec<&'static str>,
    /// Chip id confirmed by an identity read.
    pub identified: Option<&'static str>,
    /// Driver name of the identified chip, if it has one.
    pub driver: Option<&'static str>,
    /// True when a kernel driver owns the address.
    pub in_use_by_kernel: bool,
    /// Set when an identity probe was not run.
    pub probe_skipped_reason: Option<ProbeSkipReason>,
    /// Other addresses merged into this device (24AA02UID).
    pub aliases: Vec<u8>,
}

impl DiscoveredDevice {
    fn new(address: u8) -> Self {
        Self {
            address,
            candidates: Vec::new(),
            identified: None,
            driver: None,
            in_use_by_kernel: false,
            probe_skipped_reason: None,
            aliases: Vec::new(),
        }
    }
}

/// Default error classifier: `NoAcknowledge` is absent; on Linux (`linux-embedded-hal`)
/// `EBUSY` is kernel-bound and `EREMOTEIO` is absent. The Linux cases are recognised by
/// the error's `Debug` text because `embedded-hal` folds them into `Bus` / `Other`.
pub fn default_classify<E: embedded_hal::i2c::Error + Debug>(e: &E) -> Probe {
    match e.kind() {
        ErrorKind::NoAcknowledge(_) => Probe::Absent,
        kind => {
            let text = format!("{:?}", e);
            if kind == ErrorKind::Bus && (text.contains("EBUSY") || text.contains("code: 16,")) {
                Probe::KernelBound
            } else if text.contains("EREMOTEIO") || text.contains("code: 121,") {
                Probe::Absent
            } else {
                Probe::Failed
            }
        }
    }
}

fn uses_read_byte(addr: u8) -> bool {
    READ_BYTE_RANGES.iter().any(|&(lo, hi)| addr >= lo && addr <= hi)
}

fn probe_once<I2C: I2c>(
    i2c: &mut I2C,
    addr: u8,
    read: bool,
    classify: &impl Fn(&I2C::Error) -> Probe,
) -> (Probe, Option<I2C::Error>) {
    let result = if read {
        i2c.read(addr, &mut [0u8; 1])
    } else {
        i2c.write(addr, &[])
    };
    match result {
        Ok(()) => (Probe::Present, None),
        Err(e) => (classify(&e), Some(e)),
    }
}

/// Scan with a custom error classifier.
///
/// Returns `address -> in_use_by_kernel` for every address that responded.
/// A zero-length write is the quick write; if it fails with something other
/// than a NACK the address is retried with a read byte. When every probed
/// address failed with a non-NACK error the last error is returned instead of
/// an empty map.
pub fn scan_detailed_with<I2C: I2c>(
    i2c: &mut I2C,
    first: u8,
    last: u8,
    classify: impl Fn(&I2C::Error) -> Probe,
) -> Result<BTreeMap<u8, bool>, I2C::Error> {
    let mut found = BTreeMap::new();
    let mut failures = 0usize;
    let mut last_error = None;
    let total = if last >= first { (last - first) as usize + 1 } else { 0 };
    for addr in first..=last {
        let read_first = uses_read_byte(addr);
        let (mut outcome, mut error) = probe_once(i2c, addr, read_first, &classify);
        if outcome == Probe::Failed && !read_first {
            (outcome, error) = probe_once(i2c, addr, true, &classify);
        }
        match outcome {
            Probe::Present => {
                found.insert(addr, false);
            }
            Probe::KernelBound => {
                found.insert(addr, true);
            }
            Probe::Absent => {}
            Probe::Failed => {
                failures += 1;
                last_error = error;
            }
        }
    }
    if failures > 0 && failures == total {
        if let Some(e) = last_error {
            return Err(e);
        }
    }
    Ok(found)
}

/// Scan using [`default_classify`]; returns `address -> in_use_by_kernel`.
pub fn scan_detailed<I2C: I2c>(i2c: &mut I2C, first: u8, last: u8) -> Result<BTreeMap<u8, bool>, I2C::Error>
where
    I2C::Error: Debug,
{
    scan_detailed_with(i2c, first, last, |e| default_classify(e))
}

/// Sorted 7-bit addresses that respond on the bus (`0x08`-`0x77`).
pub fn scan<I2C: I2c>(i2c: &mut I2C) -> Result<Vec<u8>, I2C::Error>
where
    I2C::Error: Debug,
{
    Ok(scan_detailed(i2c, FIRST_ADDRESS, LAST_ADDRESS)?.keys().copied().collect())
}

type ProbeKey = (u32, usize, usize, bool);

fn read_identity<I2C: I2c>(
    i2c: &mut I2C,
    addr: u8,
    probe: &IdProbe,
    cache: &mut BTreeMap<ProbeKey, Option<u32>>,
) -> Option<u32> {
    let key = (probe.register, probe.reg_bytes, probe.length, probe.little_endian);
    *cache.entry(key).or_insert_with(|| {
        let reg = probe.register.to_be_bytes();
        let mut buf = [0u8; 4];
        let data = &mut buf[..probe.length];
        i2c.write_read(addr, &reg[4 - probe.reg_bytes..], data).ok()?;
        let mut value = 0u32;
        for i in 0..probe.length {
            let b = if probe.little_endian { data[probe.length - 1 - i] } else { data[i] };
            value = (value << 8) | b as u32;
        }
        Some(value)
    })
}

fn sorted_ids(chips: &[&ChipEntry]) -> Vec<&'static str> {
    let mut ids: Vec<&'static str> = chips.iter().map(|c| c.id).collect();
    ids.sort_unstable();
    ids
}

fn classify_address<I2C: I2c>(
    i2c: &mut I2C,
    addr: u8,
    cands: &[&ChipEntry],
    in_use: bool,
    active: bool,
) -> DiscoveredDevice {
    let mut dev = DiscoveredDevice::new(addr);
    dev.in_use_by_kernel = in_use;
    if cands.is_empty() {
        return dev;
    }
    dev.candidates = sorted_ids(cands);
    if in_use {
        dev.probe_skipped_reason = Some(ProbeSkipReason::KernelBound);
        return dev;
    }
    let probed: Vec<&&ChipEntry> = cands.iter().filter(|c| c.probe.is_some()).collect();
    if probed.is_empty() {
        return dev;
    }
    if !active && cands.iter().any(|c| c.write_sensitive) {
        dev.probe_skipped_reason = Some(ProbeSkipReason::WriteSensitiveCandidate);
        return dev;
    }

    let mut cache = BTreeMap::new();
    let mut matched: Vec<&ChipEntry> = Vec::new();
    for chip in probed {
        let probe = chip.probe.as_ref().unwrap();
        if let Some(value) = read_identity(i2c, addr, probe, &mut cache) {
            if probe.expected.contains(&(value & probe.mask)) {
                matched.push(chip);
            }
        }
    }
    match matched.len() {
        1 => {
            dev.candidates = vec![matched[0].id];
            dev.identified = Some(matched[0].id);
            dev.driver = matched[0].driver;
        }
        0 => {
            let id_less: Vec<&ChipEntry> = cands.iter().filter(|c| c.probe.is_none()).copied().collect();
            dev.candidates = sorted_ids(&id_less);
        }
        _ => dev.candidates = sorted_ids(&matched),
    }
    dev
}

/// Scan the bus and name the chips that are connected, using a custom registry.
///
/// See [`discover`] for the result semantics.
pub fn discover_with<I2C: I2c>(
    i2c: &mut I2C,
    registry: &'static [ChipEntry],
    active: bool,
) -> Result<Vec<DiscoveredDevice>, I2C::Error>
where
    I2C::Error: Debug,
{
    let present = scan_detailed(i2c, FIRST_ADDRESS, LAST_ADDRESS)?;
    let mut by_addr: BTreeMap<u8, Vec<&ChipEntry>> = BTreeMap::new();
    for chip in registry {
        for &a in chip.addresses {
            by_addr.entry(a).or_default().push(chip);
        }
    }

    let mut devices = Vec::new();
    let mut merged = Vec::new();
    for chip in registry.iter().filter(|c| c.aliased) {
        if chip.addresses.iter().all(|a| present.contains_key(a)) {
            let mut dev = DiscoveredDevice::new(chip.addresses[0]);
            // A full block also fits any non-aliased chip that lists every address (24AA025UID).
            let twins: Vec<&ChipEntry> = registry
                .iter()
                .filter(|o| o.id != chip.id && !o.aliased && chip.addresses.iter().all(|a| o.addresses.contains(a)))
                .collect();
            let mut block = vec![chip];
            block.extend(twins);
            dev.candidates = sorted_ids(&block);
            dev.in_use_by_kernel = chip.addresses.iter().any(|a| present[a]);
            dev.aliases = chip.addresses[1..].to_vec();
            devices.push(dev);
            merged.extend_from_slice(chip.addresses);
        }
    }
    for (&addr, &in_use) in &present {
        if !merged.contains(&addr) {
            let cands = by_addr.get(&addr).cloned().unwrap_or_default();
            devices.push(classify_address(i2c, addr, &cands, in_use, active));
        }
    }
    devices.sort_by_key(|d| d.address);
    Ok(devices)
}

/// Scan the bus and name the chips that are connected.
///
/// `identified` is set only when an identity-register read matches exactly
/// one chip; otherwise `candidates` lists what the address could be.
/// Addresses with a write-sensitive candidate are not probed unless `active`
/// is true.
pub fn discover<I2C: I2c>(i2c: &mut I2C, active: bool) -> Result<Vec<DiscoveredDevice>, I2C::Error>
where
    I2C::Error: Debug,
{
    discover_with(i2c, CHIPS, active)
}

#[cfg(test)]
mod tests;
