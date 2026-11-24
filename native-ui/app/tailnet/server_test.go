package main

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"net/url"
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
	return newHarnessWithDial(t, directDialer())
}

func newHarnessWithDial(t *testing.T, direct DialFunc) *harness {
	inR, inW := io.Pipe()
	outR, outW := io.Pipe()
	h := &harness{t: t, stdin: inW, lines: make(chan map[string]any, 64), raw: &strings.Builder{}, exit: make(chan int, 1)}
	h.backend = &fakeBackend{fakeDirectory: fakeDirectory{peers: samplePeers()}}
	s := NewServer(NewWriter(outW), func(opts *StartOptions) Backend { h.backend.opts = opts; return h.backend }, direct)
	t.Cleanup(func() { inW.Close(); inR.Close(); outR.Close() })
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

func TestSetUpstreamRPC(t *testing.T) {
	connects := make(chan string, 4)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodConnect || r.Header.Get("Proxy-Authorization") != basic("fixture", "upstream-secret") {
			w.WriteHeader(http.StatusProxyAuthRequired)
			return
		}
		conn, buf, err := w.(http.Hijacker).Hijack()
		if err != nil {
			t.Errorf("接管 CONNECT：%v", err)
			return
		}
		defer conn.Close()
		connects <- r.Host
		buf.WriteString("HTTP/1.1 200 Connection established\r\n\r\nvia")
		buf.Flush()
		io.Copy(io.Discard, buf)
	}))
	defer upstream.Close()
	upstreamAddr := strings.TrimPrefix(upstream.URL, "http://")
	echo := startEcho(t)
	h := newHarnessWithDial(t, func(ctx context.Context, network, addr string) (net.Conn, error) {
		if addr != upstreamAddr {
			addr = echo
		}
		return directDialer()(ctx, network, addr)
	})
	h.send(fmt.Sprintf(`{"id":2,"op":"set_upstream","upstream":{"address":%q,"proxyUser":"fixture","proxyPassword":"upstream-secret"}}`, upstreamAddr))
	if r := h.response(2); r["ok"] != true {
		t.Fatalf("预配置上游失败：%v", r)
	}
	h.send(strings.Replace(validStart, "%s", t.TempDir(), 1))
	start := h.response(1)
	proxyAddr := fmt.Sprintf("127.0.0.1:%.0f", start["result"].(map[string]any)["proxyPort"])
	for i, line := range []string{
		`{"op":"set_upstream"}`,
		`{"op":"set_upstream","upstream":{"address":"localhost:1234","proxyUser":"fixture","proxyPassword":"fixture"}}`,
		`{"op":"set_upstream","upstream":{"address":"127.0.0.1:65536","proxyUser":"fixture","proxyPassword":"fixture"}}`,
		`{"op":"set_upstream","upstream":{"address":"127.0.0.1:1234","proxyUser":"fixture"}}`,
		fmt.Sprintf(`{"op":"set_upstream","upstream":{"address":%q,"proxyUser":"fixture","proxyPassword":"fixture"}}`, proxyAddr),
	} {
		id := i + 10
		h.send(fmt.Sprintf(`{"id":%d,%s`, id, line[1:]))
		if r := h.response(float64(id)); r["error"] != ErrBadRequest {
			t.Fatalf("非法上游参数应拒绝：%v", r)
		}
	}
	open := func() (net.Conn, *bufio.Reader) {
		t.Helper()
		conn, err := net.DialTimeout("tcp", proxyAddr, time.Second)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { conn.Close() })
		conn.SetDeadline(time.Now().Add(3 * time.Second))
		fmt.Fprintf(conn, "CONNECT 8.8.8.8:443 HTTP/1.1\r\nHost: 8.8.8.8:443\r\nProxy-Authorization: %s\r\n\r\n", basic(testUser, testPassword))
		reader := bufio.NewReader(conn)
		response, err := http.ReadResponse(reader, &http.Request{Method: http.MethodConnect})
		if err != nil || response.StatusCode != http.StatusOK {
			t.Fatalf("下游 CONNECT 失败：%v", err)
		}
		return conn, reader
	}
	_, chained := open()
	buf := make([]byte, 3)
	if _, err := io.ReadFull(chained, buf); err != nil || string(buf) != "via" {
		t.Fatalf("应保留 CONNECT 响应中已缓冲的隧道字节：%q，%v", buf, err)
	}
	if got := <-connects; got != "8.8.8.8:443" {
		t.Fatalf("上游目标错误：%q", got)
	}
	h.send(`{"id":3,"op":"set_upstream","upstream":null}`)
	if r := h.response(3); r["ok"] != true {
		t.Fatalf("清空上游失败：%v", r)
	}
	if _, err := chained.ReadByte(); err != io.EOF {
		t.Fatalf("清空后原上游隧道应关闭：%v", err)
	}
	conn, direct := open()
	io.WriteString(conn, "new")
	if _, err := io.ReadFull(direct, buf); err != nil || string(buf) != "new" {
		t.Fatalf("清空后应恢复直连：%q，%v", buf, err)
	}
	h.stdin.Close()
	select {
	case <-h.exit:
	case <-time.After(5 * time.Second):
		t.Fatal("测试进程未退出")
	}
	for range h.lines {
	}
	if strings.Contains(h.raw.String(), "upstream-secret") {
		t.Fatal("协议输出回显上游口令")
	}
}

func TestSetUpstreamReplacesHTTPForwardPool(t *testing.T) {
	origin := func(tag string) string {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			for _, name := range []string{"Proxy-Authorization", "Proxy-Connection", "X-Request-Hop"} {
				if r.Header.Get(name) != "" {
					t.Errorf("源站收到逐跳头 %s", name)
				}
			}
			if r.Header.Get("Authorization") != "Bearer fixture" {
				t.Error("源站应收到端到端认证头")
			}
			w.Header().Set("Connection", "X-Response-Hop")
			w.Header().Set("X-Response-Hop", "fixture")
			w.Header().Set("Proxy-Authenticate", "Basic realm=fixture")
			io.WriteString(w, tag)
		}))
		t.Cleanup(server.Close)
		return strings.TrimPrefix(server.URL, "http://")
	}
	directOrigin := origin("direct")
	upstreamDial := &targetRecorder{backend: origin("upstream")}
	upstream := &Proxy{Credentials: Credentials{User: "fixture", Password: "relay-pass"}, Dial: upstreamDial.dial}
	_, err := upstream.Listen()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(upstream.Close)
	upstreamAddr := upstream.listener.Addr().String()
	h := newHarnessWithDial(t, func(ctx context.Context, network, addr string) (net.Conn, error) {
		if addr != upstreamAddr {
			addr = directOrigin
		}
		return directDialer()(ctx, network, addr)
	})
	h.send(strings.Replace(validStart, "%s", t.TempDir(), 1))
	start := h.response(1)
	proxyURL := &url.URL{
		Scheme: "http",
		Host:   fmt.Sprintf("127.0.0.1:%.0f", start["result"].(map[string]any)["proxyPort"]),
		User:   url.UserPassword(testUser, testPassword),
	}
	transport := &http.Transport{Proxy: http.ProxyURL(proxyURL)}
	t.Cleanup(transport.CloseIdleConnections)
	client := &http.Client{Transport: transport, Timeout: 5 * time.Second}
	verify := func(want string, status int) {
		t.Helper()
		request, _ := http.NewRequest(http.MethodGet, "http://9.9.9.9/example", nil)
		request.Header.Set("Authorization", "Bearer fixture")
		request.Header.Set("Proxy-Connection", "keep-alive")
		request.Header.Set("Connection", "X-Request-Hop")
		request.Header.Set("X-Request-Hop", "fixture")
		response, err := client.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		body, err := io.ReadAll(response.Body)
		if err != nil || strings.TrimSpace(string(body)) != want || response.StatusCode != status {
			t.Fatalf("转发得到状态 %d，正文 %q，错误 %v", response.StatusCode, body, err)
		}
		if response.Header.Get("X-Response-Hop") != "" || response.Header.Get("Proxy-Authenticate") != "" {
			t.Fatal("下游收到逐跳响应头")
		}
	}
	set := func(id int, password string) {
		t.Helper()
		h.send(fmt.Sprintf(`{"id":%d,"op":"set_upstream","upstream":{"address":%q,"proxyUser":"fixture","proxyPassword":%q}}`, id, upstreamAddr, password))
		if r := h.response(float64(id)); r["ok"] != true {
			t.Fatalf("设置上游失败：%v", r)
		}
	}
	verify("direct", http.StatusOK)
	set(2, "relay-pass")
	verify("upstream", http.StatusOK)
	if got := upstreamDial.last(); got != "9.9.9.9:80" {
		t.Fatalf("普通 HTTP 应以 CONNECT 建立上游隧道，实际目标 %q", got)
	}
	set(3, "wrong")
	verify(errUpstreamConnect.Error(), http.StatusBadGateway)
	h.send(`{"id":4,"op":"set_upstream","upstream":null}`)
	if r := h.response(4); r["ok"] != true {
		t.Fatalf("清空上游失败：%v", r)
	}
	verify("direct", http.StatusOK)
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
		"主机名大写":    func(o *StartOptions) { o.Hostname = "Infinity Deck" },
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
