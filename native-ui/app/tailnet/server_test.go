package main

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type fakeBackend struct {
	fakeDirectory
	opts        *StartOptions
	closed      atomic.Bool
	logins      atomic.Int32
	pingTargets []netip.Addr
}

func (f *fakeBackend) Start(ctx context.Context, emit func(StatusSummary)) error {
	emit(StatusSummary{BackendState: "NeedsLogin", AuthURL: "https://login.example.test/a/123"})
	return nil
}

func (f *fakeBackend) Summary(ctx context.Context) (StatusSummary, error) {
	return StatusSummary{BackendState: "Running", SelfName: "phone", SelfIPs: []string{"100.64.0.1"}, Online: true}, nil
}

func (f *fakeBackend) PeerList(ctx context.Context) []PeerInfo {
	var out []PeerInfo
	for _, p := range f.peers {
		p := p
		out = append(out, PeerInfo{Name: p.ShortName, IPs: []string{p.IPs[0].String()}, Online: p.Online})
	}
	return out
}

func (f *fakeBackend) Login(ctx context.Context) error  { f.logins.Add(1); return nil }
func (f *fakeBackend) Logout(ctx context.Context) error { return nil }
func (f *fakeBackend) Dial(ctx context.Context, network, address string) (net.Conn, error) {
	return nil, errTailnetNotRunning
}
func (f *fakeBackend) Close() error { f.closed.Store(true); return nil }

func (f *fakeBackend) Ping(ctx context.Context, ip netip.Addr) PingReply {
	f.pingTargets = append(f.pingTargets, ip)
	latency := 1.5
	return PingReply{Path: pathDirect, LatencyMS: &latency}
}

type harness struct {
	t       *testing.T
	stdin   *io.PipeWriter
	lines   chan map[string]any
	raw     *strings.Builder
	exit    chan int
	backend *fakeBackend
}

func newHarness(t *testing.T) *harness {
	inR, inW := io.Pipe()
	outR, outW := io.Pipe()
	h := &harness{t: t, stdin: inW, lines: make(chan map[string]any, 64), raw: &strings.Builder{}, exit: make(chan int, 1)}
	h.backend = &fakeBackend{fakeDirectory: fakeDirectory{peers: samplePeers()}}
	s := NewServer(NewWriter(outW), func(opts *StartOptions) Backend { h.backend.opts = opts; return h.backend }, directDialer())
	go func() { h.exit <- s.Run(inR); outW.Close() }()
	go func() {
		scanner := bufio.NewScanner(outR)
		for scanner.Scan() {
			h.raw.WriteString(scanner.Text() + "\n")
			var m map[string]any
			if err := json.Unmarshal(scanner.Bytes(), &m); err != nil {
				t.Errorf("输出不是 JSON 行：%q", scanner.Text())
				continue
			}
			h.lines <- m
		}
		close(h.lines)
	}()
	return h
}

func (h *harness) send(line string) {
	if _, err := io.WriteString(h.stdin, line+"\n"); err != nil {
		h.t.Fatal(err)
	}
}

// response 等待指定 id 的应答，跳过事件。
func (h *harness) response(id float64) map[string]any {
	timeout := time.After(5 * time.Second)
	for {
		select {
		case m, ok := <-h.lines:
			if !ok {
				h.t.Fatalf("输出提前结束，等待 id=%v", id)
			}
			if m["id"] == id {
				return m
			}
		case <-timeout:
			h.t.Fatalf("等待 id=%v 超时", id)
		}
	}
}

func (h *harness) event() map[string]any {
	timeout := time.After(5 * time.Second)
	for {
		select {
		case m := <-h.lines:
			if m["event"] != nil {
				return m
			}
		case <-timeout:
			h.t.Fatal("等待事件超时")
		}
	}
}

const validStart = `{"id":1,"op":"start","start":{"stateDir":"%s","hostname":"infinity-test","authKey":"tskey-auth-SECRETVALUE","proxyUser":"` + testUser + `","proxyPassword":"` + testPassword + `"}}`

func TestProtocolLifecycle(t *testing.T) {
	h := newHarness(t)
	dir := t.TempDir()

	h.send(`{"id":9,"op":"status"}`)
	if r := h.response(9); r["ok"] != false || r["error"] != ErrNotStarted {
		t.Fatalf("启动前的命令应报告 not_started：%v", r)
	}
	h.send(`{"id":2,"op":"start","start":{"stateDir":"relative","hostname":"x","proxyUser":"u","proxyPassword":"p"}}`)
	if r := h.response(2); r["ok"] != false || r["error"] != ErrBadRequest {
		t.Fatalf("非法参数应拒绝：%v", r)
	}
	h.send(strings.Replace(validStart, "%s", dir, 1))
	r := h.response(1)
	if r["ok"] != true {
		t.Fatalf("start 失败：%v", r)
	}
	port := r["result"].(map[string]any)["proxyPort"].(float64)
	if port <= 0 {
		t.Fatalf("应返回代理端口：%v", r)
	}
	if h.backend.opts.AuthKey != "tskey-auth-SECRETVALUE" || h.backend.opts.StateDir != dir {
		t.Fatalf("start 参数应原样交给 tsnet：%+v", h.backend.opts.StateDir)
	}
	ev := h.event()
	if state := ev["state"].(map[string]any); state["backendState"] != "NeedsLogin" || state["authUrl"] == "" {
		t.Fatalf("应推送登录状态：%v", ev)
	}
	h.send(strings.Replace(validStart, "%s", dir, 1))
	if r := h.response(1); r["error"] != ErrStarted {
		t.Fatalf("重复 start 应拒绝：%v", r)
	}
	h.send(`{"id":3,"op":"status"}`)
	if r := h.response(3); r["result"].(map[string]any)["backendState"] != "Running" {
		t.Fatalf("status 结果错误：%v", r)
	}
	h.send(`{"id":4,"op":"peers"}`)
	peers := h.response(4)["result"].(map[string]any)["peers"].([]any)
	if len(peers) != 2 {
		t.Fatalf("应列出全部 tailnet 节点：%v", peers)
	}
	h.send(`{"id":7,"op":"login"}`)
	h.response(7)
	if h.backend.logins.Load() != 1 {
		t.Fatal("login 应调用后端")
	}
	h.send(`not json`)
	if r := h.response(0); r["error"] != ErrBadRequest {
		t.Fatalf("无法解析的行应回复 bad_request：%v", r)
	}
	h.send(`{"id":8,"op":"bogus"}`)
	if r := h.response(8); r["error"] != ErrUnsupported {
		t.Fatalf("未知命令应回复 unsupported_op：%v", r)
	}
	h.send(`{"id":10,"op":"status","pad":"` + strings.Repeat("x", maxLineBytes) + `"}`)
	if r := h.response(0); r["error"] != ErrBadRequest {
		t.Fatalf("超长行应拒绝：%v", r)
	}
	h.send(`{"id":11,"op":"status"}`)
	h.response(11)

	// stdin 关闭（父进程退出）后应关闭节点并以 0 退出。
	h.stdin.Close()
	select {
	case code := <-h.exit:
		if code != 0 {
			t.Fatalf("退出码 %d", code)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("stdin 关闭后未退出")
	}
	if !h.backend.closed.Load() {
		t.Fatal("退出前应关闭 tsnet")
	}
	for range h.lines {
	}
	if out := h.raw.String(); strings.Contains(out, "SECRETVALUE") || strings.Contains(out, testPassword) {
		t.Fatalf("协议输出不得包含 auth key 或代理口令：%s", out)
	}
}

func TestShutdownCommandExits(t *testing.T) {
	h := newHarness(t)
	h.send(strings.Replace(validStart, "%s", t.TempDir(), 1))
	h.response(1)
	h.send(`{"id":2,"op":"shutdown"}`)
	h.response(2)
	select {
	case <-h.exit:
	case <-time.After(5 * time.Second):
		t.Fatal("shutdown 后未退出")
	}
	if !h.backend.closed.Load() {
		t.Fatal("shutdown 应关闭 tsnet")
	}
}

func TestValidateStart(t *testing.T) {
	base := StartOptions{StateDir: "/data/tailnet", Hostname: "infinity-pixel", ProxyUser: testUser, ProxyPassword: testPassword}
	if p := validateStart(&base); p != "" {
		t.Fatalf("合法参数被拒绝：%s", p)
	}
	for name, mutate := range map[string]func(*StartOptions){
		"相对路径":     func(o *StartOptions) { o.StateDir = "tailnet" },
		"主机名大写":    func(o *StartOptions) { o.Hostname = "Infinity Workbench" },
		"主机名过长":    func(o *StartOptions) { o.Hostname = strings.Repeat("a", 64) },
		"口令过短":     func(o *StartOptions) { o.ProxyPassword = "short" },
		"控制服务器带凭据": func(o *StartOptions) { o.ControlURL = "https://u:p@hs.example.org" },
		"控制服务器协议":  func(o *StartOptions) { o.ControlURL = "ftp://hs.example.org" },
	} {
		o := base
		mutate(&o)
		if validateStart(&o) == "" {
			t.Errorf("%s 应拒绝", name)
		}
	}
	o := base
	o.ControlURL = "https://headscale.example.org:8443"
	if p := validateStart(&o); p != "" {
		t.Fatalf("自定义控制服务器被拒绝：%s", p)
	}
}
