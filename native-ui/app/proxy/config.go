package main

import (
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"unicode"

	"github.com/metacubex/mihomo/adapter/outbound"
	"github.com/metacubex/mihomo/common/convert"
	"github.com/metacubex/mihomo/common/structure"
	mihomo "github.com/metacubex/mihomo/constant"
	"go.yaml.in/yaml/v3"
	"golang.org/x/net/idna"
)

const maxNodes = 512

type configParams struct {
	ProxyUser     string   `json:"proxyUser"`
	ProxyPassword string   `json:"proxyPassword"`
	DNSServers    []string `json:"dnsServers"`
	Mode          string   `json:"mode"`
	ActiveNode    string   `json:"activeNode"`
	FallbackOrder []string `json:"fallbackOrder"`
	EnabledNodes  []string `json:"enabledNodes"`
	YAML          string   `json:"yaml"`
}

type nodeInfo struct {
	Name   string `json:"name"`
	Type   string `json:"type"`
	Server string `json:"server"`
	Port   int    `json:"port"`
}

type parsedNode struct {
	nodeInfo
	options map[string]any
}

type outboundNode struct {
	nodeInfo
	adapter mihomo.ProxyAdapter
}

func yamlProxies(source string) ([]map[string]any, error) {
	if strings.TrimSpace(source) == "" {
		return nil, errors.New("代理配置为空")
	}
	decoder := yaml.NewDecoder(strings.NewReader(source))
	var doc yaml.Node
	if decoder.Decode(&doc) != nil || len(doc.Content) != 1 || doc.Content[0].Kind != yaml.MappingNode {
		return nil, errors.New("代理配置格式无效")
	}
	var extra yaml.Node
	if decoder.Decode(&extra) != io.EOF {
		return nil, errors.New("代理配置仅支持单个文档")
	}
	var entries []map[string]any
	found := false
	root := doc.Content[0]
	for i := 0; i < len(root.Content); i += 2 {
		if root.Content[i].Value != "proxies" {
			continue
		}
		value := root.Content[i+1]
		if found || value.Kind != yaml.SequenceNode || len(value.Content) > maxNodes || value.Decode(&entries) != nil {
			return nil, errors.New("节点列表格式无效或超出上限")
		}
		found = true
	}
	if !found {
		return nil, errors.New("缺少节点列表")
	}
	return entries, nil
}

func normalizedHost(host string) string {
	if ip, err := netip.ParseAddr(host); err == nil {
		return ip.Unmap().String()
	}
	host = strings.TrimSuffix(host, ".")
	ascii, err := idna.Lookup.ToASCII(host)
	if err != nil || ascii == "" || len(ascii) > 253 {
		return ""
	}
	for _, label := range strings.Split(ascii, ".") {
		if len(label) == 0 || len(label) > 63 {
			return ""
		}
		for _, c := range label {
			if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_') {
				return ""
			}
		}
	}
	return ascii
}

func parseNode(options map[string]any) (parsedNode, error) {
	name, _ := options["name"].(string)
	kind, _ := options["type"].(string)
	server, _ := options["server"].(string)
	name = strings.TrimSpace(name)
	kind = strings.ToLower(strings.TrimSpace(kind))
	server = normalizedHost(strings.TrimSpace(server))
	var port int
	switch raw := options["port"].(type) {
	case int:
		port = raw
	case string:
		port, _ = strconv.Atoi(raw)
	}
	if name == "" || len(name) > 256 || strings.ContainsFunc(name, unicode.IsControl) || server == "" || port < 1 || port > 65535 {
		return parsedNode{}, errors.New("名称、地址或端口无效")
	}
	options["name"], options["type"], options["server"], options["port"] = name, kind, server, port
	return parsedNode{nodeInfo: nodeInfo{Name: name, Type: kind, Server: server, Port: port}, options: options}, nil
}

var proxyDecoder = structure.NewDecoder(structure.Option{
	TagName: "proxy", WeaklyTypedInput: true, KeyReplacer: structure.DefaultKeyReplacer,
})

func decodeAdapter[T any, A mihomo.ProxyAdapter](mapping map[string]any, constructor func(T) (A, error)) (mihomo.ProxyAdapter, error) {
	var option T
	if err := proxyDecoder.Decode(mapping, &option); err != nil {
		return nil, err
	}
	node, err := constructor(option)
	if err != nil {
		return nil, err
	}
	return node, nil
}

// 仅注册用户态出站协议，避免引用会创建虚拟网卡的构造器。
func newOutbound(kind string, options map[string]any) (mihomo.ProxyAdapter, error) {
	switch kind {
	case "http":
		return decodeAdapter(options, outbound.NewHttp)
	case "socks5":
		return decodeAdapter(options, outbound.NewSocks5)
	case "ss":
		return decodeAdapter(options, outbound.NewShadowSocks)
	case "ssr":
		return decodeAdapter(options, outbound.NewShadowSocksR)
	case "vmess":
		return decodeAdapter(options, outbound.NewVmess)
	case "vless":
		return decodeAdapter(options, outbound.NewVless)
	case "trojan":
		return decodeAdapter(options, outbound.NewTrojan)
	case "hysteria":
		return decodeAdapter(options, outbound.NewHysteria)
	case "hysteria2":
		return decodeAdapter(options, outbound.NewHysteria2)
	case "tuic":
		return decodeAdapter(options, outbound.NewTuic)
	case "anytls":
		return decodeAdapter(options, outbound.NewAnyTLS)
	case "snell":
		return decodeAdapter(options, outbound.NewSnell)
	case "shadowquic":
		return decodeAdapter(options, outbound.NewShadowQuic)
	case "mieru":
		return decodeAdapter(options, outbound.NewMieru)
	case "ssh":
		return decodeAdapter(options, outbound.NewSsh)
	default:
		return nil, errUnsupportedProtocol
	}
}

var errUnsupportedProtocol = errors.New("不支持此出站协议")

// 节点的证书和密钥可内联，解析配置时禁止通过字段读取本地路径。
func safeOptions(options map[string]any, kind string) bool {
	var cert, key string
	for field, value := range options {
		switch strings.ReplaceAll(field, "_", "-") {
		case "certificate", "private-key":
			s, ok := value.(string)
			if value != nil && !ok {
				return false
			}
			if strings.ReplaceAll(field, "_", "-") == "certificate" {
				cert = s
			} else {
				key = s
			}
		case "dialer-proxy", "interface-name", "routing-mark":
			if value != nil && value != "" && value != 0 {
				return false
			}
		}
		if nested, ok := value.(map[string]any); ok && !safeOptions(nested, kind) {
			return false
		}
	}
	if kind == "ssh" {
		return cert == "" && (key == "" || strings.Contains(key, "PRIVATE KEY"))
	}
	if key != "" {
		_, err := tls.X509KeyPair([]byte(cert), []byte(key))
		return err == nil
	}
	return cert == "" || x509.NewCertPool().AppendCertsFromPEM([]byte(cert))
}

func (n parsedNode) build() (node outboundNode, err error) {
	defer func() {
		if recover() != nil {
			node = outboundNode{}
			err = errors.New("出站节点参数无效")
		}
	}()
	if !safeOptions(n.options, n.Type) {
		return node, errors.New("节点使用了无效或不支持的参数")
	}
	a, err := newOutbound(n.Type, n.options)
	if err != nil {
		if errors.Is(err, errUnsupportedProtocol) {
			return node, err
		}
		return node, errors.New("出站节点参数无效")
	}
	if raw, ok := n.options["smux"].(map[string]any); ok {
		var mux outbound.SingMuxOption
		err := proxyDecoder.Decode(raw, &mux)
		if err != nil {
			a.Close()
			return node, errors.New("节点复用参数无效")
		}
		if mux.Enabled {
			wrapped, err := outbound.NewSingMux(mux, a.(outbound.ProxyAdapter))
			if err != nil {
				a.Close()
				return node, errors.New("节点复用参数无效")
			}
			a = wrapped
		}
	}
	return outboundNode{nodeInfo: n.nodeInfo, adapter: a}, nil
}

func compileNodes(source string) (nodes []outboundNode, err error) {
	entries, err := yamlProxies(source)
	if err != nil {
		return nil, err
	}
	nodes = make([]outboundNode, 0, len(entries))
	defer func() {
		if err != nil {
			closeNodes(nodes)
			nodes = nil
		}
	}()
	seen := make(map[string]bool, len(entries))
	for index, entry := range entries {
		node, err := parseNode(entry)
		if err != nil {
			return nodes, fmt.Errorf("第 %d 个节点：%w", index+1, err)
		}
		if seen[node.Name] {
			return nodes, fmt.Errorf("第 %d 个节点：节点名称重复", index+1)
		}
		seen[node.Name] = true
		adapter, err := node.build()
		if err != nil {
			return nodes, fmt.Errorf("第 %d 个节点：%w", index+1, err)
		}
		nodes = append(nodes, adapter)
	}
	return nodes, nil
}

func closeNodes(nodes []outboundNode) {
	for _, node := range nodes {
		node.adapter.Close()
	}
}

func shareEntry(line string) (entry map[string]any) {
	defer func() {
		if recover() != nil {
			entry = nil
		}
	}()
	scheme, _, _ := strings.Cut(line, "://")
	switch strings.ToLower(scheme) {
	case "http", "https", "socks", "socks5", "socks5h":
		link, err := url.Parse(line)
		if err != nil || link.Hostname() == "" {
			return nil
		}
		protocol := "socks5"
		port := link.Port()
		switch link.Scheme {
		case "http", "https":
			protocol = "http"
			if port == "" {
				port = "80"
				if link.Scheme == "https" {
					port = "443"
				}
			}
		default:
			if port == "" {
				port = "1080"
			}
		}
		entry = map[string]any{"name": link.Fragment, "type": protocol, "server": link.Hostname(), "port": port}
		if link.User != nil {
			username := link.User.Username()
			password, hasPassword := link.User.Password()
			if !hasPassword {
				if decoded, err := convert.TryDecodeBase64(username); err == nil {
					if user, pass, ok := strings.Cut(string(decoded), ":"); ok {
						username, password = user, pass
					}
				}
			}
			entry["username"], entry["password"] = username, password
		}
		if link.Scheme == "https" {
			entry["tls"] = true
		}
		return entry
	default:
		proxies, err := convert.ConvertsV2Ray([]byte(line))
		if err == nil && len(proxies) == 1 {
			return proxies[0]
		}
		return nil
	}
}

func importEntries(content string) ([]map[string]any, error) {
	if entries, err := yamlProxies(content); err == nil {
		return entries, nil
	}
	content = strings.TrimPrefix(strings.TrimSpace(content), "\ufeff")
	if !strings.Contains(content, "://") {
		compact := strings.Join(strings.Fields(content), "")
		if decoded, err := convert.TryDecodeBase64(compact); err == nil {
			content = strings.TrimPrefix(string(decoded), "\ufeff")
			if entries, err := yamlProxies(content); err == nil {
				return entries, nil
			}
		}
	}
	var entries []map[string]any
	for line := range strings.SplitSeq(content, "\n") {
		line = strings.TrimSpace(line)
		if entry := shareEntry(line); entry != nil {
			entries = append(entries, entry)
			if len(entries) > maxNodes {
				return nil, errors.New("导入节点数超出上限")
			}
		}
	}
	return entries, nil
}

func parseImport(content string) ([]nodeInfo, string, error) {
	entries, err := importEntries(content)
	if err != nil {
		return nil, "", err
	}
	seen := make(map[string]bool)
	names := make(map[string]bool)
	infos := make([]nodeInfo, 0, len(entries))
	options := make([]map[string]any, 0, len(entries))
	for _, entry := range entries {
		base, _ := entry["name"].(string)
		base = strings.TrimSpace(base)
		if base == "" {
			base = net.JoinHostPort(fmt.Sprint(entry["server"]), fmt.Sprint(entry["port"]))
		}
		entry["name"] = base
		node, err := parseNode(entry)
		if err != nil {
			continue
		}
		delete(entry, "name")
		key, err := json.Marshal(entry)
		if err != nil || seen[string(key)] {
			continue
		}
		for i := 2; names[node.Name]; i++ {
			node.Name = fmt.Sprintf("%s (%d)", base, i)
		}
		if len(node.Name) > 256 {
			continue
		}
		entry["name"] = node.Name
		built, err := node.build()
		if err != nil {
			continue
		}
		built.adapter.Close()
		names[node.Name] = true
		seen[string(key)] = true
		infos = append(infos, node.nodeInfo)
		options = append(options, entry)
	}
	if len(infos) == 0 {
		return nil, "", errors.New("未找到可用的代理节点")
	}
	out, err := yaml.Marshal(map[string]any{"proxies": options})
	if err != nil {
		return nil, "", errors.New("无法生成节点配置")
	}
	return infos, string(out), nil
}
