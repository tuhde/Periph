package adcdac

import (
	"bytes"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// es8311Mock is an in-memory register-file connection for the ES8311 unit tests.
type es8311Mock struct {
	regs   [256]byte
	writes [][]byte
}

func (m *es8311Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	for i := 1; i < len(data); i++ {
		m.regs[int(data[0])+i-1] = data[i]
	}
	return nil
}
func (m *es8311Mock) Read(n int) ([]byte, error) { return make([]byte, n), nil }
func (m *es8311Mock) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	out := make([]byte, n)
	for i := range out {
		out[i] = m.regs[int(data[0])+i]
	}
	return out, nil
}
func (m *es8311Mock) ReadReg(reg uint32, length int) ([]byte, error) {
	return m.WriteRead([]byte{byte(reg)}, length)
}
func (m *es8311Mock) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}
func (m *es8311Mock) Close() error                { return nil }
func (m *es8311Mock) Enable()                     {}
func (m *es8311Mock) Disable()                    {}
func (m *es8311Mock) IsEnabled() bool             { return true }
func (m *es8311Mock) IntPin() connection.InputPin { return nil }
func (m *es8311Mock) EnPin() connection.OutputPin { return nil }

func newES8311Full(t *testing.T) (*es8311Mock, *ES8311Full) {
	t.Helper()
	m := &es8311Mock{}
	d, err := NewES8311Full(m, 12288000, 48000)
	if err != nil {
		t.Fatalf("NewES8311Full: %v", err)
	}
	return m, d
}

func expectRegs(t *testing.T, m *es8311Mock, label string, want map[byte]byte) {
	t.Helper()
	for r, v := range want {
		if m.regs[r] != v {
			t.Errorf("%s: reg 0x%02X = 0x%02X, want 0x%02X", label, r, m.regs[r], v)
		}
	}
}

func TestES8311Init(t *testing.T) {
	m := &es8311Mock{}
	if _, err := NewES8311Minimal(m, 12288000, 48000); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(m.writes[0], []byte{0x00, 0x1F}) || !bytes.Equal(m.writes[1], []byte{0x00, 0x00}) ||
		!bytes.Equal(m.writes[2], []byte{0x00, 0x80}) {
		t.Errorf("reset sequence = %v", m.writes[:3])
	}
	expectRegs(t, m, "init", map[byte]byte{
		0x01: 0x3F, 0x02: 0x00, 0x03: 0x10, 0x04: 0x10, 0x05: 0x00, 0x06: 0x03, 0x07: 0x00, 0x08: 0xFF,
		0x09: 0x0C, 0x0A: 0x0C, 0x0D: 0x01, 0x0E: 0x02, 0x12: 0x00, 0x13: 0x10, 0x1C: 0x6A, 0x37: 0x08,
		0x14: 0x18, 0x17: 0xBF, 0x32: 0x00, 0x16: 0x04,
	})
}

func TestES8311MinimalAPI(t *testing.T) {
	m := &es8311Mock{}
	d, _ := NewES8311Minimal(m, 12288000, 48000)
	for _, c := range []struct {
		db   float32
		want byte
	}{{0, 0xBF}, {-95.5, 0x00}, {32, 0xFF}, {100, 0xFF}, {-10.2, 171}} {
		d.SetVolume(c.db)
		if m.regs[0x32] != c.want {
			t.Errorf("SetVolume(%v) = 0x%02X, want 0x%02X", c.db, m.regs[0x32], c.want)
		}
	}
	m.regs[0x31] = 0x81
	d.SetMute(true)
	if m.regs[0x31] != 0xE1 {
		t.Errorf("mute = 0x%02X", m.regs[0x31])
	}
	d.SetMute(false)
	if m.regs[0x31] != 0x81 {
		t.Errorf("unmute = 0x%02X", m.regs[0x31])
	}
	d.SetMicGain(30)
	if m.regs[0x14] != 0x1A {
		t.Errorf("mic 30 dB = 0x%02X", m.regs[0x14])
	}
	d.SetMicGain(7.4)
	if m.regs[0x14] != 0x12 {
		t.Errorf("mic 7.4 dB = 0x%02X", m.regs[0x14])
	}
	d.SetMicGain(99)
	if m.regs[0x14]&0x0F != 10 {
		t.Errorf("mic clamp = 0x%02X", m.regs[0x14])
	}
}

func TestES8311ClockSolver(t *testing.T) {
	if _, err := NewES8311Minimal(&es8311Mock{}, 1000, 48000); err == nil {
		t.Error("expected error for impossible clock")
	}
	m := &es8311Mock{}
	NewES8311Minimal(m, 24576000, 48000)
	if m.regs[0x02] != 0x20 {
		t.Errorf("preset 0x02 = 0x%02X", m.regs[0x02])
	}
	m = &es8311Mock{}
	NewES8311Minimal(m, 12288000, 16000)
	r02 := m.regs[0x02]
	mults := []uint64{1, 2, 4, 8}
	ratio := 12288000 * mults[(r02>>3)&3] / (uint64((r02>>5)&7) + 1) / (uint64(m.regs[0x05]&0x0F) + 1) / 16000
	if ratio != 256 && ratio != 384 && ratio != 512 && ratio != 768 {
		t.Errorf("computed ratio %d not allowed", ratio)
	}
	if m.regs[0x03] != m.regs[0x04] || m.regs[0x03] < 16 || m.regs[0x03] > 63 {
		t.Errorf("OSR code = 0x%02X/0x%02X", m.regs[0x03], m.regs[0x04])
	}
}

func TestES8311PowerAndReadback(t *testing.T) {
	m, d := newES8311Full(t)
	d.SetVolume(-20)
	if v, _ := d.GetVolume(); v != -20 {
		t.Errorf("GetVolume = %v", v)
	}
	d.SetMicGain(21)
	if g, _ := d.GetMicGain(); g != 21 {
		t.Errorf("GetMicGain = %v", g)
	}
	n := len(m.writes)
	d.Reset()
	if len(m.writes) != n+3 || !bytes.Equal(m.writes[n], []byte{0, 0x1F}) || !bytes.Equal(m.writes[n+2], []byte{0, 0x80}) {
		t.Errorf("reset writes = %v", m.writes[n:])
	}
	d.PowerDown()
	expectRegs(t, m, "power_down", map[byte]byte{0x00: 0x1F, 0x0D: 0xFC})
	if m.regs[0x31]&0x60 != 0x60 || m.regs[0x0E]&0x70 != 0x70 {
		t.Errorf("power_down regs 0x31=0x%02X 0x0E=0x%02X", m.regs[0x31], m.regs[0x0E])
	}
	d.PowerUp()
	expectRegs(t, m, "power_up", map[byte]byte{0x00: 0x80, 0x0D: 0x01})
	d.SetLowPower(ES8311LowPower{DAC: true, Integrator: true})
	expectRegs(t, m, "lp1", map[byte]byte{0x0F: 0x81})
	d.SetLowPower(ES8311LowPower{PGA: true, ADC: true, ADCRef: true, Flash: true})
	expectRegs(t, m, "lp2", map[byte]byte{0x0F: 0x40 | 0x10 | 0x08 | 0x02})
}

func TestES8311ClockAndSerial(t *testing.T) {
	m, d := newES8311Full(t)
	d.ConfigureClock(12288000, 48000, true, true)
	expectRegs(t, m, "clk invert", map[byte]byte{0x01: 0xFF})
	d.ConfigureClock(12288000, 48000, false, false)
	expectRegs(t, m, "clk default", map[byte]byte{0x01: 0x3F})
	if err := d.ConfigureClock(1000, 48000, false, false); err == nil {
		t.Error("expected invalid clock error")
	}

	d.ConfigureSerial(ES8311SerialConfig{Format: ES8311FormatLeftJustified, WordLength: 24})
	expectRegs(t, m, "lj24", map[byte]byte{0x09: 0x01, 0x0A: 0x01})
	d.ConfigureSerial(ES8311SerialConfig{Format: ES8311FormatDSPB, WordLength: 32, DACRight: true})
	expectRegs(t, m, "dspb32", map[byte]byte{0x09: 0x80 | 0x20 | 4<<2 | 3, 0x0A: 0x20 | 4<<2 | 3})
	d.ConfigureSerial(ES8311SerialConfig{Format: ES8311FormatDSPA, LrckInvert: true})
	expectRegs(t, m, "dspa", map[byte]byte{0x0A: 0x20 | 3<<2 | 3})
	if m.regs[0x00]&0x44 != 0x04 {
		t.Errorf("slave RST_MST: 0x%02X", m.regs[0x00])
	}
	d.SetSDPMute(true, false)
	if m.regs[0x09]&0x40 == 0 || m.regs[0x0A]&0x40 != 0 {
		t.Error("sdp mute in only")
	}
	d.ConfigureSerial(ES8311SerialConfig{})
	if m.regs[0x09]&0x40 == 0 {
		t.Error("serial must keep the mute bit")
	}
	d.ConfigureSerial(ES8311SerialConfig{Master: true, BclkDiv: 4, LrckDiv: 256})
	if m.regs[0x00]&0x44 != 0x40 || m.regs[0x06] != 0x03 || m.regs[0x07]&0x0F != 0 || m.regs[0x08] != 0xFF {
		t.Errorf("master regs 0x00=0x%02X 0x06=0x%02X 0x08=0x%02X", m.regs[0x00], m.regs[0x06], m.regs[0x08])
	}
	d.ConfigureSerial(ES8311SerialConfig{Master: true, BclkDiv: 32, LrckDiv: 64, StoppedBclk: true, BclkInvert: true})
	expectRegs(t, m, "master table div", map[byte]byte{0x06: 0x40 | 0x20 | 24})
	if d.ConfigureSerial(ES8311SerialConfig{Format: 9}) == nil || d.ConfigureSerial(ES8311SerialConfig{WordLength: 8}) == nil {
		t.Error("expected errors for bad format / word length")
	}
	d.SetTristate(true, false)
	expectRegs(t, m, "tri1", map[byte]byte{0x07: m.regs[0x07]&^0x30 | 0x20})
	d.SetTristate(false, true)
	if m.regs[0x07]&0x30 != 0x10 {
		t.Error("tristate adcdat")
	}
}

func TestES8311InputAndADC(t *testing.T) {
	m, d := newES8311Full(t)
	d.SetInput(ES8311InputDigital)
	if m.regs[0x14]&0x70 != 0x40 || m.regs[0x0E]&0x40 == 0 {
		t.Error("input digital")
	}
	d.SetInput(ES8311InputAnalog)
	if m.regs[0x14]&0x70 != 0x10 || m.regs[0x0E]&0x40 != 0 {
		t.Error("input analog")
	}
	d.SetInput(ES8311InputNone)
	if m.regs[0x14]&0x70 != 0x00 || m.regs[0x0E]&0x40 == 0 {
		t.Error("input none")
	}
	if d.SetInput(7) == nil {
		t.Error("expected input error")
	}
	d.ConfigureDMIC(true)
	if m.regs[0x15]&1 != 1 {
		t.Error("dmic falling")
	}
	d.ConfigureDMIC(false)
	if m.regs[0x15]&1 != 0 {
		t.Error("dmic rising")
	}
	d.SetADCVolume(-95.5)
	if m.regs[0x17] != 0 {
		t.Error("adc volume min")
	}
	cfg := ES8311ADCConfig{ScaleDB: 42, OSR: 128, HPFOff: true, HPFCoeff1: 5, HPFCoeff2: 0x0A}
	if err := d.ConfigureADC(cfg); err != nil {
		t.Fatal(err)
	}
	if m.regs[0x16]&7 != 7 || m.regs[0x03] != 32 || m.regs[0x1C]&0x3F != 0x0A || m.regs[0x1B]&0x1F != 0x05 {
		t.Errorf("adc cfg regs 0x16=0x%02X 0x03=0x%02X 0x1C=0x%02X 0x1B=0x%02X", m.regs[0x16], m.regs[0x03], m.regs[0x1C], m.regs[0x1B])
	}
	dbl := DefaultES8311ADCConfig()
	dbl.DoubleSpeed = true
	d.ConfigureADC(dbl)
	if m.regs[0x03] != 0x40|32 || m.regs[0x1C]&0x20 == 0 {
		t.Errorf("double speed 0x03=0x%02X", m.regs[0x03])
	}
	bad := DefaultES8311ADCConfig()
	bad.OSR = 8
	if d.ConfigureADC(bad) == nil {
		t.Error("expected OSR error")
	}
	bad = DefaultES8311ADCConfig()
	bad.OSR, bad.DoubleSpeed = 20, true
	if d.ConfigureADC(bad) == nil {
		t.Error("expected double-speed OSR error")
	}
	d.SetADCRamp(7)
	if m.regs[0x15]>>4 != 7 {
		t.Error("adc ramp")
	}
	d.SetADCRamp(99)
	if m.regs[0x15]>>4 != 15 {
		t.Error("adc ramp clamp")
	}
	d.ConfigureALC(ES8311ALCConfig{Enabled: true, MaxLevelDB: -12, MinLevelDB: -18.1, Window: 3, Automute: true, AutomuteWindow: 2, NoiseGateDB: -60})
	expectRegs(t, m, "alc", map[byte]byte{0x18: 0xC3, 0x19: 7<<4 | 3, 0x1A: 2<<4 | 6})
	d.ConfigureALC(DefaultES8311ALCConfig())
	expectRegs(t, m, "alc off", map[byte]byte{0x18: 0, 0x19: 0xF0, 0x1A: 0})
}

func TestES8311EqualizersAndRouting(t *testing.T) {
	m, d := newES8311Full(t)
	if err := d.SetADCEq([5]uint32{1, 2, 3, 4, 0x3FFFFFFF}); err != nil {
		t.Fatal(err)
	}
	if m.regs[0x1C]&0x40 != 0 || m.regs[0x20] != 1 || m.regs[0x2D] != 0x3F || m.regs[0x30] != 0xFF {
		t.Error("adc eq registers")
	}
	burst := false
	for _, w := range m.writes {
		if len(w) == 21 && w[0] == 0x1D {
			burst = true
		}
	}
	if !burst {
		t.Error("expected one 21-byte EQ burst")
	}
	d.BypassADCEq()
	if m.regs[0x1C]&0x40 == 0 {
		t.Error("adc eq bypass")
	}
	if d.SetADCEq([5]uint32{1 << 30}) == nil {
		t.Error("expected 30-bit range error")
	}
	d.SetDACEq([3]uint32{0x100, 0x200, 0x300})
	if m.regs[0x37]&0x08 != 0 || m.regs[0x3A] != 1 || m.regs[0x3E] != 2 || m.regs[0x42] != 3 {
		t.Error("dac eq registers")
	}
	d.BypassDACEq()
	if m.regs[0x37]&0x08 == 0 {
		t.Error("dac eq bypass")
	}
	d.SetDACRamp(8)
	if m.regs[0x37]>>4 != 8 || m.regs[0x37]&0x08 == 0 {
		t.Error("dac ramp")
	}
	d.ConfigureDRC(ES8311DRCConfig{Enabled: true, MaxLevelDB: -12, MinLevelDB: -24.1, Window: 5})
	expectRegs(t, m, "drc", map[byte]byte{0x34: 0x85, 0x35: 7<<4 | 1})
	d.SetDACMuteOptions(true, false, true)
	if m.regs[0x31]&0xE0 != 0xC0 {
		t.Error("mute options")
	}
	d.SetDACMuteOptions(true, true, false)
	if m.regs[0x31]&0xE0 != 0x60 {
		t.Error("mute options default")
	}
	d.SetLoopback(true)
	d.SetAdcdatMode(ES8311AdcdatDACADC)
	expectRegs(t, m, "adcdat keeps loopback", map[byte]byte{0x44: 0xC0})
	d.SetAdcdatMode(ES8311AdcdatDACDAC)
	if (m.regs[0x44]>>4)&7 != 6 {
		t.Error("adcdat dac_dac")
	}
	d.SetLoopback(false)
	expectRegs(t, m, "loopback off keeps mode", map[byte]byte{0x44: 0x60})
	if d.SetAdcdatMode(3) == nil {
		t.Error("expected ADCDAT mode error")
	}
	d.WriteRegister(0x40, 0x34)
	if v, _ := d.ReadRegister(0x40); v != 0x34 {
		t.Error("raw register")
	}
}
