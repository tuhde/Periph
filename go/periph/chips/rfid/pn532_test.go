package rfid

import (
	"bytes"
	"errors"
	"testing"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

var pn532ErrorFrameBytes = []byte{0x00, 0x00, 0xFF, 0x01, 0xFF, 0x7F, 0x81, 0x00}

func pn532RespFrame(tfi byte, data []byte) []byte {
	body := append([]byte{tfi}, data...)
	sum := byte(0)
	for _, b := range body {
		sum += b
	}
	l := byte(len(body))
	f := append([]byte{0x00, 0x00, 0xFF, l, -l}, body...)
	return append(f, -sum, 0x00)
}

type pn532Cmd struct {
	cmd    byte
	params []byte
}

// pn532Sim simulates the PN532 I²C frame protocol: ACK, response, NACK resend, ACK abort and
// the status byte.
type pn532Sim struct {
	queue                                       [][]byte
	last                                        []byte
	commands                                    []pn532Cmd
	writes                                      [][]byte
	reads                                       []int
	card, noAck, noResponse, errorFrame, badDCS bool
	ic, status                                  byte
	uid                                         []byte
	listResponse                                []byte
	regs                                        map[uint16]byte
	failWrites                                  int
	pin                                         connection.InputPin
}

func newPN532Sim() *pn532Sim {
	return &pn532Sim{card: true, ic: 0x32, uid: []byte{0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66}, regs: map[uint16]byte{}}
}

func (m *pn532Sim) respond(cmd byte, p []byte) []byte {
	switch cmd {
	case 0x02:
		return []byte{m.ic, 0x01, 0x06, 0x07}
	case 0x04:
		return []byte{0x00, 0x01, 0x01, 0x01, 0x00, 0x00, 0x10, 0x01}
	case 0x4A:
		if m.listResponse != nil {
			return m.listResponse
		}
		if !m.card {
			return []byte{0}
		}
		return append([]byte{1, 1, 0x00, 0x44, 0x00, byte(len(m.uid))}, m.uid...)
	case 0x52, 0x54, 0x44, 0x16:
		return []byte{m.status}
	case 0x40:
		if m.status != 0 {
			return []byte{m.status}
		}
		if p[1] == 0x30 {
			r := []byte{0}
			for i := 0; i < 16; i++ {
				r = append(r, p[2]+byte(i))
			}
			return r
		}
		return []byte{0}
	case 0x42:
		r := []byte{0}
		for i := len(p) - 1; i >= 0; i-- {
			r = append(r, p[i])
		}
		return r
	case 0x06:
		return []byte{m.regs[uint16(p[0])<<8|uint16(p[1])]}
	case 0x08:
		m.regs[uint16(p[0])<<8|uint16(p[1])] = p[2]
		return nil
	case 0x00:
		if p[0] == 0 {
			return append([]byte(nil), p...)
		}
		return []byte{0}
	}
	return nil
}

func (m *pn532Sim) Write(data []byte) error {
	d := append([]byte(nil), data...)
	m.writes = append(m.writes, d)
	if m.failWrites > 0 {
		m.failWrites--
		return errors.New("nack")
	}
	if bytes.Equal(d, pn532Ack) {
		m.queue = nil
		return nil
	}
	if bytes.Equal(d, pn532Nack) {
		if m.last != nil {
			m.queue = append(m.queue, m.last)
		}
		return nil
	}
	ln := int(d[3])
	c := pn532Cmd{d[6], append([]byte(nil), d[7:5+ln]...)}
	m.commands = append(m.commands, c)
	if m.noAck {
		return nil
	}
	m.queue = [][]byte{pn532Ack}
	if m.noResponse {
		return nil
	}
	if m.errorFrame {
		m.queue = append(m.queue, pn532ErrorFrameBytes)
		return nil
	}
	f := pn532RespFrame(0xD5, append([]byte{c.cmd + 1}, m.respond(c.cmd, c.params)...))
	if m.badDCS {
		f[len(f)-2]++
	}
	m.queue = append(m.queue, f)
	return nil
}

func (m *pn532Sim) Read(n int) ([]byte, error) {
	m.reads = append(m.reads, n)
	out := make([]byte, n)
	if len(m.queue) == 0 {
		return out, nil
	}
	out[0] = 0x01
	if n == 1 {
		return out, nil
	}
	item := m.queue[0]
	m.queue = m.queue[1:]
	if !bytes.Equal(item, pn532Ack) {
		m.last = item
	}
	for i := 1; i < n; i++ {
		if i-1 < len(item) {
			out[i] = item[i-1]
		} else {
			out[i] = 0xFF
		}
	}
	return out, nil
}

func (m *pn532Sim) WriteRead(data []byte, n int) ([]byte, error) { return make([]byte, n), nil }
func (m *pn532Sim) Close() error                                 { return nil }
func (m *pn532Sim) Enable()                                      {}
func (m *pn532Sim) Disable()                                     {}
func (m *pn532Sim) IsEnabled() bool                              { return true }
func (m *pn532Sim) IntPin() connection.InputPin                  { return m.pin }
func (m *pn532Sim) EnPin() connection.OutputPin                  { return nil }

func (m *pn532Sim) lastCmd() pn532Cmd { return m.commands[len(m.commands)-1] }

func cmdIs(c pn532Cmd, cmd byte, params []byte) bool {
	return c.cmd == cmd && bytes.Equal(c.params, params)
}

func TestPN532FrameVector(t *testing.T) {
	if !bytes.Equal(pn532Frame(0x02, nil), []byte{0x00, 0x00, 0xFF, 0x02, 0xFE, 0xD4, 0x02, 0x2A, 0x00}) {
		t.Fatal("GetFirmwareVersion frame")
	}
}

func TestPN532MinimalInitAndReadUID(t *testing.T) {
	sim := newPN532Sim()
	m, _ := NewPN532Minimal(sim)
	if err := m.Init(); err != nil {
		t.Fatal(err)
	}
	if len(sim.commands) != 3 || !cmdIs(sim.commands[1], 0x14, []byte{0x01, 0x14, 0x01}) ||
		!cmdIs(sim.commands[2], 0x32, []byte{0x05, 0xFF, 0x01, 0x02}) {
		t.Fatalf("init commands %v", sim.commands)
	}
	if !bytes.Equal(sim.writes[1], pn532Nack) {
		t.Fatal("NACK after header")
	}
	acks, full := 0, 0
	for _, r := range sim.reads {
		if r == 7 {
			acks++
		}
		if r == 14 {
			full++
		}
	}
	if acks != 3 || full != 1 {
		t.Fatalf("reads acks=%d full=%d", acks, full)
	}
	uid, err := m.ReadUID(time.Second)
	if err != nil || !bytes.Equal(uid, sim.uid) {
		t.Fatalf("uid %x %v", uid, err)
	}
	if !cmdIs(sim.commands[3], 0x4A, []byte{0x01, 0x00}) || !cmdIs(sim.commands[4], 0x52, []byte{0x01}) {
		t.Fatal("list / release")
	}
	sim.card = false
	if uid, err := m.ReadUID(time.Second); uid != nil || err != nil {
		t.Fatal("no card")
	}
	sim.noResponse = true
	if uid, err := m.ReadUID(10 * time.Millisecond); uid != nil || err != nil {
		t.Fatal("timeout must return nil")
	}
	if !bytes.Equal(sim.writes[len(sim.writes)-1], pn532Ack) {
		t.Fatal("timeout must abort with ACK")
	}
	sim.noResponse = false
	sim.errorFrame = true
	if _, err := m.ReadUID(time.Second); !errors.Is(err, ErrPN532ErrorFrame) {
		t.Fatalf("error frame: %v", err)
	}
	sim.errorFrame = false
	sim.card = true
	sim.badDCS = true
	if _, err := m.ReadUID(time.Second); !errors.Is(err, ErrPN532Frame) {
		t.Fatalf("bad DCS: %v", err)
	}
}

func TestPN532MinimalErrors(t *testing.T) {
	sim := newPN532Sim()
	sim.ic = 0x31
	m, _ := NewPN532Minimal(sim)
	if err := m.Init(); err == nil {
		t.Fatal("bad IC must fail")
	}
	sim = newPN532Sim()
	sim.failWrites = 3
	m, _ = NewPN532Minimal(sim)
	if err := m.Init(); err != nil || len(sim.commands) != 3 {
		t.Fatalf("wake retry: %v", err)
	}
	sim = newPN532Sim()
	sim.noAck = true
	m, _ = NewPN532Minimal(sim)
	if err := m.Init(); !errors.Is(err, ErrPN532NoAck) || len(sim.commands) != 3 {
		t.Fatalf("no ACK: %v", err)
	}
}

func TestPN532FullInfoAndList(t *testing.T) {
	sim := newPN532Sim()
	f, _ := NewPN532Full(sim)
	fw, err := f.FirmwareVersion()
	if err != nil || fw != (PN532FirmwareVersion{0x32, 1, 6, 7, true, true, true}) {
		t.Fatalf("fw %+v %v", fw, err)
	}
	gs, err := f.GeneralStatus()
	if err != nil || gs != (PN532GeneralStatus{0, true, 1, 1}) {
		t.Fatalf("gs %+v %v", gs, err)
	}
	sim.listResponse = []byte{2, 1, 0x00, 0x04, 0x20, 4, 1, 2, 3, 4, 5, 0x75, 0x77, 0x81, 0x02,
		2, 0x00, 0x44, 0x00, 7, 9, 9, 9, 9, 9, 9, 9}
	ts, err := f.ListPassiveTargets(PN532Baud106A, 2, nil, time.Second)
	if err != nil || len(ts) != 2 {
		t.Fatalf("list %v %v", ts, err)
	}
	if ts[0].Tg != 1 || ts[0].SensRes != 0x0004 || ts[0].SelRes != 0x20 || !bytes.Equal(ts[0].UID, []byte{1, 2, 3, 4}) ||
		!bytes.Equal(ts[0].ATS, []byte{5, 0x75, 0x77, 0x81, 0x02}) {
		t.Fatalf("target A %+v", ts[0])
	}
	if ts[1].Tg != 2 || ts[1].SensRes != 0x0044 || len(ts[1].UID) != 7 || len(ts[1].ATS) != 0 {
		t.Fatalf("target B %+v", ts[1])
	}
	if !cmdIs(sim.lastCmd(), 0x4A, []byte{0x02, 0x00}) {
		t.Fatal("list params")
	}
	sim.listResponse = []byte{1, 1, 20, 0x01, 1, 2, 3, 4, 5, 6, 7, 8, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x12, 0xFC}
	ts, err = f.ListPassiveTargets(PN532Baud212F, 1, []byte{0x00, 0xFF, 0xFF, 0x01, 0x00}, time.Second)
	if err != nil || len(ts) != 1 || !bytes.Equal(ts[0].UID, []byte{1, 2, 3, 4, 5, 6, 7, 8}) ||
		!bytes.Equal(ts[0].ATS, []byte{0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x12, 0xFC}) {
		t.Fatalf("felica %+v %v", ts, err)
	}
	if !cmdIs(sim.lastCmd(), 0x4A, []byte{0x01, 0x01, 0x00, 0xFF, 0xFF, 0x01, 0x00}) {
		t.Fatal("felica params")
	}
	sim.listResponse = []byte{1, 1, 0x50, 0xA1, 0xA2, 0xA3, 0xA4, 0, 0, 0, 0, 0x81, 0x81, 0x00, 1, 0x00}
	ts, err = f.ListPassiveTargets(PN532Baud106B, 1, []byte{0x00}, time.Second)
	if err != nil || len(ts) != 1 || !bytes.Equal(ts[0].UID, []byte{0xA1, 0xA2, 0xA3, 0xA4}) || !bytes.Equal(ts[0].ATS, []byte{0}) {
		t.Fatalf("type B %+v %v", ts, err)
	}
	sim.listResponse = []byte{0}
	if ts, err := f.ListPassiveTargets(PN532Baud106A, 1, nil, time.Second); err != nil || len(ts) != 0 {
		t.Fatal("empty list")
	}
}

func TestPN532FullTagOperations(t *testing.T) {
	sim := newPN532Sim()
	f, _ := NewPN532Full(sim)
	b, err := f.MifareReadBlock(1, 4)
	if err != nil || b[0] != 4 || b[15] != 19 || !cmdIs(sim.lastCmd(), 0x40, []byte{0x01, 0x30, 0x04}) {
		t.Fatalf("read block %v %v", b, err)
	}
	key := [6]byte{0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF}
	if err := f.MifareAuthenticate(1, 7, key, sim.uid, false); err != nil ||
		!cmdIs(sim.lastCmd(), 0x40, []byte{0x01, 0x60, 0x07, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x33, 0x44, 0x55, 0x66}) {
		t.Fatal("auth payload")
	}
	_ = f.MifareAuthenticate(1, 7, key, []byte{0xDE, 0xAD, 0xBE, 0xEF}, true)
	if sim.lastCmd().params[1] != 0x61 {
		t.Fatal("key B")
	}
	if err := f.MifareWriteBlock(1, 4, [16]byte{}); err != nil ||
		!cmdIs(sim.lastCmd(), 0x40, append([]byte{0x01, 0xA0, 0x04}, make([]byte, 16)...)) {
		t.Fatal("write block")
	}
	if p, err := f.NTAGReadPage(1, 4); err != nil || p[0] != 4 {
		t.Fatal("ntag read")
	}
	if err := f.NTAGWritePage(1, 5, [4]byte{1, 2, 3, 4}); err != nil ||
		!cmdIs(sim.lastCmd(), 0x40, []byte{0x01, 0xA2, 0x05, 1, 2, 3, 4}) {
		t.Fatal("ntag write")
	}
	if r, err := f.CommunicateThru([]byte{1, 2, 3}); err != nil || !bytes.Equal(r, []byte{3, 2, 1}) {
		t.Fatal("communicate thru")
	}
	sim.status = 0x14
	var se *PN532StatusError
	if _, err := f.DataExchange(1, []byte{0x30, 0x00}); !errors.As(err, &se) || se.Code != 0x14 {
		t.Fatalf("status error %v", err)
	}
	if err := f.Release(1); !errors.As(err, &se) || se.Code != 0x14 {
		t.Fatal("release status")
	}
	sim.status = 0
	if f.Release(0) != nil || !cmdIs(sim.lastCmd(), 0x52, []byte{0x00}) {
		t.Fatal("release all")
	}
	if f.Select(1) != nil || !cmdIs(sim.lastCmd(), 0x54, []byte{0x01}) {
		t.Fatal("select")
	}
	if f.Deselect(1) != nil || !cmdIs(sim.lastCmd(), 0x44, []byte{0x01}) {
		t.Fatal("deselect")
	}
}

func TestPN532FullConfigAndRaw(t *testing.T) {
	sim := newPN532Sim()
	f, _ := NewPN532Full(sim)
	_ = f.SetRFField(false, false)
	if !cmdIs(sim.lastCmd(), 0x32, []byte{0x01, 0x00}) {
		t.Fatal("rf off")
	}
	_ = f.SetRFField(true, true)
	if !cmdIs(sim.lastCmd(), 0x32, []byte{0x01, 0x03}) {
		t.Fatal("rf on rfca")
	}
	_ = f.SetMaxRetries(0xFF, 0xFF, 1)
	if !cmdIs(sim.lastCmd(), 0x32, []byte{0x05, 0xFF, 0x01, 0xFF}) {
		t.Fatal("max retries")
	}
	_ = f.WriteRegister(0x6302, 0x5A)
	if !cmdIs(sim.lastCmd(), 0x08, []byte{0x63, 0x02, 0x5A}) {
		t.Fatal("write register")
	}
	if v, err := f.ReadRegister(0x6302); err != nil || v != 0x5A || !cmdIs(sim.lastCmd(), 0x06, []byte{0x63, 0x02}) {
		t.Fatal("read register")
	}
	if ok, err := f.SelfTest(0x00); err != nil || !ok || !cmdIs(sim.lastCmd(), 0x00, append([]byte{0}, []byte("PERIPH01")...)) {
		t.Fatal("self test echo")
	}
	if ok, err := f.SelfTest(0x01); err != nil || !ok {
		t.Fatal("self test rom")
	}
	if r, err := f.SendCommand(0x02, nil, time.Second); err != nil || !bytes.Equal(r, []byte{0x32, 0x01, 0x06, 0x07}) {
		t.Fatal("send command")
	}
	if f.PowerDown(0x20) != nil || !cmdIs(sim.lastCmd(), 0x16, []byte{0x20}) {
		t.Fatal("power down")
	}
	if f.StartCommand(0x02, nil) != nil {
		t.Fatal("start")
	}
	if ok, _ := f.WaitReady(10 * time.Millisecond); !ok {
		t.Fatal("wait ready")
	}
	if ok, _ := f.PollInterrupt(); !ok {
		t.Fatal("poll ready")
	}
	if r, err := f.ReadResponse(0x02, time.Second); err != nil || r[0] != 0x32 {
		t.Fatal("read response")
	}
	if ok, _ := f.PollInterrupt(); ok {
		t.Fatal("poll idle")
	}
	if ok, _ := f.WaitReady(5 * time.Millisecond); ok {
		t.Fatal("wait idle")
	}
}

type pn532FakePin struct{ handler func() }

func (p *pn532FakePin) OnEdge(_ connection.Trigger, h func()) func() {
	p.handler = h
	return func() { p.handler = nil }
}
func (p *pn532FakePin) IsHigh() (bool, error) { return true, nil }

func TestPN532Interrupt(t *testing.T) {
	sim := newPN532Sim()
	pin := &pn532FakePin{}
	sim.pin = pin
	f, _ := NewPN532Full(sim)
	hits := 0
	_ = f.OnInterrupt(func() { hits++ })
	if pin.handler == nil {
		t.Fatal("not registered")
	}
	pin.handler()
	if hits != 1 {
		t.Fatal("callback")
	}
	_ = f.OffInterrupt()
	if pin.handler != nil {
		t.Fatal("unsubscribe")
	}
}
