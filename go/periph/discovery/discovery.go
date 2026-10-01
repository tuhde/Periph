// Package discovery is I²C bus auto-discovery for Linux hosts
// (specs/feature_i2c_discovery.md).
//
// Scan enumerates the addresses that respond on a bus; Discover maps them to
// the chips in the generated registry (registry.go, built from
// registry/chips.json) and confirms a chip only when an identity-register read
// matches exactly one candidate. Everything else is reported as candidates.
package discovery

import (
	"errors"
	"sort"

	"golang.org/x/sys/unix"
)

const (
	// FirstAddress is the first address probed by default.
	FirstAddress uint8 = 0x08
	// LastAddress is the last address probed by default.
	LastAddress uint8 = 0x77
)

// Bus is the minimum a discovery run needs from an I²C bus. The Linux
// implementation is returned by Open; tests supply fakes.
type Bus interface {
	// Probe checks one address with a quick write, or a read byte when readByte
	// is set. nil means the device ACKed; unix.EBUSY means a kernel driver owns
	// the address; unix.ENXIO / unix.EREMOTEIO mean no device.
	Probe(addr uint8, readByte bool) error
	// QuickWriteSupported reports whether the adapter supports quick write.
	QuickWriteSupported() bool
	// ReadRegister writes the regBytes-wide big-endian register address, then reads length bytes.
	ReadRegister(addr uint8, reg uint32, regBytes, length int) ([]byte, error)
}

// ProbeSkipReason says why an identity probe was not run.
type ProbeSkipReason string

const (
	// SkipNone means the address was probed (or had nothing to probe).
	SkipNone ProbeSkipReason = ""
	// SkipKernelBound means a kernel driver owns the address.
	SkipKernelBound ProbeSkipReason = "kernel_bound"
	// SkipWriteSensitive means a candidate treats a stray write as data; use active=true to probe anyway.
	SkipWriteSensitive ProbeSkipReason = "write_sensitive_candidate"
)

// Device is one responding address (or merged alias block) and what it could be.
type Device struct {
	Address       uint8    // 7-bit address (lowest address of an alias block)
	Candidates    []string // registry chip ids that could be here; empty when unknown
	Identified    string   // chip id confirmed by an identity read, else ""
	Driver        string   // driver name of the identified chip, if any
	InUseByKernel bool     // a kernel driver owns the address (EBUSY)
	ProbeSkipped  ProbeSkipReason
	Aliases       []uint8 // other addresses merged into this device (24AA02UID)
}

// readByteRanges mirrors i2cdetect: EEPROM-class ranges are probed with a read byte.
var readByteRanges = [][2]uint8{{0x30, 0x37}, {0x50, 0x5F}}

func usesReadByte(addr uint8) bool {
	for _, r := range readByteRanges {
		if addr >= r[0] && addr <= r[1] {
			return true
		}
	}
	return false
}

// ScanDetailed probes first..last on b and returns address -> inUseByKernel for
// every address that responded. If every probed address failed with something
// other than a NACK it returns that error instead of an empty map.
func ScanDetailed(b Bus, first, last uint8) (map[uint8]bool, error) {
	found := map[uint8]bool{}
	quick := b.QuickWriteSupported()
	failures := 0
	var lastErr error
	for addr := int(first); addr <= int(last); addr++ {
		err := b.Probe(uint8(addr), !quick || usesReadByte(uint8(addr)))
		switch {
		case err == nil:
			found[uint8(addr)] = false
		case errors.Is(err, unix.EBUSY):
			found[uint8(addr)] = true
		case errors.Is(err, unix.ENXIO), errors.Is(err, unix.EREMOTEIO):
		default:
			failures++
			lastErr = err
		}
	}
	if total := int(last) - int(first) + 1; failures > 0 && failures == total {
		return nil, lastErr
	}
	return found, nil
}

// ScanBus returns the sorted responding addresses of b over 0x08-0x77.
func ScanBus(b Bus) ([]uint8, error) {
	found, err := ScanDetailed(b, FirstAddress, LastAddress)
	if err != nil {
		return nil, err
	}
	out := make([]uint8, 0, len(found))
	for a := range found {
		out = append(out, a)
	}
	sort.Slice(out, func(i, j int) bool { return out[i] < out[j] })
	return out, nil
}

type probeKey struct {
	register uint32
	regBytes int
	length   int
	little   bool
}

func readIdentity(b Bus, addr uint8, p *IdProbe, cache map[probeKey]*uint32) *uint32 {
	key := probeKey{p.Register, p.RegBytes, p.Length, p.LittleEndian}
	if v, ok := cache[key]; ok {
		return v
	}
	var out *uint32
	if data, err := b.ReadRegister(addr, p.Register, p.RegBytes, p.Length); err == nil && len(data) == p.Length {
		var v uint32
		for i := 0; i < p.Length; i++ {
			by := data[i]
			if p.LittleEndian {
				by = data[p.Length-1-i]
			}
			v = v<<8 | uint32(by)
		}
		out = &v
	}
	cache[key] = out
	return out
}

func sortedIDs(chips []*Chip) []string {
	ids := make([]string, 0, len(chips))
	for _, c := range chips {
		ids = append(ids, c.ID)
	}
	sort.Strings(ids)
	return ids
}

// coversAll reports whether have contains every address in want.
func coversAll(have, want []uint8) bool {
	set := map[uint8]bool{}
	for _, a := range have {
		set[a] = true
	}
	for _, a := range want {
		if !set[a] {
			return false
		}
	}
	return true
}

func contains(list []uint32, v uint32) bool {
	for _, x := range list {
		if x == v {
			return true
		}
	}
	return false
}

func classify(b Bus, addr uint8, cands []*Chip, inUse, active bool) Device {
	dev := Device{Address: addr, InUseByKernel: inUse}
	if len(cands) == 0 {
		return dev
	}
	dev.Candidates = sortedIDs(cands)
	if inUse {
		dev.ProbeSkipped = SkipKernelBound
		return dev
	}
	var probed []*Chip
	writeSensitive := false
	for _, c := range cands {
		if c.Probe != nil {
			probed = append(probed, c)
		}
		writeSensitive = writeSensitive || c.WriteSensitive
	}
	if len(probed) == 0 {
		return dev
	}
	if !active && writeSensitive {
		dev.ProbeSkipped = SkipWriteSensitive
		return dev
	}

	cache := map[probeKey]*uint32{}
	var matched []*Chip
	for _, c := range probed {
		if v := readIdentity(b, addr, c.Probe, cache); v != nil && contains(c.Probe.Expected, *v&c.Probe.Mask) {
			matched = append(matched, c)
		}
	}
	switch len(matched) {
	case 1:
		dev.Candidates = []string{matched[0].ID}
		dev.Identified = matched[0].ID
		dev.Driver = matched[0].Driver
	case 0:
		var idLess []*Chip
		for _, c := range cands {
			if c.Probe == nil {
				idLess = append(idLess, c)
			}
		}
		dev.Candidates = sortedIDs(idLess)
	default:
		dev.Candidates = sortedIDs(matched)
	}
	return dev
}

// DiscoverBus scans b and names the chips that are connected, using registry.
//
// Identified is set only when an identity-register read matches exactly one
// chip; otherwise Candidates lists what the address could be. Addresses with a
// write-sensitive candidate are not probed unless active is true.
func DiscoverBus(b Bus, registry []Chip, active bool) ([]Device, error) {
	present, err := ScanDetailed(b, FirstAddress, LastAddress)
	if err != nil {
		return nil, err
	}
	byAddr := map[uint8][]*Chip{}
	for i := range registry {
		for _, a := range registry[i].Addresses {
			byAddr[a] = append(byAddr[a], &registry[i])
		}
	}

	var devices []Device
	merged := map[uint8]bool{}
	for i := range registry {
		c := &registry[i]
		if !c.Aliased {
			continue
		}
		all, busy := true, false
		for _, a := range c.Addresses {
			inUse, ok := present[a]
			all = all && ok
			busy = busy || inUse
		}
		if all {
			// A full block also fits any non-aliased chip that lists every address (24AA025UID).
			block := []*Chip{c}
			for j := range registry {
				o := &registry[j]
				if o != c && !o.Aliased && coversAll(o.Addresses, c.Addresses) {
					block = append(block, o)
				}
			}
			devices = append(devices, Device{
				Address: c.Addresses[0], Candidates: sortedIDs(block), InUseByKernel: busy,
				Aliases: append([]uint8(nil), c.Addresses[1:]...),
			})
			for _, a := range c.Addresses {
				merged[a] = true
			}
		}
	}
	for addr, inUse := range present {
		if !merged[addr] {
			devices = append(devices, classify(b, addr, byAddr[addr], inUse, active))
		}
	}
	sort.Slice(devices, func(i, j int) bool { return devices[i].Address < devices[j].Address })
	return devices, nil
}
