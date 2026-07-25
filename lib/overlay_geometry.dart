import 'package:flutter_overlay_window/flutter_overlay_window.dart';

/// Geometria padrão da faixa do teleprompter.
///
/// Sempre centralizada na tela (nunca perto do topo/status bar) e com
/// altura pequena o bastante pra caber em qualquer aparelho, em pé ou
/// deitado — de propósito não depende de detectar a orientação, porque a
/// janela do overlay não tem como ler isso de forma confiável (é uma
/// janela de sistema, não a activity normal do app). Uma geometria única
/// que sirva pros dois casos evita esse problema por completo.
class OverlayGeometry {
  const OverlayGeometry({required this.height, required this.position});

  final int height;
  final OverlayPosition position;

  static const int width = WindowSize.matchParent;

  static const OverlayGeometry standard = OverlayGeometry(
    height: 340,
    position: OverlayPosition(0, 0),
  );
}
