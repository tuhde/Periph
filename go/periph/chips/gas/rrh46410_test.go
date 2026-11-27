package gas

import (
	"bytes"
	"errors"
	"math"
	"sync"
	"testing"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// rrh46410Sim simulates the RRH46410 request/response protocol with checksums.
type rrh46410Sim struct {
	mu                     sync.Mutex
	writes                 [][]byte
	response               []byte
	mode, counter, gpio    byte
	gpioDir, humidity      byte
	hasResult, badChecksum bool
	requestsOK             bool
	statusOverride         int
	pid                    uint16
	failWrites             int
	pin                    connection.InputPin
}

func newRRH46410Sim() *rrh46410Sim {
	return &rrh46410Sim{gpio: 0x0C, pid: 0x2310, statusOverride: -1, requestsOK: true}
}

func (m *rrh46410Sim) sample(n byte) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.hasResult = true
	m.counter += n
}

func (m *rrh46410Sim) Write(data []byte) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	d := append([]byte(nil), data...)
	m.writes = append(m.writes, d)
	if m.failWrites > 0 {
		m.failWrites--
		return errors.New("nack")
	}
	var sum byte
	for _, b := range d {
		sum += b
	}
	if sum != 0xFF {
		m.requestsOK = false
	}
	cmd, p := d[0], d[1:len(d)-1]
	var status byte
	var out []byte
	switch cmd {
	case 0x00:
		out = []byte{byte(m.pid >> 8), byte(m.pid), 1, 2, 3}
	case 0x01:
		out = []byte{1, 2, 3, 4, 5, 6}
	case 0x10:
		out = []byte{m.mode}
	case 0x11:
		m.mode, m.counter, m.hasResult = p[0], 0, false
	case 0x12:
		m.humidity = p[0]
	case 0x18:
		if !m.hasResult {
			status = 0x10
		}
		if m.mode == 0x05 {
			out = []byte{m.counter, 0x01, 0xF4, 0x0B, 0xB8}
		} else {
			out = []byte{m.counter, 25, 0x00, 0x32, 0x01, 0x2C, 0x02, 0x58, 3}
		}
	case 0x20:
		m.gpioDir = p[0]
	case 0x21:
		out = []byte{m.gpio}
	case 0x22:
		m.gpio |= p[0]
	case 0x23:
		m.gpio &^= p[0]
	case 0x8F:
		m.mode, m.response = 0, nil
		return nil
	default:
		status = 0x80
	}
	if m.statusOverride >= 0 {
		status = byte(m.statusOverride)
	}
	frame := append([]byte{status}, out...)
	c := rrh46410Checksum(frame)
	if m.badChecksum {
		c ^= 1
	}
	m.response = append(frame, c)
	return nil
}

func (m *rrh46410Sim) Read(n int) ([]byte, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := bytes.Repeat([]byte{0xFF}, n)
	copy(out, m.response)
	return out, nil
}

func (m *rrh46410Sim) WriteRead(data []byte, n int) ([]byte, error) { return make([]byte, n), nil }
func (m *rrh46410Sim) Close() error                                 { return nil }
func (m *rrh46410Sim) Enable()                                      {}
func (m *rrh46410Sim) Disable()                                     {}
func (m *rrh46410Sim) IsEnabled() bool                              { return true }
func (m *rrh46410Sim) IntPin() connection.InputPin                  { return m.pin }
func (m *rrh46410Sim) EnPin() connection.OutputPin                  { return nil }

type rrh46410Pin struct {
	handler func()
	trigger connection.Trigger
	high    bool
}

func (p *rrh46410Pin) OnEdge(t connection.Trigger, h func()) func() {
	p.handler, p.trigger = h, t
	return func() { p.handler = nil }
}
func (p *rrh46410Pin) IsHigh() (bool, error) { return p.high, nil }

func near(a, b float64) bool { return math.Abs(a-b) < 1e-9 }

func TestRRH46410InitAndRead(t *testing.T) {
	sim := newRRH46410Sim()
	m, _ := NewRRH46410Minimal(sim)
	if err := m.Init(); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(sim.writes[0], []byte{0x00, 0xFF}) || !bytes.Equal(sim.writes[1], []byte{0x11, 0x01, 0xED}) {
		t.Fatalf("init requests %X", sim.writes)
	}
	if !sim.requestsOK || sim.mode != 0x01 {
		t.Fatal("request checksums or mode")
	}
	sim.sample(5)
	r, err := m.Read()
	if err != nil {
		t.Fatal(err)
	}
	if !near(r.IAQ, 2.5) || !near(r.TVOCMgM3, 0.5) || !near(r.EtOHPPM, 3.0) || r.ECO2PPM != 600 ||
		r.RelativeIAQ != 30 || r.Sample != 5 || r.WarmedUp || r.PBAQ {
		t.Fatalf("decode %+v", r)
	}
	if !bytes.Equal(sim.writes[len(sim.writes)-1], []byte{0x18, 0xE7}) {
		t.Fatal("results request")
	}
	sim.sample(95)
	if r, _ = m.Read(); !r.WarmedUp || r.Sample != 100 {
		t.Fatalf("warm-up after 100: %+v", r)
	}
	sim.sample(200)
	if r, _ = m.Read(); !r.WarmedUp || r.Sample != 44 {
		t.Fatalf("counter wrap: %+v", r)
	}
	sim.sample(1)
	sim.badChecksum = true
	if _, err = m.Read(); !errors.Is(err, ErrRRH46410Checksum) {
		t.Fatalf("checksum error: %v", err)
	}
	sim.badChecksum = false
	sim.statusOverride = 0x0A
	var se *RRH46410StatusError
	if _, err = m.Read(); !errors.As(err, &se) || se.Status != 0x0A {
		t.Fatalf("damage status: %v", err)
	}
}

func TestRRH46410InitErrors(t *testing.T) {
	sim := newRRH46410Sim()
	sim.pid = 0x1234
	m, _ := NewRRH46410Minimal(sim)
	if err := m.Init(); !errors.Is(err, ErrRRH46410ProductID) {
		t.Fatalf("wrong product id: %v", err)
	}
	sim = newRRH46410Sim()
	sim.failWrites = 3
	m, _ = NewRRH46410Minimal(sim)
	if err := m.Init(); err != nil || sim.mode != 1 {
		t.Fatalf("retry: %v", err)
	}
	sim = newRRH46410Sim()
	sim.failWrites = 1000
	m, _ = NewRRH46410Minimal(sim)
	if err := m.Init(); !errors.Is(err, ErrRRH46410Timeout) {
		t.Fatalf("timeout: %v", err)
	}
}

func TestRRH46410Full(t *testing.T) {
	sim := newRRH46410Sim()
	f, _ := NewRRH46410Full(sim)
	f.pollEvery = time.Millisecond
	if err := f.Init(); err != nil {
		t.Fatal(err)
	}
	if pid, fw, err := f.ProductID(); err != nil || pid != 0x2310 || fw != [3]uint8{1, 2, 3} {
		t.Fatal("product id")
	}
	if tn, _ := f.TrackingNumber(); !bytes.Equal(tn, []byte{1, 2, 3, 4, 5, 6}) {
		t.Fatal("tracking number")
	}
	if mode, _ := f.GetMode(); mode != RRH46410ModeIAQ2ndGen {
		t.Fatal("get mode")
	}
	if _, ok, err := f.ReadResults(); ok || err != nil || !f.Status().NotComplete {
		t.Fatal("not ready")
	}
	sim.sample(3)
	if r, ok, _ := f.ReadResults(); !ok || r.Sample != 3 || r.ECO2PPM != 600 || f.Status().Raw != 0 {
		t.Fatal("read results")
	}
	if ok, _ := f.WaitForSample(5 * time.Millisecond); ok {
		t.Fatal("wait without sample")
	}
	sim.sample(1)
	if ok, _ := f.PollInterrupt(); !ok {
		t.Fatal("poll interrupt true")
	}
	if ok, _ := f.WaitForSample(100 * time.Millisecond); !ok || f.LastResult().Sample != 4 {
		t.Fatal("wait for sample")
	}
	if ok, _ := f.PollInterrupt(); ok {
		t.Fatal("poll interrupt false")
	}
	if f.SetHumidity(50) != nil || sim.humidity != 128 || f.SetHumidity(100) != nil || sim.humidity != 255 {
		t.Fatal("humidity code")
	}
	if !errors.Is(f.SetHumidity(101), ErrRRH46410Argument) {
		t.Fatal("humidity range")
	}
	if f.SetMode(RRH46410ModePBAQ) != nil || sim.mode != 5 {
		t.Fatal("set mode pbaq")
	}
	if f.SamplePeriod(-1) != 5*time.Second || f.WarmupSamples(-1) != 60 ||
		f.SamplePeriod(int(RRH46410ModeULP)) != 90*time.Second || f.WarmupSamples(int(RRH46410ModeULP)) != 10 ||
		f.SamplePeriod(int(RRH46410ModeIAQ2ndGen)) != 3*time.Second || f.WarmupSamples(int(RRH46410ModeIAQ2ndGen)) != 100 {
		t.Fatal("mode timing")
	}
	sim.sample(2)
	if r, ok, _ := f.ReadResults(); !ok || !r.PBAQ || !near(r.TVOCMgM3, 0.5) || !near(r.EtOHPPM, 3.0) || r.ECO2PPM != 0 {
		t.Fatal("pbaq decode")
	}
	if !errors.Is(f.SetMode(0x04), ErrRRH46410Argument) {
		t.Fatal("invalid mode")
	}
	for iaq, want := range map[float64]string{1.5: "very good", 2.0: "good", 3.9: "medium", 4.0: "poor", 5.0: "bad"} {
		if f.AirQualityLabel(iaq) != want {
			t.Fatalf("label %v", iaq)
		}
	}
	if f.ConfigGPIO(3) != nil || sim.gpioDir != 3 {
		t.Fatal("config gpio")
	}
	if f.SetGPIO(1) != nil {
		t.Fatal("set gpio")
	}
	if g, _ := f.GetGPIO(); g&1 != 1 {
		t.Fatal("gpio high")
	}
	if f.ClearGPIO(1) != nil {
		t.Fatal("clear gpio")
	}
	if g, _ := f.GetGPIO(); g&1 != 0 {
		t.Fatal("gpio low")
	}
	if !errors.Is(f.SetGPIO(4), ErrRRH46410Argument) {
		t.Fatal("gpio range")
	}
	if f.Suspend() != nil || sim.mode != 0 {
		t.Fatal("suspend")
	}
	if !errors.Is(f.CleanSensor(false), ErrRRH46410Argument) || sim.mode != 0 {
		t.Fatal("clean requires confirm")
	}
	if out, err := f.SendCommand(0x21, nil, 1); err != nil || out[0] != sim.gpio {
		t.Fatal("send command")
	}
	if _, err := f.SendCommand(0x55, nil, 0); err != nil || !f.Status().InvalidCommand {
		t.Fatal("invalid command status")
	}
	_ = f.SetMode(RRH46410ModeIAQ2ndGen)
	if err := f.Reset(); err != nil {
		t.Fatal(err)
	}
	found := false
	for _, w := range sim.writes {
		if bytes.Equal(w, []byte{0x8F, 0x70}) {
			found = true
		}
	}
	if !found || f.SamplePeriod(-1) != 0 || sim.mode != 0 {
		t.Fatal("reset")
	}
}

func TestRRH46410Interrupt(t *testing.T) {
	sim := newRRH46410Sim()
	pin := &rrh46410Pin{}
	sim.pin = pin
	f, _ := NewRRH46410Full(sim)
	if err := f.Init(); err != nil {
		t.Fatal(err)
	}
	if ok, _ := f.PollInterrupt(); ok {
		t.Fatal("pin low")
	}
	pin.high = true
	if ok, _ := f.PollInterrupt(); !ok {
		t.Fatal("pin high")
	}
	var hits []RRH46410Result
	_ = f.OnInterrupt(func(r RRH46410Result) { hits = append(hits, r) })
	if pin.handler == nil || pin.trigger != connection.Rising {
		t.Fatal("rising edge subscription")
	}
	sim.sample(1)
	pin.handler()
	pin.handler()
	if len(hits) != 1 || hits[0].Sample != 1 {
		t.Fatalf("callback %v", hits)
	}
	_ = f.OffInterrupt()
	if pin.handler != nil {
		t.Fatal("off interrupt")
	}

	sim = newRRH46410Sim()
	f, _ = NewRRH46410Full(sim)
	f.pollEvery = 2 * time.Millisecond
	_ = f.Init()
	got := make(chan RRH46410Result, 4)
	_ = f.OnInterrupt(func(r RRH46410Result) { got <- r })
	sim.sample(1)
	select {
	case r := <-got:
		if r.Sample != 1 {
			t.Fatal("polled sample")
		}
	case <-time.After(time.Second):
		t.Fatal("polling callback")
	}
	_ = f.OffInterrupt()
}
