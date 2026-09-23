package temperature

import (
	"bytes"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// wordConnection is an in-memory fake connection.Connection for unit tests.
// MCP9808 registers are 16 bits wide at consecutive pointer values
// (MANUFACTURER_ID at 0x06, DEVICE_ID at 0x07), so each pointer owns one
// 16-bit word; 1-byte accesses (RESOLUTION) use the word's low byte.
type wordConnection struct {
	mu     sync.Mutex
	words  map[uint8]uint16
	writes [][]byte
}

func newWordConnection() *wordConnection {
	return &wordConnection{words: map[uint8]uint16{0x06: 0x0054, 0x07: 0x0400, 0x08: 0x0003}}
}

func (m *wordConnection) set(reg uint8, value uint16) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.words[reg] = value
}

func (m *wordConnection) get(reg uint8) uint16 {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.words[reg]
}

func (m *wordConnection) Write(data []byte) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.writes = append(m.writes, append([]byte(nil), data...))
	switch len(data) {
	case 3:
		m.words[data[0]] = uint16(data[1])<<8 | uint16(data[2])
	case 2:
		m.words[data[0]] = uint16(data[1])
	}
	return nil
}

func (m *wordConnection) Read(n int) ([]byte, error) { return make([]byte, n), nil }

func (m *wordConnection) WriteRead(data []byte, n int) ([]byte, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.writes = append(m.writes, append([]byte(nil), data...))
	w := m.words[data[0]]
	if n == 1 {
		return []byte{byte(w)}, nil
	}
	return []byte{byte(w >> 8), byte(w)}, nil
}

func (m *wordConnection) Close() error                { return nil }
func (m *wordConnection) Enable()                     {}
func (m *wordConnection) Disable()                    {}
func (m *wordConnection) IsEnabled() bool             { return true }
func (m *wordConnection) IntPin() connection.InputPin { return nil }
func (m *wordConnection) EnPin() connection.OutputPin { return nil }

func (m *wordConnection) writesTo(reg uint8) [][]byte {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out [][]byte
	for _, w := range m.writes {
		if len(w) >= 2 && w[0] == reg {
			out = append(out, w)
		}
	}
	return out
}

func TestMCP9808IdentityCheck(t *testing.T) {
	m := newWordConnection()
	if _, err := NewMCP9808Minimal(m); err != nil {
		t.Fatal(err)
	}
	for _, w := range m.writes {
		if len(w) != 1 {
			t.Fatalf("init wrote a register: % X", w)
		}
	}

	bad := newWordConnection()
	bad.set(0x06, 0x1234)
	if _, err := NewMCP9808Minimal(bad); !errors.Is(err, ErrMCP9808NotFound) {
		t.Fatalf("wrong manufacturer: got %v", err)
	}
	bad = newWordConnection()
	bad.set(0x07, 0x0500)
	if _, err := NewMCP9808Full(bad); !errors.Is(err, ErrMCP9808NotFound) {
		t.Fatalf("wrong device: got %v", err)
	}
	rev := newWordConnection()
	rev.set(0x07, 0x0401)
	if _, err := NewMCP9808Minimal(rev); err != nil {
		t.Fatalf("revision byte must be ignored: %v", err)
	}
}

func TestMCP9808TemperatureDecoding(t *testing.T) {
	m := newWordConnection()
	s, _ := NewMCP9808Minimal(m)
	cases := []struct {
		raw  uint16
		want float32
	}{
		{0x0194, 25.25}, {0xE194, 25.25}, {0x1FF0, -1.0}, {0x1E6C, -25.25}, {0x0001, 0.0625},
	}
	for _, c := range cases {
		m.set(mcp9808RegTA, c.raw)
		got, err := s.ReadTemperature()
		if err != nil || got != c.want {
			t.Errorf("TA=0x%04X: got %v (%v), want %v", c.raw, got, err, c.want)
		}
	}
}

func TestMCP9808Limits(t *testing.T) {
	m := newWordConnection()
	s, _ := NewMCP9808Full(m)
	check := func(label string, got float32, want float32) {
		if got != want {
			t.Errorf("%s: got %v, want %v", label, got, want)
		}
	}
	_ = s.SetUpperLimit(80)
	if m.get(mcp9808RegTUpper) != 0x0500 {
		t.Errorf("upper encode: 0x%04X", m.get(mcp9808RegTUpper))
	}
	v, _ := s.GetUpperLimit()
	check("upper", v, 80)
	_ = s.SetLowerLimit(-25)
	if m.get(mcp9808RegTLower) != 0x1E70 {
		t.Errorf("lower encode: 0x%04X", m.get(mcp9808RegTLower))
	}
	v, _ = s.GetLowerLimit()
	check("lower", v, -25)
	_ = s.SetCriticalLimit(-5.1)
	v, _ = s.GetCriticalLimit()
	check("critical rounds", v, -5)
	_ = s.SetCriticalLimit(22.13)
	v, _ = s.GetCriticalLimit()
	check("critical rounds up", v, 22.25)
	_ = s.SetUpperLimit(1000)
	v, _ = s.GetUpperLimit()
	check("clamp high", v, 255.75)
	_ = s.SetLowerLimit(-1000)
	v, _ = s.GetLowerLimit()
	check("clamp low", v, -256)
}

func TestMCP9808ResolutionAndHysteresis(t *testing.T) {
	m := newWordConnection()
	s, _ := NewMCP9808Full(m)
	if err := s.SetResolution(0.25); err != nil {
		t.Fatal(err)
	}
	w := m.writesTo(mcp9808RegResolution)
	if !bytes.Equal(w[len(w)-1], []byte{0x08, 0x01}) {
		t.Errorf("resolution write: % X", w[len(w)-1])
	}
	if r, _ := s.GetResolution(); r != 0.25 {
		t.Errorf("resolution read: %v", r)
	}
	n := len(m.writesTo(mcp9808RegResolution))
	if err := s.SetResolution(0.3); !errors.Is(err, ErrMCP9808InvalidResolution) {
		t.Errorf("invalid resolution: %v", err)
	}
	if len(m.writesTo(mcp9808RegResolution)) != n {
		t.Error("invalid resolution must not write")
	}

	m.set(mcp9808RegConfig, 0x0000)
	if err := s.SetHysteresis(3.0); err != nil {
		t.Fatal(err)
	}
	if m.get(mcp9808RegConfig) != 0x0400 {
		t.Errorf("hysteresis write: 0x%04X", m.get(mcp9808RegConfig))
	}
	if h, _ := s.GetHysteresis(); h != 3.0 {
		t.Errorf("hysteresis read: %v", h)
	}
	if err := s.SetHysteresis(2.0); !errors.Is(err, ErrMCP9808InvalidHysteresis) {
		t.Errorf("invalid hysteresis: %v", err)
	}
}

func TestMCP9808ConfigShutdownLocksAlert(t *testing.T) {
	m := newWordConnection()
	s, _ := NewMCP9808Full(m)
	expect := func(label string, want uint16) {
		if got := m.get(mcp9808RegConfig); got != want {
			t.Errorf("%s: CONFIG=0x%04X, want 0x%04X", label, got, want)
		}
	}

	m.set(mcp9808RegConfig, 0x0400)
	_ = s.Shutdown()
	expect("shutdown keeps THYST", 0x0500)
	if on, _ := s.IsShutdown(); !on {
		t.Error("IsShutdown after Shutdown")
	}
	_ = s.Wake()
	expect("wake", 0x0400)
	m.set(mcp9808RegConfig, 0x0080)
	n := len(m.writesTo(mcp9808RegConfig))
	_ = s.Shutdown()
	if len(m.writesTo(mcp9808RegConfig)) != n {
		t.Error("shutdown while locked must not write")
	}

	m.set(mcp9808RegConfig, 0x0000)
	_ = s.LockCriticalLimit()
	expect("lock critical", 0x0080)
	if l, _ := s.IsCriticalLimitLocked(); !l {
		t.Error("IsCriticalLimitLocked")
	}
	if l, _ := s.IsWindowLimitsLocked(); l {
		t.Error("IsWindowLimitsLocked should be false")
	}
	m.set(mcp9808RegConfig, 0x0000)
	_ = s.LockWindowLimits()
	expect("lock window", 0x0040)

	m.set(mcp9808RegConfig, 0x0000)
	if err := s.ConfigureAlert(MCP9808AlertCriticalOnly, MCP9808AlertInterrupt, MCP9808AlertActiveHigh); err != nil {
		t.Fatal(err)
	}
	expect("configure alert", 0x0007)
	m.set(mcp9808RegConfig, 0x0040)
	if err := s.ConfigureAlert(MCP9808AlertAll, MCP9808AlertInterrupt, MCP9808AlertActiveLow); !errors.Is(err, ErrMCP9808Locked) {
		t.Errorf("locked configure: %v", err)
	}
	expect("locked configure writes nothing", 0x0040)

	m.set(mcp9808RegConfig, 0x0000)
	_ = s.EnableAlert()
	expect("enable alert", 0x0008)
	_ = s.DisableAlert()
	expect("disable alert", 0x0000)

	m.set(mcp9808RegConfig, 0x0019)
	if a, _ := s.IsAlertAsserted(); !a {
		t.Error("IsAlertAsserted")
	}
	_ = s.ClearInterrupt()
	w := m.writesTo(mcp9808RegConfig)
	if !bytes.Equal(w[len(w)-1], []byte{mcp9808RegConfig, 0x00, 0x29}) {
		t.Errorf("clear interrupt write: % X", w[len(w)-1])
	}
}

func TestMCP9808PollAndOnInterrupt(t *testing.T) {
	m := newWordConnection()
	s, _ := NewMCP9808Full(m)
	for _, c := range []struct {
		raw  uint16
		want uint8
	}{
		{0x0194, 0}, {0x2194, MCP9808SourceLower}, {0xC194, MCP9808SourceUpper | MCP9808SourceCritical},
	} {
		m.set(mcp9808RegTA, c.raw)
		if got, _ := s.PollInterrupt(); got != c.want {
			t.Errorf("TA=0x%04X: got %d, want %d", c.raw, got, c.want)
		}
	}

	// Polling fallback (no IntPin): calls back only when the mask changes.
	m.set(mcp9808RegTA, 0x0194)
	calls := make(chan uint8, 8)
	if err := s.OnInterrupt(func(status uint8) { calls <- status }); err != nil {
		t.Fatal(err)
	}
	time.Sleep(30 * time.Millisecond)
	if len(calls) != 0 {
		t.Errorf("callback fired without a change: %d", len(calls))
	}
	m.set(mcp9808RegTA, 0x4194)
	select {
	case st := <-calls:
		if st != MCP9808SourceUpper {
			t.Errorf("callback status %d", st)
		}
	case <-time.After(time.Second):
		t.Error("callback not fired on change")
	}
	if err := s.OffInterrupt(); err != nil {
		t.Fatal(err)
	}
}
