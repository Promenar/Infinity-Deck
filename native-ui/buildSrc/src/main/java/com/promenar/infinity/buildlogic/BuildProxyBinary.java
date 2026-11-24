package com.promenar.infinity.buildlogic;

import java.util.List;

/** 将独立 Go 代理进程作为原生可执行文件打入两个发行渠道。 */
public abstract class BuildProxyBinary extends BuildTailnetBinary {
    @Override protected String componentName() { return "内置代理"; }
    @Override protected String executableName() { return "libnxproxy.so"; }
    @Override protected List<String> buildTags() {
        return List.of("no_easytier", "no_zerotier");
    }
    @Override protected String skipHint() {
        return "请安装 Go 1.21 及以上版本，或用 -Pinfinity.goExecutable=<go 路径> 指定。"
                + "本地开发可用 -Pinfinity.proxy=false 跳过。";
    }
}
