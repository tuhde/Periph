package adcdac

import (
	"math"
	"sync"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ads1013Conn is an in-memory fake connection.RegisterConnection with one
// 16-bit word per register pointer.
type ads1013Conn struct {
	mu     sync.Mutex
	words  map[uint8]uint16
	writes [][]byte
}

func newADS1013Conn() *ads1013Conn {
	return &ads1013Conn{words: map[uint8]uint16{0: 0x0000, 1: 0x8583}}
}

func (m *ads1013Conn) set(reg uint8, v uint16) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.words[reg] = v
}

func (m *ads1013Conn) get(reg uint8) uint16 {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.words[reg]
}

func (m *ads1013Conn) Write(data []byte) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.writes = append(m.writes, append([]byte(nil), data...))
	if len(data) == 3 {
		m.words[data[0]] = uint16(data[1])<<8 | uint16(data[2])
	}
	return nil
}

func (m *ads1013Conn) Read(n int) ([]byte, error) { return make([]byte, n), nil }

func (m *ads1013Conn) WriteRead(data []byte, n int) ([]byte, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.writes = append(m.writes, append([]byte(nil), data...))
	w := m.words[data[0]]
	return []byte{byte(w >> 8), byte(w)}, nil
}

func (m *ads1013Conn) ReadReg(reg uint32, length int) ([]byte, error) {
	return m.WriteRead([]byte{byte(reg)}, length)
}

func (m *ads1013Conn) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}

func (m *ads1013Conn) Close() error                { return nil }
func (m *ads1013Conn) Enable()                     {}
func (m *ads1013Conn) Disable()                    {}
func (m *ads1013Conn) IsEnabled() bool             { return true }
func (m *ads1013Conn) IntPin() connection.InputPin { return nil }
func (m *ads1013Conn) EnPin() connection.OutputPin { return nil }

func (m *ads1013Conn) lastConfigWrite() uint16 {
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

func (m *ads1013Conn) clearLog() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.writes = nil
}

func ads1013Close(a, b float32) bool { return math.Abs(float64(a-b)) < 1e-5 }

func TestADS1013MinimalSingleShot(t *testing.T) {
	conn := newADS1013Conn()
	adc, _ := NewADS1013Minimal(conn)
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
	if v, _ := adc.ReadVoltage(); !ads1013Close(v, 1.0) {
		t.Fatalf("ReadVoltage = %v", v)
	}
}

func TestADS1013DataRateAndFixedRange(t *testing.T) {
	conn := newADS1013Conn()
	adc, _ := NewADS1013Full(conn)
	if adc.FullScale() != 2.048 || adc.LSB() != 0.001 {
		t.Fatal("fixed range")
	}
	_ = adc.SetDataRate(ADS1013DR128)
	if adc.DataRateSPS() != 128 || adc.GetDataRate() != 0 {
		t.Fatal("128 SPS")
	}
	_ = adc.SetDataRate(ADS1013DR3300)
	if adc.DataRateSPS() != 3300 || adc.GetDataRate() != 6 {
		t.Fatal("3300 SPS")
	}
	conn.set(0, 0x1000)
	_, _ = adc.ReadRaw()
	if want := uint16(0x8000 | 0x0403 | 0x0100 | 6<<5); conn.lastConfigWrite() != want {
		t.Fatalf("config = 0x%04X, want 0x%04X", conn.lastConfigWrite(), want)
	}
	if adc.SetDataRate(7) == nil {
		t.Fatal("SetDataRate must reject 7")
	}
}

func TestADS1013ContinuousAndReset(t *testing.T) {
	conn := newADS1013Conn()
	adc, _ := NewADS1013Full(conn)
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
	if v, _ := adc.ReadContinuous(); !ads1013Close(v, 0.256) {
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
	_ = adc.SetDataRate(ADS1013DR128)
	_ = adc.Reset()
	if conn.get(1) != 0x8583 || adc.GetDataRate() != ADS1013DR1600 {
		t.Fatal("Reset")
	}
}
