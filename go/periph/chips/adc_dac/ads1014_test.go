package adcdac

import (
	"math"
	"sync"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ads1014Conn is an in-memory fake connection.RegisterConnection. The ADS1014
// registers are 16 bits wide at consecutive pointer values, so each pointer
// owns one 16-bit word.
type ads1014Conn struct {
	mu     sync.Mutex
	words  map[uint8]uint16
	writes [][]byte
	pin    connection.InputPin
}

func newADS1014Conn() *ads1014Conn {
	return &ads1014Conn{words: map[uint8]uint16{0: 0x0000, 1: 0x8583, 2: 0x8000, 3: 0x7FFF}}
}

func (m *ads1014Conn) set(reg uint8, v uint16) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.words[reg] = v
}

func (m *ads1014Conn) get(reg uint8) uint16 {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.words[reg]
}

func (m *ads1014Conn) Write(data []byte) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.writes = append(m.writes, append([]byte(nil), data...))
	if len(data) == 3 {
		m.words[data[0]] = uint16(data[1])<<8 | uint16(data[2])
	}
	return nil
}

func (m *ads1014Conn) Read(n int) ([]byte, error) { return make([]byte, n), nil }

func (m *ads1014Conn) WriteRead(data []byte, n int) ([]byte, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.writes = append(m.writes, append([]byte(nil), data...))
	w := m.words[data[0]]
	return []byte{byte(w >> 8), byte(w)}, nil
}

func (m *ads1014Conn) ReadReg(reg uint32, length int) ([]byte, error) {
	return m.WriteRead([]byte{byte(reg)}, length)
}

func (m *ads1014Conn) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}

func (m *ads1014Conn) Close() error                { return nil }
func (m *ads1014Conn) Enable()                     {}
func (m *ads1014Conn) Disable()                    {}
func (m *ads1014Conn) IsEnabled() bool             { return true }
func (m *ads1014Conn) IntPin() connection.InputPin { return m.pin }
func (m *ads1014Conn) EnPin() connection.OutputPin { return nil }

func (m *ads1014Conn) lastConfigWrite() uint16 {
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

func (m *ads1014Conn) clearLog() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.writes = nil
}

// ads1014Pin is a fake InputPin with a settable level.
type ads1014Pin struct {
	high    bool
	handler func()
	trigger connection.Trigger
}

func (p *ads1014Pin) OnEdge(t connection.Trigger, h func()) func() {
	p.handler, p.trigger = h, t
	return func() { p.handler = nil }
}

func (p *ads1014Pin) IsHigh() (bool, error) { return p.high, nil }

func ads1014Close(a, b float32) bool { return math.Abs(float64(a-b)) < 1e-5 }

func TestADS1014MinimalSingleShot(t *testing.T) {
	conn := newADS1014Conn()
	adc, _ := NewADS1014Minimal(conn)
	if len(conn.writes) != 0 {
		t.Fatalf("init made bus traffic: %v", conn.writes)
	}
	for _, c := range []struct {
		word uint16
		want int16
	}{{0x7FF0, 2047}, {0x8000, -2048}, {0xFFF0, -1}} {
		conn.set(0, c.word)
		got, err := adc.ReadRaw()
		if err != nil || got != c.want {
			t.Fatalf("ReadRaw(0x%04X) = %d, %v; want %d", c.word, got, err, c.want)
		}
	}
	if conn.lastConfigWrite() != 0x8583 {
		t.Fatalf("default config word = 0x%04X", conn.lastConfigWrite())
	}
	conn.set(0, 0x3E80)
	if v, _ := adc.ReadVoltage(); !ads1014Close(v, 1.0) {
		t.Fatalf("ReadVoltage = %v", v)
	}
}

func TestADS1014GainAndDataRate(t *testing.T) {
	conn := newADS1014Conn()
	adc, _ := NewADS1014Full(conn)
	if err := adc.SetGain(ADS1014PGA6_144); err != nil || adc.GetGain() != 0 {
		t.Fatalf("SetGain: %v", err)
	}
	if adc.FullScale() != 6.144 || !ads1014Close(adc.LSB(), 0.003) {
		t.Fatalf("6.144 V range: %v %v", adc.FullScale(), adc.LSB())
	}
	_ = adc.SetGain(ADS1014PGA0_256)
	conn.set(0, 0x4000)
	if v, _ := adc.ReadVoltage(); !ads1014Close(v, 0.256*0x400/2048) {
		t.Fatalf("ReadVoltage at ±0.256 V = %v", v)
	}
	if want := uint16(0x8000 | 5<<9 | 0x0100 | 4<<5 | 0x3); conn.lastConfigWrite() != want {
		t.Fatalf("config = 0x%04X, want 0x%04X", conn.lastConfigWrite(), want)
	}
	if adc.SetGain(6) == nil || adc.SetGain(7) == nil || adc.GetGain() != 5 {
		t.Fatal("SetGain must reject 6 and 7 without changing the gain")
	}
	_ = adc.SetDataRate(ADS1014DR128)
	if adc.DataRateSPS() != 128 {
		t.Fatal("128 SPS")
	}
	_ = adc.SetDataRate(ADS1014DR3300)
	if adc.DataRateSPS() != 3300 || adc.GetDataRate() != 6 {
		t.Fatal("3300 SPS")
	}
	if adc.SetDataRate(7) == nil {
		t.Fatal("SetDataRate must reject 7")
	}
}

func TestADS1014Continuous(t *testing.T) {
	conn := newADS1014Conn()
	adc, _ := NewADS1014Full(conn)
	_ = adc.StartContinuous()
	if conn.lastConfigWrite() != 0x0483 {
		t.Fatalf("continuous config = 0x%04X", conn.lastConfigWrite())
	}
	conn.set(0, 0x1000)
	conn.clearLog()
	if raw, _ := adc.ReadContinuousRaw(); raw != 256 {
		t.Fatalf("ReadContinuousRaw = %d", raw)
	}
	if conn.lastConfigWrite() != 0 {
		t.Fatal("ReadContinuousRaw must not write Config")
	}
	if v, _ := adc.ReadContinuous(); !ads1014Close(v, 0.256) {
		t.Fatalf("ReadContinuous = %v", v)
	}
	_ = adc.StopContinuous()
	if conn.lastConfigWrite() != 0x0583 {
		t.Fatalf("stop config = 0x%04X", conn.lastConfigWrite())
	}
	conn.set(1, 0x0583)
	if busy, _ := adc.IsConverting(); !busy {
		t.Fatal("IsConverting with OS = 0")
	}
	conn.set(1, 0x8583)
	if busy, _ := adc.IsConverting(); busy {
		t.Fatal("IsConverting with OS = 1")
	}
	if cfg, _ := adc.ReadConfig(); cfg != 0x8583 {
		t.Fatalf("ReadConfig = 0x%04X", cfg)
	}
}

func TestADS1014ComparatorAndThresholds(t *testing.T) {
	conn := newADS1014Conn()
	adc, _ := NewADS1014Full(conn)
	if err := adc.ConfigureComparator(ADS1014CompWindow, ADS1014ActiveHigh, true, ADS1014Queue4); err != nil {
		t.Fatal(err)
	}
	if want := uint16(0x0580 | 0x10 | 0x08 | 0x04 | 0x02); conn.lastConfigWrite() != want {
		t.Fatalf("comparator config = 0x%04X, want 0x%04X", conn.lastConfigWrite(), want)
	}
	_ = adc.DisableComparator()
	if conn.lastConfigWrite()&0x3 != 3 {
		t.Fatal("DisableComparator must set COMP_QUE = 11")
	}
	if adc.ConfigureComparator(0, 0, false, 3) == nil {
		t.Fatal("queue 3 must be rejected")
	}

	if err := adc.SetThresholds(-0.5, 1.0); err != nil {
		t.Fatal(err)
	}
	if lowCode := int16(-500); conn.get(2) != (uint16(lowCode)&0x0FFF)<<4 {
		t.Fatalf("low word = 0x%04X", conn.get(2))
	}
	if conn.get(3) != 1000<<4 {
		t.Fatalf("high word = 0x%04X", conn.get(3))
	}
	if lo, hi, _ := adc.GetThresholdsRaw(); lo != -500 || hi != 1000 {
		t.Fatalf("thresholds = %d %d", lo, hi)
	}
	_ = adc.SetThresholds(-10, 10)
	if lo, hi, _ := adc.GetThresholdsRaw(); lo != -2048 || hi != 2047 {
		t.Fatalf("clamped thresholds = %d %d", lo, hi)
	}
	if adc.SetThresholds(1, 1) == nil || adc.SetHighThresholdRaw(2048) == nil {
		t.Fatal("invalid thresholds must be rejected")
	}

	conn.clearLog()
	_ = adc.ClearAlert()
	if len(conn.writes) != 1 || len(conn.writes[0]) != 1 || conn.writes[0][0] != 0 {
		t.Fatalf("ClearAlert log = %v", conn.writes)
	}

	_ = adc.Reset()
	if conn.get(1) != 0x8583 || conn.get(2) != 0x8000 || conn.get(3) != 0x7FFF {
		t.Fatal("Reset must write the reset values")
	}
}

func TestADS1014InterruptRouting(t *testing.T) {
	conn := newADS1014Conn()
	pin := &ads1014Pin{high: true}
	conn.pin = pin
	adc, _ := NewADS1014Full(conn)

	_ = adc.EnableInterrupt(ADS1014SourceConversionReady)
	if conn.get(3) != 0x8000 || conn.get(2) != 0x0000 || conn.get(1)&0x3 != 0 {
		t.Fatal("conversion-ready routing")
	}
	if s, _ := adc.PollInterrupt(); s != 0 {
		t.Fatal("pin high = idle for an active-low alert")
	}
	pin.high = false
	if s, _ := adc.PollInterrupt(); s != ADS1014SourceConversionReady {
		t.Fatalf("PollInterrupt = %d", s)
	}

	_ = adc.SetHighThresholdRaw(-5)
	_ = adc.SetLowThresholdRaw(10)
	if adc.EnableInterrupt(ADS1014SourceComparator) == nil {
		t.Fatal("comparator needs high > low")
	}
	if adc.EnableInterrupt(0x04) == nil {
		t.Fatal("unknown source must be rejected")
	}
	_ = adc.SetThresholds(-0.1, 0.1)
	_ = adc.ConfigureComparator(ADS1014CompTraditional, ADS1014ActiveLow, true, ADS1014Queue1)
	_ = adc.EnableInterrupt(ADS1014SourceComparator)
	if lo, hi, _ := adc.GetThresholdsRaw(); lo != -100 || hi != 100 {
		t.Fatalf("thresholds not restored: %d %d", lo, hi)
	}
	conn.clearLog()
	if s, _ := adc.PollInterrupt(); s != ADS1014SourceComparator {
		t.Fatalf("PollInterrupt = %d", s)
	}
	cleared := false
	for _, w := range conn.writes {
		if len(w) == 1 && w[0] == 0 {
			cleared = true
		}
	}
	if !cleared {
		t.Fatal("a latched alert must be cleared by reading CONVERSION")
	}

	var got []uint8
	_ = adc.OnInterrupt(func(s uint8) { got = append(got, s) })
	if pin.handler == nil || pin.trigger != connection.Falling {
		t.Fatal("OnInterrupt must register a falling-edge handler")
	}
	pin.handler()
	if len(got) != 1 || got[0] != ADS1014SourceComparator {
		t.Fatalf("callback = %v", got)
	}
	_ = adc.OffInterrupt()
	if pin.handler != nil {
		t.Fatal("OffInterrupt must remove the handler")
	}

	_ = adc.DisableInterrupt(ADS1014SourceComparator)
	if conn.get(1)&0x3 != 3 {
		t.Fatal("DisableInterrupt must set COMP_QUE = 11")
	}
	if s, _ := adc.PollInterrupt(); s != 0 {
		t.Fatal("disabled")
	}
}

func TestADS1014PollFallbackWithoutPin(t *testing.T) {
	conn := newADS1014Conn()
	adc, _ := NewADS1014Full(conn)
	_ = adc.EnableInterrupt(ADS1014SourceConversionReady)
	conn.set(1, conn.get(1)|0x8000) // the real chip reads OS = 1 when idle
	if s, _ := adc.PollInterrupt(); s != ADS1014SourceConversionReady {
		t.Fatal("OS fallback")
	}
	conn.set(1, conn.get(1)&0x7FFF)
	if s, _ := adc.PollInterrupt(); s != 0 {
		t.Fatal("OS = 0 is busy")
	}
}
