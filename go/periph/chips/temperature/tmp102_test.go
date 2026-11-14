package temperature

import (
	"bytes"
	"errors"
	"testing"
)

// newTMP102Connection reuses the word-addressed fake from mcp9808_test.go,
// preloaded with the TMP102 power-on register values. osDone controls
// whether CONFIGURATION reads back with OS = 1.
func newTMP102Connection() *wordConnection {
	return &wordConnection{words: map[uint8]uint16{0x00: 0x0000, 0x01: 0x60A0, 0x02: 0x4B00, 0x03: 0x5000}}
}

func TestTMP102NoBusAccessAtConstruction(t *testing.T) {
	m := newTMP102Connection()
	NewTMP102Minimal(m)
	NewTMP102Full(m)
	if len(m.writes) != 0 {
		t.Errorf("constructor accessed the bus: %x", m.writes)
	}
}

func TestTMP102TemperatureDecoding(t *testing.T) {
	m := newTMP102Connection()
	d := NewTMP102Minimal(m)
	for _, c := range []struct {
		raw  uint16
		want float32
	}{
		{0x1900, 25.0}, {0xE700, -25.0}, {0x7FF0, 127.9375}, {0xC900, -55.0},
		{(2400 << 3) | 1, 150.0}, {0xCE00 | 1, -100.0},
	} {
		m.set(0x00, c.raw)
		if got, _ := d.ReadTemperature(); got != c.want {
			t.Errorf("raw 0x%04X: got %v, want %v", c.raw, got, c.want)
		}
	}
}

func TestTMP102ConfigReadModifyWrite(t *testing.T) {
	m := newTMP102Connection()
	d := NewTMP102Full(m)
	check := func(label string, mask, want uint16) {
		t.Helper()
		if got := m.get(0x01) & mask; got != want {
			t.Errorf("%s: got 0x%04X, want 0x%04X", label, got, want)
		}
	}
	if err := d.SetConversionRate(8); err != nil {
		t.Fatal(err)
	}
	check("rate 8 Hz", 0x00C0, 0x00C0)
	check("reserved preserved", 0x6000, 0x6000)
	_ = d.SetConversionRate(0.25)
	check("rate 0.25 Hz", 0x00C0, 0x0000)
	if err := d.SetConversionRate(2); !errors.Is(err, ErrTMP102InvalidRate) {
		t.Errorf("want ErrTMP102InvalidRate, got %v", err)
	}
	_ = d.SetFaultQueue(6)
	check("fault queue 6", 0x1800, 0x1800)
	_ = d.SetFaultQueue(2)
	check("fault queue 2", 0x1800, 0x0800)
	if err := d.SetFaultQueue(3); !errors.Is(err, ErrTMP102InvalidFaultQueue) {
		t.Errorf("want ErrTMP102InvalidFaultQueue, got %v", err)
	}
	_ = d.SetPolarity(true)
	check("polarity high", 0x0400, 0x0400)
	_ = d.SetPolarity(false)
	check("polarity low", 0x0400, 0)
	_ = d.SetThermostatMode(true)
	check("interrupt mode", 0x0200, 0x0200)
	_ = d.SetThermostatMode(false)
	check("comparator mode", 0x0200, 0)
	_ = d.SetExtendedMode(true)
	check("extended on", 0x0010, 0x0010)
	_ = d.SetExtendedMode(false)
	check("extended off", 0x0010, 0)
	_ = d.Shutdown()
	check("shutdown", 0x0100, 0x0100)
	_ = d.Wake()
	check("wake", 0x0100, 0)
	_ = d.WriteConfig(0xFFFF)
	check("read-only bits masked", 0x602F, 0x6000)
}

func TestTMP102Limits(t *testing.T) {
	m := newTMP102Connection()
	d := NewTMP102Full(m)
	_ = d.SetTHigh(30)
	if got := m.get(0x03); got != 0x1E00 {
		t.Errorf("T_HIGH raw: got 0x%04X", got)
	}
	if got, _ := d.GetTHigh(); got != 30 {
		t.Errorf("T_HIGH roundtrip: %v", got)
	}
	_ = d.SetTLow(-10.5)
	if got, _ := d.GetTLow(); got != -10.5 {
		t.Errorf("T_LOW roundtrip: %v", got)
	}
	_ = d.SetTHigh(500)
	if got, _ := d.GetTHigh(); got != 127.9375 {
		t.Errorf("12-bit clamp: %v", got)
	}
	_ = d.SetExtendedMode(true)
	_ = d.SetTHigh(140)
	if got := m.get(0x03); got != 2240<<3 {
		t.Errorf("extended raw: got 0x%04X", got)
	}
	if got, _ := d.GetTHigh(); got != 140 {
		t.Errorf("extended roundtrip: %v", got)
	}
	_ = d.SetTHigh(1000)
	if got, _ := d.GetTHigh(); got != 150 {
		t.Errorf("clamp 150: %v", got)
	}
}

// osConnection wraps wordConnection so CONFIGURATION reads report OS = 1
// only when done is set.
type osConnection struct {
	*wordConnection
	done bool
}

func (o *osConnection) ReadReg(reg uint32, length int) ([]byte, error) {
	b, err := o.wordConnection.ReadReg(reg, length)
	if err == nil && reg == 0x01 {
		if o.done {
			b[0] |= 0x80
		} else {
			b[0] &^= 0x80
		}
	}
	return b, err
}

func TestTMP102OneShot(t *testing.T) {
	m := &osConnection{wordConnection: newTMP102Connection(), done: true}
	d := NewTMP102Full(m)
	m.set(0x00, 0x1900)
	if got, err := d.OneShot(); err != nil || got != 25 {
		t.Fatalf("one-shot: %v, %v", got, err)
	}
	sawOS := false
	for _, w := range m.writesTo(0x01) {
		if len(w) == 3 && w[1]&0x80 != 0 {
			sawOS = true
		}
	}
	if !sawOS {
		t.Error("OS bit never written")
	}
	if m.get(0x01)&0x0100 != 0 {
		t.Error("running mode not restored")
	}
	m.set(0x01, 0x6180)
	if _, err := d.OneShot(); err != nil {
		t.Fatal(err)
	}
	if m.get(0x01)&0x0100 == 0 {
		t.Error("shutdown not preserved")
	}
	m.done = false
	if _, err := d.OneShot(); !errors.Is(err, ErrTMP102Timeout) {
		t.Errorf("want ErrTMP102Timeout, got %v", err)
	}
}

func TestTMP102AlertStateAndPoll(t *testing.T) {
	m := newTMP102Connection()
	d := NewTMP102Full(m)
	for _, c := range []struct {
		cfg  uint16
		want bool
	}{{0x6020, false}, {0x6000, true}, {0x6420, true}, {0x6400, false}} {
		m.set(0x01, c.cfg)
		if got, _ := d.AlertState(); got != c.want {
			t.Errorf("config 0x%04X: got %v, want %v", c.cfg, got, c.want)
		}
	}
	m.set(0x01, 0x6000)
	m.writes = nil
	if alerting, _ := d.PollInterrupt(); !alerting {
		t.Error("poll should report alerting")
	}
	if len(m.writes) != 2 || !bytes.Equal(m.writes[1], []byte{0x00}) {
		t.Errorf("poll should read CONFIGURATION then TEMPERATURE, got %x", m.writes)
	}
}

func TestTMP102GeneralCallReset(t *testing.T) {
	m := newTMP102Connection()
	d := NewTMP102Full(m)
	if err := d.Reset(); err != nil {
		t.Fatal(err)
	}
	if len(m.writes) != 1 || !bytes.Equal(m.writes[0], []byte{0x00, 0x06}) {
		t.Errorf("reset: got %x", m.writes)
	}
}
