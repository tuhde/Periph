package humidity

import (
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// htu21dfMock is an in-memory fake connection.Connection: it logs writes and
// WriteRead requests, serves plain reads from a FIFO queue (optionally NACKing
// reads first) and WriteRead from a map keyed by the request bytes.
type htu21dfMock struct {
	writes    [][]byte
	readQueue [][]byte
	nackReads int
	wr        map[string][]byte
}

func (m *htu21dfMock) queue(b []byte) { m.readQueue = append(m.readQueue, b) }

func (m *htu21dfMock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}

func (m *htu21dfMock) Read(n int) ([]byte, error) {
	if m.nackReads > 0 {
		m.nackReads--
		return nil, errors.New("NACK")
	}
	out := make([]byte, n)
	if len(m.readQueue) > 0 {
		copy(out, m.readQueue[0])
		m.readQueue = m.readQueue[1:]
	}
	return out, nil
}

func (m *htu21dfMock) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	out := make([]byte, n)
	copy(out, m.wr[string(data)])
	return out, nil
}
func (m *htu21dfMock) Close() error                { return nil }
func (m *htu21dfMock) Enable()                     {}
func (m *htu21dfMock) Disable()                    {}
func (m *htu21dfMock) IsEnabled() bool             { return true }
func (m *htu21dfMock) IntPin() connection.InputPin { return nil }
func (m *htu21dfMock) EnPin() connection.OutputPin { return nil }

func (m *htu21dfMock) first() []byte {
	var out []byte
	for _, w := range m.writes {
		out = append(out, w[0])
	}
	return out
}

func (m *htu21dfMock) lastWrite(want ...byte) bool {
	w := m.writes[len(m.writes)-1]
	if len(w) != len(want) {
		return false
	}
	for i := range w {
		if w[i] != want[i] {
			return false
		}
	}
	return true
}

func hFrame(code uint16) []byte {
	d := []byte{byte(code >> 8), byte(code)}
	return []byte{d[0], d[1], HTU21DFCRC8(d)}
}

func hRH(rh float64) uint16 {
	return uint16(math.Round((rh+6)*65536/125))&0xFFFC | 0x02
}
func hT(t float64) uint16 { return uint16(math.Round((t+46.85)*65536/175.72)) & 0xFFFC }

func hNear(a, b, tol float64) bool { return math.Abs(a-b) <= tol }

func hBytesEq(a []byte, b ...byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func TestHTU21DFCRCReference(t *testing.T) {
	if HTU21DFCRC8([]byte{0x68, 0x3A}) != 0x7C || HTU21DFCRC8([]byte{0x4E, 0x85}) != 0x6B || HTU21DFCRC8([]byte{0xDC}) != 0x79 {
		t.Error("CRC reference values wrong")
	}
}

func TestHTU21DFInitAndReadCommands(t *testing.T) {
	m := &htu21dfMock{}
	s := NewHTU21DFFull(m)
	if len(m.writes) != 0 {
		t.Error("constructor accessed the bus")
	}
	if err := s.Init(); err != nil || !hBytesEq(m.first(), 0xFE) {
		t.Fatalf("Init: %v cmds %x", err, m.first())
	}
	m.writes = nil
	m.queue(hFrame(hT(25)))
	m.queue(hFrame(hRH(50)))
	temp, rh, err := s.Read()
	if err != nil || !hBytesEq(m.first(), 0xF3, 0xF5) {
		t.Fatalf("Read: %v cmds %x", err, m.first())
	}
	if !hNear(float64(rh), 50, 0.05) || !hNear(float64(temp), 25, 0.05) {
		t.Errorf("Read = %v %v", temp, rh)
	}
	if NewHTU21DFMinimal(&htu21dfMock{}) == nil {
		t.Error("minimal constructor")
	}
}

func TestHTU21DFDatasheetConversion(t *testing.T) {
	m := &htu21dfMock{}
	m.queue(hFrame(0x683A))
	m.queue(hFrame(0x683A))
	temp, rh, err := NewHTU21DFFull(m).Read()
	if err != nil || !hNear(float64(temp), -46.85+175.72*0x6838/65536.0, 1e-3) || !hNear(float64(rh), -6+125*0x6838/65536.0, 1e-3) {
		t.Errorf("conversion %v %v %v", temp, rh, err)
	}
}

func TestHTU21DFPollsAfterNack(t *testing.T) {
	m := &htu21dfMock{nackReads: 3}
	m.queue(hFrame(hT(20)))
	m.queue(hFrame(hRH(40)))
	_, rh, err := NewHTU21DFFull(m).Read()
	if err != nil || !hNear(float64(rh), 40, 0.05) {
		t.Errorf("Read after NACKs: %v %v", rh, err)
	}
}

func TestHTU21DFTimeout(t *testing.T) {
	m := &htu21dfMock{nackReads: 1000}
	if _, _, err := NewHTU21DFFull(m).Read(); !errors.Is(err, ErrHTU21DFTimeout) {
		t.Errorf("want timeout, got %v", err)
	}
}

func TestHTU21DFCRCRetry(t *testing.T) {
	bad := hFrame(0x6000)
	bad[2] ^= 0xFF
	m := &htu21dfMock{}
	m.queue(bad)
	m.queue(bad)
	if _, _, err := NewHTU21DFFull(m).Read(); !errors.Is(err, ErrHTU21DFCRC) {
		t.Errorf("want CRC error, got %v", err)
	}
	m = &htu21dfMock{}
	m.queue(bad)
	m.queue(hFrame(hT(25)))
	m.queue(hFrame(hRH(50)))
	temp, _, err := NewHTU21DFFull(m).Read()
	if err != nil || !hNear(float64(temp), 25, 0.05) || !hBytesEq(m.first(), 0xF3, 0xF3, 0xF5) {
		t.Errorf("retry: %v %v cmds %x", temp, err, m.first())
	}
}

func TestHTU21DFHumidityNotClamped(t *testing.T) {
	m := &htu21dfMock{}
	m.queue(hFrame(0))
	m.queue(hFrame(0))
	_, rh, _ := NewHTU21DFFull(m).Read()
	if !hNear(float64(rh), -6, 1e-6) {
		t.Errorf("rh = %v", rh)
	}
}

func TestHTU21DFSingleValuesAndRaw(t *testing.T) {
	m := &htu21dfMock{}
	s := NewHTU21DFFull(m)
	m.queue(hFrame(hRH(30)))
	if v, err := s.ReadHumidity(); err != nil || !hNear(float64(v), 30, 0.05) {
		t.Errorf("ReadHumidity %v %v", v, err)
	}
	m.queue(hFrame(hT(-10)))
	if v, err := s.ReadTemperature(); err != nil || !hNear(float64(v), -10, 0.05) {
		t.Errorf("ReadTemperature %v %v", v, err)
	}
	if !hBytesEq(m.first(), 0xF5, 0xF3) {
		t.Errorf("commands %x", m.first())
	}
	m.queue(hFrame(0x1237))
	m.queue(hFrame(0x4321))
	if rt, rr, err := s.ReadRaw(); err != nil || rt != 0x1234 || rr != 0x4320 {
		t.Errorf("ReadRaw %x %x %v", rt, rr, err)
	}
	m.queue(hFrame(hT(35)))
	m.queue(hFrame(hRH(50)))
	if v, err := s.ReadHumidityCompensated(); err != nil || !hNear(float64(v), 51.5, 0.1) {
		t.Errorf("compensated %v %v", v, err)
	}
}

func TestHTU21DFHoldMaster(t *testing.T) {
	m := &htu21dfMock{}
	s := NewHTU21DFFull(m)
	s.SetHoldMaster(true)
	m.queue(hFrame(hT(25)))
	m.queue(hFrame(hRH(50)))
	if _, _, err := s.Read(); err != nil || !hBytesEq(m.first(), 0xE3, 0xE5) {
		t.Errorf("hold master: %v cmds %x", err, m.first())
	}
}

func TestHTU21DFUserRegister(t *testing.T) {
	m := &htu21dfMock{wr: map[string][]byte{"\xe7": {0x02}}}
	s := NewHTU21DFFull(m)
	for _, c := range []struct {
		res  int
		want byte
	}{{3, 0x83}, {1, 0x03}, {2, 0x82}} {
		if err := s.SetResolution(c.res); err != nil || !m.lastWrite(0xE6, c.want) {
			t.Errorf("SetResolution(%d): %v", c.res, err)
		}
	}
	if !errors.Is(s.SetResolution(4), ErrHTU21DFResolution) {
		t.Error("SetResolution(4) accepted")
	}
	for _, c := range []struct {
		reg  byte
		want int
	}{{0x83, 3}, {0x03, 1}, {0x82, 2}, {0x02, 0}} {
		m.wr["\xe7"] = []byte{c.reg}
		if r, err := s.GetResolution(); err != nil || r != c.want {
			t.Errorf("GetResolution(%#x) = %d %v", c.reg, r, err)
		}
	}
	m.wr["\xe7"] = []byte{0x02}
	if err := s.SetHeater(true); err != nil || !m.lastWrite(0xE6, 0x06) {
		t.Errorf("SetHeater(true): %v", err)
	}
	m.wr["\xe7"] = []byte{0x06}
	if on, _ := s.Heater(); !on {
		t.Error("Heater not on")
	}
	if err := s.SetHeater(false); err != nil || !m.lastWrite(0xE6, 0x02) {
		t.Errorf("SetHeater(false): %v", err)
	}
	m.wr["\xe7"] = []byte{0x02}
	if low, _ := s.BatteryLow(); low {
		t.Error("battery low at 0x02")
	}
	m.wr["\xe7"] = []byte{0x42}
	if low, _ := s.BatteryLow(); !low {
		t.Error("battery not low at 0x42")
	}
	if v, _ := s.ReadUserRegister(); v != 0x42 {
		t.Errorf("ReadUserRegister = %#x", v)
	}
}

func TestHTU21DFSoftResetAndDewPoint(t *testing.T) {
	m := &htu21dfMock{}
	s := NewHTU21DFFull(m)
	if err := s.SoftReset(); err != nil || !hBytesEq(m.first(), 0xFE) {
		t.Errorf("SoftReset: %v", err)
	}
	m.queue(hFrame(hT(25)))
	m.queue(hFrame(hRH(50)))
	if v, err := s.DewPoint(); err != nil || !hNear(float64(v), 13.86, 0.1) {
		t.Errorf("DewPoint %v %v", v, err)
	}
}
