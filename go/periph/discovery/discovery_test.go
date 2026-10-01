package discovery

import (
	"errors"
	"reflect"
	"testing"

	"golang.org/x/sys/unix"
)

// fakeBus: devices maps address -> {register: byte} (missing registers read 0xFF).
type fakeBus struct {
	devices     map[uint8]map[uint32]byte
	busy        map[uint8]bool
	noQuick     bool
	broken      bool
	readError   bool
	probes      []probeCall
	registerOps [][2]uint32 // {addr, reg} of ReadRegister calls
	regBytes    []int
}

type probeCall struct {
	addr uint8
	read bool
}

func newBus(devs map[uint8]map[uint32]byte) *fakeBus {
	return &fakeBus{devices: devs, busy: map[uint8]bool{}}
}

func (f *fakeBus) QuickWriteSupported() bool { return !f.noQuick }

func (f *fakeBus) Probe(addr uint8, readByte bool) error {
	f.probes = append(f.probes, probeCall{addr, readByte})
	if f.broken {
		return unix.EIO
	}
	if f.busy[addr] {
		return unix.EBUSY
	}
	if _, ok := f.devices[addr]; !ok {
		return unix.ENXIO
	}
	return nil
}

func (f *fakeBus) ReadRegister(addr uint8, reg uint32, regBytes, length int) ([]byte, error) {
	if f.readError {
		return nil, unix.EIO
	}
	regs, ok := f.devices[addr]
	if !ok {
		return nil, unix.ENXIO
	}
	f.registerOps = append(f.registerOps, [2]uint32{uint32(addr), reg})
	f.regBytes = append(f.regBytes, regBytes)
	out := make([]byte, length)
	for i := range out {
		if v, ok := regs[reg+uint32(i)]; ok {
			out[i] = v
		} else {
			out[i] = 0xFF
		}
	}
	return out, nil
}

func one(t *testing.T, devs map[uint8]map[uint32]byte, active bool) Device {
	t.Helper()
	got, err := DiscoverBus(newBus(devs), registry, active)
	if err != nil || len(got) == 0 {
		t.Fatalf("discover: %v %v", got, err)
	}
	return got[0]
}

func single(addr uint8, regs map[uint32]byte) map[uint8]map[uint32]byte {
	return map[uint8]map[uint32]byte{addr: regs}
}

func TestScanFindsAllAndPicksProbeMethod(t *testing.T) {
	b := newBus(map[uint8]map[uint32]byte{0x76: {}, 0x50: {}, 0x1B: {}})
	got, err := ScanBus(b)
	if err != nil || !reflect.DeepEqual(got, []uint8{0x1B, 0x50, 0x76}) {
		t.Fatalf("scan = %v, %v", got, err)
	}
	read := map[uint8]bool{}
	for _, p := range b.probes {
		read[p.addr] = p.read
	}
	if read[0x76] || read[0x08] || !read[0x50] || !read[0x30] || !read[0x5F] {
		t.Errorf("wrong probe methods: %v", read)
	}
	if b.probes[0].addr != 0x08 || b.probes[len(b.probes)-1].addr != 0x77 {
		t.Errorf("range = %#x..%#x", b.probes[0].addr, b.probes[len(b.probes)-1].addr)
	}
}

func TestScanFallsBackToReadByteWithoutQuickWrite(t *testing.T) {
	b := newBus(map[uint8]map[uint32]byte{0x76: {}})
	b.noQuick = true
	if _, err := ScanBus(b); err != nil {
		t.Fatal(err)
	}
	for _, p := range b.probes {
		if !p.read {
			t.Fatalf("address %#x probed with quick write", p.addr)
		}
	}
}

func TestScanEBusyIsPresentAndFlagged(t *testing.T) {
	b := newBus(map[uint8]map[uint32]byte{0x40: {}})
	b.busy[0x42] = true
	got, err := ScanDetailed(b, FirstAddress, LastAddress)
	if err != nil || !reflect.DeepEqual(got, map[uint8]bool{0x40: false, 0x42: true}) {
		t.Fatalf("got %v, %v", got, err)
	}
}

func TestScanErrorsWhenEveryAddressFails(t *testing.T) {
	b := newBus(nil)
	b.broken = true
	if _, err := ScanBus(b); !errors.Is(err, unix.EIO) {
		t.Fatalf("err = %v", err)
	}
	if got, err := ScanBus(newBus(nil)); err != nil || len(got) != 0 {
		t.Fatalf("empty bus = %v, %v", got, err)
	}
}

func TestIdentifiesChips(t *testing.T) {
	table := []struct {
		id    string
		reg   uint32
		value byte
		addr  uint8
	}{
		{"bme280", 0xD0, 0x60, 0x76}, {"bmp280", 0xD0, 0x58, 0x76}, {"bme680", 0xD0, 0x61, 0x77},
		{"bmp384", 0x00, 0x50, 0x76}, {"mpu6050", 0x75, 0x68, 0x68}, {"mpu9250", 0x75, 0x71, 0x68},
		{"mpu9255", 0x75, 0x73, 0x69}, {"l3g4200d", 0x0F, 0xD3, 0x68}, {"lps33hw", 0x0F, 0xB1, 0x5C},
		{"adxl345", 0x00, 0xE5, 0x53}, {"vl53l0x", 0xC0, 0xEE, 0x29}, {"mfrc522", 0x37, 0x92, 0x28},
	}
	for _, tc := range table {
		if d := one(t, single(tc.addr, map[uint32]byte{tc.reg: tc.value}), false); d.Identified != tc.id {
			t.Errorf("%s: identified %q (candidates %v)", tc.id, d.Identified, d.Candidates)
		}
	}
	if d := one(t, single(0x76, map[uint32]byte{0xD0: 0x60}), false); d.Driver != "bme280" {
		t.Errorf("driver = %q", d.Driver)
	}
}

func TestIdentifiesMultiByteAndMaskedRegisters(t *testing.T) {
	cases := []struct {
		id   string
		addr uint8
		regs map[uint32]byte
	}{
		{"ens160", 0x52, map[uint32]byte{0x00: 0x60, 0x01: 0x01}},
		{"ina226", 0x40, map[uint32]byte{0xFF: 0x22, 0x100: 0x60}},
		{"ina3221", 0x40, map[uint32]byte{0xFF: 0x32, 0x100: 0x20}},
		{"mcp9808", 0x18, map[uint32]byte{0x07: 0x04, 0x08: 0x01}},
		{"hmc5883l", 0x1E, map[uint32]byte{0x0A: 0x48, 0x0B: 0x34, 0x0C: 0x33}},
		{"vl53l1x", 0x29, map[uint32]byte{0x010F: 0xEA, 0x0110: 0xCC}},
	}
	for _, tc := range cases {
		if d := one(t, single(tc.addr, tc.regs), false); d.Identified != tc.id {
			t.Errorf("%s: identified %q (candidates %v)", tc.id, d.Identified, d.Candidates)
		}
	}
	b := newBus(single(0x29, map[uint32]byte{0x010F: 0xEA, 0x0110: 0xCC}))
	if _, err := DiscoverBus(b, registry, false); err != nil {
		t.Fatal(err)
	}
	wide := false
	for _, w := range b.regBytes {
		wide = wide || w == 2
	}
	if !wide {
		t.Errorf("vl53l1x register never sent as 2 bytes: %v", b.regBytes)
	}
}

func TestWriteSensitiveCandidatesBlockProbingUnlessActive(t *testing.T) {
	regs := map[uint32]byte{0x0F: 0x01, 0x10: 0x17}
	b := newBus(single(0x48, regs))
	got, _ := DiscoverBus(b, registry, false)
	if got[0].Identified != "" || got[0].ProbeSkipped != SkipWriteSensitive || len(b.registerOps) != 0 {
		t.Errorf("not gated: %+v", got[0])
	}
	if d := one(t, single(0x48, regs), true); d.Identified != "tmp117" {
		t.Errorf("active tmp117: %+v", d)
	}
	if d := one(t, single(0x39, map[uint32]byte{0x92: 0xAB}), false); d.ProbeSkipped != SkipWriteSensitive {
		t.Errorf("apds gating: %+v", d)
	}
	if d := one(t, single(0x39, map[uint32]byte{0x92: 0xAB}), true); d.Identified != "apds9960" {
		t.Errorf("apds9960: %+v", d)
	}
	if d := one(t, single(0x39, map[uint32]byte{0x92: 0x39}), true); d.Identified != "apds-9930" {
		t.Errorf("apds-9930: %+v", d)
	}
}

func TestAmbiguityIsAFinalAnswer(t *testing.T) {
	d := one(t, single(0x5C, map[uint32]byte{0x0F: 0xB4}), false)
	if d.Identified != "" || !reflect.DeepEqual(d.Candidates, []string{"lps22df", "lps28dfw"}) {
		t.Errorf("lps: %+v", d)
	}
	d = one(t, single(0x77, map[uint32]byte{0xD0: 0x55}), false)
	if d.Identified != "" || !reflect.DeepEqual(d.Candidates, []string{"bmp085", "bmp180"}) {
		t.Errorf("bmp: %+v", d)
	}
}

func TestFallsBackToIDLessCandidates(t *testing.T) {
	cases := []struct {
		addr     uint8
		want     []string
		noProbes bool // nothing at this address has an identity register
	}{
		{0x68, []string{"drv8830", "ds3231", "pcf8523"}, false},
		{0x40, []string{"ina219"}, false},
		{0x36, []string{"as5600"}, true},
		{0x0B, nil, true},
		{0x38, []string{"ade7953", "aht21", "pcf8574", "pcf8576"}, true},
	}
	for _, tc := range cases {
		b := newBus(single(tc.addr, map[uint32]byte{}))
		got, _ := DiscoverBus(b, registry, false)
		d := got[0]
		if d.Identified != "" || !reflect.DeepEqual(d.Candidates, tc.want) || d.ProbeSkipped != SkipNone || (tc.noProbes && len(b.registerOps) != 0) {
			t.Errorf("%#x: %+v", tc.addr, d)
		}
	}
}

func TestCustomRegistryAndActiveFlag(t *testing.T) {
	reg := []Chip{
		{ID: "pcf-like", WriteSensitive: true, Addresses: []uint8{0x20}},
		{ID: "idchip", Addresses: []uint8{0x20}, Probe: &IdProbe{Register: 0x10, RegBytes: 1, Length: 1, Mask: 0xFF, Expected: []uint32{0x42}}},
	}
	b := newBus(single(0x20, map[uint32]byte{0x10: 0x42}))
	got, _ := DiscoverBus(b, reg, false)
	if got[0].ProbeSkipped != SkipWriteSensitive || len(b.registerOps) != 0 || !reflect.DeepEqual(got[0].Candidates, []string{"idchip", "pcf-like"}) {
		t.Errorf("gated: %+v", got[0])
	}
	b = newBus(single(0x20, map[uint32]byte{0x10: 0x42}))
	got, _ = DiscoverBus(b, reg, true)
	if got[0].Identified != "idchip" || len(b.registerOps) == 0 {
		t.Errorf("active: %+v", got[0])
	}
}

func TestKernelBoundAddressIsReportedNotProbed(t *testing.T) {
	b := newBus(single(0x76, map[uint32]byte{0xD0: 0x60}))
	b.busy[0x77] = true
	got, _ := DiscoverBus(b, registry, false)
	var d Device
	for _, x := range got {
		if x.Address == 0x77 {
			d = x
		}
	}
	if !d.InUseByKernel || d.ProbeSkipped != SkipKernelBound || d.Identified != "" {
		t.Errorf("0x77: %+v", d)
	}
	for _, op := range b.registerOps {
		if op[0] == 0x77 {
			t.Error("kernel-bound address was probed")
		}
	}
}

func TestFailedIdentityReadIsNoMatch(t *testing.T) {
	b := newBus(single(0x76, map[uint32]byte{0xD0: 0x60}))
	b.readError = true
	got, _ := DiscoverBus(b, registry, false)
	if got[0].Identified != "" || len(got[0].Candidates) != 0 {
		t.Errorf("%+v", got[0])
	}
}

func TestAliasedBlockMergedOnlyWhenComplete(t *testing.T) {
	all := map[uint8]map[uint32]byte{}
	for a := uint8(0x50); a <= 0x57; a++ {
		all[a] = map[uint32]byte{}
	}
	got, _ := DiscoverBus(newBus(all), registry, false)
	if len(got) != 1 || got[0].Address != 0x50 || !reflect.DeepEqual(got[0].Candidates, []string{"24aa025uid", "24aa02uid"}) ||
		!reflect.DeepEqual(got[0].Aliases, []uint8{0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57}) {
		t.Errorf("merged: %+v", got)
	}
	got, _ = DiscoverBus(newBus(map[uint8]map[uint32]byte{0x50: {}, 0x51: {}}), registry, false)
	if len(got) != 2 || len(got[0].Aliases) != 0 || len(got[1].Aliases) != 0 {
		t.Errorf("partial: %+v", got)
	}
}

func TestRegistryIsSane(t *testing.T) {
	if len(registry) < 45 {
		t.Fatalf("registry has %d chips", len(registry))
	}
	ids := map[string]bool{}
	for _, c := range registry {
		if ids[c.ID] {
			t.Errorf("duplicate id %s", c.ID)
		}
		ids[c.ID] = true
	}
}
