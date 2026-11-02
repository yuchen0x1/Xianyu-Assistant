import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:webview_flutter/webview_flutter.dart';

const _defaultServiceUrl = kReleaseMode ? 'https://127.0.0.1:12400' : 'http://127.0.0.1:12400';
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
      home: ConsolePage(enableWebView: enableWebView),
    );
  }
}

class ConsolePage extends StatefulWidget {
  const ConsolePage({super.key, this.enableWebView = true});

  final bool enableWebView;

  @override
  State<ConsolePage> createState() => _ConsolePageState();
}

class _ConsolePageState extends State<ConsolePage> {
  WebViewController? _webViewController;
  final _urlController = TextEditingController();
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
              final configured = parseServiceUrl(_urlController.text);
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
    final url = preferences.getString(_serviceUrlKey) ?? _defaultServiceUrl;
    final parsed = parseServiceUrl(url);
    _urlController.text = url;
    try {
      if (parsed == null) {
        throw FormatException('不支持的服务地址格式');
      }
      await _webViewController!.loadRequest(parsed);
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
      await _webViewController!.loadRequest(uri);
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
        title: const Text('闲鱼卖家运营控制台'),
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
