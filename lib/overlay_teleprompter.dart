import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' show PlatformDispatcher;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter_overlay_window/flutter_overlay_window.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import 'overlay_geometry.dart';
import 'script_store.dart';

/// Teleprompter flutuante: faixa estreita no centro, play/pausa, velocidade,
/// e scroll manual com o dedo a qualquer momento — durante a rolagem
/// automática o dedo assume, e ao soltar o auto-scroll retoma de onde parou.
class OverlayTeleprompter extends StatefulWidget {
  const OverlayTeleprompter({super.key});

  @override
  State<OverlayTeleprompter> createState() => _OverlayTeleprompterState();
}

class _OverlayTeleprompterState extends State<OverlayTeleprompter>
    with SingleTickerProviderStateMixin, WidgetsBindingObserver {
  final _scrollController = ScrollController();
  late Ticker _ticker;
  Duration _lastTick = Duration.zero;
  bool _skipNextDt = false;
  bool _userDragging = false;
  StreamSubscription? _overlaySub;
  bool? _lastLandscape;

  String _text = '';
  bool _playing = false;
  bool _loading = true;
  double _speed = 18;
  double _fontSize = 22;
  bool _controlsExpanded = true;

  // Posição/tamanho atuais da janela, em dp — mesma unidade que
  // moveOverlay/resizeOverlay esperam. Servem só pra acumular o arraste
  // das alças próprias (ver _buildMoveHandle/_buildResizeHandle);
  // continuam válidos entre reaberturas porque _resetGeometryState()
  // busca o valor real da janela toda vez que o roteiro é reenviado.
  double _dragX = 0;
  double _dragY = 0;
  double _boxWidth = 320;
  double _boxHeight = 520;
  Size _screenSize = const Size(360, 800);

  static const double _textMaxWidth = 280;
  static const double _minSpeed = 6;
  static const double _maxSpeed = 48;
  static const double _speedStep = 3;
  static const double _minBoxWidth = 220;
  static const double _minBoxHeight = 160;

  @override
  void initState() {
    super.initState();
    WakelockPlus.enable();
    WidgetsBinding.instance.addObserver(this);
    _ticker = createTicker(_onTick)..start();
    _load();
    // O FlutterEngine do overlay fica em cache e é reaproveitado entre
    // aberturas, então initState só roda na primeira vez. Nas próximas
    // vezes o app manda o roteiro atualizado por aqui.
    _overlaySub = FlutterOverlayWindow.overlayListener.listen(_onShareData);
    // Geometria já veio certa do main.dart na abertura — só guarda a
    // orientação atual pra comparar depois, sem reaplicar à toa.
    _lastLandscape = _currentLandscape();
    _resetGeometryState();
  }

  /// Sincroniza posição/tamanho locais com o que a janela nativa tem agora
  /// — chamado na primeira abertura e toda vez que o roteiro é reenviado
  /// (ou seja, toda reabertura), já que main.dart pode ter aplicado uma
  /// geometria nova (orientação mudou desde a última vez).
  Future<void> _resetGeometryState() async {
    final view = PlatformDispatcher.instance.views.first;
    final logicalSize = view.display.size / view.devicePixelRatio;
    final geometry = OverlayGeometry.forScreen(logicalSize);
    final pos = await FlutterOverlayWindow.getOverlayPosition();
    if (!mounted) return;
    setState(() {
      _screenSize = logicalSize;
      _boxWidth = logicalSize.width;
      _boxHeight = geometry.height.toDouble();
      _dragX = pos.x;
      _dragY = pos.y;
    });
  }

  bool _currentLandscape() {
    final view = PlatformDispatcher.instance.views.first;
    final logicalSize = view.display.size / view.devicePixelRatio;
    return logicalSize.width > logicalSize.height;
  }

  @override
  void didChangeMetrics() {
    super.didChangeMetrics();
    // didChangeMetrics também dispara por outros motivos (teclado, insets
    // de sistema), então só reage quando a orientação de fato virou.
    final landscape = _currentLandscape();
    if (landscape == _lastLandscape) return;
    _lastLandscape = landscape;

    final view = PlatformDispatcher.instance.views.first;
    final logicalSize = view.display.size / view.devicePixelRatio;
    final geometry = OverlayGeometry.forScreen(logicalSize);
    FlutterOverlayWindow.resizeOverlay(
      OverlayGeometry.width,
      geometry.height,
      false,
    );
    FlutterOverlayWindow.moveOverlay(geometry.position);
  }

  void _onShareData(dynamic message) {
    if (message is! Map) return;
    final text = message['text'];
    if (text is! String) return;
    if (!mounted) return;
    setState(() {
      _text = text.trim().isEmpty
          ? 'Cole um roteiro no app PromptCue e toque em Abrir teleprompter.'
          : text;
      _playing = false;
      _controlsExpanded = true;
    });
    if (_scrollController.hasClients) {
      _scrollController.jumpTo(0);
    }
    _resetGeometryState();
  }

  Future<void> _load() async {
    final text = await ScriptStore.loadText();
    final speed = await ScriptStore.loadSpeed();
    final font = await ScriptStore.loadFontSize();
    if (!mounted) return;
    setState(() {
      _text = text.trim().isEmpty
          ? 'Cole um roteiro no app PromptCue e toque em Abrir teleprompter.'
          : text;
      _speed = speed.clamp(_minSpeed, _maxSpeed);
      if (speed > _maxSpeed) _speed = 18;
      _fontSize = font;
      _loading = false;
    });
  }

  void _onTick(Duration elapsed) {
    if (!_playing || _userDragging || !_scrollController.hasClients) {
      _lastTick = elapsed;
      return;
    }
    if (_skipNextDt) {
      _lastTick = elapsed;
      _skipNextDt = false;
      return;
    }
    final dt = (elapsed - _lastTick).inMicroseconds / 1e6;
    _lastTick = elapsed;
    if (dt <= 0 || dt > 0.05) return;

    final maxScroll = _scrollController.position.maxScrollExtent;
    if (maxScroll <= 0) {
      setState(() => _playing = false);
      return;
    }

    final next = (_scrollController.offset + _speed * dt).clamp(0.0, maxScroll);
    _scrollController.jumpTo(next);
    if (next >= maxScroll - 0.5) {
      setState(() => _playing = false);
    }
  }

  void _togglePlay() {
    if (!_playing) {
      if (_scrollController.hasClients) {
        final max = _scrollController.position.maxScrollExtent;
        final atEnd = max <= 0 || _scrollController.offset >= max - 1;
        if (atEnd) {
          _scrollController.jumpTo(0);
        }
      }
      setState(() {
        _playing = true;
        _skipNextDt = true;
        _controlsExpanded = true;
      });
    } else {
      setState(() => _playing = false);
    }
  }

  Future<void> _changeSpeed(double delta) async {
    setState(() => _speed = (_speed + delta).clamp(_minSpeed, _maxSpeed));
    await ScriptStore.saveSpeed(_speed);
  }

  Future<void> _changeFont(double delta) async {
    setState(() => _fontSize = (_fontSize + delta).clamp(14, 40));
    await ScriptStore.saveFontSize(_fontSize);
  }

  Future<void> _closeOverlay() async {
    setState(() => _playing = false);
    await FlutterOverlayWindow.closeOverlay();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _overlaySub?.cancel();
    _ticker.dispose();
    _scrollController.dispose();
    WakelockPlus.disable();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Material(
        color: Colors.transparent,
        child: Center(
          child: SizedBox(
            width: 24,
            height: 24,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
        ),
      );
    }

    return Material(
      color: Colors.transparent,
      child: Stack(
        children: [
          Container(
            decoration: BoxDecoration(
              color: const Color(0xE603010A),
              borderRadius: BorderRadius.circular(14),
              border: Border.all(
                color: const Color(0xFF3D3D6B).withValues(alpha: 0.8),
              ),
              boxShadow: [
                BoxShadow(
                  color: const Color(0xFF1E90FF).withValues(alpha: 0.18),
                  blurRadius: 16,
                ),
              ],
            ),
            child: Column(
              children: [
                _buildControls(),
                Expanded(
                  child: LayoutBuilder(
                    builder: (context, constraints) {
                      final viewH = constraints.maxHeight;
                      // Topo grande: texto começa na linha de leitura.
                      // Fundo curto: para no último trecho — sem “vazio infinito”.
                      final topPad = math.max(viewH * 0.38, 64.0);
                      final bottomPad = math.max(viewH * 0.12, 36.0);
                      final width = math.min(
                        _textMaxWidth,
                        constraints.maxWidth,
                      );

                      return Align(
                        alignment: Alignment.topCenter,
                        child: SizedBox(
                          width: width,
                          height: viewH,
                          child: ShaderMask(
                            shaderCallback: (rect) {
                              return const LinearGradient(
                                begin: Alignment.topCenter,
                                end: Alignment.bottomCenter,
                                colors: [
                                  Colors.transparent,
                                  Colors.white,
                                  Colors.white,
                                  Colors.transparent,
                                ],
                                stops: [0.0, 0.10, 0.90, 1.0],
                              ).createShader(rect);
                            },
                            blendMode: BlendMode.dstIn,
                            child: ScrollConfiguration(
                              behavior: const _NoGlowScrollBehavior(),
                              // O dedo funciona mesmo com o auto-scroll rodando:
                              // um arraste do usuário (dragDetails != null) pausa
                              // o ticker e, ao soltar (fim da rolagem, incluindo a
                              // inércia do fling), o auto-scroll retoma dali.
                              // Os jumpTo() do ticker também emitem notificações,
                              // mas sempre sem dragDetails, então não interferem.
                              child: NotificationListener<ScrollNotification>(
                                onNotification: (notification) {
                                  if (notification is ScrollStartNotification &&
                                      notification.dragDetails != null) {
                                    _userDragging = true;
                                  } else if (notification
                                          is ScrollEndNotification &&
                                      _userDragging) {
                                    _userDragging = false;
                                    _skipNextDt = true;
                                  }
                                  return false;
                                },
                                child: SingleChildScrollView(
                                  controller: _scrollController,
                                  physics: const ClampingScrollPhysics(),
                                  padding: EdgeInsets.only(
                                    top: topPad,
                                    bottom: bottomPad,
                                    left: 10,
                                    right: 10,
                                  ),
                                  child: Text(
                                    _text,
                                    textAlign: TextAlign.center,
                                    style: TextStyle(
                                      color: const Color(0xFFE8EAF6),
                                      fontSize: _fontSize,
                                      height: 1.35,
                                      fontWeight: FontWeight.w600,
                                      shadows: const [
                                        Shadow(
                                          blurRadius: 8,
                                          color: Colors.black87,
                                        ),
                                      ],
                                    ),
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                      );
                    },
                  ),
                ),
              ],
            ),
          ),
          Positioned(right: 0, bottom: 0, child: _buildResizeHandle()),
        ],
      ),
    );
  }

  Widget _buildMoveHandle() {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onPanUpdate: (details) {
        _dragX += details.delta.dx;
        _dragY += details.delta.dy;
        FlutterOverlayWindow.moveOverlay(OverlayPosition(_dragX, _dragY));
      },
      child: const Padding(
        padding: EdgeInsets.symmetric(horizontal: 6),
        child: Icon(Icons.open_with, color: Color(0xFF9FA8DA), size: 20),
      ),
    );
  }

  Widget _buildResizeHandle() {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onPanUpdate: (details) {
        final maxWidth = _screenSize.width;
        final maxHeight = _screenSize.height * 0.9;
        _boxWidth = (_boxWidth + details.delta.dx).clamp(
          _minBoxWidth,
          maxWidth,
        );
        _boxHeight = (_boxHeight + details.delta.dy).clamp(
          _minBoxHeight,
          maxHeight,
        );
        FlutterOverlayWindow.resizeOverlay(
          _boxWidth.round(),
          _boxHeight.round(),
          false,
        );
      },
      child: Container(
        width: 28,
        height: 28,
        alignment: Alignment.bottomRight,
        padding: const EdgeInsets.all(4),
        child: const Icon(
          Icons.open_in_full,
          color: Color(0xFF9FA8DA),
          size: 16,
        ),
      ),
    );
  }

  Widget _buildControls() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 4),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: Color(0x403D3D6B))),
      ),
      child: _controlsExpanded
          ? Row(
              children: [
                _buildMoveHandle(),
                IconButton(
                  tooltip: _playing ? 'Pausar' : 'Play',
                  iconSize: 28,
                  color: _playing
                      ? const Color(0xFFB24BF3)
                      : const Color(0xFF1E90FF),
                  icon: Icon(_playing ? Icons.pause_circle : Icons.play_circle),
                  onPressed: _togglePlay,
                ),
                IconButton(
                  tooltip: 'Mais lento',
                  iconSize: 22,
                  color: const Color(0xFFE8EAF6),
                  icon: const Icon(Icons.remove),
                  onPressed: () => _changeSpeed(-_speedStep),
                ),
                Text(
                  '${_speed.round()}',
                  style: const TextStyle(
                    color: Color(0xFF9FA8DA),
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                IconButton(
                  tooltip: 'Mais rápido',
                  iconSize: 22,
                  color: const Color(0xFFE8EAF6),
                  icon: const Icon(Icons.add),
                  onPressed: () => _changeSpeed(_speedStep),
                ),
                IconButton(
                  tooltip: 'Fonte −',
                  iconSize: 18,
                  color: const Color(0xFF9FA8DA),
                  icon: const Icon(Icons.text_decrease),
                  onPressed: () => _changeFont(-2),
                ),
                IconButton(
                  tooltip: 'Fonte +',
                  iconSize: 18,
                  color: const Color(0xFF9FA8DA),
                  icon: const Icon(Icons.text_increase),
                  onPressed: () => _changeFont(2),
                ),
                const Spacer(),
                Text(
                  _playing ? 'rolando · dedo ajusta' : 'role o dedo',
                  style: TextStyle(
                    color: _playing
                        ? const Color(0xFF1E90FF)
                        : const Color(0xFF9FA8DA),
                    fontSize: 10,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                IconButton(
                  tooltip: 'Minimizar barra',
                  iconSize: 20,
                  color: const Color(0xFF9FA8DA),
                  icon: const Icon(Icons.keyboard_arrow_up),
                  onPressed: () => setState(() => _controlsExpanded = false),
                ),
                IconButton(
                  tooltip: 'Fechar overlay',
                  iconSize: 22,
                  color: const Color(0xFFE8EAF6),
                  icon: const Icon(Icons.close),
                  onPressed: _closeOverlay,
                ),
              ],
            )
          : SizedBox(
              height: 28,
              child: Center(
                child: IconButton(
                  tooltip: 'Mostrar controles',
                  icon: const Icon(
                    Icons.drag_handle,
                    color: Color(0xFF9FA8DA),
                    size: 20,
                  ),
                  onPressed: () => setState(() => _controlsExpanded = true),
                ),
              ),
            ),
    );
  }
}

class _NoGlowScrollBehavior extends ScrollBehavior {
  const _NoGlowScrollBehavior();

  @override
  Widget buildOverscrollIndicator(
    BuildContext context,
    Widget child,
    ScrollableDetails details,
  ) {
    return child;
  }
}
