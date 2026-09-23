package temperature

import (
	"bytes"
	"errors"
	"testing"
	"time"
)

// newTMP117Connection reuses the word-addressed fake from mcp9808_test.go,
// preloaded with the TMP117 power-on register values.
func newTMP117Connection() *wordConnection {
	return &wordConnection{words: map[uint8]uint16{
		0x00: 0x8000, 0x01: 0x0220, 0x02: 0x6000, 0x03: 0x8000, 0x0F: 0x1117,
	}}
}

func newTMP117Full(t *testing.T) (*TMP117Full, *wordConnection) {
	t.Helper()
	m := newTMP117Connection()
	d, err := NewTMP117Full(m)
	if err != nil {
		t.Fatal(err)
	}
	return d, m
}

func TestTMP117IdentityCheck(t *testing.T) {
	m := newTMP117Connection()
	if _, err := NewTMP117Minimal(m); err != nil {
		t.Fatal(err)
	}
	if len(m.writes) != 1 || !bytes.Equal(m.writes[0], []byte{0x0F}) {
		t.Errorf("init should only read DEVICE_ID, got %x", m.writes)
	}
	m.set(0x0F, 0x2117) // revision nibble is ignored
	if _, err := NewTMP117Minimal(m); err != nil {
		t.Errorf("revision should be ignored: %v", err)
	}
	m.set(0x0F, 0x0118)
	if _, err := NewTMP117Minimal(m); !errors.Is(err, ErrTMP117NotFound) {
		t.Errorf("want ErrTMP117NotFound, got %v", err)
	}
}

func TestTMP117TemperatureDecoding(t *testing.T) {
	m := newTMP117Connection()
	d, err := NewTMP117Minimal(m)
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range []struct {
		raw  uint16
		want float32
	}{{0x0C80, 25.0}, {0xFFFF, -0.0078125}, {0xF380, -25.0}, {0x8000, -256.0}, {0x7FFF, 255.9921875}} {
		m.set(0x00, c.raw)
		if got, _ := d.ReadTemperature(); got != c.want {
			t.Errorf("raw 0x%04X: got %v, want %v", c.raw, got, c.want)
		}
	}
}

func TestTMP117LimitsAndOffset(t *testing.T) {
	d, m := newTMP117Full(t)
	check := func(name string, reg uint8, want uint16) {
		t.Helper()
		if got := m.get(reg); got != want {
			t.Errorf("%s: raw 0x%04X, want 0x%04X", name, got, want)
		}
	}
	_ = d.SetHighLimit(30.0)
	check("high", 0x02, 0x0F00)
	if v, _ := d.GetHighLimit(); v != 30.0 {
		t.Errorf("GetHighLimit = %v", v)
	}
	_ = d.SetLowLimit(-10.25)
	check("low", 0x03, 0xFAE0)
	if v, _ := d.GetLowLimit(); v != -10.25 {
		t.Errorf("GetLowLimit = %v", v)
	}
	_ = d.SetLowLimit(0.004)
	check("rounding", 0x03, 0x0001)
	_ = d.SetHighLimit(1000)
	check("clamp high", 0x02, 0x7FFF)
	_ = d.SetLowLimit(-1000)
	check("clamp low", 0x03, 0x8000)
	_ = d.SetTemperatureOffset(-0.5)
	check("offset", 0x07, 0xFFC0)
	if v, _ := d.GetTemperatureOffset(); v != -0.5 {
		t.Errorf("GetTemperatureOffset = %v", v)
	}
}

func TestTMP117ConversionConfig(t *testing.T) {
	d, m := newTMP117Full(t)
	if c, _ := d.GetConfig(); c != (TMP117Config{TMP117Continuous, 8, 1.0}) {
		t.Errorf("default config = %+v", c)
	}
	steps := []struct {
		mode  TMP117Mode
		avg   uint8
		cycle float32
		want  uint16
	}{
		{TMP117Shutdown, 64, 16.0, 0x07E0},
		{TMP117Continuous, 0, 0.01, 0x0000},
		{TMP117Continuous, 8, 0.3, 0x0120}, // nearest step 250 ms
		{TMP117OneShot, 32, 2.0, 0x0E40},   // nearest step 1 s
	}
	for _, s := range steps {
		if err := d.Configure(s.mode, s.avg, s.cycle); err != nil {
			t.Fatal(err)
		}
		if got := m.get(0x01); got != s.want {
			t.Errorf("Configure(%v, %v, %v) = 0x%04X, want 0x%04X", s.mode, s.avg, s.cycle, got, s.want)
		}
	}
	if c, _ := d.GetConfig(); c != (TMP117Config{TMP117OneShot, 32, 1.0}) {
		t.Errorf("one-shot config = %+v", c)
	}
	m.set(0x01, 0x07E0)
	if s, _ := d.IsShutdown(); !s {
		t.Error("IsShutdown = false")
	}
	m.set(0x01, 0x0800)
	if c, _ := d.GetConfig(); c.Mode != TMP117Continuous {
		t.Error("MOD=10 should read as continuous")
	}
	m.set(0x01, 0xF01C)
	_ = d.Configure(TMP117Continuous, 8, 1.0)
	if got := m.get(0x01); got != 0x023C {
		t.Errorf("Configure should preserve alert bits, got 0x%04X", got)
	}
	if err := d.Configure(TMP117Continuous, 16, 1.0); !errors.Is(err, ErrTMP117InvalidAveraging) {
		t.Errorf("want ErrTMP117InvalidAveraging, got %v", err)
	}
	m.set(0x01, 0xE660)
	_ = d.TriggerOneShot()
	if got := m.get(0x01); got != 0x0E60 {
		t.Errorf("TriggerOneShot = 0x%04X", got)
	}
	m.set(0x01, 0x2220)
	if r, _ := d.IsDataReady(); !r {
		t.Error("IsDataReady = false")
	}
	_ = d.Reset()
	w := m.writesTo(0x01)
	if !bytes.Equal(w[len(w)-1], []byte{0x01, 0x00, 0x02}) {
		t.Errorf("Reset write = %x", w[len(w)-1])
	}
}

func TestTMP117Eeprom(t *testing.T) {
	d, m := newTMP117Full(t)
	_ = d.UnlockEeprom()
	if m.get(0x04) != 0x8000 {
		t.Error("UnlockEeprom")
	}
	_ = d.LockEeprom()
	if m.get(0x04) != 0x0000 {
		t.Error("LockEeprom")
	}
	m.set(0x04, 0x4000)
	if b, _ := d.IsEepromBusy(); !b {
		t.Error("IsEepromBusy = false")
	}
	m.set(0x05, 0x1111)
	m.set(0x06, 0x2222)
	m.set(0x08, 0x3333)
	for slot, want := range map[uint8]uint16{1: 0x1111, 2: 0x2222, 3: 0x3333} {
		if v, _ := d.ReadEepromScratch(slot); v != want {
			t.Errorf("ReadEepromScratch(%d) = 0x%04X", slot, v)
		}
	}
	if _, err := d.ReadEepromScratch(4); !errors.Is(err, ErrTMP117InvalidSlot) {
		t.Errorf("slot 4: %v", err)
	}
	_ = d.WriteEepromScratch(2, 0xBEEF)
	if m.get(0x06) != 0xBEEF {
		t.Error("WriteEepromScratch(2)")
	}
	for _, slot := range []uint8{1, 3} {
		if err := d.WriteEepromScratch(slot, 0); !errors.Is(err, ErrTMP117InvalidSlot) {
			t.Errorf("slot %d write: %v", slot, err)
		}
	}
	if m.get(0x05) != 0x1111 || m.get(0x08) != 0x3333 {
		t.Error("factory slots modified")
	}
}

func TestTMP117AlertAndInterrupt(t *testing.T) {
	d, m := newTMP117Full(t)
	_ = d.ConfigureAlert(TMP117AlertTherm, TMP117AlertActiveHigh, TMP117PinDataReady)
	if got := m.get(0x01); got != 0x023C {
		t.Errorf("ConfigureAlert = 0x%04X", got)
	}
	_ = d.ConfigureAlert(TMP117AlertWindow, TMP117AlertActiveLow, TMP117PinAlert)
	if got := m.get(0x01); got != 0x0220 {
		t.Errorf("ConfigureAlert defaults = 0x%04X", got)
	}
	for raw, want := range map[uint16]uint8{
		0x2220: 0, 0x8220: TMP117SourceHigh, 0x4220: TMP117SourceLow, 0xC220: TMP117SourceHigh | TMP117SourceLow,
	} {
		m.set(0x01, raw)
		if s, _ := d.PollInterrupt(); s != want {
			t.Errorf("PollInterrupt(0x%04X) = %d, want %d", raw, s, want)
		}
	}

	// Polling fallback (no IntPin): calls back only when the mask changes.
	m.set(0x01, 0x0220)
	calls := make(chan uint8, 8)
	if err := d.OnInterrupt(func(s uint8) { calls <- s }); err != nil {
		t.Fatal(err)
	}
	defer d.OffInterrupt()
	select {
	case s := <-calls:
		t.Fatalf("unexpected callback %d", s)
	case <-time.After(30 * time.Millisecond):
	}
	m.set(0x01, 0x8220)
	select {
	case s := <-calls:
		if s != TMP117SourceHigh {
			t.Errorf("callback status %d", s)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatal("no callback on status change")
	}
}
