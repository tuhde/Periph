package led

import (
	"bytes"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// is31fl3731Mock is an in-memory fake connection.RegisterConnection for unit
// tests — no hardware, no bus. Every WriteReg is logged as reg+data; ReadReg
// answers from a register map.
type is31fl3731Mock struct {
	connection.RegisterConnection // embedded nil: only the methods overridden below are used
	writes                        [][]byte
	regs                          map[uint32]byte
}

func (m *is31fl3731Mock) WriteReg(reg uint32, data []byte) error {
	m.writes = append(m.writes, append([]byte{byte(reg)}, data...))
	return nil
}

func (m *is31fl3731Mock) ReadReg(reg uint32, length int) ([]byte, error) {
	out := make([]byte, length)
	for i := range out {
		out[i] = m.regs[reg+uint32(i)]
	}
	return out, nil
}

func (m *is31fl3731Mock) IntPin() connection.InputPin { return nil }
func (m *is31fl3731Mock) Close() error                { return nil }

func (m *is31fl3731Mock) last() []byte { return m.writes[len(m.writes)-1] }

func rep31(reg byte, n int, v byte) []byte {
	b := bytes.Repeat([]byte{v}, n+1)
	b[0] = reg
	return b
}

func TestIs31fl3731(t *testing.T) {
	m := &is31fl3731Mock{regs: map[uint32]byte{}}
	d, err := NewIs31fl3731Full(m)
	if err != nil {
		t.Fatal(err)
	}
	eq := func(label string, got []byte, want ...byte) {
		t.Helper()
		if !bytes.Equal(got, want) {
			t.Errorf("%s: got % X, want % X", label, got, want)
		}
	}
	w := func() [][]byte { return m.writes }
	reset := func() { m.writes = nil }

	if len(w()) != 10 {
		t.Fatalf("init writes = %d, want 10", len(w()))
	}
	eq("init0", w()[0], 0xFD, 0x0B)
	eq("init1", w()[1], 0x0A, 0x00)
	eq("init2", w()[2], 0x00, 0x00, 0x00)
	eq("init3", w()[3], 0x05, 0x00)
	eq("init4", w()[4], 0xFD, 0x00)
	eq("init5", w()[5], rep31(0x00, 18, 0xFF)...)
	eq("init6", w()[6], rep31(0x12, 18, 0)...)
	eq("init7", w()[7], rep31(0x24, 144, 0)...)
	eq("init8", w()[8], 0xFD, 0x0B)
	eq("init9", w()[9], 0x0A, 0x01)

	reset()
	if err := d.SetPixel(2, 5, 200); err != nil {
		t.Fatal(err)
	}
	if len(w()) != 2 {
		t.Fatalf("set_pixel writes = %d", len(w()))
	}
	eq("page", w()[0], 0xFD, 0x00)
	eq("pwm", w()[1], 0x24+37, 200)
	reset()
	_ = d.SetPixel(8, 15, 1)
	if len(w()) != 1 {
		t.Errorf("cached page: %d writes", len(w()))
	}
	eq("pwm_last", w()[0], 0x24+143, 1)
	if v, _ := d.GetPixel(2, 5); v != 200 {
		t.Errorf("get_pixel = %d", v)
	}
	if d.SetPixel(9, 0, 0) == nil || d.SetPixel(0, 16, 0) == nil {
		t.Error("pixel range not rejected")
	}

	reset()
	_ = d.Fill(0x40)
	eq("fill", m.last(), rep31(0x24, 144, 0x40)...)
	_ = d.Clear()
	eq("clear", m.last(), rep31(0x24, 144, 0)...)
	if v, _ := d.GetPixel(2, 5); v != 0 {
		t.Errorf("clear left pixel %d", v)
	}

	ramp := make([]byte, 144)
	for i := range ramp {
		ramp[i] = byte(i)
	}
	_ = d.SetPixels(ramp)
	eq("set_pixels", m.last(), append([]byte{0x24}, ramp...)...)
	if d.SetPixels(ramp[:143]) == nil {
		t.Error("set_pixels length not rejected")
	}
	if v, _ := d.GetPixel(1, 1); v != 17 {
		t.Errorf("get_pixel after set_pixels = %d", v)
	}

	_ = d.SetLEDEnabled(1, 9, false)
	eq("led_off", m.last(), 0x03, 0xFD)
	_ = d.SetLEDEnabled(1, 9, true)
	eq("led_on", m.last(), 0x03, 0xFF)
	_ = d.SetBlink(1, 9, true)
	eq("blink_bit", m.last(), 0x15, 0x02)
	_ = d.SetBlinkAll(true)
	eq("blink_all", m.last(), rep31(0x12, 18, 0xFF)...)
	_ = d.SetBlinkAll(false)
	eq("blink_all_off", m.last(), rep31(0x12, 18, 0)...)

	reset()
	_ = d.SelectFrame(3)
	eq("select_frame", m.last(), 0xFD, 0x03)
	if d.SelectFrame(8) == nil || d.CopyFrame(0, 8) == nil || d.ShowFrame(8) == nil || d.ClearFrame(8) == nil {
		t.Error("frame range not rejected")
	}
	_ = d.SetPixel(0, 0, 9)
	eq("draw_frame3", m.last(), 0x24, 9)
	_ = d.CopyFrame(3, 5)
	eq("copy_page", w()[len(w())-2], 0xFD, 0x05)
	want := rep31(0x24, 144, 0)
	want[1] = 9
	eq("copy_pwm", m.last(), want...)
	reset()
	_ = d.ClearAllFrames()
	n := 0
	for _, x := range w() {
		if len(x) == 145 {
			n++
		}
	}
	if n != 8 {
		t.Errorf("clear_all_frames wrote %d PWM blocks", n)
	}
	_ = d.SelectFrame(0)

	reset()
	_ = d.ShowFrame(4)
	if len(w()) != 2 {
		t.Fatalf("show_frame writes = %d", len(w()))
	}
	eq("show_frame_page", w()[0], 0xFD, 0x0B)
	eq("show_frame", w()[1], 0x00, 0x00, 0x04)
	_ = d.SetPictureMode()
	eq("picture_mode", m.last(), 0x00, 0x00)

	_ = d.StartAutoPlay(3, 2, 121, 1)
	eq("auto_play1", w()[len(w())-2], 0x02, 0x23, 11)
	eq("auto_play_cfg", m.last(), 0x00, 0x09)
	_ = d.StartAutoPlay(0, 0, 704, 0)
	eq("auto_play_64", w()[len(w())-2], 0x02, 0x00, 0x00)
	if d.StartAutoPlay(0, 0, 2000, 0) == nil || d.StartAutoPlay(0, 8, 121, 0) == nil {
		t.Error("auto play range not rejected")
	}
	m.regs[0x07] = 0x15
	_ = d.StopAutoPlay()
	eq("stop_auto_play", m.last(), 0x00, 0x00, 0x05)

	_ = d.SetAudioMode(true)
	eq("audio_mode", m.last(), 0x00, 0x10)
	_ = d.SetAudioMode(false)
	eq("audio_mode_off", m.last(), 0x00, 0x00)
	_ = d.SetAudioSync(true)
	eq("audio_sync", m.last(), 0x06, 0x01)
	_ = d.SetAudioGain(9, true, true)
	eq("audio_gain", m.last(), 0x0B, 0x1B)
	if d.SetAudioGain(4, false, false) == nil || d.SetAudioGain(24, false, false) == nil {
		t.Error("audio gain range not rejected")
	}
	_ = d.SetAudioRate(100)
	eq("audio_rate", m.last(), 0x0C, 100)

	_ = d.EnableBlink(true, 3)
	eq("enable_blink", m.last(), 0x05, 0x0B)
	_ = d.SetIntensityControl(true)
	eq("intensity", m.last(), 0x05, 0x2B)
	_ = d.EnableBlink(false, 1)
	eq("disable_blink", m.last(), 0x05, 0x21)
	if d.EnableBlink(true, 8) == nil {
		t.Error("blink period not rejected")
	}
	_ = d.SetBreath(true, 2, 5, 3)
	eq("breath", m.last(), 0x08, 0x52, 0x13)
	_ = d.SetBreath(false, 0, 0, 0)
	eq("breath_off", m.last(), 0x08, 0x00, 0x00)

	_ = d.Shutdown(true)
	eq("shutdown", m.last(), 0x0A, 0x00)
	_ = d.Shutdown(false)
	eq("shutdown_off", m.last(), 0x0A, 0x01)

	m.regs[0x07] = 0x13
	if v, _ := d.FrameState(); v != 0x13 {
		t.Errorf("frame_state = %X", v)
	}
	if v, _ := d.CurrentFrame(); v != 3 {
		t.Errorf("current_frame = %d", v)
	}
	if ok, _ := d.MovieFinished(); !ok {
		t.Error("movie_finished false")
	}
	m.regs[0x07] = 0x02
	if ok, _ := d.MovieFinished(); ok {
		t.Error("movie_finished true")
	}

	_ = d.SelectFrame(2)
	reset()
	m.regs[0x03] = 0x2A
	if v, _ := d.ReadFunctionRegister(0x03); v != 0x2A {
		t.Errorf("read_function_register = %X", v)
	}
	eq("read_restores_page", m.last(), 0xFD, 0x02)
	reset()
	_ = d.WriteFunctionRegister(0x03, 5)
	if len(w()) != 3 {
		t.Fatalf("write_function_register writes = %d", len(w()))
	}
	eq("wfr0", w()[0], 0xFD, 0x0B)
	eq("wfr1", w()[1], 0x03, 5)
	eq("wfr2", w()[2], 0xFD, 0x02)
	if d.WriteFunctionRegister(0x0D, 0) == nil {
		t.Error("function register range not rejected")
	}
	m.regs[0x07] = 0x14
	if v, _ := d.PollInterrupt(); v != 0x14 {
		t.Errorf("poll_interrupt = %X", v)
	}
	eq("poll_restores_page", m.last(), 0xFD, 0x02)

	if Is31fl3731Gamma32[31] != 255 || Is31fl3731Gamma64[63] != 255 || Is31fl3731Gamma64[9] != 10 {
		t.Error("gamma tables")
	}
	mm, err := NewIs31fl3731Minimal(&is31fl3731Mock{regs: map[uint32]byte{}})
	if err != nil || mm.SetPixel(0, 0, 1) != nil {
		t.Error("minimal")
	}
}
