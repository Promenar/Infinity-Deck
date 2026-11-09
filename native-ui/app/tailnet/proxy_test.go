package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"golang.org/x/net/proxy"
)

const (
	testUser     = "user-0123456789abcdef"
	testPassword = "pass-0123456789abcdef"
)

// targetRecorder 把所有目标改拨到本机测试服务器，并记录代理请求拨号的原始目标。
type targetRecorder struct {
	mu      sync.Mutex
	targets []string
	backend string
	fail    error
}

func (t *targetRecorder) dial(ctx context.Context, network, address string) (net.Conn, error) {
	t.mu.Lock()
	t.targets = append(t.targets, address)
	fail := t.fail
	t.mu.Unlock()
	if fail != nil {
		return nil, fail
	}
	var d net.Dialer
	return d.DialContext(ctx, "tcp", t.backend)
}

func (t *targetRecorder) last() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	if len(t.targets) == 0 {
		return ""
	}
	return t.targets[len(t.targets)-1]
}

func startEcho(t *testing.T) string {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); io.Copy(c, c) }()
		}
	}()
	return ln.Addr().String()
}

func startProxy(t *testing.T, rec *targetRecorder) string {
	p := &Proxy{Credentials: Credentials{User: testUser, Password: testPassword}, Dial: rec.dial}
	port, err := p.Listen()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(p.Close)
	if host, _, _ := net.SplitHostPort(p.listener.Addr().String()); host != "127.0.0.1" {
		t.Fatalf("代理只能绑定 127.0.0.1，实际 %s", host)
	}
	return fmt.Sprintf("127.0.0.1:%d", port)
}

func TestSOCKS5RequiresCredentials(t *testing.T) {
	rec := &targetRecorder{backend: startEcho(t)}
	addr := startProxy(t, rec)

	// 无认证方法：应回复 0xFF。
	c, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	c.Write([]byte{5, 1, 0})
	reply := make([]byte, 2)
	io.ReadFull(c, reply)
	c.Close()
	if reply[1] != socksNoAcceptable {
		t.Fatalf("无凭据应拒绝，得到 %v", reply)
	}

	wrong, _ := proxy.SOCKS5("tcp", addr, &proxy.Auth{User: testUser, Password: "wrong-password-000000"}, proxy.Direct)
	if _, err := wrong.Dial("tcp", "nas.tail1234.ts.net:80"); err == nil {
		t.Fatal("错误口令应失败")
	}
	if rec.last() != "" {
		t.Fatalf("认证失败时不得拨号，实际拨号 %q", rec.last())
	}

	good, _ := proxy.SOCKS5("tcp", addr, &proxy.Auth{User: testUser, Password: testPassword}, proxy.Direct)
	conn, err := good.Dial("tcp", "nas.tail1234.ts.net:80")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if rec.last() != "nas.tail1234.ts.net:80" {
		t.Fatalf("域名应原样交给分流拨号（远端解析），得到 %q", rec.last())
	}
	conn.Write([]byte("ping"))
	buf := make([]byte, 4)
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.ReadFull(conn, buf); err != nil || string(buf) != "ping" {
		t.Fatalf("隧道回显失败：%v %q", err, buf)
	}
}

func TestSOCKS5ReportsTailnetOffline(t *testing.T) {
	rec := &targetRecorder{backend: startEcho(t), fail: errTailnetNotRunning}
	addr := startProxy(t, rec)
	c, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.Write([]byte{5, 1, socksAuthPassword})
	io.ReadFull(c, make([]byte, 2))
	auth := []byte{1, byte(len(testUser))}
	auth = append(auth, testUser...)
	auth = append(auth, byte(len(testPassword)))
	auth = append(auth, testPassword...)
	c.Write(auth)
	io.ReadFull(c, make([]byte, 2))
	c.Write([]byte{5, 1, 0, socksAtypIPv4, 100, 64, 0, 9, 0, 22})
	reply := make([]byte, 10)
	if _, err := io.ReadFull(c, reply); err != nil {
		t.Fatal(err)
	}
	if reply[1] != socksReplyHostUnr {
		t.Fatalf("未连接应回复主机不可达，得到 %#x", reply[1])
	}
}

func basic(user, password string) string {
	return "Basic " + base64.StdEncoding.EncodeToString([]byte(user+":"+password))
}

func TestHTTPConnectRequiresCredentials(t *testing.T) {
	rec := &targetRecorder{backend: startEcho(t)}
	addr := startProxy(t, rec)

	connect := func(auth string) (*http.Response, net.Conn) {
		c, err := net.Dial("tcp", addr)
		if err != nil {
			t.Fatal(err)
		}
		req := "CONNECT nas:443 HTTP/1.1\r\nHost: nas:443\r\n"
		if auth != "" {
			req += "Proxy-Authorization: " + auth + "\r\n"
		}
		c.Write([]byte(req + "\r\n"))
		resp, err := http.ReadResponse(bufio.NewReader(c), nil)
		if err != nil {
			t.Fatal(err)
		}
		return resp, c
	}

	resp, c := connect("")
	c.Close()
	if resp.StatusCode != http.StatusProxyAuthRequired || !strings.Contains(resp.Header.Get("Proxy-Authenticate"), "Basic") {
		t.Fatalf("无凭据应回复 407 与 Basic 质询，得到 %d %q", resp.StatusCode, resp.Header.Get("Proxy-Authenticate"))
	}
	if want := `Basic realm="infinity-tailnet-` + testUser + `"`; resp.Header.Get("Proxy-Authenticate") != want {
		t.Fatalf("质询 realm 应带本次代理用户名，得到 %q", resp.Header.Get("Proxy-Authenticate"))
	}
	resp, c = connect(basic(testUser, "wrong-password-000000"))
	c.Close()
	if resp.StatusCode != http.StatusProxyAuthRequired {
		t.Fatalf("错误口令应回复 407，得到 %d", resp.StatusCode)
	}
	if rec.last() != "" {
		t.Fatalf("认证失败时不得拨号，实际拨号 %q", rec.last())
	}
	resp, c = connect(basic(testUser, testPassword))
	defer c.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("带凭据的 CONNECT 应成功，得到 %d", resp.StatusCode)
	}
	if rec.last() != "nas:443" {
		t.Fatalf("CONNECT 目标应原样交给分流拨号，得到 %q", rec.last())
	}
	c.Write([]byte("hello"))
	buf := make([]byte, 5)
	c.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.ReadFull(c, buf); err != nil || string(buf) != "hello" {
		t.Fatalf("隧道回显失败：%v %q", err, buf)
	}
}

func TestHTTPForwardStripsProxyHeaders(t *testing.T) {
	var seen http.Header
	var seenHost string
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen, seenHost = r.Header.Clone(), r.Host
		io.WriteString(w, "ok:"+r.URL.Path)
	}))
	defer backend.Close()
	rec := &targetRecorder{backend: strings.TrimPrefix(backend.URL, "http://")}
	addr := startProxy(t, rec)

	proxyURL, _ := url.Parse("http://" + testUser + ":" + testPassword + "@" + addr)
	client := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(proxyURL)}, Timeout: 10 * time.Second}
	request, _ := http.NewRequest("GET", "http://nas:5666/info/refs", nil)
	request.Header.Set(tailnetOriginHostHeader, "ignored.test")
	resp, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 || string(body) != "ok:/info/refs" {
		t.Fatalf("转发失败：%d %q", resp.StatusCode, body)
	}
	if rec.last() != "nas:5666" {
		t.Fatalf("应按请求目标分流拨号，得到 %q", rec.last())
	}
	if seenHost != "nas:5666" {
		t.Fatalf("Host 头应保持原目标，得到 %q", seenHost)
	}
	for _, name := range []string{"Proxy-Authorization", "Proxy-Connection", "X-Forwarded-For", "Forwarded", tailnetOriginHostHeader} {
		if v := seen.Get(name); v != "" {
			t.Fatalf("目标服务器不应收到 %s：%q", name, v)
		}
	}

	noAuth := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(&url.URL{Scheme: "http", Host: addr})}}
	resp, err = noAuth.Get("http://nas:5666/")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusProxyAuthRequired {
		t.Fatalf("无凭据的转发应回复 407，得到 %d", resp.StatusCode)
	}
}

func TestHTTPForwardPreservesPinnedTailnetOrigin(t *testing.T) {
	for _, authority := range []string{"origin.test", "origin.test:8080", "[fd7a:115c:a1e0::2]:8080"} {
		t.Run(authority, func(t *testing.T) {
			var seenHost, seenInternal string
			backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				seenHost, seenInternal = r.Host, r.Header.Get(tailnetOriginHostHeader)
				w.WriteHeader(http.StatusOK)
			}))
			defer backend.Close()
			rec := &targetRecorder{backend: strings.TrimPrefix(backend.URL, "http://")}
			addr := startProxy(t, rec)
			proxyURL, _ := url.Parse("http://" + testUser + ":" + testPassword + "@" + addr)
			transport := &http.Transport{Proxy: http.ProxyURL(proxyURL)}
			defer transport.CloseIdleConnections()
			request, _ := http.NewRequest("GET", "http://100.64.0.9:8080/path", nil)
			request.Header.Set(tailnetOriginHostHeader, authority)
			response, err := (&http.Client{Transport: transport, Timeout: 10 * time.Second}).Do(request)
			if err != nil {
				t.Fatal(err)
			}
			response.Body.Close()
			if rec.last() != "100.64.0.9:8080" || seenHost != authority || seenInternal != "" {
				t.Fatalf("固定 IP、原始 Host 或内部头剥离错误：%q %q %q", rec.last(), seenHost, seenInternal)
			}
		})
	}
}

func TestForwardedOriginHostValidation(t *testing.T) {
	for _, authority := range []string{"", "user@origin.test", "origin.test/path", "origin.test?x=1", "origin.test#frag", "origin.test:0", "origin.test:65536", "origin.test:", "origin.test\r\nX: injected", "[invalid]", "origin.test:80:90", "::1", "fd7a:115c:a1e0::1"} {
		if actual := forwardedOriginHost("100.64.0.9", authority); actual != "" {
			t.Fatalf("无效 authority 应忽略：%q -> %q", authority, actual)
		}
	}
	for _, target := range []string{"192.168.1.2", "127.0.0.1", "example.test"} {
		if actual := forwardedOriginHost(target, "origin.test"); actual != "" {
			t.Fatalf("普通目标不使用内部 Host：%q -> %q", target, actual)
		}
	}
}

func TestHTTPStatusForPolicyErrors(t *testing.T) {
	rec := &targetRecorder{backend: startEcho(t), fail: errTailnetNotRunning}
	addr := startProxy(t, rec)
	proxyURL, _ := url.Parse("http://" + testUser + ":" + testPassword + "@" + addr)
	client := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(proxyURL)}}
	resp, err := client.Get("http://nas/")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusServiceUnavailable || !strings.Contains(string(body), "tailnet_not_running") {
		t.Fatalf("未登录应回复 503 并说明原因，得到 %d %q", resp.StatusCode, body)
	}
}

func TestCredentialsMatch(t *testing.T) {
	c := Credentials{User: testUser, Password: testPassword}
	if !c.Match(testUser, testPassword) || c.Match(testUser, "") || c.Match("", testPassword) || c.Match(testPassword, testUser) {
		t.Fatal("凭据比较结果错误")
	}
}
