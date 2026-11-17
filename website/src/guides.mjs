// 双语专题共用路由，首页、文档页与 sitemap 使用同一清单。
export const guides = {
  'android-ai-coding': {
    zh: {
      title: '在手机上用 AI 管理 Git 仓库与编写代码',
      intro: 'Infinity Deck 内置了免 Root 的 Alpine Linux 与完整 Git 工具链。不在电脑旁时，你可以直接用手机拉取代码仓库、运行测试脚本，并通过多分支工作树（Worktree）让智能体并行处理不同任务。',
      steps: [
        '安装应用并配置常用的模型服务（或启用本地模型），在会话设置中将执行环境切换为内置 Linux。',
        '克隆 HTTPS 仓库并绑定到当前会话工作区；如果是私有仓库，可在「钥匙串 › Git」中保存个人访问令牌，或通过内置浏览器完成 GitHub 登录授权。',
        '直接用自然语言说明要修改的功能或需要排查的 Bug。如果手头还有其它延伸任务，可以点击派生「分支会话」，在独立的 Git 工作树中同时推进，避免代码冲突。',
        '打开右侧的 Git 检视器查看文件修改差异（Diff）、分支状态与测试运行结果，确认无误后再提交或推送到远端。',
      ],
      note: '内置 Linux 与 Git 命令均在手机本机直接运行；拉取远端代码仓库和调用云端大模型时需保持网络连接。开启默认命令审批后，每一条修改或执行命令都会先由你确认。',
      alt: 'Infinity Deck 会话中的 Git 分支方案与工作树',
    },
    en: {
      title: 'Edit Git repositories with an AI coding agent on Android',
      intro: 'Infinity Deck is an open-source AI agent for Android 12 and later. The sideloaded app includes Alpine Linux for cloning Git repositories, running commands, and reviewing changes on the phone.',
      steps: [
        'Install the sideloaded APK, configure your model provider and API key, and select the Linux execution environment for the session.',
        'Clone an HTTPS repository and bind it to the session workspace. For a private repository, add a token or sign in to GitHub in the browser under Git in the keychain.',
        'Describe the change and how to test it. For parallel work, use a separate Git worktree for the branch session.',
        'Review diffs, branches, and test results in the Git inspector before committing or pushing.',
      ],
      note: 'Linux commands run on your phone. Remote repositories and cloud models still require a connection to their respective services. In command approval mode, review each command before it runs.',
      alt: 'A Git branch plan and worktrees in an Infinity Deck session',
    },
    image: 'inner-chat.state.branch-plan.webp',
    source: 'workspaces.md',
  },
  'android-ssh-ops': {
    zh: {
      title: '出门在外用手机 SSH 安全排查服务器故障',
      intro: '收到线上告警而身边没有电脑时，Infinity Deck 可以直接从手机发起 SSH 连接。配合内置的 Tailscale 私有网络与逐条命令审批机制，既能快速查清故障原因，又能避免误操作风险。',
      steps: [
        '在应用中添加目标 SSH 主机并核对指纹，从本机钥匙串选择登录密码或 SSH 私钥。如果服务器位于内网，可先一键开启应用内置的 Tailscale 节点。',
        '将告警现象或异常接口告诉智能体，让它先登录服务器检查服务状态、配置文件和近期日志。',
        '在命令审批模式下，智能体拟运行的每条检查命令都会先呈现在卡片上，你可以看清具体命令与参数后再点击「批准当前」。',
        '定位到根因后，批准修复命令并让智能体验证接口恢复情况，最后自动整理出一份包含故障原因、处理步骤与验证结果的排障记录。',
      ],
      note: 'SSH 命令在远程服务器上按当前登录账户权限执行。所有主机密码与私钥均由手机本机钥匙串加密保管，不会发送给大模型。',
      alt: 'Infinity Deck 外屏显示的 SSH 排障报告',
    },
    en: {
      title: 'Troubleshoot servers over SSH with an Android AI agent',
      intro: 'Infinity Deck connects to SSH hosts from Android. In command approval mode, you can inspect each proposed command before authorizing it. For a host on your tailnet, you can connect through the app’s built-in Tailscale integration.',
      steps: [
        'Add the SSH host, verify its fingerprint, and choose a password or an SSH key from the keychain. Connect to Tailscale first if the host is on your tailnet.',
        'Give the agent the affected site, error code, and time window. Ask it to start with read-only checks.',
        'Following the homepage’s 502 demo, review checks for reverse-proxy logs and upstream service status. Use the output to locate the failure.',
        'Confirm the scope before approving a repair, verify the site response, and ask the agent to summarize the results and follow-up items.',
      ],
      note: 'SSH commands run on the target server and require valid credentials and network access. Adapt the steps in the 502 demo to your service configuration.',
      alt: 'An SSH incident report on the Infinity Deck cover screen',
    },
    image: 'outer-chat.state.ops-report.webp',
    source: 'execution-environments.md',
  },
};

export const guidePaths = Object.keys(guides).map(slug => `/guides/${slug}/`);
