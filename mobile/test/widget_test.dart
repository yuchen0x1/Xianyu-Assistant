import 'package:flutter_test/flutter_test.dart';
import 'package:xianyu_seller_mobile/main.dart';

void main() {
  test('service address parser handles debug and release modes', () {
    expect(
      parseServiceUrl('192.168.1.100:12400', releaseMode: false)!.scheme,
      'http',
    );
    expect(
      parseServiceUrl('192.168.1.100:12400', releaseMode: true)!.scheme,
      'https',
    );
    expect(parseServiceUrl('http://192.168.1.100', releaseMode: true), isNull);
    expect(parseServiceUrl('https://console.example.com')!.scheme, 'https');
    expect(parseServiceUrl('ftp://192.168.1.100'), isNull);
  });

  testWidgets('console client renders settings entry', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const XianyuSellerApp(enableWebView: false));

    expect(find.text('闲鱼卖家运营控制台'), findsOneWidget);
    expect(find.text('移动工作台'), findsOneWidget);
    expect(find.text('待办中心'), findsOneWidget);
    expect(find.byTooltip('服务设置'), findsOneWidget);
  });
}
