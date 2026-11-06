package adcdac

import (
	"errors"
	"sync"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// adsConn is a word-addressed in-memory RegisterConnection: the four ADS111x
// registers are 16 bits wide at consecutive pointer values, so each pointer
// owns one 16-bit word. An optional pin stands in for ALERT/RDY.
type adsConn struct {
	mu     sync.Mutex
	words  map[uint8]uint16
	writes [][]byte
	pin    connection.InputPin
}

func newADSConn() *adsConn {
	return &adsConn{words: map[uint8]uint16{0: 0x0000, 1: 0x8583, 2: 0x8000, 3: 0x7FFF}}
}

func (m *adsConn) set(reg uint8, v uint16) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.words[reg] = v
}

func (m *adsConn) get(reg uint8) uint16 {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.words[reg]
}

// lastConfig returns the last 16-bit word written to CONFIG.
func (m *adsConn) lastConfig() uint16 {
	m.mu.Lock()
	defer m.mu.Unlock()
	var last uint16
	for _, w := range m.writes {
		if len(w) == 3 && w[0] == 1 {
			last = uint16(w[1])<<8 | uint16(w[2])
		}
	}
	return last
}

func (m *adsConn) Write(data []byte) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.writes = append(m.writes, append([]byte(nil), data...))
	if len(data) == 3 {
		m.words[data[0]] = uint16(data[1])<<8 | uint16(data[2])
	}
	return nil
}

func (m *adsConn) Read(n int) ([]byte, error) { return make([]byte, n), nil }

func (m *adsConn) WriteRead(data []byte, n int) ([]byte, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.writes = append(m.writes, append([]byte(nil), data...))
	w := m.words[data[0]]
	return []byte{byte(w >> 8), byte(w)}, nil
}

func (m *adsConn) ReadReg(reg uint32, length int) ([]byte, error) {
	return m.WriteRead([]byte{byte(reg)}, length)
}

func (m *adsConn) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}

func (m *adsConn) Close() error                { return nil }
func (m *adsConn) Enable()                     {}
func (m *adsConn) Disable()                    {}
func (m *adsConn) IsEnabled() bool             { return true }
func (m *adsConn) IntPin() connection.InputPin { return m.pin }
func (m *adsConn) EnPin() connection.OutputPin { return nil }

type adsPin struct{ high bool }

func (p *adsPin) OnEdge(connection.Trigger, func()) func() { return func() {} }
func (p *adsPin) IsHigh() (bool, error)                    { return p.high, nil }

func TestADS1115MinimalSingleEnded(t *testing.T) {
	m := newADSConn()
	d, _ := NewADS1115Minimal(m)
	if len(m.writes) != 0 {
		t.Fatalf("init wrote %x", m.writes)
	}
	m.set(0, 0x4000)
	if raw, _ := d.ReadRaw(0); raw != 0x4000 {
		t.Errorf("raw = %d", raw)
	}
	if got := m.lastConfig(); got != 0xC583 {
		t.Errorf("config word 0x%04X, want 0xC583", got)
	}
	m.set(0, 0xFFFF)
	if raw, _ := d.ReadRaw(2); raw != -1 {
		t.Errorf("raw = %d, want -1", raw)
	}
	if got := m.lastConfig(); got != 0xE583 {
		t.Errorf("channel 2 config 0x%04X, want 0xE583", got)
	}
	d.ReadRaw(9)
	if got := m.lastConfig(); got != 0xF583 {
		t.Errorf("clamped channel config 0x%04X, want 0xF583", got)
	}
	m.set(0, 0x4000)
	if v, _ := d.ReadVoltage(0); v != float32(0x4000)*0.0000625 {
		t.Errorf("voltage = %v", v)
	}
}

func TestADS1115GainRateDifferentialContinuous(t *testing.T) {
	m := newADSConn()
	d, _ := NewADS1115Full(m)
	if err := d.SetGain(ADS1115PGA4096); err != nil {
		t.Fatal(err)
	}
	if d.FullScale() != 4.096 || d.LSB() != 4.096/32768 {
		t.Errorf("scale %v %v", d.FullScale(), d.LSB())
	}
	m.set(0, 0x2000)
	if v, _ := d.ReadVoltage(1); v != float32(0x2000)*(4.096/32768) {
		t.Errorf("voltage %v", v)
	}
	if got := m.lastConfig(); got != 0xD383 {
		t.Errorf("config 0x%04X, want 0xD383", got)
	}
	if err := d.SetGain(6); !errors.Is(err, ErrADS111xInvalidArgument) {
		t.Errorf("SetGain(6) = %v", err)
	}
	d.SetDataRate(ADS1115DR860)
	if d.GetDataRate() != 7 || d.DataRateSps() != 860 {
		t.Errorf("rate %d %d", d.GetDataRate(), d.DataRateSps())
	}
	d.ReadRaw(0)
	if got := m.lastConfig(); got != 0xC3E3 {
		t.Errorf("config 0x%04X, want 0xC3E3", got)
	}
	if raw, _ := d.ReadDifferentialRaw(ADS1115Diff23); raw != 0x2000 {
		t.Errorf("diff raw %d", raw)
	}
	if got := m.lastConfig(); got != 0xB3E3 {
		t.Errorf("diff config 0x%04X, want 0xB3E3", got)
	}
	if _, err := d.ReadDifferentialRaw(4); !errors.Is(err, ErrADS111xInvalidArgument) {
		t.Errorf("pair 4 = %v", err)
	}
	d.StartContinuous(ADS1115MuxAIN1GND)
	if got := m.lastConfig(); got != 0x52E3 {
		t.Errorf("continuous config 0x%04X, want 0x52E3", got)
	}
	m.set(0, 0x0100)
	if raw, _ := d.ReadContinuousRaw(); raw != 256 {
		t.Errorf("continuous raw %d", raw)
	}
	d.StopContinuous()
	if got := m.lastConfig(); got != 0x53E3 {
		t.Errorf("stop config 0x%04X, want 0x53E3", got)
	}
	m.set(1, 0x0583)
	if busy, _ := d.IsConverting(); !busy {
		t.Error("should be converting")
	}
	m.set(1, 0x8583)
	if busy, _ := d.IsConverting(); busy {
		t.Error("should be idle")
	}
	if cfg, _ := d.ReadConfig(); cfg != 0x8583 {
		t.Errorf("config 0x%04X", cfg)
	}
}

func TestADS1115ThresholdsAndComparator(t *testing.T) {
	m := newADSConn()
	d, _ := NewADS1115Full(m)
	d.SetThresholds(0.5, 3.0)
	if m.get(2) != 8000 || m.get(3) != 0x7FFF {
		t.Errorf("thresholds %04X %04X", m.get(2), m.get(3))
	}
	d.SetHighThresholdRaw(-100)
	d.SetLowThresholdRaw(-200)
	if m.get(3) != 0xFF9C {
		t.Errorf("negative encoding %04X", m.get(3))
	}
	if lo, hi, _ := d.GetThresholdsRaw(); lo != -200 || hi != -100 {
		t.Errorf("readback %d %d", lo, hi)
	}
	if err := d.SetThresholds(1.0, 1.0); !errors.Is(err, ErrADS111xInvalidArgument) {
		t.Errorf("inverted thresholds = %v", err)
	}
	d.SetThresholds(0.5, 1.5)
	d.ConfigureComparator(ADS1115CompWindow, ADS1115ActiveHigh, true, ADS1115Queue2)
	if got := m.lastConfig(); got != 0x0400|0x0100|0x0080|0x0010|0x0008|0x0004|0x0001 {
		t.Errorf("comparator config 0x%04X", got)
	}
	d.DisableComparator()
	if m.lastConfig()&3 != 3 {
		t.Error("comparator not disabled")
	}
	m.writes = nil
	d.ClearAlert()
	if len(m.writes) != 1 || m.writes[0][0] != 0 {
		t.Errorf("ClearAlert should read CONVERSION, got %x", m.writes)
	}
}

func TestADS1115InterruptRouting(t *testing.T) {
	m := newADSConn()
	d, _ := NewADS1115Full(m)
	d.EnableInterrupt(ADS1115SourceConversionReady)
	if m.get(3) != 0x8000 || m.get(2) != 0x0000 || m.lastConfig()&3 != 0 {
		t.Errorf("ready mode thresholds %04X %04X config %04X", m.get(3), m.get(2), m.lastConfig())
	}
	m.set(1, 0x8583)
	if s, _ := d.PollInterrupt(); s != ADS1115SourceConversionReady {
		t.Errorf("fallback poll = %d", s)
	}
	m.set(1, 0x0583)
	if s, _ := d.PollInterrupt(); s != 0 {
		t.Errorf("idle poll = %d", s)
	}
	pin := &adsPin{high: false}
	m.pin = pin
	if s, _ := d.PollInterrupt(); s != ADS1115SourceConversionReady {
		t.Errorf("pin asserted poll = %d", s)
	}
	pin.high = true
	if s, _ := d.PollInterrupt(); s != 0 {
		t.Errorf("pin deasserted poll = %d", s)
	}
	d.DisableInterrupt(ADS1115SourceConversionReady)
	if m.lastConfig()&3 != 3 {
		t.Error("interrupt not disabled")
	}
	if s, _ := d.PollInterrupt(); s != 0 {
		t.Errorf("disabled poll = %d", s)
	}
	d.SetThresholds(0.5, 1.5)
	d.EnableInterrupt(ADS1115SourceComparator)
	pin.high = false
	if s, _ := d.PollInterrupt(); s != ADS1115SourceComparator {
		t.Errorf("comparator poll = %d", s)
	}
	if err := d.EnableInterrupt(0x80); !errors.Is(err, ErrADS111xInvalidArgument) {
		t.Errorf("unknown source = %v", err)
	}
	d.SetGain(0)
	d.Reset()
	if m.get(1) != 0x8583 || m.get(2) != 0x8000 || m.get(3) != 0x7FFF || d.GetGain() != 2 || d.GetDataRate() != 4 {
		t.Error("reset did not restore defaults")
	}
}
