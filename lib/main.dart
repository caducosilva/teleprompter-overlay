import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_overlay_window/flutter_overlay_window.dart';

import 'overlay_geometry.dart';
import 'overlay_teleprompter.dart';
import 'script_store.dart';
import 'services/update_service.dart';

const _appChannel = MethodChannel('com.abobicaduco.teleprompter_overlay/app');

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const PromptCueApp());
}

@pragma('vm:entry-point')
void overlayMain() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(
    const MaterialApp(
      debugShowCheckedModeBanner: false,
      home: OverlayTeleprompter(),
    ),
  );
}

class PromptCueApp extends StatelessWidget {
  const PromptCueApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'PromptCue',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF03010A),
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF1E90FF),
          secondary: Color(0xFFB24BF3),
          surface: Color(0xFF0F0F2E),
        ),
        inputDecorationTheme: InputDecorationTheme(
          filled: true,
          fillColor: const Color(0xFF0F0F2E),
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: const BorderSide(color: Color(0xFF3D3D6B)),
          ),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: const BorderSide(color: Color(0xFF3D3D6B)),
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: const BorderSide(color: Color(0xFF1E90FF)),
          ),
        ),
        useMaterial3: true,
      ),
      home: const HomeScreen(),
    );
  }
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _controller = TextEditingController();
  bool _busy = false;
  bool _overlayActive = false;

  @override
  void initState() {
    super.initState();
    _bootstrap();
  }

  Future<void> _bootstrap() async {
    final text = await ScriptStore.loadText();
    if (!mounted) return;
    _controller.text = text;
    final active = await FlutterOverlayWindow.isActive();
    if (!mounted) return;
    setState(() => _overlayActive = active);
    // Checa por atualização toda vez que a tela do app abre (no máximo 1x
    // por dia por versão — ver update_service.dart).
    unawaited(UpdateService.checkForUpdate(context));
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _ensureOverlayPermission() async {
    final granted = await FlutterOverlayWindow.isPermissionGranted();
    if (granted) return;
    await FlutterOverlayWindow.requestPermission();
  }

  Future<void> _closeAppUi() async {
    try {
      await _appChannel.invokeMethod<bool>('closeAppUi');
    } catch (_) {}
  }

  Future<void> _openOverlay() async {
    final text = _controller.text.trim();
    if (text.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Cole um roteiro antes de abrir.')),
      );
      return;
    }

    // Captura o tamanho da tela antes de qualquer 'await': depois disso o
    // 'context' pode não valer mais a pena consultar entre os gaps async.
    final screenSize = MediaQuery.of(context).size;

    setState(() => _busy = true);
    try {
      await ScriptStore.saveText(text);
      await _ensureOverlayPermission();

      final granted = await FlutterOverlayWindow.isPermissionGranted();
      if (!granted) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text(
                'Permissão de sobreposição negada. Ative em '
                'Configurações → Apps especiais → Aparecer sobre outros apps.',
              ),
            ),
          );
        }
        return;
      }

      if (await FlutterOverlayWindow.isActive()) {
        await FlutterOverlayWindow.closeOverlay();
        await Future<void>.delayed(const Duration(milliseconds: 300));
      }

      try {
        await _appChannel.invokeMethod('ensureQuietNotificationChannel');
      } catch (_) {}

      // Overlay ativo → Activity some. Só o teleprompter fica na tela.
      await ScriptStore.setStayInBackground(true);

      // Geometria calculada pra orientação atual (retrato ou paisagem) —
      // o overlay_teleprompter.dart reaplica isso sozinho se o celular
      // girar depois de aberto.
      final geometry = OverlayGeometry.forScreen(screenSize);

      await FlutterOverlayWindow.showOverlay(
        height: geometry.height,
        width: OverlayGeometry.width,
        alignment: OverlayAlignment.center,
        // Fixo sempre no mesmo lugar (drag desativado): perto da lente
        // frontal, pra parecer que olha pra quem assiste em vez de ficar
        // lendo de um canto.
        startPosition: geometry.position,
        enableDrag: false,
        flag: OverlayFlag.defaultFlag,
        overlayTitle: 'PromptCue',
        overlayContent: 'Teleprompter ativo',
        visibility: NotificationVisibility.visibilitySecret,
      );

      // O overlay reaproveita o mesmo FlutterEngine entre aberturas, então
      // ele só relê o SharedPreferences sozinho na primeiríssima vez. Nas
      // próximas, manda o roteiro atual direto pra ele não ficar com texto
      // velho.
      await FlutterOverlayWindow.shareData({'text': text});

      // Fecha a UI do app; o serviço do overlay continua sozinho.
      await Future<void>.delayed(const Duration(milliseconds: 150));
      await _closeAppUi();
    } catch (e) {
      await ScriptStore.setStayInBackground(false);
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('Falha ao abrir overlay: $e')));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _closeOverlay() async {
    setState(() => _busy = true);
    try {
      await ScriptStore.setStayInBackground(false);
      await FlutterOverlayWindow.closeOverlay();
      if (mounted) setState(() => _overlayActive = false);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('PromptCue'),
        backgroundColor: const Color(0xFF0A0A1F),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              'Cole o roteiro e toque em Abrir. O PromptCue fecha sozinho e '
              'deixa só a faixa do teleprompter por cima da câmera.',
              style: TextStyle(color: Color(0xFF9FA8DA), height: 1.35),
            ),
            const SizedBox(height: 14),
            Expanded(
              child: TextField(
                controller: _controller,
                maxLines: null,
                expands: true,
                textAlignVertical: TextAlignVertical.top,
                style: const TextStyle(color: Color(0xFFE8EAF6), fontSize: 16),
                decoration: const InputDecoration(
                  hintText: 'Cole ou digite o roteiro aqui…',
                  alignLabelWithHint: true,
                ),
              ),
            ),
            const SizedBox(height: 14),
            if (_overlayActive)
              OutlinedButton.icon(
                onPressed: _busy ? null : _closeOverlay,
                icon: const Icon(Icons.close),
                label: const Text('Fechar teleprompter'),
              ),
            const SizedBox(height: 8),
            FilledButton.icon(
              onPressed: _busy ? null : _openOverlay,
              icon: _busy
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.open_in_new),
              label: Text(
                _overlayActive ? 'Reabrir teleprompter' : 'Abrir teleprompter',
              ),
              style: FilledButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 14),
                backgroundColor: const Color(0xFF1E90FF),
              ),
            ),
            const SizedBox(height: 8),
            const Text(
              'Ao abrir, o app some e só o teleprompter fica na tela. '
              'Abra a câmera → play. Pra editar o roteiro, toque de novo '
              'no ícone PromptCue.',
              textAlign: TextAlign.center,
              style: TextStyle(color: Color(0xFF9FA8DA), fontSize: 12),
            ),
          ],
        ),
      ),
    );
  }
}
