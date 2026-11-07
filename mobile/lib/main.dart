import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:webview_flutter/webview_flutter.dart';

const _defaultServiceUrl = kReleaseMode
    ? 'https://127.0.0.1:12400'
    : 'http://127.0.0.1:12400';
const _serviceUrlKey = 'service_url';

/// Parse a service address entered by a user.
///
/// A bare LAN address such as `192.168.1.100:12400` is accepted and treated
/// as HTTP so the mobile client is practical to configure on a phone.
Uri? parseServiceUrl(String value, {bool releaseMode = kReleaseMode}) {
  final input = value.trim();
  if (input.isEmpty) return null;
  final candidate = input.contains('://')
      ? input
      : '${releaseMode ? 'https' : 'http'}://$input';
  final uri = Uri.tryParse(candidate);
  if (uri == null || uri.host.isEmpty) {
    return null;
  }
  if (uri.scheme == 'https') {
    return uri;
  }
  if (uri.scheme == 'http' && !releaseMode) {
    return uri;
  }
  return null;
}

Uri _serviceEndpoint(Uri base, String path) =>
    base.replace(path: path, query: '');

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const XianyuSellerApp());
}

class XianyuSellerApp extends StatelessWidget {
  const XianyuSellerApp({super.key, this.enableWebView = true});

  final bool enableWebView;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '闲鱼卖家运营控制台',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff1677ff)),
        useMaterial3: true,
      ),
      home: MobileHomePage(enableWebView: enableWebView),
    );
  }
}

class MobileHomePage extends StatefulWidget {
  const MobileHomePage({super.key, this.enableWebView = true});

  final bool enableWebView;

  @override
  State<MobileHomePage> createState() => _MobileHomePageState();
}

class _MobileHomePageState extends State<MobileHomePage> {
  final _urlController = TextEditingController();
  bool _showSettings = false;
  bool _checkingService = false;
  bool? _serviceHealthy;
  String? _serviceVersion;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _loadService();
  }

  Future<void> _loadService() async {
    final preferences = await SharedPreferences.getInstance();
    _urlController.text =
        preferences.getString(_serviceUrlKey) ?? _defaultServiceUrl;
    await _checkService();
  }

  Future<void> _checkService() async {
    final uri = parseServiceUrl(_urlController.text);
    if (uri == null) {
      if (mounted) {
        setState(() {
          _serviceHealthy = false;
          _errorMessage = '请输入完整的 HTTP/HTTPS 服务地址';
        });
      }
      return;
    }
    if (mounted) {
      setState(() {
        _checkingService = true;
        _errorMessage = null;
      });
    }

    final client = HttpClient()..connectionTimeout = const Duration(seconds: 5);
    try {
      final request = await client.getUrl(
        _serviceEndpoint(uri, '/api/system/health'),
      );
      request.headers.set(HttpHeaders.acceptHeader, 'application/json');
      final response = await request.close();
      final body = await utf8.decoder.bind(response).join();
      final decoded = jsonDecode(body);
      final data = decoded is Map<String, dynamic> ? decoded['data'] : null;
      final healthy =
          response.statusCode == HttpStatus.ok &&
          decoded is Map<String, dynamic> &&
          (decoded['code'] == 0 || decoded['code'] == 200) &&
          data is Map<String, dynamic> &&
          data['status'] == 'UP';
      if (mounted) {
        setState(() {
          _serviceHealthy = healthy;
          _serviceVersion = healthy ? data['version']?.toString() : null;
          _errorMessage = healthy ? null : '服务已响应，但健康检查未通过';
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _serviceHealthy = false;
          _serviceVersion = null;
          _errorMessage = '无法连接服务，请确认电脑服务已启动并与手机处于同一网络';
        });
      }
    } finally {
      client.close(force: true);
      if (mounted) setState(() => _checkingService = false);
    }
  }

  Future<void> _saveAndCheck() async {
    final uri = parseServiceUrl(_urlController.text);
    if (uri == null) {
      setState(() => _errorMessage = '请输入完整的 HTTP/HTTPS 服务地址');
      return;
    }
    final preferences = await SharedPreferences.getInstance();
    await preferences.setString(_serviceUrlKey, uri.toString());
    if (mounted) setState(() => _showSettings = false);
    await _checkService();
  }

  void _openConsole([String path = '/']) {
    final uri = parseServiceUrl(_urlController.text);
    if (uri == null) {
      setState(() => _errorMessage = '请先配置有效的服务地址');
      return;
    }
    if (!widget.enableWebView) return;
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => ConsolePage(serviceUri: uri, initialPath: path),
      ),
    );
  }

  @override
  void dispose() {
    _urlController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('闲鱼卖家运营控制台'),
        actions: [
          IconButton(
            tooltip: '刷新服务状态',
            onPressed: _checkingService ? null : _checkService,
            icon: const Icon(Icons.refresh),
          ),
          IconButton(
            tooltip: '服务设置',
            onPressed: () => setState(() => _showSettings = !_showSettings),
            icon: const Icon(Icons.settings_outlined),
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _checkService,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
          children: [
            if (_showSettings) ...[
              _settingsPanel(),
              const SizedBox(height: 16),
            ],
            _serviceCard(),
            const SizedBox(height: 20),
            Text(
              '移动工作台',
              style: Theme.of(context).textTheme.headlineSmall
                  ?.copyWith(fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 6),
            Text(
              '快速处理高频任务，复杂配置可进入完整控制台。',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 14),
            _todoCard(),
            const SizedBox(height: 14),
            LayoutBuilder(
              builder: (context, constraints) {
                final width = (constraints.maxWidth - 12) / 2;
                return Wrap(
                  spacing: 12,
                  runSpacing: 12,
                  children: [
                    _shortcutCard(
                      width,
                      Icons.local_shipping_outlined,
                      '待发货',
                      '处理订单发货',
                      '/pending-orders',
                    ),
                    _shortcutCard(
                      width,
                      Icons.chat_bubble_outline,
                      '待回复',
                      '查看买家消息',
                      '/messages',
                    ),
                    _shortcutCard(
                      width,
                      Icons.fact_check_outlined,
                      '待复核',
                      '核对自动发货',
                      '/orders',
                    ),
                    _shortcutCard(
                      width,
                      Icons.star_border,
                      '待评价',
                      '确认评价记录',
                      '/reviews',
                    ),
                  ],
                );
              },
            ),
            const SizedBox(height: 20),
            FilledButton.icon(
              onPressed: widget.enableWebView ? _openConsole : null,
              icon: const Icon(Icons.dashboard_outlined),
              label: const Text('打开完整控制台'),
              style: FilledButton.styleFrom(
                minimumSize: const Size.fromHeight(52),
              ),
            ),
            if (_errorMessage != null) ...[
              const SizedBox(height: 14),
              _errorPanel(),
            ],
          ],
        ),
      ),
    );
  }

  Widget _serviceCard() {
    final colorScheme = Theme.of(context).colorScheme;
    final statusColor = _serviceHealthy == true
        ? Colors.green.shade700
        : _serviceHealthy == false
        ? colorScheme.error
        : colorScheme.onSurfaceVariant;
    final statusText = _checkingService
        ? '正在检测'
        : _serviceHealthy == true
        ? '服务在线'
        : _serviceHealthy == false
        ? '未连接'
        : '等待检测';
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Row(
          children: [
            CircleAvatar(
              radius: 24,
              backgroundColor: statusColor.withAlpha(30),
              child: Icon(Icons.cloud_outlined, color: statusColor),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Text(
                        '电脑服务',
                        style: Theme.of(context).textTheme.titleMedium
                            ?.copyWith(fontWeight: FontWeight.w700),
                      ),
                      const SizedBox(width: 8),
                      Text(
                        statusText,
                        style: TextStyle(
                          color: statusColor,
                          fontSize: 12,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Text(
                    _urlController.text,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  if (_serviceVersion != null)
                    Text(
                      '版本 $_serviceVersion',
                      style: Theme.of(context).textTheme.bodySmall
                          ?.copyWith(color: colorScheme.onSurfaceVariant),
                    ),
                ],
              ),
            ),
            IconButton(
              tooltip: '编辑服务地址',
              onPressed: () => setState(() => _showSettings = true),
              icon: const Icon(Icons.edit_outlined),
            ),
          ],
        ),
      ),
    );
  }

  Widget _todoCard() {
    final colorScheme = Theme.of(context).colorScheme;
    return Card(
      color: colorScheme.primaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Row(
          children: [
            Icon(
              Icons.pending_actions,
              size: 30,
              color: colorScheme.onPrimaryContainer,
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '待办中心',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      color: colorScheme.onPrimaryContainer,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '登录态由完整控制台安全维护，打开后可查看实时待办数量。',
                    style: Theme.of(context).textTheme.bodySmall
                        ?.copyWith(color: colorScheme.onPrimaryContainer),
                  ),
                ],
              ),
            ),
            IconButton(
              tooltip: '打开待办中心',
              onPressed: widget.enableWebView
                  ? () => _openConsole('/todo')
                  : null,
              color: colorScheme.onPrimaryContainer,
              icon: const Icon(Icons.arrow_forward),
            ),
          ],
        ),
      ),
    );
  }

  Widget _shortcutCard(
    double width,
    IconData icon,
    String title,
    String subtitle,
    String path,
  ) {
    final colorScheme = Theme.of(context).colorScheme;
    return SizedBox(
      width: width,
      child: Card(
        child: InkWell(
          onTap: widget.enableWebView ? () => _openConsole(path) : null,
          borderRadius: const BorderRadius.all(Radius.circular(20)),
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                CircleAvatar(
                  backgroundColor: colorScheme.secondaryContainer,
                  child: Icon(icon, color: colorScheme.onSecondaryContainer),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: const TextStyle(fontWeight: FontWeight.w700),
                      ),
                      const SizedBox(height: 3),
                      Text(
                        subtitle,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ),
                ),
                const Icon(Icons.chevron_right),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _settingsPanel() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('电脑服务地址', style: TextStyle(fontWeight: FontWeight.w700)),
            const SizedBox(height: 8),
            TextField(
              controller: _urlController,
              keyboardType: TextInputType.url,
              textInputAction: TextInputAction.go,
              onSubmitted: (_) => _saveAndCheck(),
              decoration: const InputDecoration(
                hintText: '例如 https://192.168.1.100:12400',
                border: OutlineInputBorder(),
                isDense: true,
              ),
            ),
            const SizedBox(height: 8),
            const Text(
              '手机与电脑需处于同一网络；release 版本使用 HTTPS，debug 版本可使用局域网 HTTP。',
              style: TextStyle(fontSize: 12),
            ),
            const SizedBox(height: 10),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton.icon(
                onPressed: _checkingService ? null : _saveAndCheck,
                icon: const Icon(Icons.wifi_find),
                label: const Text('保存并检测'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _errorPanel() {
    return MaterialBanner(
      content: Text(_errorMessage!),
      leading: const Icon(Icons.error_outline),
      actions: [
        TextButton(
          onPressed: () => setState(() => _errorMessage = null),
          child: const Text('关闭'),
        ),
      ],
    );
  }
}

class ConsolePage extends StatefulWidget {
  const ConsolePage({
    super.key,
    this.enableWebView = true,
    this.serviceUri,
    this.initialPath = '/',
  });

  final bool enableWebView;
  final Uri? serviceUri;
  final String initialPath;

  @override
  State<ConsolePage> createState() => _ConsolePageState();
}

class _ConsolePageState extends State<ConsolePage> {
  WebViewController? _webViewController;
  final _urlController = TextEditingController();
  Uri? _configuredUri;
  bool _ready = false;
  bool _showSettings = false;
  int _loadingProgress = 0;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    if (widget.enableWebView) {
      _webViewController = WebViewController()
        ..setJavaScriptMode(JavaScriptMode.unrestricted)
        ..setNavigationDelegate(
          NavigationDelegate(
            onNavigationRequest: (request) {
              final configured =
                  _configuredUri ?? parseServiceUrl(_urlController.text);
              final target = Uri.tryParse(request.url);
              if (configured != null &&
                  target != null &&
                  target.scheme == configured.scheme &&
                  target.host == configured.host &&
                  target.port == configured.port) {
                return NavigationDecision.navigate;
              }
              return NavigationDecision.prevent;
            },
            onProgress: (progress) {
              if (mounted) setState(() => _loadingProgress = progress);
            },
            onPageStarted: (_) {
              if (mounted) setState(() => _errorMessage = null);
            },
            onWebResourceError: (error) {
              if (mounted && (error.isForMainFrame ?? true)) {
                setState(() => _errorMessage = '无法连接到服务：${error.description}');
              }
            },
          ),
        );
      _loadSavedUrl();
    } else {
      _ready = true;
    }
  }

  Future<void> _loadSavedUrl() async {
    final preferences = await SharedPreferences.getInstance();
    final url =
        widget.serviceUri?.toString() ??
        preferences.getString(_serviceUrlKey) ??
        _defaultServiceUrl;
    final parsed = parseServiceUrl(url);
    _urlController.text = url;
    try {
      if (parsed == null) {
        throw FormatException('不支持的服务地址格式');
      }
      _configuredUri = parsed;
      await _webViewController!.loadRequest(
        _serviceEndpoint(parsed, widget.initialPath),
      );
      if (mounted) setState(() => _ready = true);
    } catch (error) {
      if (mounted) {
        setState(() {
          _ready = true;
          _errorMessage = '无法连接到服务：$error';
        });
      }
    }
  }

  Future<void> _saveAndLoad() async {
    final value = _urlController.text.trim();
    final uri = parseServiceUrl(value);
    if (uri == null) {
      setState(() => _errorMessage = '请输入完整的 HTTP/HTTPS 服务地址');
      return;
    }
    try {
      final preferences = await SharedPreferences.getInstance();
      await preferences.setString(_serviceUrlKey, uri.toString());
      _configuredUri = uri;
      await _webViewController!.loadRequest(
        _serviceEndpoint(uri, widget.initialPath),
      );
      if (mounted) setState(() => _showSettings = false);
    } catch (error) {
      if (mounted) setState(() => _errorMessage = '连接服务失败：$error');
    }
  }

  @override
  void dispose() {
    _urlController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('完整控制台'),
        actions: [
          IconButton(
            tooltip: '刷新',
            // enableWebView=false 时 _ready 为真但控制器为 null，守卫必须同时检查控制器
            onPressed: (_ready && _webViewController != null)
                ? () => _webViewController!.reload()
                : null,
            icon: const Icon(Icons.refresh),
          ),
          IconButton(
            tooltip: '服务设置',
            onPressed: () => setState(() => _showSettings = !_showSettings),
            icon: const Icon(Icons.settings_outlined),
          ),
        ],
        bottom: _loadingProgress < 100
            ? PreferredSize(
                preferredSize: const Size.fromHeight(2),
                child: LinearProgressIndicator(value: _loadingProgress / 100),
              )
            : null,
      ),
      body: Column(
        children: [
          if (_showSettings) _settingsPanel(),
          if (_errorMessage != null) _errorPanel(),
          Expanded(
            child: widget.enableWebView
                ? (_ready
                      ? WebViewWidget(controller: _webViewController!)
                      : const _LoadingView())
                : const SizedBox.shrink(),
          ),
        ],
      ),
    );
  }

  Widget _settingsPanel() {
    return Material(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('电脑服务地址', style: TextStyle(fontWeight: FontWeight.w600)),
            const SizedBox(height: 6),
            TextField(
              controller: _urlController,
              keyboardType: TextInputType.url,
              textInputAction: TextInputAction.go,
              onSubmitted: (_) => _saveAndLoad(),
              decoration: const InputDecoration(
                hintText: '例如 https://192.168.1.100:12400',
                border: OutlineInputBorder(),
                isDense: true,
              ),
            ),
            const SizedBox(height: 8),
            const Text(
              '手机与电脑需处于同一网络；服务在 release 需使用 HTTPS，debug 可使用本机或局域网 HTTP。',
              style: TextStyle(fontSize: 12),
            ),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton.icon(
                onPressed: _saveAndLoad,
                icon: const Icon(Icons.open_in_new),
                label: const Text('连接'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _errorPanel() {
    return MaterialBanner(
      content: Text(_errorMessage!),
      leading: const Icon(Icons.error_outline),
      actions: [
        TextButton(
          onPressed: () => setState(() => _errorMessage = null),
          child: const Text('关闭'),
        ),
      ],
    );
  }
}

class _LoadingView extends StatelessWidget {
  const _LoadingView();

  @override
  Widget build(BuildContext context) {
    return const Center(child: CircularProgressIndicator());
  }
}
