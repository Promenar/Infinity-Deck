package main

import (
	"bufio"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"

	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/miekg/dns"
)

const testProxyPassword = "test-pass-fixture-01"

var testAuthorization = "Basic " + base64.StdEncoding.EncodeToString([]byte("test-user:"+testProxyPassword))

type testControl struct {
	in  *io.PipeWriter
	out *json.Decoder
}

func openControl(t *testing.T) testControl {
	t.Helper()
	r, in := io.Pipe()
	out, w := io.Pipe()
	done := make(chan struct{})
	go func() {
		defer close(done)
		defer w.Close()
		defer r.Close()
		runControl(r, w)
	}()
	t.Cleanup(func() {
		in.Close()
		out.Close()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Error("控制进程未退出")
		}
	})
	return testControl{in, json.NewDecoder(out)}
}

func (c testControl) call(t *testing.T, id any, method string, params any, into any) []json.RawMessage {
	t.Helper()
	err := json.NewEncoder(c.in).Encode(map[string]any{"id": id, "method": method, "params": params})
	if err != nil {
		t.Fatal(err)
	}
	var events []json.RawMessage
	for {
		var reply struct {
			ID     json.RawMessage
			Event  string
			State  json.RawMessage
			Result json.RawMessage
			Error  json.RawMessage
		}
		if err := c.out.Decode(&reply); err != nil {
			t.Fatal(err)
		}
		if reply.Event != "" {
			if reply.Event != "state" || len(reply.State) == 0 {
				t.Fatalf("事件异常 %+v", reply)
			}
			events = append(events, reply.State)
			continue
		}
		want, _ := json.Marshal(id)
		if string(reply.ID) != string(want) || len(reply.Error) > 0 {
			t.Fatalf("控制响应异常 id=%s 错误=%s", reply.ID, reply.Error)
		}
		if into != nil {
			if err := json.Unmarshal(reply.Result, into); err != nil {
				t.Fatal(err)
			}
		}
		return events
	}
}

func mockOutbound(t *testing.T, marker string, fail bool, attempts *atomic.Int32) *httptest.Server {
	t.Helper()
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		attempts.Add(1)
		t.Logf("本地出站模拟节点 %q 收到 %s %s", marker, r.Method, r.Host)
		if r.Method != "CONNECT" || r.Host != "203.0.113.7:443" && r.Host != "public.invalid:80" && r.Host != "public.invalid:443" {
			t.Errorf("出站目标异常 %s %s", r.Method, r.Host)
		}
		if fail {
			w.Header().Set("Proxy-Authenticate", "Basic realm=\"upstream-test-secret\"")
			w.WriteHeader(http.StatusProxyAuthRequired)
			return
		}
		c, rw, err := http.NewResponseController(w).Hijack()
		if err != nil {
			t.Error(err)
			return
		}
		defer c.Close()
		fmt.Fprint(rw, "HTTP/1.1 200 OK\r\n\r\n")
		rw.Flush()
		buf, err := rw.Reader.Peek(4)
		if err != nil {
			t.Logf("模拟节点未读到隧道数据：%v", err)
			return
		}
		if string(buf) == "GET " {
			req, err := http.ReadRequest(rw.Reader)
			if err != nil {
				t.Error(err)
				return
			}
			if req.Header.Get("Proxy-Authorization") != "" {
				t.Error("向目标转发了入口认证")
			}
			if req.URL.Path == "/probe" {
				fmt.Fprint(rw, "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")
				rw.Flush()
				return
			}
			fmt.Fprintf(rw, "HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s", len(marker), marker)
			rw.Flush()
			return
		}
		for {
			if _, err := io.CopyN(io.Discard, rw.Reader, 4); err != nil {
				return
			}
			if _, err := io.WriteString(c, marker); err != nil {
				return
			}
		}
	}))
	t.Cleanup(s.Close)
	return s
}

func nodeYAML(name string, s *httptest.Server) string {
	_, port, _ := net.SplitHostPort(s.Listener.Addr().String())
	return fmt.Sprintf("  - {name: %s, type: http, server: 127.0.0.1, port: %s}\n", name, port)
}

func rawClient(t *testing.T, port int) net.Conn {
	t.Helper()
	c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	c.SetDeadline(time.Now().Add(8 * time.Second))
	t.Cleanup(func() { c.Close() })
	return c
}

func connectClient(t *testing.T, port int, target, auth string) (net.Conn, *bufio.Reader, *http.Response) {
	t.Helper()
	c := rawClient(t, port)
	fmt.Fprintf(c, "CONNECT %s HTTP/1.1\r\nHost: %s\r\nProxy-Authorization: %s\r\n\r\n", target, target, auth)
	br := bufio.NewReader(c)
	resp, err := http.ReadResponse(br, &http.Request{Method: "CONNECT"})
	if err != nil {
		t.Fatal(err)
	}
	return c, br, resp
}

func assertTunnel(t *testing.T, c net.Conn, r io.Reader, want string) {
	t.Helper()
	if _, err := io.WriteString(c, "ping"); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len(want))
	if _, err := io.ReadFull(r, got); err != nil || string(got) != want {
		t.Fatalf("隧道回显 %q，错误 %v，预期 %q", got, err, want)
	}
}

func TestSharedPortFallbackAndReload(t *testing.T) {
	var attempts, oldUses, newUses atomic.Int32
	bad := mockOutbound(t, "", true, &attempts)
	old := mockOutbound(t, "old", false, &oldUses)
	fresh := mockOutbound(t, "new", false, &newUses)
	c := openControl(t)
	params := map[string]any{"proxyUser": "test-user", "proxyPassword": testProxyPassword, "mode": "fallback", "activeNode": "bad",
		"fallbackOrder": []string{"bad", "old", "new"}, "dnsServers": []string{mockDNS(t, "203.0.113.7")}, "yaml": "proxies:\n" + nodeYAML("bad", bad) + nodeYAML("old", old) + nodeYAML("new", fresh)}
	var result struct {
		ProxyPort  int
		ActiveNode string
		NodeCount  int
	}
	if events := c.call(t, json.Number("9007199254740993"), "start", params, &result); len(events) == 0 || result.ProxyPort == 0 || result.NodeCount != 3 || result.ActiveNode != "bad" {
		t.Fatalf("启动结果异常 %+v，事件数 %d", result, len(events))
	}
	port := result.ProxyPort
	_, _, denied := connectClient(t, port, "203.0.113.7:443", "Basic ZmFpbDpmYWls")
	if denied.StatusCode != 407 || denied.Header.Get("Proxy-Authenticate") != "Basic realm=\"infinity-proxy-test-user\"" {
		t.Fatalf("认证挑战异常 %d %s", denied.StatusCode, denied.Header.Get("Proxy-Authenticate"))
	}
	denied.Body.Close()
	tunnel, br, resp := connectClient(t, port, "203.0.113.7:443", testAuthorization)
	if resp.StatusCode != 200 {
		t.Fatalf("CONNECT=%d", resp.StatusCode)
	}
	assertTunnel(t, tunnel, br, "old")
	socks := rawClient(t, port)
	socks.Write([]byte{5, 2, 0, 2})
	buf := make([]byte, 2)
	if _, err := io.ReadFull(socks, buf); err != nil || string(buf) != "\x05\x02" {
		t.Fatalf("SOCKS 方法 %v %v", buf, err)
	}
	authPacket := append([]byte{1, byte(len("test-user"))}, "test-user"...)
	authPacket = append(authPacket, byte(len(testProxyPassword)))
	socks.Write(append(authPacket, testProxyPassword...))
	if _, err := io.ReadFull(socks, buf); err != nil || string(buf) != "\x01\x00" {
		t.Fatalf("SOCKS 认证 %v %v", buf, err)
	}
	socks.Write(append([]byte("\x05\x01\x00\x03\x0epublic.invalid"), 1, 187))
	buf = make([]byte, 10)
	if _, err := io.ReadFull(socks, buf); err != nil || buf[1] != 0 {
		t.Fatalf("SOCKS 连接 %v %v", buf, err)
	}
	assertTunnel(t, socks, socks, "old")

	client := rawClient(t, port)
	fmt.Fprintf(client, "GET http://public.invalid/ HTTP/1.1\r\nHost: public.invalid\r\nProxy-Authorization: %s\r\n\r\n", testAuthorization)
	httpResp, err := http.ReadResponse(bufio.NewReader(client), &http.Request{Method: "GET"})
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(httpResp.Body)
	httpResp.Body.Close()
	if httpResp.StatusCode != 200 || string(body) != "old" || attempts.Load() != 1 {
		t.Fatalf("HTTP 转发 %d %q，失败节点请求次数 %d", httpResp.StatusCode, body, attempts.Load())
	}

	var state struct {
		Running                    bool
		ActiveNode, FallbackReason string
		HealthyNodeCount           int
	}
	events := c.call(t, "status", "status", nil, &state)
	if len(events) == 0 || !state.Running || state.ActiveNode != "old" || state.FallbackReason == "" || state.HealthyNodeCount != 2 {
		t.Fatalf("降级状态 %+v，事件数 %d", state, len(events))
	}
	params["yaml"] = "proxies:\n" + nodeYAML("new", fresh)
	params["activeNode"] = "new"
	params["fallbackOrder"] = []string{"new"}
	params["proxyPassword"] = "ignored-reload-password"
	c.call(t, "reload", "reload", params, &result)
	if result.ProxyPort != port || result.ActiveNode != "new" || result.NodeCount != 1 {
		t.Fatalf("重载结果 %+v", result)
	}
	if _, err := br.ReadByte(); err != io.EOF {
		t.Fatalf("配置重载后旧隧道仍可读：%v", err)
	}
	reloaded, reloadedRead, response := connectClient(t, port, "203.0.113.7:443", testAuthorization)
	if response.StatusCode != 200 {
		t.Fatalf("重载后 CONNECT=%d", response.StatusCode)
	}
	assertTunnel(t, reloaded, reloadedRead, "new")
	c.call(t, "bye", "shutdown", nil, nil)
	if _, err := io.ReadFull(reloaded, make([]byte, 1)); err != io.EOF {
		t.Fatalf("退出后隧道未关闭 %v", err)
	}
}

func TestImportAndValidation(t *testing.T) {
	c := openControl(t)
	content := "socks5://demo:sample@proxy.example:1080#One\n" +
		"SOCKS5://demo:sample@PROXY.EXAMPLE:1080#Same\n" +
		"https://proxy.example#One"
	wrapped := base64.RawURLEncoding.EncodeToString([]byte(content))
	var imported struct {
		Nodes []struct {
			Name, Type, Server string
			Port               int
		}
		YAML        string
		NodeConfigs []map[string]any
	}
	c.call(t, 2, "parse_import", map[string]string{"content": wrapped}, &imported)
	if len(imported.NodeConfigs) != 2 || imported.NodeConfigs[0]["type"] != "socks5" || len(imported.Nodes) != 2 || imported.Nodes[0].Server != "proxy.example" ||
		imported.Nodes[1].Port != 443 || imported.Nodes[0].Name == imported.Nodes[1].Name || !strings.HasPrefix(imported.YAML, "proxies:") {
		t.Fatalf("导入结构异常，节点数 %d", len(imported.Nodes))
	}
	var validated struct {
		Valid       bool
		Error       string
		Nodes       []map[string]any
		NodeConfigs []map[string]any
	}
	c.call(t, 3, "validate_yaml", map[string]string{"yaml": imported.YAML + "rules: [MATCH,ignored]\n"}, &validated)
	if !validated.Valid || len(validated.Nodes) != 2 || len(validated.NodeConfigs) != 2 {
		t.Fatalf("导入结果未通过校验，错误 %q", validated.Error)
	}
	c.call(t, 4, "validate_yaml", map[string]string{"yaml": "proxies: [{name: test, type: http, server: proxy.example, port: sample-secret}]"}, &validated)
	if validated.Valid || validated.Error == "" || strings.Contains(validated.Error, "sample-secret") {
		t.Fatal("配置异常未被脱敏")
	}
}

func TestMainstreamShareLinks(t *testing.T) {
	id := "00000000-0000-4000-8000-000000000001"
	ss := base64.RawURLEncoding.EncodeToString([]byte("aes-128-gcm:fixture"))
	vmess := base64.StdEncoding.EncodeToString([]byte(`{"v":"2","ps":"vmess-fixture","add":"proxy.example","port":"443","id":"` + id + `","aid":"0","net":"tcp"}`))
	links := []struct{ link, kind string }{
		{"vless://" + id + "@proxy.example:443?encryption=none&type=tcp#vless", "vless"},
		{"vmess://" + vmess, "vmess"},
		{"ss://" + ss + "@proxy.example:443#ss", "ss"},
		{"trojan://fixture@proxy.example:443#trojan", "trojan"},
		{"hysteria2://fixture@proxy.example:443#hy2", "hysteria2"},
		{"hy2://fixture@proxy.example:443#hy2-alt", "hysteria2"},
		{"tuic://" + id + ":fixture@proxy.example:443#tuic", "tuic"},
		{"anytls://fixture@proxy.example:443#anytls", "anytls"},
	}
	for _, tc := range links {
		t.Run(tc.kind, func(t *testing.T) {
			nodes, yaml, err := parseImport(tc.link)
			if err != nil || len(nodes) != 1 || nodes[0].Type != tc.kind {
				t.Fatalf("协议 %s 导入失败：%v", tc.kind, err)
			}
			parsed, err := compileNodes(yaml)
			if err != nil || len(parsed) != 1 {
				t.Fatalf("协议 %s 导入的配置无法复用：%v", tc.kind, err)
			}
			closeNodes(parsed)
		})
	}
}

func mockDNS(t *testing.T, ipv4 string) string {
	t.Helper()
	conn, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := &dns.Server{PacketConn: conn, Handler: dns.HandlerFunc(func(w dns.ResponseWriter, q *dns.Msg) {
		r := new(dns.Msg).SetReply(q)
		if q.Question[0].Qtype == dns.TypeA {
			r.Answer = []dns.RR{&dns.A{Hdr: dns.RR_Header{Name: q.Question[0].Name, Rrtype: dns.TypeA, Class: dns.ClassINET}, A: net.ParseIP(ipv4)}}
		}
		w.WriteMsg(r)
	})}
	go s.ActivateAndServe()
	t.Cleanup(func() { s.Shutdown(); conn.Close() })
	return conn.LocalAddr().String()
}

func TestPublicTargetRejectsPrivateReclassification(t *testing.T) {
	var destinationCalls, proxyCalls atomic.Int32
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		destinationCalls.Add(1)
		w.WriteHeader(204)
	}))
	defer origin.Close()
	node := mockOutbound(t, "test", false, &proxyCalls)
	dns := mockDNS(t, "127.0.0.1")
	control := openControl(t)
	var result configResult
	control.call(t, 1, "start", configParams{
		ProxyUser: "test-user", ProxyPassword: testProxyPassword,
		DNSServers: []string{dns}, YAML: "proxies:\n" + nodeYAML("proxy", node),
	}, &result)
	_, port, _ := net.SplitHostPort(origin.Listener.Addr().String())
	target := net.JoinHostPort("private.example", port)
	for _, method := range []string{"GET", "CONNECT"} {
		conn := rawClient(t, result.ProxyPort)
		uri := target
		if method == "GET" {
			uri = "http://" + target + "/"
		}
		fmt.Fprintf(conn, "%s %s HTTP/1.1\r\nHost: %s\r\nProxy-Authorization: %s\r\nX-Infinity-Public-Target: 1\r\n\r\n", method, uri, target, testAuthorization)
		response, err := http.ReadResponse(bufio.NewReader(conn), &http.Request{Method: method})
		if err != nil || response.StatusCode != 502 {
			t.Fatalf("公开范围的请求应拒绝新的私网解析结果，方法=%s 错误=%v", method, err)
		}
		response.Body.Close()
		conn.Close()
	}
	if destinationCalls.Load() != 0 || proxyCalls.Load() != 0 {
		t.Fatal("公开范围请求不能拨号私网目标或远端节点")
	}
}

func TestRejectShortCredentials(t *testing.T) {
	if _, err := newCredentials("test-user", "15-byte-fixture"); err == nil {
		t.Fatal("不足16字节的代理口令通过校验")
	}
	if _, err := newCredentials("test-user", testProxyPassword); err != nil {
		t.Fatal("有效代理口令被拒绝")
	}
}

func TestDNSOverridesAndPrivateBypass(t *testing.T) {
	var privateUses, proxyUses atomic.Int32
	private := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		privateUses.Add(1)
		w.WriteHeader(204)
	}))
	t.Cleanup(private.Close)
	directDNS := mockDNS(t, "127.0.0.1")
	publicDNS := mockDNS(t, "203.0.113.7")
	proxy := mockOutbound(t, "proxy", false, &proxyUses)
	c := openControl(t)
	var result struct{ ProxyPort int }
	c.call(t, 1, "start", map[string]any{"proxyUser": "test-user", "proxyPassword": testProxyPassword, "mode": "single",
		"activeNode": "proxy", "dnsServers": []string{directDNS}, "yaml": "proxies:\n" + nodeYAML("proxy", proxy)}, &result)
	_, localPort, _ := net.SplitHostPort(private.Listener.Addr().String())
	get := func(target string, want int) {
		cli := rawClient(t, result.ProxyPort)
		fmt.Fprintf(cli, "GET http://%s/ HTTP/1.1\r\nHost: %s\r\nProxy-Authorization: %s\r\n\r\n", target, target, testAuthorization)
		r, err := http.ReadResponse(bufio.NewReader(cli), &http.Request{Method: "GET"})
		if err != nil {
			t.Fatal(err)
		}
		defer r.Body.Close()
		if r.StatusCode != want {
			t.Fatalf("HTTP %d，预期 %d", r.StatusCode, want)
		}
	}
	get(net.JoinHostPort("private.example", localPort), 204)
	c.call(t, 2, "set_dns", map[string]any{"dnsServers": []string{publicDNS}}, nil)
	get("public.invalid", 200)
	if privateUses.Load() != 1 || proxyUses.Load() != 1 {
		t.Fatal("出站路径异常")
	}
}

func TestOfflineLatency(t *testing.T) {
	var probes atomic.Int32
	proxy := mockOutbound(t, "", false, &probes)
	c := openControl(t)
	c.call(t, "dns", "set_dns", map[string]any{"dnsServers": []string{mockDNS(t, "203.0.113.7")}}, nil)
	var result struct {
		Nodes []struct {
			Name    string
			OK      bool
			DelayMs int
			Error   string
		}
	}
	c.call(t, 1, "test_latency", map[string]any{"nodes": []string{"proxy", "missing"}, "url": "http://public.invalid/probe",
		"timeoutMs": 1000, "yaml": "proxies:\n" + nodeYAML("proxy", proxy)}, &result)
	if len(result.Nodes) != 2 || !result.Nodes[0].OK || result.Nodes[0].DelayMs < 1 ||
		result.Nodes[1].OK || result.Nodes[1].Error == "" || probes.Load() != 1 {
		t.Fatalf("测速结果异常 %+v", result)
	}
}

func TestBypassAddressRanges(t *testing.T) {
	for _, host := range []string{"127.0.0.1", "::1", "::ffff:192.168.1.1", "10.1.2.3", "172.31.2.3", "169.254.1.2", "100.64.0.1", "100.127.255.255", "fc00::1", "fd7a:115c:a1e0::1", "fe80::1%wlan0"} {
		if !bypassIP(netip.MustParseAddr(host)) {
			t.Errorf("未旁路 %s", host)
		}
	}
	for _, host := range []string{"100.63.255.255", "100.128.0.1", "203.0.113.1", "2001:db8::1"} {
		if bypassIP(netip.MustParseAddr(host)) {
			t.Errorf("意外旁路 %s", host)
		}
	}
}

func TestFailedReloadKeepsConfig(t *testing.T) {
	var uses atomic.Int32
	node := mockOutbound(t, "old", false, &uses)
	c := openControl(t)
	var start struct{ ProxyPort int }
	c.call(t, 17, "start", map[string]string{
		"proxyUser": "test-user", "proxyPassword": testProxyPassword, "yaml": "proxies:\n" + nodeYAML("old", node),
	}, &start)
	if err := json.NewEncoder(c.in).Encode(map[string]any{
		"id": 18, "method": "reload", "params": map[string]string{"yaml": "proxies:\n" + nodeYAML("old", node) + "  - {name: invalid, type: tun, server: invalid.example, port: 1, password: fixture-secret}\n"},
	}); err != nil {
		t.Fatal(err)
	}
	var failure response
	if err := c.out.Decode(&failure); err != nil {
		t.Fatal(err)
	}
	if string(failure.ID) != "18" || failure.Result != nil || failure.Error == nil || strings.Contains(failure.Error.Message, "fixture-secret") {
		t.Fatalf("无效配置的控制响应异常 %+v", failure)
	}
	var state proxyState
	c.call(t, 19, "status", nil, &state)
	if state.ActiveNode != "old" || len(state.Nodes) != 1 {
		t.Fatalf("配置重载失败后状态异常 %+v", state)
	}
	conn, reader, resp := connectClient(t, start.ProxyPort, "203.0.113.7:443", testAuthorization)
	if resp.StatusCode != 200 {
		t.Fatalf("重载失败后 CONNECT=%d", resp.StatusCode)
	}
	assertTunnel(t, conn, reader, "old")
}

func TestLatencyCancellationAndConcurrentStatus(t *testing.T) {
	reached := make(chan struct{})
	release := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(reached)
		select {
		case <-r.Context().Done():
		case <-release:
		}
	}))
	defer server.Close()
	defer close(release)
	c := openControl(t)
	c.call(t, "dns", "set_dns", map[string]any{"dnsServers": []string{mockDNS(t, "203.0.113.7")}}, nil)
	enc := json.NewEncoder(c.in)
	if err := enc.Encode(map[string]any{"id": "latency", "method": "test_latency",
		"params": map[string]any{"nodes": []string{"slow"}, "url": "http://public.invalid/probe", "timeoutMs": 30000,
			"yaml": "proxies:\n" + nodeYAML("slow", server)}}); err != nil {
		t.Fatal(err)
	}
	select {
	case <-reached:
	case <-time.After(3 * time.Second):
		t.Fatal("模拟节点未收到测速请求")
	}
	var state proxyState
	c.call(t, "status", "status", nil, &state)
	if state.Running {
		t.Fatal("独立测速不应启动入口")
	}
	if err := enc.Encode(map[string]any{"id": "end", "method": "shutdown"}); err != nil {
		t.Fatal(err)
	}
	seen := map[string]response{}
	for range 2 {
		var line response
		if err := c.out.Decode(&line); err != nil {
			t.Fatal(err)
		}
		seen[string(line.ID)] = line
	}
	reply, _ := json.Marshal(seen[`"latency"`].Result)
	if seen[`"end"`].Result == nil || !strings.Contains(string(reply), "测速已取消") {
		t.Fatalf("并发退出状态异常 %s", reply)
	}
	// 控制退出也必须取消实际出站连接。
	closed := make(chan struct{})
	go func() { server.Close(); close(closed) }()
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Error("退出后测速的出站连接仍未关闭")
	}
}
