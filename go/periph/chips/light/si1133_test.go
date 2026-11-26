package light

import (
	"bytes"
	"errors"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// si1133Mock simulates the command engine: RESPONSE0 counter, parameter table and FORCE
// packing HOSTOUT from per-channel sample values.
type si1133Mock struct {
	partID         byte
	params         [64]byte
	counter, err   byte
	resp1          byte
	irqEnable      byte
	irqStatus      byte
	hostout        [26]byte
	samples        [6]int32
	commands       []byte
	writes         [][]byte
	ignoreCommands bool
	pin            connection.InputPin
}

func newSi1133Mock() *si1133Mock {
	return &si1133Mock{partID: 0x33, counter: 0x0F, samples: [6]int32{0x012345, -5, 0xABCD}}
}

func (m *si1133Mock) resp0() byte {
	if m.err != 0 {
		return 0x30 | m.err&0x0F
	}
	return 0x20 | m.counter
}

func (m *si1133Mock) command(cmd, hostin byte) {
	m.commands = append(m.commands, cmd)
	if m.ignoreCommands {
		return
	}
	switch {
	case cmd == 0x00:
		m.counter, m.err = 0, 0
		return
	case cmd == 0x01:
		m.params = [64]byte{}
		m.counter, m.err, m.irqEnable = 0x0F, 0, 0
		return
	case cmd&0xC0 == 0x80:
		p := cmd & 0x3F
		if p > 0x2B {
			m.err = 0x11
			return
		}
		m.params[p], m.resp1 = hostin, hostin
	case cmd&0xC0 == 0x40:
		m.resp1 = m.params[cmd&0x3F]
	case cmd == 0x11:
		m.hostout = [26]byte{}
		pos := 0
		for ch := 0; ch < 6; ch++ {
			if m.params[1]&(1<<ch) == 0 {
				continue
			}
			v := uint32(m.samples[ch])
			if m.params[0x04+4*ch]&0x40 != 0 {
				m.hostout[pos], m.hostout[pos+1], m.hostout[pos+2] = byte(v>>16), byte(v>>8), byte(v)
				pos += 3
			} else {
				m.hostout[pos], m.hostout[pos+1] = byte(v>>8), byte(v)
				pos += 2
			}
		}
		m.irqStatus |= m.params[1] & m.irqEnable
	case cmd == 0x12 || cmd == 0x13:
	default:
		m.err = 0x10
		return
	}
	m.counter = (m.counter + 1) & 0x0F
}

func (m *si1133Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	switch {
	case data[0] == 0x0A && len(data) >= 3:
		m.command(data[2], data[1])
	case data[0] == 0x0B && len(data) >= 2:
		m.command(data[1], 0)
	case data[0] == 0x0F && len(data) >= 2:
		m.irqEnable = data[1]
	}
	return nil
}

func (m *si1133Mock) ReadReg(reg uint32, n int) ([]byte, error) {
	m.writes = append(m.writes, []byte{byte(reg)})
	out := make([]byte, n)
	switch {
	case reg == 0x00:
		copy(out, []byte{m.partID, 0x03, 0x10})
	case reg == 0x10:
		out[0] = m.resp1
	case reg == 0x11:
		out[0] = m.resp0()
	case reg == 0x12:
		out[0] = m.irqStatus
		m.irqStatus = 0
	case reg >= 0x13:
		copy(out, m.hostout[reg-0x13:])
	}
	return out, nil
}

func (m *si1133Mock) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}
func (m *si1133Mock) Read(n int) ([]byte, error) { return make([]byte, n), nil }
func (m *si1133Mock) WriteRead(data []byte, n int) ([]byte, error) {
	return m.ReadReg(uint32(data[0]), n)
}
func (m *si1133Mock) Close() error                { return nil }
func (m *si1133Mock) Enable()                     {}
func (m *si1133Mock) Disable()                    {}
func (m *si1133Mock) IsEnabled() bool             { return true }
func (m *si1133Mock) IntPin() connection.InputPin { return m.pin }
func (m *si1133Mock) EnPin() connection.OutputPin { return nil }

func (m *si1133Mock) wrote(frame []byte) bool {
	for _, w := range m.writes {
		if bytes.Equal(w, frame) {
			return true
		}
	}
	return false
}

func (m *si1133Mock) lastCommand() byte { return m.commands[len(m.commands)-1] }

type si1133FakePin struct {
	handler func()
	trigger connection.Trigger
}

func (p *si1133FakePin) OnEdge(trigger connection.Trigger, handler func()) func() {
	p.handler, p.trigger = handler, trigger
	return func() { p.handler = nil }
}

func (p *si1133FakePin) IsHigh() (bool, error) { return true, nil }

func newSi1133FullTest(t *testing.T) (*si1133Mock, *Si1133Full) {
	t.Helper()
	c := newSi1133Mock()
	s, _ := NewSi1133Full(c)
	if len(c.writes) != 0 {
		t.Fatal("constructor touched the bus")
	}
	if err := s.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}
	return c, s
}

func TestSi1133InitAndRead(t *testing.T) {
	c, s := newSi1133FullTest(t)
	if c.commands[0] != 0x01 {
		t.Error("init must start with RESET_SW")
	}
	if c.params[0x02] != 0x18 || c.params[0x03] != 0x09 || c.params[0x04] != 0x40 {
		t.Error("channel 0 not configured for UV")
	}
	if c.params[0x06] != 0x0B || c.params[0x07] != 0x07 || c.params[0x08] != 0x40 {
		t.Error("channel 1 not configured for white")
	}
	if c.params[0x01] != 0x03 || c.irqEnable != 0x3F {
		t.Error("CHAN_LIST / IRQ_ENABLE defaults")
	}
	if !c.wrote([]byte{0x0A, 0x03, 0x81}) {
		t.Error("PARAM_SET must write HOSTIN0 and COMMAND in one transaction")
	}
	r, err := s.Read()
	if err != nil || r.UV != 0x012345 || r.White != -5 {
		t.Errorf("Read = %+v, %v", r, err)
	}
	if c.lastCommand() != 0x11 {
		t.Error("Read must FORCE")
	}
}

func TestSi1133WrongPart(t *testing.T) {
	c := newSi1133Mock()
	c.partID = 0x32
	s, _ := NewSi1133Minimal(c)
	if err := s.Init(); !errors.Is(err, ErrSi1133NotFound) {
		t.Errorf("Init = %v", err)
	}
}

func TestSi1133ChannelsAndScratch(t *testing.T) {
	c, s := newSi1133FullTest(t)
	if err := s.ConfigureChannel(2, Si1133MuxLargeIR, 3, 2, 1, true, false, 1); err != nil {
		t.Fatal(err)
	}
	if c.params[0x0A] != 0x22 || c.params[0x0B] != 0xA3 || c.params[0x0C] != 0x08 {
		t.Errorf("channel 2 params % X", c.params[0x0A:0x0D])
	}
	if err := s.EnableChannels(0x07); err != nil || c.params[0x01] != 0x07 {
		t.Fatal("EnableChannels")
	}
	v, err := s.ReadChannels(true)
	if err != nil || len(v) != 3 || v[0] != 0x012345 || v[1] != -5 || v[2] != 0xABCD {
		t.Errorf("ReadChannels = %v, %v", v, err)
	}
	v, err = s.ReadChannels(false)
	if err != nil || len(v) != 3 || v[2] != 0xABCD {
		t.Errorf("ReadChannels(false) = %v, %v", v, err)
	}
	if r, err := s.Read(); err != nil || r.UV != 0x012345 {
		t.Errorf("Read after reconfiguration = %+v, %v", r, err)
	}
	if s.ConfigureChannel(6, 0, 0, 0, 0, false, true, 0) == nil || s.ConfigureChannel(0, 0x18, 12, 0, 0, false, true, 0) == nil {
		t.Error("ConfigureChannel must reject out-of-range values")
	}
	if s.EnableChannels(0x40) == nil {
		t.Error("EnableChannels must reject 0x40")
	}
	c.samples[5] = 1234
	if x, err := s.ReadUV(); err != nil || x != 1234 || c.params[0x16] != 0x18 || c.params[0x17] != 0x09 {
		t.Errorf("ReadUV = %d, %v", x, err)
	}
	if c.params[0x01] != 0x07 {
		t.Error("scratch read must restore CHAN_LIST")
	}
	if x, err := s.ReadWhite(); err != nil || x != 1234 || c.params[0x16] != 0x0B {
		t.Errorf("ReadWhite = %d, %v", x, err)
	}
	if x, err := s.ReadIR(Si1133MuxMediumIR); err != nil || x != 1234 || c.params[0x16] != 0x01 {
		t.Errorf("ReadIR = %d, %v", x, err)
	}
	if _, err := s.ReadIR(0x0B); !errors.Is(err, ErrSi1133InvalidArgument) {
		t.Error("ReadIR must reject non-IR photodiodes")
	}
	if err := s.Force(); err != nil || c.lastCommand() != 0x11 {
		t.Error("Force")
	}
}

func TestSi1133AutonomousBurstThresholds(t *testing.T) {
	c, s := newSi1133FullTest(t)
	if err := s.StartAutonomous(1.0, [3]uint8{2, 3, 4}); err != nil {
		t.Fatal(err)
	}
	if uint16(c.params[0x1A])<<8|uint16(c.params[0x1B]) != 1250 {
		t.Error("MEASRATE")
	}
	if c.params[0x1C] != 2 || c.params[0x1D] != 3 || c.params[0x1E] != 4 {
		t.Error("MEASCOUNT")
	}
	if c.params[0x05] != 0x40 || c.params[0x09] != 0x40 || c.lastCommand() != 0x13 {
		t.Error("COUNTER_INDEX / START")
	}
	if err := s.SetChannelCounter(2, 3); err != nil || c.params[0x0D] != 0xC0 {
		t.Error("SetChannelCounter")
	}
	if err := s.Pause(); err != nil || c.lastCommand() != 0x12 {
		t.Error("Pause")
	}
	if s.StartAutonomous(0, [3]uint8{1, 1, 1}) == nil {
		t.Error("StartAutonomous must reject a zero rate")
	}
	if err := s.SetBurst(4); err != nil || c.params[0x2B] != 0x84 {
		t.Error("SetBurst(4)")
	}
	if err := s.SetBurst(0); err != nil || c.params[0x2B] != 0 {
		t.Error("SetBurst(0)")
	}
	if s.SetBurst(128) == nil {
		t.Error("SetBurst must reject 128")
	}
	if err := s.SetThreshold(1, 0x1234); err != nil || c.params[0x27] != 0x12 || c.params[0x28] != 0x34 {
		t.Error("SetThreshold")
	}
	if err := s.SetChannelThreshold(0, 2); err != nil || c.params[0x04] != 0x42 {
		t.Error("SetChannelThreshold")
	}
	if s.SetThreshold(3, 0) == nil {
		t.Error("SetThreshold must reject index 3")
	}
}

func TestSi1133ParametersAndStatus(t *testing.T) {
	c, s := newSi1133FullTest(t)
	if err := s.WriteParameter(0x1C, 0x55); err != nil || c.params[0x1C] != 0x55 {
		t.Error("WriteParameter")
	}
	if v, err := s.ReadParameter(0x1C); err != nil || v != 0x55 || c.lastCommand() != 0x5C {
		t.Errorf("ReadParameter = %d, %v", v, err)
	}
	if r, err := s.ReadResponse(); err != nil || r.State != 0x20 || r.Error || r.Counter != c.counter {
		t.Errorf("ReadResponse = %+v, %v", r, err)
	}
	if err := s.WriteParameter(0x30, 1); !errors.Is(err, ErrSi1133Command) {
		t.Errorf("invalid parameter address: %v", err)
	}
	if c.lastCommand() != 0x00 || c.err != 0 {
		t.Error("command error must be cleared with RESET_CMD_CTR")
	}
	c.irqStatus = 0x05
	if m, _ := s.DataReady(); m != 0x05 {
		t.Error("DataReady")
	}
	if m, _ := s.DataReady(); m != 0 {
		t.Error("DataReady must clear")
	}
	if p, h, r, err := s.ReadID(); err != nil || p != 0x33 || h != 0x03 || r != 0x10 {
		t.Error("ReadID")
	}
	if err := s.SetI2CAddress(0x30); err != nil || c.params[0x00] != 0x30 {
		t.Error("SetI2CAddress")
	}
	if s.SetI2CAddress(0x80) == nil {
		t.Error("SetI2CAddress must reject 0x80")
	}
	if err := s.Reset(); err != nil || c.params[0x01] != 0x03 || c.params[0x02] != 0x18 {
		t.Error("Reset must re-apply defaults")
	}
	if r, err := s.Read(); err != nil || r.UV != 0x012345 {
		t.Errorf("Read after reset = %+v, %v", r, err)
	}
}

func TestSi1133Interrupt(t *testing.T) {
	c, s := newSi1133FullTest(t)
	if err := s.DisableInterrupt(Si1133SourceChannel1); err != nil || c.irqEnable != 0x3D {
		t.Error("DisableInterrupt")
	}
	if err := s.EnableInterrupt(Si1133SourceChannel1); err != nil || c.irqEnable != 0x3F {
		t.Error("EnableInterrupt")
	}
	c.irqStatus = 0x02
	if m, _ := s.PollInterrupt(); m != 0x02 {
		t.Error("PollInterrupt")
	}
	pin := &si1133FakePin{}
	c.pin = pin
	var got []uint8
	if err := s.OnInterrupt(func(st uint8) { got = append(got, st) }); err != nil {
		t.Fatal(err)
	}
	if pin.handler == nil || pin.trigger != connection.Falling {
		t.Fatal("OnInterrupt must subscribe to the falling edge")
	}
	c.irqStatus = 0x01
	pin.handler()
	if len(got) != 1 || got[0] != 0x01 {
		t.Errorf("callback got %v", got)
	}
	if err := s.OffInterrupt(); err != nil || pin.handler != nil {
		t.Error("OffInterrupt")
	}
}

func TestSi1133CommandTimeout(t *testing.T) {
	c := newSi1133Mock()
	s, _ := NewSi1133Minimal(c)
	if err := s.Init(); err != nil {
		t.Fatal(err)
	}
	c.ignoreCommands = true
	if _, err := s.Read(); !errors.Is(err, ErrSi1133Timeout) {
		t.Errorf("Read = %v", err)
	}
}
