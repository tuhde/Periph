package humidity

import (
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// htu31dMock is an in-memory fake connection.Connection: it logs writes and
// WriteRead requests, serves plain reads from a FIFO queue (optionally NACKing
// reads first) and WriteRead from a map keyed by the request bytes.
type htu31dMock struct {
	writes    [][]byte
	readQueue [][]byte
	nackReads int
	wr        map[string][]byte
}

func (m *htu31dMock) queue(b []byte) { m.readQueue = append(m.readQueue, b) }

func (m *htu31dMock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}

func (m *htu31dMock) Read(n int) ([]byte, error) {
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

func (m *htu31dMock) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	out := make([]byte, n)
	copy(out, m.wr[string(data)])
	return out, nil
}
func (m *htu31dMock) Close() error                { return nil }
func (m *htu31dMock) Enable()                     {}
func (m *htu31dMock) Disable()                    {}
func (m *htu31dMock) IsEnabled() bool             { return true }
func (m *htu31dMock) IntPin() connection.InputPin { return nil }
func (m *htu31dMock) EnPin() connection.OutputPin { return nil }

func (m *htu31dMock) first() []byte {
	var out []byte
	for _, w := range m.writes {
		out = append(out, w[0])
	}
	return out
}

func (m *htu31dMock) lastWrite(want ...byte) bool {
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

func h31Word(code uint16) []byte {
	d := []byte{byte(code >> 8), byte(code)}
	return []byte{d[0], d[1], HTU31DCRC8(d)}
}

func h31TRH(t, rh uint16) []byte { return append(h31Word(t), h31Word(rh)...) }

func h31Near(a, b, tol float64) bool { return math.Abs(a-b) <= tol }

func h31BytesEq(a []byte, b ...byte) bool {
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

func TestHTU31DCRCReference(t *testing.T) {
	if HTU31DCRC8([]byte{0xDC}) != 0x79 || HTU31DCRC8([]byte{0x68, 0x3A}) != 0x7C || HTU31DCRC8([]byte{0x4E, 0x85}) != 0x6B {
		t.Error("CRC reference values wrong")
	}
}

func TestHTU31DInitAndRead(t *testing.T) {
	m := &htu31dMock{}
	s := NewHTU31DFull(m)
	if len(m.writes) != 0 {
		t.Error("constructor accessed the bus")
	}
	if err := s.Init(); err != nil || !h31BytesEq(m.first(), 0x1E) {
		t.Fatalf("Init: %v cmds %x", err, m.first())
	}
	m.writes = nil
	m.queue(h31TRH(0x683A, 0x8000))
	temp, rh, err := s.Read()
	if err != nil || !h31BytesEq(m.first(), 0x5E, 0x00) {
		t.Fatalf("Read: %v cmds %x", err, m.first())
	}
	if !h31Near(float64(temp), -40+165*float64(0x683A)/65535, 1e-3) || !h31Near(float64(rh), 100*float64(0x8000)/65535, 1e-3) {
		t.Errorf("conversion: %v %v", temp, rh)
	}
}

func TestHTU31DFullScaleAndClamp(t *testing.T) {
	m := &htu31dMock{}
	s := NewHTU31DMinimal(m)
	m.queue(h31TRH(0xFFFF, 0xFFFF))
	temp, rh, _ := s.Read()
	if !h31Near(float64(temp), 125, 1e-3) || !h31Near(float64(rh), 100, 1e-3) {
		t.Errorf("full scale: %v %v", temp, rh)
	}
	m.queue(h31TRH(0, 0))
	temp, rh, _ = s.Read()
	if !h31Near(float64(temp), -40, 1e-3) || rh != 0 {
		t.Errorf("zero scale: %v %v", temp, rh)
	}
}

func TestHTU31DCRCMismatch(t *testing.T) {
	m := &htu31dMock{}
	s := NewHTU31DMinimal(m)
	b := h31TRH(0x683A, 0x8000)
	b[2] ^= 0xFF
	m.queue(b)
	if _, _, err := s.Read(); !errors.Is(err, ErrHTU31DCRC) {
		t.Errorf("temperature CRC: %v", err)
	}
	b = h31TRH(0x683A, 0x8000)
	b[5] ^= 0xFF
	m.queue(b)
	if _, _, err := s.Read(); !errors.Is(err, ErrHTU31DCRC) {
		t.Errorf("humidity CRC: %v", err)
	}
}

func TestHTU31DSingleValuesAndRaw(t *testing.T) {
	m := &htu31dMock{}
	s := NewHTU31DFull(m)
	m.queue(h31Word(0x4000))
	h, err := s.ReadHumidity()
	if err != nil || !h31Near(float64(h), 100*0x4000/65535.0, 1e-3) || !h31BytesEq(m.first(), 0x5E, 0x10) {
		t.Errorf("ReadHumidity: %v %v cmds %x", h, err, m.first())
	}
	m.writes = nil
	m.queue(h31Word(0x4000))
	tc, err := s.ReadTemperature()
	if err != nil || !h31Near(float64(tc), -40+165*0x4000/65535.0, 1e-3) || !h31BytesEq(m.first(), 0x5E, 0x00) {
		t.Errorf("ReadTemperature: %v %v cmds %x", tc, err, m.first())
	}
	m.queue(h31TRH(0x1234, 0x5678))
	tr, rr, err := s.ReadRaw()
	if err != nil || tr != 0x1234 || rr != 0x5678 {
		t.Errorf("ReadRaw: %x %x %v", tr, rr, err)
	}
}

func TestHTU31DResolution(t *testing.T) {
	m := &htu31dMock{}
	s := NewHTU31DFull(m)
	if a, b := s.GetResolution(); a != 3 || b != 3 {
		t.Error("default resolution")
	}
	if !h31Near(s.ConversionTime(), 0.02032, 1e-9) {
		t.Errorf("conversion time %v", s.ConversionTime())
	}
	if err := s.SetResolution(2, 1); err != nil {
		t.Fatal(err)
	}
	m.queue(h31TRH(0x4000, 0x4000))
	s.Read()
	if m.first()[0] != 0x40|2<<3|1<<1 {
		t.Errorf("convert command %x", m.first())
	}
	if s.SetResolution(4, 0) == nil || s.SetResolution(0, 4) == nil {
		t.Error("resolution range not enforced")
	}
	m.writes = nil
	s.SetResolution(1, 2)
	s.StartConversion()
	if !h31BytesEq(m.first(), 0x40|1<<3|2<<1) {
		t.Errorf("StartConversion %x", m.first())
	}
	m.writes = nil
	m.queue(h31TRH(0x2000, 0x6000))
	_, rh, err := s.ReadResult()
	if err != nil || !h31BytesEq(m.first(), 0x00) || !h31Near(float64(rh), 100*0x6000/65535.0, 1e-3) {
		t.Errorf("ReadResult: %v %v cmds %x", rh, err, m.first())
	}
}

func TestHTU31DHeaterDiagnosticSerial(t *testing.T) {
	m := &htu31dMock{}
	s := NewHTU31DFull(m)
	s.SetHeater(true)
	s.SetHeater(false)
	if !h31BytesEq(m.first(), 0x04, 0x02) {
		t.Errorf("heater cmds %x", m.first())
	}
	m.queue([]byte{0x01})
	if on, _ := s.IsHeaterOn(); !on {
		t.Error("heater should be on")
	}
	m.queue([]byte{0xA5})
	d, _ := s.Diagnostic()
	if d != 0xA5 || d&HTU31DDiagNVMErr == 0 || d&HTU31DDiagRHHighErr == 0 || d&HTU31DDiagRHOverrun != 0 {
		t.Errorf("diagnostic %x", d)
	}
	sn := []byte{0xAB, 0xCD, 0xEF}
	m.queue(append(append([]byte{}, sn...), HTU31DCRC8(sn)))
	if v, err := s.SerialNumber(); err != nil || v != 0xABCDEF {
		t.Errorf("serial %x %v", v, err)
	}
	m.queue(append(append([]byte{}, sn...), HTU31DCRC8(sn)^0x55))
	if _, err := s.SerialNumber(); !errors.Is(err, ErrHTU31DCRC) {
		t.Errorf("serial CRC: %v", err)
	}
}

func TestHTU31DSoftResetAndDewPoint(t *testing.T) {
	m := &htu31dMock{}
	s := NewHTU31DFull(m)
	s.SetResolution(0, 1)
	s.SoftReset()
	if a, b := s.GetResolution(); a != 3 || b != 3 || !h31BytesEq(m.first(), 0x1E) {
		t.Error("SoftReset defaults")
	}
	m.queue(h31TRH(uint16(math.Round(65*65535/165.0)), uint16(math.Round(0.5*65535))))
	dp, err := s.DewPoint()
	if err != nil || !h31Near(float64(dp), 13.86, 0.15) {
		t.Errorf("dew point %v %v", dp, err)
	}
}
