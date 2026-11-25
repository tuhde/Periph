package adcdac

import (
	"errors"
	"fmt"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// cs43l22Mock is an in-memory register-file connection for the CS43L22 unit tests.
type cs43l22Mock struct {
	regs   [256]byte
	writes [][]byte
}

func (m *cs43l22Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	for i := 1; i < len(data); i++ {
		m.regs[int(data[0])+i-1] = data[i]
	}
	return nil
}
func (m *cs43l22Mock) Read(n int) ([]byte, error) { return make([]byte, n), nil }
func (m *cs43l22Mock) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	out := make([]byte, n)
	for i := range out {
		out[i] = m.regs[int(data[0])+i]
	}
	return out, nil
}
func (m *cs43l22Mock) ReadReg(reg uint32, length int) ([]byte, error) {
	return m.WriteRead([]byte{byte(reg)}, length)
}
func (m *cs43l22Mock) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}
func (m *cs43l22Mock) Close() error                { return nil }
func (m *cs43l22Mock) Enable()                     {}
func (m *cs43l22Mock) Disable()                    {}
func (m *cs43l22Mock) IsEnabled() bool             { return true }
func (m *cs43l22Mock) IntPin() connection.InputPin { return nil }
func (m *cs43l22Mock) EnPin() connection.OutputPin { return nil }

type cs43l22Pin struct{ levels []bool }

func (p *cs43l22Pin) Set(high bool) error {
	p.levels = append(p.levels, high)
	return nil
}

func newCS43L22(t *testing.T) (*cs43l22Mock, *CS43L22Full) {
	t.Helper()
	m := &cs43l22Mock{}
	m.regs[0x01] = 0xE1
	m.regs[0x32] = 0x3B
	d, err := NewCS43L22Full(m, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := d.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}
	return m, d
}

func cs43l22Expect(t *testing.T, m *cs43l22Mock, label string, want map[byte]byte) {
	t.Helper()
	for r, v := range want {
		if m.regs[r] != v {
			t.Errorf("%s: reg 0x%02X = 0x%02X, want 0x%02X", label, r, m.regs[r], v)
		}
	}
}

func cs43l22Must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

func TestCS43L22InitSequence(t *testing.T) {
	m := &cs43l22Mock{}
	m.regs[0x01] = 0xE1
	m.regs[0x32] = 0x3B
	pin := &cs43l22Pin{}
	d, _ := NewCS43L22Full(m, pin)
	if len(m.writes) != 0 || len(pin.levels) != 0 {
		t.Fatalf("construction made traffic")
	}
	cs43l22Must(t, d.Init())
	if len(pin.levels) != 2 || pin.levels[0] || !pin.levels[1] {
		t.Errorf("reset pulse = %v", pin.levels)
	}
	if fmt.Sprint(m.writes[1]) != "[2 1]" {
		t.Errorf("power-down first: %v", m.writes[1])
	}
	cs43l22Expect(t, m, "defaults", map[byte]byte{0x04: 0xAF, 0x05: 0xA0, 0x06: 0x04, 0x0D: 0x60, 0x20: 0, 0x21: 0, 0x02: 0x01})
	var req []string
	for _, w := range m.writes {
		if len(w) == 2 && (w[0] == 0x00 || w[0] == 0x47 || w[0] == 0x32) {
			req = append(req, fmt.Sprint(w))
		}
	}
	if fmt.Sprint(req) != "[[0 153] [71 128] [50 187] [50 59] [0 0]]" {
		t.Errorf("required sequence = %v", req)
	}
	bad := &cs43l22Mock{}
	b, _ := NewCS43L22Minimal(bad, nil)
	if err := b.Init(); !errors.Is(err, ErrCS43L22NotFound) {
		t.Errorf("bad ID error = %v", err)
	}
}

func TestCS43L22StartStopVolume(t *testing.T) {
	m, d := newCS43L22(t)
	cs43l22Must(t, d.Start())
	cs43l22Expect(t, m, "start", map[byte]byte{0x02: 0x9E})
	m.regs[0x0E], m.regs[0x0A] = 0x03, 0x0F
	cs43l22Must(t, d.Stop())
	cs43l22Expect(t, m, "stop", map[byte]byte{0x02: 0x9F, 0x0E: 0x00, 0x0A: 0x00})
	if m.regs[0x0F]&0xF0 != 0xF0 {
		t.Errorf("stop did not mute")
	}
	for _, c := range []struct {
		db   float32
		a, b byte
	}{{12, 0x18, 0x18}, {-102, 0x34, 0x34}, {-500, 0x34, 0x34}, {-0.5, 0xFF, 0xFF}, {0, 0, 0}} {
		cs43l22Must(t, d.SetVolume(c.db))
		cs43l22Expect(t, m, fmt.Sprint("volume ", c.db), map[byte]byte{0x20: c.a, 0x21: c.b})
	}
	cs43l22Must(t, d.SetVolume(-0.5))
	if v, _ := d.GetVolume(); v != -0.5 {
		t.Errorf("GetVolume = %v", v)
	}
	cs43l22Must(t, d.SetVolumeChannels(-10, 3))
	cs43l22Expect(t, m, "channels", map[byte]byte{0x20: 0xEC, 0x21: 0x06})
}

func TestCS43L22ChannelVolumesAndMutes(t *testing.T) {
	m, d := newCS43L22(t)
	cs43l22Must(t, d.SetHeadphoneVolume(-20, -20))
	cs43l22Expect(t, m, "hp", map[byte]byte{0x22: 0xD8, 0x23: 0xD8})
	cs43l22Must(t, d.SetHeadphoneVolume(-96, -100))
	cs43l22Expect(t, m, "hp mute", map[byte]byte{0x22: 0x40, 0x23: 0x01})
	cs43l22Must(t, d.SetSpeakerVolume(5, -1))
	cs43l22Expect(t, m, "spk", map[byte]byte{0x24: 0x00, 0x25: 0xFE})
	cs43l22Must(t, d.SetPCMVolume(-51.5, 12))
	cs43l22Expect(t, m, "pcm", map[byte]byte{0x1A: 0x19, 0x1B: 0x18})
	cs43l22Must(t, d.MutePCM(true, false))
	cs43l22Expect(t, m, "pcm mute", map[byte]byte{0x1A: 0x99, 0x1B: 0x18})
	cs43l22Must(t, d.SetPCMVolume(0, 0))
	cs43l22Expect(t, m, "pcm keeps mute", map[byte]byte{0x1A: 0x80})
	cs43l22Must(t, d.SetPassthroughVolume(-60, 12))
	cs43l22Expect(t, m, "pass vol", map[byte]byte{0x14: 0x88, 0x15: 0x18})
	m.regs[0x0F] = 0
	cs43l22Must(t, d.Mute(true))
	cs43l22Expect(t, m, "mute", map[byte]byte{0x0F: 0xC0})
	cs43l22Must(t, d.Mute(false))
	cs43l22Expect(t, m, "unmute", map[byte]byte{0x0F: 0x00})
	cs43l22Must(t, d.MuteOutputs(-1, 1))
	cs43l22Expect(t, m, "spk only", map[byte]byte{0x0F: 0x30})
	cs43l22Must(t, d.MuteOutputs(1, 0))
	cs43l22Expect(t, m, "hp mute spk unmute", map[byte]byte{0x0F: 0xC0})
	cs43l22Must(t, d.SetSpeakerMode(true, false, true))
	if m.regs[0x0F]&7 != 3 {
		t.Errorf("speaker mode = 0x%02X", m.regs[0x0F])
	}
}

func TestCS43L22OutputsGainClockingInterface(t *testing.T) {
	m, d := newCS43L22(t)
	cs43l22Must(t, d.SetOutputs(CS43L22OutOn, CS43L22OutOff))
	cs43l22Expect(t, m, "outputs", map[byte]byte{0x04: 0xAF})
	cs43l22Must(t, d.SetOutputs(0, 1))
	cs43l22Expect(t, m, "outputs follow", map[byte]byte{0x04: 0x05})
	if err := d.SetOutputs(4, 0); !errors.Is(err, ErrCS43L22Argument) {
		t.Errorf("SetOutputs(4) = %v", err)
	}
	cs43l22Must(t, d.SetHeadphoneGain(CS43L22Gain1000))
	if g, _ := d.HeadphoneGain(); g != 1.0 || m.regs[0x0D] != 0xC0 {
		t.Errorf("gain = %v reg 0x%02X", g, m.regs[0x0D])
	}
	if err := d.SetHeadphoneGain(8); err == nil {
		t.Errorf("SetHeadphoneGain(8) accepted")
	}
	cs43l22Must(t, d.SetClocking(DefaultCS43L22Clocking()))
	cs43l22Expect(t, m, "clocking default", map[byte]byte{0x05: 0xA0})
	cs43l22Must(t, d.SetClocking(CS43L22Clocking{Speed: 3, Group32k: true, VideoClk: true, Ratio: 3, MclkDiv2: true}))
	cs43l22Expect(t, m, "clocking all", map[byte]byte{0x05: 0x7F})
	if err := d.SetClocking(CS43L22Clocking{Speed: 4}); err == nil {
		t.Errorf("speed 4 accepted")
	}
	cs43l22Must(t, d.SetInterface(DefaultCS43L22Interface()))
	cs43l22Expect(t, m, "iface default", map[byte]byte{0x06: 0x04, 0x07: 0x00})
	cs43l22Must(t, d.SetInterface(CS43L22Interface{Master: true, Fmt: CS43L22FmtRJ, WordLength: 2, InvertSclk: true, SclkEqMclk: true, InvertSwitch: true}))
	cs43l22Expect(t, m, "iface all", map[byte]byte{0x06: 0xCA, 0x07: 0x48})
	cs43l22Must(t, d.SetInterface(CS43L22Interface{Fmt: CS43L22FmtLJ, WordLength: 1, DSP: true}))
	cs43l22Expect(t, m, "iface dsp", map[byte]byte{0x06: 0x11})
	if err := d.SetInterface(CS43L22Interface{Fmt: CS43L22FmtI2S, DSP: true}); err == nil {
		t.Errorf("DSP without LJ accepted")
	}
}

func TestCS43L22ClockingTable(t *testing.T) {
	cases := []struct {
		mclk             uint32
		fs               float64
		speed, ratio     uint8
		g32, video, div2 bool
	}{
		{12288000, 48000, 1, 0, false, false, false},
		{12288000, 8000, 3, 0, true, false, false},
		{11289600, 44100, 1, 0, false, false, false},
		{12000000, 48000, 1, 1, false, false, false},
		{24000000, 44117.647, 1, 3, false, false, true},
		{27000000, 16000, 2, 1, true, true, false},
		{16934400, 8018.18, 3, 2, false, false, false},
	}
	for _, c := range cases {
		got, err := CS43L22ClockingFor(c.mclk, c.fs)
		if err != nil || got.Auto || got.Speed != c.speed || got.Ratio != c.ratio || got.Group32k != c.g32 || got.VideoClk != c.video || got.MclkDiv2 != c.div2 {
			t.Errorf("ClockingFor(%d, %v) = %+v, %v", c.mclk, c.fs, got, err)
		}
	}
	if _, err := CS43L22ClockingFor(12288000, 44100); err == nil {
		t.Errorf("unsupported pair accepted")
	}
}

func TestCS43L22ToneBeepLimiter(t *testing.T) {
	m, d := newCS43L22(t)
	cs43l22Must(t, d.SetTone(12, -10.5, 1, 2, true))
	if m.regs[0x1F] != 0x0F || m.regs[0x1E]&0x1F != 0x0D {
		t.Errorf("tone regs = 0x%02X 0x%02X", m.regs[0x1F], m.regs[0x1E])
	}
	cs43l22Must(t, d.SetTone(0, 0, 0, 0, false))
	if m.regs[0x1F] != 0x88 || m.regs[0x1E]&1 != 0 {
		t.Errorf("flat tone regs = 0x%02X 0x%02X", m.regs[0x1F], m.regs[0x1E])
	}
	cs43l22Must(t, d.SetBeep(0xC, 2, 3, 6, CS43L22BeepSingle, true))
	if m.regs[0x1C] != 0xC2 || m.regs[0x1D] != 0x66 || m.regs[0x1E]>>6 != 1 {
		t.Errorf("beep regs = 0x%02X 0x%02X 0x%02X", m.regs[0x1C], m.regs[0x1D], m.regs[0x1E])
	}
	if err := d.SetBeep(1, 1, 1, 0, 1, true); !errors.Is(err, ErrCS43L22BeepActive) {
		t.Errorf("active beep error = %v", err)
	}
	cs43l22Must(t, d.StopBeep())
	if m.regs[0x1E]>>6 != 0 {
		t.Errorf("beep not stopped")
	}
	cs43l22Must(t, d.SetBeep(1, 1, 0, -56, 3, false))
	if m.regs[0x1D]&0x1F != 0x07 || m.regs[0x1E]&0xE0 != 0xE0 {
		t.Errorf("min volume regs = 0x%02X 0x%02X", m.regs[0x1D], m.regs[0x1E])
	}
	cs43l22Must(t, d.SetBeep(1, 1, 0, 0, CS43L22BeepOff, true))
	if m.regs[0x1E]>>6 != 0 {
		t.Errorf("BeepOff did not stop")
	}
	cs43l22Must(t, d.SetBeep(1, 1, 0, 0, 2, true))
	if m.regs[0x1D]&0x1F != 0x03 {
		t.Errorf("0 dB beep volume = 0x%02X", m.regs[0x1D])
	}
	cs43l22Must(t, d.StopBeep())
	if err := d.SetBeep(16, 0, 0, 0, 1, true); !errors.Is(err, ErrCS43L22Argument) {
		t.Errorf("frequency 16 accepted")
	}
	cs43l22Must(t, d.SetLimiter(CS43L22Limiter{Enabled: true, MaxDB: -6, CushionDB: -12, Release: 0x20, Attack: 5, SoftRampDisable: true, ZeroCrossDisable: true}))
	cs43l22Expect(t, m, "limiter", map[byte]byte{0x27: 0x53, 0x28: 0xA0, 0x29: 5})
	l := DefaultCS43L22Limiter()
	l.MaxDB = -29
	cs43l22Must(t, d.SetLimiter(l))
	if m.regs[0x27]>>5 != 7 || m.regs[0x28] != 0x7F {
		t.Errorf("nearest limiter regs = 0x%02X 0x%02X", m.regs[0x27], m.regs[0x28])
	}
	l.Release = 64
	if err := d.SetLimiter(l); err == nil {
		t.Errorf("release 64 accepted")
	}
}

func TestCS43L22DSPPassthroughStatusRaw(t *testing.T) {
	m, d := newCS43L22(t)
	m.regs[0x0E], m.regs[0x0A] = 0, 0xA5
	cs43l22Must(t, d.SetSoftRamp(true, true, false))
	cs43l22Expect(t, m, "soft ramp", map[byte]byte{0x0E: 0x02, 0x0A: 0xA7})
	cs43l22Must(t, d.SetZeroCross(true, false, true))
	cs43l22Expect(t, m, "zero cross", map[byte]byte{0x0E: 0x03, 0x0A: 0xA6})
	cs43l22Must(t, d.SetDeemphasis(true))
	cs43l22Must(t, d.Freeze(true))
	if m.regs[0x0E]&0x0C != 0x0C {
		t.Errorf("deemph/freeze = 0x%02X", m.regs[0x0E])
	}
	cs43l22Must(t, d.Freeze(false))
	if m.regs[0x0E]&0x08 != 0 {
		t.Errorf("freeze not cleared")
	}
	cs43l22Must(t, d.SetPCMSwap(CS43L22SrcSwapped, CS43L22SrcMix))
	cs43l22Expect(t, m, "pcm swap", map[byte]byte{0x26: 0xD0})
	cs43l22Must(t, d.InvertPCM(true, false))
	if m.regs[0x0D]&0x0C != 0x04 {
		t.Errorf("invert = 0x%02X", m.regs[0x0D])
	}
	cs43l22Must(t, d.SetPassthrough(true, 2, 4))
	cs43l22Expect(t, m, "passthrough", map[byte]byte{0x08: 0x82, 0x09: 0x88})
	if m.regs[0x0E]&0xC0 != 0xC0 {
		t.Errorf("passthrough enable = 0x%02X", m.regs[0x0E])
	}
	if err := d.SetPassthrough(true, 0, 1); err == nil {
		t.Errorf("input 0 accepted")
	}
	cs43l22Must(t, d.MutePassthrough(true, false))
	if m.regs[0x0E]&0x30 != 0x10 {
		t.Errorf("mute passthrough = 0x%02X", m.regs[0x0E])
	}
	cs43l22Must(t, d.SetBatteryCompensation(true, 9, true))
	cs43l22Expect(t, m, "battery", map[byte]byte{0x2F: 0xC9})
	m.regs[0x30] = 0x40
	if v, _ := d.ReadVPVoltage(2.5); v < 2.5*64/63.3-1e-4 || v > 2.5*64/63.3+1e-4 {
		t.Errorf("VP = %v", v)
	}
	m.regs[0x2E] = 0x4C
	st, _ := d.ReadStatus()
	if !st.ClockError || !st.PCMAOverflow || !st.PCMBOverflow || st.DSPAOverflow || st.DSPBOverflow {
		t.Errorf("status = %+v", st)
	}
	m.regs[0x31] = 0x28
	sp, _ := d.ReadSpeakerStatus()
	if !sp.SpeakerAShort || !sp.SpkrHpPin || sp.SpeakerBShort {
		t.Errorf("speaker status = %+v", sp)
	}
	m.regs[0x34] = 0x5F
	cs43l22Must(t, d.SetChargePumpFrequency(7))
	cs43l22Expect(t, m, "charge pump", map[byte]byte{0x34: 0x7F})
	if err := d.SetChargePumpFrequency(16); err == nil {
		t.Errorf("n=16 accepted")
	}
	if id, rev, _ := d.ReadID(); id != 0x1C || rev != 1 {
		t.Errorf("ReadID = %d %d", id, rev)
	}
	if v, err := d.ReadRegister(0x01); err != nil || v != 0xE1 {
		t.Errorf("ReadRegister(1) = %v %v", v, err)
	}
	if _, err := d.ReadRegister(0x03); err == nil {
		t.Errorf("reserved read accepted")
	}
	cs43l22Must(t, d.WriteRegister(0x20, 0x10))
	cs43l22Expect(t, m, "write register", map[byte]byte{0x20: 0x10})
	if d.WriteRegister(0x2E, 0) == nil || d.WriteRegister(0x33, 0) == nil {
		t.Errorf("read-only / reserved write accepted")
	}
	if err := d.Reset(); !errors.Is(err, ErrCS43L22NoResetPin) {
		t.Errorf("Reset without pin = %v", err)
	}
}

func TestCS43L22Reset(t *testing.T) {
	m := &cs43l22Mock{}
	m.regs[0x01] = 0xE1
	pin := &cs43l22Pin{}
	d, _ := NewCS43L22Full(m, pin)
	cs43l22Must(t, d.Init())
	cs43l22Must(t, d.Start())
	cs43l22Must(t, d.Reset())
	if m.regs[0x02] != 0x01 || len(pin.levels) != 4 {
		t.Errorf("reset: power 0x%02X pin %v", m.regs[0x02], pin.levels)
	}
}
